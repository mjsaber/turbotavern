package com.turbotavern.trinket

import com.turbotavern.herotier.BoxPx
import com.turbotavern.herotier.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrinketRecommenderTest {

    private fun m(cid: String, avg: Double, cls: TrinketClass = TrinketClass.LESSER, tier: Tier = Tier.B) =
        TrinketMatch(TrinketEntry(cid, cls, tier, avg), BoxPx(0, 0, 10, 10))

    @Test fun highlightsLowestAvgPlacementAmongTheOffered() {
        val offered = listOf(m("A", 4.3), m("B", 3.8), m("C", 4.6))
        val rec = TrinketRecommender.rank(offered)
        val best = rec.single { it.isBest }
        assertEquals("B", best.match.entry.cardId)        // 3.8 is lowest
        assertEquals(1, best.rank)
        assertEquals(1, rec.count { it.isBest })          // exactly one best
    }

    @Test fun ranksAllOfferedByAvgPlacementAscending() {
        val rec = TrinketRecommender.rank(listOf(m("A", 4.3), m("B", 3.8), m("C", 4.6)))
        assertEquals(listOf("B" to 1, "A" to 2, "C" to 3), rec.map { it.match.entry.cardId to it.rank })
    }

    @Test fun tieBreaksByCardIdForDeterminism() {
        val rec = TrinketRecommender.rank(listOf(m("Z", 4.0), m("A", 4.0)))
        assertEquals("A", rec.first { it.isBest }.match.entry.cardId)   // equal avg -> "A" < "Z"
    }

    @Test fun singleOfferedIsTriviallyBest() {
        val rec = TrinketRecommender.rank(listOf(m("Solo", 4.9)))
        assertEquals(1, rec.size)
        assertTrue(rec[0].isBest)
        assertEquals(1, rec[0].rank)
    }

    @Test fun emptyInEmptyOut() {
        assertTrue(TrinketRecommender.rank(emptyList()).isEmpty())
    }

    @Test fun lowerTierCanStillWinIfAvgIsBetter() {
        // The coarse tier does not decide the within-offer pick; avgPlacement does.
        val rec = TrinketRecommender.rank(listOf(
            m("HighTier", 4.5, tier = Tier.S),
            m("LowTier", 4.0, tier = Tier.C),
        ))
        assertEquals("LowTier", rec.first { it.isBest }.match.entry.cardId)
    }
}
