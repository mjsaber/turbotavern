package com.turbotavern.herotier

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

private class FakeGrabber(private val rotationDeg: Int = 0) : ScreenGrabber {
    var captureCount = 0
    override fun capture(): Frame? {
        captureCount++
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return Frame(bmp, 1, 1, Transform(1f, 1f, 0, 0), rotationDeg)
    }
}

/** Scripted OCR: entry i for recognize call i; after the script ends, the last entry repeats. */
private class FakeOcr(private val script: List<List<OcrLine>>, private val available: Boolean = true) : HeroOcr {
    var callCount = 0
    override fun isAvailable() = available
    override fun recognize(frame: Frame): List<OcrLine> {
        val r = script.getOrElse(callCount) { script.lastOrNull() ?: emptyList() }
        callCount++
        return r
    }
}

private class FakeRenderer : BadgeRenderer {
    var renderCount = 0
    var clearCount = 0
    var lastBadges: List<HeroBadge> = emptyList()
    override fun render(badges: List<HeroBadge>, transform: Transform) { renderCount++; lastBadges = badges }
    override fun clear() { clearCount++ }
}

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class HeroTierCoordinatorTest {
    private lateinit var ht: HandlerThread
    private lateinit var handler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val table = TierTable.fromJson(
        """{"heroes":[
            {"cardId":"BG_HERO_001","tier":"S","names":{"enUS":"Sneed"}},
            {"cardId":"BG_HERO_002","tier":"A","names":{"enUS":"Rafaam"}}]}""")

    private val two = listOf(OcrLine("Sneed", BoxPx(0, 0, 10, 10)), OcrLine("Rafaam", BoxPx(0, 20, 10, 30)))
    private val one = listOf(OcrLine("Sneed", BoxPx(0, 0, 10, 10)))
    private val zero = emptyList<OcrLine>()

    private var fg = Foreground.TRUE
    private var rotation = 0
    private var forceOpenFlag = false

    @Before fun setUp() {
        ht = HandlerThread("herotier-test").apply { start() }
        handler = Handler(ht.looper)
    }

    @After fun tearDown() { ht.quitSafely() }

    private fun coordinator(grabber: FakeGrabber, ocr: FakeOcr, renderer: FakeRenderer) =
        HeroTierCoordinator(
            grabber = grabber, ocr = ocr, matcher = HeroMatcher(table), renderer = renderer,
            foreground = { fg }, currentRotation = { rotation },
            handler = handler, mainHandler = mainHandler,
            gate = VisualProbeGate(openMatches = 2, closeK = 3),
            forceOpen = { forceOpenFlag },
            probeMs = 100, captureIntervalMs = 50, maxAttempts = 3, maxWindowMs = 10_000,
        )

    private fun drain(ms: Long) {
        shadowOf(ht.looper).idleFor(ms, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    // --- opening on the match-count trigger ---

    @Test fun opensAndRendersOnTwoMatchRound() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(two)), r).start()
        drain(60)
        assertTrue("rendered at least once", r.renderCount >= 1)
        assertEquals(setOf("BG_HERO_001", "BG_HERO_002"), r.lastBadges.map { it.cardId }.toSet())
    }

    @Test fun staysClosedUntilTwoMatchesThenOpens() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(zero, zero, two)), r).start()
        drain(50)                                           // t0 closed probe only (next at probeMs=100)
        assertEquals("one closed probe", 1, g.captureCount)
        assertEquals("not opened yet", 0, r.renderCount)
        drain(250)                                          // t100, t200(open+render), t250(fast)
        assertTrue("opened after the >=2 round", r.renderCount >= 1)
    }

    // --- B1: held window closes on CLOSE_K zeros AFTER maxAttempts ---

    @Test fun heldWindowClosesOnCloseKZerosPastMaxAttempts() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(two, two, two, two, two, two, zero, zero, zero, zero)), r).start()
        drain(450)                                          // open + held well past maxAttempts(3)
        assertEquals("held open through maxAttempts", 0, r.clearCount)
        drain(800)                                          // the 3 zeros accrue -> CLOSE_K -> Exit
        assertTrue("CLOSE_K zeros closed the held window", r.clearCount >= 1)
    }

    // --- B2 of spec: foreground-lost closes a held window ---

    @Test fun foregroundLostClosesHeldWindow() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(two)), r).start()     // steady 2 -> opens, holds
        fg = Foreground.TRUE; drain(450)
        val mid = g.captureCount
        fg = Foreground.FALSE; drain(400)
        assertTrue("cleared on fg loss", r.clearCount >= 1)
        assertEquals("no captures after fg lost", mid, g.captureCount)
    }

    // --- B3: MAX_WINDOW closes a window held open by a steady 1-match (gate never closes it) ---

    @Test fun maxWindowClosesWindowHeldBySteadyOneMatch() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(two, one)), r).start()   // open on 2, then steady 1 -> gate stays None
        drain(5_000)
        assertEquals("steady 1-match does not close via CLOSE_K", 0, r.clearCount)
        drain(6_000)                                            // past maxWindowMs(10s)
        assertTrue("MAX_WINDOW closed it", r.clearCount >= 1)
    }

    // --- B4: forceOpen bypasses the gate, closes on the falling edge ---

    @Test fun forceOpenOpensBypassingGateAndClosesOnFallingEdge() {
        val g = FakeGrabber(); val r = FakeRenderer()
        forceOpenFlag = true
        coordinator(g, FakeOcr(listOf(one, one, one, zero, zero, zero, zero)), r).start()
        drain(900)                                          // count<2 never opens the gate; force does, and zeros don't close it
        assertTrue("forced open rendered the 1-match rounds", r.renderCount >= 1)
        assertEquals("zeros do NOT close while forced", 0, r.clearCount)
        forceOpenFlag = false; drain(200)
        assertTrue("falling edge closed the window", r.clearCount >= 1)
    }

    // --- OCR unavailable -> inert (never captures) ---

    @Test fun ocrUnavailableNeverCaptures() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(two), available = false), r).start()
        drain(500)
        assertEquals(0, g.captureCount)
        assertEquals(0, r.renderCount)
    }

    // --- foreground gate precedes capture ---

    @Test fun foregroundUnknownNeverCaptures() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(two)), r).start()
        fg = Foreground.UNKNOWN; drain(500)
        assertEquals(0, g.captureCount)
        assertEquals(0, r.renderCount)
    }

    @Test fun foregroundFalseNeverCaptures() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(two)), r).start()
        fg = Foreground.FALSE; drain(500)
        assertEquals(0, g.captureCount)
        assertEquals(0, r.renderCount)
    }

    // --- rotation guards (ported) ---

    @Test fun staleRotationFrameDropped() {
        val g = FakeGrabber(rotationDeg = 0); val r = FakeRenderer()
        rotation = 90                                       // display rotated vs frame
        coordinator(g, FakeOcr(listOf(two)), r).start()
        drain(200)
        assertTrue("captured", g.captureCount >= 1)
        assertEquals("stale-rotation frame not rendered", 0, r.renderCount)
    }

    @Test fun rotationChangeBeforeRenderDropsBadges() {
        val g = FakeGrabber(rotationDeg = 0); val r = FakeRenderer()
        rotation = 0
        coordinator(g, FakeOcr(listOf(two)), r).start()
        shadowOf(ht.looper).idleFor(60, TimeUnit.MILLISECONDS)   // capture+match; render runnable queued on main
        rotation = 90                                            // rotate AFTER match, BEFORE main render runs
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("render-time guard drops rotated frame", 0, r.renderCount)
    }

    // --- lifecycle (ported) ---

    @Test fun projectionStopCloses() {
        val g = FakeGrabber(); val r = FakeRenderer()
        val c = coordinator(g, FakeOcr(listOf(two)), r); c.start()
        drain(120)
        c.onProjectionStopped(); drain(50)
        assertTrue(r.clearCount >= 1)
    }

    @Test fun stopRemovesCallbacks() {
        val g = FakeGrabber(); val r = FakeRenderer()
        val c = coordinator(g, FakeOcr(listOf(two)), r); c.start()
        drain(120)
        val n = g.captureCount
        c.stop(); drain(500)
        assertEquals("no captures after stop", n, g.captureCount)
        assertTrue("cleared on stop", r.clearCount >= 1)
    }

    // --- cadence: fast for maxAttempts rounds, then drops to probeMs ---

    @Test fun openCadenceDropsToProbeMsAfterMaxAttempts() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(listOf(two)), r).start()
        // t0 open(cap1,att1), t50(cap2,att2), t100(cap3,att3 -> drop to probeMs); next cap at t200.
        drain(130)                                          // clock -> 130
        assertEquals("3 fast rounds (50ms) by t100", 3, g.captureCount)
        drain(60)                                           // clock -> 190, still < t200 (a 50ms cadence would have fired t150)
        assertEquals("dropped to probeMs: no 4th before t200", 3, g.captureCount)
        drain(20)                                           // clock -> 210, crosses t200
        assertEquals("one slow round at probeMs", 4, g.captureCount)
    }
}
