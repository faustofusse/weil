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
        timeKnown: Boolean = true,
        sourceDocumentId: String? = null,
        sources: List<TransactionSource> = emptyList(),
        /** Only the QR placeholder writes zero-amount postings; see [resolvePostings]. */
        allowZeroAmounts: Boolean = false,
    ): String {
        val txId = Uuid.random().toString()
        val postings = resolvePostings(drafts, allowZero = allowZeroAmounts)
            .map { it.copy(transactionId = txId) }
        writeAtomically {
            insertTransaction(txId, date, payee, note, timeKnown, sourceDocumentId)
            insertPostings(postings)
            insertSources(txId, sources)
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
                insertTransaction(
                    txId,
                    entry.date,
                    entry.payee,
                    entry.note,
                    entry.timeKnown,
                    entry.sourceDocumentId,
                )
                insertPostings(postings)
                insertSources(txId, entry.sources)
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
        timeKnown: Boolean = true,
    ) {
        val postings = resolvePostings(drafts).map { it.copy(transactionId = id) }
        writeAtomically {
            execute(
                // The embedding is dropped because payee/note/accounts are
                // exactly what it was computed from; the next sweep
                // recomputes it. Cheaper and safer than keeping a vector that
                // describes a transaction the user just rewrote.
                if (note.isNullOrBlank()) {
                    "update ledger_transactions set date = :date, payee = :payee, note = null," +
                        " time_known = :time_known, embedding = null, embedding_model = null" +
                        " where id = :id"
                } else {
                    "update ledger_transactions set date = :date, payee = :payee, note = :note," +
                        " time_known = :time_known, embedding = null, embedding_model = null" +
                        " where id = :id"
                },
                buildMap {
                    put(":date", date)
                    put(":payee", payee.trim())
                    put(":time_known", if (timeKnown) 1L else 0L)
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
            execute("delete from transaction_sources where transaction_id = :id", mapOf(":id" to id))
            execute("delete from ledger_transactions where id = :id", mapOf(":id" to id))
        }
        emitChange()
    }

    /**
     * Attaches incoming events to transactions that already record them: adds
     * the provenance rows and, for a half-recorded transfer, repoints the
     * dangling category leg at the event's own account. One SQL transaction for
     * the whole batch; the returned undos restore the previous state
     * ([revertAssociations]).
     */
    suspend fun associate(ops: List<AssociateOp>): List<AssociationUndo> {
        if (ops.isEmpty()) return emptyList()
        // Read the accounts being repointed before the write, so undo can put
        // them back exactly as they were.
        val previous = ops.mapNotNull { it.retargetPostingId }.let { postingIds ->
            if (postingIds.isEmpty()) {
                emptyMap()
            } else {
                db.useForRead { d ->
                    d.query(
                        "select id, account_id from postings where id in (${quoteList(postingIds)})",
                        null,
                    ) { rows ->
                        rows.filter { it.size >= 2 }.mapNotNull { row ->
                            val id = row[0]?.toString() ?: return@mapNotNull null
                            val accountId = row[1]?.toString() ?: return@mapNotNull null
                            id to accountId
                        }.toMap()
                    }
                }
            }
        }
        writeAtomically {
            for (op in ops) {
                insertSources(op.transactionId, op.sources)
                if (op.retargetPostingId != null && op.retargetAccountId != null) {
                    execute(
                        "update postings set account_id = :account where id = :id",
                        mapOf(":account" to op.retargetAccountId, ":id" to op.retargetPostingId),
                    )
                }
            }
        }
        emitChange()
        return ops.map { op ->
            AssociationUndo(
                transactionId = op.transactionId,
                sources = op.sources,
                postingId = op.retargetPostingId?.takeIf { op.retargetAccountId != null },
                previousAccountId = op.retargetPostingId?.let { previous[it] },
            )
        }
    }

    /** Undo for [associate]: drops the added sources and restores repointed legs. */
    suspend fun revertAssociations(undos: List<AssociationUndo>) {
        if (undos.isEmpty()) return
        writeAtomically {
            for (undo in undos) {
                for (source in undo.sources) {
                    execute(
                        "delete from transaction_sources where transaction_id = :tx" +
                            " and kind = :kind and ref = :ref",
                        mapOf(
                            ":tx" to undo.transactionId,
                            ":kind" to source.kind.db,
                            ":ref" to source.ref,
                        ),
                    )
                }
                if (undo.postingId != null && undo.previousAccountId != null) {
                    execute(
                        "update postings set account_id = :account where id = :id",
                        mapOf(":account" to undo.previousAccountId, ":id" to undo.postingId),
                    )
                }
            }
        }
        emitChange()
    }

    /**
     * Blocking step of reconciliation: every transaction dated in
     * [from]..[to] (epoch ms, inclusive) with its legs, each leg's account
     * type, and the origins already attached. Deliberately one window query
     * instead of a lookup per candidate — a statement asks about forty rows at
     * once and they all share the same window.
     */
    suspend fun reconcileFacts(from: Long, to: Long): List<LedgerFact> = db.useForRead { d ->
        val legs = d.query(
            "select t.id, t.date, t.payee, p.id, p.account_id, a.type, p.amount_minor, p.commodity" +
                " from ledger_transactions t" +
                " join postings p on p.transaction_id = t.id" +
                " left join accounts a on a.id = p.account_id" +
                " where t.date >= :from and t.date <= :to",
            mapOf(":from" to from, ":to" to to),
        ) { rows ->
            val acc = LinkedHashMap<String, Triple<Long, String, MutableList<FactLeg>>>()
            for (row in rows) {
                if (row.size < 8) continue
                val txId = row[0]?.toString() ?: continue
                val date = (row[1] as? Number)?.toLong() ?: continue
                val payee = row[2]?.toString() ?: ""
                val postingId = row[3]?.toString() ?: continue
                val accountId = row[4]?.toString() ?: continue
                val amount = (row[6] as? Number)?.toLong() ?: continue
                val commodity = row[7]?.toString() ?: continue
                acc.getOrPut(txId) { Triple(date, payee, mutableListOf()) }.third += FactLeg(
                    postingId = postingId,
                    accountId = accountId,
                    type = AccountType.fromDb(row[5]?.toString()),
                    amountMinor = amount,
                    commodity = commodity,
                )
            }
            acc
        }
        if (legs.isEmpty()) return@useForRead emptyList()
        val sources = d.query(
            "select transaction_id, kind, ref, event_key from transaction_sources" +
                " where transaction_id in (${quoteList(legs.keys.toList())})",
            null,
        ) { rows ->
            val keys = mutableMapOf<String, MutableSet<String>>()
            val refs = mutableMapOf<String, MutableSet<String>>()
            for (row in rows) {
                if (row.size < 4) continue
                val txId = row[0]?.toString() ?: continue
                row[2]?.toString()?.let { refs.getOrPut(txId) { mutableSetOf() } += it }
                row[3]?.toString()?.let { keys.getOrPut(txId) { mutableSetOf() } += it }
            }
            keys to refs
        }
        legs.map { (txId, value) ->
            val (date, payee, factLegs) = value
            LedgerFact(
                transactionId = txId,
                date = date,
                payee = payee,
                legs = factLegs,
                eventKeys = sources.first[txId].orEmpty(),
                sourceRefs = sources.second[txId].orEmpty(),
            )
        }
    }


    /**
     * Which of [refs] already belong to some transaction. The inbox asks this
     * to stop re-offering a notification it has already turned into (or
     * attached to) a transaction; passing the refs in keeps it one bounded
     * query instead of loading the whole provenance table.
     */
    suspend fun knownSourceRefs(refs: List<String>): Set<String> {
        if (refs.isEmpty()) return emptySet()
        return db.useForRead { d ->
            d.query(
                "select ref from transaction_sources where ref in (${quoteList(refs)})",
                null,
            ) { rows -> rows.mapNotNull { it.firstOrNull()?.toString() }.toSet() }
        }
    }

    /** Every origin recorded for one transaction, oldest first. */
    /**
     * The other direction of [sources]: which transactions already record this
     * origin. Used by the message detail screens to show a neighbour as
     * already linked instead of offering the link again.
     */
    suspend fun transactionsForSource(kind: EventSource, ref: String): Set<String> =
        db.useForRead { d ->
            d.query(
                "select transaction_id from transaction_sources where kind = :kind and ref = :ref",
                mapOf(":kind" to kind.db, ":ref" to ref),
            ) { rows -> rows.mapNotNull { it.firstOrNull()?.toString() }.toSet() }
        }

    suspend fun sources(transactionId: String): List<StoredSource> = db.useForRead { d ->
        d.query(
            "select kind, ref, event_key, created_at from transaction_sources" +
                " where transaction_id = :id order by created_at",
            mapOf(":id" to transactionId),
        ) { rows ->
            rows.filter { it.size >= 4 }.mapNotNull { row ->
                val kind = EventSource.fromDb(row[0]?.toString()) ?: return@mapNotNull null
                StoredSource(
                    kind = kind,
                    ref = row[1]?.toString() ?: "",
                    eventKey = row[2]?.toString(),
                    createdAt = (row[3] as? Number)?.toLong() ?: 0L,
                )
            }.toList()
        }
    }

    /**
     * Dev utility: hard-deletes every transaction (and its postings) whose
     * date falls within [from, to] (both inclusive, epoch ms). Returns the
     * number of transactions removed. Irreversible — no undo, unlike
     * [delete].
     */
    suspend fun deleteRange(from: Long, to: Long): Int {
        val ids = db.useForRead { d ->
            d.query(
                "select id from ledger_transactions where date >= :from and date <= :to",
                mapOf(":from" to from, ":to" to to),
            ) { rows -> rows.mapNotNull { it.firstOrNull()?.toString() }.toList() }
        }
        if (ids.isEmpty()) return 0
        writeAtomically {
            val idList = quoteList(ids)
            execute("delete from postings where transaction_id in ($idList)", null)
            execute("delete from transaction_sources where transaction_id in ($idList)", null)
            execute("delete from ledger_transactions where id in ($idList)", null)
        }
        emitChange()
        return ids.size
    }

    /** Page of the journal, newest first; postings grouped in memory. */
    suspend fun page(limit: Int = LIST_PAGE_SIZE, before: LedgerCursor? = null): List<Transaction> =
        db.useForRead { d ->
            val txs = d.query(
                "select t.id, t.date, t.payee, t.note, t.created_at, t.time_known" +
                    " from ledger_transactions t" +
                    (if (before == null) "" else " where $TX_CURSOR_FILTER") +
                    " order by t.date desc, t.id desc limit $limit",
                cursorParams(before),
            ) { rows ->
                rows.filter { it.size >= 6 }.map { row ->
                    Transaction(
                        id = row[0]?.toString() ?: "",
                        date = (row[1] as? Number)?.toLong() ?: 0L,
                        payee = row[2]?.toString() ?: "",
                        note = row[3]?.toString(),
                        createdAt = (row[4] as? Number)?.toLong() ?: 0L,
                        postings = emptyList(),
                        timeKnown = isTimeKnown(row[5]),
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
            "select id, date, payee, note, created_at, time_known from ledger_transactions" +
                " where id in ($safe)",
            null,
        ) { rows ->
            rows.filter { it.size >= 6 }.map { row ->
                Transaction(
                    id = row[0]?.toString() ?: "",
                    date = (row[1] as? Number)?.toLong() ?: 0L,
                    payee = row[2]?.toString() ?: "",
                    note = row[3]?.toString(),
                    createdAt = (row[4] as? Number)?.toLong() ?: 0L,
                    postings = emptyList(),
                    timeKnown = isTimeKnown(row[5]),
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
     *
     * Balances are tracked per commodity: an account holding two currencies
     * (or a subtree mixing a pesos and a dollars account) has one running
     * balance each, and every entry reports the one for its own commodity.
     * A single accumulator would add dollars to pesos.
     */
    suspend fun register(
        subtreeIds: List<String>,
        limit: Int = LIST_PAGE_SIZE,
        before: LedgerCursor? = null,
    ): List<RegisterEntry> {
        if (subtreeIds.isEmpty()) return emptyList()
        val idList = quoteList(subtreeIds)
        return db.useForRead { d ->
            val entries = d.query(
                "select p.id, p.transaction_id, p.account_id, p.amount_minor, p.commodity," +
                    " t.date, t.payee, t.time_known" +
                    " from postings p join ledger_transactions t on p.transaction_id = t.id" +
                    " where p.account_id in ($idList)" +
                    (if (before == null) "" else " and ($TX_CURSOR_FILTER)") +
                    " order by t.date desc, t.id desc limit $limit",
                cursorParams(before),
            ) { rows ->
                rows.filter { it.size >= 8 }.mapNotNull { row ->
                    val id = row[0]?.toString() ?: return@mapNotNull null
                    val transactionId = row[1]?.toString() ?: return@mapNotNull null
                    val accountId = row[2]?.toString() ?: return@mapNotNull null
                    val amount = (row[3] as? Number)?.toLong() ?: return@mapNotNull null
                    val commodity = row[4]?.toString() ?: return@mapNotNull null
                    val date = (row[5] as? Number)?.toLong() ?: 0L
                    val payee = row[6]?.toString() ?: ""
                    Posting(id, transactionId, accountId, amount, commodity) to
                        Triple(date, payee, isTimeKnown(row[7]))
                }.toList()
            }

            // The balance the page starts from: everything strictly OLDER
            // than its last row. Summing the whole account instead (what the
            // cursor-less path used to do) counted the page twice and showed
            // doubled balances on the first screen of every register.
            val oldest = entries.lastOrNull()?.let { (posting, meta) ->
                LedgerCursor(meta.first, posting.transactionId)
            }

            val opening = if (oldest == null) {
                emptyMap()
            } else {
                d.query(
                    "select p.commodity, sum(p.amount_minor) from postings p" +
                        " join ledger_transactions t on p.transaction_id = t.id" +
                        " where p.account_id in ($idList) and ($TX_CURSOR_FILTER)" +
                        " group by p.commodity",
                    cursorParams(oldest),
                ) { rows ->
                    rows.mapNotNull { row ->
                        val commodity = row.getOrNull(0)?.toString() ?: return@mapNotNull null
                        val sum = (row.getOrNull(1) as? Number)?.toLong() ?: 0L
                        commodity to sum
                    }.toMap()
                }
            }

            val running = HashMap(opening)
            val balanceAfter = HashMap<String, Long>(entries.size)
            for ((posting, _) in entries.asReversed()) {
                val next = (running[posting.commodity] ?: 0L) + posting.amountMinor
                running[posting.commodity] = next
                balanceAfter[posting.id] = next
            }
            entries.map { (posting, meta) ->
                RegisterEntry(
                    posting = posting,
                    date = meta.first,
                    payee = meta.second,
                    balanceAfter = Money(balanceAfter.getValue(posting.id), posting.commodity),
                    timeKnown = meta.third,
                )
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
        timeKnown: Boolean,
        sourceDocumentId: String?,
    ) {
        val columns = mutableListOf("id", "date", "payee", "created_at", "time_known")
        val values = mutableListOf(":id", ":date", ":payee", ":created_at", ":time_known")
        val params = mutableMapOf<String, Any>(
            ":id" to txId,
            ":date" to date,
            ":payee" to payee.trim(),
            ":created_at" to epochMillis(),
            ":time_known" to if (timeKnown) 1L else 0L,
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

    /**
     * Provenance rows. `insert or ignore`: re-recording the same origin for the
     * same transaction is a no-op, which keeps association idempotent.
     */
    private fun Database.insertSources(txId: String, sources: List<TransactionSource>) {
        if (sources.isEmpty()) return
        val now = epochMillis()
        for (source in sources) {
            val columns = mutableListOf("transaction_id", "kind", "ref", "created_at")
            val values = mutableListOf(":tx", ":kind", ":ref", ":created_at")
            val params = mutableMapOf<String, Any>(
                ":tx" to txId,
                ":kind" to source.kind.db,
                ":ref" to source.ref,
                ":created_at" to now,
            )
            source.eventKey?.takeIf { it.isNotBlank() }?.let {
                columns += "event_key"
                values += ":event_key"
                params[":event_key"] = it
            }
            execute(
                "insert or ignore into transaction_sources(${columns.joinToString(", ")})" +
                    " values(${values.joinToString(", ")})",
                params,
            )
        }
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

        /** Null (a row written before the column existed) means a real time. */
        fun isTimeKnown(value: Any?): Boolean = ((value as? Number)?.toLong() ?: 1L) != 0L

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
