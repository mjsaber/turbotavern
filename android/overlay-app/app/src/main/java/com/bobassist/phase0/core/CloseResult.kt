package com.bobassist.phase0.core

/**
 * Result of closing a single connection by id. A pure-Kotlin type with NO gomobile dependency, so the
 * GPL-free hot path ([BattleConnectionController], [ConnectionCoreFacade], and test fakes) can reference
 * it without pulling in the GPL-3.0 [MihomoCore]/bobcore native core. [MihomoCore] maps Bobcore's int
 * status codes onto these cases. Lives in `src/main` (shared by both the clean and full flavors).
 */
sealed class CloseResult {
    object Success : CloseResult() { override fun toString() = "Success" }
    object NotFound : CloseResult() { override fun toString() = "NotFound" }
    object AlreadyClosed : CloseResult() { override fun toString() = "AlreadyClosed" }
    object CoreStopped : CloseResult() { override fun toString() = "CoreStopped" }
    data class InternalError(val code: Int) : CloseResult()
}
