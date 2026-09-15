package ar.fausto.weil

import kotlinx.coroutines.delay

/**
 * Seeded stand-in for the worker-backed [ImportRepository], used by the
 * headless screenshot harness: the review screen can be rendered without a
 * session, a network call or a Gemini bill. The delay keeps the "analyzing"
 * state on screen for a beat, like the real round trip.
 */
class FakeImportAnalyzer : DocumentAnalyzer {
    override suspend fun analyze(document: PickedDocument): ImportAnalysis {
        delay(700)
        val day = 24 * 60 * 60 * 1000L
        val now = epochMillis()
        return ImportAnalysis(
            docId = "fake".repeat(16),
            candidates = listOf(
                ImportCandidate(
                    date = now - day,
                    payee = "Carrefour",
                    note = "Compra semanal",
                    amountMinor = 4_532_50,
                    commodity = "ARS",
                    direction = ImportDirection.Expense,
                    categoryAccountId = null,
                    categoryPath = null,
                ),
                ImportCandidate(
                    date = now - 2 * day,
                    payee = "Netflix",
                    note = null,
                    amountMinor = 12_99,
                    commodity = "USD",
                    direction = ImportDirection.Expense,
                    categoryAccountId = null,
                    categoryPath = null,
                ),
                ImportCandidate(
                    date = now - 3 * day,
                    payee = "Sueldo",
                    note = null,
                    amountMinor = 1_250_000_00,
                    commodity = "ARS",
                    direction = ImportDirection.Income,
                    categoryAccountId = null,
                    categoryPath = null,
                ),
                ImportCandidate(
                    date = now - 4 * day,
                    payee = "YPF",
                    note = "Nafta",
                    amountMinor = 38_400_00,
                    commodity = "ARS",
                    direction = ImportDirection.Expense,
                    categoryAccountId = null,
                    categoryPath = null,
                ),
            ),
        )
    }
}
