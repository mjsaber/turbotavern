package com.bobassist.phase0.core

/** Debug-variant holder: routes through the GPL-free [DebugForegroundOverride]. */
object ForegroundOverrideHolder {
    fun get(): ForegroundOverrideProvider = DebugForegroundOverride
}
