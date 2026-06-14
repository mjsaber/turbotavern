package com.turbotavern.trinket

import android.os.Handler
import com.turbotavern.herotier.BadgeRenderer
import com.turbotavern.herotier.Foreground
import com.turbotavern.herotier.HeroBadge
import com.turbotavern.herotier.HeroMatcher
import com.turbotavern.herotier.HeroOcr
import com.turbotavern.herotier.OrientedOcr
import com.turbotavern.herotier.ScreenGrabber

/**
 * Unified hero+trinket select-overlay runtime. Captures and OCRs ONCE per round (OCR is the ~1.3s cost,
 * so two independent coordinators would double battery) then feeds the single OcrLine set to BOTH the
 * hero and trinket matchers. A [SelectWindowArbiter] mutually-excludes the two overlays — hero-select
 * and trinket-shop are distinct screens, so at most one is ever shown and neither cross-fires.
 *
 * Subsumes HeroTierCoordinator: same §8.2 visual-probe trigger, own [handler], adaptive cadence
 * ([captureIntervalMs] for the first [maxAttempts] open rounds then [probeMs]), [maxWindowMs] cap,
 * foreground gate before capture, stale-rotation guard, and projection-stop/stop closes. The arbiter
 * replaces the single gate; the rest of the machinery is identical.
 */
