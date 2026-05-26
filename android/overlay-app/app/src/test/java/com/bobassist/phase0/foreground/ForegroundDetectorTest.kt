package com.bobassist.phase0.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDetectorTest {

    @Test
    fun `default state is target-foreground=true (optimistic)`() {
        val detector = ForegroundDetector(
            queryForegroundPackage = { null },
            targetPackage = HS,
            onChange = { },
        )
        assertTrue(detector.isTargetForeground)
    }

    @Test
    fun `transitions to false when foreground is some other app`() {
        var current: String? = null
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        current = "com.example.notes"
        detector.tick()
        assertFalse(detector.isTargetForeground)
        assertEquals(listOf(false), changes)
    }

    @Test
    fun `transitions back to true when HS returns to foreground`() {
        var current: String? = "com.example.notes"
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.tick()                  // → false (changed)
        current = HS
        detector.tick()                  // → true (changed)
        assertTrue(detector.isTargetForeground)
        assertEquals(listOf(false, true), changes)
    }

    @Test
    fun `no onChange call when state stays the same`() {
        var current: String? = HS
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.tick()                  // HS already assumed → no change
        detector.tick()
        detector.tick()
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test
    fun `null query result preserves previous state (no events)`() {
        var current: String? = "com.example.notes"
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.tick()                  // → false
        current = null                   // no recent events
        detector.tick()                  // → no change, stays false
        detector.tick()
        assertFalse(detector.isTargetForeground)
        assertEquals(listOf(false), changes)
    }

    @Test
    fun `reset reverts to optimistic true and notifies`() {
        var current: String? = "com.example.notes"
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.tick()                  // → false
        detector.reset()                 // → true (optimistic)
        assertTrue(detector.isTargetForeground)
        assertEquals(listOf(false, true), changes)
    }

    @Test
    fun `reset is idempotent when already true`() {
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { null },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.reset()
        detector.reset()
        assertTrue(detector.isTargetForeground)
        assertEquals(emptyList<Boolean>(), changes)
    }

    companion object {
        private const val HS = "com.blizzard.wtcg.hearthstone"
    }
}
