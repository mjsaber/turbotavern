package com.turbotavern.core

/**
 * Variant-routed indirection for foreground-detector overrides. Debug builds
 * provide a real implementation backed by [DebugConnectionCoreOverride]; release
 * builds resolve to [NoOpForegroundOverride].
 *
 * codex round-2 P1 #15 + round-4 P1 #33: the name MUST NOT contain "Debug" —
 * the release-dex grep audit forbids `Debug*` symbols from leaking into the
 * release APK. Only [NoOpForegroundOverride] (release) and the debug-only
 * concrete class are bound through [ForegroundOverrideHolder.get()].
 */
interface ForegroundOverrideProvider {
    /** Returns null = use real detector; true = force HS foreground; false = force not. */
    fun foregroundOverride(): Boolean?
}

object NoOpForegroundOverride : ForegroundOverrideProvider {
    override fun foregroundOverride(): Boolean? = null
}
