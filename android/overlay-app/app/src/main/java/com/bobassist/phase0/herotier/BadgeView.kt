package com.bobassist.phase0.herotier

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** A small colored rounded badge drawing the tier letter (spec §9.4 colors). */
class BadgeView(context: Context, private val tier: Tier) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(tier) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = minOf(w, h) * 0.2f
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bgPaint)
        textPaint.textSize = h * 0.6f
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(tier.name, w / 2f, baseline, textPaint)
    }

    companion object {
        fun colorFor(t: Tier): Int = when (t) {
            Tier.S -> 0xFFFFD700.toInt()   // gold
            Tier.A -> 0xFFA335EE.toInt()   // purple
            Tier.B -> 0xFF3B82F6.toInt()   // blue
            Tier.C -> 0xFF9CA3AF.toInt()   // gray
        }
    }
}
