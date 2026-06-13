package com.turbotavern.devrec

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import com.turbotavern.herotier.ImagePlaneBitmap
import java.io.File

/**
 * Holds the latest captured [Image] (cheap: acquire+close per frame, no Bitmap), converting to PNG
 * only at MARK time. All calls on the recorder thread ([handler]); [held] has no lock. Spec §5.6.
 */
class ScreenShotter(
    private var reader: ImageReader,
    private val captureW: () -> Int,
    private val captureH: () -> Int,
    handler: Handler,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private var held: Image? = null
    private var heldAcquiredEpochMs = 0L

    private val listener = ImageReader.OnImageAvailableListener { r ->
        // acquire-first, replace held only on non-null (keep last good image if queue drained)
        val next = runCatching { r.acquireLatestImage() }.getOrNull()
        if (next != null) { val old = held; held = next; heldAcquiredEpochMs = nowMs(); runCatching { old?.close() } }
    }

    init { reader.setOnImageAvailableListener(listener, handler) }

    data class Shot(val ok: Boolean, val w: Int, val h: Int, val acquiredEpochMs: Long)

    /** Convert the held image to a PNG at [out] (temp+rename). Returns ok=false if no frame yet. */
    fun snapshot(out: File): Shot {
        val img = held ?: return Shot(false, 0, 0, 0)
        return runCatching {
            val bmp = ImagePlaneBitmap.of(img, captureW(), captureH())
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(out)
            val s = Shot(true, bmp.width, bmp.height, heldAcquiredEpochMs)
            bmp.recycle(); s
        }.getOrElse { log("devrec: shot failed: ${it.message}"); Shot(false, 0, 0, 0) }
    }

    /** Rotation: point at a new reader. Caller resizes the VD + closes the old reader (§5.6 teardown). */
    fun swapReader(newReader: ImageReader, handler: Handler) {
        runCatching { reader.setOnImageAvailableListener(null, null) }
        held?.let { runCatching { it.close() } }; held = null
        reader = newReader
        reader.setOnImageAvailableListener(listener, handler)
    }

    fun release() {
        runCatching { reader.setOnImageAvailableListener(null, null) }
        held?.let { runCatching { it.close() } }; held = null
    }
}
