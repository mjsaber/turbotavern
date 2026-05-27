package com.bobassist.phase0.integration

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
class RobolectricSmokeTest {

    @Test
    fun `main looper exists and is idle initially`() {
        assertNotNull(Looper.getMainLooper())
        assertTrue(shadowOf(Looper.getMainLooper()).isIdle())
    }

    @Test
    fun `SystemClock advances when ShadowLooper idleFor is called`() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS)
        val t1 = SystemClock.elapsedRealtimeNanos()
        assertEquals(2_000L * 1_000_000L, t1 - t0)
    }
}
