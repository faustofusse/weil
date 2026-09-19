package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmvcoQrTest {

    /** Builds a valid payload by appending the CRC the parser will check. */
    private fun signed(body: String): String {
        val covered = body + "6304"
        var crc = 0xFFFF
        for (ch in covered) {
            crc = crc xor ((ch.code and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return covered + crc.toString(16).uppercase().padStart(4, '0')
    }

    private val dynamic = signed(
        "000201" +
            "010212" +
            "26380016com.mercadolibre011412345678901234" +
            "52045812" +
            "5303032" +
            "5406123.45" +
            "5802AR" +
            "5909PANADERIA" +
            "6007CAPITAL",
    )

    @Test
    fun parsesDynamicQr() {
        val qr = parseEmvcoQr(dynamic)!!
        assertEquals("PANADERIA", qr.merchant)
        assertEquals(12345L, qr.amountMinor)
        assertEquals("ARS", qr.commodity)
        assertEquals("5812", qr.mcc)
        assertEquals(dynamic, qr.raw)
    }

    @Test
    fun staticQrHasNoAmount() {
        val raw = signed("000201010211" + "5303032" + "5802AR" + "5906KIOSCO")
        val qr = parseEmvcoQr(raw)!!
        assertNull(qr.amountMinor)
        assertEquals("KIOSCO", qr.merchant)
        assertEquals("ARS", qr.commodity)
    }

    @Test
    fun amountWithoutDecimalsIsMinorUnits() {
        val raw = signed("000201" + "5303032" + "54041200" + "5904CAFE")
        assertEquals(120000L, parseEmvcoQr(raw)!!.amountMinor)
    }

    /**
     * A real "Cobrar con QR" payload from the MP app ($ 800). Note what it
     * does *not* carry: there is no tag 54. MP's dynamic QRs put the amount in
     * the order behind tag 43's id, resolved server-side — so the amount
     * prefill is null for exactly the QRs a shop shows.
     */
    @Test
    fun parsesRealMercadoPagoQr() {
        val raw = "00020101021243650016com.mercadolibre0201306366b290f4f-7b70-47af-ba91-c2b5d8af8fe7" +
            "50150011204371902685204970053030325802AR5917Negocio de Fausto6004CABA63047F8B"
        val qr = parseEmvcoQr(raw)!!
        assertEquals("Negocio de Fausto", qr.merchant)
        assertNull(qr.amountMinor)
        assertEquals("ARS", qr.commodity)
        assertEquals("9700", qr.mcc)
    }

    @Test
    fun rejectsBadCrc() {
        val broken = dynamic.dropLast(4) + "0000"
        assertNull(parseEmvcoQr(broken))
    }

    @Test
    fun rejectsNonEmvco() {
        assertNull(parseEmvcoQr("https://mpago.la/abc"))
        assertNull(parseEmvcoQr("test123"))
        assertNull(parseEmvcoQr(""))
    }

    @Test
    fun rejectsTruncatedPayload() {
        assertNull(parseEmvcoQr(dynamic.dropLast(10)))
    }
}
