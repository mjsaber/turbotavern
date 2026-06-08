package com.bobassist.phase0.herotier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpDetPostTest {
    private val W = 10
    private val H = 8

    private fun mask(vararg rects: Rect): FloatArray {
        val a = FloatArray(W * H)
        for (r in rects) for (y in r.y0..r.y1) for (x in r.x0..r.x1) a[y * W + x] = r.v
        return a
    }
    private data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val v: Float)

    @Test fun twoSeparatedBlobsGiveTwoBoxesSortedTopToBottom() {
        // blob1 cols1-4 rows1-3 (4x3), blob2 cols2-6 rows5-7 (5x3); dst == map so scale is 1:1.
        val boxes = PpDetPost().boxes(mask(Rect(1, 1, 4, 3, 0.9f), Rect(2, 5, 6, 7, 0.8f)), W, H, W, H)
        // unclipFrac 0.2 → pad round(4*.2)=1 / round(3*.2)=1 etc; bottom clamped to H.
        assertEquals(listOf(BoxPx(0, 0, 6, 5), BoxPx(1, 4, 8, 8)), boxes)
    }

    @Test fun lowMeanProbBlobFilteredByBoxScore() =                        // on (>0.3) but mean<0.5
        assertTrue(PpDetPost().boxes(mask(Rect(2, 2, 6, 5, 0.4f)), W, H, W, H).isEmpty())

    @Test fun tinyBlobFilteredByMinSize() =                                // 2x2 < minSize 3
        assertTrue(PpDetPost().boxes(mask(Rect(3, 3, 4, 4, 0.9f)), W, H, W, H).isEmpty())

    @Test fun scalesBitmapBoxToDestPixels() {
        // bbox cols2-5 rows2-4; dst 20x16 → sx=sy=2; no unclip pad.
        val boxes = PpDetPost(unclipFrac = 0f).boxes(mask(Rect(2, 2, 5, 4, 0.9f)), W, H, 20, 16)
        assertEquals(listOf(BoxPx(4, 4, 12, 10)), boxes)
    }
}
