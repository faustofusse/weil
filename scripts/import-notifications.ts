/** One-shot: import the old finance-worker's notifications + applications
 * into a user's Turso DB (icons can't round-trip the old JSON API and are
 * skipped; new captures re-fill them). Usage:
 *   TURSO_API_TOKEN=... bun scripts/import-notifications.ts <userId> */
import { findUser, takeEnv, platformClient, fetchOld, toMillis } from './import-common';

interface OldNotification {
  id: string;
  packageName: string;
  title: string;
  text: string;
  category?: string | null;
  postTime: number;
  receivedAt?: number | null;
  deletedAt?: number | null;
}

interface OldApplication {
  id: string;
  name: string;
  isSystemApp?: boolean;
  firstSeenAt: number;
  notificationCount?: number | null;
  lastNotificationAt?: number | null;
  deletedAt?: number | null;
}

async function main() {
  const userId = process.argv[2];
  if (!userId) {
    console.error('usage: bun scripts/import-notifications.ts <userId>');
    process.exit(1);
  }
  const token = takeEnv('TURSO_API_TOKEN');
  const org = process.env.TURSO_ORG ?? 'faustofusse';

  const user = await findUser(userId);
  console.log(`importing into ${user.turso_db_name} for user ${user.id}`);

  const notifications = await fetchOld<OldNotification>('/api/notifications?since=0', 'notifications');
  const applications = await fetchOld<OldApplication>('/api/applications?since=0', 'applications');
  console.log(`fetched ${notifications.length} notifications, ${applications.length} applications`);

  const db = await platformClient({ org, dbName: user.turso_db_name, hostname: user.turso_db_hostname, tursoApiToken: token });
  let insN = 0;
  let insA = 0;
  try {
    for (const n of notifications) {
      const result = await db.execute({
        sql: `insert or ignore into notifications(id, package_name, title, text, category, post_time, received_at)
              values (?, ?, ?, ?, ?, ?, ?)`,
        args: [
          n.id,
          n.packageName,
          n.title,
          n.text,
          n.category ?? null,
          toMillis(n.postTime),
          toMillis(n.receivedAt ?? Date.now()),
        ],
      });
      insN += Number(result.rowsAffected) > 0 ? 1 : 0;
    }
    for (const a of applications) {
      const result = await db.execute({
        sql: `insert or ignore into applications(id, name, icon, is_system_app, first_seen_at, notification_count, last_notification_at)
              values (?, ?, ?, ?, ?, ?, ?)`,
        args: [
          a.id,
          a.name,
          null,
          a.isSystemApp ? 1 : 0,
          toMillis(a.firstSeenAt),
          a.notificationCount ?? 0,
          a.lastNotificationAt ? toMillis(a.lastNotificationAt) : null,
        ],
      });
      insA += Number(result.rowsAffected) > 0 ? 1 : 0;
    }
  } finally {
    db.close();
  }
  console.log(`notifications: inserted ${insN}, skipped ${notifications.length - insN}`);
  console.log(`applications:  inserted ${insA}, skipped ${applications.length - insA}`);
  console.log('(icons are null until the app re-captures them on the next notification)');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
