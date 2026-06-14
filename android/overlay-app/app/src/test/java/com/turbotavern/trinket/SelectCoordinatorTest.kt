package com.turbotavern.trinket

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.turbotavern.herotier.BadgeRenderer
import com.turbotavern.herotier.BoxPx
import com.turbotavern.herotier.Foreground
import com.turbotavern.herotier.Frame
import com.turbotavern.herotier.HeroBadge
import com.turbotavern.herotier.HeroMatcher
import com.turbotavern.herotier.HeroOcr
import com.turbotavern.herotier.OcrLine
import com.turbotavern.herotier.ScreenGrabber
import com.turbotavern.herotier.TierTable
import com.turbotavern.herotier.Transform
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
    val renders = mutableListOf<Set<String>>()           // cardId set per render, for flicker assertions
    override fun render(badges: List<HeroBadge>, transform: Transform) { renderCount++; last = badges; renders += badges.map { it.cardId }.toSet() }
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
    private var forceOpenFlag = false

    @Before fun setUp() { ht = HandlerThread("select-test").apply { start() }; handler = Handler(ht.looper) }
    @After fun tearDown() { ht.quitSafely() }

    private fun coord(
        g: SGrabber, ocr: SOcr, hr: FakeHeroRenderer, tr: FakeTrinkRenderer,
        heroEnabled: Boolean = true, trinketEnabled: Boolean = true, heroMaxMisses: Int = 2,
    ) =
        SelectCoordinator(
            grabber = g, ocr = ocr,
            heroMatcher = HeroMatcher(heroTable), heroRenderer = hr,
            trinketMatcher = TrinketMatcher(trinketTable), trinketRenderer = tr,
            foreground = { fg }, currentRotation = { rotation },
            handler = handler, mainHandler = mainHandler,
            arbiter = SelectWindowArbiter(),
            forceOpen = { forceOpenFlag },
            heroEnabled = { heroEnabled }, trinketEnabled = { trinketEnabled },
            probeMs = 100, captureIntervalMs = 50, maxAttempts = 3, maxWindowMs = 10_000,
            heroMaxMisses = heroMaxMisses,
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

    @Test fun forceOpenBypassesGateAndClosesOnFallingEdge() {
        // a single-trinket frame is BELOW the gate's openMatches=2, so it would never open normally;
        // force-open shows it anyway (on-device tuning). The falling edge then closes it.
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        forceOpenFlag = true
        val singleTrinket = listOf(l("Welcome Inn", 0))
        coord(g, SOcr(listOf(singleTrinket)), hr, tr).start()
        repeat(8) { drain(50) }
        assertTrue("forced open rendered the 1-match trinket", tr.renderCount >= 1)
        forceOpenFlag = false
        repeat(4) { drain(50) }
        assertTrue("falling edge closed the forced window", tr.clearCount >= 1)
    }

    @Test fun heroDisabledHidesHeroOverlayOnHeroScreen() {
        // AppPrefs hero toggle OFF: even on a hero-select screen the hero overlay must never render.
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        coord(g, SOcr(listOf(heroFrame, heroFrame, heroFrame)), hr, tr, heroEnabled = false).start()
        repeat(8) { drain(50) }
        assertEquals("hero overlay suppressed when the setting is off", 0, hr.renderCount)
        assertEquals("no trinket on a hero screen", 0, tr.renderCount)
    }

    @Test fun trinketDisabledHidesTrinketOverlayOnTrinketScreen() {
        // AppPrefs trinket toggle OFF: even on a trinket-shop screen the trinket overlay must never render.
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        coord(g, SOcr(listOf(trinketFrame, trinketFrame, trinketFrame)), hr, tr, trinketEnabled = false).start()
        repeat(8) { drain(50) }
        assertEquals("trinket overlay suppressed when the setting is off", 0, tr.renderCount)
        assertEquals("no hero on a trinket screen", 0, hr.renderCount)
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

    // Bug 2 (live hero 4↔3 flicker): one hero is momentarily missed by OCR while the window stays HERO
    // (gate keeps it open on >=1 match). The stabilizer holds the missed hero so its badge never blinks.
    @Test fun heroBadgeHeldAcrossSingleFrameMiss_noFlicker() {
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        val sneedOnly = listOf(l("Sneed", 0))               // H2 (Rafaam) dropped by OCR this frame
        coord(g, SOcr(listOf(heroFrame, sneedOnly, heroFrame)), hr, tr).start()
        repeat(12) { drain(50) }
        assertTrue("hero overlay opened", hr.renders.isNotEmpty())
        assertTrue("every render shows BOTH heroes — the held badge never blinks: ${hr.renders}",
            hr.renders.all { it == setOf("H1", "H2") })
    }

    // Control / in-test A/B: with no hold (heroMaxMisses=0 = the old behavior) the same missed frame
    // drops the hero, so a render shows only H1 — i.e. the flicker. Proves the stabilizer is the fix.
    @Test fun withoutHold_singleFrameMissDropsHero_reproducesFlicker() {
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        val sneedOnly = listOf(l("Sneed", 0))
        coord(g, SOcr(listOf(heroFrame, sneedOnly, heroFrame)), hr, tr, heroMaxMisses = 0).start()
        repeat(12) { drain(50) }
        assertTrue("control: with no hold a render shows only H1 (the blink): ${hr.renders}",
            hr.renders.any { it == setOf("H1") })
    }

    // Forced close (foreground loss) must reset the stabilizer — else held heroes leak into the NEXT
    // hero window. Open with {H1,H2}, lose foreground (closeAll), reopen with a DIFFERENT pair {H3,H4};
    // no render after reopen may contain the old heroes. (codex P2 on closeAll reset coverage.)
    @Test fun foregroundLossResetsHeroStabilizer_noStaleLeakOnReopen() {
        val table = TierTable.fromJson(
            """{"heroes":[{"cardId":"H1","tier":"S","names":{"enUS":"Sneed"}},
                          {"cardId":"H2","tier":"A","names":{"enUS":"Rafaam"}},
                          {"cardId":"H3","tier":"B","names":{"enUS":"Brann"}},
                          {"cardId":"H4","tier":"B","names":{"enUS":"Tess"}}]}""")
        val g = SGrabber(); val hr = FakeHeroRenderer(); val tr = FakeTrinkRenderer()
        val firstPair = listOf(l("Sneed", 0), l("Rafaam", 20))    // H1,H2
        val secondPair = listOf(l("Brann", 0), l("Tess", 20))     // H3,H4
        val c = SelectCoordinator(
            grabber = g, ocr = SOcr(listOf(firstPair, firstPair, secondPair)),
            heroMatcher = HeroMatcher(table), heroRenderer = hr,
            trinketMatcher = TrinketMatcher(trinketTable), trinketRenderer = tr,
            foreground = { fg }, currentRotation = { rotation },
            handler = handler, mainHandler = mainHandler, arbiter = SelectWindowArbiter(),
            forceOpen = { false }, heroEnabled = { true }, trinketEnabled = { true },
            probeMs = 100, captureIntervalMs = 50, maxAttempts = 3, maxWindowMs = 10_000, heroMaxMisses = 2,
        )
        c.start()
        drain(80)                                       // open + render {H1,H2}
        assertTrue("opened with first pair", hr.renders.any { it == setOf("H1", "H2") })
        fg = Foreground.FALSE; drain(150)               // closeAll -> must reset stabilizer (no capture while fg false)
        hr.renders.clear()
        fg = Foreground.TRUE; repeat(10) { drain(50) }  // reopen with the second pair
        assertTrue("reopened with the new pair", hr.renders.any { it == setOf("H3", "H4") })
        assertTrue("no stale H1/H2 leaked into the reopened window: ${hr.renders}",
            hr.renders.none { it.contains("H1") || it.contains("H2") })
    }
}
