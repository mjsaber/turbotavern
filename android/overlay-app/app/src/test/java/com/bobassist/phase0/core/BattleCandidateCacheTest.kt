package com.bobassist.phase0.core

import com.bobassist.phase0.util.ManualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for the Phase 1.4 candidate cache. */
class BattleCandidateCacheTest {

    private fun cache(json: () -> String, clock: ManualClock = ManualClock(0L)) =
        BattleCandidateCache(snapshot = json, clock = clock)

    @Test
    fun `refresh with one candidate caches it and returns 1`() {
        val c = cache({ ONE })
        assertEquals(1, c.refresh())
        val r = c.current()
        assertEquals("abc-1", r.candidate?.id)
        assertEquals(1, r.count)
    }

    @Test
    fun `refresh with empty caches null and returns 0`() {
        val c = cache({ "[]" })
        assertEquals(0, c.refresh())
        assertNull(c.current().candidate)
        assertEquals(0, c.current().count)
    }

    @Test
    fun `refresh with two candidates picks newest-by-createdAt`() {
        val c = cache({ TWO_OLD_NEW })
        assertEquals(2, c.refresh())
        assertEquals("newer", c.current().candidate?.id)   // createdAt 2000 > 1000
        assertEquals(2, c.current().count)
    }

    @Test
    fun `current reflects latest refresh`() {
        var json = ONE
        val c = cache({ json })
        c.refresh()
        assertEquals("abc-1", c.current().candidate?.id)
        json = "[]"
        c.refresh()
        assertNull(c.current().candidate)
    }

    @Test
    fun `clear resets to empty`() {
        val c = cache({ ONE })
        c.refresh()
        c.clear()
        assertNull(c.current().candidate)
        assertEquals(0, c.current().count)
    }

    @Test
    fun `capturedAtMs comes from clock`() {
        // ManualClock is in NANOS; capturedAtMs uses nowMillis() = nowNanos()/1e6.
        val clock = ManualClock(1_234_000_000L)   // 1234 ms
        val c = cache({ ONE }, clock)
        c.refresh()
        assertEquals(1234L, c.current().capturedAtMs)
    }

    @Test
    fun `refresh matches the port-1119 game socket (2026-05 build)`() {
        val c = cache({ ONE_1119 })
        assertEquals(1, c.refresh())
        assertEquals("g-1119", c.current().candidate?.id)
        assertEquals(1119, c.current().candidate?.destinationPort)
    }

    @Test
    fun `readiness keyed on candidate not raw count (non-candidate snapshot is empty)`() {
        // host != "" → not a battle socket → no candidate → return 0.
        val c = cache({ NON_CANDIDATE })
        assertEquals(0, c.refresh())
        assertNull(c.current().candidate)
        assertEquals(0, c.current().count)
    }

    // --- zero-candidate near-miss logging (debt #9 diagnostics) ---

    private class TracedCache(json: () -> String) {
        val lines = mutableListOf<String>()
        var json = json
        val cache = BattleCandidateCache(
            snapshot = { json() },
            clock = ManualClock(0L),
            trace = com.bobassist.phase0.util.TraceSink(
                enabled = true, clock = ManualClock(0L), output = { lines.add(it) },
            ),
        )
        fun zeroTableLines() = lines.filter { it.contains("phase=zero_candidate_table") }
    }

    @Test
    fun `zero-candidate refresh emits the explained table once`() {
        val t = TracedCache({ NON_CANDIDATE })
        t.cache.refresh()
        val emitted = t.zeroTableLines()
        assertEquals(1, emitted.size)
        assertEquals(true, emitted[0].contains("HOST(battle.net)"))
    }

    @Test
    fun `identical zero-candidate tables are not re-emitted`() {
        val t = TracedCache({ NON_CANDIDATE })
        t.cache.refresh()
        t.cache.refresh()
        t.cache.refresh()
        assertEquals(1, t.zeroTableLines().size)
    }

    @Test
    fun `changed zero-candidate table is re-emitted`() {
        var current = NON_CANDIDATE
        val t = TracedCache({ current })
        t.cache.refresh()
        current = "[]"
        t.cache.refresh()
        val emitted = t.zeroTableLines()
        assertEquals(2, emitted.size)
        assertEquals(true, emitted[1].contains("conns=0"))
    }

    @Test
    fun `candidate-found resets the signature so the next zero window re-emits`() {
        var current = NON_CANDIDATE
        val t = TracedCache({ current })
        t.cache.refresh()                 // zero → emit
        current = ONE
        t.cache.refresh()                 // candidate found → reset
        current = NON_CANDIDATE
        t.cache.refresh()                 // same zero table again → must re-emit
        assertEquals(2, t.zeroTableLines().size)
    }

    companion object {
        private const val ONE =
            """[{"id":"abc-1","host":"","network":"tcp","destinationIp":"1.2.3.4","destinationPort":3724,"createdAt":1000}]"""
        private const val TWO_OLD_NEW =
            """[{"id":"older","host":"","network":"tcp","destinationIp":"1.2.3.4","destinationPort":3724,"createdAt":1000},""" +
            """{"id":"newer","host":"","network":"tcp","destinationIp":"1.2.3.5","destinationPort":3724,"createdAt":2000}]"""
        private const val NON_CANDIDATE =
            """[{"id":"x","host":"battle.net","network":"tcp","destinationIp":"1.2.3.4","destinationPort":3724,"createdAt":1000}]"""
        private const val ONE_1119 =
            """[{"id":"g-1119","host":"","network":"tcp","destinationIp":"66.40.188.46","destinationPort":1119,"createdAt":1000}]"""
    }
}
