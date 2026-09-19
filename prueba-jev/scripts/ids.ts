/**
 * List the embedded messages with their ids, to feed `search.ts --like`.
 *
 *   bun scripts/ids.ts                 # los 30 más recientes
 *   bun scripts/ids.ts spotify         # los que mencionen "spotify"
 *   bun scripts/ids.ts --all -n 100    # incluye los no embebidos
 */
import { labClient, remoteClient, TABLE, type Kind } from '../lib/db';
import { decodeMimeHeader } from '../lib/text';

const flag = (name: string) => process.argv.includes(`--${name}`);
const arg = (name: string, fallback?: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? (process.argv[i + 1] ?? fallback) : fallback;
};
const nIndex = Math.max(process.argv.indexOf('-n'), process.argv.indexOf('--n'));
const LIMIT = Number(nIndex > 0 ? (process.argv[nIndex + 1] ?? '30') : '30');

const needle = process.argv.slice(2).find((a, i, all) => {
  const prev = i > 0 ? all[i - 1] : undefined;
  return !a.startsWith('-') && !(prev ?? '').startsWith('-');
});

async function main() {
  const db = flag('remote') ? await remoteClient(arg('db')) : labClient();
  const where = [flag('all') ? '1 = 1' : 'embedding is not null'];
  const args: unknown[] = [];
  if (needle) {
    where.push('(lower(TITLE) like ? or lower(BODY) like ?)');
    args.push(`%${needle.toLowerCase()}%`, `%${needle.toLowerCase()}%`);
  }

  const rows: Array<{ kind: Kind; id: string; title: string; body: string; date: number; score: number | null }> = [];
  for (const [kind, table] of Object.entries(TABLE) as Array<[Kind, string]>) {
    const mail = kind === 'email';
    const title = mail ? 'subject' : 'title';
    const body = mail ? "coalesce(body_text, '')" : 'text';
    const date = mail ? 'received_at' : 'post_time';
    const rs = await db.execute({
      sql: `select id, ${title} as title, substr(${body}, 1, 60) as body, ${date} as date, jev_score
            from ${table}
            where ${where.join(' and ').replaceAll('TITLE', title).replaceAll('BODY', body)}
            order by ${date} desc limit ?`,
      args: [...args, LIMIT] as never,
    });
    for (const r of rs.rows) {
      rows.push({
        kind,
        id: String(r.id),
        title: mail ? decodeMimeHeader(String(r.title ?? '')) : String(r.title ?? ''),
        body: String(r.body ?? '').replace(/\s+/g, ' '),
        date: Number(r.date),
        score: r.jev_score == null ? null : Number(r.jev_score),
      });
    }
  }

  for (const r of rows.sort((a, b) => b.date - a.date).slice(0, LIMIT)) {
    // Eight characters is enough to be unique here and short enough to paste;
    // `--like` resolves prefixes.
    const when = new Date(r.date).toISOString().slice(0, 10);
    const score = r.score == null ? '   –' : r.score.toFixed(2);
    console.log(`${r.id.slice(0, 8)}  ${when}  ${score}  [${r.kind[0]}] ${r.title.slice(0, 40).padEnd(40)} ${r.body.slice(0, 50)}`);
  }
  db.close();
}

await main();
