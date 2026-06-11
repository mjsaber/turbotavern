package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.BadgeLayout
import com.bobassist.phase0.herotier.BoxPx
import com.bobassist.phase0.herotier.Tier
import com.bobassist.phase0.herotier.Transform

/** One trinket's on-screen render model: a tier badge above the name + a highlight ring on the best. */
data class TrinketBadgeView(
    val cardId: String,
    val tier: Tier,
    val isBest: Boolean,
    val tierBadge: BoxPx,        // small S/A/B/C badge, centered above the name (screen px)
    val highlight: BoxPx?,       // ring around the recommended trinket's name; null unless isBest
)

/**
 * Pure capture->screen geometry for the trinket overlay (UX: highlight the best + an S/A/B/C badge on
 * every offered trinket). Reuses the hero [BadgeLayout] for the tier badge so badges line up identically
 * to heroes; adds the highlight rect = the name box mapped to screen, inflated by [inflatePx] on each
 * side so the ring sits just outside the text. Per-axis scale+offset only (rotation handled upstream).
 */
object TrinketBadgeLayout {

    fun highlight(box: BoxPx, t: Transform, inflatePx: Int): BoxPx {
        val left = (box.left * t.scaleX).toInt() + t.offsetX
        val right = (box.right * t.scaleX).toInt() + t.offsetX
        val top = (box.top * t.scaleY).toInt() + t.offsetY
        val bottom = (box.bottom * t.scaleY).toInt() + t.offsetY
        return BoxPx(left - inflatePx, top - inflatePx, right + inflatePx, bottom + inflatePx)
    }

    fun layout(
        recs: List<TrinketRecommendation>,
        t: Transform,
        badgePx: Int,
        gapPx: Int,
        inflatePx: Int,
    ): List<TrinketBadgeView> = recs.map { r ->
        TrinketBadgeView(
            cardId = r.match.entry.cardId,
            tier = r.match.entry.tier,
            isBest = r.isBest,
            tierBadge = BadgeLayout.place(r.match.box, t, badgePx, gapPx),
            highlight = if (r.isBest) highlight(r.match.box, t, inflatePx) else null,
        )
    }
}
