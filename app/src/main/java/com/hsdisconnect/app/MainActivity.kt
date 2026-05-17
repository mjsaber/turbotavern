package com.hsdisconnect.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hsdisconnect.app.core.PermissionGate
import com.hsdisconnect.app.core.Prefs
import com.hsdisconnect.app.ui.SettingsActions
import com.hsdisconnect.app.ui.SettingsScreen
import com.hsdisconnect.app.ui.SettingsUiState
import com.hsdisconnect.app.ui.theme.HsDisconnectTheme

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.from(this)
        setContent {
            HsDisconnectTheme {
                var perms by remember { mutableStateOf(PermissionGate.check(this)) }
                var duration by remember { mutableStateOf(prefs.durationMs) }

                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.addObserver(
                        androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                perms = PermissionGate.check(this@MainActivity)
                            }
                        }
                    )
                }

                val vpnLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { perms = PermissionGate.check(this) }

                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { perms = PermissionGate.check(this) }

                val state = SettingsUiState(perms, duration, isRunning = false)
                val actions = object : SettingsActions {
                    override fun requestOverlay() {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                    override fun requestUsageStats() {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    override fun requestVpn() {
                        val intent = VpnService.prepare(this@MainActivity)
                        if (intent != null) vpnLauncher.launch(intent)
                        else perms = PermissionGate.check(this@MainActivity)
                    }
                    override fun requestNotifications() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    override fun setDuration(ms: Long) {
                        prefs.durationMs = ms
                        duration = ms
                    }
                    override fun toggleService() { /* T13 */ }
                }
                SettingsScreen(state, actions)
            }
        }
    }
}
