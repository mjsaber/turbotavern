package com.turbotavern.herotier

import org.junit.Assert.assertEquals
import org.junit.Test

class PpImageGeomTest {

    @Test fun detUpscalesShortSideThenSnapsTo32() =
        assertEquals(736 to 1120, PpImageGeom.detResizeTarget(512, 776))   // ratio 736/512

    @Test fun detNoUpscaleWhenShortSideAlready736() =
        assertEquals(736 to 992, PpImageGeom.detResizeTarget(736, 1000))   // ratio 1, snap /32

    @Test fun detResultDimsAreMultiplesOf32() {
        val (w, h) = PpImageGeom.detResizeTarget(800, 600)
        assertEquals(0, w % 32); assertEquals(0, h % 32)
    }

    @Test fun detDownscalesLargeCaptureToMaxSide() =                       // long side 2412 > 1280
        assertEquals(1280 to 576, PpImageGeom.detResizeTarget(2412, 1086))

    @Test fun recPadsToBandFor48HighCrop() =                               // ratio 2 < 320/48
        assertEquals(PpImageGeom.RecPlan(96, 320, 48), PpImageGeom.recResizePlan(96, 48))

    @Test fun recGrowsBandForVeryWideCrop() =                              // ratio 10 > 320/48
        assertEquals(PpImageGeom.RecPlan(480, 480, 48), PpImageGeom.recResizePlan(480, 48))
}
