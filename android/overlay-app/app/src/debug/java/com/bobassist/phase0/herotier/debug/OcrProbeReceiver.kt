package com.bobassist.phase0.herotier.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.bobassist.phase0.herotier.BoxPx
import com.bobassist.phase0.herotier.Frame
import com.bobassist.phase0.herotier.HeroMatcher
import com.bobassist.phase0.herotier.HeroOcr
import com.bobassist.phase0.herotier.MlKitHeroOcr
import com.bobassist.phase0.herotier.OcrLine
import com.bobassist.phase0.herotier.TierTable
import com.bobassist.phase0.herotier.Transform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Spike-A harness (debug-only). Trigger:
 *   adb shell am broadcast -a com.bobassist.phase0.OCR_PROBE
 *
 * Reads every PNG in `<app files>/probe/`, runs each registered [HeroOcr] engine + [HeroMatcher]
 * over the bundled tier table, logs one JSON line per (image, engine) under tag "OcrProbe", and
 * writes an annotated copy (OCR line boxes red, matched-hero boxes green + tier label) to
 * `<app files>/probe/out/<name>.<engine>.png` so box↔text alignment is visually verifiable.
 *
 * Pull results:
 *   adb logcat -s OcrProbe
 *   adb pull /sdcard/Android/data/com.bobassist.phase0/files/probe/out
 */
class OcrProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                run(context)
            } catch (t: Throwable) {
                Log.e(TAG, "probe failed: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun run(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "probe")
        val out = File(dir, "out").apply { mkdirs() }
        val pngs = dir.listFiles { f -> f.extension.lowercase() == "png" }?.sortedBy { it.name } ?: emptyList()
        if (pngs.isEmpty()) {
            Log.w(TAG, "no PNGs in ${dir.absolutePath} (push frames there first)")
            return
        }
        val table = loadTable(context)
        val matcher = HeroMatcher(table)
        // PP-OCRv5 (PaddleHeroOcr) is added here for the bake-off once/if ML Kit fails the gate.
        val engines: List<Pair<String, HeroOcr>> = listOf("mlkit" to MlKitHeroOcr())
        Log.i(TAG, "probe start: ${pngs.size} images, ${engines.size} engine(s), table=${table.size} keys")

        for (png in pngs) {
            val bmp = BitmapFactory.decodeFile(png.absolutePath)
            if (bmp == null) { Log.w(TAG, "decode failed: ${png.name}"); continue }
            val frame = Frame(bmp, bmp.width, bmp.height, Transform(1f, 1f, 0, 0), 0)
            for ((name, ocr) in engines) {
                val t0 = System.nanoTime()
                val lines = runCatching { ocr.recognize(frame) }.getOrElse {
                    Log.e(TAG, "${png.name}/$name recognize failed: ${it.message}"); emptyList()
                }
                val badges = matcher.match(lines)
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i(TAG, report(png.name, name, ms, lines, badges))
                writeAnnotated(out, png.name, name, bmp, lines, badges)
            }
            bmp.recycle()
        }
        Log.i(TAG, "probe done -> ${out.absolutePath}")
    }

    private fun loadTable(context: Context): TierTable =
        runCatching {
            TierTable.fromJson(context.assets.open("herotier_v1.json").bufferedReader().use { it.readText() })
        }.getOrElse {
            Log.e(TAG, "tier asset missing -> empty table (${it.message})")
            TierTable.fromJson("""{"heroes":[]}""")
        }

    private fun report(file: String, engine: String, ms: Long,
                       lines: List<OcrLine>, badges: List<com.bobassist.phase0.herotier.HeroBadge>): String {
        val o = JSONObject()
        o.put("file", file); o.put("engine", engine); o.put("ms", ms); o.put("lineCount", lines.size)
        val la = JSONArray()
        for (l in lines) la.put(JSONObject().apply {
            put("text", l.text); put("box", JSONArray(listOf(l.box.left, l.box.top, l.box.right, l.box.bottom)))
            put("conf", l.confidence ?: JSONObject.NULL)
        })
        o.put("lines", la)
        val ma = JSONArray()
        for (b in badges) ma.put(JSONObject().apply { put("cardId", b.cardId); put("tier", b.tier.name) })
        o.put("matches", ma)
        return o.toString()
    }

    private fun writeAnnotated(outDir: File, file: String, engine: String, src: Bitmap,
                               lines: List<OcrLine>, badges: List<com.bobassist.phase0.herotier.HeroBadge>) {
        val annotated = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val linePaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f }
        val matchPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 6f }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; textSize = 40f }
        for (l in lines) drawBox(canvas, l.box, linePaint)
        for (b in badges) {
            drawBox(canvas, b.box, matchPaint)
            canvas.drawText("${b.tier.name} ${b.cardId}", b.box.left.toFloat(), (b.box.top - 10).toFloat(), labelPaint)
        }
        val base = file.substringBeforeLast('.')
        runCatching {
            FileOutputStream(File(outDir, "$base.$engine.png")).use { annotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.e(TAG, "write annotated failed: ${it.message}") }
        annotated.recycle()
    }

    private fun drawBox(canvas: Canvas, b: BoxPx, paint: Paint) =
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)

    companion object {
        private const val TAG = "OcrProbe"
    }
}
