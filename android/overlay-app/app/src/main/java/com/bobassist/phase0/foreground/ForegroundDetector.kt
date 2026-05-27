package com.bobassist.phase0.foreground

/**
 * Polling detector for whether [targetPackage] is the current foreground app.
 *
 * Pure logic; no Android dependencies — the host injects [queryForegroundPackage]
 * which (in production) wraps UsageStatsManager.queryEvents and returns the
 * latest ACTIVITY_RESUMED package name, or null if no recent events.
 *
 * Default state is `isTargetForeground = true` (optimistic). Reasons:
 * 1. If the user denies PACKAGE_USAGE_STATS, every tick returns null → we
 *    never transition away from "HS foreground", so the overlay stays visible
 *    (spec D6 degraded mode).
 * 2. If the detector hasn't ticked yet, we'd rather show the overlay than
 *    hide it — better to over-show than miss combat.
 *
 * Thread model: all methods must be called from a single thread (the host's
 * pollHandler in BobVpnService). isTargetForeground is @Volatile so consumers
 * on other threads can read it.
 */
class ForegroundDetector(
    private val queryForegroundPackage: () -> String?,
    private val targetPackage: String,
    private val onChange: (Boolean) -> Unit,
    private val trace: com.bobassist.phase0.util.TraceSink? = null,
) {

    @Volatile var isTargetForeground: Boolean = true
        private set

    fun tick() {
        val current = queryForegroundPackage() ?: return  // unknown → keep state
        val next = (current == targetPackage)
        if (next == isTargetForeground) return
        val cycle = trace?.beginCycle()
        cycle?.emit("fg_change", "entry", "from" to isTargetForeground, "to" to next)
        isTargetForeground = next
        onChange(next)
        cycle?.emit("fg_change", "exit")
    }

    /**
     * Revert to optimistic "foreground=true" state. Call when permission was
     * just revoked, or when the host wants to force a re-show.
     */
    fun reset() {
        if (isTargetForeground) return
        val cycle = trace?.beginCycle()
        cycle?.emit("fg_reset", "entry")
        isTargetForeground = true
        onChange(true)
        cycle?.emit("fg_reset", "exit")
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
