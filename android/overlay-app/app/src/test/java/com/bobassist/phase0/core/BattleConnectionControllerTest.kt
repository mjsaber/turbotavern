package com.bobassist.phase0.core

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
                MihomoCore.CloseResult.Success
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
                MihomoCore.CloseResult.Success
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
            close = { MihomoCore.CloseResult.AlreadyClosed },
        )
        assertTrue(ctrl.killBattleSocket() is BattleConnectionController.KillResult.AlreadyClosed)
    }

    @Test
    fun `wraps unexpected close failures as Failure`() {
        val ctrl = BattleConnectionController(
            snapshot = { ONE_CANDIDATE_JSON },
            close = { MihomoCore.CloseResult.CoreStopped },
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
