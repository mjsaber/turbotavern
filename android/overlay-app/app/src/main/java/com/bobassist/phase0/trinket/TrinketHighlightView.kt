package com.bobassist.phase0.trinket

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The "pick this one" ring drawn around the recommended trinket's name. A bright green rounded stroke
 * (no fill) so the trinket art stays visible; touch-through is handled by the overlay window flags.
 */
class TrinketHighlightView(context: Context) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2EA043.toInt()                 // green = recommended (matches the kill-ready green)
        style = Paint.Style.STROKE
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x662EA043                          // soft outer glow
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val stroke = minOf(w, h) * 0.08f
        val r = minOf(w, h) * 0.22f
        glow.strokeWidth = stroke * 2.2f
        ring.strokeWidth = stroke
        val inset = stroke
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, glow)
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, ring)
    }
}
