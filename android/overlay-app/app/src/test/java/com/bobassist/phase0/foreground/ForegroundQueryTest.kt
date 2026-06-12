package com.bobassist.phase0.foreground

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ForegroundQueryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val usm = context.getSystemService(UsageStatsManager::class.java)!!
    private val appOps = context.getSystemService(AppOpsManager::class.java)!!
    private val HS = "com.blizzard.wtcg.hearthstone"

    private fun setUsageAccess(mode: Int) = shadowOf(appOps).setMode(
        AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, context.packageName, mode,
    )

    private fun resume(pkg: String, ts: Long) =
        shadowOf(usm).addEvent(pkg, ts, UsageEvents.Event.ACTIVITY_RESUMED)

    @Before fun grantUsageAccess() = setUsageAccess(AppOpsManager.MODE_ALLOWED)

    @Test fun returnsLatestResumedPackage() {
        val t = 1_000_000L
        val fq = ForegroundQuery(context, HS, now = { t })
        resume("com.other.app", t - 5_000)
        resume(HS, t - 1_000)
        assertEquals(HS, fq.queryForegroundPackage())
    }

    @Test fun emptyWindowKeepsStickyLastPackage() {
        var t = 1_000_000L
        val fq = ForegroundQuery(context, HS, now = { t })
        resume(HS, t - 1_000)
        assertEquals(HS, fq.queryForegroundPackage())
        // Advance past the 60s window: an entire uninterrupted BG match produces no new RESUMED
        // events, so the next query sees an empty window and must KEEP the last package, not go null.
        t += 120_000
        assertEquals("sticky keeps last package on an empty window", HS, fq.queryForegroundPackage())
    }

    @Test fun noEventsReturnsNull() {
        val fq = ForegroundQuery(context, HS, now = { 1_000_000L })
        assertNull(fq.queryForegroundPackage())
    }

    @Test fun revokedUsageAccessForgetsStickyAndReturnsNull() {
        val t = 1_000_000L
        val fq = ForegroundQuery(context, HS, now = { t })
        resume(HS, t - 1_000)
        assertEquals(HS, fq.queryForegroundPackage())    // sticky now = HS
        setUsageAccess(AppOpsManager.MODE_IGNORED)        // user revokes Usage Access mid-session
        assertNull("revoked usage access must not return a stale sticky", fq.queryForegroundPackage())
    }
}
