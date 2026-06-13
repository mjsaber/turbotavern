package com.turbotavern.core

/** Release builds always use RealConnectionCore. */
object ConnectionCoreProvider {
    fun get(): ConnectionCoreFacade = RealConnectionCore
}
