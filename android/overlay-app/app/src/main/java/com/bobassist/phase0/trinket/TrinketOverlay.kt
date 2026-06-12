package com.bobassist.phase0.trinket

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.bobassist.phase0.herotier.BadgeView
import com.bobassist.phase0.herotier.BoxPx
import com.bobassist.phase0.herotier.WindowHost

/**
 * Renders the trinket-offer overlay: one S/A/B/C [BadgeView] above each offered trinket, plus a green
 * [TrinketHighlightView] ring around the recommended one. Mirrors the hero [com.bobassist.phase0.herotier.TierOverlay]
 * (touch-through TYPE_APPLICATION_OVERLAY windows, FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE, alpha <= cap)
 * and reuses its [WindowHost] seam. Not thread-safe; call from the host service main looper.
 */
class TrinketOverlay(
    private val host: WindowHost,
    private val context: Context,
    private val opacityCap: () -> Float,
) {
    private val views = mutableListOf<View>()

    fun show(badgeViews: List<TrinketBadgeView>) {
        clear()
        for (bv in badgeViews) {
            // highlight ring first (drawn behind the tier badge if they overlap)
            bv.highlight?.let { addWindow(TrinketHighlightView(context), it) }
            addWindow(BadgeView(context, bv.tier), bv.tierBadge)
        }
    }

    private fun addWindow(v: View, r: BoxPx) {
        val lp = WindowManager.LayoutParams(
            r.width, r.height, r.left, r.top,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = opacityCap().coerceIn(0f, 1f)
        }
        if (host.add(v, lp)) views += v
    }

    fun clear() {
        views.forEach { host.remove(it) }
        views.clear()
    }
}
