package com.bobassist.phase0.integration

import android.os.Build
import android.os.Looper
import com.bobassist.phase0.core.MihomoCore
import com.bobassist.phase0.overlay.OverlayState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/**
 * Phase 1.4 integration tests: tap closes the CACHED candidate directly, with no
 * connectionsJson() on the tap path. Exercises the real Handler/HandlerThread wiring
 * via Robolectric ShadowLoopers. Covers spec §6 T1–T9.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlaySessionCacheTest {

    private var factory: IntegrationFactory? = null

    private fun f(): IntegrationFactory = factory!!

    @After
    fun cleanup() {
        factory?.tearDown()
    }

    private fun drainBoth(ms: Long = 0) {
        if (ms > 0) shadowOf(f().pollThread.looper).idleFor(ms, TimeUnit.MILLISECONDS)
        shadowOf(f().pollThread.looper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun oneCandidateJson(id: String = "conn-1", createdAt: Long = 100L): String =
        """[{"id":"$id","host":"","network":"tcp","destinationIp":"1.2.3.4","destinationPort":3724,"createdAt":$createdAt}]"""

    /** Start a session and advance one poll tick so it settles to the expected state. */
    private fun startAndSettle(json: String, traceEnabled: Boolean = false) {
        factory = IntegrationFactory(traceEnabled = traceEnabled)
        f().fakeConn.snapshotJson = json
        f().session.start()
        drainBoth()
        shadowOf(f().pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
    }

    // --- T1: tap uses the cache, never re-snapshots ---
    @Test
    fun `T1 tap on Ready closes cached id without calling connectionsJson`() {
        startAndSettle(oneCandidateJson(id = "battle-1"))
        assertEquals(OverlayState.Ready, f().poller.currentState())

        val snapshotsBeforeTap = f().fakeConn.snapshotCallLog.size
        f().session.handleTap()
        drainBoth()

        assertEquals("tap must not take a fresh snapshot",
            snapshotsBeforeTap, f().fakeConn.snapshotCallLog.size)
        assertEquals(1, f().fakeConn.closeCallLog.size)
        assertEquals("battle-1", f().fakeConn.closeCallLog[0].second)
    }

    // --- T2: INV-1 (Ready ⟹ cached candidate) ---
    @Test
    fun `T2 ready implies cached candidate and empty implies none`() {
        startAndSettle(oneCandidateJson(id = "battle-2"))
        assertEquals(OverlayState.Ready, f().poller.currentState())
        assertNotNull(f().candidateCache.current().candidate)
        assertEquals("battle-2", f().candidateCache.current().candidate?.id)

        f().fakeConn.snapshotJson = "[]"
        shadowOf(f().pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.WaitingForBattle, f().poller.currentState())
        assertNull(f().candidateCache.current().candidate)
    }

    // --- T3: stale cache → close NotFound → graceful failure, no cooldown (INV-3) ---
    @Test
    fun `T3 stale cached id NotFound stays Ready and does not cooldown`() {
        startAndSettle(oneCandidateJson(id = "stale-1"))
        assertEquals(OverlayState.Ready, f().poller.currentState())
        f().fakeConn.closeResults["stale-1"] = MihomoCore.CloseResult.NotFound

        f().session.handleTap()
        drainBoth()

        assertEquals(1, f().fakeConn.closeCallLog.size)
        assertEquals("must not enter Cooldown on a non-Success kill",
            OverlayState.Ready, f().poller.currentState())
    }

    // --- T4: rapid-tap still exactly one close (INV-4) ---
    @Test
    fun `T4 rapid taps close exactly once`() {
        startAndSettle(oneCandidateJson(id = "rapid-1"))
        assertEquals(OverlayState.Ready, f().poller.currentState())

        repeat(5) { f().session.handleTap() }
        drainBoth()

        assertEquals(1, f().fakeConn.closeCallLog.size)
        assertEquals(OverlayState.Cooldown, f().poller.currentState())
    }

    // --- T5: state transition clears readiness when candidate disappears ---
    @Test
    fun `T5 candidate disappearing transitions to WaitingForBattle and empties cache`() {
        startAndSettle(oneCandidateJson(id = "gone-1"))
        assertEquals(OverlayState.Ready, f().poller.currentState())
        assertNotNull(f().candidateCache.current().candidate)

        f().fakeConn.snapshotJson = "[]"
        shadowOf(f().pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()

        assertEquals(OverlayState.WaitingForBattle, f().poller.currentState())
        assertNull(f().candidateCache.current().candidate)
    }

    // --- T6: tap accepted then stop() → tap is a no-op (teardown liveness) ---
    @Test
    fun `T6 tap then stop does not close`() {
        startAndSettle(oneCandidateJson(id = "teardown-1"))
        assertEquals(OverlayState.Ready, f().poller.currentState())

        f().session.handleTap()
        f().session.stop()      // before draining the queued tap runnable
        drainBoth()

        assertTrue("stopped session must not close",
            f().fakeConn.closeCallLog.isEmpty())
    }

    // --- T7: tap during Cooldown is rejected; cooldown still expires normally ---
    @Test
    fun `T7 tap during cooldown rejected and cooldown still expires`() {
        startAndSettle(oneCandidateJson(id = "cool-1"))
        assertEquals(OverlayState.Ready, f().poller.currentState())

        f().session.handleTap()        // → Success → Cooldown
        drainBoth()
        assertEquals(OverlayState.Cooldown, f().poller.currentState())
        val closesAfterFirst = f().fakeConn.closeCallLog.size

        f().session.handleTap()        // during cooldown: rejected
        drainBoth()
        assertEquals(closesAfterFirst, f().fakeConn.closeCallLog.size)

        // Cooldown expires (COOLDOWN_MS) → back to WaitingForBattle (snapshot still has candidate,
        // but exitCooldown emits WaitingForBattle; next tick re-evaluates).
        shadowOf(f().pollThread.looper).idleFor(OverlayState.COOLDOWN_MS + 1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertTrue("cooldown should have exited",
            f().poller.currentState() != OverlayState.Cooldown)
    }

    // --- T8: tap before a queued poll tick reads stale state (no false close) ---
    @Test
    fun `T8 tap before queued poll uses stale state and does not close`() {
        startAndSettle("[]")   // settles to WaitingForBattle, empty cache
        assertEquals(OverlayState.WaitingForBattle, f().poller.currentState())

        // A candidate now exists, but NO poll tick has refreshed the cache yet.
        f().fakeConn.snapshotJson = oneCandidateJson(id = "future-1")

        f().session.handleTap()   // runs before the next periodic poll tick is due
        drainBoth()               // no time advance → pending periodic tick does not fire

        assertTrue("stale read must not close anything",
            f().fakeConn.closeCallLog.isEmpty())
        assertEquals(OverlayState.WaitingForBattle, f().poller.currentState())

        // Once the poll tick fires, the candidate is discovered.
        shadowOf(f().pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Ready, f().poller.currentState())
    }

    // --- T10: pause clears the cache AND drops to WaitingForBattle so the button never lies green
    //          during the ≤1-poll window after resume (codex gate-3 P2 + green-button-lies fix) ---
    @Test
    fun `T10 pausing clears cache and shows WaitingForBattle so a stale tap is a safe no-op`() {
        startAndSettle(oneCandidateJson(id = "pause-1"))
        assertEquals(OverlayState.Ready, f().poller.currentState())
        assertNotNull(f().candidateCache.current().candidate)

        // HS goes background → poller pauses, clears the cache, and stops claiming Ready.
        f().session.handleForegroundChange(false)
        drainBoth()
        assertNull("pause must clear the cache", f().candidateCache.current().candidate)
        // The button must NOT stay green while we are no longer polling — it would be a dead tap.
        assertEquals("pause must drop to WaitingForBattle (no lying-green button)",
            OverlayState.WaitingForBattle, f().poller.currentState())

        f().session.handleTap()
        drainBoth()

        assertTrue("stale tap must not close anything",
            f().fakeConn.closeCallLog.isEmpty())
        assertEquals("must not falsely enter cooldown",
            OverlayState.WaitingForBattle, f().poller.currentState())
    }

    // --- T10b: after resume, the next poll re-arms the button to Ready (no permanent grey) ---
    @Test
    fun `T10b resume re-arms to Ready on the next poll`() {
        startAndSettle(oneCandidateJson(id = "resume-1"))
        assertEquals(OverlayState.Ready, f().poller.currentState())

        f().session.handleForegroundChange(false)   // pause -> WaitingForBattle
        drainBoth()
        assertEquals(OverlayState.WaitingForBattle, f().poller.currentState())

        f().session.handleForegroundChange(true)     // resume
        drainBoth()
        shadowOf(f().pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals("resume + one poll re-arms the live socket", OverlayState.Ready, f().poller.currentState())
    }

    // --- T9: INV-5 trace fields are emitted (snapshot_ms + cache_age_ms) ---
    @Test
    fun `T9 trace emits poll_snapshot snapshot_ms and tap cache_age_ms`() {
        startAndSettle(oneCandidateJson(id = "trace-1"), traceEnabled = true)
        assertEquals(OverlayState.Ready, f().poller.currentState())

        f().session.handleTap()
        drainBoth()

        val lines = f().traceOutput.toList()
        assertTrue("expected a poll_snapshot exit with snapshot_ms, got:\n${lines.joinToString("\n")}",
            lines.any { it.contains("phase=poll_snapshot") && it.contains("event=exit") && it.contains("snapshot_ms=") })
        assertTrue("expected a tap state_check with cache_age_ms, got:\n${lines.joinToString("\n")}",
            lines.any { it.contains("phase=state_check") && it.contains("cache_age_ms=") })
    }
}
