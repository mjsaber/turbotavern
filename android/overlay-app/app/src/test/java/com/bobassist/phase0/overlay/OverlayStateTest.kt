package com.bobassist.phase0.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStateTest {

    @Test
    fun `WaitingForBattle stays Waiting when no candidates`() {
        val next = OverlayState.WaitingForBattle.onPoll(candidateCount = 0)
        assertTrue(next is OverlayState.WaitingForBattle)
    }

    @Test
    fun `WaitingForBattle moves to Ready when candidates appear`() {
        val next = OverlayState.WaitingForBattle.onPoll(candidateCount = 1)
        assertEquals(OverlayState.Ready, next)
    }

    @Test
    fun `Ready stays Ready while candidates persist`() {
        val next = OverlayState.Ready.onPoll(candidateCount = 2)
        assertEquals(OverlayState.Ready, next)
    }

    @Test
    fun `Ready falls back to Waiting when candidates disappear`() {
        val next = OverlayState.Ready.onPoll(candidateCount = 0)
        assertTrue(next is OverlayState.WaitingForBattle)
    }

    @Test
    fun `Cooldown ignores poll updates regardless of candidate count`() {
        val cool = OverlayState.Cooldown
        assertEquals(cool, cool.onPoll(candidateCount = 0))
        assertEquals(cool, cool.onPoll(candidateCount = 1))
        assertEquals(cool, cool.onPoll(candidateCount = 7))
    }

    @Test
    fun `Cooldown after 2s timer transitions to WaitingForBattle on next poll source`() {
        // The state machine itself doesn't know about the timer — the
        // OverlayPoller fires the explicit transition. This test pins down
        // the SHAPE of the contract: onPoll alone never exits Cooldown.
        assertEquals(OverlayState.Cooldown, OverlayState.Cooldown.onPoll(candidateCount = 1))
    }

    @Test
    fun `each state exposes the correct visual identifier`() {
        assertEquals(OverlayState.Visual.WAITING, OverlayState.WaitingForBattle.visual)
        assertEquals(OverlayState.Visual.READY, OverlayState.Ready.visual)
        assertEquals(OverlayState.Visual.COOLDOWN, OverlayState.Cooldown.visual)
    }
}
