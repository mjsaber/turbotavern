package com.turbotavern.herotier

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

    // A card-frame stroke OCR reads as a leading "|" used to push the name to distance 2 (miss at
    // cap 1). It's stripped at the edge before matching (NameKey can't, it must keep parity).
    @Test fun stripsLeadingFrameStrokeBeforeMatching() {
        val t = TierTable.fromJson(
            """{"heroes":[{"cardId":"BG_BIG","tier":"B","names":{"zhTW":"畢勾沃斯先生"}}]}""")
        assertEquals("BG_BIG", HeroMatcher(t).match(listOf(ln("|畢匀沃斯先生"))).single().cardId)
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

    // --- optional minFuzzyConfidence: gate the FUZZY path, never the exact path ---
    private fun lnc(s: String, c: Float?) = OcrLine(s, BoxPx(0, 0, 10, 10), c)

    @Test fun fuzzyBelowConfidenceFloorIsRejected() =
        assertTrue("a low-confidence fuzzy read must not produce a badge",
            HeroMatcher(table, minFuzzyConfidence = 0.5f).match(listOf(lnc("Patches the Pircte", 0.3f))).isEmpty())

    @Test fun fuzzyAtOrAboveConfidenceFloorMatches() =
        assertEquals("BG_HERO_002",
            HeroMatcher(table, minFuzzyConfidence = 0.5f).match(listOf(lnc("Patches the Pircte", 0.9f))).single().cardId)

    @Test fun exactMatchIgnoresConfidenceFloor() =                    // exact stays unconditional even at very low conf
        assertEquals("BG_HERO_001",
            HeroMatcher(table, minFuzzyConfidence = 0.5f).match(listOf(lnc("Sneed", 0.01f))).single().cardId)

    @Test fun nullConfidenceLeavesFuzzyEnabled() =                    // unknown confidence != low confidence
        assertEquals("BG_HERO_002",
            HeroMatcher(table, minFuzzyConfidence = 0.5f).match(listOf(lnc("Patches the Pircte", null))).single().cardId)

    // Regression (live emulator, 2026-06-13): zhTW hero names use ‧ (U+2027, absent from ppocrv5_dict
    // so OCR drops it -> folded alias), but the interchangeable middot · (U+00B7) IS in the dict, so
    // the rec head can emit it. The matcher must fold the OCR-side mark too. Table key uses ‧.
    private val cariel = TierTable.fromJson(
        "{\"heroes\":[{\"cardId\":\"BG_CR\",\"tier\":\"A\",\"names\":{\"zhTW\":\"凱瑞爾‧羅姆\"}}]}")

    @Test fun ocrEmittedMiddotMatchesFoldedAlias() =                  // U+00B7 vs table U+2027, else exact
        assertEquals("BG_CR", HeroMatcher(cariel).match(listOf(ln("凱瑞爾·羅姆"))).single().cardId)

    @Test fun ocrMiddotPlusOneSlipStillMatches() =                    // U+00B7 + 1-char slip (姆->婭): mark must be free
        assertEquals("BG_CR", HeroMatcher(cariel).match(listOf(ln("凱瑞爾·羅婭"))).single().cardId)

    // Regression (codex P2): a wrapped zhTW name split across two OCR lines, where the rec head emitted
    // the middot on the fragment, must still match after the vertical merge (table key uses ‧ U+2027).
    private val mioff = TierTable.fromJson(
        "{\"heroes\":[{\"cardId\":\"BG_MF\",\"tier\":\"B\",\"names\":{\"zhTW\":\"米歐菲瑟‧曼納斯頓\"}}]}")

    @Test fun mergesWrappedNameWithEmittedMiddot() {
        val top = OcrLine("米歐菲瑟·", BoxPx(1707, 648, 1850, 675))   // U+00B7 emitted on the wrapped fragment
        val bot = OcrLine("曼納斯頓", BoxPx(1707, 683, 1850, 718))
        assertEquals("BG_MF", HeroMatcher(mioff).match(listOf(top, bot)).single().cardId)
    }

    // Regression (codex P2): the fuzzy cap must come from the ORIGINAL read length, not the folded one.
    // 凱瑞爾‧羅姆 read as 凱瑞·羅姆 (middot + dropped 爾) folds to 凱瑞羅姆 (len 4) -> cap 0 if measured
    // post-fold = miss; measured on the len-5 read it stays cap 1 and recovers the 1-edit match.
    @Test fun separatorPlusDroppedCharStillMatches() =
        assertEquals("BG_CR", HeroMatcher(cariel).match(listOf(ln("凱瑞·羅姆"))).single().cardId)

    // Regression (codex P2): folding stays ambiguity-safe. If a mark-LESS hero's name equals another
    // hero's name minus the separator, an OCR'd middot read is ambiguous -> NO badge (missing beats wrong).
    @Test fun foldCollisionWithMarklessCanonicalIsDropped() {
        val t = TierTable.fromJson(
            "{\"heroes\":[{\"cardId\":\"BG_A\",\"tier\":\"S\",\"names\":{\"enUS\":\"foo‧bar\"}}," +
                "{\"cardId\":\"BG_B\",\"tier\":\"C\",\"names\":{\"enUS\":\"foobar\"}}]}")
        assertTrue(HeroMatcher(t).match(listOf(ln("foo·bar"))).isEmpty())
    }

    // Regression (codex P2): OCR drops the separator and emits EXACTLY the other hero's markless
    // canonical name (foobar). The exact-first lookup must NOT return B — foobar is indistinguishable
    // from A's separator name folded, so it is ambiguous -> no badge (missing beats wrong).
    @Test fun foldCollisionViaMarklessExactIsDropped() {
        val t = TierTable.fromJson(
            "{\"heroes\":[{\"cardId\":\"BG_A\",\"tier\":\"S\",\"names\":{\"enUS\":\"foo‧bar\"}}," +
                "{\"cardId\":\"BG_B\",\"tier\":\"C\",\"names\":{\"enUS\":\"foobar\"}}]}")
        assertTrue(HeroMatcher(t).match(listOf(ln("foobar"))).isEmpty())
    }

    // Regression (codex P2): a hero's MULTIPLE folded locale keys must not count as competing heroes.
    // Sylvanas zhCN 希瓦娜斯·风行者 / zhTW 希瓦娜斯‧風行者 both fold to ~希瓦娜斯[风/風]行者 (one cardId); an
    // OCR slip within cap of BOTH must resolve to that one hero, not be rejected as ambiguous.
    @Test fun sameHeroMultiLocaleFoldedKeysNotAmbiguous() {
        val t = TierTable.fromJson(
            "{\"heroes\":[{\"cardId\":\"BG_SYL\",\"tier\":\"S\"," +
                "\"names\":{\"zhCN\":\"希瓦娜斯·风行者\",\"zhTW\":\"希瓦娜斯‧風行者\"}}]}")
        // OCR: zhCN form with the middot + a 1-char slip (风->凤) — within cap 1 of both folded locale keys.
        assertEquals("BG_SYL", HeroMatcher(t).match(listOf(ln("希瓦娜斯·凤行者"))).single().cardId)
    }

    // Regression (codex P2): a line that is a COMPLETE hero only via the folded alias (OCR emitted
    // 凱瑞爾羅姆 for table 凱瑞爾‧羅姆) must count as complete in the vertical-merge guard, NOT a fragment —
    // else it merges with a stacked neighbor into a different hero (凱瑞爾羅姆守衛) and emits a spurious badge.
    @Test fun foldCompleteLineIsNotMergedIntoSpuriousBadge() {
        val t = TierTable.fromJson(
            "{\"heroes\":[{\"cardId\":\"BG_X\",\"tier\":\"S\",\"names\":{\"zhTW\":\"凱瑞爾‧羅姆\"}}," +
                "{\"cardId\":\"BG_Y\",\"tier\":\"C\",\"names\":{\"zhTW\":\"凱瑞爾羅姆守衛\"}}]}")
        val top = OcrLine("凱瑞爾羅姆", BoxPx(1707, 648, 1850, 675))   // complete via fold -> BG_X, not a fragment
        val bot = OcrLine("守衛", BoxPx(1707, 683, 1850, 718))
        assertEquals(listOf("BG_X"), HeroMatcher(t).match(listOf(top, bot)).map { it.cardId })
    }
}
