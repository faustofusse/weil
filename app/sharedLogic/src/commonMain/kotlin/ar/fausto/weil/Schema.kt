package ar.fausto.weil

/**
 * Idempotent schema applied on every database open on all platforms.
 * The Turso engine replicates row state (including deletes), so unlike the
 * old app there is no device_id/modified_at/deleted_at/synced_at bookkeeping.
 */
const val SCHEMA_SQL =
    "drop table if exists cuentas;" +
    "create table if not exists accounts(id text primary key not null, name text not null);" +
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
