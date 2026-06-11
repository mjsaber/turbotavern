package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.BoxPx
import com.bobassist.phase0.herotier.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrinketMatcherTest {

    private val json = """
        {"trinkets":[
          {"cardId":"L1","trinketClass":"lesser","tier":"S","avgPlacement":3.4,
           "names":{"enUS":"Welcome Inn","zhTW":"歡迎客棧"}},
          {"cardId":"G1","trinketClass":"greater","tier":"A","avgPlacement":4.1,
           "names":{"enUS":"Grand Trove","zhTW":"宏偉寶庫"}},
          {"cardId":"L_SHARED","trinketClass":"lesser","tier":"B","avgPlacement":4.4,
           "names":{"enUS":"Mystic Charm"}},
          {"cardId":"G_SHARED","trinketClass":"greater","tier":"C","avgPlacement":5.4,
           "names":{"enUS":"Mystic Charm"}}
        ]}
    """.trimIndent()
    private val matcher = TrinketMatcher(TrinketTable.fromJson(json))
    private fun ln(s: String) = OcrLine(s, BoxPx(0, 0, 10, 10))

    @Test fun exactMatchAcrossLocales() {
        assertEquals(listOf("L1"), matcher.match(listOf(ln("Welcome Inn"))).map { it.entry.cardId })
        assertEquals(listOf("G1"), matcher.match(listOf(ln("宏偉寶庫"))).map { it.entry.cardId })
    }

    @Test fun fuzzyRecoversSingleCharOcrSlip() {
        // "Grand Trose" (v->s) is one edit from "Grand Trove"
        assertEquals(listOf("G1"), matcher.match(listOf(ln("Grand Trose"))).map { it.entry.cardId })
    }

    @Test fun classHintDisambiguatesSharedName() {
        assertEquals("L_SHARED", matcher.match(listOf(ln("Mystic Charm")), TrinketClass.LESSER).single().entry.cardId)
        assertEquals("G_SHARED", matcher.match(listOf(ln("Mystic Charm")), TrinketClass.GREATER).single().entry.cardId)
        assertTrue("no hint -> ambiguous shared name yields no match",
            matcher.match(listOf(ln("Mystic Charm"))).isEmpty())
    }

    @Test fun stripsCardFrameEdgeBars() {
        assertEquals(listOf("L1"), matcher.match(listOf(ln("|Welcome Inn|"))).map { it.entry.cardId })
    }

    @Test fun noFalsePositiveOnUiChrome() {
        for (s in listOf("選擇一個飾品", "Choose a Trinket", "重骰", "Refresh")) {
            assertTrue("\"$s\" should not match", matcher.match(listOf(ln(s))).isEmpty())
        }
    }

    @Test fun matchesAllOfferedInOneCall() {
        val got = matcher.match(listOf(ln("Welcome Inn"), ln("宏偉寶庫")), null).map { it.entry.cardId }
        // Welcome Inn (lesser) and Grand Trove (greater) are both unique names -> both resolve even w/o hint
        assertEquals(setOf("L1", "G1"), got.toSet())
    }
}
