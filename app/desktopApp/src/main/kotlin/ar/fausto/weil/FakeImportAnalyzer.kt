package ar.fausto.weil

import kotlinx.coroutines.delay

/**
 * Seeded stand-in for the worker-backed [ImportRepository], used by the
 * headless screenshot harness: the review screen can be rendered without a
 * session, a network call or a Gemini bill. The delay keeps the "analyzing"
 * state on screen for a beat, like the real round trip. Carrefour carries
 * multiple splits so the harness exercises the itemized-receipt row too.
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
                    commodity = "ARS",
                    direction = ImportDirection.Expense,
                    splits = listOf(
                        ImportSplit(amountMinor = 3_120_50, categoryAccountId = null, categoryPath = null),
                        ImportSplit(amountMinor = 980_00, categoryAccountId = null, categoryPath = null),
                        ImportSplit(amountMinor = 432_00, categoryAccountId = null, categoryPath = null),
                    ),
                ),
                ImportCandidate(
                    date = now - 2 * day,
                    payee = "Netflix",
                    note = null,
                    commodity = "USD",
                    direction = ImportDirection.Expense,
                    splits = listOf(
                        ImportSplit(amountMinor = 12_99, categoryAccountId = null, categoryPath = null),
                    ),
                ),
                ImportCandidate(
                    date = now - 3 * day,
                    payee = "Sueldo",
                    note = null,
                    commodity = "ARS",
                    direction = ImportDirection.Income,
                    splits = listOf(
                        ImportSplit(amountMinor = 1_250_000_00, categoryAccountId = null, categoryPath = null),
                    ),
                ),
                ImportCandidate(
                    date = now - 3 * day,
                    payee = "Pago tarjeta de credito visa",
                    note = "Deb. automatico",
                    commodity = "ARS",
                    direction = ImportDirection.Transfer,
                    splits = listOf(
                        ImportSplit(amountMinor = 111_192_41, categoryAccountId = null, categoryPath = null),
                    ),
                ),
                ImportCandidate(
                    date = now - 4 * day,
                    payee = "YPF",
                    note = "Nafta",
                    commodity = "ARS",
                    direction = ImportDirection.Expense,
                    splits = listOf(
                        ImportSplit(amountMinor = 38_400_00, categoryAccountId = null, categoryPath = null),
                    ),
                ),
            ),
        )
    }
}
