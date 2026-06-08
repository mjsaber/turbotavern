package com.bobassist.phase0.herotier

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure image-geometry for the PP-OCRv5 pipeline — ports rapidocr's `DetPreProcess.resize` and
 * `TextRecognizer.resize_norm_img` sizing rules (Apache-2.0). No Android/ORT deps so it is
 * JUnit-testable; the bitmap sampling that uses these sizes lives in [PaddleHeroOcr].
 */
object PpImageGeom {

    /**
     * Det input size: scale the short side up to ≥ [minSide] (rapidocr "min" rule), but also **cap the
     * long side at [maxSide]** so full-res screen captures are downscaled before det (the dominant
     * cost) — detected boxes are scaled back to capture px in [PpDetPost], so this only trades a little
     * det resolution for speed. Each dim is rounded to a /32 multiple.
     */
    fun detResizeTarget(w: Int, h: Int, minSide: Int = 736, maxSide: Int = 1280): Pair<Int, Int> {
        var ratio = if (minOf(w, h) < minSide) minSide.toFloat() / minOf(w, h) else 1f
        if (maxOf(w, h) * ratio > maxSide) ratio = maxSide.toFloat() / maxOf(w, h)   // downscale wins
        fun snap(v: Int) = ((v * ratio).toInt() / 32f).roundToInt().times(32).coerceAtLeast(32)
        return snap(w) to snap(h)
    }

    /** Rec crop sizing: height fixed to [imgH]; width = aspect-preserving, capped+padded to a band. */
    data class RecPlan(val resizedW: Int, val paddedW: Int, val imgH: Int)

    fun recResizePlan(srcW: Int, srcH: Int, imgH: Int = 48, bandW: Int = 320): RecPlan {
        val ratio = srcW.toDouble() / srcH                       // double: avoid 48*(320/48)=319.99
        val maxRatio = maxOf(bandW.toDouble() / imgH, ratio)
        val paddedW = (imgH * maxRatio).toInt()
        val want = ceil(imgH * ratio).toInt()
        return RecPlan(if (want > paddedW) paddedW else want, paddedW, imgH)
    }
}
