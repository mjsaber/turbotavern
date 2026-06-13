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
        //    ONLY exact matches (exactOrFolded is ambiguity-safe) so a spurious concat is never a badge.
        //    Folded too, so a wrapped zhTW name whose OCR emitted a middot (米歐菲瑟·曼納斯頓) still hits.
        if (verticalMerge) {
            for (m in verticalMerges(lines)) {
                val ht = exactOrFolded(NameKey.of(m.text)) ?: continue
                out.getOrPut(ht.cardId) { HeroBadge(ht.cardId, ht.tier, m.box) }
            }
        }
        return out.values.toList()
    }

    /** Exact canonical lookup, then the ambiguity-safe folded lookup (drops OCR-emitted ·/‧/・…).
     *  Even an exact canonical hit is downgraded to "no badge" when its folded form is ambiguous: OCR
     *  drops separators, so a markless key (e.g. `foobar`) is indistinguishable from another hero's
     *  separator name folded (`foo‧bar`). lookupFolded==null on a key we just matched exactly means the
     *  folded form maps to >1 hero -> drop. Never a wrong badge (missing beats wrong). */
    private fun exactOrFolded(nameKey: String): HeroTier? {
        table.lookup(nameKey)?.let { return if (table.lookupFolded(nameKey) == null) null else it }
        return table.lookupFolded(nameKey)
    }

    private fun verticalMerges(lines: List<OcrLine>): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (top in lines) for (bot in lines) {
            if (top === bot) continue
            if (!stacked(top.box, bot.box)) continue
            // Only merge wrapped FRAGMENTS: if either line already resolves to a hero on its own it
            // is a complete name, not a fragment — merging it would only risk a spurious key. Use the
            // SAME exact+folded resolution as the lookups (a line complete only via a folded alias,
            // e.g. OCR emitted 凱瑞爾羅姆 for 凱瑞爾‧羅姆, must also count as complete, not a fragment).
            if (exactOrFolded(NameKey.of(top.text)) != null || exactOrFolded(NameKey.of(bot.text)) != null) continue
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
        exactOrFolded(k)?.let { return it }                // exact, then exact via folded alias — unconditional
        // OCR-emitted separator/decorative marks that the table folds into aliases (·/‧/・…): fuzzy on the
        // FOLDED OCR key too. The table assumes the rec head DROPS these marks — true for U+2027 (absent
        // from ppocrv5_dict) but NOT for U+00B7 (·), which IS in the dict, so a name like 凱瑞爾·羅姆
        // reaches OCR WITH the middot and would otherwise spend an edit on the mark, blowing the
        // length-scaled cap when combined with a 1-char OCR slip (姆→婭). Folding makes the mark free.
        val fk = TierTable.foldSeparators(k).ifEmpty { k } // fuzzy on the folded key (marks don't count)
        if (fk.length <= shortLen) return null             // short -> exact-only
        // Below the confidence floor, do not let a shaky read fuzzy-match into a real hero.
        if (minFuzzyConfidence != null && confidence != null && confidence < minFuzzyConfidence) return null
        // Tolerance scales with name length (not a fixed 1): floor(ratio*len), bounded by fuzzyCap.
        // A single OCR slip (勾/匀, dropped 爾, 復/複) is one edit; longer names absorb proportionally
        // more. Budget from the ORIGINAL read length k (NOT the folded fk): folding a separator must not
        // shrink the cap and starve a "separator + one OCR slip" read (e.g. 凱瑞·羅姆 -> fold 凱瑞羅姆).
        val cap = minOf(fuzzyCap, Math.floor(fuzzyRatio * k.length).toInt())
        if (cap <= 0) return null
        // Compare in the FOLDED key space (marks already gone on both sides). Collect the best distance
        // PER DISTINCT hero, so a hero's multiple folded locale keys (e.g. zhCN 希瓦娜斯风行者 + zhTW
        // 希瓦娜斯風行者, both -> one cardId) don't count as competing heroes and trip the ambiguity guard.
        // A folded key that is itself ambiguous (maps to >1 hero) is a real competitor -> tracked separately.
        val perCard = HashMap<String, Pair<Int, HeroTier>>()  // cardId -> (best distance, tier)
        var ambigBest = Int.MAX_VALUE                          // best distance to a key that maps to >1 hero
        for (key in table.foldedKeys()) {
            val d = Levenshtein.distance(fk, key, cap)
            if (d > cap) continue
            val ht = table.lookupFolded(key)
            if (ht == null) { ambigBest = minOf(ambigBest, d); continue }
            val prev = perCard[ht.cardId]
            if (prev == null || d < prev.first) perCard[ht.cardId] = d to ht
        }
        if (perCard.isEmpty()) return null
        val winner = perCard.values.minByOrNull { it.first }!!
        val b1 = winner.first
        var b2 = ambigBest                                     // runner-up = nearest DIFFERENT hero or ambiguous key
        for (v in perCard.values) if (v !== winner) b2 = minOf(b2, v.first)
        // Accept the unique best within tolerance. Levenshtein saturates the runner-up at cap+1, so
        // "(b2 - b1) >= ambigMargin" can never hold when cap == 1; "b2 > cap" (only ONE hero within
        // tolerance) is the cap-independent ambiguity guard that also makes the 1-edit budget usable.
        return if (b1 <= cap && (b2 > cap || (b2 - b1) >= ambigMargin)) winner.second else null
    }
}
