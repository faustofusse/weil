package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every notification and email string here is verbatim from the device's own
 * capture table — including the marketing that must *not* become a movement,
 * which is the hard half of the problem.
 */
private const val MP = "com.mercadopago.wallet"
private const val SANTANDER_APP = "ar.com.santander.rio.mbanking"
private const val DAY = 1_757_000_000_000L

private fun notif(pkg: String, title: String, text: String) =
    parseNotification("n1", pkg, title, text, DAY)

class MoneyParsingTest {

    @Test
    fun readsArgentineGrouping() {
        assertEquals(ParsedMoney(589_557, "ARS"), parseMoney("$ 5.895,57"))
        assertEquals(ParsedMoney(20_000_000, "ARS"), parseMoney("$200.000,00"))
        assertEquals(ParsedMoney(800_000, "ARS"), parseMoney("$8.000"))
    }

    @Test
    fun readsAmountsWithoutSeparators() {
        assertEquals(ParsedMoney(1_920_000, "ARS"), parseMoney("$ 19200"))
    }

    @Test
    fun readsDollars() {
        assertEquals(ParsedMoney(10_000, "USD"), parseMoney("U\$S100,00"))
        assertEquals(ParsedMoney(73_000, "USD"), parseMoney("USD 730"))
    }

    @Test
    fun rejectsTextWithoutDigits() {
        assertNull(parseMoney("\$ --"))
    }
}

class NotificationParsingTest {

    @Test
    fun readsMercadoPagoPaymentToMerchant() {
        val movement = assertNotNull(
            notif(MP, "Pagaste a Spotify", "Debitamos $ 5.895,57 de tu cuenta."),
        )
        assertEquals(589_557, movement.amountMinor)
        assertEquals("Spotify", movement.payee)
        assertEquals(ImportDirection.Expense, movement.direction)
        assertEquals(EventSource.Notification, movement.source)
    }

    @Test
    fun readsPaymentWithAmountInTheTitle() {
        val movement = assertNotNull(notif(MP, "Pagaste $ 46.210 a Rappi", "Conocé más detalles."))
        assertEquals(4_621_000, movement.amountMinor)
        assertEquals("Rappi", movement.payee)
    }

    @Test
    fun readsApprovedPaymentWithoutMerchant() {
        val movement = assertNotNull(
            notif(MP, "Tu pago fue aprobado", "Hiciste un pago por  $ 19200. Revisá el detalle de la operación."),
        )
        assertEquals(1_920_000, movement.amountMinor)
        assertEquals("", movement.payee)
    }

    @Test
    fun readsIncomingTransferWithSenderName() {
        val movement = assertNotNull(
            notif(
                MP,
                "Recibiste $ 15.000",
                "Luciano Ramiro Veiga te envió dinero y ya está generando rendimientos en tu cuenta.",
            ),
        )
        assertEquals(1_500_000, movement.amountMinor)
        assertEquals("Luciano Ramiro Veiga", movement.payee)
        assertEquals(ImportDirection.Income, movement.direction)
    }

    @Test
    fun readsIncomingTransferPhrasedTheOtherWay() {
        val movement = assertNotNull(
            notif(MP, "Recibiste $ 10.000", "De Mariano Nicolas Romero desde su cuenta de Mercado Pago."),
        )
        assertEquals("Mariano Nicolas Romero", movement.payee)
    }

    @Test
    fun readsSantanderTransferOut() {
        val movement = assertNotNull(
            notif(SANTANDER_APP, "Transferiste con éxito", "Enviaste $ 200.000,00 a Fausto Fusse."),
        )
        assertEquals(20_000_000, movement.amountMinor)
        assertEquals("Fausto Fusse", movement.payee)
        assertEquals(ImportDirection.Expense, movement.direction)
    }

    @Test
    fun ignoresMarketingThatLooksLikeAPayment() {
        // Every one of these carries an amount, a merchant and a verb.
        assertNull(notif(MP, "¡15% OFF en Carrefour! 😱", "Aprovechá hoy ¡Sin tope! Compra mínima: $15.000"))
        assertNull(notif(MP, "Recibiste $5.000 🍟", "🍔$5.000 de regalo para pedir tu comida favorita"))
        assertNull(
            notif(
                SANTANDER_APP,
                "50% de ahorro en bondis y subtes 🚍",
                "Todos los días, pagando desde la App Santander con dinero en cuenta. Tope $8.000.",
            ),
        )
        assertNull(notif("com.whatsapp", "Mamá", "te debo $5000"))
    }

