package com.bobassist.phase0.integration

import android.os.Build
import android.os.Looper
import com.bobassist.phase0.core.MihomoCore
import com.bobassist.phase0.overlay.OverlayState
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Integration tests for OverlaySession tap-handling, exercising the real
 * Handler/HandlerThread wiring with Robolectric ShadowLoopers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlaySessionTapTest {

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

    /** Single live HS battle socket fixture (passes BattleConnection filter). */
    private fun oneCandidateJson(id: String = "conn-1", createdAt: Long = 100L): String =
        """[{"id":"$id","host":"","network":"tcp","destinationIp":"1.2.3.4","destinationPort":3724,"createdAt":$createdAt}]"""

    @Test
    fun `tap on WaitingForBattle is no-op (no close call, no state change)`() {
        factory.fakeConn.snapshotJson = "[]"   // no candidates
        factory.session.start()
        drainBoth()
        // Run one or two ticks to make sure state settles to WaitingForBattle
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.WaitingForBattle, factory.poller.currentState())

        factory.session.handleTap()
        drainBoth()

        assertTrue("expected no close calls, got ${factory.fakeConn.closeCallLog}",
            factory.fakeConn.closeCallLog.isEmpty())
        assertEquals(OverlayState.WaitingForBattle, factory.poller.currentState())
    }

    @Test
    fun `tap on Ready calls closeConnection with the picked candidate id`() {
        factory.fakeConn.snapshotJson = oneCandidateJson(id = "battle-99")
        factory.session.start()
        drainBoth()
        // Advance to allow a poll tick to fire and transition to Ready
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Ready, factory.poller.currentState())

        factory.session.handleTap()
        drainBoth()

        assertEquals(1, factory.fakeConn.closeCallLog.size)
        assertEquals("battle-99", factory.fakeConn.closeCallLog[0].second)
    }

    @Test
    fun `tap on Cooldown is suppressed`() {
        factory.fakeConn.snapshotJson = oneCandidateJson(id = "battle-cool")
        factory.session.start()
        drainBoth()
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Ready, factory.poller.currentState())

        // First tap → Success → Cooldown
        factory.session.handleTap()
        drainBoth()
        assertEquals(OverlayState.Cooldown, factory.poller.currentState())
        val callsAfterFirstTap = factory.fakeConn.closeCallLog.size

        // Second tap while in Cooldown: must be suppressed (no further close calls)
        factory.session.handleTap()
        drainBoth()
        assertEquals(callsAfterFirstTap, factory.fakeConn.closeCallLog.size)
        assertEquals(OverlayState.Cooldown, factory.poller.currentState())
    }

    @Test
    fun `tap on Ready that returns Success transitions to Cooldown`() {
        factory.fakeConn.snapshotJson = oneCandidateJson(id = "battle-ok")
        factory.fakeConn.closeResults["battle-ok"] = MihomoCore.CloseResult.Success
        factory.session.start()
        drainBoth()
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Ready, factory.poller.currentState())

        factory.session.handleTap()
        drainBoth()

        assertEquals(OverlayState.Cooldown, factory.poller.currentState())
        assertEquals(1, factory.fakeConn.closeCallLog.size)
    }
}
