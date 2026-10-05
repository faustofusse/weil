package ar.fausto.weil

import kotlin.test.Test
import kotlin.test.assertEquals

class InterleaveNeighboursTest {
    private fun item(id: String, distance: Double) = SimilarItem(
        kind = EmbedKind.Transaction,
        id = id,
        title = id,
        subtitle = "",
        date = 0L,
        amountMinor = null,
        commodity = null,
        distance = distance,
    )

    @Test
    fun merchantHitsSurviveCloserSentenceHits() {
        // The sentence query names the account, so every purchase on that
        // account is closer than the merchant's own rows paid from elsewhere.
        val byPayee = listOf(item("lavision-1", 0.46), item("lavision-2", 0.57), item("google", 0.58))
        val bySentence = listOf(item("coto", 0.33), item("showcase", 0.35), item("lavision-2", 0.42))
        val merged = interleaveNeighbours(byPayee, bySentence).map { it.id }
        assertEquals(listOf("lavision-1", "coto", "lavision-2", "showcase", "google"), merged)
        assertEquals(listOf("lavision-1", "coto", "lavision-2"), merged.take(3))
    }

    @Test
    fun emptySideLeavesTheOther() {
        val only = listOf(item("a", 0.1), item("b", 0.2))
        assertEquals(listOf("a", "b"), interleaveNeighbours(emptyList(), only).map { it.id })
        assertEquals(listOf("a", "b"), interleaveNeighbours(only, emptyList()).map { it.id })
    }
}
