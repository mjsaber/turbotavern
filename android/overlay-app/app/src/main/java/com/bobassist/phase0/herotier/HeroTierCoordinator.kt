package com.bobassist.phase0.herotier

import android.os.Handler

/**
 * Owns the hero-tier runtime (spec §4/§6/§9) with the §8.2 visual-probe trigger. Runs on its OWN
 * [handler] (a dedicated HandlerThread in production) so it never shares the kill-button's
 * exclusively-owned pollHandler.
 *
 * ONE always-on tick (spec §4.2): while Hearthstone is strictly foreground it captures → OCR →
 * match every round and feeds the [gate] (or, in DEBUG, [forceOpen] bypasses the gate). The window
 * OPENS on Enter and renders; it stays open and re-renders to track rerolls. Interval is adaptive:
 * [probeMs] while closed; [captureIntervalMs] for the first [maxAttempts] open rounds (snappy first
 * render as art settles) then back to [probeMs] (cheap hold). The gate's CLOSE_K-zeros, foreground
 * loss, [maxWindowMs] and projection-stop each close the window — a single tick means there is no
 * second loop to race and the foreground guard + gate feed stay reachable on a held window.
 *
 * Threading: every state mutation is confined to [handler]; render calls post to [mainHandler].
 * [stop] clears [handler]; a `started` guard makes any in-flight runnable a no-op.
 */
class HeroTierCoordinator(
    private val grabber: ScreenGrabber,
    private val ocr: HeroOcr,
    private val matcher: HeroMatcher,
    private val renderer: BadgeRenderer,
    private val foreground: () -> Foreground,
    private val currentRotation: () -> Int,
    private val handler: Handler,
    private val mainHandler: Handler,
    private val gate: VisualProbeGate = VisualProbeGate(),
    private val forceOpen: () -> Boolean = { false },
    private val probeMs: Long = 2000,
    private val captureIntervalMs: Long = 700,
    private val maxAttempts: Int = 8,
    private val maxWindowMs: Long = 15_000,
    private val breadcrumb: (String) -> Unit = {},
) {
    @Volatile private var started = false
    @Volatile private var open = false     // @Volatile so the main-thread render runnable can re-check it
    private var attempts = 0
    private var wasForced = false
    private var loggedInert = false

    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            step()
            if (started) handler.postDelayed(this, nextIntervalMs())
        }
    }

    private val windowTimeout = Runnable {
        if (started && open) { breadcrumb("herotier: window timeout"); closeWindow(); gate.forceClose() }
    }

    fun start() {
        if (started) return
        started = true
        breadcrumb("HeroTierCoordinator.start")
        handler.post(tick)
    }

    fun stop() {
        if (!started) return
        started = false
        breadcrumb("HeroTierCoordinator.stop")
        handler.removeCallbacksAndMessages(null)
        open = false; attempts = 0; wasForced = false
        gate.forceClose()
        mainHandler.post { runCatching { renderer.clear() } }
    }

    /** MediaProjection.Callback.onStop -> tear down the current window on our handler. */
    fun onProjectionStopped() {
        handler.post { if (started) { closeWindow(); gate.forceClose() } }
    }

    private fun nextIntervalMs(): Long =
        if (open && attempts < maxAttempts) captureIntervalMs else probeMs

    private fun step() {
        val fo = forceOpen()
        // Strict foreground gate FIRST — also guards the held/open window (subsumes the old poll loop).
        if (foreground() != Foreground.TRUE) {
            if (open) { breadcrumb("herotier: foreground not TRUE -> close"); closeWindow() }
            gate.forceClose(); wasForced = false
            return
        }
        if (!ocr.isAvailable()) {                       // inert: never capture (spec §8.2)
            if (!loggedInert) { loggedInert = true; breadcrumb("herotier: OCR unavailable -> inert") }
            return
        }
        val frame = grabber.capture() ?: return
        val ocrLines = runCatching { ocr.recognize(frame) }
            .getOrElse { breadcrumb("herotier: ocr failed: ${it.message}"); emptyList() }
        val badges = runCatching { matcher.match(ocrLines) }
            .getOrElse { breadcrumb("herotier: match failed: ${it.message}"); emptyList() }
        val count = badges.size
        if (com.bobassist.phase0.BuildConfig.DEBUG)
            breadcrumb("herotier: ocr=${ocrLines.size} lines [${ocrLines.take(6).joinToString("|") { it.text }}] matched=$count open=$open")

        // Open/close decision — force-open (DEBUG) bypasses the gate (spec §4.5).
        when {
            wasForced && !fo -> { wasForced = false; closeWindow(); gate.forceClose() }   // falling edge
            fo -> { wasForced = true; if (!open) openWindow() }                            // forced: skip gate
            else -> when (gate.onProbe(count)) {
                Transition.Enter -> openWindow()
                Transition.Exit -> closeWindow()
                Transition.None -> {}
            }
        }

        val rotationDeg = frame.rotationDeg
        val transform = frame.transform
        runCatching { frame.bitmap.recycle() }          // free the capture bitmap; render needs only transform
        if (!open) return
        if (attempts < maxAttempts) attempts++          // drives the cadence drop after the snappy phase
        if (count == 0) return
        if (currentRotation() != rotationDeg) {         // stale-rotation guard (pre-post)
            breadcrumb("herotier: dropped stale-rotation frame")
            return
        }
        // Re-check at render time: the window may have closed or the display rotated while this
        // render runnable sat on the (possibly delayed) main queue.
        mainHandler.post {
            if (started && open && currentRotation() == rotationDeg) {
                runCatching { renderer.render(badges, transform) }
            }
        }
    }

    private fun openWindow() {
        if (open) return
        open = true
        attempts = 0
        breadcrumb("herotier: window open")
        handler.postDelayed(windowTimeout, maxWindowMs)
    }

    private fun closeWindow() {
        handler.removeCallbacks(windowTimeout)
        if (open) breadcrumb("herotier: window close")
        open = false
        attempts = 0
        mainHandler.post { runCatching { renderer.clear() } }
    }
}
