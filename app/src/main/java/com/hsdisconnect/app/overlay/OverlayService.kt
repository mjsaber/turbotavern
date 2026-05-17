package com.hsdisconnect.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hsdisconnect.app.MainActivity
import com.hsdisconnect.app.R
import com.hsdisconnect.app.core.Constants
import com.hsdisconnect.app.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var detector: ForegroundDetector
    private lateinit var window: OverlayWindow
    private lateinit var controller: DisconnectController
    private var pollingJob: Job? = null
    private var observerJob: Job? = null
    private var counterObserverJob: Job? = null
    private var stateObserverJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val launcher = object : VpnLauncher {
        override fun start(durationMs: Long) {
            try {
                com.hsdisconnect.app.vpn.DropVpnService.start(this@OverlayService, durationMs)
            } catch (e: Exception) {
                // Android 12+ ForegroundServiceStartNotAllowedException, or others
                controller.onVpnFailed(e.message ?: e.javaClass.simpleName)
            }
        }
        override fun stop() {
            try {
                com.hsdisconnect.app.vpn.DropVpnService.stop(this@OverlayService)
            } catch (_: Exception) { /* idempotent */ }
        }
    }

    private val vpnListener = object : com.hsdisconnect.app.vpn.DropVpnService.Listener {
        override fun onVpnActive() { controller.onVpnActive() }
        override fun onVpnStopped() { /* timer-driven stop already handled */ }
        override fun onVpnRevoked() { controller.onVpnRevoked() }
        override fun onVpnFailed(message: String) { controller.onVpnFailed(message) }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.from(this)
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        detector = ForegroundDetector.fromUsageStats(usm)
        controller = DisconnectController(
            scope = scope,
            launcher = launcher,
            checkVpnPrepared = { android.net.VpnService.prepare(this) == null },
        )
        com.hsdisconnect.app.vpn.DropVpnService.listener = vpnListener
        window = OverlayWindow(this).apply {
            val (x, y) = prefs.buttonPosition
            if (x >= 0 && y >= 0) setPosition(x, y)
            onPositionChanged = { newX, newY -> prefs.buttonPosition = newX to newY }
            onClick = { controller.onTap(prefs.durationMs) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        pollingJob = detector.startPolling(scope)
        observerJob = scope.launch {
            detector.isForeground.collect { foreground ->
                if (foreground) {
                    window.show()
                } else {
                    window.hide()
                    controller.resetCounter()
                }
            }
        }
        stateObserverJob = scope.launch {
            controller.state.collect { state ->
                when (state) {
                    is DisconnectState.Active -> {
                        window.setDisconnecting(state.durationMs)
                        clearFailureNotification()
                    }
                    is DisconnectState.Failed -> {
                        window.setDisconnecting(null)
                        postFailureNotification(state.reason)
                        // Auto-clear failure after 3s so user can tap again
                        scope.launch {
                            kotlinx.coroutines.delay(3_000L)
                            controller.clearFailure()
                        }
                    }
                    else -> window.setDisconnecting(null)
                }
            }
        }
        counterObserverJob = scope.launch {
            controller.counter.collect { count -> window.setCounter(count) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        observerJob?.cancel()
        stateObserverJob?.cancel()
        counterObserverJob?.cancel()
        if (window.isShown()) window.hide()
        com.hsdisconnect.app.vpn.DropVpnService.listener = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_OVERLAY)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIF_ID_OVERLAY, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Constants.NOTIF_ID_OVERLAY, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Constants.NOTIF_CHANNEL_OVERLAY) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.NOTIF_CHANNEL_OVERLAY,
                getString(R.string.notif_channel_overlay),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun postFailureNotification(reason: FailureReason) {
        val text = when (reason) {
            FailureReason.VpnNotAuthorized -> "VPN 授权失效，点击修复"
            is FailureReason.VpnLaunchFailed -> "拔线失败：${reason.message}"
        }
        val tapIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_OVERLAY)
            .setContentTitle("炉石拔线")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(Constants.NOTIF_ID_OVERLAY + 1, n)
    }

    private fun clearFailureNotification() {
        notificationManager.cancel(Constants.NOTIF_ID_OVERLAY + 1)
    }
}
