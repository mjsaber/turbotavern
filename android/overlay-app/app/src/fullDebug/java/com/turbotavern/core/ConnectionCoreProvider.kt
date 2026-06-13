package com.turbotavern.core

/** Debug builds route through [DebugConnectionCoreOverride] so tests can inject fakes. */
object ConnectionCoreProvider : ConnectionCoreBinding() {
    override fun get(): ConnectionCoreFacade = DebugConnectionCoreOverride
}
