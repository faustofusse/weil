/** Shared bits for the one-shot import scripts (emails, notifications).
 * Reaching the user's database is `./turso.ts`, which the `db.ts` CLI and
 * prueba-jev use too. */
import { findUser as lookupUser, platformClient, type UserRow } from './turso';

export { platformClient, type UserRow } from './turso';

export const OLD_WORKER_BASE = 'https://finance-worker.fausto-fusse.workers.dev';
export const AUTH_D1_URL = 'https://api.cloudflare.com/client/v4/accounts';

/** Timestamps imported from the old worker mix seconds (server-generated) and
 * millis (device-synced): anything under 1e12 is seconds. */
export function toMillis(value: number | null | undefined): number {
  if (value == null) return 0;
  return value < 1e12 ? Math.round(value * 1000) : value;
}

/** Resolve a finance user's Turso DB via the auth worker's D1 (read-only).
 * Kept async and exit-on-failure for the existing callers. */
export async function findUser(userId: string): Promise<UserRow> {
  try {
    return lookupUser(userId);
  } catch (err) {
    console.error(err instanceof Error ? err.message : err);
    process.exit(1);
  }
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
