package ar.fausto.weil

import kotlinx.coroutines.flow.MutableSharedFlow

/** Settings key holding the default account of [type]; see [SettingsRepository]. */
fun defaultAccountKey(type: AccountType): String = "default_account.${type.db}"

/**
 * The user's display name, mirrored from the auth worker. Besides the profile
 * screen, the message reader uses it to tell money the user sent to
 * themselves (a cash withdrawal order in their own name) from the same
 * template addressed to someone else.
 */
const val PROFILE_NAME_KEY = "profile.name"

/**
 * User preferences stored in the user's own Turso database (the secure store
 * is device-local and holds tokens), so a default set on the phone is the
 * default on every paired device. One row per key: the sync engine resolves
 * concurrent edits row by row, which is exactly right for a scalar.
 *
 * Nothing here validates that a stored account id still exists — accounts can
 * be deleted or re-typed on another device. [resolveDefault] does that against
 * the live tree at the point of use, and falls back to the heuristic default.
 */
class SettingsRepository(private val db: DatabaseProvider) {

    /** Emitted after every write; the UI collects it to re-read. */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    suspend fun all(): Map<String, String> = db.useForRead { d ->
        d.query("select key, value from settings", null) { rows ->
            rows.mapNotNull { row ->
                val key = row.getOrNull(0)?.toString() ?: return@mapNotNull null
                val value = row.getOrNull(1)?.toString() ?: return@mapNotNull null
                key to value
            }.toList().toMap()
        }
    }

    /** A null [value] deletes the row (back to the heuristic default). */
    suspend fun set(key: String, value: String?) {
        db.use { d ->
            if (value == null) {
                d.execute("delete from settings where key = :key", mapOf(":key" to key))
            } else {
                // `insert or replace` rather than an upsert with a do-update
                // clause: a named parameter repeated in one statement binds
                // only its first position on this engine.
                d.execute(
                    "insert or replace into settings(key, value, updated_at) " +
                        "values(:key, :value, :now)",
                    mapOf(":key" to key, ":value" to value, ":now" to epochMillis()),
                )
            }
            d.sync()
        }
        changes.tryEmit(Unit)
    }

    /** Stored ids only; use [resolveDefault] to turn one into a usable pick. */
    suspend fun defaultAccounts(): Map<AccountType, String> {
        val rows = all()
        return AccountType.entries.mapNotNull { type ->
            rows[defaultAccountKey(type)]?.let { type to it }
        }.toMap()
    }

    /** A null [accountId] clears the default for [type]. */
    suspend fun setDefaultAccount(type: AccountType, accountId: String?) =
        set(defaultAccountKey(type), accountId)
}

/**
 * The account to preselect for [type]: the user's stored default when that
 * account still exists with that type, else the heuristic that was the only
 * behaviour before defaults existed.
 *
 * The fallback is deliberately weaker for income/expense than for the rest:
 * picking an arbitrary category for the user silently miscategorizes a
 * movement, so without the seeded "Otros" (or a single candidate) this
 * returns null and the screen makes the user choose. "Which of my accounts
 * paid" is the opposite — a wrong guess is visible and one tap to fix.
 */
fun resolveDefault(
    tree: List<AccountNode>,
    type: AccountType,
    stored: String?,
): String? {
    val nodes = tree.flatMap { it.selfAndDescendants }.filter { it.account.type == type }
    if (stored != null) {
        nodes.firstOrNull { it.account.id == stored }?.let { return it.account.id }
    }
    val seedId = when (type) {
        AccountType.Expense -> EXTERNAL_EXPENSE_ID
        AccountType.Income -> EXTERNAL_INCOME_ID
        else -> null
    }
    if (seedId != null) {
        nodes.firstOrNull { it.account.id == seedId }?.let { return it.account.id }
        nodes.firstOrNull { it.account.name.equals(EXTERNAL_ACCOUNT_NAME, ignoreCase = true) }
            ?.let { return it.account.id }
        return nodes.singleOrNull()?.account?.id
    }
    // Roots first: "which of my accounts paid" is more often a top-level
    // account than the first leaf of the first subtree.
    return tree.firstOrNull { it.account.type == type }?.account?.id
        ?: nodes.firstOrNull()?.account?.id
}
