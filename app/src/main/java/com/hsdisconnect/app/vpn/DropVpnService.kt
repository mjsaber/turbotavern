package com.hsdisconnect.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hsdisconnect.app.R
import com.hsdisconnect.app.core.Constants

class DropVpnService : VpnService() {

    companion object {
        const val EXTRA_DURATION_MS = "duration_ms"
        const val ACTION_START = "com.hsdisconnect.action.START_DROP"
        const val ACTION_STOP = "com.hsdisconnect.action.STOP_DROP"

        @Volatile var listener: Listener? = null

        fun start(context: Context, durationMs: Long) {
            val intent = Intent(context, DropVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DropVpnService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    interface Listener {
        fun onVpnActive()
        fun onVpnStopped()
        fun onVpnRevoked()
        fun onVpnFailed(message: String)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                // T15 will add: establish() and start drop loop
                listener?.onVpnActive()
            }
            ACTION_STOP -> stopAndRelease()
            else -> stopAndRelease()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        listener?.onVpnRevoked()
        stopAndRelease()
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.onVpnStopped()
    }

    private fun stopAndRelease() {
        // T15 will add: close ParcelFileDescriptor
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_VPN)
            .setContentTitle(getString(R.string.notif_vpn_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIF_ID_VPN, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            startForeground(Constants.NOTIF_ID_VPN, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Constants.NOTIF_CHANNEL_VPN) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                Constants.NOTIF_CHANNEL_VPN,
                getString(R.string.notif_channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }
}
