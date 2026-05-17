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
import kotlin.math.min

class OverlayWindow(context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sizePx = (60 * context.resources.displayMetrics.density).toInt()

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

    var onClick: () -> Unit = {}

    fun show() {
        if (attached) return
        attached = true
        view.onClick = { onClick() }
        wm.addView(view, params)
    }

    fun hide() {
        if (!attached) return
        attached = false
        wm.removeView(view)
    }

    fun isShown(): Boolean = attached

    private class ButtonView(context: Context, private val sizePx: Int) : View(context) {

        var onClick: () -> Unit = {}

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 220, 60, 60)
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 2f - strokePaint.strokeWidth
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            canvas.drawCircle(width / 2f, height / 2f, r, strokePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                onClick()
                return true
            }
            return super.onTouchEvent(event)
        }
    }
}
