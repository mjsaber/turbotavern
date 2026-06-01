package com.bobassist.phase0.herotier

import android.graphics.Bitmap

enum class Tier { S, A, B, C }

data class HeroTier(val cardId: String, val tier: Tier)

/**
 * App-owned integer rectangle in pixels. Used for all OCR/badge geometry so the pure-logic stages
 * (matcher, badge layout) are testable in plain JUnit without Robolectric and without depending on
 * `android.graphics.Rect` behavior under the mockable Android jar. Framework edges (OCR impls,
 * overlay) convert to/from platform `Rect` at the boundary.
 */
data class BoxPx(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
}

/** One recognized OCR line. [box] is in CAPTURE-bitmap pixels (never screen px). */
data class OcrLine(val text: String, val box: BoxPx, val confidence: Float? = null)

/** A matched hero to render. [box] is in CAPTURE-bitmap pixels; BadgeLayout maps it to screen. */
data class HeroBadge(val cardId: String, val tier: Tier, val box: BoxPx)

/**
 * Capture->screen mapping. The capture buffer is sized to the display's CURRENT orientation, so
 * mapping is pure scale + offset (no in-plane rotation). See [Frame.rotationDeg].
 */
data class Transform(val scaleX: Float, val scaleY: Float, val offsetX: Int, val offsetY: Int)

/**
 * A captured frame. [rotationDeg] is the display rotation at capture time; the coordinator drops
 * a frame whose rotation no longer matches the display when it is about to render (stale guard).
 */
data class Frame(val bitmap: Bitmap, val captureW: Int, val captureH: Int,
                 val transform: Transform, val rotationDeg: Int)
