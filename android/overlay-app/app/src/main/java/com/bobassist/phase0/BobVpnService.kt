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
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bobassist.phase0.core.BattleConnection
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.core.RealConnectionCore
import com.bobassist.phase0.core.RealLifecycleCore
import com.bobassist.phase0.foreground.ForegroundDetector
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
            snapshot = { RealConnectionCore.connectionsJson() },
            close = { id -> RealConnectionCore.closeConnection(id) },
        )
        val trace = TraceSink(enabled = BuildConfig.DEBUG, clock = AndroidElapsedRealtimeClock)

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
                    BattleConnection.pickWithCount(RealConnectionCore.connectionsJson()).second
                }.getOrElse { err ->
                    breadcrumb("poll snapshot failed: ${err.message}")
                    0
                }
            },
            onStateChange = { state -> mainHandler.post { ow.applyState(state) } },
            scheduleAfter = { delayMs, cb -> handler.postDelayed(cb, delayMs) },
            clock = AndroidElapsedRealtimeClock,
        )
        val detector = ForegroundDetector(
            queryForegroundPackage = { queryForegroundPackage() },
            targetPackage = HS_PACKAGE,
            onChange = { isFg -> session?.handleForegroundChange(isFg) },
        )
        val newSession = OverlaySession(
            controller = controller,
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

        // codex round-5 P1: transitional live* assignments — TestReceiver and Task 7
        // sim_force_tick read these. Task 9 collapses them into liveSession.
        // codex round-3 P1 #25: keep liveController = controller until Task 9.
        liveController = controller
        livePoller = poller
        liveTapTrigger = { newSession.handleTap() }
        livePollHandler = handler          // for sim_force_tick (Task 7)

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
        overlayRunning = false
        // codex round-3 P1 #24: liveSession is introduced in Task 9, NOT here.
        // Until then, keep existing liveController/livePoller/liveTapTrigger clears.
        liveController = null
        livePoller = null
        liveTapTrigger = null
        livePollHandler = null
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

        private const val HS_PACKAGE = "com.blizzard.wtcg.hearthstone"

        const val ACTION_START = "com.bobassist.phase0.START"
        const val ACTION_STOP = "com.bobassist.phase0.STOP"

        @Volatile var liveController: BattleConnectionController? = null
            internal set

        @Volatile var livePoller: OverlayPoller? = null
            internal set

        @Volatile var liveTapTrigger: (() -> Unit)? = null
            internal set

        // codex round-6 P1: transitional handle for sim_force_tick (Task 7).
        // Task 9 collapses this into liveSession.forceTickNow().
        @Volatile var livePollHandler: android.os.Handler? = null
            internal set
    }
}
