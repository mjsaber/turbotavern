package com.turbotavern.herotier

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeLayoutTest {
    @Test fun centersAboveNameBox() {
        val t = Transform(scaleX = 2f, scaleY = 2f, offsetX = 0, offsetY = 10)
        val nameBox = BoxPx(100, 200, 160, 224)                 // capture px (w=60,h=24)
        val r = BadgeLayout.place(nameBox, t, badgePx = 40, gapPx = 6)
        // screen name box: x[200,320], y top 410; centerX 260; badge 40 wide -> [240,280]
        assertEquals(240, r.left); assertEquals(280, r.right)
        // top = screenTop(410) - gap(6) - badge(40) = 364
        assertEquals(364, r.top); assertEquals(404, r.bottom)
    }

    @Test fun unitScaleNoOffset() {
        val r = BadgeLayout.place(BoxPx(0, 100, 50, 120), Transform(1f, 1f, 0, 0), 20, 4)
        assertEquals(76, r.top)                                 // 100 - 4 - 20
        assertEquals(96, r.bottom)
        assertEquals(25, r.centerX)                             // (0..50) center
    }

    @Test fun anisotropicScaleAndOffset() {                     // scaleX != scaleY + both offsets
        val t = Transform(scaleX = 1.5f, scaleY = 3f, offsetX = 5, offsetY = 7)
        val r = BadgeLayout.place(BoxPx(10, 20, 30, 24), t, badgePx = 10, gapPx = 2)
        // left 10*1.5+5=20, right 30*1.5+5=50, top 20*3+7=67, cx=35
        // bTop = 67-2-10=55 ; badge [30,40] x [55,65]
        assertEquals(30, r.left); assertEquals(40, r.right)
        assertEquals(55, r.top); assertEquals(65, r.bottom)
    }
}
