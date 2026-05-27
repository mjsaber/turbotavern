package com.bobassist.phase0.core

/** Release builds always use RealConnectionCore. */
object ConnectionCoreProvider {
    fun get(): ConnectionCoreFacade = RealConnectionCore
}
