package com.hsdisconnect.app.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.hsdisconnect.app.core.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ForegroundDetector(
    private val sampleNow: () -> Boolean,
    private val debounceSamples: Int = Constants.FOREGROUND_DEBOUNCE_SAMPLES,
) {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private var streak = 0
    private var lastSample: Boolean = false

    fun tick() {
        val sample = sampleNow()
        if (sample == lastSample) {
            streak++
        } else {
            streak = 1
            lastSample = sample
        }
        if (streak >= debounceSamples && _isForeground.value != sample) {
            _isForeground.value = sample
        }
    }

    companion object {
        fun fromUsageStats(
            usm: UsageStatsManager,
            targetPackage: String = Constants.HEARTHSTONE_PACKAGE,
        ): ForegroundDetector = ForegroundDetector(
            sampleNow = {
                val now = System.currentTimeMillis()
                // Walk events across all packages over the past hour to track
                // the currently-foreground package. A short lookback (e.g. 10s)
                // would lose the initial RESUMED event once the user has been
                // sitting in the foreground app without any app switches.
                val events = usm.queryEvents(now - 60 * 60 * 1000L, now)
                val e = UsageEvents.Event()
                var foreground: String? = null
                while (events.getNextEvent(e)) {
                    when (e.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> foreground = e.packageName
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED -> {
                            if (e.packageName == foreground) foreground = null
                        }
                    }
                }
                foreground == targetPackage
            },
        )
    }

    fun startPolling(scope: CoroutineScope, intervalMs: Long = Constants.FOREGROUND_POLL_INTERVAL_MS): Job {
        return scope.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }
}
