package com.bobassist.phase0.session

import android.os.Handler
import android.util.Log
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.foreground.ForegroundDetector
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.overlay.OverlayState
import com.bobassist.phase0.overlay.OverlayUi
import com.bobassist.phase0.util.Clock

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
    val poller: OverlayPoller,
    val detector: ForegroundDetector,
    private val overlay: OverlayUi,
    private val pollHandler: Handler,
    private val mainHandler: Handler,
    private val clock: Clock,
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
        pollHandler.post {
            if (!started) return@post
            when (poller.currentState()) {
                OverlayState.Ready -> {
                    val result = runCatching { controller.killBattleSocket() }
                        .getOrElse {
                            breadcrumb("overlay tap kill threw: ${it.message}")
                            return@post
                        }
                    breadcrumb("overlay tap result=$result")
                    if (result is BattleConnectionController.KillResult.Success) {
                        Log.i(TAG, "overlay kill success: id=${result.closedId}")
                        poller.enterCooldown()
                    } else {
                        Log.i(TAG, "overlay kill non-success: $result")
                    }
                }
                OverlayState.WaitingForBattle -> breadcrumb("overlay tap ignored (no candidate)")
                OverlayState.Cooldown -> breadcrumb("overlay tap ignored (cooldown)")
            }
        }
    }

    /**
     * Direct kill (bypasses state machine) — for spike-e regression compatibility.
     * Do NOT use this from production code paths.
     */
    fun killBattleSocketDirect(): BattleConnectionController.KillResult {
        return controller.killBattleSocket()
    }

    fun handleForegroundChange(isHsForeground: Boolean) {
        breadcrumb("foreground change: HS=$isHsForeground")
        pollHandler.post {
            if (!started) return@post
            if (isHsForeground) poller.resume() else poller.pause()
        }
        val capturedOverlay = overlay
        mainHandler.post {
            if (!started) return@post
            runCatching { capturedOverlay.setVisible(isHsForeground) }
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
