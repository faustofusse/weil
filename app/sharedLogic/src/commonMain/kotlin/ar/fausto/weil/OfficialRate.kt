package ar.fausto.weil

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The official dollar (plans/inversiones-brokers.md, question 4): what the
 * «≈ US$ X al oficial» line under the net worth converts with. The BCRA's
 * rate, not the MEP: a primary source, public, with history by date, and it
 * serves users who have no broker at all. Stored in `prices` as USD in ARS,
 * source [OFFICIAL_SOURCE], so it syncs like any other price.
 */
const val OFFICIAL_SOURCE = "bcra"

/**
 * Which currency the net worth is also shown in, converted at the official
 * rate: `USD` (default, no row), `ARS`, or [NET_WORTH_CONVERSION_OFF]. A
 * synced setting: it is a preference about the account, not the device.
 */
const val NET_WORTH_CURRENCY_KEY = "networth.currency"
const val NET_WORTH_CONVERSION_OFF = "off"
const val DEFAULT_NET_WORTH_CURRENCY = "USD"

/** Older than this, a rate says nothing about today and the line hides. */
const val OFFICIAL_RATE_MAX_AGE_MS = 5L * 24 * 60 * 60 * 1000

private const val BCRA_BASE_URL = "https://api.bcra.gob.ar/estadisticascambiarias/v1.0"

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Daily official USD quotes in ARS for [from]..[to] ("yyyy-MM-dd", ART days
 * — Argentina has no DST, which is what [iolDate]/[iolTime] assume too),
 * both inclusive.
 */
fun interface OfficialRateSource {
    suspend fun usdRates(from: String, to: String): List<PriceQuote>
}

/**
 * The BCRA's `Cotizaciones/USD` response → one [PriceQuote] per day. The
 * rate is read from the JSON text (never through a Double), stamped at
 * 15:00 ART, the close of the wholesale day it refers to. Rows without a
 * rate are skipped rather than failing the batch.
 */
fun parseBcraUsd(body: String): List<PriceQuote> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
    val results = root["results"] as? JsonArray ?: return emptyList()
    return results.mapNotNull { day ->
        val obj = day as? JsonObject ?: return@mapNotNull null
        val date = (obj["fecha"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val usd = (obj["detalle"] as? JsonArray)?.firstOrNull {
            ((it as? JsonObject)?.get("codigoMoneda") as? JsonPrimitive)?.content == "USD"
        } as? JsonObject ?: return@mapNotNull null
        val rate = (usd["tipoCotizacion"] as? JsonPrimitive)?.content?.let { Decimal.parse(it) }
            ?.stripTrailingZeros()
            ?.takeIf { it.signum > 0 } ?: return@mapNotNull null
        val at = iolTime("${date}T15:00:00") ?: return@mapNotNull null
        PriceQuote("USD", "ARS", at, rate, OFFICIAL_SOURCE)
    }
}

class BcraClient(private val baseUrl: String = BCRA_BASE_URL) : OfficialRateSource {
    private val http = platformHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        platformUserAgent()?.let { ua -> install(UserAgent) { agent = ua } }
    }

    override suspend fun usdRates(from: String, to: String): List<PriceQuote> {
        val response = http.get("$baseUrl/Cotizaciones/USD?fechadesde=$from&fechahasta=$to")
        // A range with no business day in it answers 404: nothing, not an error.
        if (!response.status.isSuccess()) return emptyList()
        return parseBcraUsd(response.bodyAsText())
    }
}

/**
 * Keeps the official rate current in `prices`: fetches the days after the
 * newest stored one (the last ten on a fresh ledger), at most once an hour
 * per process, and never throws — a background refresh must not surface
 * as an error on Home.
 */
class OfficialRatesRepository(
    private val brokers: BrokersRepository,
    private val source: OfficialRateSource,
) {
    private var lastAttempt = 0L

    /** True when a new rate was written (the caller reloads the valuation). */
    suspend fun refresh(now: Long = epochMillis()): Boolean {
        if (now - lastAttempt < 60L * 60 * 1000) return false
        lastAttempt = now
        return runCatching {
            val today = iolDate(now)
            val newest = brokers.latestOfficialRate()
            // ISO dates order as strings.
            if (newest != null && iolDate(newest.at) >= today) return@runCatching false
            val from = iolDate(newest?.let { it.at + DAY_MS } ?: (now - 10 * DAY_MS))
            val rates = source.usdRates(from, today)
            if (rates.isEmpty()) return@runCatching false
            brokers.savePrices(rates)
            true
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }
}

/** The net worth restated in one currency at the official rate. */
data class ConvertedTotal(val commodity: String, val minor: Long, val rate: PriceQuote)

/**
 * [money] (currency → minor units, already valued) as one amount in
 * [target], at [Valuation.official]. Null — no line — when there is no
 * rate, the rate is older than [OFFICIAL_RATE_MAX_AGE_MS], a currency other
 * than ARS/USD holds money (no honest rate for it), or nothing needs
 * converting (the line would repeat the headline).
 */
fun Valuation.convert(money: Map<String, Long>, target: String, now: Long): ConvertedTotal? {
    if (target != "USD" && target != "ARS") return null
    val rate = official ?: return null
    if (now - rate.at > OFFICIAL_RATE_MAX_AGE_MS) return null
    val nonzero = money.filterValues { it != 0L }
    if (nonzero.keys.any { it != "USD" && it != "ARS" }) return null
    if (nonzero.keys.none { it != target }) return null
    val ars = Decimal.ofMinorUnits(nonzero["ARS"] ?: 0L, 2)
    val usd = Decimal.ofMinorUnits(nonzero["USD"] ?: 0L, 2)
    val total = if (target == "USD") usd + ars.divide(rate.price, 4) else ars + usd * rate.price
    return ConvertedTotal(target, total.toMinorUnits(2), rate)
}
