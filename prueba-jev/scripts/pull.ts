/**
 * Copy the user's notifications/emails/applications into `data/lab.db`.
 *
 *   bun scripts/pull.ts [--since <epochMs>] [--db <name>]
 *
 * Idempotent (`insert or replace`) and additive: the classification and
 * embedding columns in the lab file are never overwritten by a re-pull,
 * because this only writes the source columns.
 */
import { labClient, ensureLabSchema, remoteClient } from '../lib/db';

const arg = (name: string) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : undefined;
};

const PAGE = 2000;

async function main() {
  const since = Number(arg('since') ?? 0);
  const remote = await remoteClient(arg('db'));
  const lab = labClient();
  await ensureLabSchema(lab);

  const apps = await remote.execute('select id, name from applications');
  for (const r of apps.rows) {
    await lab.execute({
      sql: 'insert or replace into applications(id, name) values(?, ?)',
      args: [String(r.id), String(r.name ?? r.id)],
    });
  }
  console.log(`applications: ${apps.rows.length}`);

  let copied = 0;
  for (let offset = 0; ; offset += PAGE) {
    const rs = await remote.execute({
      sql: `select id, package_name, title, text, category, post_time from notifications
            where post_time >= ? order by post_time limit ? offset ?`,
      args: [since, PAGE, offset],
    });
    if (rs.rows.length === 0) break;
    // One batch per page: 24k single round trips to a local file is slow for
    // no reason, and a page is a natural unit to resume from.
    await lab.batch(
      rs.rows.map((r) => ({
        sql: `insert or replace into notifications(id, package_name, title, text, category, post_time)
              values(?, ?, ?, ?, ?, ?)`,
        args: [String(r.id), String(r.package_name), String(r.title ?? ''), String(r.text ?? ''),
          r.category == null ? null : String(r.category), Number(r.post_time)] as never,
      })),
      'write',
    );
    copied += rs.rows.length;
    process.stdout.write(`\rnotifications: ${copied}`);
  }
  console.log(`\rnotifications: ${copied}`);

  const mails = await remote.execute({
    sql: `select id, from_email, subject, body_text, body_html, received_at from emails
          where received_at >= ?`,
    args: [since],
  });
  for (const r of mails.rows) {
    await lab.execute({
      sql: `insert or replace into emails(id, from_email, subject, body_text, body_html, received_at)
            values(?, ?, ?, ?, ?, ?)`,
      args: [String(r.id), String(r.from_email), r.subject == null ? null : String(r.subject),
        r.body_text == null ? null : String(r.body_text),
        r.body_html == null ? null : String(r.body_html), Number(r.received_at)],
    });
  }
  console.log(`emails: ${mails.rows.length}`);

  remote.close();
  lab.close();
}

await main();
