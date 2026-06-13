package com.turbotavern.trinket

import com.turbotavern.herotier.NameKey
import com.turbotavern.herotier.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrinketTableTest {

    private val json = """
        {"trinkets":[
          {"cardId":"L1","trinketClass":"lesser","tier":"S","avgPlacement":3.4,
           "names":{"enUS":"Welcome Inn","zhTW":"歡迎客棧","zhCN":"欢迎客栈"}},
          {"cardId":"G1","trinketClass":"greater","tier":"A","avgPlacement":4.1,
           "names":{"enUS":"Grand Trove","zhTW":"宏偉寶庫","zhCN":"宏伟宝库"}},
          {"cardId":"L_SHARED","trinketClass":"lesser","tier":"B","avgPlacement":4.4,
           "names":{"enUS":"Mystic Charm","zhTW":"神秘飾品"}},
          {"cardId":"G_SHARED","trinketClass":"greater","tier":"C","avgPlacement":5.4,
           "names":{"enUS":"Mystic Charm","zhTW":"神秘飾品"}},
          {"cardId":"L_SEP","trinketClass":"lesser","tier":"A","avgPlacement":3.9,
           "names":{"zhTW":"泰朗‧寶箱"}}
        ]}
    """.trimIndent()

    private val table = TrinketTable.fromJson(json)

    @Test fun looksUpAcrossLocales() {
        assertEquals("L1", table.lookup(NameKey.of("Welcome Inn"))!!.cardId)
        assertEquals(Tier.S, table.lookup(NameKey.of("歡迎客棧"))!!.tier)
        assertEquals("L1", table.lookup(NameKey.of("欢迎客栈"))!!.cardId)
        assertEquals("G1", table.lookup(NameKey.of("宏偉寶庫"))!!.cardId)
    }

    @Test fun collidingBaseNameAcrossClassesResolvesViaClassHint() {
        // "神秘飾品" is shared by a lesser and a greater -> ambiguous without a hint...
        assertNull(table.lookup(NameKey.of("神秘飾品")))
        // ...but the offer turn's class disambiguates it.
        assertEquals("L_SHARED", table.lookup(NameKey.of("神秘飾品"), TrinketClass.LESSER)!!.cardId)
        assertEquals("G_SHARED", table.lookup(NameKey.of("神秘飾品"), TrinketClass.GREATER)!!.cardId)
    }

    @Test fun classHintThatStillLeavesOneCandidateIsFine() {
        // unique name + correct hint resolves; unique name + wrong-class hint yields null (not wrong).
        assertEquals("L1", table.lookup(NameKey.of("Welcome Inn"), TrinketClass.LESSER)!!.cardId)
        assertNull(table.lookup(NameKey.of("Welcome Inn"), TrinketClass.GREATER))
    }

    @Test fun separatorFoldedAliasMatchesOcrWithoutTheUndecodableMark() {
        // PP-OCRv5 cannot emit U+2027, so the OCR line is "泰朗寶箱" — the folded alias must resolve it,
        // and the canonical "泰朗‧寶箱" must still resolve too.
        assertEquals("L_SEP", table.lookup(NameKey.of("泰朗寶箱"))!!.cardId)
        assertEquals("L_SEP", table.lookup(NameKey.of("泰朗‧寶箱"))!!.cardId)
    }

    @Test fun unknownNameIsNull() {
        assertNull(table.lookup(NameKey.of("not a trinket")))
    }

    @Test fun duplicateCardIdRejected() {
        val dup = """{"trinkets":[
          {"cardId":"D","trinketClass":"lesser","tier":"S","avgPlacement":3.0,"names":{"enUS":"A"}},
          {"cardId":"D","trinketClass":"greater","tier":"A","avgPlacement":4.0,"names":{"enUS":"B"}}]}"""
        try {
            TrinketTable.fromJson(dup); throw AssertionError("expected duplicate-cardId rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message!!.contains("duplicate cardId"))
        }
    }
}
