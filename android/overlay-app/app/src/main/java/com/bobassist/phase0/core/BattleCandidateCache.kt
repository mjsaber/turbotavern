package com.bobassist.phase0.core

import com.bobassist.phase0.util.Clock
import com.bobassist.phase0.util.TraceSink

/** One poll's immutable result. candidate==null ⇒ no closable battle socket right now. */
data class CachedReadiness(
    val candidate: BattleConnection.Candidate?,
    val count: Int,
    val capturedAtMs: Long,
)

/**
 * Single-thread-confined (pollHandler) cache of the most recently polled battle
 * candidate. The poll loop calls [refresh] each tick; the tap path reads [current].
 *
 * This removes connectionsJson() from the tap critical path (Phase 1.4 P1): the tap
 * closes the cached connection id directly instead of taking a fresh snapshot.
 *
 * [refresh] opens its own trace cycle to emit `poll_snapshot` (with `snapshot_ms`),
 * so OverlayPoller's `snapshot: () -> Int` contract stays unchanged.
 */
class BattleCandidateCache(
    private val snapshot: () -> String,
    private val clock: Clock,
    private val trace: TraceSink? = null,
) {
    @Volatile private var cached: CachedReadiness = CachedReadiness(null, 0, 0L)

    /**
     * Poll-thread: take a fresh snapshot, pick the candidate, atomically publish the
     * cache, and return the candidate count for the state machine. Readiness is keyed
     * on the picked candidate (not the raw count): returns 0 when nothing was picked.
     */
    fun refresh(): Int {
        val cycle = trace?.beginCycle()
        val t0 = clock.nowMillis()
        cycle?.emit("poll_snapshot", "entry")
        val json = snapshot()
        val snapshotMs = clock.nowMillis() - t0
        val (cand, n) = BattleConnection.pickWithCount(json)
        cached = CachedReadiness(cand, n, t0)
        cycle?.emit("poll_snapshot", "exit", "snapshot_ms" to snapshotMs, "count" to n, "picked_id" to cand?.id)
        return if (cand != null) n else 0
    }

    /** Latest published readiness. Safe to read from the pollHandler thread. */
    fun current(): CachedReadiness = cached

    fun clear() { cached = CachedReadiness(null, 0, clock.nowMillis()) }
}
