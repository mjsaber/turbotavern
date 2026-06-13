package com.turbotavern.integration

import android.os.Build
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/**
 * Codex round P1 #3 — verifies the liveness guard (`if (!started) return`) on
 * every runnable posted by OverlaySession actually fires when stop() is called
 * before the runnable executes. We do this by exploiting Robolectric's PAUSED
 * looper mode: posts are queued but only run when we explicitly idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlaySessionTeardownRaceTest {

    private lateinit var factory: IntegrationFactory

    @Before
    fun setup() {
        factory = IntegrationFactory()
    }

    @After
    fun cleanup() {
        factory.tearDown()
    }

    private fun drainBoth(ms: Long = 0) {
        if (ms > 0) shadowOf(factory.pollThread.looper).idleFor(ms, TimeUnit.MILLISECONDS)
        shadowOf(factory.pollThread.looper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun oneCandidateJson(id: String = "c1", createdAt: Long = 100L): String =
        """[{"id":"$id","host":"","network":"tcp","destinationIp":"1.2.3.4","destinationPort":3724,"createdAt":$createdAt}]"""

    @Test
    fun `Tap posted after stop() does NOT call controller`() {
        factory.fakeConn.snapshotJson = oneCandidateJson("c1")
        factory.session.start()
        drainBoth()
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        val closeCallsBefore = factory.fakeConn.closeCallLog.size

        // Post a tap then immediately stop — stop() calls removeCallbacksAndMessages(null),
        // which should nuke the pending tap runnable.
        factory.session.handleTap()
        factory.session.stop()
        drainBoth()

        assertEquals("controller must not be invoked after stop()",
            closeCallsBefore, factory.fakeConn.closeCallLog.size)
    }

    @Test
    fun `Foreground change posted after stop() does NOT call setVisible`() {
        factory.fakeConn.snapshotJson = "[]"
        factory.session.start()
        drainBoth()
        val logBefore = factory.fakeOverlay.log.toList()

        // Post a foreground change then immediately stop. The setVisible runnable on
        // mainHandler has its own `if (!started) return@post` guard.
        factory.session.handleForegroundChange(false)
        factory.session.stop()
        drainBoth()

        val newLogEntries = factory.fakeOverlay.log.toList() - logBefore.toSet()
        // After stop(), the only allowed UI side-effect is the final hide() posted by stop() itself.
        val unexpectedSetVisible = newLogEntries.filter { it.startsWith("setVisible(false)") }
        // setVisible(false) from handleForegroundChange must NOT appear (started=false guard)
        assertTrue("Unexpected setVisible entries: $newLogEntries",
            unexpectedSetVisible.isEmpty())
    }

    @Test
    fun `Configuration change posted after stop() does NOT call overlay onConfigurationChanged`() {
        factory.fakeConn.snapshotJson = "[]"
        factory.session.start()
        drainBoth()
        val configChangedCallsBefore = factory.fakeOverlay.log.count { it == "onConfigurationChanged" }

        // The outer `if (!started) return` in handleConfigurationChanged blocks the post
        // when called AFTER stop(); but to exercise the inner guard, we call BEFORE stop()
        // and then stop synchronously before draining.
        factory.session.handleConfigurationChanged()
        factory.session.stop()
        drainBoth()

        val configChangedCallsAfter = factory.fakeOverlay.log.count { it == "onConfigurationChanged" }
        assertEquals("overlay.onConfigurationChanged must not be invoked after stop()",
            configChangedCallsBefore, configChangedCallsAfter)
    }

    @Test
    fun `Poll tick after stop() does NOT call snapshot`() {
        // We need to detect "snapshot was called". We use a snapshot whose access triggers a flag.
        // The IntegrationFactory's poller.snapshot lambda reads fakeConn.connectionsJson(),
        // so we wrap by toggling an atomic flag inside snapshotJson read via override.
        // Simpler: count how many times fakeConn.snapshotJson getter is read — but `var` doesn't
        // expose access counting. Instead, we assert no state transitions happen AFTER stop()
        // even if multiple poll-tick intervals elapse.

        factory.fakeConn.snapshotJson = "[]"   // initially no candidates → state stays Waiting
        factory.session.start()
        drainBoth()
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        val stateBeforeStop = factory.poller.currentState()
        val applyStateCountBefore = factory.fakeOverlay.log.count { it.startsWith("applyState(") }

        // Now stop and inject candidates. If poll ticks were still firing after stop(),
        // the poller would transition Waiting → Ready and a new applyState(Ready) would appear.
        factory.session.stop()
        factory.fakeConn.snapshotJson = oneCandidateJson("post-stop")

        // Idle far longer than the poll interval (800ms) — if ticks were still alive,
        // the state would have changed.
        shadowOf(factory.pollThread.looper).idleFor(3_000, TimeUnit.MILLISECONDS)
        drainBoth()

        val stateAfterStop = factory.poller.currentState()
        val applyStateCountAfter = factory.fakeOverlay.log.count { it.startsWith("applyState(") }

        assertEquals("poller state must not change after stop()",
            stateBeforeStop, stateAfterStop)
        assertEquals("no new applyState() calls after stop() (poll tick must not fire)",
            applyStateCountBefore, applyStateCountAfter)
        // And the overlay should have been hidden as part of stop()
        assertFalse(factory.fakeOverlay.visible)
    }
}
