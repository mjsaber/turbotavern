package com.bobassist.phase0.devrec

import android.os.Handler
import com.bobassist.phase0.core.ConnectionCoreProvider
import java.io.File

/**
 * Every [intervalMs] writes a numeric-stem <ts>.json connection frame and appends a sample line to
 * events.jsonl (foreground pkg + rotation). On the recorder [handler]. Spec §5.3.
 */
class ConnectionSampler(
    private val dir: File,
    private val handler: Handler,
    private val nowMs: () -> Long,
    private val uniqueTs: SessionDir.UniqueTs,
    private val foregroundPkg: () -> String,
    private val rotationDeg: () -> Int,
    private val events: (String) -> Unit,
    private val intervalMs: Long = 500,
) {
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sampleOnce()
            handler.postDelayed(this, intervalMs)
        }
    }

    /** Write a connection frame at exactly [ts] (so onMark's dense frame shares the MARK timestamp).
     *  Atomic via SessionDir.writeAtomic. */
    fun sampleAt(ts: Long) {
        val json = runCatching { ConnectionCoreProvider.get().connectionsJson() }.getOrNull()
        if (json != null && json.trimStart().startsWith("[")) {
            SessionDir.writeAtomic(File(dir, SessionDir.frameName(ts)), json)
            events("""{"t":$ts,"type":"sample","fg":"${foregroundPkg()}","rot":${rotationDeg()}}""")
        } else {
            events("""{"t":$ts,"type":"sample_error"}""")
        }
    }

    /** Periodic tick allocates its own unique ts. */
    fun sampleOnce(): Long { val ts = uniqueTs.next(nowMs()); sampleAt(ts); return ts }

    fun start() { if (!running) { running = true; handler.post(tick) } }
    fun stop() { running = false; handler.removeCallbacks(tick) }
}
