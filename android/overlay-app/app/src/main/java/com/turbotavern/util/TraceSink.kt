package com.turbotavern.util

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-cycle trace handle returned by [TraceSink.beginCycle]. Carries its own
 * window expiry so that posted-runnable emits from rapid taps stay correctly
 * attributed (each tap gets its own cycle).
 */
class TraceCycle internal constructor(
    val sessionId: Long,
    val cycleId: Long,
    private val openUntilNs: Long,
    private val clock: Clock,
    private val output: (String) -> Unit,
) {
    val enabled: Boolean = cycleId > 0    // 0 == disabled sentinel

    fun emit(phase: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        if (clock.nowNanos() > openUntilNs) return
        val sb = StringBuilder()
        sb.append("trace session=").append(sessionId)
            .append(" cycle=").append(cycleId)
            .append(" phase=").append(phase)
            .append(" event=").append(event)
            .append(" t_ns=").append(clock.nowNanos())
            .append(" thread=").append(Thread.currentThread().name)
        for ((k, v) in fields) {
            if (v == null) continue
            sb.append(' ').append(k).append('=').append(v)
        }
        output(sb.toString())
    }
}

/**
 * Structured trace emitter with per-tap cycle scoping.
 *
 * Usage: call [beginCycle] at the start of an event of interest (a tap, a
 * poll tick, a fg change). Use the returned [TraceCycle] to emit subsequent
 * phases. Each cycle has its own [windowMs] (default 10 s — long enough to
 * capture a worst-case 5-6 s tap-to-skip delay).
 *
 * Disabled (release builds): all beginCycle returns the disabled sentinel
 * cycle; emit is a no-op. No allocation per call (other than the cycle obj
 * if enabled).
 */
class TraceSink(
    private val enabled: Boolean,
    private val clock: Clock,
    private val output: (String) -> Unit = { Log.i(TAG, it) },
    private val sessionId: Long = sessionCounter.incrementAndGet(),
    val windowMs: Long = 10_000L,    // codex round-2 P1 #18: bumped from 2s for 5-6s diagnosis
) {
    private val cycleCounter = AtomicLong(0)
    private val disabledCycle = TraceCycle(sessionId, 0, 0, clock) { }

    fun beginCycle(): TraceCycle {
        if (!enabled) return disabledCycle
        val cid = cycleCounter.incrementAndGet()
        val openUntil = clock.nowNanos() + windowMs * 1_000_000L
        return TraceCycle(sessionId, cid, openUntil, clock, output)
    }

    companion object {
        private const val TAG = "BobTrace"
        private val sessionCounter = AtomicLong(0)
    }
}
