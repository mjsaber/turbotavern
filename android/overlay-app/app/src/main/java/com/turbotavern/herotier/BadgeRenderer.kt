package com.turbotavern.herotier

/** Renders the current badge set (or clears it). Seam so the coordinator is testable with a fake. */
interface BadgeRenderer {
    fun render(badges: List<HeroBadge>, transform: Transform)
    fun clear()
}

/** Production renderer: applies [BadgeLayout] (capture->screen) and drives [TierOverlay]. */
class OverlayBadgeRenderer(
    private val overlay: TierOverlay,
    private val badgePx: Int,
    private val gapPx: Int,
) : BadgeRenderer {
    override fun render(badges: List<HeroBadge>, transform: Transform) =
        overlay.show(badges) { BadgeLayout.place(it.box, transform, badgePx, gapPx) }

    override fun clear() = overlay.clear()
}
