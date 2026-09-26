package ar.fausto.weil

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay

/*
 * IBKR's Flex Web Service (plans/inversiones-brokers.md, phase 7): the same
 * report the user downloads by hand, fetched with a token and the query id.
 * Two GETs: SendRequest starts the report and answers a reference code,
 * GetStatement returns it once generated (until then, error 1019). No CORS,
 * which doesn't matter to a native client; the token lives in this device's
 * secure store, like IOL's password.
 *
 * Every answer is HTTP 200: failures come as
 * `<FlexStatementResponse><Status>Fail</Status><ErrorCode>…` (verified with
 * a bogus token: 1020). Limits: 1 request/s and 10/min per token.
 */

/** The service's own envelope: a reference to fetch, or an error. */
data class FlexServiceResponse(
    val success: Boolean,
    val referenceCode: String? = null,
    /** Where GetStatement lives, as SendRequest says (it has moved hosts before). */
    val url: String? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

/** A token IBKR refuses: expired (1012), invalid (1015) or not matching the query (1011/1020). */
class IbkrAuthException(message: String) : Exception(message)

/** Any other refusal from the service (bad query id, rate limit held too long, IBKR down). */
class IbkrFlexException(val code: Int?, message: String) : Exception(message)

private val TOKEN_ERRORS = setOf(1011, 1012, 1015, 1020)
/** "Statement generation in progress" and its siblings: try again shortly. */
private val RETRY_ERRORS = setOf(1001, 1004, 1005, 1006, 1007, 1008, 1009, 1019, 1021)
private const val TOO_MANY_REQUESTS = 1018

private fun tag(xml: String, name: String): String? =
    Regex("<$name>([\\s\\S]*?)</$name>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

/** Reads a `<FlexStatementResponse>`; anything else is a failure with no code. */
fun parseFlexServiceResponse(xml: String): FlexServiceResponse {
    if (!xml.contains("<FlexStatementResponse")) return FlexServiceResponse(false, errorMessage = xml.take(200))
    return FlexServiceResponse(
        success = tag(xml, "Status").equals("Success", ignoreCase = true),
        referenceCode = tag(xml, "ReferenceCode"),
        url = tag(xml, "Url"),
        errorCode = tag(xml, "ErrorCode")?.toIntOrNull(),
        errorMessage = tag(xml, "ErrorMessage"),
    )
}

/** The two calls, raw: what comes back is text, parsed by [fetchFlexReport]. */
interface FlexWebService {
    /** [from]/[to] are "yyyy-MM-dd" and override the query's own period when given. */
    suspend fun sendRequest(token: String, queryId: String, from: String?, to: String?): String
    suspend fun getStatement(url: String, token: String, referenceCode: String): String
}

/**
 * Starts a report and waits for it: SendRequest, then GetStatement until it
 * stops answering "in progress". Backs off 3 s, 5 s, 8 s… (a 30-day report
 * took a few seconds in practice), for about two minutes in total, and
 * honours the rate limit. Returns the report's XML.
 */
suspend fun fetchFlexReport(
    service: FlexWebService,
    token: String,
    queryId: String,
    from: String? = null,
    to: String? = null,
    sleep: suspend (Long) -> Unit = { delay(it) },
): String {
    var start = parseFlexServiceResponse(service.sendRequest(token, queryId, from, to))
    if (!start.success && start.errorCode == TOO_MANY_REQUESTS) {
        sleep(10_000)
        start = parseFlexServiceResponse(service.sendRequest(token, queryId, from, to))
    }
    if (!start.success) throw failure(start)
    val reference = start.referenceCode ?: throw IbkrFlexException(null, "IBKR answered no reference code")
    val url = start.url ?: DEFAULT_GET_STATEMENT_URL
    var wait = 3_000L
    var waited = 0L
    while (true) {
        sleep(wait)
        waited += wait
        val body = service.getStatement(url, token, reference)
        if (body.contains("<FlexQueryResponse")) return body
        val status = parseFlexServiceResponse(body)
        val retry = status.errorCode in RETRY_ERRORS || status.errorCode == TOO_MANY_REQUESTS
        if (!retry || waited >= 120_000L) throw failure(status)
        wait = minOf(wait * 5 / 3, 20_000L)
    }
}

private fun failure(r: FlexServiceResponse): Exception {
    val message = "IBKR ${r.errorCode ?: ""}: ${r.errorMessage ?: "unexpected answer"}".trim()
    return if (r.errorCode in TOKEN_ERRORS) IbkrAuthException(message) else IbkrFlexException(r.errorCode, message)
}

private const val FLEX_BASE_URL = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"
private const val DEFAULT_GET_STATEMENT_URL = "$FLEX_BASE_URL/GetStatement"

class FlexWebServiceClient(private val baseUrl: String = FLEX_BASE_URL) : FlexWebService {
    private val http = platformHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
        // IBKR refuses requests without a User-Agent.
        install(UserAgent) { agent = platformUserAgent() ?: "Weil" }
    }

    override suspend fun sendRequest(token: String, queryId: String, from: String?, to: String?): String =
        http.get("$baseUrl/SendRequest") {
            parameter("t", token)
            parameter("q", queryId)
            parameter("v", "3")
            from?.let { parameter("fd", it.replace("-", "")) }
            to?.let { parameter("td", it.replace("-", "")) }
        }.bodyAsText()

    override suspend fun getStatement(url: String, token: String, referenceCode: String): String =
        http.get(url) {
            parameter("t", token)
            parameter("q", referenceCode)
            parameter("v", "3")
        }.bodyAsText()
}
