package com.turbotavern

import android.content.Context
import android.content.Intent

/**
 * Abstraction over the optional 拔线 (VPN socket-kill) feature, so the shared [MainActivity] has NO
 * compile-time dependency on BobVpnService or the GPL-3.0 mihomo core. The `full` flavor provides a
 * VPN-backed impl; the `clean` (Play) flavor provides a no-op so the overlay runs with no VPN at all.
 * Resolved via the flavor-specific `KillFeatureHolder` (mirrors the ForegroundOverrideHolder pattern).
 */
interface KillFeature {
    /** Short status label for the verifier UI (full: "bobcore X.Y"; clean: a plain label). */
    fun statusLabel(): String

    /** Is the kill/VPN service currently running? (clean: always false) */
    fun isRunning(): Boolean

    /**
     * Does this build offer the 拔线 (Disconnect) feature? full: true; clean: false. Lets the shared
     * [MainActivity] decide whether "Start anyway" is meaningful when Usage Access (required for ratings)
     * is missing — only the full SKU has a feature that works without it. No flavor/BuildConfig reflection.
     */
    fun providesKillFeature(): Boolean

    /**
     * Returns a consent Intent the caller must launch via startActivityForResult, or null if no consent
     * is needed (already authorized, or the clean flavor). On null the caller proceeds straight to [start].
     */
    fun prepareConsent(context: Context): Intent?

    /** Start the kill/VPN service. clean: no-op. */
    fun start(context: Context)

    /** Stop the kill/VPN service. clean: no-op. */
    fun stop(context: Context)
}

/**
 * Compile-time contract for the flavor-specific `KillFeatureHolder`. Each flavor's holder MUST
 * subclass this, so changing [get]'s signature here breaks BOTH src/clean and src/full at compile
 * time — selector drift becomes a build error, not a latent runtime fork. (Dedup strategy Phase 1.)
 */
abstract class KillFeatureBinding {
    abstract fun get(): KillFeature
}
