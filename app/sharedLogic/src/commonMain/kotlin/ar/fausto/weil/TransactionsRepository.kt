package ar.fausto.weil

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow

/** Keyset cursor for journal and register pages: strictly older rows begin here. */
data class LedgerCursor(val date: Long, val id: String)

/**
 * Double-entry store: transactions (the journal header) with balanced
 * postings (the movements). All multi-statement writes happen inside
 * begin/commit on the same connection+dispatcher, mirroring an SQL transaction.
 */
class TransactionsRepository(private val db: DatabaseProvider) {

    /** Emitted after every local mutation; screens collect it to refresh. */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    suspend fun syncNow() {
        db.use { it.sync() }
    }

    suspend fun add(
        date: Long,
        payee: String,
        note: String?,
        drafts: List<DraftPosting>,
        sourceDocumentId: String? = null,
    ): String {
        val txId = Uuid.random().toString()
        val postings = resolvePostings(drafts).map { it.copy(transactionId = txId) }
        writeAtomically {
            insertTransaction(txId, date, payee, note, sourceDocumentId)
            insertPostings(postings)
        }
        emitChange()
        return txId
    }

    /**
     * Batch insert of reviewed import candidates: one SQL transaction for the
     * whole set, so a failure halfway leaves the ledger untouched. Returns the
     * new transaction ids in input order.
     */
    suspend fun addAll(entries: List<NewTransaction>): List<String> {
        if (entries.isEmpty()) return emptyList()
        val prepared = entries.map { entry ->
            val txId = Uuid.random().toString()
            txId to (entry to resolvePostings(entry.drafts).map { it.copy(transactionId = txId) })
        }
        writeAtomically {
            for ((txId, pair) in prepared) {
                val (entry, postings) = pair
                insertTransaction(txId, entry.date, entry.payee, entry.note, entry.sourceDocumentId)
                insertPostings(postings)
            }
        }
        emitChange()
        return prepared.map { it.first }
    }

    suspend fun update(
        id: String,
        date: Long,
        payee: String,
        note: String?,
        drafts: List<DraftPosting>,
    ) {
        val postings = resolvePostings(drafts).map { it.copy(transactionId = id) }
        writeAtomically {
            execute(
                if (note.isNullOrBlank()) {
                    "update ledger_transactions set date = :date, payee = :payee, note = null" +
                        " where id = :id"
                } else {
                    "update ledger_transactions set date = :date, payee = :payee, note = :note" +
                        " where id = :id"
                },
                buildMap {
                    put(":date", date)
                    put(":payee", payee.trim())
                    put(":id", id)
                    if (!note.isNullOrBlank()) put(":note", note.trim())
                },
            )
            execute("delete from postings where transaction_id = :id", mapOf(":id" to id))
            insertPostings(postings)
        }
        emitChange()
    }

    suspend fun delete(id: String) {
        writeAtomically {
            // postings first: cross-connection FK cascades are not enforced
            execute("delete from postings where transaction_id = :id", mapOf(":id" to id))
            execute("delete from ledger_transactions where id = :id", mapOf(":id" to id))
        }
        emitChange()
    }

