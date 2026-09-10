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
    suspend fun tree(): List<AccountNode> = db.use { d ->
        val all = d.query(
            "select id, name, parent_id, type from accounts order by lower(name), id",
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
                )
            }.toList()
        }
        buildTree(all)
    }

    /** Flat ids of an account's subtree (itself included), for register rollups. */
    fun subtreeIds(nodes: List<AccountNode>, accountId: String): List<String> =
        (nodes.firstOrNull { it.account.id == accountId }?.selfAndDescendants
            ?: listOf()).map { it.account.id }

    suspend fun add(
        name: String,
        type: AccountType,
        parentId: String? = null,
    ) = db.use { d ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "account name cannot be empty" }
        if (parentId == null) {
            d.execute(
                "insert into accounts(id, name, parent_id, type) values(:id, :name, null, :type)",
                mapOf(":id" to Uuid.random().toString(), ":name" to trimmed, ":type" to type.db),
            )
        } else {
            val parent = fetch(d, parentId) ?: throw IllegalArgumentException("parent account not found")
            if (parent.type != type) {
                throw IllegalArgumentException("children must share the parent's type (${parent.type.db})")
            }
            d.execute(
                "insert into accounts(id, name, parent_id, type) values(:id, :name, :parent, :type)",
                mapOf(
                    ":id" to Uuid.random().toString(),
                    ":name" to trimmed,
                    ":parent" to parentId,
                    ":type" to type.db,
                ),
            )
        }
        d.sync()
    }

    suspend fun rename(id: String, name: String) = db.use { d ->
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "account name cannot be empty" }
        d.execute(
            "update accounts set name = :name where id = :id",
            mapOf(":name" to trimmed, ":id" to id),
        )
        d.sync()
    }

    /** Re-parents; guards against cycles and cross-type moves. */
    suspend fun reparent(id: String, newParentId: String) = db.use { d ->
        val account = fetch(d, id) ?: throw IllegalArgumentException("account not found")
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
        d.execute(
            "update accounts set parent_id = :parent where id = :id",
            mapOf(":parent" to newParentId, ":id" to id),
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
        d.sync()
    }

    private fun fetch(d: Database, id: String): Account? =
        d.query(
            "select id, name, parent_id, type from accounts where id = :id",
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
