package com.bobassist.phase0.herotier

/**
 * The single capture->screen transform (spec §9.3). Maps an OCR name box (capture-bitmap pixels)
 * to a fixed-size badge rect (screen pixels) centered horizontally above the name. Per-axis
 * scale + offset only; rotation is handled upstream by capturing in the display's current
 * orientation + the coordinator's stale-rotation guard.
 */
object BadgeLayout {
    fun place(box: BoxPx, t: Transform, badgePx: Int, gapPx: Int): BoxPx {
        val left = (box.left * t.scaleX).toInt() + t.offsetX
        val right = (box.right * t.scaleX).toInt() + t.offsetX
        val top = (box.top * t.scaleY).toInt() + t.offsetY
        val cx = (left + right) / 2
        val bLeft = cx - badgePx / 2
        val bTop = top - gapPx - badgePx
        return BoxPx(bLeft, bTop, bLeft + badgePx, bTop + badgePx)
    }
}
