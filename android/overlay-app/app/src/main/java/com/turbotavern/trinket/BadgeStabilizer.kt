package com.turbotavern.trinket

/**
 * Temporal stabilizer that holds an item across up to [maxMisses] CONSECUTIVE absent frames, so a
 * transient per-frame OCR miss does not blink a badge off and back on (the live hero 4↔3 flicker:
 * a different hero drops on different frames; the renderer rebuilds every frame, so a single miss
 * removes that badge for that frame). Render the recently-seen UNION instead of the bare current frame.
 *
 * Pure + deterministic; keyed by [keyOf]. Insertion order is preserved (a held item keeps its slot).
 * NOT thread-safe: confine all calls to one thread (the coordinator's handler thread); the list it
 * returns is a fresh snapshot, safe to hand to the main thread for rendering.
 *
 * A re-seen item resets its miss counter to 0 and updates to the FRESH value (its current box); a
 * held-but-missed item keeps its LAST-SEEN value until it ages out at miss `maxMisses + 1`.
 *
 * Built for the hero overlay, whose select set is fixed for the whole window. Deliberately NOT applied
 * to trinkets — the trinket shop can change its offer set, where holding a union would briefly show
 * stale + new offers together. See docs/proposals/2026-06-13-rating-reliability.md.
 */
class BadgeStabilizer<T>(
    private val keyOf: (T) -> String,
    private val maxMisses: Int = 2,
) {
    private class Entry<T>(var item: T, var misses: Int)
    private val held = LinkedHashMap<String, Entry<T>>()

    /** Feed this frame's matches; returns the set to render (seen ∪ recently-held), in stable order. */
    fun update(current: List<T>): List<T> {
        val seen = HashSet<String>(current.size * 2)
        for (item in current) {
            val k = keyOf(item)
            seen.add(k)
            val e = held[k]
            if (e == null) held[k] = Entry(item, 0) else { e.item = item; e.misses = 0 }  // refresh box, reset misses; keep slot
        }
        val it = held.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (keyOf(e.item) !in seen) {
                e.misses++
                if (e.misses > maxMisses) it.remove()
            }
        }
        return held.values.map { it.item }
    }

    fun reset() = held.clear()
}
