package com.bobassist.phase0

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.bobassist.phase0.BuildConfig

/**
 * Phase 0 verifier UI: two buttons (Start/Stop VPN). Spike B verifies HS
 * traffic appears in mihomo log via logcat (`adb logcat -s BobPhase0 GoLog`).
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var grantOverlayBtn: Button
    private lateinit var grantUsageBtn: Button
    private val kill = KillFeatureHolder.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "kill feature: ${kill.statusLabel()}")
        actionBar?.hide()                       // drop the "Bob Phase 0" title bar (it overlapped content on Android 15 edge-to-edge)
        val root = buildLayout()
        setContentView(root)
        applyTopInset(root)                     // pad below the status/nav bars (targetSdk 35 draws edge-to-edge)

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
            text = "${kill.statusLabel()}\nstatus: idle"
            textSize = 16f
            setPadding(40, 20, 40, 20)
        }
        startBtn = Button(this).apply {
            text = "Start Turbo Tavern"
            setOnClickListener { onStartClicked() }
        }
        val stopBtn = Button(this).apply {
            text = "Stop"
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
        grantUsageBtn = Button(this).apply {
            text = "Grant Usage Access (optional)"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            }
        }
        val aboutBtn = Button(this).apply {
            text = "About / Licenses"
            setOnClickListener { startActivity(Intent(this@MainActivity, AboutActivity::class.java)) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            addView(statusView)
            addView(startBtn)
            addView(stopBtn)
            addView(grantOverlayBtn)
            addView(grantUsageBtn)
            addView(aboutBtn)
        }
    }

    /** Inset the content below the status & navigation bars (edge-to-edge under targetSdk 35). */
    private fun applyTopInset(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val bars = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            else insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            v.setPadding(40, bars.first + 40, 40, bars.second + 40)
            insets
        }
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    private fun refreshPermissionUi() {
        val canOverlay = hasOverlayPermission()
        val canUsage = hasUsageAccessPermission()
        grantOverlayBtn.visibility = if (canOverlay) View.GONE else View.VISIBLE
        grantUsageBtn.visibility = if (canUsage) View.GONE else View.VISIBLE
        startBtn.isEnabled = canOverlay  // usage-access is OPTIONAL — does not gate Start
        val statusLines = mutableListOf(kill.statusLabel())
        if (!canOverlay) statusLines += "Overlay permission required to start."
        if (!canUsage) statusLines += "Usage access NOT granted — overlay will stay visible even when HS is closed."
        statusView.text = statusLines.joinToString("\n")
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationInfo.uid,
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** One step: ensure the VPN is up, then request screen-capture and enable the tier overlay. */
    private fun onStartClicked() {
        if (!hasOverlayPermission()) {
            statusView.text = "${statusView.text}\nOverlay permission required."
            return
        }
        if (kill.isRunning()) {                      // kill/VPN already running -> go straight to tier
            requestProjection(); return
        }
        val consent = kill.prepareConsent(this)
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN_AUTHORIZE)   // -> on OK: kill.start() + requestProjection()
            statusView.text = "${statusView.text}\nasking VPN authorization..."
        } else {
            kill.start(this); requestProjection()    // clean flavor: start() is a no-op -> straight to overlay
        }
    }

    /** Screen-capture consent (user may pick "single app" -> Hearthstone); result -> ENABLE_TIER. */
    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun onStopClicked() {
        kill.stop(this)
        startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP })
        statusView.text = "${kill.statusLabel()}\nstatus: stop requested"
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_AUTHORIZE) {
            if (resultCode == RESULT_OK) { kill.start(this); requestProjection() }   // chain into tier
            else statusView.text = "${statusView.text}\nVPN denied"
        } else if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_ENABLE_TIER
                        putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                    }
                )
                statusView.text = "${statusView.text}\ntier overlay enabling..."
            } else {
                statusView.text = "${statusView.text}\nscreen capture denied"
            }
        }
    }

    companion object {
        private const val TAG = "BobPhase0"
        private const val REQ_VPN_AUTHORIZE = 1001
        private const val REQ_PROJECTION = 1002
        const val EXTRA_AUTO_START = "auto_start"
    }
}
