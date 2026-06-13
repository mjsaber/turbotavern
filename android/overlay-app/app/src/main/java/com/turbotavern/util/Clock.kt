package com.turbotavern.util

import android.os.SystemClock

/**
 * Monotonic clock abstraction. Production uses [AndroidElapsedRealtimeClock]
 * which delegates to SystemClock.elapsedRealtimeNanos (advances with
 * Robolectric ShadowLooper.idleFor). Tests requiring controlled time can
 * also use [ManualClock] in pure-JVM contexts.
 */
interface Clock {
    fun nowNanos(): Long
    fun nowMillis(): Long = nowNanos() / 1_000_000L
}

object AndroidElapsedRealtimeClock : Clock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** For pure-JVM tests only; do NOT use in Robolectric tests (use AndroidElapsedRealtimeClock + ShadowLooper.idleFor instead). */
class ManualClock(initial: Long = 0L) : Clock {
    @Volatile private var current: Long = initial
    fun advance(deltaNanos: Long) { current += deltaNanos }
    override fun nowNanos(): Long = current
}
