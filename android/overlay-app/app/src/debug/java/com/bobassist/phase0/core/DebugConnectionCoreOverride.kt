package com.bobassist.phase0.core

import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-variant override for [ConnectionCoreFacade]. Intercepts the
 * connection-table snapshot + close calls so tests / sim scripts can inject
 * fake state without touching the real mihomo core.
 *
 * codex round-3 P1 #23 + round-4 P1 #36: implements BOTH facades; renames the
 * private property to `foregroundOverrideRef` to avoid collision with the
 * interface method `foregroundOverride()`.
 */
object DebugConnectionCoreOverride : ConnectionCoreFacade, ForegroundOverrideProvider {
    private val snapshotOverride = AtomicReference<String?>(null)
    private val closeOverrides = AtomicReference<Map<String, MihomoCore.CloseResult>>(emptyMap())
    private val snapshotDelayMs = AtomicReference(0L)      // codex P1 #5
    private val closeDelayMs = AtomicReference(0L)
    private val foregroundOverrideRef = AtomicReference<Boolean?>(null)  // codex P1 #7 + round-4 P1 #36

    fun setSnapshot(json: String?) { snapshotOverride.set(json) }
    fun setSnapshotDelay(ms: Long) { snapshotDelayMs.set(ms) }
    fun setCloseResult(id: String, result: MihomoCore.CloseResult) {
        while (true) {
            val curr = closeOverrides.get()
            val next = curr + (id to result)
            if (closeOverrides.compareAndSet(curr, next)) return
        }
    }
    fun setCloseDelay(ms: Long) { closeDelayMs.set(ms) }
    fun setForeground(v: Boolean?) { foregroundOverrideRef.set(v) }
    override fun foregroundOverride(): Boolean? = foregroundOverrideRef.get()
    fun clearAll() {
        snapshotOverride.set(null)
        closeOverrides.set(emptyMap())
        snapshotDelayMs.set(0)
        closeDelayMs.set(0)
        foregroundOverrideRef.set(null)
    }

    override fun connectionsJson(): String {
        val delay = snapshotDelayMs.get()
        if (delay > 0) Thread.sleep(delay)
        return snapshotOverride.get() ?: RealConnectionCore.connectionsJson()
    }

    override fun closeConnection(id: String): MihomoCore.CloseResult {
        val delay = closeDelayMs.get()
        if (delay > 0) Thread.sleep(delay)
        return closeOverrides.get()[id] ?: RealConnectionCore.closeConnection(id)
    }
}
