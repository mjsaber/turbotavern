package com.turbotavern.herotier

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Orientation-robust OCR wrapper.
 *
 * Hearthstone Battlegrounds renders in LANDSCAPE, but on a portrait-locked phone MediaProjection can
 * capture a PORTRAIT buffer with the game rotated 90° inside it. OCR on the raw bitmap then reads
 * sideways text and matches nothing (the on-device symptom: cap=1080x2412, hero=0 trinket=0).
 *
 * This wrapper: for an UPRIGHT capture buffer (width >= height — emulator and most phones) it OCRs
 * unchanged (rotation 0), so there is zero cost/behaviour change there. For a PORTRAIT buffer it OCRs
 * the bitmap rotated 90° and 270° and keeps whichever orientation actually yields hero/trinket matches
 * ([scorer] > 0) — so the correct rotation is auto-selected, no device-specific guess needed. The
 * recognised boxes are mapped BACK to the original capture coordinate space, so the existing scale-only
 * capture→screen transform and the overlay rendering are unchanged. The winning rotation is cached for
 * the session, so the steady state is a single OCR per frame.
 */
class OrientedOcr(
    private val ocr: HeroOcr,
    /** Hero+trinket match count for a candidate line set — used to pick the orientation that reads. */
    private val scorer: (List<OcrLine>) -> Int,
) : HeroOcr {

    @Volatile private var lockedDeg: Int? = null

    override fun isAvailable(): Boolean = ocr.isAvailable()

    override fun recognize(frame: Frame): List<OcrLine> {
        val candidates: List<Int> = when {
            frame.captureW >= frame.captureH -> listOf(0)                  // upright buffer
            else -> lockedDeg?.let { listOf(it) } ?: listOf(90, 270)       // portrait buffer of a landscape game
        }
        var best: List<OcrLine> = emptyList()
        var bestScore = -1
        var bestDeg = candidates.first()
        for (deg in candidates) {                                          // evaluate ALL candidates, pick the best
            val lines = recognizeAt(frame, deg)
            val score = runCatching { scorer(lines) }.getOrElse { 0 }
            if (score > bestScore) { bestScore = score; best = lines; bestDeg = deg }
        }
        // Lock the winning rotation only on a CONFIDENT read (a real select screen shows several hero/
        // trinket names), so a single spurious sideways match can't pin us to the wrong rotation. (codex)
        if (frame.captureW < frame.captureH && bestScore >= LOCK_MIN_SCORE) lockedDeg = bestDeg
        return best
    }

    private fun recognizeAt(frame: Frame, deg: Int): List<OcrLine> {
        if (deg == 0) return runCatching { ocr.recognize(frame) }.getOrElse { emptyList() }
        val rotated = runCatching { rotate(frame.bitmap, deg) }.getOrNull() ?: return emptyList()
        return try {
            val raw = runCatching {
                ocr.recognize(Frame(rotated, rotated.width, rotated.height, frame.transform, frame.rotationDeg))
            }.getOrElse { emptyList() }
            raw.map { it.copy(box = unrotateBox(it.box, deg, frame.captureW, frame.captureH)) }
        } finally {
            runCatching { rotated.recycle() }                              // only the rotated copy; caller owns frame.bitmap
        }
    }

    private fun rotate(src: Bitmap, deg: Int): Bitmap {
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
    }

    companion object {
        /** Minimum hero+trinket match count before we trust + cache a rotation (a real select screen
         *  shows several names; 1 could be a spurious sideways read). */
        private const val LOCK_MIN_SCORE = 2

        /**
         * Map a box detected on a bitmap rotated [deg] (90 or 270, clockwise, via [Matrix.postRotate])
         * back to the original pre-rotation coordinate space of size [origW]x[origH]. Pure + unit-tested.
         */
        fun unrotateBox(b: BoxPx, deg: Int, origW: Int, origH: Int): BoxPx = when (deg) {
            90 -> BoxPx(left = b.top, top = origH - b.right, right = b.bottom, bottom = origH - b.left)
            270 -> BoxPx(left = origW - b.bottom, top = b.left, right = origW - b.top, bottom = b.right)
            else -> b
        }
    }
}
