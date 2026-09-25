package ar.fausto.weil

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Account trees with an explicit type: every account has one of the five
 * [AccountType]s, children inherit the parent's type (enforced here), and the
 * tree is organizational only — postings reference ids, so renaming or
 * re-parenting never rewrites history.
 */
class AccountsRepository(private val db: DatabaseProvider) {


    /** One flat query turned into a node tree with colon-joined paths. */
    suspend fun tree(): List<AccountNode> = db.useForRead { d ->
        val all = d.query(
            "select id, name, parent_id, type, in_net_worth, icon, color " +
                "from accounts order by lower(name), id",
            null,
        ) { rows ->
            rows.mapNotNull { row ->
                val id = row[0]?.toString() ?: return@mapNotNull null
                val name = row[1]?.toString() ?: return@mapNotNull null
                val type = AccountType.fromDb(row[3]?.toString()) ?: return@mapNotNull null
                Account(
                    id = id,
                    name = name,
                    parentId = row[2]?.toString(),
                    type = type,
                    // null only mid-migration on a lagging replica; treat as included.
                    inNetWorth = (row.getOrNull(4) as? Number)?.toLong() != 0L,
                    icon = row.getOrNull(5)?.toString()?.takeIf { it.isNotBlank() },
                    color = row.getOrNull(6)?.toString()?.takeIf { it.isNotBlank() },
                )
            }.toList()
        }
        buildTree(all)
    }

    /**
     * Flat ids of an account's subtree (itself included), for register
     * rollups. [nodes] is the *root* list from [tree], so the account has to
     * be looked for at every depth: matching only the roots silently returned
     * an empty list for any child — and an empty id list reads downstream as
     * "no accounts", i.e. a register or a filtered list that is simply empty,
     * which looks like missing data rather than a bug.
     */
    fun subtreeIds(nodes: List<AccountNode>, accountId: String): List<String> =
        (nodes.flatMap { it.selfAndDescendants }
            .firstOrNull { it.account.id == accountId }
            ?.selfAndDescendants
            ?: listOf()).map { it.account.id }

