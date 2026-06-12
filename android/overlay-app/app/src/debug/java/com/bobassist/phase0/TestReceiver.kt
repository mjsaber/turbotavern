package com.bobassist.phase0

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.core.CloseResult
import com.bobassist.phase0.core.DebugConnectionCoreOverride
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
            "overlay_state" -> {
                val session = BobVpnService.liveSession
                val state = session?.poller?.currentState()?.let { stateLabel(it) } ?: "no_session"
                Log.i(TAG, "overlay_state state=$state service_alive=${session != null}")
            }
            "overlay_tap" -> {
                // Routes through the SAME OverlaySession.handleTap() the real overlay
                // tap uses — same state-gating, same cooldown semantics, same kill
                // controller. Headless equivalent of a finger on glass.
                val session = BobVpnService.liveSession ?: run {
                    Log.i(TAG, "overlay_tap service_down"); return
                }
                session.handleTap()
                Log.i(TAG, "overlay_tap dispatched")
            }
            "record_start" -> startRecording(context)
            "record_stop" -> stopRecording(context)
            "record_mark" -> {
                val label = intent.getStringExtra("label") ?: "mark"
                recordMark(context, label)
            }
            "devrec_mark" -> {
                val s = com.bobassist.phase0.devrec.DevRecorderService.live
                if (s == null) Log.i(TAG, "devrec_mark: not recording")
                else { context.startService(Intent(context, s::class.java)
                    .setAction(com.bobassist.phase0.devrec.DevRecorderService.ACTION_MARK)); Log.i(TAG, "devrec_mark dispatched") }
            }
            "devrec_stop" -> {
                val s = com.bobassist.phase0.devrec.DevRecorderService.live
                if (s == null) Log.i(TAG, "devrec_stop: not recording")
                else { context.startService(Intent(context, s::class.java)
                    .setAction(com.bobassist.phase0.devrec.DevRecorderService.ACTION_STOP)); Log.i(TAG, "devrec_stop dispatched") }
            }
            "sim_set_snapshot" -> {
                val json = intent.getStringExtra("json")
                DebugConnectionCoreOverride.setSnapshot(json)
                Log.i(TAG, "sim_set_snapshot len=${json?.length ?: 0}")
            }
            "sim_clear_snapshot" -> {
                DebugConnectionCoreOverride.setSnapshot(null)
                Log.i(TAG, "sim_clear_snapshot")
            }
            "sim_set_snapshot_delay" -> {                                   // codex P1 #5
                val ms = intent.getStringExtra("ms")?.toLongOrNull() ?: return
                DebugConnectionCoreOverride.setSnapshotDelay(ms)
                Log.i(TAG, "sim_set_snapshot_delay ms=$ms")
            }
            "sim_set_close" -> {
                val id = intent.getStringExtra("id") ?: return
                val resultStr = intent.getStringExtra("result") ?: "Success"
                val r = when (resultStr) {
                    "Success" -> CloseResult.Success
                    "NotFound" -> CloseResult.NotFound
                    "AlreadyClosed" -> CloseResult.AlreadyClosed
                    "CoreStopped" -> CloseResult.CoreStopped
                    else -> CloseResult.InternalError(-1)
                }
                DebugConnectionCoreOverride.setCloseResult(id, r)
                Log.i(TAG, "sim_set_close id=$id result=$r")
            }
            "sim_set_close_delay" -> {
                val ms = intent.getStringExtra("ms")?.toLongOrNull() ?: return
                DebugConnectionCoreOverride.setCloseDelay(ms)
                Log.i(TAG, "sim_set_close_delay ms=$ms")
            }
            "sim_set_foreground" -> {                                       // codex P1 #7 + round-2 P1 #16
                val v = intent.getStringExtra("value")
                val parsed: Boolean? = when (v?.lowercase()) {
                    "true" -> true
                    "false" -> false
                    "null", "clear" -> null
                    else -> null
                }
                DebugConnectionCoreOverride.setForeground(parsed)
                if (parsed != null) BobVpnService.liveSession?.handleForegroundChange(parsed)
                Log.i(TAG, "sim_set_foreground value=$parsed")
            }
            "sim_force_tick" -> {                                           // codex P1 #6 + round-4 P1 #35
                val session = BobVpnService.liveSession ?: run {
                    Log.i(TAG, "sim_force_tick service_down"); return
                }
                session.forceTickNow()
                Log.i(TAG, "sim_force_tick dispatched")
            }
            "sim_clear_all" -> {
                DebugConnectionCoreOverride.clearAll()
                Log.i(TAG, "sim_clear_all")
            }
            else -> Log.w(TAG, "unknown cmd=$cmd")
        }
    }

    private fun killBattle() {
        val session = BobVpnService.liveSession ?: run {
            Log.i(TAG, "kill_battle service_down")
            return
        }
        when (val r = session.killBattleSocketDirect()) {
            is BattleConnectionController.KillResult.Success ->
                Log.i(
                    TAG,
                    "kill_battle n=${r.candidatesAtKill} id=${r.closedId} " +
                        "dst=${r.destinationIp}:${r.destinationPort} result=Success",
                )
            BattleConnectionController.KillResult.NoCandidate ->
                Log.i(TAG, "kill_battle no_candidate (n=0)")
            BattleConnectionController.KillResult.AlreadyClosed ->
                Log.i(TAG, "kill_battle result=AlreadyClosed")
            is BattleConnectionController.KillResult.Failure ->
                Log.i(TAG, "kill_battle result=Failure reason=${r.reason}")
        }
    }

    private fun stateLabel(s: com.bobassist.phase0.overlay.OverlayState): String =
        when (s) {
            com.bobassist.phase0.overlay.OverlayState.WaitingForBattle -> "Waiting"
            com.bobassist.phase0.overlay.OverlayState.Ready -> "Ready"
            com.bobassist.phase0.overlay.OverlayState.Cooldown -> "Cooldown"
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
