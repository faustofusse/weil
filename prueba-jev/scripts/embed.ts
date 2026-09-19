/**
 * Embed the messages Jev marked as movements, into the `embedding` column of
 * their own row.
 *
 *   bun scripts/embed.ts [--all] [--limit N] [--force] [--provider openai|gemini|hash]
 *
 * `--all` embeds every money-bearing message instead of only the movements,
 * which is what you want to measure how well similarity separates movements
 * from promotions on its own.
 */
import { labClient, ensureLabSchema, loadMessages, TABLE, canonicalText } from '../lib/db';
import { embedAll, providerFrom, tagFor } from '../lib/embed';
import { hasAmount, type Message } from '../lib/text';

const flag = (name: string) => process.argv.includes(`--${name}`);
const arg = (name: string, fallback: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? (process.argv[i + 1] ?? fallback) : fallback;
};

const BATCH = 100;

async function main() {
  const provider = providerFrom(process.argv);
  const tag = tagFor(provider);
  const db = labClient();
  await ensureLabSchema(db);

  const all = await loadMessages(db);
  const state = new Map<string, { movement: boolean; model: string | null }>();
  for (const table of ['notifications', 'emails']) {
    const rs = await db.execute(`select id, is_movement, embedding_model from ${table}`);
    for (const r of rs.rows) {
      state.set(String(r.id), {
        movement: Number(r.is_movement ?? 0) === 1,
        model: r.embedding_model == null ? null : String(r.embedding_model),
      });
    }
  }

  const wanted = all.filter((m) =>
    flag('all') ? hasAmount(`${m.title} ${m.body}`) : state.get(m.id)?.movement,
  );
  // A row embedded with another provider is pending, not done: vectors from
  // two models are not comparable and mixing them silently poisons every
  // distance in the table.
  const pending = flag('force') ? wanted : wanted.filter((m) => state.get(m.id)?.model !== tag);
  const todo = pending.slice(0, Number(arg('limit', String(pending.length))));
  console.log(`proveedor: ${tag} | a embeber: ${todo.length} de ${wanted.length} elegibles`);

  for (let i = 0; i < todo.length; i += BATCH) {
    const chunk = todo.slice(i, i + BATCH);
    const vectors = await embedAll(chunk.map((m) => canonicalText(m)), provider);
    await db.batch(
      chunk.map((m: Message, j: number) => ({
        // `vector32(?)` takes the JSON text form and stores the packed blob;
        // the column's declared `F32_BLOB(512)` is only a hint to the server.
        sql: `update ${TABLE[m.kind]} set embedding = vector32(?), embedding_model = ? where id = ?`,
        args: [JSON.stringify(vectors[j]), tag, m.id] as never,
      })),
      'write',
    );
    process.stdout.write(`\rembeddings: ${Math.min(i + BATCH, todo.length)}/${todo.length}`);
  }
  if (todo.length) console.log('');

  const rs = await db.execute(
    `select embedding_model as model, count(*) as n from (
       select embedding_model from notifications where embedding is not null
       union all select embedding_model from emails where embedding is not null)
     group by 1`,
  );
  for (const r of rs.rows) console.log(`vectores en lab.db: ${r.n} (${r.model})`);
  db.close();
}

await main();
