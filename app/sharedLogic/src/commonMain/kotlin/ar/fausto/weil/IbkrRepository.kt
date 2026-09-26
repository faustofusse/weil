package ar.fausto.weil

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Interactive Brokers by file (plans/inversiones-brokers.md, phase 3): a
 * Flex report picked or shared into the app → [ibkrBatch] → a plan for the
 * same review screen IOL uses. Nothing to store on the device: the report
 * is the credential.
 *
 * Coverage, not a watermark (plan question 6): IBKR reports rows dated
 * before the report that brings them, so rows are never filtered by date —
 * refs dedupe. What is kept (synced) are the date ranges already imported,
 * and only to warn when a new report leaves a gap after them.
 */
class IbkrRepository(
    private val brokers: BrokersRepository,
    private val settings: SettingsRepository,
) {
    private val json = Json

    /** The range of the report last previewed, recorded as covered once its plan is applied. */
    private var pending: Pair<String, String>? = null

    /** Parses [bytes], makes sure the accounts exist, and plans. Writes accounts only. */
    suspend fun preview(bytes: ByteArray): IbkrPreview {
        val statements = parseFlexReport(bytes.decodeToString())
        val statement = statements.first()
        val accounts = brokers.connect(IBKR_PROVIDER, "IBKR", ibkrCurrencies(statement), withTransfers = true)
        val known = brokers.knownRefs(IBKR_PROVIDER)
        val batch = ibkrBatch(statement)
        val gap = coverageGap(coverage(), statement.fromDate)
        val extraNotes = buildList {
            if (statements.size > 1) add(PlanIssue(null, "the report has ${statements.size} accounts; only ${statement.accountId} was read"))
            gap?.let { (from, to) -> add(PlanIssue(null, "missing IBKR data from $from to $to: run the weil query for that range and share the file")) }
        }
        val plan = planBrokerImport(
            batch.copy(notes = batch.notes + extraNotes),
            accounts, brokers.ledgerView(accounts), known, brokers.scales(),
        )
        pending = statement.fromDate to statement.toDate
        return IbkrPreview(plan, accounts, statement.fromDate, statement.toDate, gap)
    }

    /** Writes the reviewed plan and records the report's range as covered. */
    suspend fun apply(plan: BrokerPlan, selected: List<PlannedTransaction> = plan.transactions): List<String> {
        val ids = brokers.apply(plan, selected)
        pending?.let { range -> settings.set(COVERAGE_KEY, encode(mergeCoverage(coverage() + range))) }
        pending = null
        settings.set(brokerSyncedAtKey(IBKR_PROVIDER), epochMillis().toString())
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
