package com.bobassist.phase0.core

import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-variant foreground-state override. Split out of [DebugConnectionCoreOverride] so it carries NO
 * mihomo/GPL dependency: the foreground gate ([com.bobassist.phase0.foreground.ForegroundQuery]) is
 * shared by both the clean overlay and the full 拔线 service, so this override must be available to the
 * clean flavor too. Lives in src/debug (both flavors); release builds use [NoOpForegroundOverride].
 */
object DebugForegroundOverride : ForegroundOverrideProvider {
    private val ref = AtomicReference<Boolean?>(null)   // codex P1 #7
    fun setForeground(v: Boolean?) { ref.set(v) }
    override fun foregroundOverride(): Boolean? = ref.get()
    fun clear() { ref.set(null) }
}
