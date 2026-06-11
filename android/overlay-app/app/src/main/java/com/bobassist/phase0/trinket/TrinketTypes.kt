package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.BoxPx
import com.bobassist.phase0.herotier.Tier

/** Trinkets are offered on two distinct turns; lesser and greater never compete in the same offer. */
enum class TrinketClass { LESSER, GREATER;
    companion object {
        fun parse(s: String): TrinketClass = when (s.lowercase()) {
            "lesser" -> LESSER
            "greater" -> GREATER
            else -> throw IllegalArgumentException("bad trinketClass: $s")
        }
    }
}

/**
 * One trinket's bundled tier row. [avgPlacement] (lower is better) is kept alongside the coarse [tier]
 * so the 2-3 trinkets ACTUALLY offered can be ranked against each other — two offered trinkets often
 * share a tier.
 */
data class TrinketEntry(
    val cardId: String,
    val trinketClass: TrinketClass,
    val tier: Tier,
    val avgPlacement: Double,
)

/** A trinket recognized on the offer screen. [box] is in CAPTURE-bitmap pixels. */
data class TrinketMatch(val entry: TrinketEntry, val box: BoxPx)

/**
 * A recommendation within one offer. [rank] is 1-based by avgPlacement (1 = best of the offered set);
 * [isBest] marks the single trinket the overlay should highlight.
 */
data class TrinketRecommendation(val match: TrinketMatch, val rank: Int, val isBest: Boolean)
