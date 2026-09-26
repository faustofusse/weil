package ar.fausto.weil

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Flex Web Service handshake, against canned answers in the service's real envelope. */
class IbkrFlexApiTest {

    private fun fail(code: Int, message: String) =
        "<FlexStatementResponse timestamp='26 September, 2026 12:00 PM EDT'>\n<Status>Fail</Status>\n" +
            "<ErrorCode>$code</ErrorCode>\n<ErrorMessage>$message</ErrorMessage>\n</FlexStatementResponse>"

    private val started = "<FlexStatementResponse timestamp='x'>\n<Status>Success</Status>\n" +
        "<ReferenceCode>7415</ReferenceCode>\n<Url>https://example.test/GetStatement</Url>\n</FlexStatementResponse>"

    private val report = "<FlexQueryResponse queryName=\"weil\"><FlexStatements count=\"1\"></FlexStatements></FlexQueryResponse>"

    private class Canned(val send: List<String>, val get: List<String>) : FlexWebService {
        val calls = mutableListOf<String>()
        private var s = 0
        private var g = 0
        override suspend fun sendRequest(token: String, queryId: String, from: String?, to: String?): String {
            calls += "send $queryId $from $to"
            return send[s++]
        }
        override suspend fun getStatement(url: String, token: String, referenceCode: String): String {
            calls += "get $url $referenceCode"
            return get[g++]
        }
    }

    @Test
    fun waitsWhileTheStatementIsGenerated() = runBlocking {
        val waits = mutableListOf<Long>()
        val service = Canned(listOf(started), listOf(fail(1019, "Statement generation in progress."), report))
        val xml = fetchFlexReport(service, "tok", "1649339", "2026-09-01", "2026-09-25") { waits += it }
        assertEquals(report, xml)
        assertEquals(listOf(3_000L, 5_000L), waits)
        assertEquals(
            listOf("send 1649339 2026-09-01 2026-09-25", "get https://example.test/GetStatement 7415", "get https://example.test/GetStatement 7415"),
            service.calls,
        )
    }

    @Test
    fun aBadTokenIsAnAuthFailureAndABadQueryIsNot() = runBlocking {
        assertFailsWith<IbkrAuthException> {
            fetchFlexReport(Canned(listOf(fail(1020, "Invalid request or unable to validate request.")), emptyList()), "x", "1") {}
        }
        assertFailsWith<IbkrAuthException> {
            fetchFlexReport(Canned(listOf(fail(1012, "Token has expired.")), emptyList()), "x", "1") {}
        }
        val e = assertFailsWith<IbkrFlexException> {
            fetchFlexReport(Canned(listOf(fail(1014, "Query is invalid.")), emptyList()), "x", "1") {}
        }
        assertEquals(1014, e.code)
    }

    @Test
    fun givesUpAfterAboutTwoMinutes() = runBlocking {
        var waited = 0L
        val service = Canned(listOf(started), List(50) { fail(1019, "in progress") })
        assertFailsWith<IbkrFlexException> { fetchFlexReport(service, "t", "q") { waited += it } }
        assertTrue(waited in 120_000L..140_000L, "$waited")
    }

    @Test
    fun readsTheEnvelope() {
        val r = parseFlexServiceResponse(started)
        assertEquals(FlexServiceResponse(true, "7415", "https://example.test/GetStatement"), r)
        assertEquals(1020, parseFlexServiceResponse(fail(1020, "x")).errorCode)
    }
}
