package com.bobassist.phase0.devrec

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button

/** Debug entry: request full-screen capture consent, then start DevRecorderService. */
class DevRecorderActivity : Activity() {
    private val mpm by lazy { getSystemService(MediaProjectionManager::class.java) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(Button(this).apply { text = "Start Recording"; setOnClickListener { requestCapture() } })
    }

    private fun requestCapture() {
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        startActivityForResult(intent, REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, code: Int, data: Intent?) {
        super.onActivityResult(req, code, data)
        if (req == REQ && code == RESULT_OK && data != null) {
            startForegroundService(Intent(this, DevRecorderService::class.java)
                .setAction(DevRecorderService.ACTION_START)
                .putExtra(DevRecorderService.EXTRA_CODE, code)
                .putExtra(DevRecorderService.EXTRA_DATA, data))
        }
        finish()
    }

    companion object { private const val REQ = 7001 }
}
