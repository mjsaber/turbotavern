package com.turbotavern.core

/** Release builds always use RealConnectionCore. */
object ConnectionCoreProvider : ConnectionCoreBinding() {
    override fun get(): ConnectionCoreFacade = RealConnectionCore
}
