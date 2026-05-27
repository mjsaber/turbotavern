package com.bobassist.phase0.core

/** Debug-variant holder: routes through [DebugConnectionCoreOverride]. */
object ForegroundOverrideHolder {
    fun get(): ForegroundOverrideProvider = DebugConnectionCoreOverride
}
