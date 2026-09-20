package ar.fausto.weil

import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One account the model is allowed to answer with, by its disambiguated path. */
@Serializable
data class CategoryOption(val id: String, val path: String)

/** One option with the probability the model gave it. */
data class RankedCategory(val accountId: String?, val path: String, val probability: Double)

/**
 * What the model picked, or [accountId] = null when it answered
 * "none of these" — which is a real answer and not a failure. A failure is a
 * null [CategorySuggestion].
 */
data class CategorySuggestion(
    val accountId: String?,
    val path: String?,
    val confidence: Double,
    val ranked: List<RankedCategory> = emptyList(),
)

/**
 * Guesses which of the user's own accounts a half-typed description belongs
 * to. Kept as an interface so the desktop shot harness can answer without a
 * session or a network (see `FakeCategorySuggester`).
 */
interface CategorySuggester {
    /**
     * [context] replaces the worker's default description of where the text
     * came from. The default one says the text is *being typed* and is
     * probably unfinished, which is true of the quick-entry field and false
     * of everything else — a merchant name lifted off a payment QR is
     * complete, just abbreviated by the acquirer.
     */
    suspend fun suggest(
        text: String,
        options: List<CategoryOption>,
        kind: ImportDirection = ImportDirection.Expense,
        amount: String? = null,
        context: String? = null,
    ): CategorySuggestion?
}

@Serializable
private data class SuggestRequest(
    val text: String,
    val kind: String,
    val amount: String? = null,
    val context: String? = null,
    val options: List<CategoryOption>,
)

@Serializable
private data class RankedWire(
    val accountId: String? = null,
    val path: String = "",
    val probability: Double = 0.0,
)

@Serializable
private data class SuggestResponse(
    val accountId: String? = null,
    val path: String? = null,
    val confidence: Double = 0.0,
    val ranked: List<RankedWire> = emptyList(),
)

/**
 * Worker-backed [CategorySuggester]. The call happens on a typing debounce,
 * so **every** failure is swallowed into null: no session, no network, a slow
 * model, a 502 from TypeSafe — the picker simply keeps what it had. Nothing
 * here throws [SessionExpired], because a background guess is not a reason to
 * throw the user out of a screen they are mid-way through filling.
 */
class CategorySuggestRepository(
    private val store: SecureStore,
    private val baseUrl: String = AuthConfig.API_BASE_URL,
) : CategorySuggester {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = platformHttpClient {
        install(ContentNegotiation) { json(json) }
        // A guess that lands after the user already picked a category is
        // worse than no guess, so the budget is short on purpose.
        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = TIMEOUT_MS
            connectTimeoutMillis = 10_000
        }
        platformUserAgent()?.let { ua -> install(UserAgent) { agent = ua } }
    }
    private val cookieName = "auth_${AuthConfig.SLUG}"

    override suspend fun suggest(
        text: String,
        options: List<CategoryOption>,
        kind: ImportDirection,
        amount: String?,
        context: String?,
    ): CategorySuggestion? {
        if (text.isBlank() || options.isEmpty()) return null
        return try {
            val resp = client.post("$baseUrl/suggest/category") {
                contentType(ContentType.Application.Json)
                storedCookieHeader(cookieName, store)?.let { header(HttpHeaders.Cookie, it) }
                setBody(
                    SuggestRequest(
                        text = text,
                        kind = if (kind == ImportDirection.Income) "income" else "expense",
                        amount = amount,
                        context = context,
                        options = options,
                    ),
                )
            }
            if (!resp.status.isSuccess()) return null
            val body = resp.body<SuggestResponse>()
            CategorySuggestion(
                accountId = body.accountId,
                path = body.path,
                confidence = body.confidence,
                ranked = body.ranked.map { RankedCategory(it.accountId, it.path, it.probability) },
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    private companion object {
        const val TIMEOUT_MS = 12_000L
    }
}
