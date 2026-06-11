package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.NameKey
import com.bobassist.phase0.herotier.Tier
import com.bobassist.phase0.herotier.TierTable
import org.json.JSONObject

/**
 * Bundled trinket-tier lookup, keyed by [NameKey]-normalized name in any locale, mirroring the hero
 * [TierTable] (same separator-folded aliases for un-OCR-able marks like U+2027, same ambiguity-safety).
 *
 * One difference: a lesser and a greater trinket can share a localized base name. That key is ambiguous
 * GLOBALLY, but the offer screen always shows one class at a time, so [lookup] takes an optional class
 * hint and resolves within it — a shared name is unambiguous once you know the turn is lesser vs greater.
 * With no hint (or a hint that still leaves >1 candidate) it stays null: a missing badge beats a wrong one.
 */
class TrinketTable private constructor(private val byName: Map<String, List<TrinketEntry>>) {

    /** Resolve a normalized key, disambiguated by the current offer's class when known. */
    fun lookup(nameKey: String, classHint: TrinketClass? = null): TrinketEntry? {
        val all = byName[nameKey] ?: return null
        val scoped = if (classHint != null) all.filter { it.trinketClass == classHint } else all
        // Distinct cardIds only: the SAME entry indexed under both its canonical and folded key is fine.
        val distinct = scoped.distinctBy { it.cardId }
        return if (distinct.size == 1) distinct.first() else null
    }

    fun keys(): Set<String> = byName.keys

    val size get() = byName.size

    companion object {
        fun fromJson(json: String): TrinketTable {
            val acc = HashMap<String, MutableList<TrinketEntry>>()
            val seen = HashSet<String>()
            val arr = JSONObject(json).getJSONArray("trinkets")
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val cid = t.getString("cardId")
                require(seen.add(cid)) { "duplicate cardId in trinket asset: $cid" }
                val entry = TrinketEntry(
                    cardId = cid,
                    trinketClass = TrinketClass.parse(t.getString("trinketClass")),
                    tier = Tier.valueOf(t.getString("tier")),
                    avgPlacement = t.getDouble("avgPlacement"),
                )
                val names = t.getJSONObject("names")
                for (loc in names.keys()) {
                    val k = NameKey.of(names.getString(loc))
                    if (k.isEmpty()) continue
                    acc.getOrPut(k) { ArrayList() }.add(entry)
                    val folded = TierTable.foldSeparators(k)
                    if (folded.isNotEmpty() && folded != k) acc.getOrPut(folded) { ArrayList() }.add(entry)
                }
            }
            return TrinketTable(acc)
        }
    }
}
