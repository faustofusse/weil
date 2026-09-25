/**
 * Does a DDL statement run on one device reach the server and the other
 * devices through the Turso sync engine? The app has been burned once by a
 * statement that didn't (`alter table ... rename to`: applied locally, sync()
 * returned OK, never pushed), so before a migration uses a new kind of DDL it
 * is checked here first. See docs/sync-engine-ddl.md for results and history.
 *
 * What it does, per engine version:
 *   1. creates a throwaway database in the `finance` group (the same group and
 *      the same plain `POST /databases` the auth worker uses for real users);
 *   2. opens it from two "devices" (two local sync-engine files, A and B);
 *   3. runs each case's DDL on A, pushes, and reads the schema back from the
 *      server (over plain libsql/HTTP, i.e. the server copy) and from B after a
 *      pull;
 *   4. deletes the database.
 *
 * Usage (needs a Turso platform token; the auth repo's .env holds one):
 *   cd scripts/sync-ddl-probe && npm run setup
 *   set -a; . ../../../auth/.env; set +a
 *   node --experimental-strip-types probe.ts        # every engine version
 *   node --experimental-strip-types probe.ts pre9   # just one
 *
 * Node, not Bun: under Bun 1.2 the addon's first push fails with a bogus
 * `JSON parse error: number out of range` from the server.
 *
 * The app ships turso_sync_sdk_kit 0.8.0-pre.8 (commit 7e2fc39de, see
 * app/sharedLogic/src/androidMain/jniLibs/README.md), which was never
 * published to npm; engines/pre7 and engines/pre9 pin the releases on either
 * side of it. Each has its own node_modules with the JS layer pinned exactly
 * (overrides): the packages depend on each other with `^`, and a pre.7 addon
 * driven by the pre.13 JS layer fails every push with a protocol error.
 */
import { createClient, type Client } from '@libsql/client';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ORG = process.env.TURSO_ORG ?? 'faustofusse';
const GROUP = 'finance';
const API = `https://api.turso.tech/v1/organizations/${ORG}`;
const TOKEN = process.env.TURSO_API_TOKEN;
if (!TOKEN) throw new Error('TURSO_API_TOKEN missing (set -a; . ../../../auth/.env; set +a)');

const ENGINES: Record<string, string> = {
  pre7: './engines/pre7/node_modules/@tursodatabase/sync/dist/promise.js',
  pre9: './engines/pre9/node_modules/@tursodatabase/sync/dist/promise.js',
};

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(`${API}${path}`, {
    ...init,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
  });
  if (!res.ok) throw new Error(`${init.method ?? 'GET'} ${path}: ${res.status} ${await res.text()}`);
  return (await res.json()) as T;
}

type SyncDb = {
  exec(sql: string): Promise<void>;
  prepare(sql: string): Promise<{ all(...args: unknown[]): Promise<Record<string, unknown>[]> }>;
  push(): Promise<void>;
  pull(): Promise<boolean>;
  close(): Promise<void> | void;
};

async function columns(db: SyncDb, table: string): Promise<string[]> {
  const rows = await (await db.prepare(`pragma table_info(${table})`)).all();
  return rows.map((r) => String(r.name));
}

async function serverColumns(server: Client, table: string): Promise<string[]> {
  const rs = await server.execute(`pragma table_info(${table})`);
  return rs.rows.map((r) => String(r.name));
}

async function serverTables(server: Client): Promise<string[]> {
  const rs = await server.execute(
    "select name from sqlite_master where type = 'table' and name not like 'sqlite_%' and name not like 'turso_%' order by name",
  );
  return rs.rows.map((r) => String(r.name));
}

async function localTables(db: SyncDb): Promise<string[]> {
  const stmt = await db.prepare(
    "select name from sqlite_master where type = 'table' and name not like 'sqlite_%' and name not like 'turso_%' order by name",
  );
  const rows = await stmt.all();
  return rows.map((r) => String(r.name));
}

/** Runs [fn], returning its error message instead of throwing. */
async function attempt(fn: () => Promise<unknown>): Promise<string> {
  try {
    await fn();
    return 'ok';
  } catch (e) {
    return `ERROR ${(e as Error).message.split('\n')[0]}`;
  }
}

