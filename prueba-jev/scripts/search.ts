/**
 * Similarity search over the embedded messages.
 *
 *   bun scripts/search.ts "supermercado en pesos" [-k 10] [--kind notification]
 *   bun scripts/search.ts --like <messageId>   # el id acepta prefijos (ver ids.ts)
 *   bun scripts/search.ts "…" --remote        (against the user's real DB)
 *
 * Exact scan (`order by vector_distance_cos(...) limit k`), no DiskANN index:
 * the sync engine on the phone has the distance functions but not
 * `vector_top_k`/`libsql_vector_idx`, so this is the one query shape that runs
 * identically on the server and offline on the device. The elapsed time is
 * printed because it is the number that decides whether anything smarter is
 * ever needed.
 */
import type { Client } from '@libsql/client';
import { labClient, remoteClient, TABLE } from '../lib/db';
import { embedOne, providerFrom, tagFor } from '../lib/embed';
import { decodeMimeHeader, emailPlainText } from '../lib/text';

const flag = (name: string) => process.argv.includes(`--${name}`);
const arg = (name: string, fallback?: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? (process.argv[i + 1] ?? fallback) : fallback;
};

// `-k 5` and `--k 5` both, because one of them is what anyone actually types.
const kIndex = Math.max(process.argv.indexOf('-k'), process.argv.indexOf('--k'));
const K = Number(kIndex > 0 ? (process.argv[kIndex + 1] ?? '10') : '10');

type Hit = {
  kind: string; id: string; origin: string; title: string; body: string; date: number; d: number;
};

async function nearest(db: Client, vector: string, tag: string, kind?: string): Promise<Hit[]> {
  const hits: Hit[] = [];
  const tables: Array<[string, string, string, string]> = [
    ['notification', 'notifications', 'package_name', 'post_time'],
    ['email', 'emails', 'from_email', 'received_at'],
  ];
  for (const [k, table, origin, date] of tables) {
    if (kind && kind !== k) continue;
    const rs = await db.execute({
      sql: `select id, ${origin} as origin,
                   ${table === 'emails' ? 'subject' : 'title'} as title,
                   ${table === 'emails' ? "coalesce(body_html, body_text, '')" : 'text'} as body,
                   ${date} as date,
                   vector_distance_cos(embedding, vector32(?)) as d
            from ${table}
            where embedding is not null and embedding_model = ?
            order by d limit ?`,
      args: [vector, tag, K],
    });
    for (const r of rs.rows) {
      // A mail's stored subject and body are MIME, not text: displaying them
      // raw shows `=?utf-8?B?…` and a stylesheet instead of the receipt.
      const mail = k === 'email';
      hits.push({
        kind: k,
        id: String(r.id),
        origin: String(r.origin),
        title: mail ? decodeMimeHeader(String(r.title ?? '')) : String(r.title ?? ''),
        body: mail ? emailPlainText(String(r.body ?? '')) : String(r.body ?? ''),
        date: Number(r.date),
        d: Number(r.d),
      });
    }
  }
  return hits.sort((a, b) => a.d - b.d).slice(0, K);
}

/** The stored vector of one message, as the JSON text `vector32` accepts —
 * "more like this" without re-embedding anything. */
async function vectorOf(db: Client, id: string): Promise<{ vector: string; tag: string }> {
  for (const table of Object.values(TABLE)) {
    // Prefix match, so the 8 characters `ids.ts` prints are enough to paste.
    const rs = await db.execute({
      sql: `select vector_extract(embedding) as v, embedding_model as m from ${table}
            where (id = ? or id like ?) and embedding is not null limit 1`,
      args: [id, `${id}%`],
    });
    const row = rs.rows[0];
    if (row) return { vector: String(row.v), tag: String(row.m) };
  }
  throw new Error(`no hay vector para ${id} (¿está embebido? probá bun scripts/ids.ts)`);
}

async function main() {
  const query = process.argv.slice(2).find((a) => !a.startsWith('--') && !isFlagValue(a));
  const like = arg('like');
  if (!query && !like) {
    console.error('uso: bun scripts/search.ts "texto" | --like <messageId>');
    process.exit(1);
  }

  const db = flag('remote') ? await remoteClient(arg('db')) : labClient();
  const started = Date.now();
  const { vector, tag } = like
    ? await vectorOf(db, like)
    : { vector: JSON.stringify(await embedOne(query!, providerFrom(process.argv))), tag: tagFor(providerFrom(process.argv)) };
  const embedded = Date.now();
  const hits = await nearest(db, vector, tag, arg('kind'));
  const done = Date.now();

  console.log(`\n${like ? `similares a ${like}` : `"${query}"`}  ·  ${tag}`);
  console.log(`embedding ${embedded - started} ms · scan ${done - embedded} ms · ${hits.length} resultados\n`);
  for (const h of hits) {
    const when = new Date(h.date).toISOString().slice(0, 16).replace('T', ' ');
    const body = h.body.replace(/\s+/g, ' ').slice(0, 90);
    console.log(`${h.d.toFixed(4)}  ${when}  [${h.kind[0]}] ${h.origin.slice(0, 22).padEnd(22)} ${h.title.slice(0, 50)}`);
    if (body) console.log(`        ${body}`);
  }
  db.close();
}

/** Positional args must not swallow the value of a preceding flag. */
function isFlagValue(value: string): boolean {
  const i = process.argv.indexOf(value);
  return i > 2 && (process.argv[i - 1] ?? '').startsWith('-');
}

await main();
