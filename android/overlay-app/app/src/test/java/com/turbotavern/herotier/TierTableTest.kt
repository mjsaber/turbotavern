package com.turbotavern.herotier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierTableTest {
    private fun load(res: String) =
        TierTable.fromJson(javaClass.classLoader!!.getResource(res)!!.readText())

    @Test fun looksUpBothLocales() {
        val t = load("herotier_test.json")
        assertEquals(Tier.S, t.lookup(NameKey.of("斯尼德"))!!.tier)
        assertEquals("BG_HERO_001", t.lookup(NameKey.of("Sneed"))!!.cardId)
        assertEquals(Tier.B, t.lookup(NameKey.of("米羅克"))!!.tier)
        assertNull(t.lookup(NameKey.of("not a hero")))
    }

    @Test fun ambiguousKeyReturnsNull() {
        val t = load("herotier_ambig.json")
        assertNull(t.lookup(NameKey.of("Twin")))                 // two cardIds share "twin" -> reject
        assertTrue(t.keys().contains(NameKey.of("Twin")))        // key present, but ambiguous
        assertEquals("BG_X", t.lookup(NameKey.of("雙子甲"))!!.cardId)   // unique zhTW still resolves
    }

    @Test fun sameHeroBothLocalesNotAmbiguous() {
        val t = load("herotier_test.json")
        assertEquals(t.lookup(NameKey.of("Sneed"))!!.cardId, t.lookup(NameKey.of("斯尼德"))!!.cardId)
    }

    @Test fun localesNormalizingToSameKeySameCardNotAmbiguous() {
        // zhTW "Foo" and enUS "foo" both normalize to "foo"; same cardId -> still resolves.
        val t = TierTable.fromJson(
            """{"heroes":[{"cardId":"BG_F","tier":"A","names":{"zhTW":"Foo","enUS":"foo"}}]}""")
        assertEquals("BG_F", t.lookup(NameKey.of("foo"))!!.cardId)
    }

    @Test fun blankAndMissingLocaleNamesTolerated() {
        // one hero with a blank zhTW and only enUS present; blank key is skipped, enUS resolves.
        val t = TierTable.fromJson(
            """{"heroes":[{"cardId":"BG_G","tier":"C","names":{"zhTW":"  ","enUS":"Gar"}}]}""")
        assertEquals("BG_G", t.lookup(NameKey.of("Gar"))!!.cardId)
        assertEquals(1, t.size)                                  // blank key not stored
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateCardIdFailsLoudly() {
        TierTable.fromJson(
            """{"heroes":[{"cardId":"BG_D","tier":"S","names":{"enUS":"A"}},
                          {"cardId":"BG_D","tier":"C","names":{"enUS":"B"}}]}""")
    }
}
