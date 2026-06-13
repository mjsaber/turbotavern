package com.turbotavern.integration

import android.os.Build
import android.os.Looper
import com.turbotavern.overlay.OverlayState
import com.turbotavern.session.OverlaySession
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
 * Post-kill table dumps (debt #9): after a Success kill the session samples the
 * raw connection table on a burst schedule denser than the 800ms poll, so one
 * on-device match records what the table looks like while the button is grey.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlaySessionPostKillDumpTest {

    private lateinit var factory: IntegrationFactory

    @Before
    fun setup() {
        factory = IntegrationFactory(traceEnabled = true)
    }

    @After
    fun cleanup() {
        factory.tearDown()
    }

    private fun drainBoth() {
        shadowOf(factory.pollThread.looper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun oneCandidateJson(id: String): String =
        """[{"id":"$id","host":"","network":"tcp","destinationIp":"66.40.189.71","destinationPort":3724,"createdAt":100}]"""

    private fun tapOnReady(id: String) {
        factory.fakeConn.snapshotJson = oneCandidateJson(id)
        factory.session.start()
        drainBoth()
        shadowOf(factory.pollThread.looper).idleFor(1_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(OverlayState.Ready, factory.poller.currentState())
        factory.session.handleTap()
        drainBoth()
    }

    private fun dumpLines() = factory.traceOutput.filter { it.contains("phase=postkill_table") }

    @Test
    fun `success kill schedules one dump per burst offset with killed id and explained table`() {
        tapOnReady("battle-99")
        shadowOf(factory.pollThread.looper).idleFor(9_000, TimeUnit.MILLISECONDS)
        drainBoth()

        val dumps = dumpLines()
        assertEquals(dumps.joinToString("\n"), OverlaySession.POST_KILL_DUMP_DELAYS_MS.size, dumps.size)
        dumps.forEach { line ->
            assertTrue(line, line.contains("killed=battle-99"))
            assertTrue(line, line.contains("state="))
            // FakeConnectionCore's table is static, so every dump sees the OK socket.
            assertTrue(line, line.contains("OK tcp 66.40.189.71:3724"))
        }
        // Each burst offset appears exactly once.
        OverlaySession.POST_KILL_DUMP_DELAYS_MS.forEach { delay ->
            assertEquals("offset t+${delay}ms", 1, dumps.count { it.contains("event=t+${delay}ms") })
        }
    }

    @Test
    fun `stop cancels pending dumps`() {
        tapOnReady("battle-77")
        // drainBoth() in tapOnReady already ran the t+0 dump; nothing later has fired yet.
        val firedBeforeStop = dumpLines().size
        factory.session.stop()
        shadowOf(factory.pollThread.looper).idleFor(9_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(firedBeforeStop, dumpLines().size)
        assertTrue(firedBeforeStop < OverlaySession.POST_KILL_DUMP_DELAYS_MS.size)
    }

    @Test
    fun `non-success kill schedules no dumps`() {
        factory.fakeConn.closeResults["battle-55"] =
            com.turbotavern.core.CloseResult.AlreadyClosed
        tapOnReady("battle-55")
        shadowOf(factory.pollThread.looper).idleFor(9_000, TimeUnit.MILLISECONDS)
        drainBoth()
        assertEquals(0, dumpLines().size)
    }
}
