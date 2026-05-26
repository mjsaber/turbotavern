package com.bobassist.phase0

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.bobassist.phase0.BuildConfig
import com.bobassist.phase0.core.MihomoCore

/**
 * Phase 0 verifier UI: two buttons (Start/Stop VPN). Spike B verifies HS
 * traffic appears in mihomo log via logcat (`adb logcat -s BobPhase0 GoLog`).
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var grantOverlayBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Bobcore.version() = ${MihomoCore.version()}")
        setContentView(buildLayout())

        // Debug-only: --ez auto_start true lets `adb am start MainActivity`
        // drive the e2e test script without manual taps. Production must
        // remove this branch (it's protected by BuildConfig.DEBUG but the
        // Activity itself is exported so any app could try this intent).
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            onStartClicked()
        }
    }

    private fun buildLayout(): View {
        statusView = TextView(this).apply {
            text = "bobcore ${MihomoCore.version()}\nstatus: idle"
            textSize = 16f
            setPadding(40, 20, 40, 20)
        }
        startBtn = Button(this).apply {
            text = "Start VPN"
            setOnClickListener { onStartClicked() }
        }
        val stopBtn = Button(this).apply {
            text = "Stop VPN"
            setOnClickListener { onStopClicked() }
        }
        grantOverlayBtn = Button(this).apply {
            text = "Grant Overlay Permission"
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                // No result needed; onResume() re-checks when Settings closes.
                startActivity(intent)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            addView(statusView)
            addView(startBtn)
            addView(stopBtn)
            addView(grantOverlayBtn)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    private fun refreshPermissionUi() {
        val canOverlay = hasOverlayPermission()
        grantOverlayBtn.visibility = if (canOverlay) View.GONE else View.VISIBLE
        startBtn.isEnabled = canOverlay
        if (!canOverlay) {
            statusView.text = "bobcore ${MihomoCore.version()}\nOverlay permission required to start."
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun onStartClicked() {
        if (!hasOverlayPermission()) {
            statusView.text = "${statusView.text}\nOverlay permission required."
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN_AUTHORIZE)
            statusView.text = "${statusView.text}\nasking VPN authorization..."
        } else {
            launchService()
        }
    }

    private fun onStopClicked() {
        startService(Intent(this, BobVpnService::class.java).apply { action = BobVpnService.ACTION_STOP })
        statusView.text = "bobcore ${MihomoCore.version()}\nstatus: stop requested"
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_AUTHORIZE) {
            if (resultCode == RESULT_OK) launchService()
            else statusView.text = "${statusView.text}\nVPN denied"
        }
    }

    private fun launchService() {
        startForegroundService(Intent(this, BobVpnService::class.java).apply { action = BobVpnService.ACTION_START })
        statusView.text = "bobcore ${MihomoCore.version()}\nstatus: service started"
    }

    companion object {
        private const val TAG = "BobPhase0"
        private const val REQ_VPN_AUTHORIZE = 1001
        const val EXTRA_AUTO_START = "auto_start"
    }
}
