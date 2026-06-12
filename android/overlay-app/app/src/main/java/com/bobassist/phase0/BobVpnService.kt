package com.bobassist.phase0

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bobassist.phase0.core.BattleCandidateCache
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.core.ConnectionCoreProvider
import com.bobassist.phase0.core.RealLifecycleCore
import com.bobassist.phase0.foreground.ForegroundDetector
import com.bobassist.phase0.foreground.ForegroundQuery
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.overlay.OverlayWindow
import com.bobassist.phase0.session.OverlaySession
import com.bobassist.phase0.util.AndroidElapsedRealtimeClock
import com.bobassist.phase0.util.TraceSink
import java.io.File

/**
 * Phase 0 foreground VpnService — owns mihomo lifecycle, the TUN file descriptor, and the 拔线
 * (battle-socket kill) overlay/poller. The read-only hero/trinket tier overlay lives in the separate
 * [OverlayService] (no VPN, no GPL core), so this service is the `full`-SKU-only 拔线 half.
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
    private val foregroundQuery by lazy { ForegroundQuery(this, HS_PACKAGE) }

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

    private fun hasUsageAccessPermission(): Boolean = foregroundQuery.hasUsageAccessPermission()

    /** Foreground-app lookup for the 拔线 detector — delegates to the shared [ForegroundQuery]. */
    private fun queryForegroundPackage(): String? = foregroundQuery.queryForegroundPackage()

    private fun tearDown() {
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

    private fun startForegroundNotification() {
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
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        breadcrumb("foreground notification posted")
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

        const val ACTION_START = "com.bobassist.phase0.START"
        const val ACTION_STOP = "com.bobassist.phase0.STOP"

        @Volatile var liveSession: OverlaySession? = null
            internal set
    }
}
