package com.turbotavern.herotier

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the box un-rotation math (the device-independent core of orientation-robust OCR). A box
 * detected on the rotated (upright) bitmap must map back to the original portrait capture coordinates
 * so the existing scale-only capture→screen transform still places the badge correctly.
 */
class OrientedOcrTest {

    // Original portrait capture buffer: 100 wide x 200 tall. Rotated 90/270 -> 200 wide x 100 tall.
    private val origW = 100
    private val origH = 200

    @Test fun deg0IsIdentity() {
        val b = BoxPx(1, 2, 3, 4)
        assertEquals(b, OrientedOcr.unrotateBox(b, 0, origW, origH))
    }

    @Test fun rotate90MapsBackToPortrait() {
        // A horizontal text box on the 200x100 landscape (90°-rotated) bitmap.
        val onRotated = BoxPx(left = 10, top = 20, right = 60, bottom = 40)
        // left=top, top=H-right, right=bottom, bottom=H-left
        assertEquals(BoxPx(20, 200 - 60, 40, 200 - 10), OrientedOcr.unrotateBox(onRotated, 90, origW, origH))
    }

    @Test fun rotate270MapsBackToPortrait() {
        val onRotated = BoxPx(left = 10, top = 20, right = 60, bottom = 40)
        // left=W-bottom, top=left, right=W-top, bottom=right
        assertEquals(BoxPx(100 - 40, 10, 100 - 20, 60), OrientedOcr.unrotateBox(onRotated, 270, origW, origH))
    }

    @Test fun mappedBoxStaysWithinOriginalBounds() {
        val onRotated = BoxPx(left = 0, top = 0, right = origH, bottom = origW)  // full rotated frame
        val m90 = OrientedOcr.unrotateBox(onRotated, 90, origW, origH)
        assertEquals(BoxPx(0, 0, origW, origH), m90)   // full rotated frame -> full original frame
    }
}
