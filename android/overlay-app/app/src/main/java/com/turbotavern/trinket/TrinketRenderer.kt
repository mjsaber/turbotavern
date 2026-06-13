package com.turbotavern.trinket

import com.turbotavern.herotier.Transform

/** Renders the trinket recommendation (or clears it). Seam so the coordinator is testable with a fake. */
interface TrinketRenderer {
    fun render(recs: List<TrinketRecommendation>, transform: Transform)
    fun clear()
}

/** Production renderer: applies [TrinketBadgeLayout] (capture->screen) and drives [TrinketOverlay]. */
class OverlayTrinketRenderer(
    private val overlay: TrinketOverlay,
    private val badgePx: Int,
    private val gapPx: Int,
    private val inflatePx: Int,
) : TrinketRenderer {
    override fun render(recs: List<TrinketRecommendation>, transform: Transform) =
        overlay.show(TrinketBadgeLayout.layout(recs, transform, badgePx, gapPx, inflatePx))

    override fun clear() = overlay.clear()
}
