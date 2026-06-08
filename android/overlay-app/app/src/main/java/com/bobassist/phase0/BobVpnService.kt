package com.bobassist.phase0

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.bobassist.phase0.core.BattleCandidateCache
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.core.ConnectionCoreProvider
import com.bobassist.phase0.core.ForegroundOverrideHolder
import com.bobassist.phase0.core.RealLifecycleCore
import com.bobassist.phase0.foreground.ForegroundDetector
import com.bobassist.phase0.herotier.AndroidWindowHost
import com.bobassist.phase0.herotier.Foreground
import com.bobassist.phase0.herotier.HeroMatcher
import com.bobassist.phase0.herotier.HeroTierCoordinator
import com.bobassist.phase0.herotier.MediaProjectionGrabber
import com.bobassist.phase0.herotier.MlKitHeroOcr
import com.bobassist.phase0.herotier.OpacityCap
import com.bobassist.phase0.herotier.OverlayBadgeRenderer
import com.bobassist.phase0.herotier.PaddleHeroOcr
import com.bobassist.phase0.herotier.StrictForeground
import com.bobassist.phase0.herotier.TierOverlay
import com.bobassist.phase0.herotier.TierTable
import com.bobassist.phase0.herotier.VisualProbeGate
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.overlay.OverlayWindow
import com.bobassist.phase0.session.OverlaySession
import com.bobassist.phase0.util.AndroidElapsedRealtimeClock
import com.bobassist.phase0.util.TraceSink
import java.io.File

/**
 * Phase 0 single foreground VpnService. Owns mihomo lifecycle and the TUN
 * file descriptor; nothing else.
 *
 * VPN parameters mirror CMFA's TunService:
 *   TUN_GATEWAY        = 10.99.0.1
 *   TUN_SUBNET_PREFIX  = 30
 *   TUN_PORTAL/DNS     = 10.99.0.2
 *   TUN_MTU            = 9000
 *
 * Only HS's package is in addAllowedApplication so only its traffic enters the TUN.
 */
class BobVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var coreRunning = false

    private var session: OverlaySession? = null
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var overlayRunning = false   // idempotency guard (P1 #4)

    // --- hero-tier overlay (additive; independent of the kill-button path above) ---
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var tierThread: HandlerThread? = null
    private var tierCoordinator: HeroTierCoordinator? = null
    private var captureW = 0
    private var captureH = 0
    // Stage 9.4 (post-Spike B) wires the real select-phase predicate. Until then the window is
    // driven by this debug flag so the live capture->overlay path can be exercised on-device.
    @Volatile private var tierForceOpen = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            breadcrumb("tier: projection onStop")
            mainHandler.post { disableTier() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        breadcrumb("onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        breadcrumb("onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                tearDown()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ENABLE_TIER -> {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)   // 0 == RESULT_CANCELED
                enableTier(code, data)
                return START_STICKY
            }
            ACTION_DISABLE_TIER -> { disableTier(); return START_STICKY }
            ACTION_TIER_FORCE_OPEN -> { if (BuildConfig.DEBUG) tierForceOpen = true; return START_STICKY }
            ACTION_TIER_FORCE_CLOSE -> { if (BuildConfig.DEBUG) tierForceOpen = false; return START_STICKY }
            else -> startForegroundNotification()
        }
        bringUp()
        return START_STICKY
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        session?.handleConfigurationChanged()
        onTierConfigChanged()
    }

    private fun bringUp() {
        if (coreRunning) {
            breadcrumb("bringUp called while already running; ignoring")
            return
        }
        breadcrumb("bringUp begin")

        // Install protect callback BEFORE Setup so the very first dial
        // (mihomo internal init) is also protected.
        runCatching { RealLifecycleCore.setProtector(this) }
            .onFailure {
                breadcrumb("setProtector failed: ${it.message}")
                Log.e(TAG, "setProtector failed", it)
                stopSelf()
                return
            }
        breadcrumb("setProtector OK")

        runCatching {
            RealLifecycleCore.setup(cacheDir.absolutePath).getOrThrow()
        }.onFailure {
            breadcrumb("MihomoCore.setup failed: ${it.message}")
            Log.e(TAG, "MihomoCore.setup failed", it)
            stopSelf()
            return
        }
        breadcrumb("MihomoCore.setup OK")

        val builder = Builder()
            .setSession("Bob Assistant")
            .addAddress(TUN_GATEWAY, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TUN_PORTAL)
            .setMtu(TUN_MTU)
            .setBlocking(false)
        // addAllowedApplication throws PackageManager.NameNotFoundException
        // when HS isn't installed. Spike B test device always has it; future
        // builds must handle gracefully.
        val allowedOk = runCatching { builder.addAllowedApplication(HS_PACKAGE) }
            .onFailure {
                breadcrumb("addAllowedApplication($HS_PACKAGE) failed: ${it.message}")
                Log.e(TAG, "addAllowedApplication($HS_PACKAGE) failed", it)
            }
            .isSuccess
        if (!allowedOk) {
            stopSelf()
            return
        }
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )

        val newPfd = runCatching { builder.establish() }
            .onFailure {
                breadcrumb("establish() threw: ${it.message}")
                Log.e(TAG, "establish() threw", it)
            }
            .getOrNull()
        if (newPfd == null) {
            breadcrumb("establish() returned null")
            stopSelf()
            return
        }
        pfd = newPfd
        breadcrumb("establish OK")

        val fd = newPfd.detachFd()
        breadcrumb("starting TUN with fd=$fd")

        val gateway = "$TUN_GATEWAY/$TUN_PREFIX"
        RealLifecycleCore.startTun(fd, TUN_STACK, gateway, TUN_PORTAL).onFailure { err ->
            breadcrumb("MihomoCore.startTun failed: ${err.message}")
            Log.e(TAG, "MihomoCore.startTun failed; reclaiming fd $fd", err)
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            stopSelf()
        }.onSuccess {
            coreRunning = true
            breadcrumb("MihomoCore.startTun OK; HS traffic routed through TUN")
            Log.i(TAG, "TUN listener up, fd=$fd")
            startOverlayAndPolling()
        }
    }

    private fun startOverlayAndPolling() {
        if (overlayRunning) {
            breadcrumb("startOverlayAndPolling called while already running; ignoring")
            return
        }

        // codex round-4 P1 #34: Task 6 swaps these two lambdas to RealConnectionCore;
        // Task 7 swaps again to ConnectionCoreProvider.get(). Task 5 calls MihomoCore directly.
        val controller = BattleConnectionController(
            snapshot = { ConnectionCoreProvider.get().connectionsJson() },
            close = { id -> ConnectionCoreProvider.get().closeConnection(id) },
        )
        val trace = TraceSink(enabled = BuildConfig.DEBUG, clock = AndroidElapsedRealtimeClock)

        // Phase 1.4: the poll loop refreshes this cache each tick; the tap path reads the
        // cached candidate and closes it directly, so connectionsJson() is off the tap
        // critical path. trace lets refresh() emit `poll_snapshot` snapshot_ms (Step 0).
        val candidateCache = BattleCandidateCache(
            snapshot = { ConnectionCoreProvider.get().connectionsJson() },
            clock = AndroidElapsedRealtimeClock,
            trace = trace,
        )

        val ow = OverlayWindow(this, onTap = { session?.handleTap() })
        val ht = HandlerThread("BobOverlayPoll").apply { start() }
        val handler = Handler(ht.looper)
        pollThread = ht
        pollHandler = handler

        val poller = OverlayPoller(
            snapshot = {
                // Guarded against teardown races (P1 #5): if mihomo has stopped,
                // count as 0 candidates so the next tick safely emits Waiting.
                runCatching {
                    candidateCache.refresh()
                }.getOrElse { err ->
                    breadcrumb("poll snapshot failed: ${err.message}")
                    0
                }
            },
            onStateChange = { state -> mainHandler.post { ow.applyState(state) } },
            scheduleAfter = { delayMs, cb -> handler.postDelayed(cb, delayMs) },
            clock = AndroidElapsedRealtimeClock,
            trace = trace,
        )
        val detector = ForegroundDetector(
            queryForegroundPackage = { queryForegroundPackage() },
            targetPackage = HS_PACKAGE,
            onChange = { isFg -> session?.handleForegroundChange(isFg) },
            trace = trace,
        )
        val newSession = OverlaySession(
            controller = controller,
            candidateCache = candidateCache,
            poller = poller,
            detector = detector,
            overlay = ow,
            pollHandler = handler,
            mainHandler = mainHandler,
            clock = AndroidElapsedRealtimeClock,
            trace = trace,
            hasUsageAccessPermission = { hasUsageAccessPermission() },
            breadcrumb = { msg -> breadcrumb(msg) },
        )
        this.session = newSession
        liveSession = newSession

        newSession.start()       // OverlaySession now owns tick scheduling internally
        overlayRunning = true
        breadcrumb("overlay + poller started")    // KEEP old wording for sim script backward compat (codex P2 #10)
        breadcrumb("session started")             // ALSO emit new wording
    }

    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationInfo.uid,
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Queries UsageStatsManager for the latest ACTIVITY_RESUMED event in the
     * last 60 s. Returns the foreground package name, or null if the query
     * returned an empty/null events stream — interpreted by the detector as
     * "no recent events, keep previous state". Does NOT inspect permission
     * state; the caller (detectorTick) decides between tick() vs reset()
     * based on hasUsageAccessPermission(). See codex P1 #2 and round-1 P2 #5.
     */
    private fun queryForegroundPackage(): String? {
        // Variant-routed override (debug builds may inject a fake foreground state).
        // Release builds resolve to NoOpForegroundOverride which always returns null.
        // No BuildConfig.DEBUG gate needed — the holder is selected per variant.
        val fakeFg = ForegroundOverrideHolder.get().foregroundOverride()
        if (fakeFg != null) return if (fakeFg) HS_PACKAGE else "com.example.notbob"

        val usm = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        // queryEvents can return null when the user is locked (R+) — handle it.
        val events = runCatching { usm.queryEvents(now - 60_000L, now) }
            .getOrNull() ?: return null
        var latestTs = 0L
        var latestPkg: String? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && ev.timeStamp >= latestTs) {
                latestTs = ev.timeStamp
                latestPkg = ev.packageName
            }
        }
        return latestPkg
    }

    private fun tearDown() {
        disableTier(restoreForeground = false)   // service is stopping; don't re-post foreground
        liveSession = null
        overlayRunning = false
        session?.stop()
        session = null

        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        pollThread = null
        pollHandler = null

        if (coreRunning) {
            runCatching { RealLifecycleCore.stopTun() }
                .onFailure { Log.e(TAG, "MihomoCore.stopTun failed", it) }
            coreRunning = false
        }
        runCatching { pfd?.close() }
        pfd = null
    }

    private fun startForegroundNotification(withProjection: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bob VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tapIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tapPI = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bob Assistant (Phase 0)")
            .setContentText("Hearthstone traffic routed through TUN")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(tapPI)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            // The mediaProjection type can only be claimed once we hold consent (enableTier).
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        breadcrumb("foreground notification posted (projection=$withProjection)")
    }

    /**
     * Enable the hero-tier overlay: claim the mediaProjection FGS type, build the projection +
     * VirtualDisplay/ImageReader, and start a [HeroTierCoordinator] on its own HandlerThread.
     * Purely additive — the kill-button path and its handler are untouched.
     */
    private fun enableTier(resultCode: Int, data: Intent?) {
        if (resultCode != -1 || data == null) {           // -1 == RESULT_OK
            breadcrumb("tier: enable without consent; ignoring")
            return
        }
        if (tierCoordinator != null) { breadcrumb("tier: already enabled"); return }
        // Android 14+ ordering: claim the mediaProjection FGS type FIRST (allowed by the fresh consent
        // appop), because getMediaProjection() then requires the service to already hold that type.
        // Wrap the claim so a stale/again-consumed consent fails gracefully instead of crashing.
        val claimed = runCatching { startForegroundNotification(withProjection = true) }
            .onFailure { breadcrumb("tier: FGS mediaProjection claim failed: ${it.message}") }
            .isSuccess
        if (!claimed) { startForegroundNotification(withProjection = false); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(resultCode, data) }
            .getOrElse { breadcrumb("tier: getMediaProjection threw: ${it.message}"); null }
        if (mp == null) { disableTier(); return }          // disableTier restores the specialUse FGS type
        mp.registerCallback(projectionCallback, mainHandler)
        projection = mp
        if (!startTierPipeline(mp)) disableTier()
    }

    /**
     * Build (or rebuild) the VirtualDisplay + ImageReader + coordinator for the current display
     * size, reusing the existing [projection] token. Returns false on failure (caller cleans up).
     * Used by [enableTier] and by [onTierConfigChanged] (rotation/size change).
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
        val overlay = TierOverlay(
            AndroidWindowHost(getSystemService(WindowManager::class.java)), this,
            opacityCap = { OpacityCap.of(this) },
        )
        val renderer = OverlayBadgeRenderer(overlay, BADGE_PX, GAP_PX)
        val ht = HandlerThread("herotier").apply { start() }
        tierThread = ht
        val coordinator = HeroTierCoordinator(
            grabber = grabber,
            ocr = PaddleHeroOcr.create(this) ?: MlKitHeroOcr(),   // PP-OCRv5 primary; ML Kit fallback if load fails
            matcher = HeroMatcher(loadTierTable()),
            renderer = renderer,
            foreground = { StrictForeground.of(queryForegroundPackage(), HS_PACKAGE) },
            currentRotation = { displayInfoNow().rotationDeg },
            handler = Handler(ht.looper),
            mainHandler = mainHandler,
            // §8.2 visual probe is the production trigger (Spike B: no select connection signature).
            gate = VisualProbeGate(),
            forceOpen = { BuildConfig.DEBUG && tierForceOpen },   // debug-only manual override (bypasses the gate)
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

    /**
     * Fully disable the tier feature. [restoreForeground] re-claims the SPECIAL_USE-only FGS type
     * (dropping MEDIA_PROJECTION) when the VPN service keeps running; tearDown passes false.
     */
    private fun disableTier(restoreForeground: Boolean = true) {
        releaseTierPipeline()
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        tierForceOpen = false
        if (restoreForeground && coreRunning) startForegroundNotification(withProjection = false)
        breadcrumb("tier: disabled")
    }

    private fun loadTierTable(): TierTable =
        runCatching {
            TierTable.fromJson(assets.open(TIER_ASSET).bufferedReader().use { it.readText() })
        }.getOrElse {
            breadcrumb("tier: asset $TIER_ASSET missing/invalid -> empty table (${it.message})")
            TierTable.fromJson("""{"heroes":[]}""")
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
        private const val TAG = "BobVpnService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "bob_vpn"

        // CMFA-aligned TUN parameters.
        private const val TUN_GATEWAY = "10.99.0.1"
        private const val TUN_PREFIX = 30
        private const val TUN_PORTAL = "10.99.0.2"
        private const val TUN_MTU = 9000
        // gvisor pinned for Phase 0: on Android, sing-tun's `mixed`/`system`
        // stack silently drops TCP packets through an external fd. See
        // android/bobcore/PINNED-VERSIONS.md.
        private const val TUN_STACK = "gvisor"

        private const val HS_PACKAGE = "com.blizzard.wtcg.hearthstone"

        const val ACTION_START = "com.bobassist.phase0.START"
        const val ACTION_STOP = "com.bobassist.phase0.STOP"

        // hero-tier overlay
        const val ACTION_ENABLE_TIER = "com.bobassist.phase0.ENABLE_TIER"
        const val ACTION_DISABLE_TIER = "com.bobassist.phase0.DISABLE_TIER"
        const val ACTION_TIER_FORCE_OPEN = "com.bobassist.phase0.TIER_FORCE_OPEN"   // debug: Stage 9
        const val ACTION_TIER_FORCE_CLOSE = "com.bobassist.phase0.TIER_FORCE_CLOSE" // debug: Stage 9
        const val EXTRA_RESULT_CODE = "tier_result_code"
        const val EXTRA_RESULT_DATA = "tier_result_data"
        private const val TIER_ASSET = "herotier_v1.json"
        private const val BADGE_PX = 64
        private const val GAP_PX = 10

        @Volatile var liveSession: OverlaySession? = null
            internal set
    }
}
