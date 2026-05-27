package com.bobassist.phase0.integration

import android.os.Build
import android.os.Looper
import com.bobassist.phase0.overlay.OverlayState
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlaySessionForegroundTest {

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
    fun `Detector reports HS false then overlay setVisible(false) called on main looper`() {
        factory.fakeConn.snapshotJson = "[]"
        factory.session.start()
        drainBoth()

        // Simulate HS going to background by flipping the override and calling detector.tick directly
        factory.hsForegroundOverride = false
        factory.detector.tick()
        drainBoth()

        // overlay.setVisible(false) should have been recorded
        assertTrue("expected setVisible(false) in log: ${factory.fakeOverlay.log}",
            factory.fakeOverlay.log.contains("setVisible(false)"))
        assertFalse(factory.fakeOverlay.visible)
    }

    @Test
    fun `Detector reports HS true then overlay setVisible(true) called and poller resumes`() {
        factory.fakeConn.snapshotJson = oneCandidateJson("c1")
        factory.session.start()
        drainBoth()

        // First go to background
        factory.hsForegroundOverride = false
        factory.detector.tick()
        drainBoth()
        assertFalse(factory.fakeOverlay.visible)

        // Now back to foreground
        factory.hsForegroundOverride = true
        factory.detector.tick()
        drainBoth()

        assertTrue("expected setVisible(true) in log: ${factory.fakeOverlay.log}",
            factory.fakeOverlay.log.contains("setVisible(true)"))
        assertTrue(factory.fakeOverlay.visible)

        // After resume, poller can advance to Ready when next poll tick fires
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Ready, factory.poller.currentState())
    }

    @Test
    fun `Hide and re-show preserves lastState (Ready stays Ready)`() {
        factory.fakeConn.snapshotJson = oneCandidateJson("c1")
        factory.session.start()
        drainBoth()
        // Drive to Ready
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Ready, factory.poller.currentState())
        assertEquals(OverlayState.Ready, factory.fakeOverlay.lastState)

        // Hide via fg change false
        factory.hsForegroundOverride = false
        factory.detector.tick()
        drainBoth()

        // OverlayPoller.currentState() should still report Ready (pause does not reset state)
        assertEquals(OverlayState.Ready, factory.poller.currentState())
        // FakeOverlay's lastState should still be Ready (no applyState(Waiting) fired)
        assertEquals(OverlayState.Ready, factory.fakeOverlay.lastState)

        // Re-show
        factory.hsForegroundOverride = true
        factory.detector.tick()
        drainBoth()

        // After re-show, lastState is still Ready (the snapshot still has the candidate)
        assertEquals(OverlayState.Ready, factory.poller.currentState())
        assertEquals(OverlayState.Ready, factory.fakeOverlay.lastState)
    }
}
