package com.bobassist.phase0.herotier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroMatcherTest {
    private val table = TierTable.fromJson(
        javaClass.classLoader!!.getResource("herotier_match.json")!!.readText())
    private val m = HeroMatcher(table)
    private fun ln(s: String) = OcrLine(s, BoxPx(0, 0, 10, 10))

    @Test fun exact() = assertEquals("BG_HERO_001", m.match(listOf(ln("Sneed"))).single().cardId)

    @Test fun fuzzyRecoversOneOff() =                                  // "Pircte" d=1 from "pirate"
        assertEquals("BG_HERO_002", m.match(listOf(ln("Patches the Pircte"))).single().cardId)

    @Test fun shortNameNoFuzzy() =                                    // "米羅" 2-char, not exact
        assertTrue(m.match(listOf(ln("米羅"))).isEmpty())

    @Test fun shortNameExactStillMatches() =
        assertEquals("BG_HERO_003", m.match(listOf(ln("米羅克"))).single().cardId)

    @Test fun ambiguousRejected() =                                   // d=1 to BOTH brann & brawn
        assertTrue(m.match(listOf(ln("Bramn"))).isEmpty())

    @Test fun twinExactStillResolves() =                             // exact wins over fuzzy ambiguity
        assertEquals("BG_HERO_005", m.match(listOf(ln("Brann"))).single().cardId)

    @Test fun nonHeroDropped() = assertTrue(m.match(listOf(ln("Choose Your Hero"))).isEmpty())

    @Test fun dedupKeepsOne() =
        assertEquals(1, m.match(listOf(ln("Sneed"), ln("sneed"))).size)

    @Test fun preservesBox() {
        val box = BoxPx(3, 4, 13, 24)
        assertEquals(box, m.match(listOf(OcrLine("Sneed", box))).single().box)
    }

    @Test fun fuzzyBestIsAmbiguousKeyDropped() {
        // Two heroes share enUS "Grommashar" -> that key is ambiguous in TierTable.
        // "Grommashbr" is distance 1 from it (margin satisfied vs the far "zephrys"), so the
        // fuzzy `best` is the ambiguous key; lookup(best) is null -> dropped, never a wrong badge.
        val t = TierTable.fromJson(
            """{"heroes":[{"cardId":"BG_A","tier":"S","names":{"enUS":"Grommashar"}},
                          {"cardId":"BG_B","tier":"C","names":{"enUS":"Grommashar"}},
                          {"cardId":"BG_C","tier":"A","names":{"enUS":"Zephrys"}}]}""")
        assertTrue(HeroMatcher(t).match(listOf(OcrLine("Grommashbr", BoxPx(0, 0, 10, 10)))).isEmpty())
    }
}