    /** Page of the journal, newest first; postings grouped in memory. */
    suspend fun page(limit: Int = LIST_PAGE_SIZE, before: LedgerCursor? = null): List<Transaction> =
        db.useForRead { d ->
            val txs = d.query(
                "select t.id, t.date, t.payee, t.note, t.created_at from ledger_transactions t" +
                    (if (before == null) "" else " where $TX_CURSOR_FILTER") +
                    " order by t.date desc, t.id desc limit $limit",
                cursorParams(before),
            ) { rows ->
                rows.filter { it.size >= 5 }.map { row ->
                    Transaction(
                        id = row[0]?.toString() ?: "",
                        date = (row[1] as? Number)?.toLong() ?: 0L,
                        payee = row[2]?.toString() ?: "",
                        note = row[3]?.toString(),
                        createdAt = (row[4] as? Number)?.toLong() ?: 0L,
                        postings = emptyList(),
                    )
                }.toList()
            }
            if (txs.isEmpty()) {
                txs
            } else {
                val byTx = d.query(
                    "select id, transaction_id, account_id, amount_minor, commodity from postings" +
                        " where transaction_id in (${quoteList(txs.map { it.id })})",
                    null,
                ) { rows ->
                    val grouped = mutableMapOf<String, MutableList<Posting>>()
                    for (row in rows) {
                        if (row.size < 5) continue
                        val postingId = row[0]?.toString() ?: continue
                        val transactionId = row[1]?.toString() ?: continue
                        val accountId = row[2]?.toString() ?: continue
                        val amount = (row[3] as? Number)?.toLong() ?: continue
                        val commodity = row[4]?.toString() ?: continue
                        grouped.getOrPut(transactionId) { mutableListOf() } += Posting(
                            id = postingId,
                            transactionId = transactionId,
                            accountId = accountId,
                            amountMinor = amount,
                            commodity = commodity,
                        )
                    }
                    grouped
                }
                txs.map { tx -> tx.copy(postings = byTx[tx.id].orEmpty()) }
            }
        }

    /** One transaction with its postings, or null when unknown. */
    suspend fun get(id: String): Transaction? = db.useForRead { d ->
        val safe = quoteList(listOf(id))
        val tx = d.query(
            "select id, date, payee, note, created_at from ledger_transactions where id in ($safe)",
            null,
        ) { rows ->
            rows.filter { it.size >= 5 }.map { row ->
                Transaction(
                    id = row[0]?.toString() ?: "",
                    date = (row[1] as? Number)?.toLong() ?: 0L,
                    payee = row[2]?.toString() ?: "",
                    note = row[3]?.toString(),
                    createdAt = (row[4] as? Number)?.toLong() ?: 0L,
                    postings = emptyList(),
                )
            }.firstOrNull()
        } ?: return@useForRead null
        tx.copy(
            postings = d.query(
                "select id, transaction_id, account_id, amount_minor, commodity from postings" +
                    " where transaction_id in ($safe)",
                null,
            ) { rows ->
                rows.filter { it.size >= 5 }.mapNotNull { row ->
                    val postingId = row[0]?.toString() ?: return@mapNotNull null
                    val txId = row[1]?.toString() ?: return@mapNotNull null
                    val accountId = row[2]?.toString() ?: return@mapNotNull null
                    val amount = (row[3] as? Number)?.toLong() ?: return@mapNotNull null
                    val commodity = row[4]?.toString() ?: return@mapNotNull null
                    Posting(postingId, txId, accountId, amount, commodity)
                }.toList()
            },
        )
    }

    /**
     * Register for an account (optionally a whole subtree via the account
     * tree): postings newest first with running balances computed by walking
     * the page oldest→newest on top of the opening sum.
     */
    suspend fun register(
        subtreeIds: List<String>,
        limit: Int = LIST_PAGE_SIZE,
        before: LedgerCursor? = null,
    ): List<RegisterEntry> {
        if (subtreeIds.isEmpty()) return emptyList()
        val idList = quoteList(subtreeIds)
        return db.useForRead { d ->
            val opening = d.query(
                "select sum(p.amount_minor) from postings p" +
                    " join ledger_transactions t on p.transaction_id = t.id" +
                    " where p.account_id in ($idList)" +
                    (if (before == null) "" else " and ($TX_CURSOR_FILTER)"),
                cursorParams(before),
            ) { rows -> (rows.firstOrNull()?.firstOrNull() as? Number)?.toLong() ?: 0L }

            val entries = d.query(
                "select p.id, p.transaction_id, p.account_id, p.amount_minor, p.commodity," +
                    " t.date, t.payee" +
                    " from postings p join ledger_transactions t on p.transaction_id = t.id" +
                    " where p.account_id in ($idList)" +
                    (if (before == null) "" else " and ($TX_CURSOR_FILTER)") +
                    " order by t.date desc, t.id desc limit $limit",
                cursorParams(before),
            ) { rows ->
                rows.filter { it.size >= 7 }.mapNotNull { row ->
                    val id = row[0]?.toString() ?: return@mapNotNull null
                    val transactionId = row[1]?.toString() ?: return@mapNotNull null
                    val accountId = row[2]?.toString() ?: return@mapNotNull null
                    val amount = (row[3] as? Number)?.toLong() ?: return@mapNotNull null
                    val commodity = row[4]?.toString() ?: return@mapNotNull null
                    val date = (row[5] as? Number)?.toLong() ?: 0L
                    val payee = row[6]?.toString() ?: ""
                    Posting(id, transactionId, accountId, amount, commodity) to
                        (date to payee)
                }.toList()
            }

            var running = opening
            val balanceAfter = HashMap<String, Long>(entries.size)
            for ((posting, _) in entries.asReversed()) {
                running += posting.amountMinor
                balanceAfter[posting.id] = running
            }
            entries.map { (posting, meta) ->
                RegisterEntry(posting, meta.first, meta.second, Money(balanceAfter.getValue(posting.id), posting.commodity))
            }
        }
    }

