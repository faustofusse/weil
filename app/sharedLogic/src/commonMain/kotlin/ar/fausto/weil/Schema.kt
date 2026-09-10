package ar.fausto.weil

/**
 * Idempotent schema applied on every database open on all platforms.
 * The Turso engine replicates row state (including deletes), so unlike the
 * old app there is no device_id/modified_at/deleted_at/synced_at bookkeeping.
 */
const val SCHEMA_SQL =
    "drop table if exists cuentas;" +
    "create table if not exists accounts(id text primary key not null, name text not null);" +
    "create table if not exists ledger_transactions(" +
    "id text primary key not null," +
    "date integer not null," +
    "payee text not null," +
    "note text," +
    "source_notification_id text," +
    "source_email_id text," +
    "created_at integer not null);" +
    "create index if not exists idx_ledger_tx_date on ledger_transactions(date desc, id desc);" +
    "create table if not exists postings(" +
    "id text primary key not null," +
    "transaction_id text not null," +
    "account_id text not null," +
    "amount_minor integer not null," +
    "commodity text not null);" +
    "create index if not exists idx_postings_tx on postings(transaction_id);" +
    "create index if not exists idx_postings_account on postings(account_id, transaction_id);" +
    "create table if not exists notifications(" +
    "id text primary key not null," +
    "package_name text not null," +
    "title text not null," +
    "text text not null," +
    "category text," +
    "post_time integer not null," +
    "received_at integer not null);" +
    "create index if not exists idx_notifications_post_time on notifications(post_time desc);" +
    "create table if not exists applications(" +
    "id text primary key not null," +
    "name text not null," +
    "icon blob," +
    "is_system_app integer not null default 0," +
    "first_seen_at integer not null," +
    "notification_count integer not null default 0," +
    "last_notification_at integer);" +
    "create table if not exists emails(" +
    "id text primary key not null," +
    "from_email text not null," +
    "to_email text not null," +
    "subject text," +
    "body_text text," +
    "received_at integer not null);" +
    "create index if not exists idx_emails_received on emails(received_at desc);" +
    "update emails set received_at = received_at * 1000 where received_at < 1000000000000;"

/**
 * Column migration for the account tree: parent_id + type on pre-existing
 * accounts tables. `alter table` is not idempotent in SQLite, so the columns
 * are checked via pragma before altering; runs after [SCHEMA_SQL] on open.
 */
fun Database.migrateSchema() {
    val columns = query("pragma table_info(accounts)", null) { rows ->
        rows.mapNotNull { it.getOrNull(1)?.toString() }.toSet()
    }
    if ("parent_id" !in columns) {
        execute("alter table accounts add column parent_id text")
    }
    if ("type" !in columns) {
        execute("alter table accounts add column type text not null default 'asset'")
    }
}
