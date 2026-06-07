package com.bobassist.phase0.herotier

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure §8.2 open/close decision (spec §4.1). No Android. */
class VisualProbeGateTest {

    @Test fun opensOnTwoMatches() {
        val g = VisualProbeGate(openMatches = 2, closeK = 3)
        assertEquals(Transition.Enter, g.onProbe(2))
    }

    @Test fun doesNotOpenOnOneMatch() {
        val g = VisualProbeGate(openMatches = 2, closeK = 3)
        assertEquals(Transition.None, g.onProbe(1))
        assertEquals(Transition.None, g.onProbe(1))
    }

    @Test fun closesOnCloseKConsecutiveZerosExactlyOnTheKth() {
        val g = VisualProbeGate(openMatches = 2, closeK = 3)
        g.onProbe(2)                                   // open
        assertEquals(Transition.None, g.onProbe(0))    // zero #1
        assertEquals(Transition.None, g.onProbe(0))    // zero #2
        assertEquals(Transition.Exit, g.onProbe(0))    // zero #3 -> close
    }

    @Test fun nonZeroBetweenZerosResetsTheCounter() {
        val g = VisualProbeGate(openMatches = 2, closeK = 3)
        g.onProbe(2)                                   // open
        g.onProbe(0); g.onProbe(0)                     // 2 zeros
        assertEquals(Transition.None, g.onProbe(1))    // reset (1 keeps it open: <openMatches but >0)
        assertEquals(Transition.None, g.onProbe(0))    // zero #1 again
        assertEquals(Transition.None, g.onProbe(0))    // zero #2
        assertEquals(Transition.Exit, g.onProbe(0))    // zero #3 -> close
    }

    @Test fun openIsSingleFire() {
        val g = VisualProbeGate(openMatches = 2, closeK = 3)
        assertEquals(Transition.Enter, g.onProbe(2))
        assertEquals(Transition.None, g.onProbe(2))    // already open
        assertEquals(Transition.None, g.onProbe(5))
    }

    @Test fun noDoubleExitAfterNaturalClose() {
        val g = VisualProbeGate(openMatches = 2, closeK = 3)
        g.onProbe(2); g.onProbe(0); g.onProbe(0)
        assertEquals(Transition.Exit, g.onProbe(0))    // natural close
        assertEquals(Transition.None, g.onProbe(0))    // no second Exit
        assertEquals(Transition.None, g.onProbe(0))
    }

    @Test fun forceCloseResetsAndAllowsReopen() {
        val g = VisualProbeGate(openMatches = 2, closeK = 3)
        g.onProbe(2)                                   // open
        g.onProbe(0)                                   // 1 zero accrued
        g.forceClose()
        assertEquals(Transition.Enter, g.onProbe(2))   // reopens cleanly
        assertEquals(Transition.None, g.onProbe(0))    // zero counter was reset by forceClose/open
    }

    @Test fun reopensAfterNaturalClose() {
        val g = VisualProbeGate(openMatches = 2, closeK = 3)
        g.onProbe(2); g.onProbe(0); g.onProbe(0); g.onProbe(0)   // open then close
        assertEquals(Transition.Enter, g.onProbe(2))             // can reopen
    }
}
