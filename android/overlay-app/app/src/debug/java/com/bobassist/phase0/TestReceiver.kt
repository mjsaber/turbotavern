package com.bobassist.phase0

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.bobassist.phase0.core.BattleConnection
import com.bobassist.phase0.core.MihomoCore
import java.io.File

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
            "kill_battle" -> killBattle()
            "record_start" -> startRecording(context)
            "record_stop" -> stopRecording(context)
            "record_mark" -> {
                val label = intent.getStringExtra("label") ?: "mark"
                recordMark(context, label)
            }
            else -> Log.w(TAG, "unknown cmd=$cmd")
        }
    }

    private fun killBattle() {
        val (cand, count) = BattleConnection.pickWithCount(MihomoCore.connectionsJson())
        if (cand == null) {
            Log.i(TAG, "kill_battle no_candidate (n=0)")
            return
        }
        val result = MihomoCore.closeConnection(cand.id)
        Log.i(TAG, "kill_battle n=$count id=${cand.id} dst=${cand.destinationIp}:${cand.destinationPort} result=$result")
    }

    private fun startRecording(context: Context) {
        synchronized(lock) {
            if (recorder != null) {
                Log.w(TAG, "record_start: already recording")
                return
            }
            val dir = File(context.filesDir, "spike-d").apply { mkdirs() }
            // Wipe previous run so we don't mix runs.
            dir.listFiles()?.forEach { it.delete() }
            val ht = HandlerThread("SpikeD-record").apply { start() }
            val handler = Handler(ht.looper)
            val r = object : Runnable {
                override fun run() {
                    val ts = System.currentTimeMillis()
                    runCatching {
                        File(dir, "$ts.json").writeText(MihomoCore.connectionsJson())
                    }
                    handler.postDelayed(this, INTERVAL_MS)
                }
            }
            handler.post(r)
            recorder = Recorder(ht, handler, r, dir)
            Log.i(TAG, "record_start dir=${dir.absolutePath} interval_ms=$INTERVAL_MS")
        }
    }

    private fun stopRecording(context: Context) {
        synchronized(lock) {
            val rec = recorder ?: run {
                Log.w(TAG, "record_stop: not recording")
                return
            }
            rec.handler.removeCallbacks(rec.task)
            rec.thread.quitSafely()
            recorder = null
            val count = rec.dir.listFiles()?.size ?: 0
            Log.i(TAG, "record_stop snapshots=$count dir=${rec.dir.absolutePath}")
        }
    }

    private fun recordMark(context: Context, label: String) {
        val dir = File(context.filesDir, "spike-d").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        File(dir, "MARK-$ts-$label.txt").writeText("$ts: $label\n")
        Log.i(TAG, "record_mark label=$label ts=$ts")
    }

    private data class Recorder(
        val thread: HandlerThread,
        val handler: Handler,
        val task: Runnable,
        val dir: File,
    )

    companion object {
        private const val TAG = "SpikeC"
        private const val INTERVAL_MS = 500L
        const val ACTION = "com.bobassist.phase0.TEST"

        private val lock = Any()
        @Volatile private var recorder: Recorder? = null
    }
}
