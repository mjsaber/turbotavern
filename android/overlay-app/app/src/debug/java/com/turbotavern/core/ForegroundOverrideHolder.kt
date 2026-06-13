package com.turbotavern.core

/** Debug-variant holder: routes through the GPL-free [DebugForegroundOverride]. */
object ForegroundOverrideHolder : ForegroundOverrideBinding() {
    override fun get(): ForegroundOverrideProvider = DebugForegroundOverride
}
