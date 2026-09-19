/**
 * Two-step filter over `data/lab.db`:
 *
 *   1. money regex — local, free, deterministic, not persisted (it is cheap to
 *      recompute; what costs money is step 2).
 *   2. Jev — `client.systemOne` with a `noul`, one call per surviving message,
 *      score checkpointed per row so a run can be cut and resumed.
 *
 *   bun scripts/classify.ts [--limit N] [--threshold 0.6] [--dry-run] [--rescore]
 *
 * The raw score is stored, never just the verdict: moving the threshold is
 * then an `update`, not 166 calls again.
 */
import { noul, TypeSafeClient } from '@typesafe-ai/sdk';
import { labClient, ensureLabSchema, loadMessages, TABLE, canonicalText } from '../lib/db';
import { allowlisted } from '../lib/allowlist';
import { ANY_DIGIT, hasAmount, type Message } from '../lib/text';

const flag = (name: string) => process.argv.includes(`--${name}`);
const arg = (name: string, fallback: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? (process.argv[i + 1] ?? fallback) : fallback;
};

const THRESHOLD = Number(arg('threshold', '0.6'));
const CONCURRENCY = 8;
const REPORT = new URL('../data/report.md', import.meta.url).pathname;

const client = new TypeSafeClient();

/** The state Jev sees. Shaped per kind because a package name is not a sender
 * address and pretending they are the same hides what the model is judging. */
function state(m: Message): Record<string, unknown> {
  if (m.kind === 'notification') {
    return {
      notification: {
        application: m.originLabel,
        title: m.title,
        text: m.body.slice(0, 1500),
        category: m.category,
      },
    };
  }
  return { email: { from: m.origin, subject: m.title, body: m.body.slice(0, 1500) } };
}

async function score(m: Message): Promise<number> {
  const res = await client.systemOne({
    state: state(m) as never,
    questions: {
      isTransaction: noul(
        m.kind === 'notification'
          ? 'Does this push notification report a financial transaction that already happened on the recipient\'s own money (a payment, charge, transfer or deposit) — as opposed to a promotion, reminder, offer or account update?'
          : 'Does this email report a financial transaction that already happened on the recipient\'s own money (a payment, charge, transfer or deposit) — as opposed to a promotion, newsletter, reminder or statement summary?',
      ),
    },
  });
  return res.answers.isTransaction.noul;
}

/** Bounded parallelism with retry: the API is one call per message. */
async function pool<T>(items: T[], n: number, fn: (item: T, i: number) => Promise<void>) {
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(n, items.length) }, async () => {
      for (;;) {
        const i = next++;
        if (i >= items.length) return;
        for (let attempt = 0; ; attempt++) {
          try {
            await fn(items[i]!, i);
            break;
          } catch (e) {
            if (attempt >= 3) {
              console.error(`\n  ! ${String(e).slice(0, 120)}`);
              break;
            }
            await Bun.sleep(500 * 2 ** attempt);
          }
        }
      }
    }),
  );
}

async function main() {
  const db = labClient();
  await ensureLabSchema(db);

  const all = await loadMessages(db);
  const withMoney = all.filter((m) => hasAmount(`${m.title} ${m.body}`));
  const digitsOnly = all.filter(
    (m) => !hasAmount(`${m.title} ${m.body}`) && ANY_DIGIT.test(`${m.title} ${m.body}`),
  );
  console.log(`mensajes: ${all.length} | con moneda: ${withMoney.length} | solo dígitos: ${digitsOnly.length}`);

  // Already-scored rows are skipped: this is the resume point.
  const done = new Map<string, number>();
  for (const table of ['notifications', 'emails']) {
    const rs = await db.execute(`select id, jev_score from ${table} where classified_at is not null`);
    for (const r of rs.rows) done.set(String(r.id), Number(r.jev_score));
  }

  const pending = flag('rescore') ? withMoney : withMoney.filter((m) => !done.has(m.id));
  const limit = Number(arg('limit', String(pending.length)));
  const todo = pending.slice(0, limit);

  if (flag('dry-run')) {
    console.log(`--dry-run: haría ${todo.length} llamadas a Jev (${done.size} ya clasificados)`);
    db.close();
    return;
  }

  let n = 0;
  await pool(todo, CONCURRENCY, async (m) => {
    const value = await score(m);
    done.set(m.id, value);
    await db.execute({
      sql: `update ${TABLE[m.kind]} set jev_score = ?, is_movement = ?, classified_at = ? where id = ?`,
      args: [value, value >= THRESHOLD ? 1 : 0, Date.now(), m.id],
    });
    process.stdout.write(`\rjev: ${++n}/${todo.length}`);
  });
  console.log('');

  // Re-apply the threshold over every stored score, so `--threshold` alone is
  // a full re-run without a single call.
  for (const table of ['notifications', 'emails']) {
    await db.execute({
      sql: `update ${table} set is_movement = case when jev_score >= ? then 1 else 0 end
            where jev_score is not null`,
      args: [THRESHOLD],
    });
  }

  await writeReport(db, all, withMoney, digitsOnly, done);
  console.log(`reporte: ${REPORT}`);
  db.close();
}

