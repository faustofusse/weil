/**
 * Two databases, same shape:
 *
 * - `remote()`  — the user's real Turso DB (`finance-00mtqgh7qtjguu7n6902`).
 * - `lab()`     — a local libSQL file, `data/lab.db`, where the whole
 *                 experiment runs. Working here first means a wrong threshold
 *                 or a bad embedding costs a `rm`, not a migration.
 *
 * The experiment's columns live **on `notifications`/`emails` themselves**
 * (see the plan, §3): the embedding is a property of the message, not an
 * entity with its own life, so it dies with it instead of leaving an orphan
 * replicating forever.
 */
import { createClient, type Client } from '@libsql/client';
import { platformToken } from '../../scripts/turso';
import { canonicalText, decodeMimeHeader, emailPlainText, type Kind, type Message } from './text';

export const DEFAULT_DB = 'finance-00mtqgh7qtjguu7n6902';
export const LAB_PATH = new URL('../data/lab.db', import.meta.url).pathname;

export const EMBED_MODEL = 'text-embedding-3-small';
/** 512 instead of the model's native 1536: 2 KB per row rather than 6, and
 * these rows replicate to two phones. See `lib/embed.ts` for the tag stored
 * per row so two models never get compared to each other. */
export const EMBED_DIMS = 512;

export function labClient(): Client {
  return createClient({ url: `file:${LAB_PATH}` });
}

/** Platform token for the user's DB (minting lives in `scripts/turso.ts`,
 * shared with the import scripts and the `db.ts` CLI). `TURSO_TOKEN`
 * short-circuits it when one is already at hand. */
export async function remoteClient(dbName = process.env.TURSO_DB || DEFAULT_DB): Promise<Client> {
  const url = `libsql://${dbName}-faustofusse.aws-us-east-1.turso.io`;
  const direct = process.env.TURSO_TOKEN;
  if (direct) return createClient({ url, authToken: direct });
  if (!process.env.TURSO_API_TOKEN) {
    throw new Error(
      'set TURSO_TOKEN (turso db tokens create ' + dbName + ') or TURSO_API_TOKEN in prueba-jev/.env',
    );
  }
  return createClient({ url, authToken: await platformToken({ dbName }) });
}

/**
 * The five columns the experiment adds, per table. `F32_BLOB(512)` is a
 * declared type, which SQLite treats as a hint: the sync engine on the phone
 * does not know the name and stores a plain BLOB, while the server uses it.
 * Deliberately **no** `libsql_vector_idx` index — the device engine has no
 * DiskANN and the schema replicates to it (see the plan, §0).
 */
const COLUMNS: Array<[string, string]> = [
  ['jev_score', 'real'],
  ['is_movement', 'integer'],
  ['classified_at', 'integer'],
  ['embedding', `F32_BLOB(${EMBED_DIMS})`],
  ['embedding_model', 'text'],
];

/** `alter table add column`, tolerating "duplicate column name" — the same
 * thing `addColumn()` does in Kotlin, for the same reason. */
export async function ensureColumns(db: Client, tables = ['notifications', 'emails']) {
  for (const table of tables) {
    for (const [name, type] of COLUMNS) {
      try {
        await db.execute(`alter table ${table} add column ${name} ${type}`);
      } catch (e) {
        if (!/duplicate column name/i.test(String(e))) throw e;
      }
    }
  }
}

export async function ensureLabSchema(db: Client) {
  await db.execute(`create table if not exists notifications(
    id text primary key not null, package_name text not null, title text not null,
    text text not null, category text, post_time integer not null)`);
  await db.execute(`create table if not exists emails(
    id text primary key not null, from_email text not null, subject text,
    body_text text, body_html text, received_at integer not null)`);
  await db.execute(`create table if not exists applications(
    id text primary key not null, name text not null)`);
  await ensureColumns(db);
}

export async function appLabels(db: Client): Promise<Map<string, string>> {
  const rs = await db.execute('select id, name from applications');
  return new Map(rs.rows.map((r) => [String(r.id), String(r.name || r.id)]));
}

/** Every message in one shape, with the mail body already made readable. */
export async function loadMessages(
  db: Client,
  opts: { kind?: Kind; where?: string; args?: unknown[] } = {},
): Promise<Message[]> {
  const labels = await appLabels(db);
  const out: Message[] = [];
  const extra = opts.where ? ` where ${opts.where}` : '';

  if (opts.kind !== 'email') {
    const rs = await db.execute({
      sql: `select id, package_name, title, text, category, post_time from notifications${extra}`,
      args: (opts.args ?? []) as never,
    });
    for (const r of rs.rows) {
      const origin = String(r.package_name);
      out.push({
        kind: 'notification',
        id: String(r.id),
        origin,
        originLabel: labels.get(origin) ?? origin,
        title: String(r.title ?? ''),
        body: String(r.text ?? ''),
        date: Number(r.post_time),
        category: r.category == null ? null : String(r.category),
      });
    }
  }
  if (opts.kind !== 'notification') {
    const rs = await db.execute({
      sql: `select id, from_email, subject, body_text, body_html, received_at from emails${extra}`,
      args: (opts.args ?? []) as never,
    });
    for (const r of rs.rows) {
      const from = String(r.from_email);
      // `body_html` first: the stored text part is often raw MIME cut at 10 kB
      // with the receipt past the cut.
      const raw = String(r.body_html ?? '') || String(r.body_text ?? '');
      out.push({
        kind: 'email',
        id: String(r.id),
        origin: from,
        originLabel: from,
        title: decodeMimeHeader(String(r.subject ?? '')),
        body: emailPlainText(raw),
        date: Number(r.received_at),
        category: null,
      });
    }
  }
  return out;
}

export const TABLE: Record<Kind, string> = { notification: 'notifications', email: 'emails' };

export { canonicalText };
export type { Kind, Message };