    @Test
    fun ignoresAnotherAppSpeakingTheSameSentence() {
        // The rule is scoped to the bank's package: a chat app quoting
        // "Pagaste a X" must not book a charge.
        assertNull(notif("org.telegram.messenger", "Pagaste a Spotify", "Debitamos $ 5.895,57 de tu cuenta."))
    }
}

class EmailParsingTest {

    private val cardReceipt = """
        Aviso de consumo credito Información sobre tu pago Te acercamos el detalle de tu consumo con la
        Tarjeta Santander Visa Cr=C3=A9dito terminada en 1500 .
        Monto $20.000,00 Cuotas 1 Comercio WWWBOCAJUNIORSCOMAR Fecha 07/09/2026 Hora 14:03
    """.trimIndent()

    @Test
    fun readsCardChargeFromTheReceiptBody() {
        val movement = assertNotNull(
            parseEmail("e1", "mensajesyavisos@mails.santander.com.ar", "Aviso de consumo", cardReceipt, receivedAt = DAY),
        )
        assertEquals(2_000_000, movement.amountMinor)
        assertEquals("WWWBOCAJUNIORSCOMAR", movement.payee)
        assertEquals(EventSource.Email, movement.source)
        // The card named in the body sharpens the account hint.
        assertTrue(movement.accountHints.any { it.contains("Visa", ignoreCase = true) })
    }

    @Test
    fun decodesEncodedSubjects() {
        assertEquals("Aviso de débito automático", decodeMimeHeader("=?UTF-8?Q?Aviso_de_d=C3=A9bito_autom=C3=A1tico?="))
        assertEquals("¡Martes de ClubDia!", decodeMimeHeader("=?utf-8?B?wqFNYXJ0ZXMgZGUgQ2x1YkRpYSE=?="))
    }

    @Test
    fun flattensQuotedPrintableHtml() {
        val text = emailPlainText("<p>Monto&nbsp;$1.000</p>\r\n<b>Comercio=\r\n COTO</b>")
        assertEquals("Monto $1.000 Comercio COTO", text)
    }

    @Test
    fun readsTheReceiptOutOfTheHtmlWhenTheTextPartIsJunk() {
        // What the worker actually stores for a bank mail: the text part is
        // the nested MIME envelope, the receipt only exists in the HTML.
        val movement = assertNotNull(
            parseEmail(
                "e3",
                "mensajesyavisos@mails.santander.com.ar",
                "Pagaste U\$S21,23",
                bodyText = "------=_Part_1 Content-Type: multipart/related; boundary=\"x\"",
                bodyHtml = "<style>.title { color: #767676 } .monto { }</style>" +
                    "<p>Tarjeta Santander Visa Cr=C3=A9dito terminada en 1500</p>" +
                    "<td>Monto U\$S21,23</td><td>Cuotas 1</td><td>Come=\r\nrcio OPENCODE</td><td>Fecha 07/09/2026</td>",
                receivedAt = DAY,
            ),
        )
        assertEquals(2_123, movement.amountMinor)
        assertEquals("USD", movement.commodity)
        // The soft line break inside "Comercio" must not reach the payee.
        assertEquals("OPENCODE", movement.payee)
    }

    @Test
    fun ignoresNewsletters() {
        assertNull(
            parseEmail(
                "e2",
                "newsletter@news.supermercadosdia.com.ar",
                "¡Festival de precios!",
                "HASTA 2x1 + 15% de reintegro Monto mínimo $10.000",
                receivedAt = DAY,
            ),
        )
    }
}

class AccountHintTest {

    private fun asset(id: String, name: String, commodity: String? = null) =
        Account(id, name, null, AccountType.Asset, commodity = commodity)

    private val accounts = listOf(
        asset("mp", "Mercado Pago"),
        asset("sp", "Santander Pesos"),
        asset("sd", "Santander Dolares"),
        asset("gp", "Galicia Pesos"),
    )

