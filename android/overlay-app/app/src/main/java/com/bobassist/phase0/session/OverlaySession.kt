package com.bobassist.phase0.session

import android.os.Handler
import android.util.Log
import com.bobassist.phase0.core.BattleCandidateCache
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.foreground.ForegroundDetector
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.overlay.OverlayState
import com.bobassist.phase0.overlay.OverlayUi
import com.bobassist.phase0.util.Clock
import com.bobassist.phase0.util.TraceSink

/**
 * Coordinates the runtime collaboration between OverlayPoller, ForegroundDetector,
 * OverlayUi, and BattleConnectionController. Single-thread-confined to [pollHandler]
 * for state-machine mutations; main-thread for UI updates.
 *
 * Lifecycle:
 *   start() must be called once after construction.
 *   stop() must be called when the host service tears down; after stop(), all
 *   subsequent posted runnables are no-ops (liveness guard).
 *
 * Note (codex round-3 P3 #30): if stop() runs before a posted tap lambda
 * executes, removeCallbacksAndMessages(null) removes the lambda entirely —
 * so the cycle's `session_stopped` exit line is NOT guaranteed to appear in
 * the trace. The cycle's tap entry line ALWAYS appears (it logs before
 * post). This is intentional.
 *
 * No Android-Service dependencies — instances can be exercised in Robolectric.
 *
 * **pollHandler contract (codex round-3 P2 #27)**: pollHandler MUST be
 * exclusively owned by this OverlaySession instance. No other subsystem may
 * post to it. OverlaySession.stop() calls removeCallbacksAndMessages(null)
 * which would silently cancel any non-session runnables.
 */
