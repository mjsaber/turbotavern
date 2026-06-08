package com.bobassist.phase0.herotier

import kotlin.math.roundToInt

/**
 * Simplified DBNet post-process (plan §Stage-2 option A): the DB probability map already produces one
 * connected blob per text *line*, so for axis-aligned hero-name UI text we take **connected components
 * → axis-aligned bbox** instead of cv2 findContours + minAreaRect + pyclipper unclip. Keeps the
 * validated thresholds (binarize > [thresh] 0.3, mean-prob box score ≥ [boxThresh] 0.5) and rapidocr's
 * bitmap→source coordinate scaling; "unclip" is emulated by a size-proportional pad ([unclipFrac]).
 *
 * Boxes are returned in **capture-bitmap pixels** (dstW×dstH), sorted top→bottom then left→right.
 */
class PpDetPost(
    private val thresh: Float = 0.3f,
    private val boxThresh: Float = 0.5f,
    private val minSize: Int = 3,
    private val unclipFrac: Float = 0.2f,
) {
    /** [prob] = DB output, row-major length mapW*mapH. Returns name-line boxes scaled to dst px. */
    fun boxes(prob: FloatArray, mapW: Int, mapH: Int, dstW: Int, dstH: Int): List<BoxPx> {
        val n = mapW * mapH
        val on = BooleanArray(n) { prob[it] > thresh }
        val seen = BooleanArray(n)
        val sx = dstW.toFloat() / mapW
        val sy = dstH.toFloat() / mapH
        val out = ArrayList<BoxPx>()
        val stack = ArrayDeque<Int>()
        for (s in 0 until n) {
            if (!on[s] || seen[s]) continue
            var minX = mapW; var minY = mapH; var maxX = -1; var maxY = -1
            stack.addLast(s); seen[s] = true
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                val x = p % mapW; val y = p / mapW
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                var dy = -1
                while (dy <= 1) {
                    var dx = -1
                    while (dx <= 1) {
                        if (dx != 0 || dy != 0) {
                            val nx = x + dx; val ny = y + dy
                            if (nx in 0 until mapW && ny in 0 until mapH) {
                                val q = ny * mapW + nx
                                if (on[q] && !seen[q]) { seen[q] = true; stack.addLast(q) }
                            }
                        }
                        dx++
                    }
                    dy++
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (minOf(bw, bh) < minSize) continue
            // box score = mean prob over the bbox (rapidocr box_score_fast, axis-aligned)
            var sum = 0f
            for (yy in minY..maxY) { val base = yy * mapW; for (xx in minX..maxX) sum += prob[base + xx] }
            if (sum / (bw * bh) < boxThresh) continue
            // unclip: pad proportional to size, in map space, then scale to dst px
            val px = (bw * unclipFrac).roundToInt(); val py = (bh * unclipFrac).roundToInt()
            val l = ((minX - px) * sx).roundToInt().coerceIn(0, dstW)
            val t = ((minY - py) * sy).roundToInt().coerceIn(0, dstH)
            val r = ((maxX + 1 + px) * sx).roundToInt().coerceIn(0, dstW)
            val b = ((maxY + 1 + py) * sy).roundToInt().coerceIn(0, dstH)
            if (r > l && b > t) out.add(BoxPx(l, t, r, b))
        }
        out.sortWith(compareBy({ it.top }, { it.left }))
        return out
    }
}
