package com.bobassist.phase0.util

import com.bobassist.phase0.core.ConnectionCoreFacade
import com.bobassist.phase0.core.MihomoCore

class FakeConnectionCore : ConnectionCoreFacade {
    @Volatile var snapshotJson: String = "[]"
    val closeCallLog: MutableList<Pair<Long, String>> =
        java.util.Collections.synchronizedList(mutableListOf())
    val closeResults: MutableMap<String, MihomoCore.CloseResult> =
        java.util.Collections.synchronizedMap(mutableMapOf())
    @Volatile var closeDelayMs: Long = 0L

    override fun connectionsJson(): String = snapshotJson
    override fun closeConnection(id: String): MihomoCore.CloseResult {
        closeCallLog.add(android.os.SystemClock.elapsedRealtimeNanos() to id)
        if (closeDelayMs > 0) Thread.sleep(closeDelayMs)
        return closeResults[id] ?: MihomoCore.CloseResult.Success
    }
}
