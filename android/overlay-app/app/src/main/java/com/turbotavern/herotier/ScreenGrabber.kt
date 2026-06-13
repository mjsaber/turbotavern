package com.turbotavern.herotier

/** Captures one frame on demand from the live MediaProjection. Returns null if no frame is ready. */
interface ScreenGrabber {
    fun capture(): Frame?
}
