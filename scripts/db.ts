#!/usr/bin/env bun
/**
 * Read a user's Turso database from the command line.
 *
 *   bun scripts/db.ts --ls                        list the app's users
 *   bun scripts/db.ts <user> --schema             every table's columns
 *   bun scripts/db.ts <user> --tables             table names and row counts
 *   bun scripts/db.ts <user> "select ..."         run one query
 *
 * `<user>` is an auth user id, email or display name. Needs TURSO_API_TOKEN
 * (+ TURSO_ORG, default `faustofusse`) and a wrangler logged in to the auth
 * account.
 *
 * **Read-only on purpose.** The phone owns the transaction boundaries and the
 * undo; a statement typed here has neither, and the sync engine would happily
 * replicate the damage to every paired device. Anything that is not a
 * select/pragma-read is refused rather than gated behind a flag.
 *
 * It also reads the **server** copy: rows still sitting unsynced on a device
 * are invisible here, so "not in the database" is never a conclusion this
 * tool can reach on its own.
 */
import type { Client } from '@libsql/client';
import { clientForUser, listUsers } from './turso';

const FORBIDDEN = /\b(insert|update|delete|drop|alter|create|replace|attach|detach|vacuum|reindex)\b/i;

/** Allowed: a single select/with/explain, or a pragma that reads. */
function assertReadOnly(sql: string): void {
  const text = sql.trim().replace(/;+\s*$/, '');
  if (text.includes(';')) throw new Error('one statement at a time');
  if (/^pragma\b/i.test(text)) {
    if (text.includes('=')) throw new Error(`pragma assignment is a write: ${text}`);
    return;
  }
  if (!/^(select|with|explain)\b/i.test(text)) {
    throw new Error(`read-only: statement must start with select/with/explain/pragma, got: ${text.slice(0, 40)}`);
  }
  // `with ... delete from` is still a write.
  if (FORBIDDEN.test(text)) throw new Error(`read-only: statement contains a write keyword`);
}

function cell(value: unknown, width: number): string {
  if (value === null || value === undefined) return '∅';
  if (value instanceof Uint8Array) return `<${value.length} bytes>`;
  const text = String(value).replace(/\s+/g, ' ');
  return text.length > width ? `${text.slice(0, width - 1)}…` : text;
}

function printRows(columns: string[], rows: Array<Record<string, unknown>>, width: number): void {
  if (rows.length === 0) {
    console.log('(no rows)');
    return;
  }
  const cells = rows.map((row) => columns.map((c) => cell(row[c], width)));
  const widths = columns.map((c, i) =>
    Math.max(c.length, ...cells.map((r) => [...r[i]!].length))
  );
  const line = (values: string[]) =>
    values.map((v, i) => v.padEnd(widths[i]!)).join('  ').trimEnd();
  console.log(line(columns));
  console.log(widths.map((w) => '─'.repeat(w)).join('  '));
  for (const row of cells) console.log(line(row));
  console.log(`\n${rows.length} row${rows.length === 1 ? '' : 's'}`);
}

async function tableNames(db: Client): Promise<string[]> {
  const res = await db.execute(
    "select name from sqlite_master where type = 'table' and name not like 'sqlite_%' order by name"
  );
  return res.rows.map((r) => String(r.name));
}

/** Columns, not `pragma user_version`: the pragma lives in each device's
 * own file and is not replicated, so the server copy answers 0 no matter
 * what `SCHEMA_VERSION` the phones are on. Whether a migration landed is
 * answered by the column being there. */
async function schema(db: Client): Promise<void> {
  for (const name of await tableNames(db)) {
    const info = await db.execute(`pragma table_info(${name})`);
    const columns = info.rows
      .map((c) => `${c.name} ${c.type}${c.pk ? ' pk' : ''}${c.notnull ? ' !' : ''}`)
      .join(', ');
    console.log(`${name}(${columns})`);
  }
}

async function tables(db: Client): Promise<void> {
  const rows: Array<Record<string, unknown>> = [];
  for (const name of await tableNames(db)) {
    const count = await db.execute(`select count(*) as n from ${name}`);
    rows.push({ table: name, rows: count.rows[0]?.n ?? 0 });
  }
  printRows(['table', 'rows'], rows, 60);
}

async function main(): Promise<void> {
  const argv = process.argv.slice(2);
  const flags = new Set(argv.filter((a) => a.startsWith('--')));
  const widthArg = argv.find((a) => a.startsWith('--width='));
  const width = widthArg ? Number(widthArg.slice('--width='.length)) : 48;
  const positional = argv.filter((a) => !a.startsWith('--'));

  if (flags.has('--ls') || argv.length === 0) {
    const users = listUsers();
    printRows(
      ['id', 'display_name', 'email', 'turso_db_name'],
      users as unknown as Array<Record<string, unknown>>,
      60
    );
    if (argv.length === 0) console.log('\nusage: bun scripts/db.ts <user> "select ..." | --schema | --tables');
    return;
  }

  const selector = positional[0];
  if (!selector) throw new Error('usage: bun scripts/db.ts <user> "select ..." | --schema | --tables');
  const { user, db } = await clientForUser(selector);
  console.error(`# ${user.display_name ?? user.email ?? user.id} · ${user.turso_db_name} (server copy)\n`);

  if (flags.has('--schema')) return schema(db);
  if (flags.has('--tables')) return tables(db);

  const sql = positional[1];
  if (!sql) throw new Error('nothing to run: pass a query, --schema or --tables');
  assertReadOnly(sql);
  const res = await db.execute(sql);
  const rows = res.rows.map((r) => ({ ...r }) as Record<string, unknown>);
  if (flags.has('--json')) {
    console.log(JSON.stringify(rows, (_k, v) => (typeof v === 'bigint' ? Number(v) : v), 2));
    return;
  }
  printRows([...res.columns], rows, width);
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
