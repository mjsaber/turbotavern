package com.turbotavern.core

import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-variant override for [ConnectionCoreFacade]. Intercepts the connection-table snapshot + close
 * calls so tests / sim scripts can inject fake state without touching the real mihomo core. The
 * foreground-state override is split out into [DebugForegroundOverride] (GPL-free, shared by both
 * flavors); this connection override is GPL-coupled (falls back to [RealConnectionCore]) and so lives
 * in the `full` flavor's source set.
 */
object DebugConnectionCoreOverride : ConnectionCoreFacade {
    private val snapshotOverride = AtomicReference<String?>(null)
    private val closeOverrides = AtomicReference<Map<String, CloseResult>>(emptyMap())
    private val snapshotDelayMs = AtomicReference(0L)      // codex P1 #5
    private val closeDelayMs = AtomicReference(0L)

    fun setSnapshot(json: String?) { snapshotOverride.set(json) }
    fun setSnapshotDelay(ms: Long) { snapshotDelayMs.set(ms) }
    fun setCloseResult(id: String, result: CloseResult) {
        while (true) {
            val curr = closeOverrides.get()
            val next = curr + (id to result)
            if (closeOverrides.compareAndSet(curr, next)) return
        }
    }
    fun setCloseDelay(ms: Long) { closeDelayMs.set(ms) }
    fun clearAll() {
        snapshotOverride.set(null)
        closeOverrides.set(emptyMap())
        snapshotDelayMs.set(0)
        closeDelayMs.set(0)
    }

    override fun connectionsJson(): String {
        val delay = snapshotDelayMs.get()
        if (delay > 0) Thread.sleep(delay)
        return snapshotOverride.get() ?: RealConnectionCore.connectionsJson()
    }

    override fun closeConnection(id: String): CloseResult {
        val delay = closeDelayMs.get()
        if (delay > 0) Thread.sleep(delay)
        val explicit = closeOverrides.get()[id]
        if (explicit != null) return explicit
        // codex code-review P2: when a snapshot override is active, the fake
        // connection table is the only source of truth — falling through to
        // RealConnectionCore would always return NotFound for fake ids,
        // which breaks cooldown semantics in sim scenarios. Default to
        // Success for ids that came from the fake snapshot.
        if (snapshotOverride.get() != null) return CloseResult.Success
        return RealConnectionCore.closeConnection(id)
    }
}
