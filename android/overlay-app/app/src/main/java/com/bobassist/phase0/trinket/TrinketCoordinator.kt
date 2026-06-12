package com.bobassist.phase0.trinket

import android.os.Handler
import com.bobassist.phase0.herotier.Foreground
import com.bobassist.phase0.herotier.HeroOcr
import com.bobassist.phase0.herotier.ScreenGrabber
import com.bobassist.phase0.herotier.Transition
import com.bobassist.phase0.herotier.VisualProbeGate

/**
 * Owns the trinket-recommendation runtime, mirroring HeroTierCoordinator with its §8.2 visual-probe
 * trigger. Runs on its OWN [handler]. While Hearthstone is strictly foreground it captures -> OCR ->
 * trinket-match every round and feeds the [gate] on the trinket-dictionary match count; on open it
 * resolves the offer (class-inferred) and renders the ranked recommendation. Same adaptive cadence,
 * foreground/rotation/timeout/projection-stop closes, and single-tick threading as the hero coordinator.
 *
 * The OCR engine ([HeroOcr]) is shared — it returns generic OcrLines; only the matcher differs. The
 * host must keep at most one of {hero, trinket} window open at a time (see SelectWindowArbiter) so the
 * two overlays never both render; this class only owns the trinket side.
 */
class TrinketCoordinator(
    private val grabber: ScreenGrabber,
    private val ocr: HeroOcr,
    private val matcher: TrinketMatcher,
    private val renderer: TrinketRenderer,
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
    @Volatile private var open = false
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
        if (started && open) { breadcrumb("trinket: window timeout"); closeWindow(); gate.forceClose() }
    }

    fun start() {
        if (started) return
        started = true
        breadcrumb("TrinketCoordinator.start")
        handler.post(tick)
    }

    fun stop() {
        if (!started) return
        started = false
        breadcrumb("TrinketCoordinator.stop")
        handler.removeCallbacksAndMessages(null)
        open = false; attempts = 0; wasForced = false
        gate.forceClose()
        mainHandler.post { runCatching { renderer.clear() } }
    }

    fun onProjectionStopped() {
        handler.post { if (started) { closeWindow(); gate.forceClose() } }
    }

    private fun nextIntervalMs(): Long =
        if (open && attempts < maxAttempts) captureIntervalMs else probeMs

    private fun step() {
        val fo = forceOpen()
        if (foreground() != Foreground.TRUE) {
            if (open) { breadcrumb("trinket: foreground not TRUE -> close"); closeWindow() }
            gate.forceClose(); wasForced = false
            return
        }
        if (wasForced && !fo) {
            wasForced = false; closeWindow(); gate.forceClose()
            return
        }
        if (!ocr.isAvailable()) {
            if (!loggedInert) { loggedInert = true; breadcrumb("trinket: OCR unavailable -> inert") }
            return
        }
        val frame = grabber.capture() ?: return
        val ocrLines = runCatching { ocr.recognize(frame) }
            .getOrElse { breadcrumb("trinket: ocr failed: ${it.message}"); emptyList() }
        val recs = runCatching { TrinketOffer.resolve(matcher, ocrLines) }
            .getOrElse { breadcrumb("trinket: match failed: ${it.message}"); emptyList() }
        val count = recs.size
        if (com.bobassist.phase0.BuildConfig.DEBUG)
            breadcrumb("trinket: ocr=${ocrLines.size} matched=$count${recs.joinToString(prefix = "{", postfix = "}") { it.match.entry.cardId + if (it.isBest) "*" else "" }} open=$open")

        if (fo) {
            wasForced = true; if (!open) openWindow()
        } else when (gate.onProbe(count)) {
            Transition.Enter -> openWindow()
            Transition.Exit -> closeWindow()
            Transition.None -> {}
        }

        val rotationDeg = frame.rotationDeg
        val transform = frame.transform
        runCatching { frame.bitmap.recycle() }
        if (!open) return
        if (attempts < maxAttempts) attempts++
        if (count == 0) return
        if (currentRotation() != rotationDeg) {
            breadcrumb("trinket: dropped stale-rotation frame")
            return
        }
        mainHandler.post {
            if (started && open && currentRotation() == rotationDeg) {
                runCatching { renderer.render(recs, transform) }
            }
        }
    }

    private fun openWindow() {
        if (open) return
        open = true
        attempts = 0
        breadcrumb("trinket: window open")
        handler.postDelayed(windowTimeout, maxWindowMs)
    }

    private fun closeWindow() {
        handler.removeCallbacks(windowTimeout)
        if (open) breadcrumb("trinket: window close")
        open = false
        attempts = 0
        mainHandler.post { runCatching { renderer.clear() } }
    }
}
