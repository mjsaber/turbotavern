package com.bobassist.phase0

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import com.bobassist.gomobile.bobcore.Bobcore

/**
 * Phase 0 Spike A smoke test: instantiate one TextView, call Bobcore.version(),
 * confirm we get "0.0.1-prototype" back from Go via JNI.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = runCatching { Bobcore.version() }
            .onFailure { Log.e(TAG, "Bobcore.version() threw", it) }
            .getOrElse { "<error: ${it.message}>" }

        Log.i(TAG, "Bobcore.version() = $version")

        val text = TextView(this).apply {
            text = "Bob Assistant Phase 0\nbobcore.version() = $version"
            textSize = 18f
            setPadding(40, 80, 40, 40)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text)
        }
        setContentView(layout)
    }

    companion object {
        private const val TAG = "BobPhase0"
    }
}
