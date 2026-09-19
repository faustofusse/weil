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
    "body_html text," +
    "received_at integer not null);" +
    "create index if not exists idx_emails_received on emails(received_at desc);" +
    "update emails set received_at = received_at * 1000 where received_at < 1000000000000;" +
    // Provenance, many-to-many: one economic event reaches the app through
    // several doors (push notification, email receipt, statement row), and a
    // later source attaches to the transaction an earlier one created instead
    // of duplicating it. `event_key` is the source-independent fingerprint
    // (see CandidateEvent.eventKey) that recognizes the same movement twice.
    "create table if not exists transaction_sources(" +
    "transaction_id text not null," +
    "kind text not null," +
    "ref text not null," +
    "event_key text," +
    "created_at integer not null," +
    "primary key(transaction_id, kind, ref));" +
    "create index if not exists idx_transaction_sources_ref on transaction_sources(kind, ref);" +
    "create index if not exists idx_transaction_sources_event on transaction_sources(event_key);" +
    // Scalar user preferences that must follow the user across devices (the
    // secure store is device-local and holds tokens only). One row per key,
    // so the sync engine's row-level last-writer-wins is exactly the right
    // semantic for a preference. See SettingsRepository.
    "create table if not exists settings(" +
    "key text primary key not null," +
    "value text not null," +
    "updated_at integer not null);"

/**
 * Bumped whenever [SCHEMA_SQL] or [migrateSchema] changes shape. Stamped into
 * `pragma user_version` after a successful apply so subsequent opens of an
 * already-migrated database can skip straight past the DDL/pragma replay
 * (the Rust parser round trip for ~13 statements is real cost on every cold
 * start otherwise).
 *
 * Forgetting this is silent on the device that introduced the change (its own
 * database was migrated while the version still differed) and fatal on every
 * other one: 5 is `accounts.in_net_worth`, which already-stamped installs
 * skipped straight past, so every account read failed with "no such column".
 */
private const val SCHEMA_VERSION = 8L

/**
 * Applies [SCHEMA_SQL] plus [migrateSchema], skipping both when this
 * database's `user_version` already matches [SCHEMA_VERSION].
 */
