package com.bobassist.phase0.core

/** Debug builds route through [DebugConnectionCoreOverride] so tests can inject fakes. */
object ConnectionCoreProvider {
    fun get(): ConnectionCoreFacade = DebugConnectionCoreOverride
}
