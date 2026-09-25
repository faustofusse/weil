# Schema changes on the Turso sync engine

Every device holds a full local copy of the user's database and syncs it with
the server through the Turso sync engine. A migration runs on *one* device and
has to reach the server and every other device through that same sync. Some
DDL statements do and some don't, and the engine doesn't tell you which: the
statement succeeds locally and `sync()` returns OK either way. So a new kind of
DDL gets checked with the probe below before it goes into `migrateSchema()`.

## Results

Measured with `scripts/sync-ddl-probe` against `@tursodatabase/sync`
`0.8.0-pre.7` and `0.8.0-pre.9`. Both gave identical results. The app ships
`turso_sync_sdk_kit` `0.8.0-pre.8` (commit `7e2fc39de`), which was never
published to npm, so these are the two releases on either side of it.

| Statement | Replicates? | Notes |
|---|---|---|
| `create table`, `create index`, `insert … select`, `drop table` | yes | Checked earlier on-device, when `ledger_transactions` became `transactions`. |
| `alter table … add column` | yes | Server and other devices get the column. `migrateSchema()` depends on this. |
| `alter table … rename to` | **no** | Applies locally, `push` returns OK, the server keeps the old name. Reproduced by the probe as a control: this is what proves the harness can tell a local-only change from a replicated one. |
| `alter table … drop column` | yes | The server drops it. A device that already synced gets the new shape on its next pull, and a device that joins later bootstraps without the column. Rows written after the drop push normally. |

### What a stale device sees after a `drop column`

"Stale" means a device whose local copy still has the column, because it
hasn't pulled since the drop, or it runs an app build that still uses the
column. Cases run against `accounts` with `commodity` dropped:

| Write on the stale device before it syncs | Push | Result |
|---|---|---|
| `insert` naming the column | fails: `table accounts has no column named commodity` | **row lost**, on the server and locally |
| `insert` **not** naming the column | fails the same way | **row lost**. The engine pushes the whole row image, dropped column included |
| `update` of another column | ok | applied |
| `update` of the dropped column | fails: `no such column: commodity` | change lost, row reverts to the server's |
| `delete` | ok | applied |

In the app's sync order (`Database.android.kt`, `TursoDatabase.ios.kt`: push,
then pull, where a failed push throws before the pull), the device does **not**
get stuck:

- the first `sync()` fails,
- the next one succeeds, dropping only the rejected row change,
- good changes queued before and after it (other tables) survive on both sides,
- remote changes arrive normally from then on.

After the pull, the stale device's local table no longer has the column, so any
statement that names it fails with `no such column`.

## What this means for a real column drop

`drop column` itself is safe on this engine. The risk is the app builds still
installed:

1. **Old builds break on read.** A build whose queries name the column fails
   every such query once the drop reaches it: for `accounts.commodity`, that
   is every account read (Home, tree, pickers). Nothing heals this until the
   device updates. The old build's `migrateSchema()` won't re-add the column
   either: its `user_version` already equals its own `SCHEMA_VERSION`, so the
   migration is skipped.
2. **Old builds lose writes.** Any account insert or commodity update on an
   old build between the drop and the next sync is silently discarded, even an
   insert that never mentions the column.
3. **A re-add loop is possible but bounded.** An old build that *does* run its
   migration (its `user_version` is below its `SCHEMA_VERSION`) re-adds the
   column with `addColumn`, and that replicates. A newer build drops it again
   on its next migration. It stops once every device is on a build with the
   drop.

So the order is:

1. Ship a build that neither reads nor writes the column. For
   `accounts.commodity`, that is the build that removed per-account currency.
   The worker's `import.ts` and the web app stopped reading it at the same time.
2. Wait until every device of every user runs that build (for this app that
   means: your own phones, plus anyone on Play internal testing or TestFlight).
3. Only then, in `migrateSchema()`:
   ```kotlin
   if ("commodity" in columns) {
       // A dropColumn() helper mirroring addColumn(): swallow "no such
       // column", because the pragma reads local state, which can lag behind
       // a drop another device already pushed.
       dropColumn("alter table accounts drop column commodity")
   }
   ```
   Delete the `addColumn("… add column commodity text")` block, and bump
   `SCHEMA_VERSION`. Leave a best-effort `sync()` before the drop, as
   `migrateLegacyTransactionsTable()` does: pushing pending row changes first
   keeps them from being rendered against the new shape.

Status of `accounts.commodity`: step 1 is done. Steps 2–3 are not; the column
is still created and simply unused.

## Running the probe

```sh
cd scripts/sync-ddl-probe
npm run setup                          # root + one pinned engine per folder
set -a; . ../../../auth/.env; set +a   # TURSO_API_TOKEN (platform token)
node --experimental-strip-types probe.ts        # every engine
node --experimental-strip-types probe.ts pre9   # one
```

It creates throwaway databases named `ddl-probe-*`, `ddl-stale-*` and
`ddl-order-*` in the `finance` group, with the same `POST /databases {group}`
the auth worker uses for real users. It deletes each one in a `finally`, and
prints a line if a delete fails. It never touches a user's database. A full run
takes about a minute.

To check a different statement, edit the "case under test" block in `probe()`
and the `STALE_WRITES` list.

### Gotchas found while building it

- **Pin the whole chain, per version.** `@tursodatabase/sync` depends on
  `sync-common` and `database-common` with `^`, so a `0.8.0-pre.7` addon ends
  up driven by the `pre.13` JS layer. Every push then fails with
  `JSON parse error: invalid type: integer … expected struct PipelineReqBody`.
  Each engine lives in `engines/preN/` with `overrides` pinning all three.
- **Node, not Bun.** Under Bun 1.2 the first push fails with a server-side
  `JSON parse error: number out of range`. Node 22+ runs the `.ts` directly
  with `--experimental-strip-types`.
- `prepare()` is async in these packages: `(await db.prepare(sql)).all()`.
- It is not literally on-device. It runs the same engine and the same sync
  protocol against the same kind of server database, from macOS, on the
  releases around the shipped one. If the shipped engine is ever upgraded past
  `pre.9`, add the new version under `engines/` and re-run before relying on
  these results.
