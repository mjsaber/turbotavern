package com.hsdisconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hsdisconnect.app.core.PermissionStatus

data class SettingsUiState(
    val perms: PermissionStatus,
    val durationMs: Long,
    val isRunning: Boolean,
)

interface SettingsActions {
    fun requestOverlay()
    fun requestUsageStats()
    fun requestVpn()
    fun requestNotifications()
    fun setDuration(ms: Long)
    fun toggleService()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("炉石拔线") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.perms.hearthstoneInstalled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "未检测到炉石传说，无法启动。",
                        modifier = Modifier.padding(16.dp),
                        color = Color.Red,
                    )
                }
            }
            PermissionRow("悬浮窗权限", state.perms.overlay, actions::requestOverlay)
            PermissionRow("使用情况访问", state.perms.usageStats, actions::requestUsageStats)
            PermissionRow("VPN 授权", state.perms.vpnPrepared, actions::requestVpn)
            PermissionRow("通知权限", state.perms.postNotifications, actions::requestNotifications)
            DurationRow(state.durationMs, actions::setDuration)
            OutlinedButton(
                onClick = actions::toggleService,
                enabled = state.perms.allGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isRunning) "停止拔线助手" else "启动拔线助手")
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (granted) Color(0xFF2E7D32) else Color.Red,
                modifier = Modifier.size(24.dp),
            )
            Text(label, modifier = Modifier.padding(start = 12.dp).weight(1f))
            if (!granted) {
                OutlinedButton(onClick = onAction) { Text("去授权") }
            }
        }
    }
}

@Composable
private fun DurationRow(currentMs: Long, onChange: (Long) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("默认拔线时长", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(3_000L, 5_000L, 8_000L, 10_000L).forEach { ms ->
                    OutlinedButton(
                        onClick = { onChange(ms) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("${ms / 1000}s")
                    }
                }
            }
            Text(
                "当前: ${currentMs / 1000} 秒",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
