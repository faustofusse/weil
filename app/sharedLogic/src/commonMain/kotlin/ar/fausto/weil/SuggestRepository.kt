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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    /**
     * The same path over an email receipt. One pipeline for both doors: a
     * bank's push and its mail describe the same purchase in the same words,
     * and the only difference is where the text was read from.
     */
    suspend fun traceEmail(id: String): SuggestTrace
}

/**
 * A captured message as the pipeline sees it, whatever door it came through.
 * [origin] is the app name or the sender address — the line that tells the
 * reader who is talking.
 */
data class TracedMessage(
    val source: EventSource,
    val ref: String,
    val origin: String,
    val title: String,
    val text: String,
    val at: Long,
)

class SuggestRepository(
    private val notifications: NotificationsRepository,
    private val emails: EmailsRepository,
    private val accounts: AccountsRepository,
    private val ledger: TransactionsRepository,
    private val embeddings: EmbeddingsRepository,
    private val settings: SettingsRepository,
    private val store: SecureStore,
    private val baseUrl: String = AuthConfig.API_BASE_URL,
) : SuggestTracer {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * For anything a model wrote. Through Cloudflare's unified endpoint there
     * is no schema to constrain the answer, so a reading can arrive with a
     * null payee or a numeric amount; the worker normalizes what it returns,
     * and this is the second line of defence for a field nobody anticipated.
     */
    private val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
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
        return trace(
            TracedMessage(
                source = EventSource.Notification,
                ref = item.id,
                origin = item.appName,
                title = item.title,
                text = item.text,
                at = item.postTime,
            ),
        )
    }

    /** Same path, over an email receipt. */
    override suspend fun traceEmail(id: String): SuggestTrace {
        val item = emails.get(id) ?: return SuggestTrace(error = "mail no encontrado")
        return trace(
            TracedMessage(
                source = EventSource.Email,
                ref = item.id,
                origin = item.fromEmail,
                title = decodeMimeHeader(item.subject.orEmpty()),
                // Same choice `Ingest.kt` makes: the stored text part is
                // frequently raw MIME truncated at 10 kB with the receipt
                // past the cut, so the HTML is the more complete copy. The
                // tail is dropped because a receipt states its total near the
                // top and the rest is footer, legal text and tracking pixels
                // — all of it billed by the token.
                text = emailPlainText(item.bodyHtml ?: item.bodyText.orEmpty()).take(MAX_EMAIL_CHARS),
                at = item.receivedAt,
            ),
        )
    }

    private suspend fun trace(message: TracedMessage): SuggestTrace {
        val id = message.ref
        val tree = accounts.tree()
        val flat = tree.flatMap { it.selfAndDescendants }
        val options = flat.map { AccountOption(it.account.id, it.path, wireType(it.account.type)) }
        // Who the message was sent to. A cash order "for FAUSTO" is the user
        // moving their own money only if the models know the user is Fausto;
        // without a name set the line is simply left out.
        val userName = try {
            settings.all()[PROFILE_NAME_KEY]?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        val kind = message.source.db

        // ---- 1. what the message says ------------------------------------
        val readStarted = epochMillis()
        val readWire = try {
            postJson(
                // Set ALT_READER to run a second model beside Gemini and
                // print both in the trace. Off by default: the two run in
                // parallel, so the stage takes as long as the slower one, and
                // the slower one was taking seventeen seconds.
                buildString {
                    append("$baseUrl/suggest/message?debug=1")
                    ALT_READER?.let { append("&compare=$it&schema=0") }
                },
                ReadRequest(
                    origin = message.origin,
                    title = message.title,
                    text = message.text,
                    `when` = message.at,
                    accounts = options,
                    kind = kind,
                    userName = userName,
                ),
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return SuggestTrace(message = message, error = "lectura: ${e.message ?: e.toString()}")
        }
        val read = decodeBody<ReadResponse>(lenient, readWire)
        val readerModel = runCatching {
            readWire["debug"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        // The comparison is an observation and must never take the run with
        // it: a second model answers in whatever shape it feels like (this one
        // sent `amount` as a number and the strict decoder threw), and the
        // suggestion does not depend on a single field of it.
        val alt = readWire["alt"]?.let {
            runCatching { decodeBody<AltReading>(lenient, it.jsonObject) }
                .getOrElse { failure ->
                    AltReading(error = "no se pudo leer la respuesta: ${failure.message}")
                }
        }
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
            source = message.source,
            sourceRef = id,
            ownAccountId = ownAccountId,
            amountMinor = signed,
            commodity = read.commodity.ifBlank { Money.DEFAULT_COMMODITY },
            date = message.at,
            rawPayee = read.payee,
            direction = direction,
        )
        // Precedents come from the same kind of message: a mail's neighbours
        // are mails. The cross-table search exists and is used elsewhere, but
        // here the question is "what did I do the last time *this template*
        // arrived", and templates do not cross doors.
        val ownKind = if (message.source == EventSource.Email) EmbedKind.Email else EmbedKind.Notification
        // The ledger window is local and the neighbour search waits on a
        // network call for the query vector, so they run side by side.
        val (facts, neighbours) = coroutineScope {
            val window = async {
                ledger.reconcileFacts(message.at - WINDOW_MS, message.at + WINDOW_MS)
            }
            // Neighbours are searched with the *plain* sentence the reader
            // wrote, not with the bank's template: that is what makes the hits
            // purchases instead of rows that share boilerplate. Both tables in
            // one call — the query vector is the expensive part, and it is the
            // same vector for both.
            val similar = async {
                try {
                    embeddings.similarToText(
                        read.normalized,
                        listOf(ownKind, EmbedKind.Transaction),
                        k = 6,
                        exclude = id,
                    )
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emptyMap()
                }
            }
            window.await() to similar.await()
        }
        val outcome = matchEvent(event, facts)
        val similarTransactions = neighbours[EmbedKind.Transaction].orEmpty()
        val messageNeighbours = neighbours[ownKind].orEmpty()
        // One query for every neighbour instead of one per neighbour.
        val linked = ledger.transactionsForSources(
            message.source,
            messageNeighbours.map { it.id },
        )
        val precedents = messageNeighbours.map {
            Precedent(item = it, recordedIn = linked[it.id].orEmpty())
        }
        val retrievalMs = epochMillis() - retrievalStarted

        // Only the three closest neighbours that were actually recorded reach
        // the model: past that they start contradicting each other when the
        // same app sends alerts about different things.
        val recordedPrecedents = precedents
            .filter { it.recordedIn.isNotEmpty() }
            .take(MAX_PRECEDENTS)
        // How each one was recorded, read from the ledger itself: a precedent
        // is usually days old, far outside the ±20 h window of `facts`, and
        // "how was this recorded" is the accounts, not only the payee — a
        // withdrawal fixed by hand to land in cash has to say so to the next
        // run.
        val recordedAs = recordedPrecedents
            .mapNotNull { it.recordedIn.firstOrNull() }
            .distinct()
            .associateWith { txId ->
                try {
                    ledger.get(txId)
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            }
        val pathOf = flat.associate { it.account.id to it.path }
        val wirePrecedents = recordedPrecedents.map { p ->
            val tx = p.recordedIn.firstOrNull()?.let { recordedAs[it] }
            fun side(negative: Boolean) = tx?.postings
                ?.filter { it.amountMinor != 0L && (it.amountMinor < 0) == negative }
                ?.mapNotNull { pathOf[it.accountId] }
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
            WirePrecedent(
                text = "${p.item.title} — ${p.item.subtitle.take(160)}",
                payee = tx?.payee,
                from = side(negative = true),
                to = side(negative = false),
                `when` = relativeDay(message.at, p.item.date),
            )
        }

        // ---- 3. which accounts -------------------------------------------
        val nearby = facts.map {
            NearbyWire(
                id = it.transactionId,
                label = "${it.payee} · ${formatMinorUnits(it.legs.firstOrNull()?.amountMinor ?: 0L)} · " +
                    relativeDay(message.at, it.date),
            )
        }
        val jevStarted = epochMillis()
        val accountsWire = try {
            postJson(
                "$baseUrl/suggest/accounts?debug=1",
                AccountsRequest(
                    message = MessageWire(message.origin, message.title, message.text, message.at, kind),
                    userName = userName,
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
                            `when` = relativeDay(message.at, it.date),
                        )
                    },
                    nearby = nearby,
                    own = options.filter { it.type == "asset" || it.type == "liability" }
                        .map { PathOption(it.id, it.path, it.type) },
                    expense = options.filter { it.type == "expense" }.map { PathOption(it.id, it.path) },
                    income = options.filter { it.type == "income" }.map { PathOption(it.id, it.path) },
                ),
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return SuggestTrace(
                message = message,
                read = read,
                readerModel = readerModel,
                readDebug = readWire.debugBlock(json),
                readMs = readMs,
                retrievalMs = retrievalMs,
                precedents = precedents,
                similarTransactions = similarTransactions,
                match = outcome,
                event = event,
                facts = facts,
                error = "cuentas: ${e.message ?: e.toString()}",
            )
        }
        val decision = decodeBody<AccountsResponse>(json, accountsWire)

        return SuggestTrace(
            message = message,
            read = read,
            readerModel = readerModel,
            readDebug = readWire.debugBlock(json),
            alt = alt,
            readMs = readMs,
            retrievalMs = retrievalMs,
            precedents = precedents,
            similarTransactions = similarTransactions,
            match = outcome,
            event = event,
            facts = facts,
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
        /**
         * A second reader to print beside Gemini, or null for none.
         *
         * Measured on the same Rappi notification, both timed inside the
         * worker: `@cf/zai-org/glm-5.3-flash` answered in 544, 6.860 and
         * 11.419 ms on three runs of the same two lines, against 1.079-1.292
         * ms for Gemini Flash Lite every time. Its fastest run beat Gemini
         * twice over, and there is no way to ask for the fastest run. It also
         * kept writing the amount into `normalized`, which is the one field
         * that must not carry digits: that text is embedded to find movements
         * that read alike, and a number in it is noise the vectors cannot
         * even compare.
         */
        val ALT_READER: String? = null

        const val TIMEOUT_MS = 90_000L
        const val WINDOW_MS = 20L * 60 * 60 * 1000
        const val MAX_PRECEDENTS = 3

        /** A receipt states its total in the first screenful; the rest is footer. */
        const val MAX_EMAIL_CHARS = 4_000
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
    /** For a transfer, the own account the money landed in (a path), else null. */
    val destination: String? = null,
    val note: String? = null,
    val normalized: String = "",
    /** What the reader itself took, without the trip to the worker. */
    val readerMs: Long = 0,
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

/** The same message read by a second model, for comparison only. */
@Serializable
data class AltReading(
    val model: String = "",
    val latencyMs: Long = 0,
    val reading: ReadResponse? = null,
    val raw: String? = null,
    val error: String? = null,
)

/** A similar message, and the transactions it ended up attached to. */
data class Precedent(val item: SimilarItem, val recordedIn: List<String>)

/**
 * Every input and every output of one run, in order. The test screen renders
 * this literally; it is deliberately *not* summarized, because the whole point
 * is to see what the models were given before blaming what they answered.
 */
data class SuggestTrace(
    /** The message this run read, whichever door it came through. */
    val message: TracedMessage? = null,
    val read: ReadResponse? = null,
    /**
     * Which model produced [read]. The reader falls back across models, and
     * they do not answer alike: without this a wrong reading cannot be traced
     * to the model that made it.
     */
    val readerModel: String? = null,
    /** Prompt, model, raw JSON and token usage of the reader call. */
    val readDebug: String? = null,
    /** The same message read by a second model, for comparison. */
    val alt: AltReading? = null,
    val readMs: Long = 0,
    val retrievalMs: Long = 0,
    val precedents: List<Precedent> = emptyList(),
    val similarTransactions: List<SimilarItem> = emptyList(),
    val match: MatchOutcome? = null,
    val event: CandidateEvent? = null,
    /**
     * The ledger rows around the message that [match] was computed against.
     * Kept because the auto-record rule needs one more question of them (see
     * [findReversal]) once it knows which account it will post to.
     */
    val facts: List<LedgerFact> = emptyList(),
    val decision: AccountsResponse? = null,
    /** The `state` and `questions` posted to Jev, plus its raw answers. */
    val decisionDebug: String? = null,
    val decisionMs: Long = 0,
    val accountPaths: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    val totalMs: Long get() = readMs + retrievalMs + decisionMs

    /**
     * One line for the device log: who read the message and what each model
     * answered. The silent auto-record path keeps no trace otherwise, and a
     * row written wrong is only explainable with this at hand.
     */
    fun logLine(): String = buildString {
        append("reader=").append(readerModel ?: "?")
        read?.let {
            append(" movement=").append(it.isMovement)
            append(" dir=").append(it.direction)
            append(" account=").append(it.account)
            append(" dest=").append(it.destination)
        }
        decision?.let { d ->
            append(" jev(dir=").append(d.direction?.path)
            append(" own=").append(d.myAccount?.path)
            append(" dest=").append(d.transferDestination?.path)
            append(')')
        }
        append(" match=").append(match?.let { it::class.simpleName })
        error?.let { append(" error=").append(it) }
    }

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
        // The reader's direction wins. Which way the money went is *in the
        // sentence* ("Pagaste", "Recibiste"), so it belongs to the model that
        // reads sentences; the Choice stays as a cross-check, surfaced in the
        // trace when the two disagree. As the deciding vote it answered
        // "income" on "Pagaste $ 21.389 a Rappi" while the same call picked a
        // delivery expense category at 0.93.
        val direction = ImportDirection.fromWire(read.direction)
        val byPath = accountPaths.entries.associate { (id, path) -> path to id }

        // Jev's pick when it is sure enough, the reader's own guess otherwise.
        fun pick(picked: PickedAccount?): String? =
            picked?.path?.takeIf { picked.confidence >= MIN_CONFIDENCE }?.let { byPath[it] }

        val own = pick(decision?.myAccount) ?: read.account?.let { byPath[it] }
        val category = when (direction) {
            ImportDirection.Income -> pick(decision?.incomeCategory)
            // Jev's pick when it is sure, else the account the reader named
            // as the destination (the worker only lets an own account
            // through). Never the account the money left: a transfer to
            // itself posts +X and −X on one account and records nothing.
            ImportDirection.Transfer ->
                (pick(decision?.transferDestination) ?: read.destination?.let { byPath[it] })
                    ?.takeIf { it != own }
            ImportDirection.Expense -> pick(decision?.expenseCategory)
        }
        return ImportCandidate(
            date = message?.at ?: epochMillis(),
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
)

@Serializable
private data class PathOption(
    val id: String,
    val path: String,
    val type: String? = null,
)

@Serializable
private data class ReadRequest(
    val origin: String,
    val title: String,
    val text: String,
    val `when`: Long,
    val accounts: List<AccountOption>,
    /** `notification` | `email`: only changes how the prompt describes it. */
    val kind: String,
    val userName: String? = null,
)

@Serializable
private data class MessageWire(
    val origin: String,
    val title: String,
    val text: String,
    /** Epoch ms; the worker prints it, the model reads it as a timestamp. */
    val `when`: Long,
    val kind: String,
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
    /** Where the money left, as account paths; null when unknown. */
    val from: String? = null,
    /** Where it landed: a category, or an own account for a transfer. */
    val to: String? = null,
    val `when`: String? = null,
)

@Serializable
private data class NearbyWire(val id: String, val label: String)

@Serializable
private data class AccountsRequest(
    val message: MessageWire,
    val userName: String? = null,
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
