package com.bobassist.phase0.core

import android.net.VpnService

/**
 * Lifecycle ops on the embedded mihomo. Called once per VpnService start/stop;
 * NOT on the hot path. Separated from [ConnectionCoreFacade] so test code that
 * only needs connection inspection doesn't accidentally pull in the native
 * mihomo lifecycle path.
 */
interface LifecycleCoreFacade {
    fun version(): String
    fun setProtector(service: VpnService)
    fun setup(homeDir: String): Result<Unit>
    fun startTun(fd: Int, stack: String, gateway: String, dns: String): Result<Unit>
    fun stopTun(): Result<Unit>
}

/**
 * Runtime connection-table inspection + close. Used by [BattleConnectionController]
 * on the hot path. In debug builds, [DebugConnectionCoreOverride] can intercept
 * to inject fake snapshots / close results.
 */
interface ConnectionCoreFacade {
    fun connectionsJson(): String
    fun closeConnection(id: String): MihomoCore.CloseResult
}

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
