package com.bobassist.phase0.herotier

import android.view.View
import android.view.WindowManager

/**
 * Narrow seam over the two WindowManager calls [TierOverlay] uses, so tests record windows with a
 * hand fake (no Mockito). Production delegates to the real [WindowManager].
 */
interface WindowHost {
    fun add(view: View, p: WindowManager.LayoutParams)
    fun remove(view: View)
}

class AndroidWindowHost(private val wm: WindowManager) : WindowHost {
    override fun add(view: View, p: WindowManager.LayoutParams) {
        runCatching { wm.addView(view, p) }
    }

    override fun remove(view: View) {
        runCatching { wm.removeView(view) }
    }
}