async function writeReport(
  db: ReturnType<typeof labClient>,
  all: Message[],
  withMoney: Message[],
  digitsOnly: Message[],
  scores: Map<string, number>,
) {
  const scored = withMoney.filter((m) => scores.has(m.id));
  const truth = new Map(all.map((m) => [m.id, allowlisted(m)]));
  const lines: string[] = [];

  lines.push('# Reporte de clasificación\n');
  lines.push(`Generado: ${new Date().toISOString()}\n`);
  lines.push('| | n |');
  lines.push('| --- | --- |');
  lines.push(`| mensajes en \`lab.db\` | ${all.length} |`);
  lines.push(`| con símbolo de moneda (paso 1) | ${withMoney.length} |`);
  lines.push(`| con dígitos pero sin moneda (descartados) | ${digitsOnly.length} |`);
  lines.push(`| puntuados por Jev | ${scored.length} |`);
  lines.push(`| reconocidos por el allowlist de \`Ingest.kt\` | ${all.filter((m) => truth.get(m.id)).length} |\n`);

  lines.push('## Histograma de `jev_score`\n');
  const buckets = new Array(10).fill(0);
  for (const m of scored) buckets[Math.min(9, Math.floor((scores.get(m.id) ?? 0) * 10))]!++;
  lines.push('| rango | n | |');
  lines.push('| --- | --- | --- |');
  buckets.forEach((count, i) => {
    lines.push(`| ${(i / 10).toFixed(1)}–${((i + 1) / 10).toFixed(1)} | ${count} | ${'█'.repeat(count)} |`);
  });

  lines.push('\n## Umbral vs. allowlist\n');
  lines.push('`recuperados` = movimientos del allowlist que Jev también marca; `nuevos` = los que Jev marca y el allowlist no (lo interesante).\n');
  lines.push('| umbral | marcados | recuperados / total allowlist | nuevos |');
  lines.push('| --- | --- | --- | --- |');
  const truthTotal = scored.filter((m) => truth.get(m.id)).length;
  for (const t of [0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]) {
    const hit = scored.filter((m) => (scores.get(m.id) ?? 0) >= t);
    const recovered = hit.filter((m) => truth.get(m.id)).length;
    lines.push(`| ${t.toFixed(1)} | ${hit.length} | ${recovered} / ${truthTotal} | ${hit.length - recovered} |`);
  }

  const missed = scored
    .filter((m) => truth.get(m.id) && (scores.get(m.id) ?? 0) < 0.5)
    .slice(0, 20);
  if (missed.length) {
    lines.push('\n## Falsos negativos (allowlist sí, Jev < 0.5)\n');
    for (const m of missed) lines.push(`- \`${scores.get(m.id)?.toFixed(2)}\` ${sample(m)}`);
  }

  lines.push('\n## Candidatos nuevos mejor puntuados (allowlist no los ve)\n');
  const news = scored
    .filter((m) => !truth.get(m.id))
    .sort((a, b) => (scores.get(b.id) ?? 0) - (scores.get(a.id) ?? 0))
    .slice(0, 20);
  for (const m of news) lines.push(`- \`${scores.get(m.id)?.toFixed(2)}\` ${sample(m)}`);

  lines.push('\n## Muestra de lo descartado en el paso 1 (dígitos, sin moneda)\n');
  lines.push('Si acá aparecen movimientos, el prefiltro de moneda es demasiado estrecho.\n');
  for (const m of pickEvenly(digitsOnly, 20)) lines.push(`- ${sample(m)}`);

  await Bun.write(REPORT, lines.join('\n'));
}

function sample(m: Message): string {
  const body = m.body.replace(/\s+/g, ' ').slice(0, 120);
  return `**${m.originLabel}** — ${m.title.slice(0, 80)} · ${body}`;
}

/** Spread the sample over the whole set: the first 20 rows of a table sorted
 * by nothing in particular are 20 rows from the same app. */
function pickEvenly<T>(items: T[], n: number): T[] {
  if (items.length <= n) return items;
  const step = items.length / n;
  return Array.from({ length: n }, (_, i) => items[Math.floor(i * step)]!);
}

await main();
