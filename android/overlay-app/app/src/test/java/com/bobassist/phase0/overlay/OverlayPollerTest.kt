package com.bobassist.phase0.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPollerTest {

    @Test
    fun `emits initial state on start`() {
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { 0 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> error("not scheduled in this test") },
        )
        poller.start()
        assertEquals(listOf<OverlayState>(OverlayState.WaitingForBattle), emitted)
    }

    @Test
    fun `transitions Waiting to Ready when poll sees a candidate`() {
        val emitted = mutableListOf<OverlayState>()
        var count = 0
        val poller = OverlayPoller(
            snapshot = { count },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> },
        )
        poller.start()
        count = 1
        poller.tick()
        assertEquals(listOf<OverlayState>(OverlayState.WaitingForBattle, OverlayState.Ready), emitted)
    }

    @Test
    fun `does not re-emit when poll repeats same state`() {
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> },
        )
        poller.start()      // emits WaitingForBattle
        poller.tick()       // emits Ready
        poller.tick()       // stays Ready, no emit
        poller.tick()       // stays Ready, no emit
        assertEquals(2, emitted.size)
    }

    @Test
    fun `enterCooldown moves state to Cooldown and schedules the exit timer`() {
        val scheduled = mutableListOf<Long>()
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { delayMs, _ -> scheduled += delayMs },
        )
        poller.start()
        poller.tick()                 // Ready
        poller.enterCooldown()
        assertEquals(OverlayState.Visual.COOLDOWN, emitted.last().visual)
        assertEquals(listOf(OverlayState.COOLDOWN_MS), scheduled)
    }

    @Test
    fun `cooldown timer callback returns state to WaitingForBattle`() {
        val emitted = mutableListOf<OverlayState>()
        var expireCallback: (() -> Unit)? = null
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, cb -> expireCallback = cb },
        )
        poller.start()
        poller.tick()
        poller.enterCooldown()
        expireCallback!!()                          // simulate 2 s timer fire
        assertEquals(OverlayState.WaitingForBattle, emitted.last())
    }

    @Test
    fun `tick during Cooldown does NOT call snapshot or re-emit`() {
        var snapshotCalls = 0
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { snapshotCalls++; 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> },
        )
        poller.start()                              // emit Waiting; snapshotCalls=0
        poller.tick()                               // snapshotCalls=1, emit Ready
        poller.enterCooldown()                      // emit Cooldown; snapshotCalls=1
        val callsAfterCooldown = snapshotCalls
        val emittedSize = emitted.size
        poller.tick()                               // MUST early-return; no snapshot, no emit
        poller.tick()                               // same
        assertEquals(callsAfterCooldown, snapshotCalls)
        assertEquals(emittedSize, emitted.size)
    }

    @Test
    fun `enterCooldown is idempotent — second call during cooldown is a no-op`() {
        val scheduled = mutableListOf<Long>()
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { d, _ -> scheduled += d },
        )
        poller.start()
        poller.tick()
        poller.enterCooldown()
        val sizeBeforeRedundantCall = emitted.size
        poller.enterCooldown()                      // should be ignored
        assertEquals(sizeBeforeRedundantCall, emitted.size)
        assertEquals(1, scheduled.size)             // only one timer scheduled
    }

    @Test
    fun `currentState reflects latest emission`() {
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { },
            scheduleAfter = { _, _ -> },
        )
        assertEquals(OverlayState.WaitingForBattle, poller.currentState())
        poller.start()
        assertEquals(OverlayState.WaitingForBattle, poller.currentState())
        poller.tick()
        assertEquals(OverlayState.Ready, poller.currentState())
        poller.enterCooldown()
        assertEquals(OverlayState.Cooldown, poller.currentState())
    }

    @Test
    fun `companion exposes pollIntervalMs constant`() {
        assertEquals(800L, OverlayPoller.POLL_INTERVAL_MS)
    }

    @Test
    fun `tick during pause does NOT call snapshot or re-emit`() {
        var snapshotCalls = 0
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { snapshotCalls++; 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> },
        )
        poller.start()                  // emit Waiting
        poller.tick()                   // emit Ready (snapshotCalls=1)
        poller.pause()
        val callsAfterPause = snapshotCalls
        val emittedAfterPause = emitted.size
        poller.tick()
        poller.tick()
        assertEquals(callsAfterPause, snapshotCalls)
        assertEquals(emittedAfterPause, emitted.size)
    }

    @Test
    fun `resume after pause allows tick to run again`() {
        var count = 0
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { count },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> },
        )
        poller.start()
        count = 1
        poller.tick()                   // → Ready
        poller.pause()                  // pause now drops to Waiting (no lying-green button)
        assertEquals(OverlayState.WaitingForBattle, poller.currentState())
        count = 0
        poller.tick()                   // suppressed (still paused)
        assertEquals(OverlayState.WaitingForBattle, poller.currentState())
        poller.resume()
        count = 1
        poller.tick()                   // now runs; count=1 → re-arms to Ready
        assertEquals(OverlayState.Ready, poller.currentState())
    }
}
