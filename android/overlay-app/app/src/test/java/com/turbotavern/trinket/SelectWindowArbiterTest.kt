package com.turbotavern.trinket

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectWindowArbiterTest {

    @Test fun opensHeroOnHeroMatches() {
        val a = SelectWindowArbiter()
        assertEquals(SelectWindow.HERO, a.onProbe(heroMatches = 2, trinketMatches = 0))
    }

    @Test fun opensTrinketOnTrinketMatches() {
        val a = SelectWindowArbiter()
        assertEquals(SelectWindow.TRINKET, a.onProbe(heroMatches = 0, trinketMatches = 3))
    }

    @Test fun belowThresholdStaysNone() {
        val a = SelectWindowArbiter()
        assertEquals(SelectWindow.NONE, a.onProbe(1, 1))
        assertEquals(SelectWindow.NONE, a.onProbe(0, 0))
    }

    @Test fun whileHeroOpenTrinketCannotCrossFire() {
        val a = SelectWindowArbiter()
        a.onProbe(2, 0)                                   // HERO open
        // a stray trinket-dictionary hit while heroes are still visible must NOT flip to trinket
        assertEquals(SelectWindow.HERO, a.onProbe(3, 4))
        assertEquals(SelectWindow.HERO, a.onProbe(2, 9))
    }

    @Test fun heroClosesAfterThreeZerosThenTrinketCanOpen() {
        val a = SelectWindowArbiter()
        a.onProbe(2, 0)                                   // HERO
        assertEquals(SelectWindow.HERO, a.onProbe(0, 0))  // zero 1
        assertEquals(SelectWindow.HERO, a.onProbe(0, 0))  // zero 2
        assertEquals(SelectWindow.NONE, a.onProbe(0, 0))  // zero 3 -> closes
        assertEquals(SelectWindow.TRINKET, a.onProbe(0, 2))  // now the trinket phase can open
    }

    @Test fun trinketHeldOpenWhileTrinketsVisibleThenCloses() {
        val a = SelectWindowArbiter()
        a.onProbe(0, 2)                                   // TRINKET
        assertEquals(SelectWindow.TRINKET, a.onProbe(0, 1))  // 1 match resets the zero run, stays open
        assertEquals(SelectWindow.TRINKET, a.onProbe(0, 0))
        assertEquals(SelectWindow.TRINKET, a.onProbe(0, 0))
        assertEquals(SelectWindow.NONE, a.onProbe(0, 0))     // 3rd consecutive zero closes
    }

    @Test fun heroWinsTheImpossibleTie() {
        val a = SelectWindowArbiter()
        assertEquals(SelectWindow.HERO, a.onProbe(2, 2))  // disjoint dicts make this not happen; deterministic anyway
    }

    @Test fun forceCloseResetsToNone() {
        val a = SelectWindowArbiter()
        a.onProbe(2, 0)
        assertEquals(SelectWindow.HERO, a.active)
        a.forceClose()
        assertEquals(SelectWindow.NONE, a.active)
        // after a force-close, the next phase opens cleanly
        assertEquals(SelectWindow.TRINKET, a.onProbe(0, 2))
    }
}
