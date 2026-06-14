package com.turbotavern.trinket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for [BadgeStabilizer] — the anti-flicker gate. These are the primary
 * regression net for the live hero 4↔3 blink; the SelectCoordinator integration test proves the wiring.
 */
class BadgeStabilizerTest {

    // Test item with a stable key and a mutable "value" (stands in for a badge's box) so we can prove a
    // re-seen item updates while a held item keeps its last-seen value.
    private data class B(val id: String, val v: Int)

    private fun stab(maxMisses: Int = 2) = BadgeStabilizer<B>(keyOf = { it.id }, maxMisses = maxMisses)
    private fun ids(items: List<B>) = items.map { it.id }

    @Test fun seenEveryFrameAlwaysPresent() {
        val s = stab()
        repeat(5) { assertEquals(listOf("a"), ids(s.update(listOf(B("a", 0))))) }
    }

    @Test fun missedWithinToleranceIsHeld() {
        val s = stab(maxMisses = 2)
        s.update(listOf(B("a", 0)))
        assertEquals("miss 1 of 2 -> held", listOf("a"), ids(s.update(emptyList())))
        assertEquals("miss 2 of 2 -> held", listOf("a"), ids(s.update(emptyList())))
    }

    @Test fun missedBeyondToleranceIsDropped() {
        val s = stab(maxMisses = 2)
        s.update(listOf(B("a", 0)))
        s.update(emptyList())                                  // miss 1
        s.update(emptyList())                                  // miss 2
        assertEquals("miss 3 of 2 -> dropped", emptyList<String>(), ids(s.update(emptyList())))
    }

    @Test fun reSeenResetsMissCounter() {
        val s = stab(maxMisses = 2)
        s.update(listOf(B("a", 0)))
        s.update(emptyList())                                  // miss 1
        s.update(listOf(B("a", 0)))                            // re-seen -> counter back to 0
        s.update(emptyList())                                  // miss 1 again
        assertEquals("counter is consecutive, not cumulative", listOf("a"), ids(s.update(emptyList())))  // miss 2 -> still held
    }

    @Test fun newItemAppearsImmediately() {
        val s = stab()
        assertEquals(listOf("a"), ids(s.update(listOf(B("a", 0)))))
        assertEquals(listOf("a", "b"), ids(s.update(listOf(B("a", 0), B("b", 0)))))
    }

    @Test fun heldItemKeepsLastValue_reSeenUpdatesValue() {
        val s = stab(maxMisses = 2)
        s.update(listOf(B("a", 1)))
        assertEquals("held item keeps its last-seen value", 1, s.update(emptyList()).single().v)
        assertEquals("re-seen item updates to the fresh value", 2, s.update(listOf(B("a", 2))).single().v)
    }

    @Test fun insertionOrderIsStableAcrossHolds() {
        val s = stab(maxMisses = 2)
        s.update(listOf(B("a", 0), B("b", 0), B("c", 0)))
        // b seen, a+c missed (held in place) -> order preserved
        assertEquals(listOf("a", "b", "c"), ids(s.update(listOf(B("b", 0)))))
    }

    @Test fun resetClearsEverything() {
        val s = stab()
        s.update(listOf(B("a", 0), B("b", 0)))
        s.reset()
        assertEquals(emptyList<String>(), ids(s.update(emptyList())))
    }

    // Documents why trinkets are excluded: on a full-set swap (reroll-like) the old set lingers for
    // maxMisses frames, overlapping the new set — unacceptable in fixed shop slots, fine for a hero
    // select whose set never changes mid-window.
    @Test fun fullSetSwapAgesOutOldWithinTolerance() {
        val s = stab(maxMisses = 2)
        s.update(listOf(B("a", 0), B("b", 0)))
        val swap1 = ids(s.update(listOf(B("c", 0), B("d", 0))))   // a,b miss1 (held) + c,d new
        assertTrue("old + new overlap right after a swap", swap1.containsAll(listOf("a", "b", "c", "d")))
        s.update(listOf(B("c", 0), B("d", 0)))                    // a,b miss2 (held)
        assertEquals("old fully aged out at miss 3", listOf("c", "d"), ids(s.update(listOf(B("c", 0), B("d", 0)))))
    }
}
