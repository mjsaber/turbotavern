package com.bobassist.phase0.trinket

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.bobassist.phase0.herotier.BadgeRenderer
import com.bobassist.phase0.herotier.BoxPx
import com.bobassist.phase0.herotier.Foreground
import com.bobassist.phase0.herotier.Frame
import com.bobassist.phase0.herotier.HeroBadge
import com.bobassist.phase0.herotier.HeroMatcher
import com.bobassist.phase0.herotier.HeroOcr
import com.bobassist.phase0.herotier.OcrLine
import com.bobassist.phase0.herotier.ScreenGrabber
import com.bobassist.phase0.herotier.TierTable
import com.bobassist.phase0.herotier.Transform
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

private class SGrabber(private val rot: Int = 0) : ScreenGrabber {
    var n = 0
    override fun capture(): Frame? { n++; return Frame(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 1, 1, Transform(1f, 1f, 0, 0), rot) }
}
private class SOcr(private val script: List<List<OcrLine>>) : HeroOcr {
    var i = 0
    override fun isAvailable() = true
    override fun recognize(frame: Frame): List<OcrLine> { val r = script.getOrElse(i) { script.lastOrNull() ?: emptyList() }; i++; return r }
}
private class FakeHeroRenderer : BadgeRenderer {
    var renderCount = 0; var clearCount = 0; var last: List<HeroBadge> = emptyList()
    override fun render(badges: List<HeroBadge>, transform: Transform) { renderCount++; last = badges }
    override fun clear() { clearCount++ }
}
private class FakeTrinkRenderer : TrinketRenderer {
    var renderCount = 0; var clearCount = 0; var last: List<TrinketRecommendation> = emptyList()
    override fun render(recs: List<TrinketRecommendation>, transform: Transform) { renderCount++; last = recs }
    override fun clear() { clearCount++ }
}

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SelectCoordinatorTest {
    private lateinit var ht: HandlerThread
    private lateinit var handler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private val heroTable = TierTable.fromJson(
        """{"heroes":[{"cardId":"H1","tier":"S","names":{"enUS":"Sneed"}},
                      {"cardId":"H2","tier":"A","names":{"enUS":"Rafaam"}}]}""")
    private val trinketTable = TrinketTable.fromJson(
        """{"trinkets":[{"cardId":"T1","trinketClass":"lesser","tier":"S","avgPlacement":3.4,"names":{"enUS":"Welcome Inn"}},
                        {"cardId":"T2","trinketClass":"lesser","tier":"B","avgPlacement":4.1,"names":{"enUS":"Goblin Wallet"}}]}""")

    private fun l(s: String, y: Int) = OcrLine(s, BoxPx(0, y, 10, y + 10))
    private val heroFrame = listOf(l("Sneed", 0), l("Rafaam", 20))
    private val trinketFrame = listOf(l("Welcome Inn", 0), l("Goblin Wallet", 20))
    private val noneFrame = listOf(l("Choose Your Hero", 0))
    private val zero = emptyList<OcrLine>()

    private var fg = Foreground.TRUE
    private var rotation = 0

    @Before fun setUp() { ht = HandlerThread("select-test").apply { start() }; handler = Handler(ht.looper) }
    @After fun tearDown() { ht.quitSafely() }

    private fun coord(g: SGrabber, ocr: SOcr, hr: FakeHeroRenderer, tr: FakeTrinkRenderer) =
        SelectCoordinator(
            grabber = g, ocr = ocr,
            heroMatcher = HeroMatcher(heroTable), heroRenderer = hr,
            trinketMatcher = TrinketMatcher(trinketTable), trinketRenderer = tr,
            foreground = { fg }, currentRotation = { rotation },
            handler = handler, mainHandler = mainHandler,
            arbiter = SelectWindowArbiter(),
            probeMs = 100, captureIntervalMs = 50, maxAttempts = 3, maxWindowMs = 10_000,
        )

    private fun drain(ms: Long) { shadowOf(ht.looper).idleFor(ms, TimeUnit.MILLISECONDS); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun heroSelectOpensHeroOverlayOnly() {
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        coord(g, SOcr(listOf(heroFrame)), hr, tr).start()
        drain(60)
        assertTrue("hero rendered", hr.renderCount >= 1)
        assertEquals(setOf("H1", "H2"), hr.last.map { it.cardId }.toSet())
        assertEquals("trinket overlay never rendered on a hero screen", 0, tr.renderCount)
    }

    @Test fun trinketShopOpensTrinketOverlayOnly() {
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        coord(g, SOcr(listOf(trinketFrame)), hr, tr).start()
        drain(60)
        assertTrue("trinket rendered", tr.renderCount >= 1)
        assertEquals("T1", tr.last.single { it.isBest }.match.entry.cardId)   // Welcome Inn 3.4 best
        assertEquals("hero overlay never rendered on a trinket screen", 0, hr.renderCount)
    }

    @Test fun neverBothActiveAtOnce_phaseHandoff() {
        // hero screen for several rounds, then it ends (zeros), then the trinket screen appears.
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        coord(g, SOcr(listOf(heroFrame, heroFrame, zero, zero, zero, trinketFrame, trinketFrame)), hr, tr).start()
        repeat(20) { drain(50) }   // interleave ht ticks with main renders so each frame renders in-phase
        assertTrue("hero opened during hero phase", hr.renderCount >= 1)
        assertTrue("hero overlay cleared at/after the handoff", hr.clearCount >= 1)
        assertTrue("trinket opened during trinket phase", tr.renderCount >= 1)
    }

    @Test fun belowThresholdShowsNeither() {
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        coord(g, SOcr(listOf(noneFrame)), hr, tr).start()
        drain(300)
        assertEquals(0, hr.renderCount)
        assertEquals(0, tr.renderCount)
    }

    @Test fun foregroundLostClosesBoth() {
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        coord(g, SOcr(listOf(trinketFrame)), hr, tr).start()
        drain(120)
        val mid = g.n
        fg = Foreground.FALSE; drain(300)
        assertTrue("cleared on fg loss", tr.clearCount >= 1)
        assertEquals("no captures after fg lost", mid, g.n)
        fg = Foreground.TRUE
    }

    @Test fun staleRotationNotRendered() {
        val g = SGrabber(rot = 0); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        rotation = 90
        coord(g, SOcr(listOf(heroFrame)), hr, tr).start()
        drain(200)
        assertTrue(g.n >= 1)
        assertEquals("stale-rotation hero frame not rendered", 0, hr.renderCount)
        rotation = 0
    }

    @Test fun projectionStopAndStopClose() {
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        val c = coord(g, SOcr(listOf(trinketFrame)), hr, tr); c.start()
        drain(120)
        c.onProjectionStopped(); drain(50)
        assertTrue(tr.clearCount >= 1)
        val n = g.n
        c.stop(); drain(300)
        assertEquals(n, g.n)
    }
}
