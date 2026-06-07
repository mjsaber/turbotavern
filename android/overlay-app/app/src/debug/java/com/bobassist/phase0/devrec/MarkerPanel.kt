package com.bobassist.phase0.devrec

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Touchable floating panel: [≡][MARK][STOP]. Drag via the ≡ handle; buttons fire callbacks.
 * MAIN-THREAD ONLY (WindowManager). Buttons' onClick run on main; caller stamps clickTs there.
 */
class MarkerPanel(
    private val context: Context,
    private val onMark: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // touchable (no NOT_TOUCHABLE), never steals keys
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 8.dp() }

    fun show() {
        if (view != null) return
        val handle = TextView(context).apply {
            text = "≡"; setTextColor(Color.WHITE); setPadding(24, 16, 24, 16)
            setBackgroundColor(0xAA000000.toInt()); setOnTouchListener(DragListener())
        }
        val mark = Button(context).apply { text = "MARK"; setOnClickListener { onMark() } }
        val stop = Button(context).apply { text = "STOP"; setOnClickListener { onStop() } }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(handle); addView(mark); addView(stop)
        }
        wm.addView(row, lp); view = row
    }

    fun hide() { view?.let { runCatching { wm.removeView(it) } }; view = null }

    private inner class DragListener : View.OnTouchListener {
        private var tx = 0f; private var ty = 0f; private var sx = 0; private var sy = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { tx = e.rawX; ty = e.rawY; sx = lp.x; sy = lp.y; return true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    // Update the ROOT (the WM-managed row), not v (the ≡ handle child): passing a child
                    // makes updateViewLayout set WindowManager.LayoutParams on it, crashing LinearLayout measure.
                    view?.let { runCatching { wm.updateViewLayout(it, lp) } }; return true
                }
            }
            return false
        }
    }

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()
}
