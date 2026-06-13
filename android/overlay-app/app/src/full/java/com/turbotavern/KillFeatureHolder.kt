package com.turbotavern

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.turbotavern.core.RealLifecycleCore

/** Full (sideload) flavor: real VPN-backed 拔线 via [BobVpnService] + the GPL-3.0 mihomo core. */
object KillFeatureHolder {
    fun get(): KillFeature = VpnKillFeature
}

object VpnKillFeature : KillFeature {
    override fun statusLabel() = "Turbo Tavern · bobcore ${RealLifecycleCore.version()}"
    override fun isRunning() = BobVpnService.liveSession != null
    override fun prepareConsent(context: Context): Intent? = VpnService.prepare(context)
    override fun start(context: Context) {
        context.startForegroundService(
            Intent(context, BobVpnService::class.java).apply { action = BobVpnService.ACTION_START }
        )
    }
    override fun stop(context: Context) {
        context.startService(
            Intent(context, BobVpnService::class.java).apply { action = BobVpnService.ACTION_STOP }
        )
    }
}
