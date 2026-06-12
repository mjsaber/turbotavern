package com.bobassist.phase0.foreground

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.bobassist.phase0.core.ForegroundOverrideHolder

/**
 * Shared foreground-package query, extracted from BobVpnService so both the VPN (拔线) service and
 * the overlay service resolve the foreground app through one code path instead of duplicating the
 * sticky logic.
 *
 * Order of resolution:
 *  1. A variant-routed debug override (release builds resolve to a no-op that returns null).
 *  2. A UsageStatsManager scan for the latest ACTIVITY_RESUMED event in the last 60 s.
 *
 * STICKY: the foreground package only changes on a RESUMED event, so an EMPTY 60 s window means no
 * app switch happened — i.e. we are STILL on the last-seen package, not "unknown". Without this, a
 * long uninterrupted Hearthstone session (an entire BG match — exactly when the hero/trinket select
 * screens appear) yields no events, the strict capture gate reads UNKNOWN, and the overlay never
 * renders. The sticky value updates only when a real transition is observed.
 *
 * Does NOT inspect permission state; the caller decides tick-vs-reset based on
 * [hasUsageAccessPermission]. See codex P1 #2 and round-1 P2 #5.
 */
class ForegroundQuery(
    private val context: Context,
    private val hsPackage: String,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var lastForegroundPkg: String? = null

    fun queryForegroundPackage(): String? {
        val fakeFg = ForegroundOverrideHolder.get().foregroundOverride()
        if (fakeFg != null) return if (fakeFg) hsPackage else "com.example.notbob"

        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return lastForegroundPkg
        val end = now()
        // queryEvents can return null when the user is locked (R+) — keep the last known package.
        val events = runCatching { usm.queryEvents(end - 60_000L, end) }
            .getOrNull() ?: return lastForegroundPkg
        var latestTs = 0L
        var latestPkg: String? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && ev.timeStamp >= latestTs) {
                latestTs = ev.timeStamp
                latestPkg = ev.packageName
            }
        }
        if (latestPkg != null) lastForegroundPkg = latestPkg
        return lastForegroundPkg
    }

    fun hasUsageAccessPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
