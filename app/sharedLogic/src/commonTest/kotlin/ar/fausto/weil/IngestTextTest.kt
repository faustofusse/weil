package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three text defects measured on the dev device's own rows: raw MIME
 * stored as `body_text`, stylesheets truncated before their closing tag, and
 * amounts that write the currency *after* the number. Each one silently
 * degrades everything downstream (rules, embeddings), so each one gets a test.
 */
class MoneyTextTest {

    @Test
    fun acceptsCurrencyOnEitherSide() {
        assertTrue(hasAmount("Pagaste \$ 46.210 a Rappi"))
        assertTrue(hasAmount("Recibiste 30.000 ARS de Fausto Fusse"))
        assertTrue(hasAmount("You received 500,000 ARS"))
        assertTrue(hasAmount("Te transferimos 1.500 pesos"))
        assertTrue(hasAmount("Compraste U\$S100,00"))
    }

    @Test
    fun ignoresBareNumbers() {
        assertFalse(hasAmount("Tu pedido 12345 fue entregado"))
        assertFalse(hasAmount("2 mensajes nuevos"))
    }
}

class UnwrapMimeTest {

    @Test
    fun picksTheHtmlPartOverThePlainOne() {
        val raw = """
            Content-Type: multipart/alternative; boundary="XYZ-1234"

            --XYZ-1234
            Content-Type: text/plain; charset=utf-8

            version texto
            --XYZ-1234
            Content-Type: text/html; charset=utf-8

            <p>version html</p>
            --XYZ-1234--
        """.trimIndent()
        assertTrue("version html" in unwrapMime(raw))
        assertFalse("boundary=" in unwrapMime(raw))
    }

    @Test
    fun findsTheBoundaryWhenTheDeclarationWasTruncatedAway() {
        // A row cut at 10 kB starts *inside* the multipart: no Content-Type
        // header with the boundary, only the separator lines themselves.
        val raw = """
            --000000000000abcd1234
            Content-Type: text/html; charset=utf-8

            <p>Monto ${'$'}20.000,00 Comercio COTO</p>
            --000000000000abcd1234--
        """.trimIndent()
        assertTrue("Comercio COTO" in unwrapMime(raw))
    }

    @Test
    fun decodesABase64Part() {
        val raw = """
            Content-Type: multipart/mixed; boundary="B"

            --B
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: base64

            SG9sYSBtdW5kbw==
            --B--
        """.trimIndent()
        assertEquals("Hola mundo", unwrapMime(raw).trim())
    }

    @Test
    fun leavesPlainTextAlone() {
        val raw = "Monto \$20.000,00 Comercio COTO Fecha 01/09"
        assertEquals(raw, unwrapMime(raw))
    }

    @Test
    fun aDashedSignatureIsNotABoundary() {
        val raw = "Gracias por tu compra\n-----------------\nEquipo de soporte"
        assertEquals(raw, unwrapMime(raw))
    }
}

class EmailPlainTextTest {

    @Test
    fun dropsAStylesheetTruncatedBeforeItsClosingTag() {
        val raw = "<p>Monto \$20.000,00</p><style>.a{color:red}.b{font-size:12px}"
        val text = emailPlainText(raw)
        assertEquals("Monto \$20.000,00", text)
    }

    @Test
    fun readsTheReceiptOutOfRawMime() {
        val raw = """
            Content-Type: multipart/alternative; boundary="B1"

            --B1
            Content-Type: text/html; charset=utf-8
            Content-Transfer-Encoding: quoted-printable

            <style>.x{color:#fff}</style><p>Monto ${'$'}20.000,00 Comercio=
             COTO Fecha 01/09</p>
            --B1--
        """.trimIndent()
        val text = emailPlainText(raw)
        assertFalse("color" in text)
        assertTrue("Comercio COTO" in text)
    }
}
