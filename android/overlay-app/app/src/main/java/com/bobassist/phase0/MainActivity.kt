package com.bobassist.phase0

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-run onboarding + launcher. Explains what Turbo Tavern does, walks the user through the
 * permissions it needs (with rationale), and starts the overlay. All copy is localized (en/zh-CN/zh-TW).
 */
class MainActivity : Activity() {

    private val kill = KillFeatureHolder.get()
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var overlayRow: PermRow
    private lateinit var usageRow: PermRow

    private class PermRow(val container: View, val status: TextView, val grant: Button)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val root = buildLayout()
        setContentView(root)
        applyInsets(root)
        // Debug-only: `--ez auto_start true` drives the e2e flow without manual taps.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) onStartClicked()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLayout(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        col.addView(text(getString(R.string.app_name), 28f, bold = true))
        col.addView(text(getString(R.string.tagline), 14f).apply { setPadding(0, dp(2), 0, dp(20)) })

        col.addView(text(getString(R.string.how_title), 16f, bold = true))
        col.addView(
            text("${getString(R.string.how_1)}\n${getString(R.string.how_2)}\n${getString(R.string.how_3)}", 14f)
        )
        col.addView(spacer(dp(20)))

        overlayRow = permRow(getString(R.string.perm_overlay), getString(R.string.perm_overlay_why)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        col.addView(overlayRow.container)
        usageRow = permRow(getString(R.string.perm_usage), getString(R.string.perm_usage_why)) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        col.addView(usageRow.container)
        col.addView(
            text("• ${getString(R.string.perm_capture)} — ${getString(R.string.perm_capture_why)}", 12f)
                .apply { setPadding(0, dp(8), 0, 0) }
        )
        col.addView(spacer(dp(24)))

        startBtn = Button(this).apply {
            text = getString(R.string.action_start)
            setOnClickListener { onStartClicked() }
        }
        col.addView(startBtn)
        col.addView(Button(this).apply {
            text = getString(R.string.action_stop)
            setOnClickListener { onStopClicked() }
        })
        statusView = text("", 13f).apply { setPadding(0, dp(10), 0, dp(16)) }
        col.addView(statusView)
        col.addView(Button(this).apply {
            text = getString(R.string.action_about)
            setOnClickListener { startActivity(Intent(this@MainActivity, AboutActivity::class.java)) }
        })
        return ScrollView(this).apply { addView(col) }
    }

    private fun text(s: String, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun permRow(title: String, why: String, onGrant: () -> Unit): PermRow {
        val status = text("", 13f)
        val grant = Button(this).apply {
            text = getString(R.string.action_grant)
            setOnClickListener { onGrant() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(title, 15f, bold = true))
            addView(text(why, 12f))
            addView(status)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(texts)
            addView(grant)
        }
        return PermRow(row, status, grant)
    }

    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val bars = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            else insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            v.setPadding(0, bars.first, 0, bars.second)
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
        updateRow(overlayRow, canOverlay, getString(R.string.status_required))
        updateRow(usageRow, hasUsageAccessPermission(), getString(R.string.status_recommended))
        startBtn.isEnabled = canOverlay
        statusView.text = when {
            !canOverlay -> getString(R.string.start_blocked)
            kill.isRunning() -> getString(R.string.status_running)
            else -> getString(R.string.status_ready)
        }
    }

    private fun updateRow(row: PermRow, granted: Boolean, neededLabel: String) {
        row.status.text = if (granted) getString(R.string.status_granted) else neededLabel
        row.grant.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun hasOverlayPermission() = Settings.canDrawOverlays(this)

    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun onStartClicked() {
        if (!hasOverlayPermission()) { refreshPermissionUi(); return }
        if (kill.isRunning()) { requestProjection(); return }
        val consent = kill.prepareConsent(this)
        if (consent != null) startActivityForResult(consent, REQ_VPN_AUTHORIZE)
        else { kill.start(this); requestProjection() }    // clean flavor: start() is a no-op
    }

    /** Screen-capture consent (entire screen or single-app); result -> ENABLE_TIER on OverlayService. */
    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun onStopClicked() {
        kill.stop(this)
        startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP })
        refreshPermissionUi()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN_AUTHORIZE -> if (resultCode == RESULT_OK) { kill.start(this); requestProjection() }
            REQ_PROJECTION -> if (resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_ENABLE_TIER
                        putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                    }
                )
            }
        }
    }

    companion object {
        private const val REQ_VPN_AUTHORIZE = 1001
        private const val REQ_PROJECTION = 1002
        const val EXTRA_AUTO_START = "auto_start"
    }
}
