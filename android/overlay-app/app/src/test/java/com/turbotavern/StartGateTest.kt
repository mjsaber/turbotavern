package com.turbotavern

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Start-button gate (Bug 1). The key regression: Usage Access (required for ratings) must be gated
 * BEFORE start proceeds, regardless of kill/VPN state — so a full user with the VPN already running can't
 * slip into a silently-rating-less projection. [startGate] does not take kill state, which is exactly why
 * the usage gate can't be bypassed by the kill.isRunning() shortcut (that shortcut lives downstream of a
 * PROCEED decision).
 */
class StartGateTest {
    @Test fun noOverlayNeedsOverlay() =
        assertEquals(StartGate.NEEDS_OVERLAY, startGate(hasOverlay = false, hasUsage = false))

    @Test fun overlayTakesPriorityOverUsage() =
        assertEquals(StartGate.NEEDS_OVERLAY, startGate(hasOverlay = false, hasUsage = true))

    @Test fun overlayGrantedButNoUsagePromptsUsage() =
        assertEquals(StartGate.NEEDS_USAGE, startGate(hasOverlay = true, hasUsage = false))

    @Test fun bothGrantedProceeds() =
        assertEquals(StartGate.PROCEED, startGate(hasOverlay = true, hasUsage = true))
}
