package com.turbotavern.herotier

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
        val t0 = System.nanoTime()
        val boxes = detect(bmp, frame.captureW, frame.captureH)
        val detMs = (System.nanoTime() - t0) / 1_000_000
        val lines = recognizeBatch(bmp, boxes)
        val recMs = (System.nanoTime() - t0) / 1_000_000 - detMs
        Log.i(TAG, "timing det=${detMs}ms rec=${recMs}ms (boxes=${boxes.size}, lines=${lines.size})")
        lines
    }.getOrElse { Log.w(TAG, "recognize failed: ${it.message}"); emptyList() }

    /** Run DBNet det on the whole frame → name-line boxes in capture px. */
    private fun detect(bmp: Bitmap, capW: Int, capH: Int): List<BoxPx> {
        val (tW, tH) = PpImageGeom.detResizeTarget(capW, capH)
        val scaled = Bitmap.createScaledBitmap(bmp, tW, tH, true)
        val prob = try {
            runSession(det, detIn, singleChw(scaled, tW, tH), longArrayOf(1, 3, tH.toLong(), tW.toLong()))
        } finally {
            if (scaled !== bmp) scaled.recycle()
        }
        return detPost.boxes(prob, tW, tH, capW, capH)
    }

    /**
     * Rec all boxes in **width-sorted batches** (PP-OCR `rec_batch_num`): one `OrtSession.run` per
     * batch over `[N,3,48,batchW]` instead of one call per box — rec is ~80% of runtime, dominated by
     * per-call cost over many boxes. Each crop is scaled to height 48 and right-padded with 0.0 to the
     * batch's max width; output `[N,T,C]` is CTC-decoded per image.
     */
    private fun recognizeBatch(bmp: Bitmap, boxes: List<BoxPx>): List<OcrLine> {
        if (boxes.isEmpty()) return emptyList()
        val lineBmps = ArrayList<Bitmap>(boxes.size)
        val widths = IntArray(boxes.size)
        for ((i, box) in boxes.withIndex()) {
            val x = box.left.coerceIn(0, bmp.width - 1)
            val y = box.top.coerceIn(0, bmp.height - 1)
            val w = box.width.coerceIn(1, bmp.width - x)
            val h = box.height.coerceIn(1, bmp.height - y)
            val plan = PpImageGeom.recResizePlan(w, h)
            val crop = Bitmap.createBitmap(bmp, x, y, w, h)
            lineBmps.add(Bitmap.createScaledBitmap(crop, plan.resizedW, REC_H, true).also { crop.recycle() })
            widths[i] = plan.resizedW
        }
        val classes = chars.size
        val results = arrayOfNulls<OcrLine>(boxes.size)
        try {
            val order = boxes.indices.sortedBy { widths[it] }     // similar widths batch → minimal pad
            var p = 0
            while (p < order.size) {
                val group = order.subList(p, minOf(p + REC_BATCH, order.size))
                val batchW = group.maxOf { widths[it] }
                val plane = REC_H * batchW
                val buf = FloatBuffer.allocate(group.size * 3 * plane)
                group.forEachIndexed { gi, idx -> writeChw(buf, gi * 3 * plane, lineBmps[idx], widths[idx], REC_H, batchW) }
                buf.rewind()
                val flat = runSession(rec, recIn, buf, longArrayOf(group.size.toLong(), 3, REC_H.toLong(), batchW.toLong()))
                val steps = flat.size / (group.size * classes)
                group.forEachIndexed { gi, idx ->
                    val r = ctc.decode(flat, gi * steps * classes, steps, classes)
                    if (r.text.isNotBlank()) results[idx] = OcrLine(r.text, boxes[idx], r.confidence)
                }
                p += REC_BATCH
            }
        } finally {
            lineBmps.forEach { it.recycle() }
        }
        return results.filterNotNull()
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

    /** A single image → its own zero-filled normalized CHW buffer of width [w] (det path). */
    private fun singleChw(bmp: Bitmap, w: Int, h: Int): FloatBuffer =
        FloatBuffer.allocate(3 * h * w).also { writeChw(it, 0, bmp, w, h, w); it.rewind() }

    /**
     * Write [bmp] (already resized to [w]×[h]) as normalized CHW into [buf] starting at [base], with
     * row stride [dstW] (≥[w]; the [w]..[dstW] columns stay 0.0 = PP-OCR's zero padding). Absolute
     * `put` — caller rewinds before use. Per-channel `(px/255-0.5)/0.5`, **RGB order** (matches the
     * Stage-0 harness which fed RGB ndarrays). Swap R/B here if a device run regresses vs offline.
     */
    private fun writeChw(buf: FloatBuffer, base: Int, bmp: Bitmap, w: Int, h: Int, dstW: Int) {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val plane = h * dstW
        for (yy in 0 until h) {
            val rowSrc = yy * w
            val rowDst = yy * dstW
            for (xx in 0 until w) {
                val p = px[rowSrc + xx]
                val idx = base + rowDst + xx
                buf.put(idx, norm((p ushr 16) and 0xFF))
                buf.put(plane + idx, norm((p ushr 8) and 0xFF))
                buf.put(2 * plane + idx, norm(p and 0xFF))
            }
        }
    }

    private fun norm(c: Int) = (c / 255f - 0.5f) / 0.5f

    companion object {
        private const val TAG = "PaddleHeroOcr"
        private const val DIR = "ppocr"
        private const val REC_H = 48        // PP-OCR rec input height
        private const val REC_BATCH = 6     // rec_batch_num

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
