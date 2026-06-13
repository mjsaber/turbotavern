package com.turbotavern.trinket

import com.turbotavern.herotier.Levenshtein
import com.turbotavern.herotier.NameKey
import com.turbotavern.herotier.OcrLine

/**
 * Maps recognized OCR lines on a trinket-offer screen to trinkets, mirroring the hero matcher
 * (exact -> short-exact -> bounded fuzzy with an ambiguity guard; a missing badge beats a wrong one).
 *
 * The one addition is [classHint]: the offer screen shows ONLY lesser or ONLY greater at a time, so the
 * caller passes which, letting a localized name shared by a lesser and a greater resolve unambiguously.
 */
class TrinketMatcher(
    private val table: TrinketTable,
    private val shortLen: Int = 3,
    private val fuzzyCap: Int = 2,
    private val fuzzyRatio: Double = 0.2,
    private val ambigMargin: Int = 2,
) {
    fun match(lines: List<OcrLine>, classHint: TrinketClass? = null): List<TrinketMatch> {
        val out = LinkedHashMap<String, TrinketMatch>()
        for (ln in lines) {
            val k = NameKey.of(stripEdgeNoise(ln.text))
            if (k.isEmpty()) continue
            val e = resolve(k, classHint) ?: continue
            out.getOrPut(e.cardId) { TrinketMatch(e, ln.box) }
        }
        return out.values.toList()
    }

    /** Same card-frame stroke trim as the hero matcher (leading/trailing vertical bars + whitespace). */
    private fun stripEdgeNoise(s: String) = s.trim { it == '|' || it == '丨' || it == '｜' || it.isWhitespace() }

    private fun resolve(k: String, classHint: TrinketClass?): TrinketEntry? {
        table.lookup(k, classHint)?.let { return it }     // exact (ambiguity-safe, class-scoped)
        if (k.length <= shortLen) return null             // short -> exact-only
        val cap = minOf(fuzzyCap, Math.floor(fuzzyRatio * k.length).toInt())
        if (cap <= 0) return null
        var b1 = Int.MAX_VALUE
        var b2 = Int.MAX_VALUE
        var best: String? = null
        for (key in table.keys()) {
            val d = Levenshtein.distance(k, key, cap)
            if (d < b1) { b2 = b1; b1 = d; best = key } else if (d < b2) b2 = d
        }
        if (best != null && b1 <= cap && (b2 > cap || (b2 - b1) >= ambigMargin)) return table.lookup(best, classHint)
        return null
    }
}
