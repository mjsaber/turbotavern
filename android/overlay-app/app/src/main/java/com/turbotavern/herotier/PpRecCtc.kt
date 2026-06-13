package com.turbotavern.herotier

/**
 * Pure CTC greedy decoder for PP-OCRv5 rec output — exact port of rapidocr's `CTCLabelDecode`
 * (Apache-2.0). The character table is `["blank"] + dict + [" "]`: blank at index 0, an ASCII space
 * appended last. Build it with [charTableFromDict]; the on-device loader reads `ppocrv5_dict.txt`.
 *
 * Decode per image: argmax over the class axis at each timestep, **collapse consecutive equal
 * indices** (keep the first of each run — a blank between two equal chars is a valid separator),
 * then **drop blanks (index 0)**; confidence = mean of the kept max-probs (0 if none).
 */
class PpRecCtc(private val character: List<String>) {

    /** `logits[t]` = class scores at timestep t (already softmaxed by the rec head). */
    fun decode(logits: Array<FloatArray>): Result =
        decodeArgmax(logits.size) { t ->
            val row = logits[t]; var best = 0; var bestV = row[0]
            for (c in 1 until row.size) if (row[c] > bestV) { bestV = row[c]; best = c }
            best.toLong() shl 32 or (bestV.toRawBits().toLong() and 0xffffffffL)
        }

    /** Flat rec output: `flat` is row-major `[timeSteps][classes]` (ORT FloatBuffer, no [T][C] alloc). */
    fun decode(flat: FloatArray, timeSteps: Int, classes: Int): Result =
        decode(flat, 0, timeSteps, classes)

    /** Decode one image at `base` within a **batched** rec output `[N][timeSteps][classes]`. */
    fun decode(flat: FloatArray, base: Int, timeSteps: Int, classes: Int): Result =
        decodeArgmax(timeSteps) { t ->
            val off = base + t * classes; var best = 0; var bestV = flat[off]
            for (c in 1 until classes) if (flat[off + c] > bestV) { bestV = flat[off + c]; best = c }
            best.toLong() shl 32 or (bestV.toRawBits().toLong() and 0xffffffffL)
        }

    /** [argmaxAt] returns, per timestep, the packed `(index<<32 | floatBits(maxProb))`. */
    private inline fun decodeArgmax(timeSteps: Int, argmaxAt: (Int) -> Long): Result {
        val sb = StringBuilder()
        var confSum = 0f
        var kept = 0
        var prev = -1
        for (t in 0 until timeSteps) {
            val packed = argmaxAt(t)
            val best = (packed ushr 32).toInt()
            if (best != prev) {                       // first index of a run (repeats collapsed)
                if (best != BLANK) {                  // blank is a separator, never emitted
                    sb.append(character[best])
                    confSum += Float.fromBits(packed.toInt())
                    kept++
                }
                prev = best
            }
        }
        return Result(sb.toString(), if (kept == 0) 0f else confSum / kept)
    }

    data class Result(val text: String, val confidence: Float)

    companion object {
        const val BLANK = 0

        /** `dict` = the raw lines of ppocrv5_dict.txt (18,383 entries), in order. */
        fun charTableFromDict(dict: List<String>): List<String> =
            buildList(dict.size + 2) { add("blank"); addAll(dict); add(" ") }
    }
}