fun Database.applySchemaIfNeeded() {
    val current = query("pragma user_version", null) { rows ->
        (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
    }
    if (current == SCHEMA_VERSION) return
    for (statement in SCHEMA_SQL.split(';')) {
        val trimmed = statement.trim()
        if (trimmed.isNotEmpty()) execute(trimmed, null)
    }
    migrateSchema()
    execute("pragma user_version = $SCHEMA_VERSION")
}

/**
 * Column migration for the account tree: parent_id + type on pre-existing
 * accounts tables. `alter table` is not idempotent in SQLite, so the columns
 * are checked via pragma before altering; runs after [SCHEMA_SQL] on open.
 */
/**
 * Runs an `alter table ... add column`, tolerating a column that is already
 * there. On an embedded replica the `pragma table_info` guard reads the *local*
 * copy, which on a freshly created replica is still empty/stale, while the
 * `alter` is forwarded to the primary — where the column usually exists
 * already. The primary answers "duplicate column name", which is a no-op for us.
 */
private fun Database.addColumn(sql: String) {
    try {
        execute(sql)
    } catch (e: Exception) {
        if ("duplicate column name" !in (e.message ?: "")) throw e
    }
}

fun Database.migrateSchema() {
    val columns = query("pragma table_info(accounts)", null) { rows ->
        rows.mapNotNull { it.getOrNull(1)?.toString() }.toSet()
    }
    if ("parent_id" !in columns) {
        addColumn("alter table accounts add column parent_id text")
    }
    if ("type" !in columns) {
        addColumn("alter table accounts add column type text not null default 'asset'")
    }
    if ("in_net_worth" !in columns) {
        // Per-account opt-out from the Home net-worth sum; only meaningful
        // for Asset/Liability accounts, and cascades to descendants (an
        // excluded parent's children are never counted either, regardless
        // of their own flag) — see LedgerState.excludedFromNetWorth.
        addColumn("alter table accounts add column in_net_worth integer not null default 1")
    }
    if ("icon" !in columns) {
        // Icon *key* from the UI catalog (AccountIcons), not a glyph or a
        // blob: the drawing lives in the app, so re-drawing it or renaming a
        // vector never rewrites synced rows, and a key this build doesn't
        // know (written by a newer install on another device) falls back to
        // the account type's default icon.
        addColumn("alter table accounts add column icon text")
    }
    val txColumns = query("pragma table_info(ledger_transactions)", null) { rows ->
        rows.mapNotNull { it.getOrNull(1)?.toString() }.toSet()
    }
    if ("source_document_id" !in txColumns) {
        // Provenance of AI-imported transactions: the R2 content hash of the
        // analyzed document (see the worker's /import/analyze).
        addColumn("alter table ledger_transactions add column source_document_id text")
    }
    if ("time_known" !in txColumns) {
        // A statement row states a day, not a moment: `date` still holds a
        // timestamp (local midnight) but the time half of it is made up, so
        // the UI must not print it. Manual entries and notification/email
        // ingest carry a real clock time and keep the default 1.
        addColumn("alter table ledger_transactions add column time_known integer not null default 1")
        // Rows already imported from a document were stamped noon UTC by the
        // worker (09:00 in ART), which is exactly the phantom time this
        // column exists to hide. Runs only on the open that adds the column,
        // so a time the user set by hand afterwards is never demoted.
        execute(
            "update ledger_transactions set time_known = 0 where source_document_id is not null",
        )
    }
    val emailColumns = query("pragma table_info(emails)", null) { rows ->
        rows.mapNotNull { it.getOrNull(1)?.toString() }.toSet()
    }
    // Embeddings: one vector per row plus the model that produced it, so rows
    // from two different models are never compared against each other. No
    // vector index — the sync engine has vector32/vector_distance_cos but not
    // libsql_vector_idx, and the index DDL does not even parse on the device
    // (see plans/embeddings-prueba-jev.md). Search is an exact scan over a few
    // hundred candidate rows, which is milliseconds.
    for (table in listOf("ledger_transactions", "notifications", "emails")) {
        addColumn("alter table $table add column embedding F32_BLOB($EMBEDDING_DIMS)")
        addColumn("alter table $table add column embedding_model text")
    }
    if ("body_html" !in emailColumns) {
        // Sanitized HTML body (see the worker's email ingest). Null for rows
        // ingested before the MIME parser landed — those cannot be backfilled
        // from the device, only by reprocessing the archived raw message.
        addColumn("alter table emails add column body_html text")
    }
    backfillTransactionSources()
    seedDefaultAccounts()
    adoptOrphanSeedPostings()
}

/**
 * Copies the legacy single-valued `source_*` columns into
 * `transaction_sources`. Idempotent (`insert or ignore` on the natural key)
 * and deterministic, so every device converges on the same rows; the columns
 * keep being written for cheap provenance but nothing reads them.
 */
private fun Database.backfillTransactionSources() {
    listOf(
        "source_document_id" to EventSource.Document,
        "source_notification_id" to EventSource.Notification,
        "source_email_id" to EventSource.Email,
    ).forEach { (column, source) ->
        execute(
            "insert or ignore into transaction_sources(transaction_id, kind, ref, created_at) " +
                "select id, '${source.db}', $column, created_at from ledger_transactions " +
                "where $column is not null and $column <> ''",
        )
    }
}

/** Fixed ids for the seeded default accounts; synced PKs dedupe fresh devices. */
const val EXTERNAL_EXPENSE_ID = "seed-external-expense"
const val EXTERNAL_INCOME_ID = "seed-external-income"
const val EXTERNAL_ACCOUNT_NAME = "Otros"

/**
 * Seeds the default "Otros" income/expense accounts on a fresh database (only
 * when the accounts table is empty, so user deletions are never resurrected).
 * Fixed ids make two concurrently-seeded devices converge on the same rows.
 */
private fun Database.seedDefaultAccounts() {
    val count = query("select count(*) from accounts", null) { rows ->
        (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
    }
    if (count > 0) {
        // The seed used to be named "External" — English data in a Spanish
        // UI. Renamed in place, only while the row still carries the seeded
        // name (a user's own rename wins) and only for the fixed seed ids.
        // Deterministic on every open, so concurrently-migrating devices
        // converge on the same row state.
        execute(
            "update accounts set name = '$EXTERNAL_ACCOUNT_NAME' " +
                "where id in ('$EXTERNAL_EXPENSE_ID', '$EXTERNAL_INCOME_ID') and name = 'External'",
        )
        return
    }
    execute(
        "insert or ignore into accounts(id, name, parent_id, type) values" +
            "('$EXTERNAL_EXPENSE_ID', '$EXTERNAL_ACCOUNT_NAME', null, 'expense')," +
            "('$EXTERNAL_INCOME_ID', '$EXTERNAL_ACCOUNT_NAME', null, 'income')",
    )
    // The two seeds are also the starting per-type defaults, so quick entry
    // works before the user has set anything. `insert or ignore` keeps two
    // concurrently-seeded devices converging on the same rows.
    execute(
        "insert or ignore into settings(key, value, updated_at) values" +
            "('${defaultAccountKey(AccountType.Expense)}', '$EXTERNAL_EXPENSE_ID', 0)," +
            "('${defaultAccountKey(AccountType.Income)}', '$EXTERNAL_INCOME_ID', 0)",
    )
}

/**
 * Recreates a seeded "Otros" account when postings still point at it but the
 * row is gone. The import review used to assign the fixed seed ids without
 * checking they existed, so any ledger that was never seeded (the seed only
 * runs on an empty accounts table) ended up with postings referencing a
 * missing account — the journal had nothing to show but the raw id.
 *
 * Only fires when such a posting exists, so it never resurrects an account
 * the user deleted cleanly, and it's deterministic: every device converges on
 * the same row.
 */
private fun Database.adoptOrphanSeedPostings() {
    listOf(
        EXTERNAL_EXPENSE_ID to "expense",
        EXTERNAL_INCOME_ID to "income",
    ).forEach { (id, type) ->
        execute(
            "insert or ignore into accounts(id, name, parent_id, type) " +
                "select '$id', '$EXTERNAL_ACCOUNT_NAME', null, '$type' " +
                "where exists(select 1 from postings where account_id = '$id') " +
                "and not exists(select 1 from accounts where id = '$id')",
        )
    }
}
