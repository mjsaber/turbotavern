package com.bobassist.phase0.herotier

import org.junit.Assert.assertEquals
import org.junit.Test

class PpRecCtcTest {
    // character = ["blank","a","b","c"," "]: blank=0, space=4.
    private val ctc = PpRecCtc(PpRecCtc.charTableFromDict(listOf("a", "b", "c")))

    /** one-hot logits row for class `c`. */
    private fun row(c: Int) = FloatArray(5).also { it[c] = 1f }
    private fun seq(vararg cs: Int) = Array(cs.size) { row(cs[it]) }

    @Test fun collapsesConsecutiveRepeats() =
        assertEquals("ab", ctc.decode(seq(1, 1, 2)).text)            // a a b -> ab

    @Test fun blankBetweenEqualCharsKeepsBoth() =
        assertEquals("aa", ctc.decode(seq(1, 0, 1)).text)            // a blank a -> aa

    @Test fun dropsLeadingTrailingBlanks() =
        assertEquals("abc", ctc.decode(seq(0, 1, 2, 3, 0)).text)

    @Test fun emitsTrailingSpaceChar() =                              // index 4 = " "
        assertEquals("a b", ctc.decode(seq(1, 4, 2)).text)

    @Test fun allBlankIsEmptyWithZeroConfidence() {
        val r = ctc.decode(seq(0, 0, 0))
        assertEquals("", r.text)
        assertEquals(0f, r.confidence, 0f)
    }

    @Test fun confidenceIsMeanOfKeptMaxProbs() {
        // timesteps: a@0.8 (kept), a@0.6 (collapsed), b@0.4 (kept) -> mean(0.8,0.4)=0.6
        val logits = arrayOf(
            FloatArray(5).also { it[1] = 0.8f },
            FloatArray(5).also { it[1] = 0.6f },
            FloatArray(5).also { it[2] = 0.4f },
        )
        val r = ctc.decode(logits)
        assertEquals("ab", r.text)
        assertEquals(0.6f, r.confidence, 1e-6f)
    }

    @Test fun charTableHasBlankFirstAndSpaceLast() {
        val t = PpRecCtc.charTableFromDict(listOf("x", "y"))
        assertEquals(listOf("blank", "x", "y", " "), t)
    }
}
