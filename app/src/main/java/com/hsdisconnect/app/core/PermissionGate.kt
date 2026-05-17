package com.hsdisconnect.app.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val overlay: Boolean,
    val usageStats: Boolean,
    val vpnPrepared: Boolean,
    val postNotifications: Boolean,
    val hearthstoneInstalled: Boolean,
) {
    val allGranted: Boolean
        get() = overlay && usageStats && vpnPrepared && postNotifications && hearthstoneInstalled
}

object PermissionGate {
    fun check(context: Context): PermissionStatus = PermissionStatus(
        overlay = Settings.canDrawOverlays(context),
        usageStats = hasUsageStats(context),
        vpnPrepared = VpnService.prepare(context) == null,
        postNotifications = hasNotificationPermission(context),
        hearthstoneInstalled = isHearthstoneInstalled(context),
    )

    private fun hasUsageStats(context: Context): Boolean {
        val aom = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            aom.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            aom.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isHearthstoneInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(Constants.HEARTHSTONE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
