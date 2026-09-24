/**
 * Which model should read a captured notification?
 *
 * The reader is stage 1 of the notification→transaction path (see
 * `plans/notificacion-a-transaccion.md`): it turns "Pagaste $ 21.389 a Rappi"
 * into an amount, a direction, a payee and a plain sentence to embed. It is
 * the slowest step and the only one billed per token, so it is worth knowing
 * what the alternatives cost and whether they are any good.
 *
 *   bun scripts/readers.ts --cases        # write data/readers.cases.json from lab.db
 *   bun scripts/readers.ts [--runs 3] [--only glm,kimi]
 *   bun scripts/readers.ts --jev          # Jev's latency, for scale
 *
 * **The prompt comes from the worker** (`app/worker/src/message.ts`), not a
 * copy of it: a harness that drifts from what ships measures the harness.
 * That is the same rule `scripts/chat.ts` follows for the Jev questions.
 *
 * Everything except the Gemini baseline goes through Cloudflare's unified
 * endpoint (`/ai/v1/chat/completions`), billed against the account's AI
 * Gateway credits, so one token reaches Workers AI models and Google alike.
 */
import { labClient, loadMessages } from '../lib/db';
import { hasAmount } from '../lib/text';
import { ANSWER_KEYS, messagePrompt } from '../../app/worker/src/message';

const CASES_PATH = new URL('../data/readers.cases.json', import.meta.url).pathname;
const REPORT = new URL('../data/readers.md', import.meta.url).pathname;
const ACCOUNT = process.env.CLOUDFLARE_ACCOUNT_ID ?? 'f12da7851e4dd1d107a80417a1d4cbbd';
const GATEWAY = process.env.CLOUDFLARE_AI_GATEWAY ?? 'finance';
const NO_THINK = process.argv.includes('--no-think');

const flag = (name: string) => process.argv.includes(`--${name}`);
const arg = (name: string, fallback: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? (process.argv[i + 1] ?? fallback) : fallback;
};

/**
 * Per-million-token prices, read off the model pages on 2026-09-20. Input
 * dominates here: the prompt carries the account list (~370 tokens) and the
 * answer is ~80.
 */
interface ModelSpec {
  id: string;
  label: string;
  /** USD per million input / output tokens. */
  in: number;
  out: number;
  /** Straight at Google with GEMINI_API_KEY, i.e. what ships today. */
  direct?: boolean;
}

const MODELS: ModelSpec[] = [
  { id: 'gemini-3.5-flash-lite', label: 'gemini-3.5-flash-lite (directo)', in: 0.1, out: 0.4, direct: true },
  { id: 'google/gemini-3.5-flash-lite', label: 'gemini-3.5-flash-lite (cf)', in: 0.1, out: 0.4 },
  { id: 'google/gemini-3.1-flash-lite', label: 'gemini-3.1-flash-lite (cf)', in: 0.1, out: 0.4 },
  { id: 'google/gemini-2.5-flash-lite', label: 'gemini-2.5-flash-lite (cf)', in: 0.1, out: 0.4 },
  { id: '@cf/moonshotai/kimi-k2.6', label: 'kimi-k2.6', in: 0.95, out: 4.0 },
  { id: '@cf/zai-org/glm-5.3-flash', label: 'glm-5.3-flash', in: 0.15, out: 0.5 },
  { id: '@cf/zai-org/glm-4.7-flash', label: 'glm-4.7-flash', in: 0.0605, out: 0.4 },
  { id: '@cf/deepseek-ai/deepseek-v4-flash-0731', label: 'deepseek-v4-flash', in: 0.44, out: 1.32 },
];

/** What the reader is supposed to say about one message. */
interface Case {
  id: string;
  origin: string;
  title: string;
  text: string;
  when: number;
  /** 'notification' (default) or 'email'; the prompt describes them differently. */
  kind?: 'notification' | 'email';
  /** The profile name the app would send, when the case depends on it. */
  userName?: string;
  /**
   * Own account paths to use instead of [ACCOUNT_PATHS], to prove a rule does not
   * lean on one user's names (a cash account called something else).
   */
  accounts?: string[];
  expect: {
    isMovement: boolean;
    direction?: 'expense' | 'income' | 'transfer';
    /** Minor units, as the app would store them. */
    amountMinor?: number;
    commodity?: string;
    /** Lowercased substring the payee must contain. */
    payee?: string;
    /** The user's own account, verbatim, or null when the alert implies none. */
    account?: string | null;
    /** For a transfer, the own account the money landed in. */
    destination?: string | null;
  };
}

