package com.bobassist.phase0.herotier

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

private class FakeOcr(private val lines: List<OcrLine>, private val emptyFirst: Int = 0) : HeroOcr {
    var callCount = 0
    override fun recognize(frame: Frame): List<OcrLine> {
        val r = if (callCount < emptyFirst) emptyList() else lines
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
        """{"heroes":[{"cardId":"BG_HERO_001","tier":"S","names":{"enUS":"Sneed"}}]}""")
    private val sneedLine = listOf(OcrLine("Sneed", BoxPx(0, 0, 10, 10)))

    private var openSignal = false
    private var fg = Foreground.TRUE
    private var rotation = 0

    @Before fun setUp() {
        ht = HandlerThread("herotier-test").apply { start() }
        handler = Handler(ht.looper)
    }

    @After fun tearDown() { ht.quitSafely() }

    private fun coordinator(grabber: FakeGrabber, ocr: FakeOcr, renderer: FakeRenderer) =
        HeroTierCoordinator(
            connectionsJson = { "x" },
            trigger = SelectPhaseTrigger(isOpen = { openSignal }),
            grabber = grabber, ocr = ocr, matcher = HeroMatcher(table), renderer = renderer,
            foreground = { fg }, currentRotation = { rotation },
            handler = handler, mainHandler = mainHandler,
            pollMs = 100, captureIntervalMs = 50, maxAttempts = 3, maxWindowMs = 10_000,
        )

    private fun drain(ms: Long) {
        shadowOf(ht.looper).idleFor(ms, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun enterForegroundTrueShowsBadge() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(sneedLine), r).start()
        openSignal = true; fg = Foreground.TRUE
        drain(500)
        assertTrue("rendered at least once", r.renderCount >= 1)
        assertEquals("BG_HERO_001", r.lastBadges.single().cardId)
    }

    @Test fun retriesOnEmptyThenShows() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(sneedLine, emptyFirst = 2), r).start()
        openSignal = true
        drain(500)
        assertTrue("captured more than once", g.captureCount >= 2)
        assertTrue("eventually rendered", r.renderCount >= 1)
    }

    @Test fun exitClearsAndStopsCapturing() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(sneedLine), r).start()
        openSignal = true; drain(250)
        val capturesAtExit = g.captureCount
        openSignal = false; drain(400)
        assertTrue("clear called on exit", r.clearCount >= 1)
        assertEquals("no captures after exit", capturesAtExit, g.captureCount)
    }

    @Test fun foregroundUnknownNeverCaptures() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(sneedLine), r).start()
        openSignal = true; fg = Foreground.UNKNOWN
        drain(500)
        assertEquals(0, g.captureCount)
        assertEquals(0, r.renderCount)
    }

    @Test fun foregroundFalseNeverCaptures() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(sneedLine), r).start()
        openSignal = true; fg = Foreground.FALSE
        drain(500)
        assertEquals(0, g.captureCount)
        assertEquals(0, r.renderCount)
    }

    @Test fun foregroundLostMidWindowCloses() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(sneedLine), r).start()
        openSignal = true; fg = Foreground.TRUE; drain(120)
        val mid = g.captureCount
        fg = Foreground.FALSE; drain(300)
        assertTrue(r.clearCount >= 1)
        assertEquals("stopped capturing after fg lost", mid, g.captureCount)
    }

    @Test fun windowTimeoutCloses() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(emptyList()), r).start()       // never matches -> loop runs till timeout
        openSignal = true; drain(11_000)
        assertTrue("timeout cleared the window", r.clearCount >= 1)
    }

    @Test fun projectionStopCloses() {
        val g = FakeGrabber(); val r = FakeRenderer()
        val c = coordinator(g, FakeOcr(sneedLine), r); c.start()
        openSignal = true; drain(150)
        c.onProjectionStopped(); drain(50)
        assertTrue(r.clearCount >= 1)
    }

    @Test fun staleRotationFrameDropped() {
        val g = FakeGrabber(rotationDeg = 0); val r = FakeRenderer()
        rotation = 90                                          // display rotated vs frame
        coordinator(g, FakeOcr(sneedLine), r).start()
        openSignal = true; drain(500)
        assertTrue("captured", g.captureCount >= 1)
        assertEquals("stale-rotation frame not rendered", 0, r.renderCount)
    }

    @Test fun captureLoopBoundedByMaxAttempts() {
        val g = FakeGrabber(); val r = FakeRenderer()
        coordinator(g, FakeOcr(emptyList()), r).start()       // never matches -> loop runs to maxAttempts
        openSignal = true; drain(300)
        assertEquals("stops at maxAttempts", 3, g.captureCount)
        drain(1_000)                                          // still well before maxWindowMs(10s)
        assertEquals("stays bounded before timeout", 3, g.captureCount)
    }

    @Test fun rotationChangeBeforeRenderDropsBadges() {
        val g = FakeGrabber(rotationDeg = 0); val r = FakeRenderer()
        rotation = 0
        coordinator(g, FakeOcr(sneedLine), r).start()
        openSignal = true
        shadowOf(ht.looper).idleFor(150, TimeUnit.MILLISECONDS)   // run captures; render runnables queued on main
        rotation = 90                                             // rotate AFTER match, BEFORE main render runs
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("render-time guard drops rotated frame", 0, r.renderCount)
    }

    @Test fun stopRemovesCallbacks() {
        val g = FakeGrabber(); val r = FakeRenderer()
        val c = coordinator(g, FakeOcr(sneedLine), r); c.start()
        openSignal = true; drain(150)
        val n = g.captureCount
        c.stop(); drain(500)
        assertEquals("no captures after stop", n, g.captureCount)
        assertTrue("cleared on stop", r.clearCount >= 1)
    }
}
