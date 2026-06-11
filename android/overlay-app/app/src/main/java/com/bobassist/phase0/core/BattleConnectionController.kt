package com.bobassist.phase0.core

import com.bobassist.phase0.util.TraceCycle

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

    fun killBattleSocket(cycle: TraceCycle? = null): KillResult {
        cycle?.emit("snapshot", "entry")
        val snapshotJson = snapshot()
        cycle?.emit("snapshot", "exit")

        cycle?.emit("pick", "entry")
        val (cand, count) = BattleConnection.pickWithCount(snapshotJson)
        cycle?.emit("pick", "exit", "candidate_count" to count, "picked_id" to cand?.id)
        if (cand == null) return KillResult.NoCandidate

        cycle?.emit("close", "entry", "conn_id" to cand.id)
        val r = close(cand.id)
        cycle?.emit("close", "exit", "result" to r.toString())

        return when (r) {
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

    /**
     * Close an already-picked candidate WITHOUT taking a fresh snapshot. This is the
     * Phase 1.4 tap path: the poll loop cached the candidate, so the tap critical path
     * never calls connectionsJson().
     *
     * A stale cached id (socket rotated by the server since the last poll) closes
     * nothing and surfaces as NotFound → [KillResult.Failure]; the connection id is a
     * non-recycled UUID, so a stale id can never hit a different connection.
     */
    fun killCachedCandidate(
        cand: BattleConnection.Candidate,
        candidatesAtKill: Int,
        cycle: TraceCycle? = null,
    ): KillResult {
        cycle?.emit("close", "entry", "conn_id" to cand.id, "cached" to true)
        val r = close(cand.id)
        cycle?.emit("close", "exit", "result" to r.toString())

        return when (r) {
            MihomoCore.CloseResult.Success ->
                KillResult.Success(
                    closedId = cand.id,
                    destinationIp = cand.destinationIp,
                    destinationPort = cand.destinationPort,
                    candidatesAtKill = candidatesAtKill,
                )
            MihomoCore.CloseResult.AlreadyClosed -> KillResult.AlreadyClosed
            else -> KillResult.Failure(r.toString())
        }
    }

    /**
     * Tap path with a single bounded fallback. Closes the cached id directly (the fast path); if that
     * id rotated since the last poll the close returns NotFound and nothing would happen — a dead tap.
     * On exactly that case we take ONE fresh snapshot and kill the current live socket instead.
     *
     * Bounded by design: at most one retry, so a genuinely-absent socket fails cleanly as NoCandidate
     * rather than spinning. Non-NotFound results (Success/AlreadyClosed/other Failure) return as-is with
     * no snapshot — the fast path stays snapshot-free in the common case.
     */
    fun killCachedCandidateThenRetry(
        cand: BattleConnection.Candidate,
        candidatesAtKill: Int,
        cycle: TraceCycle? = null,
    ): KillResult {
        val first = killCachedCandidate(cand, candidatesAtKill, cycle)
        if (first is KillResult.Failure && first.reason == MihomoCore.CloseResult.NotFound.toString()) {
            cycle?.emit("retry", "entry", "reason" to "stale_cached_id")
            return killBattleSocket(cycle)
        }
        return first
    }
}
