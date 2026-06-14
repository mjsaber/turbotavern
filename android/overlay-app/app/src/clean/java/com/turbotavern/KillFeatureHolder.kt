package com.turbotavern

import android.content.Context
import android.content.Intent

/** Clean (Play) flavor: no 拔线, no VPN, no GPL core — the overlay runs on MediaProjection alone. */
object KillFeatureHolder : KillFeatureBinding() {
    override fun get(): KillFeature = NoopKillFeature
}

object NoopKillFeature : KillFeature {
    override fun statusLabel() = "Turbo Tavern"
    override fun isRunning() = false
    override fun providesKillFeature() = false
    override fun prepareConsent(context: Context): Intent? = null
    override fun start(context: Context) {}
    override fun stop(context: Context) {}
}