    @Test
    fun resolvesAnUnambiguousApp() {
        assertEquals("mp", resolveAccountHint(listOf("mercado pago"), "ARS", accounts))
    }

    @Test
    fun usesTheCommodityToPickBetweenSiblings() {
        assertEquals("sd", resolveAccountHint(listOf("santander"), "USD", accounts))
        assertEquals("sp", resolveAccountHint(listOf("santander"), "ARS", accounts))
    }

    @Test
    fun givesUpWhenTheTreeCannotAnswer() {
        assertNull(resolveAccountHint(listOf("brubank"), "ARS", accounts))
        assertNull(resolveAccountHint(emptyList(), "ARS", accounts))
        // Two equally-named accounts, nothing to break the tie.
        val twins = listOf(asset("a", "Santander"), asset("b", "Santander"))
        assertNull(resolveAccountHint(listOf("santander"), "ARS", twins))
    }

    @Test
    fun aDeclaredCurrencyTellsTwoSameNamedAccountsApart() {
        // The case the name heuristic cannot reach: identical names, and only
        // the declared commodity to go on.
        val twins = listOf(
            asset("ars", "Santander", "ARS"),
            asset("usd", "Santander", "USD"),
        )
        assertEquals("ars", resolveAccountHint(listOf("santander"), "ARS", twins))
        assertEquals("usd", resolveAccountHint(listOf("santander"), "USD", twins))
    }

    @Test
    fun aDeclaredCurrencyExcludesTheAccountOutright() {
        // A dollars-only account is not where a peso charge landed, however
        // well the name matches; with no alternative the answer is null.
        val only = listOf(asset("usd", "Brubank", "USD"))
        assertNull(resolveAccountHint(listOf("brubank"), "ARS", only))
        assertEquals("usd", resolveAccountHint(listOf("brubank"), "USD", only))
    }

    @Test
    fun anUndeclaredAccountStillCompetes() {
        // Trees predating the column keep working, and a declared match wins
        // over an undeclared namesake.
        val mixed = listOf(asset("old", "Galicia"), asset("new", "Galicia", "ARS"))
        assertEquals("new", resolveAccountHint(listOf("galicia"), "ARS", mixed))
        assertEquals("old", resolveAccountHint(listOf("galicia"), "USD", mixed))
    }
}

class IngestToEventTest {

    @Test
    fun anIncomingTransferMirrorsTheOutgoingOne() {
        // The real pair captured on the device: Santander says it sent
        // 200.000, Mercado Pago says it received them. One movement.
        val out = assertNotNull(
            notif(SANTANDER_APP, "Transferiste con éxito", "Enviaste $ 200.000,00 a Fausto Fusse."),
        ).toEvent("santander")
        val inbound = assertNotNull(
            notif(MP, "Tu dinero ya está disponible", "Ingresaste $ 200.000 y ya están generando rendimientos."),
        ).toEvent("mp")

        assertEquals(-20_000_000, out.amountMinor)
        assertEquals(20_000_000, inbound.amountMinor)

        // The outgoing half is already in the ledger, filed as a plain expense.
        val stored = LedgerFact(
            transactionId = "t1",
            date = DAY,
            payee = "Fausto Fusse",
            legs = listOf(
                FactLeg("p1", "santander", AccountType.Asset, -20_000_000, "ARS"),
                FactLeg("p2", "cat", AccountType.Expense, 20_000_000, "ARS"),
            ),
        )
        val outcome = matchEvent(inbound, listOf(stored))
        val match = assertNotNull(outcome as? MatchOutcome.Confident).match
        assertEquals(MatchRelation.Mirror, match.relation)
        assertEquals("p2", match.retargetPostingId)
    }

    @Test
    fun theSameAlertTwiceIsOneEvent() {
        val first = assertNotNull(notif(MP, "Pagaste a Personal", "Debitamos $ 51.284,01 de tu cuenta."))
        val second = assertNotNull(
            parseNotification("n2", MP, "Pagaste a Personal", "Debitamos $ 51.284,01 de tu cuenta.", DAY + 1_000),
        )
        assertEquals(first.toEvent("mp").eventKey, second.toEvent("mp").eventKey)
    }
}
