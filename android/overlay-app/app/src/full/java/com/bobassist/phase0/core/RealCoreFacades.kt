package com.bobassist.phase0.core

import android.net.VpnService

/**
 * Production facade impls backed by the embedded mihomo ([MihomoCore], GPL-3.0). Kept apart from the
 * GPL-free [LifecycleCoreFacade]/[ConnectionCoreFacade] interfaces so that only this file (plus
 * [MihomoCore] and bobcore.aar) carries the copyleft dependency — in the two-SKU split these move to
 * the `full` flavor's source set, leaving the clean Play SKU free of GPL code.
 */
object RealLifecycleCore : LifecycleCoreFacade {
    override fun version() = MihomoCore.version()
    override fun setProtector(service: VpnService) { MihomoCore.setProtector(service) }
    override fun setup(homeDir: String) = MihomoCore.setup(homeDir)
    override fun startTun(fd: Int, stack: String, gateway: String, dns: String) =
        MihomoCore.startTun(fd, stack, gateway, dns)
    override fun stopTun() = MihomoCore.stopTun()
}

object RealConnectionCore : ConnectionCoreFacade {
    override fun connectionsJson() = MihomoCore.connectionsJson()
    override fun closeConnection(id: String) = MihomoCore.closeConnection(id)
}