    /** Leaf-level totals per commodity, straight from postings. */
    suspend fun leafBalances(): Map<String, Map<String, Long>> = db.useForRead { d ->
        d.query(
            "select account_id, commodity, sum(amount_minor) from postings" +
                " group by account_id, commodity",
            null,
        ) { rows ->
            val result = mutableMapOf<String, MutableMap<String, Long>>()
            for (row in rows) {
                if (row.size < 3) continue
                val accountId = row[0]?.toString() ?: continue
                val commodity = row[1]?.toString() ?: continue
                val total = (row[2] as? Number)?.toLong() ?: continue
                result.getOrPut(accountId) { mutableMapOf() }[commodity] = total
            }
            result
        }
    }

    private suspend fun writeAtomically(block: Database.() -> Unit) {
        db.use { d ->
            d.execute("begin immediate")
            try {
                d.block()
                d.execute("commit")
            } catch (t: Throwable) {
                try {
                    d.execute("rollback")
                } catch (_: Throwable) {
                }
                throw t
            }
        }
    }

    private suspend fun emitChange() {
        changes.tryEmit(Unit)
    }

    /**
     * Unbound named placeholders silently bind nothing, so the optional
     * columns are composed into the statement instead of always listed.
     */
    private fun Database.insertTransaction(
        txId: String,
        date: Long,
        payee: String,
        note: String?,
        sourceDocumentId: String?,
    ) {
        val columns = mutableListOf("id", "date", "payee", "created_at")
        val values = mutableListOf(":id", ":date", ":payee", ":created_at")
        val params = mutableMapOf<String, Any>(
            ":id" to txId,
            ":date" to date,
            ":payee" to payee.trim(),
            ":created_at" to epochMillis(),
        )
        if (!note.isNullOrBlank()) {
            columns += "note"
            values += ":note"
            params[":note"] = note.trim()
        }
        if (!sourceDocumentId.isNullOrBlank()) {
            columns += "source_document_id"
            values += ":source_document"
            params[":source_document"] = sourceDocumentId
        }
        execute(
            "insert into ledger_transactions(${columns.joinToString(", ")})" +
                " values(${values.joinToString(", ")})",
            params,
        )
    }

    private fun Database.insertPostings(postings: List<Posting>) {
        for (p in postings) {
            execute(
                "insert into postings(id, transaction_id, account_id, amount_minor, commodity)" +
                    " values(:id, :tx, :account, :amount, :commodity)",
                mapOf(
                    ":id" to p.id,
                    ":tx" to p.transactionId,
                    ":account" to p.accountId,
                    ":amount" to p.amountMinor,
                    ":commodity" to p.commodity,
                ),
            )
        }
    }

    private companion object {
        const val TX_CURSOR_FILTER = "(t.date < :date or (t.date = :date and t.id < :tid))"

        fun cursorParams(before: LedgerCursor?): Map<String, Any>? =
            if (before == null) {
                null
            } else {
                mapOf(":date" to before.date, ":tid" to before.id)
            }
    }
}

/** Single-quoted SQL literal list, used for small in-clauses of row ids. */
private fun quoteList(ids: List<String>): String =
    ids.joinToString(",") { "'" + it.replace("'", "''") + "'" }
