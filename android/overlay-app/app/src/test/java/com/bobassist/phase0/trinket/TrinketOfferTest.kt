package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.BoxPx
import com.bobassist.phase0.herotier.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Test

class TrinketOfferTest {

    private val json = """
        {"trinkets":[
          {"cardId":"L1","trinketClass":"lesser","tier":"S","avgPlacement":3.4,"names":{"enUS":"Welcome Inn"}},
          {"cardId":"L2","trinketClass":"lesser","tier":"B","avgPlacement":4.1,"names":{"enUS":"Goblin Wallet"}},
          {"cardId":"L_SHARED","trinketClass":"lesser","tier":"A","avgPlacement":3.9,"names":{"enUS":"Mystic Charm"}},
          {"cardId":"G_SHARED","trinketClass":"greater","tier":"C","avgPlacement":5.4,"names":{"enUS":"Mystic Charm"}},
          {"cardId":"L_COIL","trinketClass":"lesser","tier":"B","avgPlacement":4.2,"names":{"enUS":"Copper Coil"}},
          {"cardId":"G_COIL","trinketClass":"greater","tier":"A","avgPlacement":4.6,"names":{"enUS":"Copper Coil"}},
          {"cardId":"G1","trinketClass":"greater","tier":"S","avgPlacement":4.0,"names":{"enUS":"Grand Trove"}}
        ]}
    """.trimIndent()
    private val matcher = TrinketMatcher(TrinketTable.fromJson(json))
    private fun ln(s: String) = OcrLine(s, BoxPx(0, 0, 10, 10))

    @Test fun inferredLesserClassResolvesTheSharedNameAndRanks() {
        // a lesser offer: two unique lesser names + the shared "Mystic Charm" (which alone is ambiguous)
        val recs = TrinketOffer.resolve(matcher, listOf(ln("Welcome Inn"), ln("Goblin Wallet"), ln("Mystic Charm")))
        val byId = recs.map { it.match.entry.cardId }.toSet()
        assertEquals("shared name resolved to the lesser via inferred class", setOf("L1", "L2", "L_SHARED"), byId)
        // best = lowest avgPlacement among the three (Welcome Inn 3.4)
        assertEquals("L1", recs.single { it.isBest }.match.entry.cardId)
    }

    @Test fun uniqueNamesResolveWithoutNeedingInference() {
        val recs = TrinketOffer.resolve(matcher, listOf(ln("Welcome Inn"), ln("Goblin Wallet")))
        assertEquals(setOf("L1", "L2"), recs.map { it.match.entry.cardId }.toSet())
    }

    @Test fun matchCountCountsTrinketDictionaryHits() {
        assertEquals(2, TrinketOffer.matchCount(matcher, listOf(ln("Welcome Inn"), ln("Goblin Wallet"))))
        assertEquals(0, TrinketOffer.matchCount(matcher, listOf(ln("Choose a Trinket"), ln("Refresh"))))
    }

    @Test fun mostlySharedOfferStillResolvesAllViaCountInference() {
        // 1 unique lesser + 2 shared names. asLesser resolves all 3; asGreater only the 2 shared.
        // The strictly-larger lesser count wins, so every offered trinket gets a recommendation.
        val recs = TrinketOffer.resolve(matcher, listOf(ln("Welcome Inn"), ln("Mystic Charm"), ln("Copper Coil")))
        assertEquals(setOf("L1", "L_SHARED", "L_COIL"), recs.map { it.match.entry.cardId }.toSet())
    }

    @Test fun allSharedOfferReturnsNothing_safeRatherThanGuessingClass() {
        // Every name exists in BOTH classes (Mystic Charm + Copper Coil). The class is undeterminable
        // from names alone, and lesser vs greater differ in tier/avg — so guessing would risk a WRONG
        // badge. resolve() must return empty (a missing badge beats a wrong one).
        val recs = TrinketOffer.resolve(matcher, listOf(ln("Mystic Charm"), ln("Copper Coil")))
        assertEquals(emptyList<TrinketRecommendation>(), recs)
    }

    @Test fun matchCountGateSignalCountsMostlySharedOffer() {
        // gate must still fire on a mostly-shared trinket screen (it IS a trinket screen)
        assertEquals(3, TrinketOffer.matchCount(matcher, listOf(ln("Welcome Inn"), ln("Mystic Charm"), ln("Copper Coil"))))
    }

    @Test fun emptyOfferEmptyRecs() {
        assertEquals(emptyList<TrinketRecommendation>(), TrinketOffer.resolve(matcher, emptyList()))
    }
}
