package com.turbotavern.herotier

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
    private val verticalMerge: Boolean = true,
    /**
     * Optional floor for the FUZZY path only. A low-confidence OCR read of UI chrome can land within
     * the fuzzy budget of a real hero name and emit a wrong badge; below this floor we require an EXACT
     * match instead. null (default) = off — exact matches are always unconditional. Pick the threshold
     * from on-device probe data; landing it after the alias fixes means real names match exactly and do
     * not depend on the confidence/fuzzy interplay.
     */
    private val minFuzzyConfidence: Float? = null,
) {
    fun match(lines: List<OcrLine>): List<HeroBadge> {
        val out = LinkedHashMap<String, HeroBadge>()
        // 1) per-line (exact + short-exact + fuzzy). Originals first so their tighter box wins dedup.
        for (ln in lines) {
            val k = NameKey.of(stripEdgeNoise(ln.text))
            if (k.isEmpty()) continue
            val ht = resolve(k, ln.confidence) ?: continue
            out.getOrPut(ht.cardId) { HeroBadge(ht.cardId, ht.tier, ln.box) }
        }
        // 2) vertically-wrapped names: concatenate stacked, x-overlapping line pairs and accept
        //    ONLY exact matches (table.lookup is ambiguity-safe) so a spurious concat is never a badge.
        if (verticalMerge) {
            for (m in verticalMerges(lines)) {
                val ht = table.lookup(NameKey.of(m.text)) ?: continue
                out.getOrPut(ht.cardId) { HeroBadge(ht.cardId, ht.tier, m.box) }
            }
        }
        return out.values.toList()
    }

    private fun verticalMerges(lines: List<OcrLine>): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (top in lines) for (bot in lines) {
            if (top === bot) continue
            if (!stacked(top.box, bot.box)) continue
            // Only merge wrapped FRAGMENTS: if either line already resolves to a hero on its own it
            // is a complete name, not a fragment — merging it would only risk a spurious key.
            if (table.lookup(NameKey.of(top.text)) != null || table.lookup(NameKey.of(bot.text)) != null) continue
            val u = union(top.box, bot.box)
            out.add(OcrLine(top.text + bot.text, u))            // CJK wraps with no separator
            out.add(OcrLine(top.text + " " + bot.text, u))      // Latin wraps need a space
        }
        return out
    }

    /** True when [bot] sits directly below [top]: majority horizontal overlap + small vertical gap. */
    private fun stacked(top: BoxPx, bot: BoxPx): Boolean {
        val overlapX = minOf(top.right, bot.right) - maxOf(top.left, bot.left)
        if (overlapX <= 0.5 * minOf(top.width, bot.width)) return false
        val h = top.height
        val gap = bot.top - top.bottom
        return gap >= -h / 2 && gap <= 4 * h / 5
    }

    private fun union(a: BoxPx, b: BoxPx) =
        BoxPx(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    /** Trim card-frame strokes OCR reads as a leading/trailing vertical bar (`| 丨 ｜`) + whitespace.
     *  Edge-only and bar-only: real hero names never start/end with a bare vertical stroke, and
     *  NameKey (parity with data-pipeline) must NOT strip punctuation, so this lives here instead. */
    private fun stripEdgeNoise(s: String) = s.trim { it == '|' || it == '丨' || it == '｜' || it.isWhitespace() }

    private fun resolve(k: String, confidence: Float? = null): HeroTier? {
        table.lookup(k)?.let { return it }                 // exact (ambiguity-safe) — always unconditional
        if (k.length <= shortLen) return null              // short -> exact-only
        // Below the confidence floor, do not let a shaky read fuzzy-match into a real hero.
        if (minFuzzyConfidence != null && confidence != null && confidence < minFuzzyConfidence) return null
        // Tolerance scales with name length (not a fixed 1): floor(ratio*len), bounded by fuzzyCap.
        // A single OCR slip (勾/匀, dropped 爾, 復/複) is one edit; longer names absorb proportionally
        // more. Card-frame strokes that OCR reads as a leading "|" are stripped upstream
        // (stripEdgeNoise) so they don't eat the budget.
        val cap = minOf(fuzzyCap, Math.floor(fuzzyRatio * k.length).toInt())
        if (cap <= 0) return null
        var b1 = Int.MAX_VALUE
        var b2 = Int.MAX_VALUE
        var best: String? = null
        for (key in table.keys()) {
            val d = Levenshtein.distance(k, key, cap)
            if (d < b1) { b2 = b1; b1 = d; best = key } else if (d < b2) b2 = d
        }
        // Accept the unique best within tolerance. Levenshtein saturates the runner-up at cap+1, so
        // "(b2 - b1) >= ambigMargin" can never hold when cap == 1; "b2 > cap" (only ONE key within
        // tolerance) is the cap-independent ambiguity guard that also makes the 1-edit budget usable.
        if (best != null && b1 <= cap && (b2 > cap || (b2 - b1) >= ambigMargin)) return table.lookup(best)
        return null                                        // table.lookup(best) is null if that key is ambiguous
    }
}
