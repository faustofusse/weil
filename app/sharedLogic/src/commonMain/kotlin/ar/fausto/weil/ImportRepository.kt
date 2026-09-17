package ar.fausto.weil

import io.ktor.client.call.body
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A document the user picked or shared into the app, held in memory. */
data class PickedDocument(
    val bytes: ByteArray,
    val mimeType: String,
    val name: String?,
) {
    val isPdf: Boolean get() = mimeType == "application/pdf"
    val isCsv: Boolean get() = mimeType == "text/csv"
    val isImage: Boolean get() = mimeType.startsWith("image/")

    // ByteArray uses identity equality; the content-based override keeps
    // recomposition keyed on the actual document.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PickedDocument &&
                mimeType == other.mimeType &&
                name == other.name &&
                bytes.contentEquals(other.bytes))

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + mimeType.hashCode()) * 31 + (name?.hashCode() ?: 0)
}

/** Opens the platform file/photo picker; null when the user cancelled. */
interface DocumentPicker {
    suspend fun pick(): PickedDocument?
}

/** Content types the worker accepts (mirrors `ALLOWED_TYPES` there). */
val IMPORTABLE_MIME_TYPES = listOf(
    "application/pdf",
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/heic",
    "image/heif",
    "text/csv",
)

/**
 * What providers hand back for a `.csv`: SAF asks the app that wrote the file,
 * and spreadsheet apps routinely answer with a legacy alias or `text/plain`.
 * Normalized to `text/csv` on the way in, which is the only spelling the
 * worker (and [IMPORTABLE_MIME_TYPES]) knows.
 */
val CSV_MIME_ALIASES = setOf(
    "text/csv",
    "text/comma-separated-values",
    "text/x-csv",
    "application/csv",
    "application/x-csv",
)

/**
 * Money moves out of (Expense), into (Income), or between two accounts the
 * user owns (Transfer — paying a credit card from the bank account, buying
 * dollars, topping up a wallet: nothing is spent or earned).
 */
enum class ImportDirection {
    Expense,
    Income,
    Transfer;

    companion object {
        fun fromWire(value: String): ImportDirection = when {
            value.equals("income", ignoreCase = true) -> Income
            value.equals("transfer", ignoreCase = true) -> Transfer
            else -> Expense
        }
    }
}

/**
 * One category slice of a candidate's total — almost always the whole payment
 * (a single split), occasionally one of several when the source document
 * itself breaks the payment into separately categorizable parts (a
 * supermarket receipt's line items, an invoice's fee-plus-tax).
 */
data class ImportSplit(
    val amountMinor: Long,
    /**
     * Suggested expense/income account, when the model matched an existing
     * one — or, on a [ImportDirection.Transfer], the user's own account the
     * money arrived in.
     */
    val categoryAccountId: String?,
    val categoryPath: String?,
)

/**
 * One payment the model found in the document, before user review: a single
 * movement of the user's own money (the [total]), sliced into one or more
 * [splits] by category.
 */
data class ImportCandidate(
    val date: Long,
    /**
     * "YYYY-MM-DD" when the origin stated a day and no time (every statement
     * row): the UI resolves it in the device's zone and records the
     * transaction with `timeKnown = false`, so no invented hour is ever shown.
     * Null for origins that carry a real clock time (a push alert, an email).
     */
    val day: String? = null,
    val payee: String,
    val note: String?,
    val commodity: String,
    val direction: ImportDirection,
    /**
     * The user's own asset/liability account the money moved through, when the
     * document names a payment method the model could match (a Mercado Pago
     * receipt, a card slip, a statement header).
     */
    val accountId: String? = null,
    val accountPath: String? = null,
    /**
     * Far leg of a currency exchange: what actually arrived in the
     * destination account, when that side is in another currency (buying
     * dollars debits pesos and credits dollars). Null for every other
     * transaction, where both legs share [commodity].
     */
    val counterAmountMinor: Long? = null,
    val counterCommodity: String? = null,
    val splits: List<ImportSplit>,
) {
    val total: Long get() = splits.sumOf { it.amountMinor }
}

/** [docId] is the R2 content hash, stored as the transactions' provenance. */
data class ImportAnalysis(
    val docId: String,
    val candidates: List<ImportCandidate>,
)

@Serializable
private data class AnalyzeResponse(
    val docId: String,
    val transactions: List<WireCandidate> = emptyList(),
)

@Serializable
private data class WireSplit(
    val amountMinor: Long,
    @SerialName("categoryAccountId") val categoryAccountId: String? = null,
    @SerialName("categoryPath") val categoryPath: String? = null,
)

