package com.turbotavern.herotier

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Renders one small touch-through overlay window per hero badge (spec §9.2). Each window is
 * `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE` with `alpha <= opacityCap()` so taps pass through to
 * Hearthstone on Android 12+. The caller supplies [place], which yields the SCREEN-space rect for
 * each badge (via [BadgeLayout]); the overlay never transforms coordinates.
 *
 * Not thread-safe; call from the main looper of the host service.
 */
class TierOverlay(
    private val host: WindowHost,
    private val context: Context,
    private val opacityCap: () -> Float,
) {
    private val views = mutableListOf<View>()

    fun show(badges: List<HeroBadge>, place: (HeroBadge) -> BoxPx) {
        clear()
        for (b in badges) {
            val r = place(b)
            val lp = WindowManager.LayoutParams(
                r.width, r.height, r.left, r.top,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = opacityCap().coerceIn(0f, 1f)   // defend against an out-of-range provider
            }
            val v = BadgeView(context, b.tier)
            if (host.add(v, lp)) views += v             // only track windows that actually attached
        }
    }

    fun clear() {
        views.forEach { host.remove(it) }
        views.clear()
    }
}