async function probe(label: string, pkg: string) {
  const { connect } = (await import(pkg)) as {
    connect(opts: { path: string; url: string; authToken: string; clientName?: string }): Promise<SyncDb>;
  };
  const name = `ddl-probe-${label}-${Date.now().toString(36)}`;
  const log: string[] = [];
  const say = (line: string) => {
    log.push(line);
    console.log(line);
  };
  say(`\n=== engine ${label} (${pkg}), scratch database ${name}`);

  const created = await api<{ database: { Hostname: string } }>('/databases', {
    method: 'POST',
    body: JSON.stringify({ name, group: GROUP }),
  });
  const host = created.database.Hostname;
  const dir = mkdtempSync(join(tmpdir(), 'ddl-probe-'));
  try {
    const { jwt } = await api<{ jwt: string }>(`/databases/${name}/auth/tokens`, { method: 'POST', body: '{}' });
    const server = createClient({ url: `https://${host}`, authToken: jwt });
    const open = (device: string) =>
      connect({ path: join(dir, `${device}.db`), url: `https://${host}`, authToken: jwt, clientName: device });

    // --- baseline: the shape of `accounts` before the migration ----------
    const a = await open('A');
    await a.exec(
      'create table accounts(id text primary key, name text not null, parent_id text, type text not null, commodity text, color text)',
    );
    await a.exec("insert into accounts values ('a1', 'Banco', null, 'asset', 'USD', null)");
    await a.exec("insert into accounts values ('a2', 'Comida', null, 'expense', null, 'azul')");
    await a.push();
    say(`baseline   server accounts: ${(await serverColumns(server, 'accounts')).join(', ')}`);

    const b = await open('B');
    await b.pull();
    say(`baseline   B accounts:      ${(await columns(b, 'accounts')).join(', ')}`);

    // --- control 1: add column (what migrateSchema() does all the time) ---
    say(`add column A exec: ${await attempt(() => a.exec('alter table accounts add column extra text'))}`);
    say(`add column A push: ${await attempt(() => a.push())}`);
    say(`add column server: ${(await serverColumns(server, 'accounts')).join(', ')}`);
    await b.pull();
    say(`add column B:      ${(await columns(b, 'accounts')).join(', ')}`);

    // --- control 2: rename table, the known local-only statement ---------
    await a.exec('create table scratch_old(id text primary key)');
    await a.push();
    say(`rename A exec:  ${await attempt(() => a.exec('alter table scratch_old rename to scratch_new'))}`);
    say(`rename A push:  ${await attempt(() => a.push())}`);
    say(`rename A local: ${(await localTables(a)).join(', ')}`);
    say(`rename server:  ${(await serverTables(server)).join(', ')}`);

    // --- the case under test: drop column ---------------------------------
    say(`drop column A exec:  ${await attempt(() => a.exec('alter table accounts drop column commodity'))}`);
    say(`drop column A local: ${(await columns(a, 'accounts')).join(', ')}`);
    say(`drop column A push:  ${await attempt(() => a.push())}`);
    say(`drop column server:  ${(await serverColumns(server, 'accounts')).join(', ')}`);

    // A row written after the drop must still arrive (a push that replays
    // row changes against a schema it no longer agrees with is the failure
    // mode that matters).
    say(
      `insert after drop A: ${await attempt(async () => {
        await a.exec("insert into accounts(id, name, type) values ('a3', 'Efectivo', 'asset')");
        await a.push();
      })}`,
    );
    const rows = await server.execute('select id from accounts order by id');
    say(`server rows:         ${rows.rows.map((r) => String(r.id)).join(', ')}`);

    // B: an un-updated device. First it writes a row naming the old column
    // (an app build that still reads/writes `commodity`), then syncs.
    say(
      `B stale write:       ${await attempt(async () => {
        await b.exec("update accounts set commodity = 'ARS' where id = 'a2'");
      })}`,
    );
    say(`B push:              ${await attempt(() => b.push())}`);
    say(`B pull:              ${await attempt(() => b.pull())}`);
    say(`B after pull:        ${(await columns(b, 'accounts')).join(', ')}`);
    say(`B select commodity:  ${await attempt(async () => (await b.prepare('select commodity from accounts')).all())}`);

    // Is B stuck? The failed change is still in its outbox; a later push of
    // something unrelated tells whether one bad row jams every later write.
    say(
      `B later push:        ${await attempt(async () => {
        await b.exec("insert into accounts(id, name, type) values ('b1', 'Nueva', 'asset')");
        await b.push();
      })}`,
    );
    say(`B push again:        ${await attempt(() => b.push())}`);
    const afterB = await server.execute('select id, name from accounts order by id');
    say(`server rows:         ${afterB.rows.map((r) => `${r.id}=${r.name}`).join(', ')}`);

    // C: a device that joins after the migration (bootstrap from server).
    const c = await open('C');
    await c.pull();
    say(`C fresh bootstrap:   ${(await columns(c, 'accounts')).join(', ')}`);

    await a.close();
    await b.close();
    await c.close();
    server.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
    await api(`/databases/${name}`, { method: 'DELETE' }).catch((e) =>
      console.error(`could not delete ${name}: ${e.message} \u2014 delete it by hand`),
    );
  }
  return log;
}