/** The tree the reader is given. Real shape, from the dev device. */
const ACCOUNT_PATHS = [
  'ARQ', 'Chase', 'Efectivo', 'Galicia Dolares', 'Galicia Pesos',
  'Interactive Brokers', 'Invertironline', 'Mercado Pago', 'Por Cobrar',
  'Santander Dolares', 'Santander Pesos', 'Santander Crédito',
];

function accountsFor(paths: string[]) {
  return paths.map((path, i) => ({
    id: `acc-${i}`,
    path,
    type: (path === 'Santander Crédito' ? 'liability' : 'asset') as 'asset' | 'liability',
    commodity: /Dolares|Chase|Interactive/.test(path) ? 'USD' : 'ARS',
  }));
}

// --------------------------------------------------------------- the cases

/**
 * Drafts a case file from real captures: messages with money in them, spread
 * across apps, plus promotions as negatives. The expectations are left
 * **blank on purpose** — they are filled in by hand, because a truth derived
 * from the same regex the models are being measured against is not a truth.
 */
async function writeCases() {
  const db = labClient();
  const all = await loadMessages(db, { kind: 'notification' });
  db.close();

  const money = all.filter((m) => hasAmount(`${m.title} ${m.body}`));
  const byApp = new Map<string, typeof money>();
  for (const m of money) {
    const list = byApp.get(m.originLabel) ?? [];
    if (list.length < 2) list.push(m);
    byApp.set(m.originLabel, list);
  }
  const picked = [...byApp.values()].flat().slice(0, 16);

  const cases: Case[] = picked.map((m) => ({
    id: m.id,
    origin: m.originLabel,
    title: m.title,
    text: m.body,
    when: m.date,
    expect: { isMovement: true },
  }));
  await Bun.write(CASES_PATH, JSON.stringify(cases, null, 2));
  console.log(`${cases.length} casos en ${CASES_PATH}`);
  console.log('Completá `expect` a mano (direction, amountMinor, payee, account) antes de correr el bench.');
}

// -------------------------------------------------------------- the readers

interface Reading {
  isMovement?: boolean;
  direction?: string;
  payee?: string;
  amount?: string | number;
  commodity?: string;
  account?: string | null;
  destination?: string | null;
  normalized?: string;
}

interface Answer {
  reading: Reading | null;
  ms: number;
  inTokens: number;
  outTokens: number;
  /** How much the model thought out loud before answering, in characters. */
  thoughtChars?: number;
  error?: string;
}

function promptFor(c: Case): string {
  return `${messagePrompt({
    origin: c.origin,
    title: c.title,
    text: c.text,
    when: c.when,
    accounts: accountsFor(c.accounts ?? ACCOUNT_PATHS),
    kind: c.kind ?? 'notification',
    userName: c.userName,
  })}\n\n${ANSWER_KEYS}`;
}

/** Outermost braces of whatever the model wrapped the object in. */
function extract(text: string): Reading | null {
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(text.slice(start, end + 1)) as Reading;
  } catch {
    return null;
  }
}

/**
 * Unified Billing has its own rate limit (`code 2018`, "wholesale rate
 * limit"), and a 429 counted as a wrong answer would make a model look bad
 * for the gateway's reasons: the first pass scored gemini-3.5-flash-lite at
 * 69% on exactly those. Waited out, not counted.
 */
async function askCloudflare(model: string, prompt: string, attempt = 0): Promise<Answer> {
  const started = Date.now();
  const token = process.env.CLOUDFLARE_API_TOKEN;
  const res = await fetch(`https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/ai/v1/chat/completions`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      // Workers AI models answer "not available on the Workers Free plan"
      // unless the request names a gateway whose Workers AI billing is set to
      // Unified: that is what spends the prepaid credits instead of the plan.
      // Third-party models (Google here) bill against the credits without it.
      ...(GATEWAY ? { 'cf-aig-gateway-id': GATEWAY } : {}),
    },
    body: JSON.stringify({
      model,
      messages: [{ role: 'user', content: prompt }],
      max_tokens: 1200,
      // --no-think: every one of these models thinks out loud before it
      // answers, and that is where their seconds go. There is no portable
      // switch — each family spells it differently and the unified endpoint
      // forwards what it does not recognize — so all three spellings go in
      // together and the bench says whether any of them took.
      ...(NO_THINK
        ? {
            thinking: { type: 'disabled' },
            chat_template_kwargs: { thinking: false },
            reasoning_effort: 'low',
          }
        : {}),
    }),
  });
  const ms = Date.now() - started;
  const body = (await res.json()) as {
    choices?: Array<{ message?: { content?: string } }>;
    usage?: { prompt_tokens?: number; completion_tokens?: number };
    errors?: unknown;
  };
  if (!res.ok) {
    const text = JSON.stringify(body);
    const throttled = res.status === 429 || text.includes('2018') || text.includes('rate limit');
    if (throttled && attempt < 5) {
      await Bun.sleep(2_000 * (attempt + 1));
      return askCloudflare(model, prompt, attempt + 1);
    }
    return { reading: null, ms, inTokens: 0, outTokens: 0, error: text.slice(0, 200) };
  }
  return {
    reading: extract(body.choices?.[0]?.message?.content ?? ''),
    ms,
    inTokens: body.usage?.prompt_tokens ?? 0,
    outTokens: body.usage?.completion_tokens ?? 0,
    thoughtChars:
      (body.choices?.[0]?.message as { reasoning_content?: string } | undefined)?.reasoning_content
        ?.length ?? 0,
  };
}

