package com.bobassist.phase0

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bobassist.phase0.core.MihomoCore
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

    private fun bringUp() {
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
        }
    }

    private fun tearDown() {
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
    }
}
