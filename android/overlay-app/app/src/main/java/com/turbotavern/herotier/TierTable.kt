package com.turbotavern.herotier

import org.json.JSONObject

/**
 * Bundled hero-tier lookup, keyed by [NameKey]-normalized name in any locale.
 *
 * Ambiguity-safe: if a normalized key maps to more than one cardId, [lookup] returns null —
 * a missing badge is acceptable, a wrong badge is not (spec §7). [keys] still exposes ambiguous
 * keys so the fuzzy matcher can see them without ever resolving them.
 */
class TierTable private constructor(
    private val byName: Map<String, HeroTier?>,
    private val byFolded: Map<String, HeroTier?>,
) {

    /** null when the key is ambiguous (multiple cardIds) or absent. */
    fun lookup(nameKey: String): HeroTier? = byName[nameKey]

    fun keys(): Set<String> = byName.keys

    /**
     * Lookup after folding OCR-emitted separator/decorative marks (·/‧/・…). Ambiguity-safe over the
     * COMPLETE, identity-inclusive fold view: returns null if the folded form maps to >1 hero, INCLUDING
     * a collision with a mark-less hero's canonical name (e.g. table holds both `foo‧bar` and `foobar`,
     * so OCR `foo·bar` is ambiguous, not a badge for `foobar`). Missing beats wrong (spec §7).
     */
    fun lookupFolded(nameKey: String): HeroTier? = byFolded[foldSeparators(nameKey)]

    /** Folded key space (marks removed) for the matcher's fuzzy pass; resolve hits via [lookupFolded]. */
    fun foldedKeys(): Set<String> = byFolded.keys

    val size get() = byName.size

    companion object {
        /**
         * Name separators / decorative marks that PP-OCRv5 cannot reliably emit, so the real OCR line
         * drops them. The headliner is U+2027 (‧ HYPHENATION POINT) — the zhTW name separator in 26/113
         * names, ABSENT from ppocrv5_dict.txt, so the rec head can never output it. We also fold the
         * interchangeable middots (·/・) and the decorative epithet brackets (『』「」《》〈〉) that OCR
         * misreads. Folding is done as an ALIAS (see below), never in NameKey (which must keep byte-parity
         * with the data-pipeline namekey_vectors), and never overrides a canonical key.
         */
        private val FOLD_MARKS = "‧·・•『』「」《》〈〉".toSet()

        /** Drop fold-marks from a NameKey, then re-collapse whitespace the marks may have bracketed. */
        internal fun foldSeparators(nameKey: String): String =
            nameKey.filterNot { it in FOLD_MARKS }.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        fun fromJson(json: String): TierTable {
            val acc = HashMap<String, MutableSet<String>>()        // canonical key -> distinct cardIds
            val foldAcc = HashMap<String, MutableSet<String>>()    // folded key (IDENTITY-INCLUSIVE) -> cardIds
            val tierOf = HashMap<String, HeroTier>()               // cardId -> HeroTier
            val heroes = JSONObject(json).getJSONArray("heroes")
            for (i in 0 until heroes.length()) {
                val h = heroes.getJSONObject(i)
                val cid = h.getString("cardId")
                require(cid !in tierOf) { "duplicate cardId in tier asset: $cid" }
                tierOf[cid] = HeroTier(cid, Tier.valueOf(h.getString("tier")))
                val names = h.getJSONObject("names")
                for (loc in names.keys()) {
                    val k = NameKey.of(names.getString(loc))
                    if (k.isEmpty()) continue
                    acc.getOrPut(k) { HashSet() }.add(cid)
                    // Identity-inclusive: a mark-LESS name folds to itself and still contributes here, so
                    // a fold collision with such a name is detected as ambiguous (missing beats wrong).
                    val folded = foldSeparators(k)
                    if (folded.isNotEmpty()) foldAcc.getOrPut(folded) { HashSet() }.add(cid)
                }
            }
            val resolved = HashMap<String, HeroTier?>()
            for ((k, cids) in acc) resolved[k] = if (cids.size == 1) tierOf[cids.first()] else null
            // Folded view: every name's separator-folded form -> tier, null on ANY collision (including a
            // mark-less canonical that equals another hero's folded name). Reached only via lookupFolded.
            val resolvedFold = HashMap<String, HeroTier?>()
            for ((k, cids) in foldAcc) resolvedFold[k] = if (cids.size == 1) tierOf[cids.first()] else null
            return TierTable(resolved, resolvedFold)
        }
    }
}