/** The baseline: Google directly, with structured output, exactly as the worker does. */
async function askGoogle(model: string, prompt: string): Promise<Answer> {
  const started = Date.now();
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'x-goog-api-key': process.env.GEMINI_API_KEY ?? '', 'content-type': 'application/json' },
    body: JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { responseMimeType: 'application/json', temperature: 0.1 },
    }),
  });
  const ms = Date.now() - started;
  const body = (await res.json()) as {
    candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
    usageMetadata?: { promptTokenCount?: number; candidatesTokenCount?: number };
  };
  if (!res.ok) return { reading: null, ms, inTokens: 0, outTokens: 0, error: JSON.stringify(body).slice(0, 200) };
  return {
    reading: extract(body.candidates?.[0]?.content?.parts?.map((p) => p.text ?? '').join('') ?? ''),
    ms,
    inTokens: body.usageMetadata?.promptTokenCount ?? 0,
    outTokens: body.usageMetadata?.candidatesTokenCount ?? 0,
  };
}

// --------------------------------------------------------------- the scoring

/** "21389.00" | 21389 → 2138900 minor units. */
function toMinor(amount: string | number | undefined): number | null {
  if (amount == null) return null;
  const text = String(amount).trim().replace(/[^0-9.]/g, '');
  if (!text) return null;
  const value = Number(text);
  return Number.isFinite(value) ? Math.round(value * 100) : null;
}

interface Score {
  movement: boolean;
  direction: boolean;
  amount: boolean;
  payee: boolean;
  account: boolean;
  destination: boolean;
  /** The embedded sentence must carry no digits: an amount in it is noise. */
  cleanNormalized: boolean;
  parsed: boolean;
}

function score(c: Case, reading: Reading | null): Score {
  const empty = {
    movement: false, direction: false, amount: false, payee: false,
    account: false, destination: false, cleanNormalized: false, parsed: false,
  };
  if (!reading) return empty;
  const e = c.expect;
  return {
    parsed: true,
    movement: reading.isMovement === e.isMovement,
    direction: e.direction == null ? true : reading.direction === e.direction,
    amount: e.amountMinor == null ? true : toMinor(reading.amount) === e.amountMinor,
    payee: e.payee == null ? true : (reading.payee ?? '').toLowerCase().includes(e.payee),
    account: e.account === undefined ? true : (reading.account ?? null) === e.account,
    destination: e.destination === undefined ? true : (reading.destination ?? null) === e.destination,
    cleanNormalized: !/[0-9]/.test(reading.normalized ?? ''),
  };
}

// ------------------------------------------------------------------ the run

function percentile(values: number[], p: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * p))]!;
}

