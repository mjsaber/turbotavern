package com.bobassist.phase0.herotier

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class OpacityCapTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test @Config(sdk = [Build.VERSION_CODES.R])                       // API 30 < 31
    fun preApi31IsFullOpacity() = assertEquals(1f, OpacityCap.of(ctx), 0f)

    @Test @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])        // API 34 >= 31
    fun api31PlusIsPositiveAndCapped() {
        val cap = OpacityCap.of(ctx)
        assertTrue("cap in (0,1]: $cap", cap > 0f && cap <= 1f)
    }
}
