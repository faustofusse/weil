/** Shared bits for the one-shot import scripts (emails, notifications). */
import { createClient, type Client } from '@libsql/client';

export const OLD_WORKER_BASE = 'https://finance-worker.fausto-fusse.workers.dev';
export const AUTH_D1_URL = 'https://api.cloudflare.com/client/v4/accounts';

export interface UserRow {
  id: string;
  app_slug: string;
  turso_db_name: string;
  turso_db_hostname: string;
}

/** Timestamps imported from the old worker mix seconds (server-generated) and
 * millis (device-synced): anything under 1e12 is seconds. */
export function toMillis(value: number | null | undefined): number {
  if (value == null) return 0;
  return value < 1e12 ? Math.round(value * 1000) : value;
}

/** Resolve a finance user's Turso DB via the auth worker's D1 (read-only).
 * Runs `wrangler d1 execute` from the auth worker checkout (its wrangler.jsonc
 * holds the binding) with --json so we can parse the result rows. */
export async function findUser(userId: string): Promise<UserRow> {
  const safeId = userId.replace(/['\\;]/g, '');
  const cmd = ['bunx', 'wrangler', 'd1', 'execute', 'auth', '--remote', '--json', '--command',
    `SELECT id, app_slug, turso_db_name, turso_db_hostname FROM users WHERE id = '${safeId}' LIMIT 1`];
  const proc = Bun.spawnSync(cmd, { cwd: '/Users/fausto/sw/auth', stdout: 'pipe', stderr: 'pipe' });
  const stdout = proc.stdout.toString();
  const jsonStart = stdout.indexOf('[');
  if (jsonStart < 0) {
    console.error('D1 lookup failed. Make sure <userId> is a user of the finance app in the auth D1.');
    console.error(stdout, proc.stderr.toString());
    process.exit(1);
  }
  // wrangler --json prints [ { results: [...], meta: ... } ]
  const parsed = JSON.parse(stdout.slice(jsonStart)) as Array<{ results?: UserRow[] }>;
  const user = parsed.flatMap((x) => x.results ?? [])[0];
  if (!user) {
    console.error(`user ${userId} not found in auth D1`);
    process.exit(1);
  }
  return user;
}

/** Platform-token libsql client for the user's DB (token via Turso API). */
export async function platformClient(cfg: {
  org: string;
  dbName: string;
  hostname: string;
  tursoApiToken: string;
}): Promise<ReturnType<typeof createClient>> {
  const res = await fetch(
    `https://api.turso.tech/v1/organizations/${cfg.org}/databases/${cfg.dbName}/auth/tokens`,
    {
      method: 'POST',
      headers: {
        authorization: `Bearer ${cfg.tursoApiToken}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({ permissions: { read_attach: { databases: [] } } }),
    }
  );
  if (!res.ok) throw new Error(`turso token: ${res.status} ${await res.text()}`);
  const { jwt } = (await res.json()) as { jwt: string };
  return createClient({ url: `libsql://${cfg.hostname}`, authToken: jwt });
}

export function takeEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    console.error(`missing TURSO_API_TOKEN / ${name}: set TURSO_API_TOKEN and TURSO_ORG in the environment`);
    process.exit(1);
  }
  return value;
}

/** GET from the old finance-worker (unauthenticated), pulling the `key` array. */
export async function fetchOld<T>(path: string, key: string): Promise<T[]> {
  const res = await fetch(`${OLD_WORKER_BASE}${path}`);
  if (!res.ok) throw new Error(`old worker ${path}: ${res.status} ${await res.text()}`);
  const data = (await res.json()) as { success: boolean; error?: string; [k: string]: unknown };
  if (!data.success) throw new Error((data.error as string) ?? 'old worker error');
  return (data[key] as T[] | undefined) ?? [];
}