class OverlaySession(
    val controller: BattleConnectionController,
    private val candidateCache: BattleCandidateCache,
    val poller: OverlayPoller,
    val detector: ForegroundDetector,
    private val overlay: OverlayUi,
    private val pollHandler: Handler,
    private val mainHandler: Handler,
    private val clock: Clock,
    private val trace: TraceSink,
    private val hasUsageAccessPermission: () -> Boolean,
    private val breadcrumb: (String) -> Unit = { },
) {
    @Volatile private var started: Boolean = false

    private var pollTick: Runnable? = null
    private var detectorTick: Runnable? = null

    fun start() {
        if (started) return
        started = true
        breadcrumb("OverlaySession.start")
        // overlay.show() happens via foreground detector callback (initial isTargetForeground=true → fires show)
        // OR via initial state push; for now, mirror Phase 1.2: show eagerly + let detector hide if needed.
        mainHandler.post { runCatching { overlay.show() } }

        val pTick = object : Runnable {
            override fun run() {
                if (!started) return        // codex P1 #3: session liveness guard
                poller.tick()
                pollHandler.postDelayed(this, OverlayPoller.POLL_INTERVAL_MS)
            }
        }
        val dTick = object : Runnable {
            override fun run() {
                if (!started) return        // codex P1 #3
                if (hasUsageAccessPermission()) detector.tick() else detector.reset()
                pollHandler.postDelayed(this, ForegroundDetector.POLL_INTERVAL_MS)
            }
        }
        pollTick = pTick
        detectorTick = dTick
        pollHandler.post {
            if (!started) return@post          // codex round-2 P1 #17: guard initial start-post
            poller.start()
            pollHandler.postDelayed(pTick, OverlayPoller.POLL_INTERVAL_MS)
            pollHandler.postDelayed(dTick, ForegroundDetector.POLL_INTERVAL_MS)
        }
    }

    fun stop() {
        if (!started) return
        started = false       // P1 #3 — any in-flight Runnable's first line `if (!started) return` will now exit
        breadcrumb("OverlaySession.stop")
        // codex round-2 P1 #17: nuke ALL queued runnables on pollHandler — not just
        // pollTick/detectorTick but also OverlayPoller's scheduled cooldown exit
        // callback and any handleTap/forceTickNow posts.
        pollHandler.removeCallbacksAndMessages(null)
        pollTick = null
        detectorTick = null
        mainHandler.post { runCatching { overlay.hide() } }
    }

    /**
     * User tapped the overlay. Confined to pollHandler so all state reads/writes
     * happen on a single thread. Enters Cooldown ONLY on Success.
     */
    fun handleTap() {
        val cycle = trace.beginCycle()
        cycle.emit("tap", "entry", "state" to poller.currentState())
        val tapEntryNs = clock.nowNanos()
        // Phase 1.4: kill the CACHED candidate directly — no connectionsJson() on this
        // path. We deliberately use plain post() (NOT postAtFrontOfQueue): front-posting
        // could reorder a tap ahead of a queued foreground pause/cache-clear, letting it
        // close a stale socket after HS already left foreground (codex gate-3 P2). It also
        // cannot preempt an in-flight snapshot anyway, so the residual in-flight wait (S#1)
        // is unchanged — that is P2's concern, not the tap path's.
        val tapRunnable = Runnable {
            cycle.emit("tap_post", "entry", "delay_ms" to (clock.nowNanos() - tapEntryNs) / 1_000_000L)
            if (!started) {
                cycle.emit("tap_post", "exit", "result" to "session_stopped")
                return@Runnable
            }
            val state = poller.currentState()
            val readiness = candidateCache.current()
            cycle.emit(
                "state_check", "exit",
                "state" to state,
                "cache_age_ms" to (clock.nowMillis() - readiness.capturedAtMs),
            )
            when (state) {
                OverlayState.Ready -> {
                    val cand = readiness.candidate
                    if (cand == null) {
                        // INV-2 tolerance: Ready should imply a cached candidate, but if
                        // not, treat as no-op rather than crashing.
                        cycle.emit("tap_post", "exit", "result" to "no_candidate_cache_miss")
                        breadcrumb("overlay tap ignored (ready but cache miss)")
                        return@Runnable
                    }
                    val result = runCatching { controller.killCachedCandidate(cand, readiness.count, cycle) }
                        .getOrElse {
                            breadcrumb("overlay tap kill threw: ${it.message}")
                            cycle.emit("kill", "exit", "result" to "exception", "msg" to it.message)
                            return@Runnable
                        }
                    cycle.emit("tap_post", "exit", "result" to result::class.simpleName)
                    breadcrumb("overlay tap result=$result")
                    if (result is BattleConnectionController.KillResult.Success) {
                        Log.i(TAG, "overlay kill success: id=${result.closedId}")
                        poller.enterCooldown()
                    } else {
                        Log.i(TAG, "overlay kill non-success: $result")
                    }
                }
                OverlayState.WaitingForBattle -> {
                    cycle.emit("tap_post", "exit", "result" to "no_candidate")
                    breadcrumb("overlay tap ignored (no candidate)")
                }
                OverlayState.Cooldown -> {
                    cycle.emit("tap_post", "exit", "result" to "cooldown")
                    breadcrumb("overlay tap ignored (cooldown)")
                }
            }
        }
        pollHandler.post(tapRunnable)
    }

    /**
     * Direct kill (bypasses state machine) — for spike-e regression compatibility.
     * Do NOT use this from production code paths.
     */
    fun killBattleSocketDirect(): BattleConnectionController.KillResult {
        return controller.killBattleSocket()
    }

    /** Debug: trigger an immediate poller.tick() on pollHandler. */
    fun forceTickNow() {
        pollHandler.post {
            if (!started) return@post
            poller.tick()
        }
    }

    fun handleForegroundChange(isHsForeground: Boolean) {
        val cycle = trace.beginCycle()
        cycle.emit("fg_change", "entry", "is_fg" to isHsForeground)
        breadcrumb("foreground change: HS=$isHsForeground")
        pollHandler.post {
            if (!started) return@post
            if (isHsForeground) {
                poller.resume()
            } else {
                poller.pause()
                // codex gate-3 P2: while paused the cache can't refresh, so it would go
                // stale. Clear it so a tap in the ≤1 poll window after returning to HS
                // (state still Ready, no refresh yet) is a safe no-op instead of closing
                // a stale socket and falsely entering cooldown.
                candidateCache.clear()
            }
        }
        val capturedOverlay = overlay
        mainHandler.post {
            if (!started) return@post
            cycle.emit("setVisible", "entry", "visible" to isHsForeground)
            runCatching { capturedOverlay.setVisible(isHsForeground) }
            cycle.emit("setVisible", "exit")
        }
    }

    fun handleConfigurationChanged() {
        if (!started) return
        mainHandler.post {
            if (!started) return@post
            runCatching { overlay.onConfigurationChanged() }
        }
    }

    companion object {
        private const val TAG = "OverlaySession"
    }
}
