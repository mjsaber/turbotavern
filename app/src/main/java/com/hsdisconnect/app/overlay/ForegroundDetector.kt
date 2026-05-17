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
                val events = usm.queryEvents(now - 10_000L, now)
                val e = UsageEvents.Event()
                var lastType = -1
                while (events.getNextEvent(e)) {
                    if (e.packageName == targetPackage) {
                        when (e.eventType) {
                            UsageEvents.Event.ACTIVITY_RESUMED,
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED -> lastType = e.eventType
                        }
                    }
                }
                lastType == UsageEvents.Event.ACTIVITY_RESUMED
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
