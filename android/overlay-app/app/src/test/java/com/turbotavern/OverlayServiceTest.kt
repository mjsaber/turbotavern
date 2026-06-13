package com.turbotavern

import android.app.Service
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class OverlayServiceTest {

    /**
     * MediaProjection consent is one-shot: a sticky restart hands onStartCommand a null intent with no
     * way to rebuild capture. The service must NOT ask to be kept alive (START_NOT_STICKY) and must stop
     * itself instead of lingering as a zombie foreground service. (codex 1b P2)
     */
    @Test fun stickyRestartWithoutProjectionStopsItselfAndIsNotSticky() {
        val service = Robolectric.buildService(OverlayService::class.java).create().get()
        val ret = service.onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, ret)
        assertTrue("service stops itself when it can't rebuild capture", shadowOf(service).isStoppedBySelf)
    }
}
