package ar.fausto.weil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Interactive Brokers (plans/inversiones-brokers.md, phases 3 and 7): a Flex
 * report → [ibkrBatch] → a plan for the same review screen IOL uses. The
 * report arrives as a file picked or shared into the app, or through the
 * Flex Web Service with a token and query id kept in this device's secure
 * store (never synced, like IOL's password), which also lets it sync
 * unattended like IOL.
 *
 * Coverage, not a watermark (plan question 6): IBKR reports rows dated
 * before the report that brings them, so rows are never filtered by date —
 * refs dedupe. What is kept (synced) are the date ranges already imported,
 * and only to warn when a new report leaves a gap after them.
 */
class IbkrRepository(
    private val brokers: BrokersRepository,
    private val settings: SettingsRepository,
    private val store: SecureStore,
    service: FlexWebService? = null,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private val json = Json
    private val service: FlexWebService by lazy { service ?: FlexWebServiceClient() }

    /** The query id this device fetches, for the UI; the token never leaves the store. */
    val queryId: String? get() = store.read(QUERY_KEY)?.takeIf { it.isNotBlank() }

    val hasToken: Boolean get() = credentials() != null

    /** The last unattended sync's outcome, as [IolRepository.lastAutoSync]. */
    val lastAutoSync = MutableStateFlow<BrokerAutoSync?>(null)
    private val autoSyncLock = Mutex()
    private var lastAttempt = 0L

    /**
     * Checks the token and query by fetching the first report — a year back,
     * as far as one request reaches, so the deposits are in it and the
     * opening is not a remainder — and keeps them only if that worked.
     * Returns the preview for review.
     */
    suspend fun connect(token: String, queryId: String, now: Long = epochMillis()): IbkrPreview {
        val t = token.trim()
        val q = queryId.trim()
        val report = fetch(t, q, window(now))
        store.write(TOKEN_KEY, t)
        store.write(QUERY_KEY, q)
        clearAutoSync()
        return preview(report.encodeToByteArray())
    }

    /** Forgets the token on this device; what was imported stays. */
    fun disconnect() {
        store.write(TOKEN_KEY, null)
        store.write(QUERY_KEY, null)
        clearAutoSync()
    }

    /** Fetches a report through the API and plans it; writes accounts only. */
    suspend fun sync(now: Long = epochMillis()): IbkrPreview {
        val (token, query) = credentials() ?: throw IbkrAuthException("IBKR is not connected on this device")
        return preview(fetch(token, query, window(now)).encodeToByteArray())
    }

    /** As [IolRepository.autoSync]: routine plans written, the rest left in [lastAutoSync]. Never throws. */
    suspend fun autoSync(now: Long = epochMillis(), force: Boolean = false): BrokerAutoSync? =
        autoSyncLock.withLock {
            if (!hasToken) return@withLock null
            val syncedAt = settings.all()[brokerSyncedAtKey(IBKR_PROVIDER)]?.toLongOrNull() ?: return@withLock null
            if (!force) {
                // End-of-day data: more often than this finds nothing.
                if (now - syncedAt < AUTO_SYNC_INTERVAL_MS) return@withLock null
                if (now - lastAttempt < RETRY_MS) return@withLock null
            }
            lastAttempt = now
            val result = try {
                val plan = sync(now).plan
                if (isRoutine(plan)) BrokerAutoSync.Applied(plan, apply(plan)) else BrokerAutoSync.NeedsReview(plan)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is IbkrAuthException) BrokerAutoSync.WrongCredentials
                else BrokerAutoSync.Failed(e.message ?: e.toString())
            }
            if (result !is BrokerAutoSync.Failed) lastAutoSync.value = result
            result
        }

    fun clearAutoSync() {
        lastAutoSync.value = null
    }

    /**
     * The dates to ask for: the query's own period (the last 30 days) when
     * it follows on from what is covered, else from the day after the
     * coverage — a phone that didn't sync for two months fills the gap
     * itself. The first fetch reaches a year back. Never today: Flex data is
     * end-of-day, and one request covers at most 365 days.
     */
    private suspend fun window(now: Long): Pair<String, String>? {
        val yesterday = plusDays(iolDate(now), -1)
        val earliest = plusDays(yesterday, -364)
        val covered = coverage().maxOfOrNull { it.second }
            ?: return earliest to yesterday
        val next = plusDays(covered, 1)
        if (next >= plusDays(yesterday, -29)) return null
        return maxOf(next, earliest) to yesterday
    }

    /** A custom range the query refuses falls back to the query's own period. */
    private suspend fun fetch(token: String, query: String, range: Pair<String, String>?): String =
        try {
            fetchFlexReport(service, token, query, range?.first, range?.second, sleep)
        } catch (e: IbkrFlexException) {
            if (range == null) throw e
            fetchFlexReport(service, token, query, sleep = sleep)
        }

    private fun credentials(): Pair<String, String>? {
        val token = store.read(TOKEN_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val query = store.read(QUERY_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return token to query
    }

    /** The range of the report last previewed, recorded as covered once its plan is applied. */
    private var pending: Pair<String, String>? = null

    /** Parses [bytes], makes sure the accounts exist, and plans. Writes accounts only. */
    suspend fun preview(bytes: ByteArray): IbkrPreview {
        // Off the main thread: a year's report is megabytes of XML, and on
        // Android the regex scan alone held the UI for half a minute (ANR).
        val started = epochMillis()
        val statements = withContext(Dispatchers.Default) { parseFlexReport(bytes.decodeToString()) }
        val statement = statements.first()
        val accounts = brokers.connect(IBKR_PROVIDER, "IBKR", ibkrCurrencies(statement), withTransfers = true)
        val known = brokers.knownRefs(IBKR_PROVIDER)
        val batch = withContext(Dispatchers.Default) { ibkrBatch(statement) }
        val gap = coverageGap(coverage(), statement.fromDate)
        val extraNotes = buildList {
            if (statements.size > 1) add(PlanIssue(null, "the report has ${statements.size} accounts; only ${statement.accountId} was read"))
            gap?.let { (from, to) -> add(PlanIssue(null, "missing IBKR data from $from to $to: run the weil query for that range and share the file")) }
        }
        val view = brokers.ledgerView(accounts)
        val scales = brokers.scales()
        val plan = withContext(Dispatchers.Default) {
            planBrokerImport(batch.copy(notes = batch.notes + extraNotes), accounts, view, known, scales)
        }
        println("ibkr: ${bytes.size / 1024} KB, ${statement.elements.size} elements, planned in ${epochMillis() - started} ms")
        pending = statement.fromDate to statement.toDate
        return IbkrPreview(plan, accounts, statement.fromDate, statement.toDate, gap)
    }

    /** Writes the reviewed plan and records the report's range as covered. */
    suspend fun apply(plan: BrokerPlan, selected: List<PlannedTransaction> = plan.transactions): List<String> {
        val ids = brokers.apply(plan, selected)
        pending?.let { range -> settings.set(COVERAGE_KEY, encode(mergeCoverage(coverage() + range))) }
        pending = null
        settings.set(brokerSyncedAtKey(IBKR_PROVIDER), epochMillis().toString())
        clearAutoSync()
        return ids
    }

    /** Imported ranges, merged and sorted ("yyyy-MM-dd" pairs). */
    suspend fun coverage(): List<Pair<String, String>> =
        settings.all()[COVERAGE_KEY]?.let { text ->
            runCatching { json.decodeFromString(ListSerializer(ListSerializer(String.serializer())), text) }
                .getOrNull()?.mapNotNull { it.takeIf { r -> r.size == 2 }?.let { r -> r[0] to r[1] } }
        }.orEmpty()

    private fun encode(ranges: List<Pair<String, String>>): String =
        json.encodeToString(ListSerializer(ListSerializer(String.serializer())), ranges.map { listOf(it.first, it.second) })

    companion object {
        const val COVERAGE_KEY = "broker.ibkr.coverage"

        /** Secure-store keys (device-local). */
        const val TOKEN_KEY = "ibkr.token"
        const val QUERY_KEY = "ibkr.query"

        private const val AUTO_SYNC_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val RETRY_MS = 10L * 60 * 1000
    }
}

/** A planned report, with the range it covers and the gap it leaves, if any. */
data class IbkrPreview(
    val plan: BrokerPlan,
    val accounts: BrokerAccounts,
    val fromDate: String,
    val toDate: String,
    val gap: Pair<String, String>?,
)

private const val DAY_MS = 24L * 60 * 60 * 1000

/** "yyyy-MM-dd" + [days]. */
internal fun plusDays(date: String, days: Int): String = iolDate(iolTime(date)!! + days * DAY_MS + DAY_MS / 2)

/** Overlapping or touching ranges merged; ISO dates order as strings. */
internal fun mergeCoverage(ranges: List<Pair<String, String>>): List<Pair<String, String>> {
    val sorted = ranges.sortedBy { it.first }
    val out = mutableListOf<Pair<String, String>>()
    for (r in sorted) {
        val last = out.lastOrNull()
        if (last != null && r.first <= plusDays(last.second, 1)) {
            out[out.lastIndex] = last.first to maxOf(last.second, r.second)
        } else {
            out += r
        }
    }
    return out
}

/**
 * The days between what is already imported and a report starting at
 * [from], or null when it follows on (or nothing was imported yet: the
 * first report is where the history starts).
 */
internal fun coverageGap(coverage: List<Pair<String, String>>, from: String): Pair<String, String>? {
    val covered = coverage.maxOfOrNull { it.second } ?: return null
    val next = plusDays(covered, 1)
    return if (from > next) next to plusDays(from, -1) else null
}
