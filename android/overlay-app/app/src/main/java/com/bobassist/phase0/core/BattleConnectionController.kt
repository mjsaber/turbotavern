package com.bobassist.phase0.core

/**
 * Wraps "find current HS battle socket and close it" into a single typed call.
 *
 * Injection-friendly: takes a `snapshot` lambda returning the raw connections
 * JSON and a `close` lambda taking an id and returning [MihomoCore.CloseResult].
 * In production, both lambdas thunk into [MihomoCore]; in tests, they take
 * fixture JSON / fixed results.
 */
class BattleConnectionController(
    private val snapshot: () -> String,
    private val close: (String) -> MihomoCore.CloseResult,
) {

    sealed class KillResult {
        /** A candidate was found and closed cleanly. */
        data class Success(
            val closedId: String,
            val destinationIp: String,
            val destinationPort: Int,
            val candidatesAtKill: Int,
        ) : KillResult()

        /** Snapshot had no battle socket matching the BattleConnection filter. */
        object NoCandidate : KillResult()

        /** mihomo says the connection was already closed when we tried. */
        object AlreadyClosed : KillResult()

        /** Anything else (CoreStopped, NotFound after we just saw it, InternalError). */
        data class Failure(val reason: String) : KillResult()
    }

    fun killBattleSocket(): KillResult {
        val (cand, count) = BattleConnection.pickWithCount(snapshot())
        if (cand == null) return KillResult.NoCandidate
        return when (val r = close(cand.id)) {
            MihomoCore.CloseResult.Success ->
                KillResult.Success(
                    closedId = cand.id,
                    destinationIp = cand.destinationIp,
                    destinationPort = cand.destinationPort,
                    candidatesAtKill = count,
                )
            MihomoCore.CloseResult.AlreadyClosed -> KillResult.AlreadyClosed
            else -> KillResult.Failure(r.toString())
        }
    }
}
