package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.BadgeLayout
import com.bobassist.phase0.herotier.BoxPx
import com.bobassist.phase0.herotier.Tier
import com.bobassist.phase0.herotier.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrinketBadgeLayoutTest {

    private val t = Transform(scaleX = 2f, scaleY = 2f, offsetX = 100, offsetY = 50)
    private fun rec(cid: String, box: BoxPx, best: Boolean, avg: Double = 4.0) =
        TrinketRecommendation(
            TrinketMatch(TrinketEntry(cid, TrinketClass.LESSER, Tier.A, avg), box),
            rank = if (best) 1 else 2, isBest = best,
        )

    @Test fun highlightInflatesTheMappedBox() {
        val box = BoxPx(10, 20, 30, 40)
        val h = TrinketBadgeLayout.highlight(box, t, inflatePx = 5)
        // mapped: left=10*2+100=120, top=20*2+50=90, right=30*2+100=160, bottom=40*2+50=130; inflate 5
        assertEquals(BoxPx(115, 85, 165, 135), h)
    }

    @Test fun onlyTheBestGetsAHighlight() {
        val views = TrinketBadgeLayout.layout(
            listOf(rec("BEST", BoxPx(10, 20, 30, 40), best = true),
                   rec("OTHER", BoxPx(50, 20, 70, 40), best = false)),
            t, badgePx = 64, gapPx = 10, inflatePx = 5,
        )
        val best = views.single { it.isBest }
        val other = views.single { !it.isBest }
        assertNotNull("best trinket has a highlight ring", best.highlight)
        assertNull("non-best trinket has no highlight", other.highlight)
    }

    @Test fun everyTrinketGetsATierBadgeMatchingHeroPlacement() {
        val box = BoxPx(10, 20, 30, 40)
        val views = TrinketBadgeLayout.layout(listOf(rec("X", box, best = true)), t, 64, 10, 5)
        // the tier badge geometry is exactly the shared hero BadgeLayout (badges line up identically)
        assertEquals(BadgeLayout.place(box, t, 64, 10), views.single().tierBadge)
    }

    @Test fun preservesCardIdAndTier() {
        val views = TrinketBadgeLayout.layout(listOf(rec("CID", BoxPx(0, 0, 10, 10), best = true)), t, 64, 10, 5)
        assertEquals("CID", views.single().cardId)
        assertEquals(Tier.A, views.single().tier)
    }

    @Test fun emptyRecsEmptyLayout() {
        assertEquals(emptyList<TrinketBadgeView>(), TrinketBadgeLayout.layout(emptyList(), t, 64, 10, 5))
    }
}
