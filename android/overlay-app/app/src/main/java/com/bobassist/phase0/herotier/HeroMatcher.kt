package com.bobassist.phase0.herotier

/**
 * Maps recognized OCR lines to hero badges (spec §7.2):
 *  - exact normalized hit -> accept (ambiguity already handled by [TierTable]);
 *  - short normalized keys (<= [shortLen]) -> exact-only (fuzzy on tiny strings is unsafe);
 *  - else fuzzy: bounded Levenshtein; accept the single best key only if it is within `cap` AND
 *    clear of the runner-up by >= [ambigMargin] (a missing badge beats a wrong one).
 * Dedup keeps the first box per cardId.
 */
class HeroMatcher(
    private val table: TierTable,
    private val shortLen: Int = 3,
    private val fuzzyCap: Int = 2,
    private val fuzzyRatio: Double = 0.2,
    private val ambigMargin: Int = 2,
) {
    fun match(lines: List<OcrLine>): List<HeroBadge> {
        val out = LinkedHashMap<String, HeroBadge>()
        for (ln in lines) {
            val k = NameKey.of(ln.text)
            if (k.isEmpty()) continue
            val ht = resolve(k) ?: continue
            out.getOrPut(ht.cardId) { HeroBadge(ht.cardId, ht.tier, ln.box) }
        }
        return out.values.toList()
    }

    private fun resolve(k: String): HeroTier? {
        table.lookup(k)?.let { return it }                 // exact (ambiguity-safe)
        if (k.length <= shortLen) return null              // short -> exact-only
        val cap = minOf(fuzzyCap, Math.floor(fuzzyRatio * k.length).toInt())
        if (cap <= 0) return null
        var b1 = Int.MAX_VALUE
        var b2 = Int.MAX_VALUE
        var best: String? = null
        for (key in table.keys()) {
            val d = Levenshtein.distance(k, key, cap)
            if (d < b1) { b2 = b1; b1 = d; best = key } else if (d < b2) b2 = d
        }
        if (best != null && b1 <= cap && (b2 - b1) >= ambigMargin) return table.lookup(best)
        return null                                        // table.lookup(best) is null if that key is ambiguous
    }
}
