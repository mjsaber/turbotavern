package com.bobassist.phase0.integration

import android.os.Build
import android.os.Looper
import com.bobassist.phase0.core.CloseResult
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlaySessionCooldownTest {

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

    private fun driveToReadyAndKill() {
        factory.fakeConn.snapshotJson = oneCandidateJson(id = "c1")
        factory.fakeConn.closeResults["c1"] = CloseResult.Success
        factory.session.start()
        drainBoth()
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Ready, factory.poller.currentState())
        factory.session.handleTap()
        drainBoth()
        assertEquals(OverlayState.Cooldown, factory.poller.currentState())
    }

    @Test
    fun `Success kill triggers Cooldown that lasts exactly 2000ms then returns to Waiting`() {
        driveToReadyAndKill()
        // Clear the snapshot so when cooldown exits, the next tick sees 0 candidates and stays Waiting
        factory.fakeConn.snapshotJson = "[]"

        // Just shy of 2s — still in Cooldown
        shadowOf(factory.pollThread.looper).idleFor(1_990, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Cooldown, factory.poller.currentState())

        // Cross the 2s boundary — exitCooldown should fire
        shadowOf(factory.pollThread.looper).idleFor(20, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.WaitingForBattle, factory.poller.currentState())
    }

    @Test
    fun `Tick during cooldown does NOT call connectionsJson`() {
        // We can't directly intercept fakeConn.connectionsJson() invocation count without
        // changing the FakeConnectionCore shared helper. Instead, we observe behavioral
        // proxies: (a) state must stay Cooldown across multiple 800ms poll cadences, and
        // (b) no new applyState calls happen on the main looper.
        factory.fakeConn.snapshotJson = oneCandidateJson("c1")
        factory.fakeConn.closeResults["c1"] = CloseResult.Success
        factory.session.start()
        drainBoth()
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        factory.session.handleTap()
        drainBoth()
        assertEquals(OverlayState.Cooldown, factory.poller.currentState())
        val applyStateCountAtCooldown = factory.fakeOverlay.log.count { it.startsWith("applyState(") }

        // Advance ~1.5s — multiple poll-tick intervals fire (800ms cadence) but
        // OverlayPoller.tick() must early-return during Cooldown (no snapshot read,
        // no emit). If a tick wrongly progressed, state would transition or applyState
        // would be invoked a second time.
        shadowOf(factory.pollThread.looper).idleFor(1_500, TimeUnit.MILLISECONDS)
        drainBoth()

        assertEquals(OverlayState.Cooldown, factory.poller.currentState())
        assertEquals(1, factory.fakeConn.closeCallLog.size)
        assertEquals("no new applyState() while in Cooldown",
            applyStateCountAtCooldown,
            factory.fakeOverlay.log.count { it.startsWith("applyState(") })
        // assertTrue stays referenced to avoid unused import after edit
        assertTrue(true)
    }

    @Test
    fun `Tap during cooldown is suppressed`() {
        driveToReadyAndKill()
        val callsAtCooldownEntry = factory.fakeConn.closeCallLog.size

        // Inject multiple taps while still in cooldown
        repeat(5) {
            factory.session.handleTap()
            shadowOf(factory.pollThread.looper).idleFor(100, TimeUnit.MILLISECONDS)
            drainBoth()
        }

        // No new close calls should have happened
        assertEquals(callsAtCooldownEntry, factory.fakeConn.closeCallLog.size)
        assertEquals(OverlayState.Cooldown, factory.poller.currentState())
    }
}
