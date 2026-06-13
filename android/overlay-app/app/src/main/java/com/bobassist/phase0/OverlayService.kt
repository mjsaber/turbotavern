package com.bobassist.phase0

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.bobassist.phase0.foreground.ForegroundQuery
import com.bobassist.phase0.herotier.AndroidWindowHost
import com.bobassist.phase0.herotier.Foreground
import com.bobassist.phase0.herotier.HeroMatcher
import com.bobassist.phase0.herotier.MediaProjectionGrabber
import com.bobassist.phase0.herotier.MlKitHeroOcr
import com.bobassist.phase0.herotier.OpacityCap
import com.bobassist.phase0.herotier.OverlayBadgeRenderer
import com.bobassist.phase0.herotier.PaddleHeroOcr
import com.bobassist.phase0.herotier.StrictForeground
import com.bobassist.phase0.herotier.TierOverlay
import com.bobassist.phase0.herotier.TierTable
import com.bobassist.phase0.trinket.OverlayTrinketRenderer
import com.bobassist.phase0.trinket.SelectCoordinator
import com.bobassist.phase0.trinket.SelectWindowArbiter
import com.bobassist.phase0.trinket.TrinketMatcher
import com.bobassist.phase0.trinket.TrinketOverlay
import com.bobassist.phase0.trinket.TrinketTable
import java.io.File

/**
 * Standalone hero/trinket tier-overlay foreground service. Owns the MediaProjection capture pipeline
 * (VirtualDisplay + ImageReader + [SelectCoordinator]) and nothing else — NO VpnService, NO mihomo,
 * NO 拔线. Extracted from BobVpnService so the read-only overlay runs by itself: this service is the
 * ENTIRE `clean` Play SKU; the `full` SKU additionally runs BobVpnService for 拔线.
 *
 * Lifecycle: MainActivity obtains MediaProjection consent and starts this service with [ACTION_ENABLE_TIER];
 * the service claims the mediaProjection FGS type, builds the projection, and runs the coordinator. Any
 * projection loss / [ACTION_DISABLE_TIER] / [ACTION_STOP] tears everything down and stops the service.
 *
 * Android 14: forbids a SECOND MediaProjection#createVirtualDisplay on the same projection instance, so a
 * rotation/size change REUSES the existing VirtualDisplay (resize + re-point at a fresh ImageReader).
 */
class OverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val foregroundQuery by lazy { ForegroundQuery(this, HS_PACKAGE) }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var tierThread: HandlerThread? = null
    private var tierCoordinator: SelectCoordinator? = null
    private var captureW = 0
    private var captureH = 0
    // Debug-only manual override so the live capture->overlay path can be exercised on-device
    // (ACTION_TIER_FORCE_OPEN) before the real select-phase predicate is wired.
    @Volatile private var tierForceOpen = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            breadcrumb("tier: projection onStop")
            mainHandler.post { disableTier() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        breadcrumb("OverlayService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        breadcrumb("OverlayService onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_ENABLE_TIER -> {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)   // 0 == RESULT_CANCELED
                enableTier(code, data)
            }
            ACTION_DISABLE_TIER, ACTION_STOP -> disableTier()
            ACTION_TIER_FORCE_OPEN -> { if (BuildConfig.DEBUG) tierForceOpen = true }
            ACTION_TIER_FORCE_CLOSE -> { if (BuildConfig.DEBUG) tierForceOpen = false }
            else -> {
                // Sticky restart (null intent) or an unknown action: MediaProjection consent is one-shot,
                // so a killed-and-restarted overlay cannot rebuild capture. Stop rather than linger as a
                // zombie foreground service with no projection. (codex 1b P2)
                if (tierCoordinator == null) { breadcrumb("overlay: restart without projection; stopping"); stopSelf() }
            }
        }
        // Never auto-restart: MediaProjection consent cannot be recovered after process death.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseTierPipeline()
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        onTierConfigChanged()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Turbo Tavern", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tapPI = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Turbo Tavern")
            .setContentText("Tier overlay active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapPI)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        breadcrumb("overlay foreground notification posted")
    }

    /**
     * Enable the tier overlay: claim the mediaProjection FGS type (allowed by the fresh consent appop,
     * and required before getMediaProjection on Android 14+), build the projection + VirtualDisplay/
     * ImageReader, and start the [SelectCoordinator]. Any failure tears down and stops the service.
     */
    private fun enableTier(resultCode: Int, data: Intent?) {
        if (resultCode != -1 || data == null) {            // -1 == RESULT_OK
            breadcrumb("tier: enable without consent; stopping")
            stopSelf()
            return
        }
        if (tierCoordinator != null) { breadcrumb("tier: already enabled"); return }
        val claimed = runCatching { startForegroundNotification() }
            .onFailure { breadcrumb("tier: FGS mediaProjection claim failed: ${it.message}") }
            .isSuccess
        if (!claimed) { stopSelf(); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(resultCode, data) }
            .getOrElse { breadcrumb("tier: getMediaProjection threw: ${it.message}"); null }
        if (mp == null) { disableTier(); return }
        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp
        if (!startTierPipeline(mp)) disableTier()
    }

    /**
     * Build (or rebuild) the VirtualDisplay + ImageReader + coordinator for the current display size,
     * reusing the existing [projection] token. Returns false on failure (caller cleans up).
     */
    private fun startTierPipeline(mp: MediaProjection): Boolean {
        val info = displayInfoNow()
        captureW = info.width
        captureH = info.height
        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val vd = runCatching {
            mp.createVirtualDisplay(
                "herotier", captureW, captureH, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
            )
        }.getOrElse { breadcrumb("tier: createVirtualDisplay threw: ${it.message}"); null }
        if (vd == null) {                                  // createVirtualDisplay can return null too
            breadcrumb("tier: createVirtualDisplay returned null")
            runCatching { reader.close() }
            imageReader = null
            return false
        }
        virtualDisplay = vd
        startCoordinator(reader)
        breadcrumb("tier: pipeline up (capture ${captureW}x$captureH)")
        return true
    }

    /** Build + start the capture->ocr->match->render coordinator over [reader]. Reused across resizes. */
    private fun startCoordinator(reader: ImageReader) {
        val grabber = MediaProjectionGrabber(reader, captureW, captureH) { displayInfoNow() }
        // Density-scale badge geometry so badges are a consistent PHYSICAL size across DPIs (raw px
        // rendered tiny on high-DPI flagships like the OnePlus). (Stage 6)
        val density = resources.displayMetrics.density
        val badgePx = (BADGE_DP * density).toInt()
        val gapPx = (GAP_DP * density).toInt()
        val inflatePx = (HIGHLIGHT_INFLATE_DP * density).toInt()
        val overlay = TierOverlay(
            AndroidWindowHost(getSystemService(WindowManager::class.java)), this,
            opacityCap = { OpacityCap.of(this) },
        )
        val renderer = OverlayBadgeRenderer(overlay, badgePx, gapPx)
        // Trinket overlay shares the SAME capture + OCR (SelectCoordinator OCRs once per round and
        // feeds both matchers); only the matcher/renderer differ. A SelectWindowArbiter mutually-
        // excludes the two overlays so the hero and trinket badges never both show.
        val trinketOverlay = TrinketOverlay(
            AndroidWindowHost(getSystemService(WindowManager::class.java)), this,
            opacityCap = { OpacityCap.of(this) },
        )
        val trinketRenderer = OverlayTrinketRenderer(
            trinketOverlay, badgePx, gapPx, inflatePx,
        )
        val ht = HandlerThread("herotier").apply { start() }
        tierThread = ht
        val coordinator = SelectCoordinator(
            grabber = grabber,
            ocr = PaddleHeroOcr.create(this) ?: MlKitHeroOcr(),   // PP-OCRv5 primary; ML Kit fallback if load fails
            heroMatcher = HeroMatcher(loadTierTable()),
            heroRenderer = renderer,
            trinketMatcher = TrinketMatcher(loadTrinketTable()),
            trinketRenderer = trinketRenderer,
            foreground = { StrictForeground.of(foregroundQuery.queryForegroundPackage(), HS_PACKAGE) },
            currentRotation = { displayInfoNow().rotationDeg },
            handler = Handler(ht.looper),
            mainHandler = mainHandler,
            // §8.2 visual probe is the production trigger (Spike B: no select connection signature).
            arbiter = SelectWindowArbiter(),
            forceOpen = { BuildConfig.DEBUG && tierForceOpen },   // debug-only manual override (ACTION_TIER_FORCE_OPEN)
            breadcrumb = { msg -> breadcrumb(msg) },
        )
        tierCoordinator = coordinator
        coordinator.start()
    }

    /** Stop + drop the coordinator and its thread, keeping the projection + virtualDisplay alive. */
    private fun stopCoordinator() {
        tierCoordinator?.stop()
        tierCoordinator = null
        tierThread?.quitSafely()
        tierThread = null
    }

    /**
     * Rotation/size change. Android 14 forbids a SECOND MediaProjection#createVirtualDisplay on the
     * same projection instance (it throws and kills capture), so we REUSE the existing VirtualDisplay:
     * resize it and point it at a fresh ImageReader, then rebuild the coordinator around that reader.
     */
    private fun onTierConfigChanged() {
        val vd = virtualDisplay ?: return
        val info = displayInfoNow()
        if (info.width == captureW && info.height == captureH) return   // no real size change
        // Skip the resize ONLY for the transient portrait rotation when HS LEAVES the foreground
        // (display rotates back to the launcher): resizing the VirtualDisplay during that transition
        // REVOKES the MediaProjection on some devices (OnePlus / Android 14), which killed the overlay
        // before it ever reached hero-select. When HS is STILL foreground a portrait buffer is the
        // OrientedOcr-supported case and MUST be resized so the capture matches the live display. (codex)
        if (info.height > info.width &&
            StrictForeground.of(foregroundQuery.queryForegroundPackage(), HS_PACKAGE) != Foreground.TRUE) {
            breadcrumb("tier: skip portrait reconfigure while HS not foreground (keep projection alive)")
            return
        }
        stopCoordinator()
        val old = imageReader
        val newW = info.width
        val newH = info.height
        val reader = ImageReader.newInstance(newW, newH, PixelFormat.RGBA_8888, 2)
        // Transactional: only commit (swap reader, close old, restart) if resize+setSurface succeed.
        val ok = runCatching {
            vd.resize(newW, newH, resources.displayMetrics.densityDpi)
            vd.surface = reader.surface
        }.onFailure { breadcrumb("tier: vd resize failed: ${it.message}") }.isSuccess
        if (!ok) {                                         // VD may still point at the old surface
            runCatching { reader.close() }                 // drop the unused new reader; keep old intact
            disableTier()
            return
        }
        captureW = newW
        captureH = newH
        imageReader = reader
        runCatching { old?.close() }
        startCoordinator(reader)
        breadcrumb("tier: resized to ${captureW}x$captureH")
    }

    /** Full teardown of the capture pipeline AND the VirtualDisplay (keeps the projection token). */
    private fun releaseTierPipeline() {
        stopCoordinator()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
    }

    /** Fully disable the overlay and stop the service (the overlay is this service's only purpose). */
    private fun disableTier() {
        releaseTierPipeline()
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        tierForceOpen = false
        breadcrumb("tier: disabled")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun loadTierTable(): TierTable =
        runCatching {
            TierTable.fromJson(assets.open(TIER_ASSET).bufferedReader().use { it.readText() })
        }.getOrElse {
            breadcrumb("tier: asset $TIER_ASSET missing/invalid -> empty table (${it.message})")
            TierTable.fromJson("""{"heroes":[]}""")
        }

    private fun loadTrinketTable(): TrinketTable =
        runCatching {
            TrinketTable.fromJson(assets.open(TRINKET_ASSET).bufferedReader().use { it.readText() })
        }.getOrElse {
            breadcrumb("tier: asset $TRINKET_ASSET missing/invalid -> empty trinket table (${it.message})")
            TrinketTable.fromJson("""{"trinkets":[]}""")
        }

    private fun displayInfoNow(): MediaProjectionGrabber.DisplayInfo {
        // A Service is a non-visual Context, so Context.getDisplay()/currentWindowMetrics throw.
        // Resolve the default Display via DisplayManager, which works from any context.
        val disp = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") disp.getRealMetrics(m)        // real (full-screen) pixels
        return MediaProjectionGrabber.DisplayInfo(m.widthPixels, m.heightPixels, disp.rotation)
    }

    private fun breadcrumb(msg: String) {
        Log.i(TAG, msg)
        runCatching {
            File(filesDir, "bob-breadcrumbs.log")
                .appendText("${System.currentTimeMillis()}: $msg\n")
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIF_ID = 1002
        private const val CHANNEL_ID = "bob_overlay"

        const val ACTION_ENABLE_TIER = "com.bobassist.phase0.ENABLE_TIER"
        const val ACTION_DISABLE_TIER = "com.bobassist.phase0.DISABLE_TIER"
        const val ACTION_STOP = "com.bobassist.phase0.OVERLAY_STOP"
        const val ACTION_TIER_FORCE_OPEN = "com.bobassist.phase0.TIER_FORCE_OPEN"   // debug
        const val ACTION_TIER_FORCE_CLOSE = "com.bobassist.phase0.TIER_FORCE_CLOSE" // debug
        const val EXTRA_RESULT_CODE = "tier_result_code"
        const val EXTRA_RESULT_DATA = "tier_result_data"
        private const val TIER_ASSET = "herotier_v1.json"
        private const val TRINKET_ASSET = "trinkettier_v1.json"
        private const val BADGE_DP = 24               // density-scaled at render time (Stage 6)
        private const val GAP_DP = 4
        private const val HIGHLIGHT_INFLATE_DP = 5    // green ring sits just outside the trinket name
    }
}