/**
 * The stale-device cases on their own database each: B syncs before the drop,
 * A drops and pushes, then B writes [staleWrite] (which may or may not name
 * the dropped column) and tries to push, then pulls, then pushes again.
 */
async function staleCase(label: string, pkg: string, title: string, staleWrite: string) {
  const { connect } = (await import(pkg)) as {
    connect(opts: { path: string; url: string; authToken: string; clientName?: string }): Promise<SyncDb>;
  };
  const name = `ddl-stale-${label}-${Date.now().toString(36)}`;
  const created = await api<{ database: { Hostname: string } }>('/databases', {
    method: 'POST',
    body: JSON.stringify({ name, group: GROUP }),
  });
  const host = created.database.Hostname;
  const dir = mkdtempSync(join(tmpdir(), 'ddl-stale-'));
  const say = (line: string) => console.log(`  [${title}] ${line}`);
  try {
    const { jwt } = await api<{ jwt: string }>(`/databases/${name}/auth/tokens`, { method: 'POST', body: '{}' });
    const server = createClient({ url: `https://${host}`, authToken: jwt });
    const open = (device: string) =>
      connect({ path: join(dir, `${device}.db`), url: `https://${host}`, authToken: jwt, clientName: device });
    const a = await open('A');
    await a.exec(
      'create table accounts(id text primary key, name text not null, parent_id text, type text not null, commodity text, color text)',
    );
    await a.exec("insert into accounts values ('a1', 'Banco', null, 'asset', 'USD', null)");
    await a.exec('create table other(id text primary key)');
    await a.push();
    const b = await open('B');
    await b.pull();
    await a.exec('alter table accounts drop column commodity');
    await a.push();

    say(`write:        ${await attempt(() => b.exec(staleWrite))}`);
    say(`push:         ${await attempt(() => b.push())}`);
    say(`pull:         ${await attempt(() => b.pull())}`);
    say(`B columns:    ${(await columns(b, 'accounts')).join(', ')}`);
    const localRows = await (await b.prepare('select id, name from accounts order by id')).all();
    say(`B local rows: ${JSON.stringify(localRows)}`);
    say(`push again:   ${await attempt(() => b.push())}`);
    say(
      `unrelated:    ${await attempt(async () => {
        await b.exec("insert into other values ('o1')");
        await b.push();
      })}`,
    );
    const acc = await server.execute('select * from accounts order by id');
    say(`server accounts: ${JSON.stringify(acc.rows.map((r) => ({ ...r })))}`);
    const oth = await server.execute('select id from other');
    say(`server other:    ${oth.rows.map((r) => String(r.id)).join(', ') || '(empty)'}`);
    await a.close();
    await b.close();
    server.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
    await api(`/databases/${name}`, { method: 'DELETE' }).catch((e) =>
      console.error(`could not delete ${name}: ${e.message} — delete it by hand`),
    );
  }
}

