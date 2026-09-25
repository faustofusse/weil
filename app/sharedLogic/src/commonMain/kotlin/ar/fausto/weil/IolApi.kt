package ar.fausto.weil

import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/*
 * InvertirOnline's REST API (api.invertironline.com), read side only.
 *
 * What the client may do is the whole point of this file: **only `POST
 * /token` and GETs**. IOL's API can place and cancel orders (everything under `/operar/`,
 * `DELETE /operaciones/{n}`), and none of that is reachable from here — a
 * bug in Weil can misread a statement but can never trade.
 *
 * Auth facts, verified live (plans/inversiones-brokers.md): password grant
 * only, access token 15 min, refresh token 20 min and single-use. A session
 * therefore cannot outlive a gap between syncs, so the client re-logs in
 * with the credentials kept in the secure store (device-local, decision 1)
 * whenever the tokens are gone or refused.
 */

/** Username and password, from the secure store; never synced, never sent anywhere but IOL. */
data class IolCredentials(val username: String, val password: String)

/** Wrong username/password, or the account has no API access: the user has to fix it. */
class IolAuthException(message: String) : Exception(message)

/** Anything else IOL answered badly (5xx, unexpected shape); worth retrying later. */
class IolApiException(message: String) : Exception(message)

/**
 * Amounts arrive as JSON numbers ("2347747.480760000000"). They are decoded
 * from the literal text, never through Double: a Double would turn a
 * statement's exact cents into the nearest binary fraction.
 */
