package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The payload is opaque text the wallet re-resolves server-side, so the only
 * thing that can break the handoff without anyone noticing is the encoding:
 * a byte altered on the way out produces a QR the acquirer does not know.
 */
class IosWalletLauncherTest {
    @Test
    fun leavesUnreservedCharactersAlone() {
        assertEquals("0002010102115204", percentEncode("0002010102115204"))
        assertEquals("a-b_c.d~e", percentEncode("a-b_c.d~e"))
    }

    @Test
    fun encodesEverythingElse() {
        // The three that actually appear in EMVCo payloads and query strings,
        // and the one Foundation would have let through as a literal.
        assertEquals("%2A", percentEncode("*"))
        assertEquals("a%20b", percentEncode("a b"))
        assertEquals("%2B", percentEncode("+"))
        assertEquals("x%26y%3Dz", percentEncode("x&y=z"))
        assertEquals("%2F%3F%23%25", percentEncode("/?#%"))
    }

    @Test
    fun encodesMultibyteCharactersPerUtf8Byte() {
        // A merchant name in tag 59 can carry accents; each UTF-8 byte gets
        // its own escape, uppercase hex.
        assertEquals("PANADER%C3%8DA", percentEncode("PANADERÍA"))
    }
}
