package com.hsdisconnect.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayWindow(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sizePx = (60 * context.resources.displayMetrics.density).toInt()

    var onClick: () -> Unit = {}
    var onPositionChanged: (Int, Int) -> Unit = { _, _ -> }

    private val view = ButtonView(context, sizePx)
    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 50
        y = 200
    }

    private var attached = false

    init {
        view.onClick = { onClick() }
        view.onDrag = { dx, dy ->
            params.x = clampX(params.x + dx)
            params.y = clampY(params.y + dy)
            if (attached) wm.updateViewLayout(view, params)
        }
        view.onDragEnd = { onPositionChanged(params.x, params.y) }
    }

    fun setPosition(x: Int, y: Int) {
        params.x = clampX(x)
        params.y = clampY(y)
        if (attached) wm.updateViewLayout(view, params)
    }

    fun show() {
        if (attached) return
        attached = true
        wm.addView(view, params)
    }

    fun hide() {
        if (!attached) return
        attached = false
        wm.removeView(view)
    }

    fun isShown(): Boolean = attached

    private fun clampX(v: Int): Int {
        val w = context.resources.displayMetrics.widthPixels
        return v.coerceIn(0, max(0, w - sizePx))
    }

    private fun clampY(v: Int): Int {
        val h = context.resources.displayMetrics.heightPixels
        return v.coerceIn(0, max(0, h - sizePx))
    }

    private class ButtonView(context: Context, private val sizePx: Int) : View(context) {

        var onClick: () -> Unit = {}
        var onDrag: (Int, Int) -> Unit = { _, _ -> }
        var onDragEnd: () -> Unit = {}

        private val touchSlopPx = (8 * context.resources.displayMetrics.density).toInt()

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 220, 60, 60)
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var isDragging = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f - strokePaint.strokeWidth
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            canvas.drawCircle(width / 2f, height / 2f, r, strokePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    lastX = downX; lastY = downY
                    isDragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - downX
                    val totalDy = event.rawY - downY
                    if (!isDragging && (abs(totalDx) > touchSlopPx || abs(totalDy) > touchSlopPx)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val dx = (event.rawX - lastX).toInt()
                        val dy = (event.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) onDrag(dx, dy)
                        lastX = event.rawX; lastY = event.rawY
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) onDragEnd() else onClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
