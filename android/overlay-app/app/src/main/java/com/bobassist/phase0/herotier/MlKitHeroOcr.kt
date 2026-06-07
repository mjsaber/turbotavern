package com.bobassist.phase0.herotier

import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Baseline OCR (spec §6.1): bundled ML Kit Latin + Chinese recognizers. ML Kit returns bounding
 * boxes in input-bitmap (= capture) pixels, so no remapping is needed. Emits one [OcrLine] per
 * recognized line, plus per-element lines as a fallback when a line splits into several elements.
 *
 * [recognize] blocks on the recognizer Tasks, so it MUST be called off the main thread (the
 * coordinator runs it on its own HandlerThread).
 */
class MlKitHeroOcr(
    // Lazy-safe so unavailability is observable via isAvailable() rather than throwing at construction.
    private val recognizers: List<TextRecognizer>? = runCatching {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
        )
    }.getOrElse { Log.w(TAG, "recognizer init failed: ${it.message}"); null },
) : HeroOcr {

    override fun isAvailable(): Boolean = recognizers != null

    override fun recognize(frame: Frame): List<OcrLine> {
        val recognizers = recognizers ?: return emptyList()
        val image = InputImage.fromBitmap(frame.bitmap, 0)   // capture buffer is already upright
        val out = ArrayList<OcrLine>()
        for (r in recognizers) {
            val text = runCatching { Tasks.await(r.process(image)) }
                .getOrElse { Log.w(TAG, "recognizer failed: ${it.message}"); null } ?: continue
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    line.boundingBox?.let { out += OcrLine(line.text, it.toBoxPx()) }
                    if (line.elements.size > 1) {
                        for (el in line.elements) el.boundingBox?.let { out += OcrLine(el.text, it.toBoxPx()) }
                    }
                }
            }
        }
        return out
    }

    private fun Rect.toBoxPx() = BoxPx(left, top, right, bottom)

    companion object {
        private const val TAG = "MlKitHeroOcr"
    }
}
