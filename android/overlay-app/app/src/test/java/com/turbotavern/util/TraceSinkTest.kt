package com.turbotavern.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSinkTest {

    @Test
    fun `disabled sink returns disabled cycle that emits nothing`() {
        val sink = TraceSink(enabled = false, clock = ManualClock(), output = capture())
        val cycle = sink.beginCycle()
        assertFalse(cycle.enabled)
        cycle.emit("tap", "entry")
        cycle.emit("close", "exit")
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `enabled cycle within window emits formatted line with its own cycle id`() {
        val clock = ManualClock(initial = 1_000_000_000L)
        val sink = TraceSink(enabled = true, clock = clock, output = capture())
        val cycle = sink.beginCycle()
        cycle.emit("tap", "entry", "state" to "Ready")
        assertEquals(1, captured.size)
        assertTrue(captured[0].contains("phase=tap"))
        assertTrue(captured[0].contains("event=entry"))
        assertTrue(captured[0].contains("cycle=${cycle.cycleId}"))
        assertTrue(captured[0].contains("state=Ready"))
    }

    @Test
    fun `each cycle has its own openUntil — late emit on old cycle is dropped`() {
        val clock = ManualClock(initial = 0L)
        val sink = TraceSink(enabled = true, clock = clock, output = capture(), windowMs = 2_000L)
        val c1 = sink.beginCycle()
        clock.advance(2_100L * 1_000_000L)   // c1 window expired
        c1.emit("poll_tick", "entry")
        assertTrue("c1 emit after expiry should be dropped", captured.isEmpty())
    }

    @Test
    fun `concurrent cycles do not interfere — rapid-tap attribution`() {
        // Verifies codex round-2 P1 #6: TraceCycle is scoped, so a posted
        // runnable from cycle 1 emits with cycle=1 even after cycle 2 begins.
        val clock = ManualClock(initial = 0L)
        val sink = TraceSink(enabled = true, clock = clock, output = capture(), windowMs = 10_000L)
        val cycle1 = sink.beginCycle()
        val cycle2 = sink.beginCycle()       // simulates rapid second tap before first's lambda ran
        cycle1.emit("tap_post", "entry")      // first tap's posted runnable executes
        cycle2.emit("tap_post", "entry")
        assertEquals(2, captured.size)
        assertTrue(captured[0].contains("cycle=${cycle1.cycleId}"))
        assertTrue(captured[1].contains("cycle=${cycle2.cycleId}"))
        assertTrue(cycle2.cycleId > cycle1.cycleId)
    }

    @Test
    fun `default window is 10s — long delays still captured`() {
        // Validates the 5-6s diagnosis use case: a tap whose close phase
        // fires 6s after entry MUST still log.
        val clock = ManualClock(initial = 0L)
        val sink = TraceSink(enabled = true, clock = clock, output = capture())  // default 10s
        val cycle = sink.beginCycle()
        clock.advance(6_000L * 1_000_000L)
        cycle.emit("close", "exit", "result" to "Success")
        assertEquals(1, captured.size)
    }

    @Test
    fun `cycle ids are monotonic`() {
        val clock = ManualClock()
        val sink = TraceSink(enabled = true, clock = clock, output = capture())
        val a = sink.beginCycle()
        val b = sink.beginCycle()
        val c = sink.beginCycle()
        assertTrue(b.cycleId > a.cycleId)
        assertTrue(c.cycleId > b.cycleId)
    }

    private val captured = mutableListOf<String>()
    private fun capture(): (String) -> Unit = { captured += it }
}
