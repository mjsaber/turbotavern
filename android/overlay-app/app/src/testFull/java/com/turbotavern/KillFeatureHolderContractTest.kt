package com.turbotavern

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Full-flavor binding contract (dedup Phase 3). Proves the full flavor wires the VPN-backed impl.
 * Asserts object IDENTITY only — it never calls VpnKillFeature.statusLabel()/isRunning(), which would
 * hit the native mihomo core (RealLifecycleCore) and can't run in a JVM unit test. Referencing the
 * `VpnKillFeature` object for identity comparison does not invoke any native code.
 */
class KillFeatureHolderContractTest {
    @Test fun fullFlavorBindsVpnKillFeature() {
        assertSame(VpnKillFeature, KillFeatureHolder.get())
    }
}
