package com.turbotavern.core

/** Release-variant holder: always returns the no-op override. */
object ForegroundOverrideHolder : ForegroundOverrideBinding() {
    override fun get(): ForegroundOverrideProvider = NoOpForegroundOverride
}
