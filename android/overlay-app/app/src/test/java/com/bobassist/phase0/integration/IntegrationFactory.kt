package com.bobassist.phase0.integration

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.foreground.ForegroundDetector
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.session.OverlaySession
import com.bobassist.phase0.util.AndroidElapsedRealtimeClock
import com.bobassist.phase0.util.FakeConnectionCore
import com.bobassist.phase0.util.FakeOverlayUi
import com.bobassist.phase0.util.TraceSink

class IntegrationFactory {
    val pollThread = HandlerThread("test-poll").apply { start() }
    val pollHandler = Handler(pollThread.looper)
    val mainHandler = Handler(Looper.getMainLooper())
    val clock = AndroidElapsedRealtimeClock
    val trace = TraceSink(enabled = false, clock = clock)   // tests don't need trace output
    val fakeConn = FakeConnectionCore()
    val fakeOverlay = FakeOverlayUi()
    val controller = BattleConnectionController(
        snapshot = { fakeConn.connectionsJson() },
        close = { id -> fakeConn.closeConnection(id) },
    )
    val poller = OverlayPoller(
        snapshot = {
            com.bobassist.phase0.core.BattleConnection
                .pickWithCount(fakeConn.connectionsJson()).second
        },
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
