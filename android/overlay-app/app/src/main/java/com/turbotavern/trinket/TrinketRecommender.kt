package com.turbotavern.trinket

/**
 * Ranks the trinkets ACTUALLY offered against each other. The bundled S/A/B/C tier is too coarse to
 * pick between an offer (two offered trinkets frequently share a tier), so we rank by stored
 * avgPlacement (lower is better) and highlight the single best.
 *
 * The recommendation is INTRA-offer only: it answers "which of these N is best", never an absolute
 * verdict, which is the honest framing for an auto-battler trinket choice.
 */
object TrinketRecommender {

    /**
     * @param offered the trinkets recognized on the current offer (already class-consistent — the
     *   screen shows one class at a time). Ties on avgPlacement break by cardId for determinism.
     * @return one [TrinketRecommendation] per input, rank 1..N by avgPlacement, exactly one isBest.
     *   Empty in, empty out. A single offered trinket is trivially the best.
     */
    fun rank(offered: List<TrinketMatch>): List<TrinketRecommendation> {
        if (offered.isEmpty()) return emptyList()
        val sorted = offered.sortedWith(
            compareBy({ it.entry.avgPlacement }, { it.entry.cardId }),
        )
        return sorted.mapIndexed { i, m -> TrinketRecommendation(match = m, rank = i + 1, isBest = i == 0) }
    }
}
