package com.turbotavern.integration

import android.os.Build
import android.os.Looper
import com.turbotavern.overlay.OverlayState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/**
 * The gray-button failure mode is multi-kill: after 2-3 kills the button wedges grey. Every existing
 * test stops after ONE kill, so the re-arm-across-rotation path — and its two grey outcomes — were
 * untested. This models a real match: each kill closes the live host=="" :1119 socket, the client
 * reconnects with a NEW id, the 2s cooldown expires, the next poll re-finds it, and the button re-arms.
 *
 * Covers the three live-data outcomes the on-emulator experiment exercised (6/6 re-arm) and the two the
 * user's phone may hit instead: nothing reappears (H3 server backoff -> stays grey) and a DNS-resolved
 * host-present :1119 reconnect (H1 -> correctly rejected, never a false re-arm or wrong kill).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlaySessionMultiKillTest {

    private lateinit var factory: IntegrationFactory
    private fun f() = factory

    @After fun cleanup() = factory.tearDown()

    private fun drainBoth() {
        shadowOf(f().pollThread.looper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun advance(ms: Long) {
        shadowOf(f().pollThread.looper).idleFor(ms, TimeUnit.MILLISECONDS)
        drainBoth()
    }

    /** Live combat socket: host=="" tcp :1119 (the EU game-server signature seen on the emulator). */
    private fun direct(id: String, createdAt: Long) =
        """[{"id":"$id","host":"","network":"tcp","destinationIp":"5.42.177.11","destinationPort":1119,"createdAt":$createdAt}]"""
    /** Resolved lobby socket: host-present tcp :1119 — must NEVER be treated as the combat socket. */
    private fun lobby(id: String, createdAt: Long) =
        """[{"id":"$id","host":"eu.actual.battle.net","network":"tcp","destinationIp":"34.34.66.139","destinationPort":1119,"createdAt":$createdAt}]"""

    private fun start(json: String) {
        factory = IntegrationFactory()
        f().fakeConn.snapshotJson = json
        f().session.start()
        drainBoth()
        advance(1_000)
    }

    @Test
    fun `three kills rotate the id, close the live socket each time, and re-arm`() {
        start(direct("sock-1", 100))
        assertEquals(OverlayState.Ready, f().poller.currentState())

        val ids = listOf("sock-1", "sock-2", "sock-3")
        for ((k, id) in ids.withIndex()) {
            assertEquals("kill #${k + 1} should start Ready", OverlayState.Ready, f().poller.currentState())
            val before = f().fakeConn.closeCallLog.size
            f().session.handleTap()
            drainBoth()
            // exactly one close this kill, against the CURRENT rotated id
            assertEquals("kill #${k + 1} closes exactly one socket", before + 1, f().fakeConn.closeCallLog.size)
            assertEquals("kill #${k + 1} closes the live id", id, f().fakeConn.closeCallLog.last().second)
            assertEquals("kill #${k + 1} -> Cooldown", OverlayState.Cooldown, f().poller.currentState())

            // client reconnects with a fresh host=="" socket (id rotates), then cooldown expires + poll re-arms
            if (k < ids.lastIndex) f().fakeConn.snapshotJson = direct(ids[k + 1], 200L + k)
            advance(OverlayState.COOLDOWN_MS + 1_000)
        }
        // after 3 rotated kills the socket is still live -> button is Ready again, never wedged grey
        assertEquals("button must re-arm after 3 kills", OverlayState.Ready, f().poller.currentState())
        assertEquals(3, f().fakeConn.closeCallLog.size)
    }

    @Test
    fun `H3 - nothing reappears after a kill stays grey (WaitingForBattle)`() {
        start(direct("sock-1", 100))
        assertEquals(OverlayState.Ready, f().poller.currentState())

        f().session.handleTap()
        drainBoth()
        assertEquals(OverlayState.Cooldown, f().poller.currentState())

        // server applies reconnect backoff: no game socket reappears
        f().fakeConn.snapshotJson = "[]"
        advance(OverlayState.COOLDOWN_MS + 2_000)

        assertEquals("no reconnect -> button correctly grey", OverlayState.WaitingForBattle, f().poller.currentState())
    }

    @Test
    fun `H1 - a host-present 1119 reconnect is rejected, never a false re-arm`() {
        start(direct("sock-1", 100))
        assertEquals(OverlayState.Ready, f().poller.currentState())

        f().session.handleTap()
        drainBoth()
        val closesAfterKill = f().fakeConn.closeCallLog.size

        // reconnect arrives DNS-resolved (host present) — only the lobby-style socket exists now
        f().fakeConn.snapshotJson = lobby("lobby-1", 300)
        advance(OverlayState.COOLDOWN_MS + 2_000)

        // host-present :1119 is NOT the combat socket: stay grey, never re-arm
        assertEquals("host-present reconnect must not re-arm", OverlayState.WaitingForBattle, f().poller.currentState())

        // and a tap in this state closes nothing (no wrong kill of the lobby socket)
        f().session.handleTap()
        drainBoth()
        assertEquals("must not kill the resolved lobby socket", closesAfterKill, f().fakeConn.closeCallLog.size)
    }
}
