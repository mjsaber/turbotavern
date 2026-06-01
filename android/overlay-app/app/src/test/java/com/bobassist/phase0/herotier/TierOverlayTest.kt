package com.bobassist.phase0.herotier

import android.os.Build
import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class FakeWindowHost : WindowHost {
    val added = mutableListOf<WindowManager.LayoutParams>()
    var removeCount = 0
    override fun add(view: View, p: WindowManager.LayoutParams) { added += p }
    override fun remove(view: View) { removeCount++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TierOverlayTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun overlay(host: WindowHost, cap: Float) = TierOverlay(host, ctx, opacityCap = { cap })
    private fun badge(c: String) = HeroBadge("BG_$c", Tier.A, BoxPx(0, 0, 10, 10))
    private val place: (HeroBadge) -> BoxPx = { BoxPx(0, 0, 40, 40) }

    @Test fun showAddsOnePerBadge() {
        val h = FakeWindowHost()
        overlay(h, 0.5f).show(listOf(badge("A"), badge("B")), place)
        assertEquals(2, h.added.size)
    }

    @Test fun layoutParamsTouchThroughAndAlphaCapped() {
        val h = FakeWindowHost()
        overlay(h, 0.5f).show(listOf(badge("A")), place)
        val p = h.added.single()
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(p.alpha <= 0.5f)
        assertEquals(40, p.width); assertEquals(40, p.height)
    }

    @Test fun clearRemovesAllIdempotent() {
        val h = FakeWindowHost()
        val o = overlay(h, 0.5f)
        o.show(listOf(badge("A")), place)
        o.clear(); o.clear()
        assertEquals(1, h.removeCount)
    }

    @Test fun showReplacesPreviousSet() {
        val h = FakeWindowHost()
        val o = overlay(h, 0.5f)
        o.show(listOf(badge("A")), place)
        o.show(listOf(badge("B"), badge("C")), place)
        assertEquals(1, h.removeCount)          // old one removed
        assertEquals(3, h.added.size)           // 1 + 2
    }
}