class SelectCoordinator(
    private val grabber: ScreenGrabber,
    private val ocr: HeroOcr,
    private val heroMatcher: HeroMatcher,
    private val heroRenderer: BadgeRenderer,
    private val trinketMatcher: TrinketMatcher,
    private val trinketRenderer: TrinketRenderer,
    private val foreground: () -> Foreground,
    private val currentRotation: () -> Int,
    private val handler: Handler,
    private val mainHandler: Handler,
    private val arbiter: SelectWindowArbiter = SelectWindowArbiter(),
    private val forceOpen: () -> Boolean = { false },   // debug: bypass the gate to show the overlay during tuning
    private val heroEnabled: () -> Boolean = { true },     // user setting (AppPrefs): show the hero overlay
    private val trinketEnabled: () -> Boolean = { true },  // user setting (AppPrefs): show the trinket overlay
    private val probeMs: Long = 2000,
    private val captureIntervalMs: Long = 700,
    private val maxAttempts: Int = 8,
    private val maxWindowMs: Long = 15_000,
    private val heroMaxMisses: Int = 2,                  // hold a hero badge across this many absent frames (anti-flicker)
    private val breadcrumb: (String) -> Unit = {},
) {
    @Volatile private var started = false
    @Volatile private var active: SelectWindow = SelectWindow.NONE
    private var attempts = 0
    private var wasForced = false
    private var loggedInert = false

    // Temporal anti-flicker for the hero overlay: per-frame OCR jitter drops a different hero on different
    // frames, and the renderer rebuilds every frame, so a single-frame miss blinks a badge. Render the
    // recently-seen union instead. Heroes only (fixed select set); NOT trinkets (the shop can change offers).
    private val heroStabilizer = BadgeStabilizer<HeroBadge>(keyOf = { it.cardId }, maxMisses = heroMaxMisses)

    // Orientation-robust OCR: handles a portrait capture buffer of the landscape game (auto-detects the
    // rotation that actually reads). Upright buffers (emulator/most phones) stay on rotation 0 unchanged.
    private val orientedOcr = OrientedOcr(ocr) { lines ->
        runCatching { heroMatcher.match(lines).size }.getOrElse { 0 } +
            runCatching { TrinketOffer.matchCount(trinketMatcher, lines) }.getOrElse { 0 }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            step()
            if (started) handler.postDelayed(this, nextIntervalMs())
        }
    }

    private val windowTimeout = Runnable {
        if (started && active != SelectWindow.NONE) { breadcrumb("select: window timeout"); closeAll() }
    }

    fun start() {
        if (started) return
        started = true
        breadcrumb("SelectCoordinator.start")
        handler.post(tick)
    }

    fun stop() {
        if (!started) return
        started = false
        breadcrumb("SelectCoordinator.stop")
        handler.removeCallbacksAndMessages(null)
        active = SelectWindow.NONE; attempts = 0; wasForced = false
        arbiter.forceClose()
        // stop() runs on the caller (main) thread, but heroStabilizer is thread-confined to the handler
        // thread (update() iterates its non-thread-safe map). Marshal the reset onto the handler so it
        // serializes AFTER any in-flight tick's update() instead of racing it (codex). closeAll()/
        // onTransition() already run on the handler, so their resets are fine.
        handler.post { heroStabilizer.reset() }
        mainHandler.post { runCatching { heroRenderer.clear(); trinketRenderer.clear() } }
    }

    fun onProjectionStopped() {
        handler.post { if (started) closeAll() }
    }

    private fun nextIntervalMs(): Long =
        if (active != SelectWindow.NONE && attempts < maxAttempts) captureIntervalMs else probeMs

    private fun step() {
        if (foreground() != Foreground.TRUE) {
            if (active != SelectWindow.NONE) { breadcrumb("select: foreground not TRUE -> close") }
            closeAll()
            return
        }
        if (!ocr.isAvailable()) {
            if (!loggedInert) { loggedInert = true; breadcrumb("select: OCR unavailable -> inert") }
            return
        }
        val frame = grabber.capture() ?: return
        val lines = runCatching { orientedOcr.recognize(frame) }
            .getOrElse { breadcrumb("select: ocr failed: ${it.message}"); emptyList() }
        val heroBadges = if (heroEnabled()) runCatching { heroMatcher.match(lines) }.getOrElse { emptyList() } else emptyList()
        val trinketRecs = if (trinketEnabled()) runCatching { TrinketOffer.resolve(trinketMatcher, lines) }.getOrElse { emptyList() } else emptyList()

        val prev = active
        val fo = forceOpen()
        val now = when {
            fo -> {                                     // debug force-open: bypass the gate, hero takes priority
                wasForced = true
                when {
                    heroBadges.isNotEmpty() -> SelectWindow.HERO
                    trinketRecs.isNotEmpty() -> SelectWindow.TRINKET
                    else -> SelectWindow.NONE
                }
            }
            wasForced -> {                              // falling edge of force-open: reset the gate + close
                wasForced = false
                arbiter.forceClose()
                SelectWindow.NONE
            }
            else -> arbiter.onProbe(heroMatches = heroBadges.size, trinketMatches = trinketRecs.size)
        }
        active = now
        if (com.turbotavern.BuildConfig.DEBUG) {
            breadcrumb("select: ocr=${lines.size} hero=${heroBadges.size} trinket=${trinketRecs.size} rot=${frame.rotationDeg} cap=${frame.captureW}x${frame.captureH} window=$now")
            // Diagnostic: when OCR found text but nothing matched, dump the recognized strings so an
            // on-device session reveals whether it's misoriented capture, wrong screen, or a matcher gap.
            if (lines.isNotEmpty() && heroBadges.isEmpty() && trinketRecs.isEmpty())
                breadcrumb("select: ocr-text=[${lines.joinToString(" | ") { it.text }}]")
        }
        onTransition(prev, now)

        val rotationDeg = frame.rotationDeg
        val transform = frame.transform
        runCatching { frame.bitmap.recycle() }
        if (now == SelectWindow.NONE) return
        if (attempts < maxAttempts) attempts++
        if (currentRotation() != rotationDeg) { breadcrumb("select: dropped stale-rotation frame"); return }

        // Render the window decided for THIS frame; re-check at render time that it's still the active
        // one (it may have closed / switched while this runnable sat on the main queue).
        val renderWindow = now
        // Stabilize the hero set AFTER the stale-rotation guard above, so a dropped rotated frame never
        // resets misses or replaces held boxes (a held box could otherwise later render against the wrong
        // transform). The union bridges transient per-frame OCR misses so a badge doesn't blink. The gate
        // (arbiter.onProbe) still saw the RAW count — only what we RENDER is stabilized.
        val heroToRender = if (renderWindow == SelectWindow.HERO) heroStabilizer.update(heroBadges) else heroBadges
        // Live proof of the anti-flicker hold: raw < rendered means a transient OCR miss was bridged this
        // frame (the badge would otherwise have blinked). Only logged on a hold, DEBUG-only.
        if (com.turbotavern.BuildConfig.DEBUG && renderWindow == SelectWindow.HERO && heroToRender.size > heroBadges.size)
            breadcrumb("select: hero hold raw=${heroBadges.size} -> rendered=${heroToRender.size}")
        mainHandler.post {
            if (!started || active != renderWindow || currentRotation() != rotationDeg) return@post
            when (renderWindow) {
                SelectWindow.HERO -> if (heroToRender.isNotEmpty()) runCatching { heroRenderer.render(heroToRender, transform) }
                SelectWindow.TRINKET -> if (trinketRecs.isNotEmpty()) runCatching { trinketRenderer.render(trinketRecs, transform) }
                SelectWindow.NONE -> {}
            }
        }
    }

    /** Open/close bookkeeping: (re)arm the timeout + reset cadence on a fresh open; clear the OTHER overlay. */
    private fun onTransition(prev: SelectWindow, now: SelectWindow) {
        if (prev == now) return
        // clear whichever overlay is no longer active
        when (prev) {
            SelectWindow.HERO -> { heroStabilizer.reset(); mainHandler.post { runCatching { heroRenderer.clear() } } }
            SelectWindow.TRINKET -> mainHandler.post { runCatching { trinketRenderer.clear() } }
            SelectWindow.NONE -> {}
        }
        handler.removeCallbacks(windowTimeout)
        if (now != SelectWindow.NONE) {
            attempts = 0
            breadcrumb("select: $now window open")
            handler.postDelayed(windowTimeout, maxWindowMs)
        } else {
            breadcrumb("select: window close")
        }
    }

    private fun closeAll() {
        handler.removeCallbacks(windowTimeout)
        val had = active
        active = SelectWindow.NONE
        attempts = 0
        arbiter.forceClose()
        heroStabilizer.reset()      // forced closes (foreground-lost / timeout / projection-stop) bypass onTransition; don't leak held heroes into the next window (codex)
        if (had != SelectWindow.NONE) mainHandler.post { runCatching { heroRenderer.clear(); trinketRenderer.clear() } }
    }
}