object IolDecimalSerializer : KSerializer<Decimal> {
    override val descriptor = PrimitiveSerialDescriptor("IolDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Decimal {
        val text = (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content
            ?: decoder.decodeString()
        return Decimal.parse(text) ?: throw IolApiException("not a number: '$text'")
    }

    override fun serialize(encoder: Encoder, value: Decimal) = encoder.encodeString(value.toPlainString())
}

@Serializable
data class IolTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

/** `GET /api/v2/estadocuenta`: the cash accounts. */
@Serializable
data class IolAccountState(val cuentas: List<IolCashAccount> = emptyList())

@Serializable
data class IolCashAccount(
    val numero: String? = null,
    /** "inversion_Argentina_Pesos", "inversion_Argentina_Dolares", "inversion_Estados_Unidos_Dolares". */
    val tipo: String? = null,
    /** "peso_Argentino", "dolar_Estadounidense". */
    val moneda: String? = null,
    @Serializable(with = IolDecimalSerializer::class) val saldo: Decimal? = null,
    @Serializable(with = IolDecimalSerializer::class) val disponible: Decimal? = null,
)

/** `GET /api/v2/portafolio/{pais}`. */
@Serializable
data class IolPortfolio(val pais: String? = null, val activos: List<IolPosition> = emptyList())

@Serializable
data class IolPosition(
    @Serializable(with = IolDecimalSerializer::class) val cantidad: Decimal? = null,
    @Serializable(with = IolDecimalSerializer::class) val ultimoPrecio: Decimal? = null,
    /** Average purchase price, per the same face value as [ultimoPrecio]. */
    @Serializable(with = IolDecimalSerializer::class) val ppc: Decimal? = null,
    @Serializable(with = IolDecimalSerializer::class) val valorizado: Decimal? = null,
    val titulo: IolInstrument,
)

/** `titulo` of a position, and `GET /api/v2/{mercado}/Titulos/{simbolo}`. */
@Serializable
data class IolInstrument(
    val simbolo: String,
    val descripcion: String? = null,
    val pais: String? = null,
    /** "bcba", "nYSE", "nASDAQ" — the casing varies between endpoints. */
    val mercado: String? = null,
    /** "CEDEARS", "Letras", "TitulosPublicos", "FondoComundeInversion", … (casing varies too). */
    val tipo: String? = null,
    val moneda: String? = null,
)

/** One row of `GET /api/v2/operaciones`. */
@Serializable
data class IolOperation(
    val numero: Long,
    val fechaOrden: String,
    /** "Compra", "Venta", "Suscripción FCI", "Rescate FCI", "Pago de Renta", "Pago de Amortización". */
    val tipo: String,
    val estado: String,
    val mercado: String? = null,
    val simbolo: String,
    val fechaOperada: String? = null,
    @Serializable(with = IolDecimalSerializer::class) val cantidadOperada: Decimal? = null,
    @Serializable(with = IolDecimalSerializer::class) val precioOperado: Decimal? = null,
    @Serializable(with = IolDecimalSerializer::class) val montoOperado: Decimal? = null,
)

/** `GET /api/v2/operaciones/{numero}`: currency and fees, which the list lacks. */
@Serializable
data class IolOperationDetail(
    val numero: Long,
    val moneda: String? = null,
    /** A string ("compra") or, for some rows, a bare number: kept raw. */
    val tipo: JsonElement? = null,
    val fechaOperado: String? = null,
    @Serializable(with = IolDecimalSerializer::class) val arancelesARS: Decimal? = null,
    @Serializable(with = IolDecimalSerializer::class) val arancelesUSD: Decimal? = null,
)

/**
 * The read verbs IolRepository needs — and, by construction, nothing that
 * trades. An interface so the repository can be exercised end to end
 * against recorded responses (app/desktopApp/src/test).
 */
interface IolSource {
    suspend fun verify(candidate: IolCredentials)
    suspend fun accountState(): IolAccountState
    suspend fun portfolio(country: String): IolPortfolio
    suspend fun operations(from: String, to: String): List<IolOperation>
    suspend fun operation(numero: Long): IolOperationDetail
    suspend fun instrument(market: String, symbol: String): IolInstrument
}

/**
 * The read-only IOL client. Tokens live in memory only; credentials come
 * from [credentials] on every (re)login, so changing them in the store takes
 * effect on the next call.
 */
class IolClient(
    private val credentials: () -> IolCredentials?,
    private val baseUrl: String = IOL_BASE_URL,
) : IolSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = platformHttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        platformUserAgent()?.let { ua -> install(UserAgent) { agent = ua } }
    }
    private val mutex = Mutex()
    private var accessToken: String? = null
    private var accessExpiresAt: Long = 0L

    /**
     * Checks [candidate] against IOL without keeping anything: the connect
     * screen calls this before the password is stored. Drops the cached
     * token either way: after a change of user it belongs to the old one,
     * and IOL would keep answering with that account for up to 15 minutes.
     */
    override suspend fun verify(candidate: IolCredentials) {
        mutex.withLock { accessToken = null }
        requestToken(candidate)
    }

    override suspend fun accountState(): IolAccountState = get("/api/v2/estadocuenta")

    /** [country] is "argentina" or "estados_Unidos". */
    override suspend fun portfolio(country: String): IolPortfolio = get("/api/v2/portafolio/$country")

    /** Finished and cancelled orders between two dates (yyyy-MM-dd, inclusive). */
    override suspend fun operations(from: String, to: String): List<IolOperation> =
        get("/api/v2/operaciones") {
            parameter("filtro.estado", "todas")
            parameter("filtro.fechaDesde", from)
            parameter("filtro.fechaHasta", to)
        }

    override suspend fun operation(numero: Long): IolOperationDetail = get("/api/v2/operaciones/$numero")

    override suspend fun instrument(market: String, symbol: String): IolInstrument =
        get("/api/v2/$market/Titulos/$symbol")

    private suspend inline fun <reified T> get(
        path: String,
        crossinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        var response = http.get(baseUrl + path) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            configure()
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            // A token IOL stopped honouring early: drop it and log in again once.
            mutex.withLock { accessToken = null }
            response = http.get(baseUrl + path) {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                configure()
            }
        }
        response.requireSuccess(path)
        return response.body()
    }

    private suspend fun token(): String = mutex.withLock {
        val cached = accessToken
        if (cached != null && epochMillis() < accessExpiresAt) return@withLock cached
        // The refresh token outlives the access token by five minutes only
        // and syncs are further apart than that, so it is not worth keeping:
        // every sync logs in.
        val stored = credentials() ?: throw IolAuthException("IOL is not connected")
        val fresh = requestToken(stored)
        accessToken = fresh.accessToken
        // A minute of margin so a call never leaves with a token about to lapse.
        accessExpiresAt = epochMillis() + ((fresh.expiresIn ?: 900L) - 60L).coerceAtLeast(60L) * 1000L
        fresh.accessToken
    }

    private suspend fun requestToken(candidate: IolCredentials): IolTokenResponse {
        val response = http.submitForm(
            url = "$baseUrl/token",
            formParameters = parameters {
                append("username", candidate.username)
                append("password", candidate.password)
                append("grant_type", "password")
            },
        )
        if (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.Unauthorized) {
            throw IolAuthException("IOL refused the credentials (${response.status.value})")
        }
        response.requireSuccess("/token")
        return response.body()
    }

    private suspend fun HttpResponse.requireSuccess(path: String) {
        if (!status.isSuccess()) {
            throw IolApiException("IOL $path answered ${status.value}: ${bodyAsText().take(200)}")
        }
    }

    companion object {
        const val IOL_BASE_URL = "https://api.invertironline.com"
    }
}

/** Raw JSON element to its text, for fields IOL sends as string or number. */
internal fun JsonElement?.rawText(): String? = (this as? JsonPrimitive)?.content
