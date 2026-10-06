package ar.fausto.weil

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IolExplorerTest {
    @Test
    fun pathsAreReadOnlyApiPaths() {
        assertEquals("/api/v2/estadocuenta", iolExplorerPath(" api/v2/estadocuenta "))
        assertEquals("/api/v2/estadocuenta", iolExplorerPath("https://api.invertironline.com/api/v2/estadocuenta"))
        assertEquals("/api/v2/operaciones?filtro.estado=todas", iolExplorerPath("/api/v2/operaciones?filtro.estado=todas"))
        assertFailsWith<IllegalArgumentException> { iolExplorerPath("/token") }
        assertFailsWith<IllegalArgumentException> { iolExplorerPath("/api/../token") }
        assertFailsWith<IllegalArgumentException> { iolExplorerPath("/api/v2/x https://evil") }
        assertFailsWith<IllegalArgumentException> { iolExplorerPath("/api//evil.com@x") }
    }

    @Test
    fun arrayItemsMergeIntoOneSchema() {
        val json = Json.parseToJsonElement(
            """
            {"pais":"argentina","activos":[
              {"cantidad":10,"ppc":null,"titulo":{"simbolo":"AL30","tipo":"TitulosPublicos"}},
              {"cantidad":2.5,"ppc":1234.5,"titulo":{"simbolo":"MELI","tipo":"CEDEARS"},"extra":[]}
            ]}
            """,
        )
        val fields = jsonFields(json).associateBy { it.path }
        assertEquals(
            listOf("pais", "activos", "activos[]", "activos[].cantidad", "activos[].ppc", "activos[].titulo",
                "activos[].titulo.simbolo", "activos[].titulo.tipo", "activos[].extra"),
            fields.keys.toList(),
        )
        assertEquals(setOf("integer", "decimal"), fields.getValue("activos[].cantidad").types)
        assertEquals(setOf("null", "decimal"), fields.getValue("activos[].ppc").types)
        assertEquals("1234.5", fields.getValue("activos[].ppc").example)
        assertEquals("AL30", fields.getValue("activos[].titulo.simbolo").example)
        assertEquals(2, fields.getValue("activos[].titulo.simbolo").count)
        assertEquals(setOf("array (vacío)"), fields.getValue("activos[].extra").types)
    }
}

class IolExplorerFormatTest {
    @Test
    fun numbersReadTheArgentineWay() {
        assertEquals("2.347.747,48", friendlyNumber("2347747.480000"))
        assertEquals("15.890", friendlyNumber("15890.0"))
        assertEquals("-0,39", friendlyNumber("-0.39"))
        assertEquals("120", friendlyNumber("120"))
        assertEquals("1E-5", friendlyNumber("1E-5"))
    }

    @Test
    fun timestamps() {
        assertEquals("14/08/2025 11:02", friendlyTimestamp("2025-08-14T11:02:33.47"))
        assertEquals("14/08/2025", friendlyTimestamp("2025-08-14T00:00:00"))
        assertEquals("14/08/2025", friendlyTimestamp("2025-08-14"))
        assertEquals(null, friendlyTimestamp("AL30"))
    }

    @Test
    fun heldInstrumentsOnly() {
        val commodities = mapOf(
            "BCBA:MELI" to InstrumentInfo("BCBA:MELI", "MELI", "Cedear Mercadolibre", "cedear", 0),
            "FCI:IOLPORA" to InstrumentInfo("FCI:IOLPORA", "IOLPORA", null, "fci", 4),
        )
        val picked = iolExplorerInstruments(
            mapOf("BCBA:MELI" to 10L, "FCI:IOLPORA" to 5L, "BCBA:AL30" to 0L, "ARS" to 100L),
            commodities,
        )
        assertEquals(
            listOf(
                ExplorerInstrument("IOLPORA", null, "fci", "fci"),
                ExplorerInstrument("MELI", "Cedear Mercadolibre", "bcba", "cedear"),
            ),
            picked,
        )
    }
}