@Serializable
private data class WireCandidate(
    val date: Long,
    val day: String? = null,
    val payee: String,
    val note: String? = null,
    val commodity: String,
    val direction: String,
    @SerialName("accountId") val accountId: String? = null,
    @SerialName("accountPath") val accountPath: String? = null,
    @SerialName("counterAmountMinor") val counterAmountMinor: Long? = null,
    @SerialName("counterCommodity") val counterCommodity: String? = null,
    val splits: List<WireSplit> = emptyList(),
)

/**
 * What the review screen needs from the import backend. The real
 * implementation is [ImportRepository]; the desktop harness substitutes a
 * fake so the screen can be rendered without a session or network.
 */
interface DocumentAnalyzer {
    suspend fun analyze(document: PickedDocument): ImportAnalysis
}

/**
 * Sends a shared/picked image or PDF to the finance worker, which runs it
 * through Gemini and returns candidate transactions. The worker authenticates
 * with the same `auth_finance` session cookie as the auth worker and stores
 * the original document in R2; nothing is written to the ledger here.
 */
class ImportRepository(
    private val store: SecureStore,
    private val baseUrl: String = AuthConfig.API_BASE_URL,
) : DocumentAnalyzer {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = platformHttpClient {
        install(ContentNegotiation) { json(json) }
        // A multi-page statement keeps the model busy well past any default
        // socket timeout (the round trip is tens of seconds, not hundreds of
        // milliseconds), so this call gets its own generous budget.
        install(HttpTimeout) {
            requestTimeoutMillis = ANALYZE_TIMEOUT_MS
            socketTimeoutMillis = ANALYZE_TIMEOUT_MS
            connectTimeoutMillis = 30_000
        }
        platformUserAgent()?.let { ua -> install(UserAgent) { agent = ua } }
    }
    private val cookieName = "auth_${AuthConfig.SLUG}"

    override suspend fun analyze(document: PickedDocument): ImportAnalysis {
        require(document.mimeType in IMPORTABLE_MIME_TYPES) {
            "unsupported document type: ${document.mimeType}"
        }
        val resp = client.post("$baseUrl/import/analyze") {
            contentType(ContentType.parse(document.mimeType))
            storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
            setBody(document.bytes)
        }
        if (resp.status.value == 401) throw SessionExpired()
        if (!resp.status.isSuccess()) {
            val text = resp.bodyAsText()
            val message = try {
                json.decodeFromString<JsonObject>(text)["error"]?.jsonPrimitive?.content ?: text
            } catch (_: Exception) {
                text
            }
            throw ApiException(resp.status.value, message)
        }
        val body = resp.body<AnalyzeResponse>()
        return ImportAnalysis(
            docId = body.docId,
            candidates = body.transactions.map {
                ImportCandidate(
                    date = it.date,
                    day = it.day,
                    payee = it.payee,
                    note = it.note,
                    commodity = it.commodity,
                    direction = ImportDirection.fromWire(it.direction),
                    accountId = it.accountId,
                    accountPath = it.accountPath,
                    counterAmountMinor = it.counterAmountMinor,
                    counterCommodity = it.counterCommodity,
                    splits = it.splits.map { s ->
                        ImportSplit(
                            amountMinor = s.amountMinor,
                            categoryAccountId = s.categoryAccountId,
                            categoryPath = s.categoryPath,
                        )
                    },
                )
            },
        )
    }

    /** URL of the stored original; the session cookie authorizes the read. */
    fun documentUrl(docId: String): String = "$baseUrl/import/document/$docId"

    private companion object {
        const val ANALYZE_TIMEOUT_MS = 180_000L
    }
}

/**
 * Hand-off slot for documents shared into the app from outside (Android's
 * ACTION_SEND): the platform entry point drops the document here and the UI
 * picks it up once it is composed and the user is signed in.
 */
object SharedImportInbox {
    private var pending: PickedDocument? = null
    private var listener: ((PickedDocument) -> Unit)? = null

    /** Called by platform code when a document arrives from outside the app. */
    fun offer(document: PickedDocument) {
        val current = listener
        if (current != null) current(document) else pending = document
    }

    /** The UI subscribes once; any document that arrived earlier is replayed. */
    fun observe(onDocument: (PickedDocument) -> Unit) {
        listener = onDocument
        pending?.let {
            pending = null
            onDocument(it)
        }
    }

    fun stopObserving() {
        listener = null
    }
}
