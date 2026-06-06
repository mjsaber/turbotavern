package com.bobassist.phase0.herotier

import android.os.Handler

/**
 * Owns the hero-tier runtime (spec §4/§6/§9). Runs on its OWN [handler] (a dedicated
 * HandlerThread in production) so it never shares the kill-button's exclusively-owned pollHandler.
 *
 * Loop: poll [connectionsJson] -> [trigger]. On Enter, run a bounded capture loop
 * (capture -> ocr -> match -> render) while Hearthstone is strictly foreground; hold the badges
 * until the window closes (trigger Exit / foreground lost / timeout / projection stop), then clear.
 *
 * Threading: every state mutation is confined to [handler]; render calls post to [mainHandler].
 * [stop] clears [handler]; a `started` guard makes any in-flight runnable a no-op.
 */
class HeroTierCoordinator(
    private val connectionsJson: () -> String,
    private val trigger: SelectPhaseTrigger,
    private val grabber: ScreenGrabber,
    private val ocr: HeroOcr,
    private val matcher: HeroMatcher,
    private val renderer: BadgeRenderer,
    private val foreground: () -> Foreground,
    private val currentRotation: () -> Int,
    private val handler: Handler,
    private val mainHandler: Handler,
    private val pollMs: Long = 800,
    private val captureIntervalMs: Long = 700,
    private val maxAttempts: Int = 8,
    private val maxWindowMs: Long = 15_000,
    private val breadcrumb: (String) -> Unit = {},
) {
    @Volatile private var started = false
    @Volatile private var open = false     // @Volatile so the main-thread render runnable can re-check it
    private var attempts = 0

    private val pollTick = object : Runnable {
        override fun run() {
            if (!started) return
            poll()
            handler.postDelayed(this, pollMs)
        }
    }

    private val captureTick = object : Runnable {
        override fun run() {
            if (!started || !open) return
            if (foreground() != Foreground.TRUE) {        // strict gate (UNKNOWN/FALSE -> no capture)
                breadcrumb("herotier: foreground not TRUE -> close")
                closeWindow()
                return
            }
            attempts++
            captureOnce()
            if (open && attempts < maxAttempts) handler.postDelayed(this, captureIntervalMs)
        }
    }

    private val windowTimeout = Runnable {
        if (started && open) { breadcrumb("herotier: window timeout"); closeWindow() }
    }

    fun start() {
        if (started) return
        started = true
        breadcrumb("HeroTierCoordinator.start")
        handler.post(pollTick)
    }

    fun stop() {
        if (!started) return
        started = false
        breadcrumb("HeroTierCoordinator.stop")
        handler.removeCallbacksAndMessages(null)
        open = false
        mainHandler.post { runCatching { renderer.clear() } }
    }

    /** MediaProjection.Callback.onStop -> tear down the current window on our handler. */
    fun onProjectionStopped() {
        handler.post { if (started) closeWindow() }
    }

    private fun poll() {
        val json = runCatching { connectionsJson() }.getOrElse { "" }
        when (trigger.update(json)) {
            Transition.Enter -> openWindow()
            Transition.Exit -> closeWindow()
            Transition.None -> {}
        }
        // Foreground-lost guard while the window holds badges: the capture loop stops after
        // maxAttempts, so this continuous poll is what notices HS leaving the foreground and
        // clears the badges (also covers usage-access being revoked -> UNKNOWN).
        if (open && foreground() != Foreground.TRUE) {
            breadcrumb("herotier: foreground not TRUE during open window -> close")
            closeWindow()
        }
    }

    private fun openWindow() {
        if (open) return
        open = true
        attempts = 0
        breadcrumb("herotier: window open")
        handler.post(captureTick)
        handler.postDelayed(windowTimeout, maxWindowMs)
    }

    private fun closeWindow() {
        handler.removeCallbacks(captureTick)
        handler.removeCallbacks(windowTimeout)
        if (open) breadcrumb("herotier: window close")
        open = false
        mainHandler.post { runCatching { renderer.clear() } }
    }

    private fun captureOnce() {
        val frame = grabber.capture() ?: return
        // Isolate a bad OCR/match round so one exception never kills the handler loop.
        val ocrLines = runCatching { ocr.recognize(frame) }
            .getOrElse { breadcrumb("herotier: ocr failed: ${it.message}"); emptyList() }
        val badges = runCatching { matcher.match(ocrLines) }
            .getOrElse { breadcrumb("herotier: match failed: ${it.message}"); emptyList() }
        if (com.bobassist.phase0.BuildConfig.DEBUG)
            breadcrumb("herotier: ocr=${ocrLines.size} lines [${ocrLines.take(6).joinToString("|") { it.text }}] matched=${badges.size}")
        runCatching { frame.bitmap.recycle() }            // free the capture bitmap; render needs only transform
        if (badges.isEmpty()) return
        if (currentRotation() != frame.rotationDeg) {     // stale-rotation guard (pre-post)
            breadcrumb("herotier: dropped stale-rotation frame")
            return
        }
        // Re-check at render time: the window may have closed or the display rotated while this
        // render runnable sat on the (possibly delayed) main queue.
        mainHandler.post {
            if (started && open && currentRotation() == frame.rotationDeg) {
                runCatching { renderer.render(badges, frame.transform) }
            }
        }
    }
}
