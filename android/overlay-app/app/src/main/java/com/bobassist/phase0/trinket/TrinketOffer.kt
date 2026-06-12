package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.OcrLine

/**
 * Resolves a trinket offer to its recommendations, inferring the offer's class so shared lesser/greater
 * base names still resolve.
 *
 * A trinket-offer screen shows ONE class at a time, but the OCR lines don't say which. We first match
 * with no class hint — every UNIQUELY-named trinket resolves and reveals the offer's class. If that
 * class is unambiguous we re-match with it as the hint, which additionally resolves any shared-name
 * trinket (a name a lesser and a greater both use). Then [TrinketRecommender] ranks the offered set.
 */
object TrinketOffer {

    fun resolve(matcher: TrinketMatcher, lines: List<OcrLine>): List<TrinketRecommendation> {
        val firstPass = matcher.match(lines, classHint = null)
        val inferred = firstPass.map { it.entry.trinketClass }.toSet().singleOrNull()
        val matches = if (inferred == null) firstPass else matcher.match(lines, classHint = inferred)
        return TrinketRecommender.rank(matches)
    }

    /** Count of trinket-dictionary matches on this frame — feeds the visual-probe gate. */
    fun matchCount(matcher: TrinketMatcher, lines: List<OcrLine>): Int =
        matcher.match(lines, classHint = null).size
}
