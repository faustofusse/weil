/** One-shot: import the old finance-worker's last 100 emails into a user's
 * Turso DB. Usage: TURSO_API_TOKEN=... bun scripts/import-emails.ts <userId> */
import { findUser, takeEnv, platformClient, fetchOld, OLD_WORKER_BASE } from './import-common';

async function main() {
  const userId = process.argv[2];
  if (!userId) {
    console.error('usage: bun scripts/import-emails.ts <userId>');
    process.exit(1);
  }
  const token = takeEnv('TURSO_API_TOKEN');
  const org = process.env.TURSO_ORG ?? 'faustofusse';

  const user = await findUser(userId);
  console.log(`importing into ${user.turso_db_name} for user ${user.id}`);

  const res = await fetch(`${OLD_WORKER_BASE}/api/emails?since=0`);
  if (!res.ok) throw new Error(`old worker: ${res.status} ${await res.text()}`);
  const { success, emails } = (await res.json()) as {
    success: boolean;
    emails?: {
      id: string; from_email: string; to_email: string;
      subject?: string | null; body_text?: string | null;
      received_at: number; deleted_at?: number | null;
    }[];
  };
  if (!success || !emails) throw new Error('old worker error');
  console.log(`fetched ${emails.length} emails (last 100 by the old API cap)`);

  const db = await platformClient({ org, dbName: user.turso_db_name, hostname: user.turso_db_hostname, tursoApiToken: token });
  let inserted = 0;
  try {
    for (const e of emails) {
      const result = await db.execute({
        sql: `insert or ignore into emails(id, from_email, to_email, subject, body_text, received_at)
              values (?, ?, ?, ?, ?, ?)`,
        args: [
          e.id,
          e.from_email,
          e.to_email,
          e.subject ?? null,
          e.body_text ?? null,
          e.received_at < 1e12 ? e.received_at : Math.round(e.received_at / 1000),
        ],
      });
      if (Number(result.rowsAffected) > 0) inserted++;
    }
  } finally {
    db.close();
  }
  console.log(`inserted ${inserted}, skipped ${emails.length - inserted} (already present)`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
