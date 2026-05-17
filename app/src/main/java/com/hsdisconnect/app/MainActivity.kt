package com.hsdisconnect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                val state = SettingsUiState(perms, duration, isRunning = false)
                val actions = object : SettingsActions {
                    override fun requestOverlay() {}
                    override fun requestUsageStats() {}
                    override fun requestVpn() {}
                    override fun requestNotifications() {}
                    override fun setDuration(ms: Long) {
                        prefs.durationMs = ms
                        duration = ms
                    }
                    override fun toggleService() {}
                }
                SettingsScreen(state, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // refreshed by recomposition on next setContent navigation; full refresh wired in T11
    }
}
