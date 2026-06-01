package com.bobassist.phase0.herotier

import android.graphics.Bitmap
import android.graphics.Rect

enum class Tier { S, A, B, C }

data class HeroTier(val cardId: String, val tier: Tier)

/** One recognized OCR line. [box] is in CAPTURE-bitmap pixels (never screen px). */
data class OcrLine(val text: String, val box: Rect, val confidence: Float? = null)

/** A matched hero to render. [box] is in CAPTURE-bitmap pixels; BadgeLayout maps it to screen. */
data class HeroBadge(val cardId: String, val tier: Tier, val box: Rect)

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
