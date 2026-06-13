package com.turbotavern.devrec

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import com.turbotavern.BobVpnService

/**
 * Debug entry. Connection sampling reads the VPN's mihomo core, so without the VPN every frame is
 * "[]" — ensure it's running first (via BobVpnService's existing ACTION_START; the kill path is
 * unchanged), THEN request full-screen capture and start DevRecorderService. One tap once the VPN
 * has been authorized once.
 */
class DevRecorderActivity : Activity() {
    private val mpm by lazy { getSystemService(MediaProjectionManager::class.java) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(Button(this).apply { text = "Start Recording"; setOnClickListener { ensureVpnThenCapture() } })
    }

    private fun ensureVpnThenCapture() {
        if (BobVpnService.liveSession != null) { requestCapture(); return }   // already running
        val prep = VpnService.prepare(this)
        if (prep == null) { startVpn(); requestCapture() }                   // already authorized
        else startActivityForResult(prep, REQ_VPN)                           // first-time consent
    }

    private fun startVpn() {
        startForegroundService(Intent(this, BobVpnService::class.java).setAction(BobVpnService.ACTION_START))
        Toast.makeText(this, "bob VPN starting (connection samples)", Toast.LENGTH_SHORT).show()
    }

    private fun requestCapture() {
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_CAP)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, code: Int, data: Intent?) {
        super.onActivityResult(req, code, data)
        when (req) {
            REQ_VPN -> {
                if (code == RESULT_OK) { startVpn(); requestCapture(); return }   // chain into capture; don't finish
                Toast.makeText(this, "VPN denied — connection samples will be empty", Toast.LENGTH_SHORT).show()
                finish()
            }
            REQ_CAP -> {
                if (code == RESULT_OK && data != null) {
                    startForegroundService(Intent(this, DevRecorderService::class.java)
                        .setAction(DevRecorderService.ACTION_START)
                        .putExtra(DevRecorderService.EXTRA_CODE, code)
                        .putExtra(DevRecorderService.EXTRA_DATA, data))
                }
                finish()
            }
        }
    }

    companion object { private const val REQ_CAP = 7001; private const val REQ_VPN = 7002 }
}
