package com.bobassist.phase0.herotier

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
    fun decode(logits: Array<FloatArray>): Result {
        val sb = StringBuilder()
        var confSum = 0f
        var kept = 0
        var prev = -1
        for (row in logits) {
            var best = 0
            var bestV = row[0]
            for (c in 1 until row.size) if (row[c] > bestV) { bestV = row[c]; best = c }
            if (best != prev) {                       // first index of a run (repeats collapsed)
                if (best != BLANK) {                  // blank is a separator, never emitted
                    sb.append(character[best])
                    confSum += bestV
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
