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

    // Regression: a short CJK name with ONE OCR slip + a distance-2 decoy present. cap==1 here, so
    // the runner-up saturates at cap+1=2 and the old "(b2-b1)>=ambigMargin(2)" could never hold ->
    // used to miss. "b2 > cap" (only one key within the 1-edit budget) now accepts the real hero.
    @Test fun cjkOneCharErrorMatchesDespiteRunnerUp() {
        val t = TierTable.fromJson(
            """{"heroes":[
                {"cardId":"BG_BIG","tier":"B","names":{"zhTW":"畢勾沃斯先生"}},
                {"cardId":"BG_DECOY","tier":"C","names":{"zhTW":"畢勾沃斯太太"}}]}""")
        assertEquals("BG_BIG", HeroMatcher(t).match(listOf(ln("畢匀沃斯先生"))).single().cardId)
    }

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

    // 4+4 split: each single line (len 4 -> fuzzy cap 0) cannot match alone; only the merge can.
    private val clk = TierTable.fromJson(
        """{"heroes":[{"cardId":"BG_CLK","tier":"A","names":{"zhCN":"钟表先生克劳沃斯"}}]}""")

    @Test fun mergesVerticallyWrappedName() {
        val top = OcrLine("钟表先生", BoxPx(1707, 648, 1850, 675))
        val bot = OcrLine("克劳沃斯", BoxPx(1707, 683, 1850, 718))
        assertEquals("BG_CLK", HeroMatcher(clk).match(listOf(top, bot)).single().cardId)
    }

    @Test fun singleWrappedLinesDoNotMatchAlone() {
        assertTrue(HeroMatcher(clk).match(listOf(OcrLine("钟表先生", BoxPx(1707, 648, 1850, 675)))).isEmpty())
        assertTrue(HeroMatcher(clk).match(listOf(OcrLine("克劳沃斯", BoxPx(1707, 683, 1850, 718)))).isEmpty())
    }

    @Test fun nonAdjacentLinesDoNotMerge() {
        val a = OcrLine("钟表先生", BoxPx(1707, 648, 1850, 675))
        val far = OcrLine("克劳沃斯", BoxPx(100, 1200, 243, 1235))   // far away -> no merge
        assertTrue(HeroMatcher(clk).match(listOf(a, far)).isEmpty())
    }

    @Test fun sameColumnTooLargeGapNoMerge() {                       // aligned x, but far below
        val top = OcrLine("钟表先生", BoxPx(1707, 648, 1850, 675))
        val bot = OcrLine("克劳沃斯", BoxPx(1707, 900, 1850, 935))   // gap 225 >> line height
        assertTrue(HeroMatcher(clk).match(listOf(top, bot)).isEmpty())
    }

    @Test fun smallGapInsufficientXOverlapNoMerge() {                // close vertically, no x overlap
        val top = OcrLine("钟表先生", BoxPx(1707, 648, 1850, 675))
        val bot = OcrLine("克劳沃斯", BoxPx(1900, 683, 2043, 718))   // to the right, no overlap
        assertTrue(HeroMatcher(clk).match(listOf(top, bot)).isEmpty())
    }

    @Test fun mergesLatinWrappedNameWithSpace() {                    // English wraps need a space join
        val t = TierTable.fromJson(
            """{"heroes":[{"cardId":"BG_LK","tier":"S","names":{"enUS":"The Lich King"}}]}""")
        val top = OcrLine("The Lich", BoxPx(100, 100, 260, 130))
        val bot = OcrLine("King", BoxPx(120, 135, 240, 165))
        assertEquals("BG_LK", HeroMatcher(t).match(listOf(top, bot)).single().cardId)
    }

    @Test fun mergedCandidatesAreExactOnly() {
        // A merge that is not an EXACT dictionary name must be rejected (no fuzzy on merges), so a
        // spurious concatenation never becomes a wrong badge.
        val top = OcrLine("钟表先生", BoxPx(1707, 648, 1850, 675))
        val botWrong = OcrLine("克劳沃丝", BoxPx(1707, 683, 1850, 718))  // 丝 != 斯 -> "...沃丝" not exact
        assertTrue(HeroMatcher(clk).match(listOf(top, botWrong)).isEmpty())
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
