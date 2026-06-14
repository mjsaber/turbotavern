package com.turbotavern

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Clean-flavor binding contract (dedup Phase 3). The compiler (Phase 1 `KillFeatureBinding`) proves
 * the selector's SIGNATURE matches; this proves the clean flavor actually wires the no-op impl — a
 * regression the shared flavor-agnostic suite can't catch, since testCleanDebug and testFullDebug
 * otherwise run identical tests. Pure JUnit: asserts identity + isRunning only (no Android Context,
 * no native mihomo call).
 */
class KillFeatureHolderContractTest {
    @Test fun cleanFlavorBindsNoopKillFeature() {
        assertSame(NoopKillFeature, KillFeatureHolder.get())
    }

    @Test fun cleanKillFeatureNeverRuns() {
        assertFalse(KillFeatureHolder.get().isRunning())
    }

    @Test fun cleanProvidesNoKillFeature() {
        assertFalse(KillFeatureHolder.get().providesKillFeature())   // clean shows no "Start anyway"
    }
}
