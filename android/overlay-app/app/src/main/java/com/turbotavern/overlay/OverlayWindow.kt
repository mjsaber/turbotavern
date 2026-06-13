package com.turbotavern.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.turbotavern.R

/**
 * Owns the floating overlay button: WindowManager view + drag + tap detection +
 * state-driven appearance. Stateless w.r.t. battle detection — caller pushes
 * state in via [applyState], caller is notified of taps via [onTap].
 *
 * Position is persisted to SharedPreferences under [PREFS_FILE]; restored on
 * [show], saved + clamped on every drag end.
 *
 * Coordinates are stored in raw window pixels, anchored to TOP|START so that
 * `layoutParams.x` increments monotonically as the user drags right (drag
 * math stays trivial). For "default top-right" the initial x is computed
 * from current screen width at [show] time (P2 #7 from codex review).
 *
 * NOT thread-safe; all methods must run on the main looper of the host Service.
 * The `WindowManager` enforces this at runtime if violated.
 */
class OverlayWindow(
    private val context: Context,
    private val onTap: () -> Unit,
) : OverlayUi {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private var view: TextView? = null
    private var lastState: OverlayState = OverlayState.WaitingForBattle
    private val layoutParams = WindowManager.LayoutParams(
        SIZE_DP.dp(context), SIZE_DP.dp(context),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        // FLAG_NOT_FOCUSABLE: never steal keystrokes from HS.
        // NOT using FLAG_LAYOUT_NO_LIMITS — that flag allowed the button to be
        // dragged under the status bar / notch and back into unrecoverable
        // territory; we clamp ourselves to safe bounds on drag end instead.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // x/y deferred to show() so we have current WindowMetrics.
    }

    override fun show() {
        if (view != null) return
        val initial = currentSafeBounds()
        layoutParams.x = clamp(prefs.getInt(KEY_X, defaultX(initial)), initial.left, initial.right - SIZE_DP.dp(context))
        layoutParams.y = clamp(prefs.getInt(KEY_Y, DEFAULT_Y_DP.dp(context)), initial.top, initial.bottom - SIZE_DP.dp(context))

        val v = TextView(context).apply {
            text = "BG"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.overlay_circle_waiting)
            setOnTouchListener(TapAndDragListener())
        }
        wm.addView(v, layoutParams)
        view = v
        applyState(lastState)  // re-apply remembered state after re-attach
        Log.i(TAG, "show at x=${layoutParams.x} y=${layoutParams.y} state=$lastState")
    }

    override fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    override fun applyState(state: OverlayState) {
        lastState = state
        val v = view ?: return
        val drawableRes = when (state.visual) {
            OverlayState.Visual.WAITING -> R.drawable.overlay_circle_waiting
            OverlayState.Visual.READY -> R.drawable.overlay_circle_ready
            OverlayState.Visual.COOLDOWN -> R.drawable.overlay_circle_cooldown
        }
        v.background = context.getDrawable(drawableRes)
    }

    /**
     * Show or hide the window without destroying the OverlayWindow instance.
     * Calling setVisible(true) when not yet shown calls [show] internally
     * (NOT a no-op — corrects the host's intent if they push state before
     * the first show). The remembered `lastState` is preserved across hide/show
     * cycles so transitions like Ready → hide → show stay Ready.
     */
    override fun setVisible(visible: Boolean) {
        if (visible) {
            if (view == null) show()
        } else {
            hide()
        }
    }

    /**
     * Safe area in raw screen pixels, accounting for system bars + display
     * cutout. Computed at show() / drag-end time so it reflects the current
     * orientation (P1 #6 from codex review).
     */
    private fun currentSafeBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
        } else {
            // Pre-R fallback: use raw screen size minus a conservative 24dp inset.
            val dm = context.resources.displayMetrics
            val pad = 24.dp(context)
            Rect(pad, pad, dm.widthPixels - pad, dm.heightPixels - pad)
        }
    }

    private fun defaultX(safe: Rect): Int =
        safe.right - SIZE_DP.dp(context) - DEFAULT_INSET_DP.dp(context)

    private fun clamp(value: Int, min: Int, max: Int): Int =
        when {
            min >= max -> min   // degenerate; happens on tiny test screens
            value < min -> min
            value > max -> max
            else -> value
        }

    private inner class TapAndDragListener : View.OnTouchListener {
        private var startTouchX = 0f
        private var startTouchY = 0f
        private var startWinX = 0
        private var startWinY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = e.rawX
                    startTouchY = e.rawY
                    startWinX = layoutParams.x
                    startWinY = layoutParams.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startTouchX
                    val dy = e.rawY - startTouchY
                    if (!dragging && (kotlin.math.abs(dx) > DRAG_SLOP_PX || kotlin.math.abs(dy) > DRAG_SLOP_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = startWinX + dx.toInt()
                        layoutParams.y = startWinY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, layoutParams) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        finalizeDrag(v)
                    } else if (e.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                        onTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    /**
     * Called on drag end OR on configuration change. Clamps the current
     * layoutParams to today's safe bounds, applies the new geometry, and
     * persists. Safe to call when no drag is in flight — clamp is a no-op
     * if already inside bounds.
     */
    private fun finalizeDrag(v: View) {
        val safe = currentSafeBounds()
        val viewSize = SIZE_DP.dp(context)
        layoutParams.x = clamp(layoutParams.x, safe.left, safe.right - viewSize)
        layoutParams.y = clamp(layoutParams.y, safe.top, safe.bottom - viewSize)
        runCatching { wm.updateViewLayout(v, layoutParams) }
        prefs.edit()
            .putInt(KEY_X, layoutParams.x)
            .putInt(KEY_Y, layoutParams.y)
            .apply()
    }

    /**
     * Host (BobVpnService) calls this on a configuration change (orientation,
     * fold, multi-display move). Re-clamps the existing position so the
     * button doesn't end up under the new system bars / outside the new
     * screen rect.
     */
    override fun onConfigurationChanged() {
        view?.let { finalizeDrag(it) }
    }

    private fun Int.dp(ctx: Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BobOverlay"
        private const val SIZE_DP = 56
        private const val DEFAULT_INSET_DP = 24   // margin from right edge
        private const val DEFAULT_Y_DP = 120
        private const val DRAG_SLOP_PX = 12  // px, not dp, to avoid surprise on hi-dpi

        private const val PREFS_FILE = "bob_overlay_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}