/**
 * The app's own sync order (Database.android.kt / TursoDatabase.ios.kt):
 * push, then pull, with a failed push throwing before the pull. A stale
 * device queues one bad row and one good row in another table, then "syncs"
 * a few times the way the app does.
 */
async function appOrderCase(label: string, pkg: string) {
  const { connect } = (await import(pkg)) as {
    connect(opts: { path: string; url: string; authToken: string; clientName?: string }): Promise<SyncDb>;
  };
  const name = `ddl-order-${label}-${Date.now().toString(36)}`;
  const created = await api<{ database: { Hostname: string } }>('/databases', {
    method: 'POST',
    body: JSON.stringify({ name, group: GROUP }),
  });
  const host = created.database.Hostname;
  const dir = mkdtempSync(join(tmpdir(), 'ddl-order-'));
  const say = (line: string) => console.log(`  [app order] ${line}`);
  try {
    const { jwt } = await api<{ jwt: string }>(`/databases/${name}/auth/tokens`, { method: 'POST', body: '{}' });
    const server = createClient({ url: `https://${host}`, authToken: jwt });
    const open = (device: string) =>
      connect({ path: join(dir, `${device}.db`), url: `https://${host}`, authToken: jwt, clientName: device });
    const a = await open('A');
    await a.exec('create table accounts(id text primary key, name text not null, type text not null, commodity text)');
    await a.exec("insert into accounts values ('a1', 'Banco', 'asset', 'USD')");
    await a.exec('create table other(id text primary key)');
    await a.push();
    const b = await open('B');
    await b.pull();
    await a.exec('alter table accounts drop column commodity');
    await a.push();
    // Something else happens on A that B should eventually see.
    await a.exec("insert into other values ('from-a')");
    await a.push();

    await b.exec("insert into other values ('good-b')");
    await b.exec("insert into accounts(id, name, type, commodity) values ('b1', 'Nueva', 'asset', null)");
    await b.exec("insert into other values ('good-b2')");
    const appSync = async () => {
      await b.push();
      await b.pull();
    };
    for (let i = 1; i <= 3; i++) say(`sync #${i}:      ${await attempt(appSync)}`);
    say(`B columns:     ${(await columns(b, 'accounts')).join(', ')}`);
    const bOther = await (await b.prepare('select id from other order by id')).all();
    say(`B other:       ${bOther.map((r) => String(r.id)).join(', ')}`);
    const sOther = await server.execute('select id from other order by id');
    say(`server other:  ${sOther.rows.map((r) => String(r.id)).join(', ')}`);
    const sAcc = await server.execute('select id from accounts order by id');
    say(`server accts:  ${sAcc.rows.map((r) => String(r.id)).join(', ')}`);
    await a.close();
    await b.close();
    server.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
    await api(`/databases/${name}`, { method: 'DELETE' }).catch((e) =>
      console.error(`could not delete ${name}: ${e.message} — delete it by hand`),
    );
  }
}

const STALE_WRITES: Array<[string, string]> = [
  // What the pre-removal app did: every insert named the column (as null).
  ['insert naming it', "insert into accounts(id, name, parent_id, type, commodity, color) values ('b1', 'Nueva', null, 'asset', null, null)"],
  ['insert without it', "insert into accounts(id, name, type) values ('b2', 'Otra', 'asset')"],
  ['update other col', "update accounts set name = 'Banco Nación' where id = 'a1'"],
  ['update the column', "update accounts set commodity = 'ARS' where id = 'a1'"],
  ['delete', "delete from accounts where id = 'a1'"],
];

const wanted = process.argv.slice(2);
for (const [label, pkg] of Object.entries(ENGINES)) {
  if (wanted.length > 0 && !wanted.includes(label)) continue;
  await probe(label, pkg);
  console.log(`\n--- stale device cases, engine ${label}`);
  for (const [title, sql] of STALE_WRITES) await staleCase(label, pkg, title, sql);
  console.log(`\n--- app sync order (push, then pull), engine ${label}`);
  await appOrderCase(label, pkg);
}
