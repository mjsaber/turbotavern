package com.bobassist.phase0.util

import com.bobassist.phase0.core.CloseResult
import com.bobassist.phase0.core.ConnectionCoreFacade

class FakeConnectionCore : ConnectionCoreFacade {
    @Volatile var snapshotJson: String = "[]"
    /** Timestamps (elapsedRealtimeNanos) of each connectionsJson() call — lets tests
     *  assert the tap path no longer takes a snapshot (Phase 1.4). */
    val snapshotCallLog: MutableList<Long> =
        java.util.Collections.synchronizedList(mutableListOf())
    val closeCallLog: MutableList<Pair<Long, String>> =
        java.util.Collections.synchronizedList(mutableListOf())
    val closeResults: MutableMap<String, CloseResult> =
        java.util.Collections.synchronizedMap(mutableMapOf())
    @Volatile var closeDelayMs: Long = 0L

    override fun connectionsJson(): String {
        snapshotCallLog.add(android.os.SystemClock.elapsedRealtimeNanos())
        return snapshotJson
    }
    override fun closeConnection(id: String): CloseResult {
        closeCallLog.add(android.os.SystemClock.elapsedRealtimeNanos() to id)
        if (closeDelayMs > 0) Thread.sleep(closeDelayMs)
        return closeResults[id] ?: CloseResult.Success
    }
}
