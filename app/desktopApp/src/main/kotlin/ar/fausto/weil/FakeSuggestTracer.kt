package ar.fausto.weil

/**
 * The suggestion bench without either model.
 *
 * The real path is two worker calls, and the sandboxed shot session can reach
 * neither — but the screen's whole job is layout over text, so a canned trace
 * of the shape the worker returns renders exactly what ships. The *retrieval*
 * half is real: the neighbours come from the seeded database through the same
 * `similarToText` the app calls, with the hashed bag-of-words embedder.
 */
class FakeSuggestTracer(
    private val notifications: NotificationsRepository,
    private val ledger: TransactionsRepository,
    private val embeddings: EmbeddingsRepository,
) : SuggestTracer {

    override suspend fun traceNotification(id: String): SuggestTrace {
        val item = notifications.get(id) ?: return SuggestTrace(error = "notificación no encontrada")
        val read = ReadResponse(
            isMovement = true,
            direction = "expense",
            payee = "Spotify",
            amount = "5895.57",
            commodity = "ARS",
            account = "Activos:Mercado Pago",
            note = null,
            normalized = "pago de suscripción a Spotify con Mercado Pago",
        )
        val neighbours = runCatching {
            embeddings.similarToText(read.normalized, EmbedKind.Notification, k = 4, exclude = id)
        }.getOrDefault(emptyList())
        val precedents = neighbours.map {
            Precedent(it, ledger.transactionsForSource(EventSource.Notification, it.id).toList())
        }
        val similar = runCatching {
            embeddings.similarToText(read.normalized, EmbedKind.Transaction, k = 4)
        }.getOrDefault(emptyList())
        val facts = ledger.reconcileFacts(item.postTime - WINDOW, item.postTime + WINDOW)
        val event = CandidateEvent(
            source = EventSource.Notification,
            sourceRef = id,
            ownAccountId = "seed-asset-wallet",
            amountMinor = -589557,
            commodity = "ARS",
            date = item.postTime,
            rawPayee = read.payee,
            direction = ImportDirection.Expense,
        )

        return SuggestTrace(
            notification = item,
            read = read,
            readDebug = FAKE_GEMINI_DEBUG,
            readMs = 1_480,
            retrievalMs = 34,
            precedents = precedents,
            similarTransactions = similar,
            match = matchEvent(event, facts),
            event = event,
            decision = AccountsResponse(
                isMovement = 0.97,
                alreadyRecorded = 0.08,
                direction = PickedAccount("expense", 0.94, listOf(RankedPath("expense", 0.94))),
                myAccount = PickedAccount(
                    "Activos:Mercado Pago",
                    0.91,
                    listOf(RankedPath("Activos:Mercado Pago", 0.91), RankedPath("Activos:Banco", 0.06)),
                ),
                transferDestination = PickedAccount(null, 0.71),
                expenseCategory = PickedAccount(
                    "Gastos:Suscripciones",
                    0.83,
                    listOf(RankedPath("Gastos:Suscripciones", 0.83), RankedPath("Gastos:Ocio", 0.11)),
                ),
                incomeCategory = PickedAccount(null, 0.64),
                duplicateOf = PickedAccount(null, 0.88),
                latencyMs = 287,
            ),
            decisionDebug = FAKE_JEV_DEBUG,
            decisionMs = 287,
        )
    }

    private companion object {
        const val WINDOW = 20L * 60 * 60 * 1000

        val FAKE_GEMINI_DEBUG = """
            {
              "prompt": "You read one message an Argentine user received…",
              "model": "gemini-3.5-flash-lite",
              "viaGateway": true,
              "rawText": "{\"isMovement\":true,\"direction\":\"expense\",…}",
              "usage": { "promptTokenCount": 412, "candidatesTokenCount": 96 },
              "latencyMs": 1480
            }
        """.trimIndent()

        val FAKE_JEV_DEBUG = """
            {
              "state": { "message": {…}, "extracted": {…}, "precedents": [ … ] },
              "questions": { "is_movement": {…}, "my_account": {…}, … },
              "answers": { "is_movement": { "type": "noul", "noul": 0.97 }, … }
            }
        """.trimIndent()
    }
}
