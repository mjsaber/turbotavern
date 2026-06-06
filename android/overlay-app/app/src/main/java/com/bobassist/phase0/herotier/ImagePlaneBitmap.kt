package com.bobassist.phase0.herotier

import android.graphics.Bitmap
import android.media.Image

/**
 * Convert a single RGBA_8888 [Image] (plane 0) to a cropped [Bitmap], handling row-stride padding.
 * Shared by [MediaProjectionGrabber] and the debug ScreenShotter so the pixel path never drifts.
 * Does NOT close the image.
 */
object ImagePlaneBitmap {
    fun of(image: Image, captureW: Int, captureH: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * captureW
        val bufferW = captureW + (if (pixelStride > 0) rowPadding / pixelStride else 0)
        val padded = Bitmap.createBitmap(bufferW, captureH, Bitmap.Config.ARGB_8888)
        // duplicate()+rewind: copyPixelsFromBuffer advances the buffer position, so the SAME held
        // Image converted on a later MARK must read from position 0.
        padded.copyPixelsFromBuffer(plane.buffer.duplicate().apply { rewind() })
        return if (bufferW != captureW) {
            val cropped = Bitmap.createBitmap(padded, 0, 0, captureW, captureH)
            padded.recycle()
            cropped
        } else padded
    }
}