    /** Returns the id of the created account (children inherit the type). */
    suspend fun add(
        name: String,
        type: AccountType,
        parentId: String? = null,
        icon: String? = null,
        color: String? = null,
    ): String = db.use { d ->
        val id = Uuid.random().toString()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "account name cannot be empty" }
        requireNameFree(d, parentId, trimmed, excludeId = null)
        // The binder takes non-null values only, so a missing icon is a
        // literal `null` in the SQL rather than an unbound parameter (an
        // unknown/unbound name silently binds nothing on this engine).
        val iconSql = if (icon.isNullOrBlank()) "null" else ":icon"
        val iconParam: Map<String, Any> =
            if (icon.isNullOrBlank()) emptyMap() else mapOf(":icon" to icon)
        val colorSql = if (color.isNullOrBlank()) "null" else ":color"
        val colorParam: Map<String, Any> =
            if (color.isNullOrBlank()) emptyMap() else mapOf(":color" to color)
        if (parentId == null) {
            d.execute(
                "insert into accounts(id, name, parent_id, type, icon, color) " +
                    "values(:id, :name, null, :type, $iconSql, $colorSql)",
                mapOf(":id" to id, ":name" to trimmed, ":type" to type.db) +
                    iconParam + colorParam,
            )
        } else {
            val parent = fetch(d, parentId) ?: throw IllegalArgumentException("parent account not found")
            if (parent.type != type) {
                throw IllegalArgumentException("children must share the parent's type (${parent.type.db})")
            }
            d.execute(
                "insert into accounts(id, name, parent_id, type, icon, color) " +
                    "values(:id, :name, :parent, :type, $iconSql, $colorSql)",
                mapOf(
                    ":id" to id,
                    ":name" to trimmed,
                    ":parent" to parentId,
                    ":type" to type.db,
                ) + iconParam + colorParam,
            )
        }
        d.sync()
        id
    }

    /** Only meaningful for Asset/Liability; excluding a node cascades to its
     *  subtree in the UI regardless of the descendants' own stored flag. */
    suspend fun setInNetWorth(id: String, included: Boolean) = db.use { d ->
        d.execute(
            "update accounts set in_net_worth = :v where id = :id",
            mapOf(":v" to (if (included) 1L else 0L), ":id" to id),
        )
        d.sync()
    }

    /**
     * Sets (or clears, with null) the account's icon key. Unvalidated on
     * purpose: the catalog lives in the UI module, and a key this build
     * doesn't know still has to round-trip through sync untouched.
     */
    suspend fun setIcon(id: String, icon: String?) = db.use { d ->
        if (icon.isNullOrBlank()) {
            d.execute("update accounts set icon = null where id = :id", mapOf(":id" to id))
        } else {
            d.execute(
                "update accounts set icon = :icon where id = :id",
                mapOf(":icon" to icon, ":id" to id),
            )
        }
        d.sync()
    }

    /**
     * Sets (or clears, with null) the account's palette key. Unvalidated for
     * the same reason as [setIcon]: the eight pairs live in the UI module, a
     * key this build doesn't know still has to survive a round trip, and a
     * stored hex would freeze a decision the next palette wants to make.
     */
    suspend fun setColor(id: String, color: String?) = db.use { d ->
        if (color.isNullOrBlank()) {
            d.execute("update accounts set color = null where id = :id", mapOf(":id" to id))
        } else {
            d.execute(
                "update accounts set color = :color where id = :id",
                mapOf(":color" to color, ":id" to id),
            )
        }
        d.sync()
    }

    suspend fun rename(id: String, name: String) = db.use { d ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "account name cannot be empty" }
        val account = fetch(d, id) ?: throw IllegalArgumentException("account not found")
        requireNameFree(d, account.parentId, trimmed, excludeId = id)
        d.execute(
            "update accounts set name = :name where id = :id",
            mapOf(":name" to trimmed, ":id" to id),
        )
        d.sync()
    }

    /**
     * Re-parents; guards against cycles and cross-type moves. A null
     * [newParentId] moves the account to the root of its type — the only way
     * back out of a subtree, and the inverse of every other move.
     */
    suspend fun reparent(id: String, newParentId: String?) = db.use { d ->
        val account = fetch(d, id) ?: throw IllegalArgumentException("account not found")
        if (newParentId != null) {
            val parent = fetch(d, newParentId)
                ?: throw IllegalArgumentException("parent account not found")
            if (parent.id == id) throw IllegalArgumentException("an account cannot be its own parent")
            if (parent.type != account.type) {
                throw IllegalArgumentException("cannot move across types (${account.type.db} → ${parent.type.db})")
            }
            var ancestor: Account? = parent
            while (ancestor != null) {
                if (ancestor.id == id) throw IllegalArgumentException("cannot move an account under a descendant")
                ancestor = ancestor.parentId?.let { fetch(d, it) }
            }
        }
        requireNameFree(d, newParentId, account.name, excludeId = id)
        d.execute(
            if (newParentId == null) {
                "update accounts set parent_id = null where id = :id"
            } else {
                "update accounts set parent_id = :parent where id = :id"
            },
            if (newParentId == null) {
                mapOf(":id" to id)
            } else {
                mapOf(":parent" to newParentId, ":id" to id)
            },
        )
        d.sync()
    }

    /** Only allowed on leaf accounts with no postings (per-connection FKs are off). */
    suspend fun delete(id: String) = db.use { d ->
        val childCount = count(
            d,
            "select count(*) from accounts where parent_id = :id",
            id,
        )
        if (childCount > 0) {
            throw IllegalArgumentException("delete the child accounts first")
        }
        val postingCount = count(
            d,
            "select count(*) from postings where account_id = :id",
            id,
        )
        if (postingCount > 0) {
            throw IllegalArgumentException("account has transactions; move or delete them first")
        }
        d.execute("delete from accounts where id = :id", mapOf(":id" to id))
        // Drop a preference pointing at the account that just went away.
        // resolveDefault() tolerates a dangling id, but leaving the row means
        // a later account reusing the id would silently inherit the default.
        d.execute(
            "delete from settings where key like 'default_account.%' and value = :id",
            mapOf(":id" to id),
        )
        d.sync()
    }

    /**
     * Siblings may not share a name (case-insensitive).
     *
     * Checked here rather than with a unique index because the sync engine
     * merges row state across devices: an index would turn two offline
     * creations into an unrepairable constraint violation at merge time.
     * This is a courtesy guard for the UI, not an invariant the reader can
     * lean on.
     */
    private fun requireNameFree(
        d: Database,
        parentId: String?,
        name: String,
        excludeId: String?,
    ) {
        val siblings = d.query(
            if (parentId == null) {
                "select id, name from accounts where parent_id is null"
            } else {
                "select id, name from accounts where parent_id = :parent"
            },
            if (parentId == null) null else mapOf(":parent" to parentId),
        ) { rows ->
            rows.map { (it.getOrNull(0)?.toString() ?: "") to (it.getOrNull(1)?.toString() ?: "") }.toList()
        }
        val clash = siblings.any { (otherId, otherName) ->
            otherId != excludeId && otherName.equals(name, ignoreCase = true)
        }
        if (clash) {
            throw IllegalArgumentException("an account named '$name' already exists here")
        }
    }

    private fun fetch(d: Database, id: String): Account? =
        d.query(
            "select id, name, parent_id, type, in_net_worth, icon, color " +
                "from accounts where id = :id",
            mapOf(":id" to id),
        ) { rows ->
            rows.filter { it.size >= 4 }
                .mapNotNull { row ->
                    AccountType.fromDb(row[3]?.toString())?.let { type ->
                        Account(
                            id = row[0]?.toString() ?: "",
                            name = row[1]?.toString() ?: "",
                            parentId = row[2]?.toString(),
                            type = type,
                            inNetWorth = (row.getOrNull(4) as? Number)?.toLong() != 0L,
                            icon = row.getOrNull(5)?.toString()?.takeIf { it.isNotBlank() },
                            color = row.getOrNull(6)?.toString()?.takeIf { it.isNotBlank() },
                        )
                    }
                }
                .firstOrNull()
        }

    private fun count(d: Database, sql: String, id: String): Long =
        d.query(sql, mapOf(":id" to id)) { rows ->
            (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L
        }

    companion object {
        /** Builds the forest; an account whose parent is missing becomes a root. */
        fun buildTree(accounts: List<Account>): List<AccountNode> {
            val byId = accounts.associateBy { it.id }
            val byParent = accounts.groupBy { it.parentId }
            fun build(account: Account, parentPath: String): AccountNode {
                val path = if (parentPath.isEmpty()) account.name else "$parentPath:${account.name}"
                val children = (byParent[account.id] ?: emptyList()).map { build(it, path) }
                return AccountNode(account, path, children)
            }
            return accounts.filter { it.parentId == null || it.parentId !in byId }
                .map { build(it, "") }
        }
    }
}
