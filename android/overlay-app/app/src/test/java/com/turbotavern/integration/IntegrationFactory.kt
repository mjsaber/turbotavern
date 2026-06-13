package com.turbotavern.integration

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.turbotavern.core.BattleCandidateCache
import com.turbotavern.core.BattleConnectionController
import com.turbotavern.foreground.ForegroundDetector
import com.turbotavern.overlay.OverlayPoller
import com.turbotavern.session.OverlaySession
import com.turbotavern.util.AndroidElapsedRealtimeClock
import com.turbotavern.util.FakeConnectionCore
import com.turbotavern.util.FakeOverlayUi
import com.turbotavern.util.TraceSink

class IntegrationFactory(traceEnabled: Boolean = false) {
    val pollThread = HandlerThread("test-poll").apply { start() }
    val pollHandler = Handler(pollThread.looper)
    val mainHandler = Handler(Looper.getMainLooper())
    val clock = AndroidElapsedRealtimeClock
    /** Captured trace lines when [traceEnabled]; empty otherwise. Lets T9 assert
     *  poll_snapshot snapshot_ms + tap cache_age_ms are emitted. */
    val traceOutput: MutableList<String> =
        java.util.Collections.synchronizedList(mutableListOf())
    val trace = TraceSink(enabled = traceEnabled, clock = clock, output = { traceOutput.add(it) })
    val fakeConn = FakeConnectionCore()
    val fakeOverlay = FakeOverlayUi()
    val controller = BattleConnectionController(
        snapshot = { fakeConn.connectionsJson() },
        close = { id -> fakeConn.closeConnection(id) },
    )
    val candidateCache = BattleCandidateCache(
        snapshot = { fakeConn.connectionsJson() },
        clock = clock,
        trace = trace,
    )
    val poller = OverlayPoller(
        snapshot = { candidateCache.refresh() },
        onStateChange = { state -> mainHandler.post { fakeOverlay.applyState(state) } },
        scheduleAfter = { delayMs, cb -> pollHandler.postDelayed(cb, delayMs) },
        clock = clock,
        trace = trace,
    )
    var hsForegroundOverride: Boolean = true   // tests can flip this
    val detector = ForegroundDetector(
        queryForegroundPackage = {
            if (hsForegroundOverride) "com.blizzard.wtcg.hearthstone" else "com.example.notbob"
        },
        targetPackage = "com.blizzard.wtcg.hearthstone",
        onChange = { isFg -> session.handleForegroundChange(isFg) },
    )
    val session: OverlaySession by lazy {
        OverlaySession(
            controller = controller,
            candidateCache = candidateCache,
            poller = poller,
            detector = detector,
            overlay = fakeOverlay,
            pollHandler = pollHandler,
            mainHandler = mainHandler,
            clock = clock,
            trace = trace,
            hasUsageAccessPermission = { true },
            breadcrumb = { },
        )
    }

    fun tearDown() {
        session.stop()
        pollThread.quitSafely()
    }
}
