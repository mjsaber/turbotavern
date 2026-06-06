package com.bobassist.phase0.herotier

import android.media.ImageReader
import android.util.Log

/**
 * [ScreenGrabber] backed by a MediaProjection VirtualDisplay's [ImageReader]. The reader/virtual
 * display are owned and sized by the host service (to the display's current orientation), so the
 * capture buffer is upright and capture↔screen mapping is scale + offset only. [displayInfo]
 * supplies the live display bounds + rotation per capture, recorded on the returned [Frame] so the
 * coordinator can drop a frame whose rotation no longer matches at render time.
 */
class MediaProjectionGrabber(
    private val reader: ImageReader,
    private val captureW: Int,
    private val captureH: Int,
    private val displayInfo: () -> DisplayInfo,
) : ScreenGrabber {

    data class DisplayInfo(val width: Int, val height: Int, val rotationDeg: Int)

    override fun capture(): Frame? {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return null
        return try {
            val bitmap = ImagePlaneBitmap.of(image, captureW, captureH)
            val d = displayInfo()
            val t = Transform(
                scaleX = d.width.toFloat() / captureW,
                scaleY = d.height.toFloat() / captureH,
                offsetX = 0, offsetY = 0,
            )
            Frame(bitmap, captureW, captureH, t, d.rotationDeg)
        } catch (e: Throwable) {
            Log.w(TAG, "capture failed: ${e.message}")
            null
        } finally {
            runCatching { image.close() }
        }
    }

    companion object {
        private const val TAG = "MediaProjGrabber"
    }
}
