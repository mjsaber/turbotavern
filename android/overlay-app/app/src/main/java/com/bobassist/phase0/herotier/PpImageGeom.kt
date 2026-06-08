package com.bobassist.phase0.herotier

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pure image-geometry for the PP-OCRv5 pipeline — ports rapidocr's `DetPreProcess.resize` and
 * `TextRecognizer.resize_norm_img` sizing rules (Apache-2.0). No Android/ORT deps so it is
 * JUnit-testable; the bitmap sampling that uses these sizes lives in [PaddleHeroOcr].
 */
object PpImageGeom {

    /** Det input size: scale so the short side ≥ [limitSide], then round each dim to a /32 multiple. */
    fun detResizeTarget(w: Int, h: Int, limitSide: Int = 736): Pair<Int, Int> {
        val ratio = if (minOf(w, h) < limitSide) limitSide.toFloat() / minOf(w, h) else 1f
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
