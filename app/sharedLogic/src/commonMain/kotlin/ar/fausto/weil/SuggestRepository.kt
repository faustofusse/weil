package ar.fausto.weil

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Turning one captured message into a proposed transaction.
 *
 * Two calls with the device's own work in between, and the middle step is the
 * reason for the split: the neighbours, the ledger window and the matcher all
 * read rows that may not have synced yet, which the worker can never see.
 *
 *   1. `/suggest/message` — Gemini reads the text (amount, direction, payee).
 *   2. here — vectors, ledger window, [matchEvent].
 *   3. `/suggest/accounts` — Jev picks accounts and categories from closed lists.
 *
 * Nothing is written. The result is a [SuggestTrace]: not just the answer but
 * everything that produced it, because the first thing this feature needs is
 * to be *looked at* (see `SuggestDebugScreen`). The eventual "Crear
 * transacción" button reads the same trace and keeps only [SuggestTrace.read]
 * and [SuggestTrace.decision].
 */
interface SuggestTracer {
    suspend fun traceNotification(id: String): SuggestTrace
}

class SuggestRepository(
    private val notifications: NotificationsRepository,
    private val accounts: AccountsRepository,
    private val ledger: TransactionsRepository,
    private val embeddings: EmbeddingsRepository,
    private val store: SecureStore,
    private val baseUrl: String = AuthConfig.API_BASE_URL,
) : SuggestTracer {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val client = platformHttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = TIMEOUT_MS
            connectTimeoutMillis = 15_000
        }
        platformUserAgent()?.let { ua -> install(UserAgent) { agent = ua } }
    }
    private val cookieName = "auth_${AuthConfig.SLUG}"

    /** Runs the whole path over one captured notification. */
    override suspend fun traceNotification(id: String): SuggestTrace {
        val item = notifications.get(id) ?: return SuggestTrace(error = "notificación no encontrada")
        val tree = accounts.tree()
        val flat = tree.flatMap { it.selfAndDescendants }
        val options = flat.map { AccountOption(it.account.id, it.path, wireType(it.account.type), it.account.commodity) }

        // ---- 1. what the message says ------------------------------------
        val readStarted = epochMillis()
        val readWire = try {
            postJson(
                "$baseUrl/suggest/message?debug=1",
                ReadRequest(
                    origin = item.appName,
                    title = item.title,
                    text = item.text,
                    `when` = item.postTime,
                    accounts = options,
                ),
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return SuggestTrace(notification = item, error = "lectura: ${e.message ?: e.toString()}")
        }
        val read = decodeBody<ReadResponse>(json, readWire)
        val readMs = epochMillis() - readStarted

        // ---- 2. what the device knows ------------------------------------
        val retrievalStarted = epochMillis()
        val amount = read.amount.takeIf { it.isNotBlank() }
            ?.let { Money.parse(it, read.commodity.ifBlank { Money.DEFAULT_COMMODITY })?.minorUnits }
        val ownAccountId = read.account?.let { path -> flat.firstOrNull { it.path == path }?.account?.id }
        val direction = ImportDirection.fromWire(read.direction)
        val signed = when (direction) {
            ImportDirection.Income -> amount ?: 0L
            else -> -(amount ?: 0L)
        }
        val event = CandidateEvent(
            source = EventSource.Notification,
            sourceRef = id,
            ownAccountId = ownAccountId,
            amountMinor = signed,
            commodity = read.commodity.ifBlank { Money.DEFAULT_COMMODITY },
            date = item.postTime,
            rawPayee = read.payee,
            direction = direction,
        )
        val facts = ledger.reconcileFacts(item.postTime - WINDOW_MS, item.postTime + WINDOW_MS)
        val outcome = matchEvent(event, facts)

        // Neighbours are searched with the *plain* sentence the reader wrote,
        // not with the bank's template: that is what makes the hits purchases
        // instead of rows that share boilerplate.
        val precedents = try {
            embeddings.similarToText(read.normalized, EmbedKind.Notification, k = 6, exclude = id)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }.map { neighbour ->
            Precedent(
                item = neighbour,
                recordedIn = ledger.transactionsForSource(EventSource.Notification, neighbour.id).toList(),
            )
        }
        val similarTransactions = try {
            embeddings.similarToText(read.normalized, EmbedKind.Transaction, k = 5)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
        val retrievalMs = epochMillis() - retrievalStarted

        // Only the three closest neighbours that were actually recorded reach
        // the model: past that they start contradicting each other when the
        // same app sends alerts about different things.
        val wirePrecedents = precedents
            .filter { it.recordedIn.isNotEmpty() }
            .take(MAX_PRECEDENTS)
            .map { p ->
                val fact = p.recordedIn.firstNotNullOfOrNull { txId -> facts.firstOrNull { it.transactionId == txId } }
                WirePrecedent(
                    text = "${p.item.title} — ${p.item.subtitle.take(160)}",
                    payee = fact?.payee,
                    `when` = relativeDay(item.postTime, p.item.date),
                )
            }

        // ---- 3. which accounts -------------------------------------------
        val nearby = facts.map {
            NearbyWire(
                id = it.transactionId,
                label = "${it.payee} · ${formatMinorUnits(it.legs.firstOrNull()?.amountMinor ?: 0L)} · " +
                    relativeDay(item.postTime, it.date),
            )
        }
        val jevStarted = epochMillis()
        val accountsWire = try {
            postJson(
                "$baseUrl/suggest/accounts?debug=1",
                AccountsRequest(
                    message = MessageWire(item.appName, item.title, item.text, item.postTime),
                    extracted = ExtractedWire(
                        amount = read.amount,
                        commodity = read.commodity,
                        merchant = read.payee,
                        direction = read.direction,
                        account = read.account,
                    ),
                    precedents = wirePrecedents,
                    // Neighbours from the ledger's own text. Nothing links
                    // them to this message, so they are weaker than a
                    // precedent — but they exist from the first run, and
                    // precedents do not until the user has linked one by hand.
                    similar = similarTransactions.take(MAX_PRECEDENTS).map {
                        WirePrecedent(
                            text = it.title + (it.subtitle.take(80).let { s -> if (s.isBlank()) "" else " — $s" }),
                            payee = it.title,
                            `when` = relativeDay(item.postTime, it.date),
                        )
                    },
                    nearby = nearby,
                    // Own accounts carry their currency: that is what tells
                    // "Santander Dolares" from "Santander Pesos" when the
                    // alert only says U$S.
                    own = options.filter { it.type == "asset" || it.type == "liability" }
                        .map { PathOption(it.id, it.path, it.commodity, it.type) },
                    expense = options.filter { it.type == "expense" }.map { PathOption(it.id, it.path) },
                    income = options.filter { it.type == "income" }.map { PathOption(it.id, it.path) },
                ),
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return SuggestTrace(
                notification = item,
                read = read,
                readDebug = readWire.debugBlock(json),
                readMs = readMs,
                retrievalMs = retrievalMs,
                precedents = precedents,
                similarTransactions = similarTransactions,
                match = outcome,
                event = event,
                error = "cuentas: ${e.message ?: e.toString()}",
            )
        }
        val decision = decodeBody<AccountsResponse>(json, accountsWire)

        return SuggestTrace(
            notification = item,
            read = read,
            readDebug = readWire.debugBlock(json),
            readMs = readMs,
            retrievalMs = retrievalMs,
            precedents = precedents,
            similarTransactions = similarTransactions,
            match = outcome,
            event = event,
            decision = decision,
            decisionDebug = accountsWire.debugBlock(json),
            decisionMs = epochMillis() - jevStarted,
            accountPaths = flat.associate { it.account.id to it.path },
        )
    }

    private suspend inline fun <reified T : Any> postJson(url: String, body: T): JsonObject {
        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
            setBody(body)
        }
        val text = resp.bodyAsText()
        if (resp.status.value == 401) throw SessionExpired()
        if (!resp.status.isSuccess()) throw ApiException(resp.status.value, text)
        return Json.parseToJsonElement(text).jsonObject
    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
        const val WINDOW_MS = 20L * 60 * 60 * 1000
        const val MAX_PRECEDENTS = 3
    }
}

// ---- what comes back -------------------------------------------------------

/** The reader's output: everything about the message that is language. */
@Serializable
data class ReadResponse(
    val isMovement: Boolean = false,
    val direction: String = "expense",
    val payee: String = "",
    val amount: String = "",
    val commodity: String = "ARS",
    val account: String? = null,
    val note: String? = null,
    val normalized: String = "",
)

/** One Choice answer, flattened: null [path] means "none of these fits". */
@Serializable
data class PickedAccount(
    val path: String? = null,
    val confidence: Double = 0.0,
    val ranked: List<RankedPath> = emptyList(),
)

@Serializable
data class RankedPath(val path: String = "", val probability: Double = 0.0)

/** Jev's answers: which account, which category, and is it already recorded. */
@Serializable
data class AccountsResponse(
    val isMovement: Double? = null,
    val alreadyRecorded: Double? = null,
    val direction: PickedAccount? = null,
    val myAccount: PickedAccount? = null,
    val transferDestination: PickedAccount? = null,
    val expenseCategory: PickedAccount? = null,
    val incomeCategory: PickedAccount? = null,
    val duplicateOf: PickedAccount? = null,
    val latencyMs: Long = 0,
)

/** A similar message, and the transactions it ended up attached to. */
data class Precedent(val item: SimilarItem, val recordedIn: List<String>)

/**
 * Every input and every output of one run, in order. The test screen renders
 * this literally; it is deliberately *not* summarized, because the whole point
 * is to see what the models were given before blaming what they answered.
 */
data class SuggestTrace(
    val notification: NotificationItem? = null,
    val read: ReadResponse? = null,
    /** Prompt, model, raw JSON and token usage of the reader call. */
    val readDebug: String? = null,
    val readMs: Long = 0,
    val retrievalMs: Long = 0,
    val precedents: List<Precedent> = emptyList(),
    val similarTransactions: List<SimilarItem> = emptyList(),
    val match: MatchOutcome? = null,
    val event: CandidateEvent? = null,
    val decision: AccountsResponse? = null,
    /** The `state` and `questions` posted to Jev, plus its raw answers. */
    val decisionDebug: String? = null,
    val decisionMs: Long = 0,
    val accountPaths: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    val totalMs: Long get() = readMs + retrievalMs + decisionMs

    /**
     * The proposed transaction, or null when there is nothing to propose
     * (the reader failed, or found no amount).
     *
     * Everything the models were unsure about is left **empty** rather than
     * guessed: the review screen already knows how to fall back to the user's
     * default account and how to make an empty field ask for attention, and a
     * wrong prefill is worse than a blank one because it gets saved.
     */
    fun candidate(): ImportCandidate? {
        val read = read ?: return null
        val amount = read.amount.takeIf { it.isNotBlank() }
            ?.let { Money.parse(it, read.commodity.ifBlank { Money.DEFAULT_COMMODITY })?.minorUnits }
            ?: return null
        val direction = ImportDirection.fromWire(decision?.direction?.path ?: read.direction)
        val byPath = accountPaths.entries.associate { (id, path) -> path to id }

        // Jev's pick when it is sure enough, the reader's own guess otherwise.
        fun pick(picked: PickedAccount?): String? =
            picked?.path?.takeIf { picked.confidence >= MIN_CONFIDENCE }?.let { byPath[it] }

        val own = pick(decision?.myAccount) ?: read.account?.let { byPath[it] }
        val category = when (direction) {
            ImportDirection.Income -> pick(decision?.incomeCategory)
            ImportDirection.Transfer -> pick(decision?.transferDestination)
            ImportDirection.Expense -> pick(decision?.expenseCategory)
        }
        return ImportCandidate(
            date = notification?.postTime ?: epochMillis(),
            payee = read.payee,
            note = read.note,
            commodity = read.commodity.ifBlank { Money.DEFAULT_COMMODITY },
            direction = direction,
            accountId = own,
            accountPath = own?.let { accountPaths[it] },
            splits = listOf(
                ImportSplit(
                    amountMinor = amount,
                    categoryAccountId = category,
                    categoryPath = category?.let { accountPaths[it] },
                ),
            ),
        )
    }

    private companion object {
        /**
         * Below this the field is left for the user. Not a tuned number yet —
         * see the plan: the thresholds are to be read off real runs, and this
         * one only has to be high enough that a hedged answer does not
         * prefill.
         */
        const val MIN_CONFIDENCE = 0.5
    }
}

// ---- wire ------------------------------------------------------------------

@Serializable
private data class AccountOption(
    val id: String,
    val path: String,
    val type: String,
    val commodity: String? = null,
)

@Serializable
private data class PathOption(
    val id: String,
    val path: String,
    val commodity: String? = null,
    val type: String? = null,
)

@Serializable
private data class ReadRequest(
    val origin: String,
    val title: String,
    val text: String,
    val `when`: Long,
    val accounts: List<AccountOption>,
)

@Serializable
private data class MessageWire(
    val origin: String,
    val title: String,
    val text: String,
    /** Epoch ms; the worker prints it, the model reads it as a timestamp. */
    val `when`: Long,
)

@Serializable
private data class ExtractedWire(
    val amount: String,
    val commodity: String,
    val merchant: String,
    val direction: String,
    val account: String?,
)

@Serializable
private data class WirePrecedent(
    val text: String,
    val payee: String? = null,
    val `when`: String? = null,
)

@Serializable
private data class NearbyWire(val id: String, val label: String)

@Serializable
private data class AccountsRequest(
    val message: MessageWire,
    val extracted: ExtractedWire,
    val precedents: List<WirePrecedent>,
    val similar: List<WirePrecedent>,
    val nearby: List<NearbyWire>,
    val own: List<PathOption>,
    val expense: List<PathOption>,
    val income: List<PathOption>,
)

private inline fun <reified T> decodeBody(json: Json, element: JsonObject): T =
    json.decodeFromJsonElement(serializer<T>(), element)

/** The `debug` block of a worker response, pretty-printed for the screen. */
private fun JsonObject.debugBlock(json: Json): String? =
    this["debug"]?.let { json.encodeToString(JsonElement.serializer(), it) }

private fun wireType(type: AccountType): String = when (type) {
    AccountType.Asset -> "asset"
    AccountType.Liability -> "liability"
    AccountType.Expense -> "expense"
    AccountType.Income -> "income"
    AccountType.Equity -> "equity"
}

/** "hace 3 h" / "hace 2 días", relative to the message being studied. */
internal fun relativeDay(from: Long, other: Long): String {
    val delta = from - other
    val abs = if (delta < 0) -delta else delta
    val hours = abs / 3_600_000
    val label = when {
        hours < 1 -> "menos de 1 h"
        hours < 48 -> "$hours h"
        else -> "${abs / 86_400_000} días"
    }
    return if (delta >= 0) "hace $label" else "en $label"
}
