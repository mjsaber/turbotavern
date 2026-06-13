package com.turbotavern.core

import android.net.VpnService

/**
 * Lifecycle ops on the embedded mihomo. Called once per VpnService start/stop;
 * NOT on the hot path. Separated from [ConnectionCoreFacade] so test code that
 * only needs connection inspection doesn't accidentally pull in the native
 * mihomo lifecycle path. The production impl ([RealLifecycleCore]) lives in
 * RealCoreFacades.kt (the GPL/`full`-flavor side); this interface is GPL-free.
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
 * to inject fake snapshots / close results. GPL-free (returns the shared [CloseResult]);
 * the production impl ([RealConnectionCore]) lives in RealCoreFacades.kt.
 */
interface ConnectionCoreFacade {
    fun connectionsJson(): String
    fun closeConnection(id: String): CloseResult
}

/**
 * Compile-time contract for the buildType-specific `ConnectionCoreProvider` (fullDebug/fullRelease).
 * Subclassing fixes [get]'s signature so the two copies can't drift. (Dedup strategy Phase 1.)
 */
abstract class ConnectionCoreBinding {
    abstract fun get(): ConnectionCoreFacade
}
