/**
 * One way to reach a user's Turso database from a dev machine.
 *
 * Two steps, both of which used to be copy-pasted (or hardcoded) in every
 * script that needed them:
 *
 *   1. the auth worker's D1 maps a user id to `turso_db_name` /
 *      `turso_db_hostname` — read with `wrangler d1 execute` from the auth
 *      checkout, whose wrangler.jsonc holds the binding;
 *   2. the Turso platform API mints a token that works on any database of
 *      the org, which is what lets a script open a database it never
 *      authenticated to as the user.
 *
 * Everything here talks to the **server** copy. It cannot see rows that are
 * still only on a phone, which is exactly why `Reconcile.kt` and the
 * retrieval in `SuggestRepository` run on-device instead of in the worker.
 */
import { createClient, type Client } from '@libsql/client';

export const AUTH_DIR = '/Users/fausto/sw/auth';
export const APP_SLUG = 'finance';

export interface UserRow {
  id: string;
  app_slug: string;
  email: string | null;
  display_name: string | null;
  turso_db_name: string;
  turso_db_hostname: string;
}

const USER_COLUMNS = 'id, app_slug, email, display_name, turso_db_name, turso_db_hostname';

/** D1 has no parameter binding through `wrangler execute --command`, so the
 * selector is inlined; it may only ever be an id/email/name typed by the
 * operator, and the quote characters that would end the literal are dropped. */
function literal(value: string): string {
  return value.replace(/['\\;]/g, '');
}

/** Run one read-only statement against the auth worker's remote D1. */
export function queryAuthD1<T>(sql: string): T[] {
  const proc = Bun.spawnSync(
    ['bunx', 'wrangler', 'd1', 'execute', 'auth', '--remote', '--json', '--command', sql],
    { cwd: AUTH_DIR, stdout: 'pipe', stderr: 'pipe' }
  );
  const stdout = proc.stdout.toString();
  const jsonStart = stdout.indexOf('[');
  if (jsonStart < 0) {
    throw new Error(
      `auth D1 lookup failed (is wrangler logged in to the auth account?)\n${stdout}\n${proc.stderr.toString()}`
    );
  }
  // wrangler --json prints [ { results: [...], meta: ... } ]
  const parsed = JSON.parse(stdout.slice(jsonStart)) as Array<{ results?: T[] }>;
  return parsed.flatMap((x) => x.results ?? []);
}

/** Every user of the app, newest first. */
export function listUsers(appSlug = APP_SLUG): UserRow[] {
  return queryAuthD1<UserRow>(
    `SELECT ${USER_COLUMNS} FROM users WHERE app_slug = '${literal(appSlug)}' ORDER BY created_at DESC`
  );
}

/** Resolve a user by id, email or display name. Throws unless exactly one
 * row matches: guessing which of two accounts was meant is how a script ends
 * up writing into a stranger's ledger. */
export function findUser(selector: string, appSlug = APP_SLUG): UserRow {
  const value = literal(selector);
  const rows = queryAuthD1<UserRow>(
    `SELECT ${USER_COLUMNS} FROM users WHERE app_slug = '${literal(appSlug)}'` +
      ` AND (id = '${value}' OR email = '${value}' OR display_name = '${value}')`
  );
  if (rows.length === 0) throw new Error(`no ${appSlug} user matches "${selector}" in the auth D1`);
  if (rows.length > 1) {
    throw new Error(
      `"${selector}" matches ${rows.length} users: ${rows.map((r) => r.id).join(', ')} — use the id`
    );
  }
  return rows[0]!;
}

export function takeEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`missing ${name} in the environment`);
  return value;
}

/** The Turso platform API token: the environment first, otherwise the one
 * the `turso` CLI is already logged in with, so the tools work on a machine
 * that never exported anything. */
export function apiToken(): string {
  const fromEnv = process.env.TURSO_API_TOKEN;
  if (fromEnv) return fromEnv;
  const proc = Bun.spawnSync(['turso', 'auth', 'token'], { stdout: 'pipe', stderr: 'pipe' });
  const token = proc.stdout.toString().trim().split('\n').pop()?.trim();
  if (proc.exitCode !== 0 || !token) {
    throw new Error('no TURSO_API_TOKEN in the environment and `turso auth token` failed (turso auth login)');
  }
  return token;
}

/** A platform token for one database, minted the way `turso db tokens
 * create` does. */
export async function platformToken(cfg: {
  org?: string;
  dbName: string;
  tursoApiToken?: string;
}): Promise<string> {
  const org = cfg.org ?? process.env.TURSO_ORG ?? 'faustofusse';
  const api = cfg.tursoApiToken ?? apiToken();
  const res = await fetch(
    `https://api.turso.tech/v1/organizations/${org}/databases/${cfg.dbName}/auth/tokens`,
    {
      method: 'POST',
      headers: { authorization: `Bearer ${api}`, 'content-type': 'application/json' },
      body: JSON.stringify({ permissions: { read_attach: { databases: [] } } }),
    }
  );
  if (!res.ok) throw new Error(`turso token: ${res.status} ${await res.text()}`);
  const { jwt } = (await res.json()) as { jwt: string };
  return jwt;
}

/** libsql client for one database of the org. */
export async function platformClient(cfg: {
  org?: string;
  dbName: string;
  hostname: string;
  tursoApiToken?: string;
}): Promise<Client> {
  return createClient({
    url: `libsql://${cfg.hostname}`,
    authToken: await platformToken(cfg),
  });
}

/** The whole chain: selector -> user row -> open client. */
export async function clientForUser(selector: string): Promise<{ user: UserRow; db: Client }> {
  const user = findUser(selector);
  return { user, db: await platformClient({ dbName: user.turso_db_name, hostname: user.turso_db_hostname }) };
}
