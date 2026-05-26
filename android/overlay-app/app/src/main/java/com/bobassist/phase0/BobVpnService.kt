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
import com.bobassist.phase0.core.BattleConnection
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.core.MihomoCore
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.overlay.OverlayState
import com.bobassist.phase0.overlay.OverlayWindow
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

    private var overlay: OverlayWindow? = null
    private var poller: OverlayPoller? = null
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var overlayRunning = false   // idempotency guard (P1 #4)
    private val controller: BattleConnectionController by lazy {
        BattleConnectionController(
            snapshot = { MihomoCore.connectionsJson() },
            close = { id -> MihomoCore.closeConnection(id) },
        )
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
        overlay?.let { ow ->
            mainHandler.post { runCatching { ow.onConfigurationChanged() } }
        }
    }

    private fun bringUp() {
        if (coreRunning) {
            breadcrumb("bringUp called while already running; ignoring")
            return
        }
        breadcrumb("bringUp begin")

        // Install protect callback BEFORE Setup so the very first dial
        // (mihomo internal init) is also protected.
        runCatching { MihomoCore.setProtector(this) }
            .onFailure {
                breadcrumb("setProtector failed: ${it.message}")
                Log.e(TAG, "setProtector failed", it)
                stopSelf()
                return
            }
        breadcrumb("setProtector OK")

        runCatching {
            MihomoCore.setup(cacheDir.absolutePath).getOrThrow()
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
        MihomoCore.startTun(fd, TUN_STACK, gateway, TUN_PORTAL).onFailure { err ->
            breadcrumb("MihomoCore.startTun failed: ${err.message}")
            Log.e(TAG, "MihomoCore.startTun failed; reclaiming fd $fd", err)
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            stopSelf()
        }.onSuccess {
            coreRunning = true
            breadcrumb("MihomoCore.startTun OK; HS traffic routed through TUN")
            Log.i(TAG, "TUN listener up, fd=$fd")
            liveController = controller
            startOverlayAndPolling()
        }
    }

    private fun startOverlayAndPolling() {
        if (overlayRunning) {
            breadcrumb("startOverlayAndPolling called while already running; ignoring")
            return
        }

        val ow = OverlayWindow(this, onTap = { handleOverlayTap() })
        overlay = ow
        mainHandler.post {
            runCatching { ow.show() }
                .onFailure {
                    Log.e(TAG, "overlay show failed", it)
                    breadcrumb("overlay show failed: ${it.message}")
                }
        }

        val ht = HandlerThread("BobOverlayPoll").apply { start() }
        val handler = Handler(ht.looper)
        pollThread = ht
        pollHandler = handler

        val p = OverlayPoller(
            snapshot = {
                // Guarded against teardown races (P1 #5): if mihomo has stopped,
                // count as 0 candidates so the next tick safely emits Waiting.
                runCatching {
                    BattleConnection.pickWithCount(MihomoCore.connectionsJson()).second
                }.getOrElse { err ->
                    breadcrumb("poll snapshot failed: ${err.message}")
                    0
                }
            },
            onStateChange = { state ->
                mainHandler.post { ow.applyState(state) }
            },
            scheduleAfter = { delayMs, cb ->
                handler.postDelayed(cb, delayMs)
            },
        )
        poller = p
        livePoller = p

        val tick = object : Runnable {
            override fun run() {
                p.tick()
                handler.postDelayed(this, OverlayPoller.POLL_INTERVAL_MS)
            }
        }
        handler.post {
            p.start()
            handler.postDelayed(tick, OverlayPoller.POLL_INTERVAL_MS)
        }
        overlayRunning = true
        breadcrumb("overlay + poller started")
    }

    /**
     * User tapped the overlay. Confined to pollHandler so all state reads/writes
     * happen on a single thread (P1 #2). Performs the kill if Ready, ignores
     * tap otherwise. Enters Cooldown ONLY on Success — failures stay Ready so
     * the user can try again.
     */
    private fun handleOverlayTap() {
        val handler = pollHandler ?: return
        val p = poller ?: return
        val ctrl = controller
        handler.post {
            when (p.currentState()) {
                OverlayState.Ready -> {
                    val result = runCatching { ctrl.killBattleSocket() }
                        .getOrElse {
                            breadcrumb("overlay tap kill threw: ${it.message}")
                            return@post
                        }
                    breadcrumb("overlay tap result=$result")
                    if (result is BattleConnectionController.KillResult.Success) {
                        Log.i(TAG, "overlay kill success: id=${result.closedId} dst=${result.destinationIp}:${result.destinationPort}")
                        p.enterCooldown()
                    } else {
                        // NoCandidate / AlreadyClosed / Failure — stay Ready,
                        // user can try again. No cooldown.
                        Log.i(TAG, "overlay kill non-success: $result")
                    }
                }
                OverlayState.WaitingForBattle -> {
                    breadcrumb("overlay tap ignored (no candidate)")
                }
                OverlayState.Cooldown -> {
                    breadcrumb("overlay tap ignored (cooldown)")
                }
            }
        }
    }

    private fun tearDown() {
        overlayRunning = false
        liveController = null
        livePoller = null

        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        pollThread = null
        pollHandler = null
        poller = null

        overlay?.let { ow ->
            mainHandler.post { runCatching { ow.hide() } }
        }
        overlay = null

        if (coreRunning) {
            runCatching { MihomoCore.stopTun() }
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
    }
}
