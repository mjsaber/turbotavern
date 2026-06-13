package com.turbotavern.devrec

import org.json.JSONObject
import java.io.File

/** Pure session-directory helpers (layout, filenames, unique ts, meta). No Android deps. */
object SessionDir {
    fun frameName(ts: Long) = "$ts.json"                       // numeric stem -> analyze-recording.py frame
    fun markName(ts: Long, seq: Int) = "MARK-$ts-$seq.txt"     // analyze parses MARK-<ts>-<label>
    fun shotName(ts: Long, seq: Int) = "SHOT-$ts-$seq.png"

    /** Monotonic, collision-free epoch-ms: never returns a value <= the last one. */
    class UniqueTs {
        private var last = Long.MIN_VALUE
        @Synchronized fun next(nowMs: Long): Long {
            val t = if (nowMs > last) nowMs else last + 1
            last = t
            return t
        }
    }

    /** Move an existing session dir aside to [prev] (replacing any older prev) instead of deleting. */
    fun rollPrevious(dir: File, prev: File) {
        if (!dir.exists()) return
        prev.deleteRecursively()
        dir.renameTo(prev)
    }

    /** Atomic discrete-file write: temp + rename. NOT for events.jsonl (append log). */
    fun writeAtomic(out: File, text: String) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.writeText(text); tmp.renameTo(out)
    }

    /** Session metadata. [stoppedAtMs]/[markCount] null at start; both set when rewritten on stop. */
    fun meta(appVersion: String, deviceModel: String, startedAtMs: Long,
             stoppedAtMs: Long? = null, markCount: Int? = null): JSONObject =
        JSONObject().put("schemaVersion", 1).put("app_version", appVersion)
            .put("device_model", deviceModel).put("started_at_ms", startedAtMs)
            .apply { stoppedAtMs?.let { put("stopped_at_ms", it) }; markCount?.let { put("mark_count", it) } }
}
