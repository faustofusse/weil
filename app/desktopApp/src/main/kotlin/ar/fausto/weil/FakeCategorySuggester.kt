package ar.fausto.weil

/**
 * Offline stand-in for the worker-backed category guess, for the shot harness
 * and desktop hot reload: substring matching over the option paths, no key and
 * no network. Non-semantic on purpose — it exercises the screen's plumbing
 * (debounce, the "sugerida" hint, the first manual pick winning), never the
 * quality of the real answer.
 */
class FakeCategorySuggester : CategorySuggester {
    override suspend fun suggest(
        text: String,
        options: List<CategoryOption>,
        kind: ImportDirection,
        amount: String?,
        context: String?,
    ): CategorySuggestion? {
        val words = text.lowercase().split(' ', ',', '.').filter { it.length >= 4 }
        val hit = options.firstOrNull { option ->
            val leaf = option.path.substringAfterLast(':').lowercase()
            words.any { leaf.startsWith(it.take(4)) || it.startsWith(leaf.take(4)) }
        } ?: return CategorySuggestion(accountId = null, path = null, confidence = 0.0)
        return CategorySuggestion(hit.id, hit.path, confidence = 0.9)
    }
}
