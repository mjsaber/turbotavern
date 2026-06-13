package com.turbotavern.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleConnectionControllerTest {

    @Test
    fun `returns NoCandidate when snapshot has no battle socket`() {
        val ctrl = BattleConnectionController(
            snapshot = { """[]""" },
            close = { error("should not be called") },
        )
        assertTrue(ctrl.killBattleSocket() is BattleConnectionController.KillResult.NoCandidate)
    }

    @Test
    fun `closes the unique candidate and returns Success`() {
        var closedId: String? = null
        val ctrl = BattleConnectionController(
            snapshot = { ONE_CANDIDATE_JSON },
            close = { id ->
                closedId = id
                CloseResult.Success
            },
        )
        val r = ctrl.killBattleSocket()
        assertTrue(r is BattleConnectionController.KillResult.Success)
        assertEquals("abc-1", closedId)
        assertEquals(1, (r as BattleConnectionController.KillResult.Success).candidatesAtKill)
    }

    @Test
    fun `with two candidates picks newest and reports count`() {
        var closedId: String? = null
        val ctrl = BattleConnectionController(
            snapshot = { TWO_CANDIDATE_JSON },
            close = { id ->
                closedId = id
                CloseResult.Success
            },
        )
        val r = ctrl.killBattleSocket() as BattleConnectionController.KillResult.Success
        assertEquals("newer-id", closedId)  // createdAt 2000 > 1000
        assertEquals(2, r.candidatesAtKill)
    }

    @Test
    fun `propagates AlreadyClosed result`() {
        val ctrl = BattleConnectionController(
            snapshot = { ONE_CANDIDATE_JSON },
            close = { CloseResult.AlreadyClosed },
        )
        assertTrue(ctrl.killBattleSocket() is BattleConnectionController.KillResult.AlreadyClosed)
    }

    @Test
    fun `wraps unexpected close failures as Failure`() {
        val ctrl = BattleConnectionController(
            snapshot = { ONE_CANDIDATE_JSON },
            close = { CloseResult.CoreStopped },
        )
        val r = ctrl.killBattleSocket()
        assertTrue(r is BattleConnectionController.KillResult.Failure)
    }

    @Test
    fun `wraps malformed snapshot as NoCandidate`() {
        val ctrl = BattleConnectionController(
            snapshot = { "this is not json" },
            close = { error("should not be called") },
        )
        assertTrue(ctrl.killBattleSocket() is BattleConnectionController.KillResult.NoCandidate)
    }

    // --- Phase 1.4: killCachedCandidate (closes an already-picked candidate, no snapshot) ---

    private val cand = BattleConnection.Candidate(
        id = "cached-1", destinationIp = "66.40.189.5", destinationPort = 3724, createdAt = 1000L,
    )

    @Test
    fun `killCachedCandidate success maps to Success with candidate fields`() {
        var closedId: String? = null
        val ctrl = BattleConnectionController(
            snapshot = { error("snapshot must not be called on cached path") },
            close = { id -> closedId = id; CloseResult.Success },
        )
        val r = ctrl.killCachedCandidate(cand, candidatesAtKill = 1)
            as BattleConnectionController.KillResult.Success
        assertEquals("cached-1", closedId)
        assertEquals("cached-1", r.closedId)
        assertEquals("66.40.189.5", r.destinationIp)
        assertEquals(3724, r.destinationPort)
        assertEquals(1, r.candidatesAtKill)
    }

    @Test
    fun `killCachedCandidate does NOT call snapshot`() {
        var snapshotCalls = 0
        val ctrl = BattleConnectionController(
            snapshot = { snapshotCalls++; "[]" },
            close = { CloseResult.Success },
        )
        ctrl.killCachedCandidate(cand, candidatesAtKill = 1)
        assertEquals(0, snapshotCalls)
    }

    @Test
    fun `killCachedCandidate NotFound maps to Failure (stale cache)`() {
        val ctrl = BattleConnectionController(
            snapshot = { error("should not be called") },
            close = { CloseResult.NotFound },
        )
        val r = ctrl.killCachedCandidate(cand, candidatesAtKill = 1)
        assertTrue(r is BattleConnectionController.KillResult.Failure)
        assertEquals("NotFound", (r as BattleConnectionController.KillResult.Failure).reason)
    }

    @Test
    fun `killCachedCandidate AlreadyClosed maps to AlreadyClosed`() {
        val ctrl = BattleConnectionController(
            snapshot = { error("should not be called") },
            close = { CloseResult.AlreadyClosed },
        )
        assertTrue(
            ctrl.killCachedCandidate(cand, candidatesAtKill = 1)
                is BattleConnectionController.KillResult.AlreadyClosed,
        )
    }

    // --- killCachedCandidateThenRetry: one bounded fresh-snapshot retry on a stale (NotFound) cached id ---

    @Test
    fun `retry on NotFound takes ONE fresh snapshot and kills the rotated live socket`() {
        var snapshots = 0
        val closed = ArrayList<String>()
        val ctrl = BattleConnectionController(
            snapshot = { snapshots++; ONE_CANDIDATE_JSON },          // fresh snapshot has the rotated socket abc-1
            close = { id ->
                closed += id
                if (id == "cached-1") CloseResult.NotFound else CloseResult.Success
            },
        )
        val r = ctrl.killCachedCandidateThenRetry(cand, candidatesAtKill = 1)
        assertTrue("rotated socket should be killed on retry", r is BattleConnectionController.KillResult.Success)
        assertEquals("exactly one fallback snapshot", 1, snapshots)
        assertEquals(listOf("cached-1", "abc-1"), closed)            // stale close, then rotated close
    }

    @Test
    fun `retry is bounded - a genuinely-absent socket fails cleanly as NoCandidate after one snapshot`() {
        var snapshots = 0
        val ctrl = BattleConnectionController(
            snapshot = { snapshots++; "[]" },                        // socket really gone
            close = { CloseResult.NotFound },
        )
        val r = ctrl.killCachedCandidateThenRetry(cand, candidatesAtKill = 1)
        assertTrue(r is BattleConnectionController.KillResult.NoCandidate)
        assertEquals("retry is bounded to a single snapshot", 1, snapshots)
    }

    @Test
    fun `no retry on Success - fast path stays snapshot-free`() {
        var snapshots = 0
        val ctrl = BattleConnectionController(
            snapshot = { snapshots++; "[]" },
            close = { CloseResult.Success },
        )
        assertTrue(ctrl.killCachedCandidateThenRetry(cand, 1) is BattleConnectionController.KillResult.Success)
        assertEquals("Success must not trigger a snapshot", 0, snapshots)
    }

    @Test
    fun `no retry on AlreadyClosed`() {
        var snapshots = 0
        val ctrl = BattleConnectionController(
            snapshot = { snapshots++; "[]" },
            close = { CloseResult.AlreadyClosed },
        )
        assertTrue(ctrl.killCachedCandidateThenRetry(cand, 1) is BattleConnectionController.KillResult.AlreadyClosed)
        assertEquals(0, snapshots)
    }

    companion object {
        private val ONE_CANDIDATE_JSON = """
            [{"id":"abc-1","host":"","network":"tcp","destinationIp":"66.40.189.110",
              "destinationPort":3724,"createdAt":1000}]
        """.trimIndent()

        private val TWO_CANDIDATE_JSON = """
            [
              {"id":"older-id","host":"","network":"tcp","destinationIp":"66.40.189.1",
               "destinationPort":3724,"createdAt":1000},
              {"id":"newer-id","host":"","network":"tcp","destinationIp":"66.40.189.2",
               "destinationPort":3724,"createdAt":2000}
            ]
        """.trimIndent()
    }
}
