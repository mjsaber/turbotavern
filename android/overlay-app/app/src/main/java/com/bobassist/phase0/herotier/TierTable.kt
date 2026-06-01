package com.bobassist.phase0.herotier

import org.json.JSONObject

/**
 * Bundled hero-tier lookup, keyed by [NameKey]-normalized name in any locale.
 *
 * Ambiguity-safe: if a normalized key maps to more than one cardId, [lookup] returns null —
 * a missing badge is acceptable, a wrong badge is not (spec §7). [keys] still exposes ambiguous
 * keys so the fuzzy matcher can see them without ever resolving them.
 */
class TierTable private constructor(private val byName: Map<String, HeroTier?>) {

    /** null when the key is ambiguous (multiple cardIds) or absent. */
    fun lookup(nameKey: String): HeroTier? = byName[nameKey]

    fun keys(): Set<String> = byName.keys

    val size get() = byName.size

    companion object {
        fun fromJson(json: String): TierTable {
            val acc = HashMap<String, MutableSet<String>>()        // key -> distinct cardIds
            val tierOf = HashMap<String, HeroTier>()               // cardId -> HeroTier
            val heroes = JSONObject(json).getJSONArray("heroes")
            for (i in 0 until heroes.length()) {
                val h = heroes.getJSONObject(i)
                val cid = h.getString("cardId")
                tierOf[cid] = HeroTier(cid, Tier.valueOf(h.getString("tier")))
                val names = h.getJSONObject("names")
                for (loc in names.keys()) {
                    val k = NameKey.of(names.getString(loc))
                    if (k.isNotEmpty()) acc.getOrPut(k) { HashSet() }.add(cid)
                }
            }
            val resolved = HashMap<String, HeroTier?>()
            for ((k, cids) in acc) resolved[k] = if (cids.size == 1) tierOf[cids.first()] else null
            return TierTable(resolved)
        }
    }
}