async function main() {
  if (flag('cases')) return writeCases();
  if (flag('jev')) return measureJev();

  // --case <needle>: only the cases whose id contains it, to iterate on one
  // rule without paying for the whole table.
  const needle = arg('case', '');
  const cases = ((await Bun.file(CASES_PATH).json()) as Case[]).filter((c) => !needle || c.id.includes(needle));
  const runs = Number(arg('runs', '2'));
  const only = arg('only', '');
  const models = only
    ? MODELS.filter((m) => only.split(',').some((needle) => m.id.includes(needle.trim())))
    : MODELS;

  console.log(`${cases.length} casos × ${runs} corridas × ${models.length} modelos`);
  const rows: string[] = [];
  rows.push('# Lectores comparados\n');
  rows.push(`Generado: ${new Date().toISOString()} · ${cases.length} notificaciones × ${runs} corridas\n`);
  rows.push('| modelo | p50 | p90 | ok | mov | dir | monto | payee | cuenta | destino | normalized sin dígitos | USD/1k | pensamiento |');
  rows.push('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |');

  for (const model of models) {
    const latencies: number[] = [];
    const totals = { movement: 0, direction: 0, amount: 0, payee: 0, account: 0, destination: 0, cleanNormalized: 0, parsed: 0 };
    let thoughts = 0;
    let inTokens = 0;
    let outTokens = 0;
    let n = 0;
    let firstError: string | undefined;

    for (let run = 0; run < runs; run++) {
      for (const c of cases) {
        const prompt = promptFor(c);
        let answer: Answer;
        try {
          answer = model.direct ? await askGoogle(model.id, prompt) : await askCloudflare(model.id, prompt);
        } catch (e) {
          answer = { reading: null, ms: 0, inTokens: 0, outTokens: 0, error: String(e).slice(0, 200) };
        }
        if (answer.error && !firstError) firstError = answer.error;
        if (answer.ms > 0) latencies.push(answer.ms);
        inTokens += answer.inTokens;
        thoughts += answer.thoughtChars ?? 0;
        outTokens += answer.outTokens;
        const s = score(c, answer.reading);
        for (const key of Object.keys(totals) as Array<keyof typeof totals>) {
          if (s[key]) totals[key]++;
        }
        // Which case missed what: a percentage says a rule is off, not where.
        const missed = (Object.keys(totals) as Array<keyof typeof totals>)
          .filter((key) => key !== 'cleanNormalized' && !s[key]);
        if (missed.length > 0) {
          console.log(`\n  ✗ ${c.id} [${missed.join(', ')}] ${JSON.stringify(answer.reading)}`);
        }
        n++;
        process.stdout.write(`\r${model.label}: ${n}/${cases.length * runs}   `);
      }
    }
    console.log('');
    if (firstError) console.log(`  ! ${firstError}`);

    // Per 1.000 messages, which is the unit that means something: the dev
    // device captured 22 movements in a year of notifications.
    const usdPerThousand = ((inTokens / n) * model.in + (outTokens / n) * model.out) / 1000;
    const pct = (value: number) => `${Math.round((value / n) * 100)}%`;
    rows.push(
      `| ${model.label} | ${percentile(latencies, 0.5)} ms | ${percentile(latencies, 0.9)} ms | ` +
        `${pct(totals.parsed)} | ${pct(totals.movement)} | ${pct(totals.direction)} | ${pct(totals.amount)} | ` +
        `${pct(totals.payee)} | ${pct(totals.account)} | ${pct(totals.destination)} | ${pct(totals.cleanNormalized)} | ` +
        `$${usdPerThousand.toFixed(3)} | ${Math.round(thoughts / n)} |`
    );
  }

  rows.push('\n`ok` = devolvió un JSON parseable. Las demás columnas son sobre el total de corridas.');
  rows.push('`USD/1k` = costo de leer mil notificaciones, a los precios de los model pages (2026-09-20).\n');
  await Bun.write(REPORT, rows.join('\n'));
  console.log(rows.join('\n'));
  console.log(`\nreporte: ${REPORT}`);
}

/**
 * Jev's own latency, for scale. It is **not** in the table: it answers a
 * different question (a Choice over the user's accounts, stage 3), and
 * TypeSafe is not one of AI Gateway's providers, so it cannot be routed
 * through Cloudflare either. The number is here because the two run in the
 * same request path and the comparison people ask for is "is Jev the slow
 * part?" — it is not.
 */
async function measureJev() {
  const { TypeSafeClient, choice } = await import('@typesafe-ai/sdk');
  const client = new TypeSafeClient();
  const criteria: Record<string, string | null> = {};
  for (const account of accountsFor(ACCOUNT_PATHS)) criteria[account.path] = null;
  criteria['Ninguna de estas cuentas'] = 'No way to tell which one';

  const latencies: number[] = [];
  for (let i = 0; i < 5; i++) {
    const started = Date.now();
    await client.systemOne({
      state: { message: { origin: 'Mercado Pago', title: 'Pagaste $ 21.389 a Rappi' } } as never,
      questions: {
        account: choice(
          'Which account of the person who received this notification did the money leave from?',
          criteria,
        ),
      },
    });
    latencies.push(Date.now() - started);
  }
  console.log(`jev: p50 ${percentile(latencies, 0.5)} ms   (${latencies.join(', ')})`);
}

await main();
