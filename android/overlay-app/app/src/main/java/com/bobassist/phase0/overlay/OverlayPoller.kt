package com.bobassist.phase0.overlay

/**
 * Drives [OverlayState] transitions from poll snapshots + explicit
 * enterCooldown commands from the host service.
 *
 * Pure logic; no Handler/Looper inside. Caller wires:
 *   - `snapshot`     : poll source returning candidate count
 *   - `onStateChange`: invoked when the state CHANGES (suppresses no-op repeats)
 *   - `scheduleAfter`: schedule the Cooldown-expiry callback (postDelayed in prod)
 *
 * Thread model: this class is NOT internally synchronized. The host MUST
 * confine all calls (`start`, `tick`, `enterCooldown`, the scheduleAfter
 * callback) to a single thread — see BobVpnService where everything posts
 * to `pollHandler`. The only thread-crossing read is `currentState()`,
 * backed by @Volatile for cheap consumer-side visibility.
 */
class OverlayPoller(
    private val snapshot: () -> Int,
    private val onStateChange: (OverlayState) -> Unit,
    private val scheduleAfter: (delayMs: Long, callback: () -> Unit) -> Unit,
    private val clock: com.bobassist.phase0.util.Clock = com.bobassist.phase0.util.AndroidElapsedRealtimeClock,
    private val trace: com.bobassist.phase0.util.TraceSink? = null,
) {

    @Volatile
    private var state: OverlayState = OverlayState.WaitingForBattle
    private var started = false
    private var paused: Boolean = false

    fun start() {
        if (started) return
        started = true
        onStateChange(state)
    }

    /**
     * Periodic tick. Early-returns during Cooldown — snapshot is NOT called,
     * avoiding both wasted JNI work and the post-kill connection-table race.
     */
    fun tick() {
        if (!started) return
        if (paused) return
        if (state == OverlayState.Cooldown) return
        val cycle = trace?.beginCycle()
        cycle?.emit("poll_tick", "entry")
        emit(state.onPoll(snapshot()))
        cycle?.emit("poll_tick", "exit", "state" to state)
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /**
     * Host calls this AFTER a successful kill. Idempotent: calling while
     * already in Cooldown is a no-op (does NOT reschedule the timer).
     */
    fun enterCooldown() {
        if (!started) return
        if (state == OverlayState.Cooldown) return
        val cycle = trace?.beginCycle()
        cycle?.emit("cooldown_enter", "entry")
        emit(OverlayState.Cooldown)
        scheduleAfter(OverlayState.COOLDOWN_MS) { exitCooldown() }
    }

    private fun exitCooldown() {
        if (state != OverlayState.Cooldown) return  // stale callback after teardown/restart
        val cycle = trace?.beginCycle()
        cycle?.emit("cooldown_exit", "entry")
        emit(OverlayState.WaitingForBattle)
    }

    /** Snapshot of the latest emitted state. Safe to read from any thread. */
    fun currentState(): OverlayState = state

    private fun emit(next: OverlayState) {
        if (next == state) return
        state = next
        onStateChange(next)
    }

    companion object {
        const val POLL_INTERVAL_MS = 800L
    }
}
