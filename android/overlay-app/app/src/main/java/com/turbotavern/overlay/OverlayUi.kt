package com.turbotavern.overlay

/**
 * Renderer contract for the overlay button. Production: [OverlayWindow].
 * Tests: FakeOverlayUi in src/test.
 *
 * All methods must be called on the main looper of the host.
 */
interface OverlayUi {
    fun show()
    fun hide()
    fun setVisible(visible: Boolean)
    fun applyState(state: OverlayState)
    fun onConfigurationChanged()
}
