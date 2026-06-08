package com.bobassist.phase0.herotier

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/**
 * PP-OCRv5 [HeroOcr] via ONNX Runtime (spec §6.1; chosen by the Stage-0 bake-off — see
 * `recordings/ocr-corpus/PPOCRV5-OFFLINE.md`). Pipeline: capture bitmap → DBNet det → name-line
 * boxes ([PpDetPost]) → per-box crop → SVTRv2 rec → CTC greedy ([PpRecCtc]) → [OcrLine] with boxes
 * in **capture-bitmap pixels**.
 *
 * Pure stages ([PpImageGeom]/[PpDetPost]/[PpRecCtc]) are JUnit-tested; this glue (bitmap sampling +
 * `OrtSession`) is validated on-device (plan Stage 4) — native ORT can't run under Robolectric.
 * Any model-load/inference failure leaves [isAvailable] false / yields no lines (never throws), so
 * the coordinator stays inert (spec §8.2, §12).
 *
 * [recognize] blocks on ORT and MUST be called off the main thread (the coordinator's HandlerThread).
 */
class PaddleHeroOcr private constructor(
    private val env: OrtEnvironment,
    private val det: OrtSession,
    private val rec: OrtSession,
    private val chars: List<String>,
) : HeroOcr {

    private val ctc = PpRecCtc(chars)
    private val detPost = PpDetPost()
    private val detIn = det.inputNames.first()
    private val recIn = rec.inputNames.first()

    override fun isAvailable(): Boolean = true   // a non-null instance means all assets loaded

    override fun recognize(frame: Frame): List<OcrLine> = runCatching {
        val bmp = frame.bitmap
        val boxes = detect(bmp, frame.captureW, frame.captureH)
        boxes.mapNotNull { box ->
            val text = recognizeCrop(bmp, box) ?: return@mapNotNull null
            if (text.first.isBlank()) null else OcrLine(text.first, box, text.second)
        }
    }.getOrElse { Log.w(TAG, "recognize failed: ${it.message}"); emptyList() }

    /** Run DBNet det on the whole frame → name-line boxes in capture px. */
    private fun detect(bmp: Bitmap, capW: Int, capH: Int): List<BoxPx> {
        val (tW, tH) = PpImageGeom.detResizeTarget(capW, capH)
        val scaled = Bitmap.createScaledBitmap(bmp, tW, tH, true)
        val prob = try {
            runSession(det, detIn, chwNorm(scaled, tW, tH, tW), longArrayOf(1, 3, tH.toLong(), tW.toLong()))
        } finally {
            if (scaled !== bmp) scaled.recycle()
        }
        return detPost.boxes(prob, tW, tH, capW, capH)
    }

    /** Crop the box from the capture bitmap, run rec, CTC-decode → (text, confidence). */
    private fun recognizeCrop(bmp: Bitmap, box: BoxPx): Pair<String, Float>? {
        val x = box.left.coerceIn(0, bmp.width - 1)
        val y = box.top.coerceIn(0, bmp.height - 1)
        val w = box.width.coerceIn(1, bmp.width - x)
        val h = box.height.coerceIn(1, bmp.height - y)
        val plan = PpImageGeom.recResizePlan(w, h)
        val crop = Bitmap.createBitmap(bmp, x, y, w, h)
        val line = Bitmap.createScaledBitmap(crop, plan.resizedW, plan.imgH, true)
        return try {
            val buf = chwNorm(line, plan.resizedW, plan.imgH, plan.paddedW)   // pad cols stay 0.0
            val flat = runSession(rec, recIn, buf, longArrayOf(1, 3, plan.imgH.toLong(), plan.paddedW.toLong()))
            val classes = chars.size
            val steps = flat.size / classes
            val r = ctc.decode(flat, steps, classes)
            r.text to r.confidence
        } finally {
            crop.recycle(); if (line !== crop) line.recycle()
        }
    }

    /** Run one session with a single float input; return the (single) output flattened to FloatArray. */
    private fun runSession(s: OrtSession, inName: String, buf: FloatBuffer, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, buf, shape).use { input ->
            s.run(mapOf(inName to input)).use { res ->
                val out = res[0] as OnnxTensor
                val fb = out.floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }

    /**
     * Bitmap (already resized to [w]×[h]) → normalized CHW float buffer of width [dstW] (≥[w]; the
     * [w]..[dstW] columns stay 0.0 = PP-OCR's zero padding). Per-channel `(px/255-0.5)/0.5`, **RGB
     * order** (matches the Stage-0 harness, which fed RGB ndarrays). Swap R/B here if a device run
     * shows worse accuracy than the offline numbers.
     */
    private fun chwNorm(bmp: Bitmap, w: Int, h: Int, dstW: Int): FloatBuffer {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(3 * h * dstW)   // allocate() backs an array zero-filled
        val plane = h * dstW
        for (yy in 0 until h) {
            val rowSrc = yy * w
            val rowDst = yy * dstW
            for (xx in 0 until w) {
                val p = px[rowSrc + xx]
                val idx = rowDst + xx
                buf.put(idx, norm((p ushr 16) and 0xFF))
                buf.put(plane + idx, norm((p ushr 8) and 0xFF))
                buf.put(2 * plane + idx, norm(p and 0xFF))
            }
        }
        buf.rewind()
        return buf
    }

    private fun norm(c: Int) = (c / 255f - 0.5f) / 0.5f

    companion object {
        private const val TAG = "PaddleHeroOcr"
        private const val DIR = "ppocr"

        /** Load models+dict from assets. Returns null (→ coordinator inert) if anything fails. */
        fun create(context: Context): PaddleHeroOcr? = runCatching {
            val env = OrtEnvironment.getEnvironment()
            val a = context.assets
            val det = env.createSession(a.open("$DIR/ch_PP-OCRv5_det_mobile.onnx").use { it.readBytes() })
            val rec = env.createSession(a.open("$DIR/ch_PP-OCRv5_rec_mobile.onnx").use { it.readBytes() })
            val dict = a.open("$DIR/ppocrv5_dict.txt").bufferedReader().use { it.readLines() }
            PaddleHeroOcr(env, det, rec, PpRecCtc.charTableFromDict(dict))
        }.getOrElse { Log.w(TAG, "PP-OCRv5 init failed: ${it.message}"); null }
    }
}
