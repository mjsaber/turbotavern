package com.turbotavern.trinket

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.turbotavern.herotier.BoxPx
import com.turbotavern.herotier.Foreground
import com.turbotavern.herotier.Frame
import com.turbotavern.herotier.HeroOcr
import com.turbotavern.herotier.OcrLine
import com.turbotavern.herotier.ScreenGrabber
import com.turbotavern.herotier.Transform
import com.turbotavern.herotier.VisualProbeGate
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
        return Frame(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 1, 1, Transform(1f, 1f, 0, 0), rotationDeg)
    }
}

private class FakeOcr(private val script: List<List<OcrLine>>, private val available: Boolean = true) : HeroOcr {
    var callCount = 0
    override fun isAvailable() = available
    override fun recognize(frame: Frame): List<OcrLine> {
        val r = script.getOrElse(callCount) { script.lastOrNull() ?: emptyList() }
        callCount++
        return r
    }
}

private class FakeTrinketRenderer : TrinketRenderer {
    var renderCount = 0
    var clearCount = 0
    var lastRecs: List<TrinketRecommendation> = emptyList()
    override fun render(recs: List<TrinketRecommendation>, transform: Transform) { renderCount++; lastRecs = recs }
    override fun clear() { clearCount++ }
}

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TrinketCoordinatorTest {
    private lateinit var ht: HandlerThread
    private lateinit var handler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val matcher = TrinketMatcher(TrinketTable.fromJson(
        """{"trinkets":[
            {"cardId":"L1","trinketClass":"lesser","tier":"S","avgPlacement":3.4,"names":{"enUS":"Welcome Inn"}},
            {"cardId":"L2","trinketClass":"lesser","tier":"B","avgPlacement":4.1,"names":{"enUS":"Goblin Wallet"}}]}"""))

    private fun line(s: String, y: Int) = OcrLine(s, BoxPx(0, y, 10, y + 10))
    private val two = listOf(line("Welcome Inn", 0), line("Goblin Wallet", 20))
    private val one = listOf(line("Welcome Inn", 0))
    private val zero = emptyList<OcrLine>()

    private var fg = Foreground.TRUE
    private var rotation = 0

    @Before fun setUp() { ht = HandlerThread("trinket-test").apply { start() }; handler = Handler(ht.looper) }
    @After fun tearDown() { ht.quitSafely() }

    private fun coordinator(g: FakeGrabber, ocr: FakeOcr, r: FakeTrinketRenderer) =
        TrinketCoordinator(
            grabber = g, ocr = ocr, matcher = matcher, renderer = r,
            foreground = { fg }, currentRotation = { rotation },
            handler = handler, mainHandler = mainHandler,
            gate = VisualProbeGate(openMatches = 2, closeK = 3),
            probeMs = 100, captureIntervalMs = 50, maxAttempts = 3, maxWindowMs = 10_000,
        )

    private fun drain(ms: Long) {
        shadowOf(ht.looper).idleFor(ms, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun opensAndRendersRankedRecommendationOnTwoMatchRound() {
        val g = FakeGrabber(); val r = FakeTrinketRenderer()
        coordinator(g, FakeOcr(listOf(two)), r).start()
        drain(60)
        assertTrue("rendered at least once", r.renderCount >= 1)
        assertEquals(setOf("L1", "L2"), r.lastRecs.map { it.match.entry.cardId }.toSet())
        assertEquals("Welcome Inn (3.4) is the highlighted best", "L1", r.lastRecs.single { it.isBest }.match.entry.cardId)
    }

    @Test fun staysClosedUntilTwoMatches() {
        val g = FakeGrabber(); val r = FakeTrinketRenderer()
        coordinator(g, FakeOcr(listOf(zero, zero, two)), r).start()
        drain(50)
        assertEquals("not opened on zero-match rounds", 0, r.renderCount)
        drain(250)
        assertTrue("opened after the >=2 round", r.renderCount >= 1)
    }

    @Test fun heldWindowClosesOnCloseKZeros() {
        val g = FakeGrabber(); val r = FakeTrinketRenderer()
        coordinator(g, FakeOcr(listOf(two, two, two, two, two, two, zero, zero, zero, zero)), r).start()
        drain(450)
        assertEquals("held open while trinkets visible", 0, r.clearCount)
        drain(800)
        assertTrue("3 consecutive zero rounds closed it", r.clearCount >= 1)
    }

    @Test fun foregroundLostClosesAndStopsCapturing() {
        val g = FakeGrabber(); val r = FakeTrinketRenderer()
        coordinator(g, FakeOcr(listOf(two)), r).start()
        drain(200)
        val mid = g.captureCount
        fg = Foreground.FALSE; drain(300)
        assertTrue("cleared on fg loss", r.clearCount >= 1)
        assertEquals("no captures after fg lost", mid, g.captureCount)
        fg = Foreground.TRUE
    }

    @Test fun staleRotationFrameNotRendered() {
        val g = FakeGrabber(rotationDeg = 0); val r = FakeTrinketRenderer()
        rotation = 90
        coordinator(g, FakeOcr(listOf(two)), r).start()
        drain(200)
        assertTrue("captured", g.captureCount >= 1)
        assertEquals("stale-rotation frame not rendered", 0, r.renderCount)
        rotation = 0
    }

    @Test fun ocrUnavailableNeverCaptures() {
        val g = FakeGrabber(); val r = FakeTrinketRenderer()
        coordinator(g, FakeOcr(listOf(two), available = false), r).start()
        drain(500)
        assertEquals(0, g.captureCount)
        assertEquals(0, r.renderCount)
    }

    @Test fun projectionStopAndStopClose() {
        val g = FakeGrabber(); val r = FakeTrinketRenderer()
        val c = coordinator(g, FakeOcr(listOf(two)), r); c.start()
        drain(120)
        c.onProjectionStopped(); drain(50)
        assertTrue("projection stop cleared", r.clearCount >= 1)
        val n = g.captureCount
        c.stop(); drain(300)
        assertEquals("no captures after stop", n, g.captureCount)
    }

    @Test fun steadyOneMatchHoldsThenMaxWindowCloses() {
        val g = FakeGrabber(); val r = FakeTrinketRenderer()
        coordinator(g, FakeOcr(listOf(two, one)), r).start()
        drain(5_000)
        assertEquals("steady 1-match does not close via CLOSE_K", 0, r.clearCount)
        drain(6_000)
        assertTrue("MAX_WINDOW closed it", r.clearCount >= 1)
    }
}
