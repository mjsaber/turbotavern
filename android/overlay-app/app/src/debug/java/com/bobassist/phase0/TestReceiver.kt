package com.bobassist.phase0

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bobassist.phase0.core.MihomoCore

/**
 * Debug-only IPC for the e2e test scripts. Lives under `src/debug/` so it is
 * compiled into debug variant only. Logs every result to tag `SpikeC` for the
 * shell script to grep.
 *
 * Commands (`--es cmd <name>`):
 *   snapshot           — dump current connections JSON
 *   kill --es id <id>  — close connection by id
 *   version            — dump bobcore version
 */
class TestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: run {
            Log.w(TAG, "no cmd")
            return
        }
        when (cmd) {
            "snapshot" -> Log.i(TAG, "snapshot=${MihomoCore.connectionsJson()}")
            "kill" -> {
                val id = intent.getStringExtra("id") ?: run {
                    Log.w(TAG, "kill: missing id")
                    return
                }
                Log.i(TAG, "kill id=$id result=${MihomoCore.closeConnection(id)}")
            }
            "version" -> Log.i(TAG, "version=${MihomoCore.version()}")
            "stop_core" -> {
                val r = MihomoCore.stopTun()
                Log.i(TAG, "stop_core result=$r")
            }
            else -> Log.w(TAG, "unknown cmd=$cmd")
        }
    }

    companion object {
        private const val TAG = "SpikeC"
        const val ACTION = "com.bobassist.phase0.TEST"
    }
}
