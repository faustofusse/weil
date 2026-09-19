/**
 * Promote the experiment's columns from `data/lab.db` to the user's real DB.
 *
 *   bun scripts/push.ts            # dry run: says what it would write
 *   bun scripts/push.ts --confirm  # writes
 *
 * Only `notifications`/`emails` columns, no new tables and no vector index
 * (the device's sync engine has no DiskANN and the schema replicates to it).
 *
 * ⚠️  This step is the one that needs the app to change in the same commit:
 * the five columns per table must be added to `SCHEMA_SQL`/`migrateSchema()`
 * **and `SCHEMA_VERSION` bumped**. Without the bump the device that ran this
 * script notices nothing and every other device fails with "no such column" —
 * exactly how `accounts.in_net_worth` shipped broken to iOS.
 */
import { ensureColumns, labClient, remoteClient, TABLE, type Kind } from '../lib/db';

const CONFIRM = process.argv.includes('--confirm');
const arg = (name: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : undefined;
};

type Row = {
  kind: Kind; id: string; score: number | null; movement: number | null;
  at: number | null; vector: string | null; model: string | null;
};

async function main() {
  const lab = labClient();
  const rows: Row[] = [];
  for (const [kind, table] of Object.entries(TABLE) as Array<[Kind, string]>) {
    // `vector_extract` gives the JSON text form, which re-encodes on the other
    // side with `vector32`: sending the raw blob through a client that has to
    // guess its type is how a vector arrives as a string of bytes.
    const rs = await lab.execute(
      // `vector_extract(NULL)` raises instead of returning NULL, so the guard
      // is not defensive style, it is the difference between a query and an
      // error on the first unclassified row.
      `select id, jev_score, is_movement, classified_at, embedding_model,
              case when embedding is null then null else vector_extract(embedding) end as v
       from ${table} where classified_at is not null or embedding is not null`,
    );
    for (const r of rs.rows) {
      rows.push({
        kind,
        id: String(r.id),
        score: r.jev_score == null ? null : Number(r.jev_score),
        movement: r.is_movement == null ? null : Number(r.is_movement),
        at: r.classified_at == null ? null : Number(r.classified_at),
        vector: r.v == null ? null : String(r.v),
        model: r.embedding_model == null ? null : String(r.embedding_model),
      });
    }
  }
  const withVector = rows.filter((r) => r.vector != null);
  const fake = withVector.filter((r) => r.model?.startsWith('hash-'));
  console.log(`filas a actualizar: ${rows.length} (con vector: ${withVector.length})`);

  if (fake.length) {
    console.error(
      `\n✗ ${fake.length} vectores son del proveedor 'hash' (no semántico, solo para pruebas).\n` +
      `  Re-embebé con --provider openai|gemini antes de promover.`,
    );
    process.exit(1);
  }
  if (!CONFIRM) {
    console.log('dry run: nada escrito. Repetí con --confirm.');
    lab.close();
    return;
  }

  const remote = await remoteClient(arg('db'));
  await ensureColumns(remote);
  let n = 0;
  for (let i = 0; i < rows.length; i += 200) {
    await remote.batch(
      rows.slice(i, i + 200).map((r) => ({
        sql: `update ${TABLE[r.kind]}
              set jev_score = ?, is_movement = ?, classified_at = ?,
                  embedding = case when ? is null then embedding else vector32(?) end,
                  embedding_model = coalesce(?, embedding_model)
              where id = ?`,
        args: [r.score, r.movement, r.at, r.vector, r.vector, r.model, r.id] as never,
      })),
      'write',
    );
    n = Math.min(i + 200, rows.length);
    process.stdout.write(`\rpush: ${n}/${rows.length}`);
  }
  console.log('');

  const check = await remote.execute(
    `select (select count(*) from notifications where is_movement = 1) as n,
            (select count(*) from emails where is_movement = 1) as e,
            (select count(*) from notifications where embedding is not null) as nv,
            (select count(*) from emails where embedding is not null) as ev`,
  );
  console.log('en la DB real:', check.rows[0]);
  remote.close();
  lab.close();
}

await main();
