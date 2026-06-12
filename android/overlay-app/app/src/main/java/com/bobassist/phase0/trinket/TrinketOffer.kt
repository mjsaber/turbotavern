package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.OcrLine

/**
 * Resolves a trinket offer to its recommendations, inferring the offer's class so shared lesser/greater
 * base names still resolve.
 *
 * A trinket-offer screen shows ONE class at a time, but the OCR lines don't say which. 23 of 209 names
 * are SHARED (a lesser and a greater trinket use the same name), so a no-hint match would drop them. We
 * instead match the lines under EACH class and take the one that resolves strictly more names: a real
 * one-class offer resolves all of its names under its own class and only the shared ones under the
 * other. Then [TrinketRecommender] ranks the offered set.
 *
 * SAFETY: when both classes resolve the SAME count (an offer made ENTIRELY of shared names, ~rare) the
 * class is genuinely undeterminable from the names alone — and a lesser vs greater trinket has different
 * tiers and avg-placements, so guessing would risk a WRONG badge. We return no recommendation (a missing
 * badge beats a wrong one). Resolving an all-shared offer correctly needs a class signal from the screen
 * (a "lesser/greater" label or the turn number) — a future on-device enhancement.
 */
object TrinketOffer {

    fun resolve(matcher: TrinketMatcher, lines: List<OcrLine>): List<TrinketRecommendation> {
        if (lines.isEmpty()) return emptyList()
        val asLesser = matcher.match(lines, classHint = TrinketClass.LESSER)
        val asGreater = matcher.match(lines, classHint = TrinketClass.GREATER)
        val chosen = when {
            asLesser.size > asGreater.size -> asLesser
            asGreater.size > asLesser.size -> asGreater
            else -> return emptyList()   // tie (all-shared or all-zero) -> class undeterminable -> safe no-rec
        }
        return TrinketRecommender.rank(chosen)
    }

    /** Count of trinket-dictionary matches on this frame (under whichever class resolves more) — a
     *  class-agnostic "this is a trinket screen" signal for the visual-probe gate. */
    fun matchCount(matcher: TrinketMatcher, lines: List<OcrLine>): Int =
        maxOf(matcher.match(lines, TrinketClass.LESSER).size, matcher.match(lines, TrinketClass.GREATER).size)
}
