package com.bobassist.phase0.devrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.bobassist.phase0.BobVpnService
import com.bobassist.phase0.BuildConfig
import java.io.File

/**
 * Debug-only screen+connection session recorder. Owns its OWN MediaProjection (Android 14 allows one
 * VirtualDisplay per projection -> run with tier OFF). Single recorder HandlerThread serializes
 * capture/data; main thread owns the panel UI. See spec 2026-06-05-dev-session-recorder-design.md.
 */
class DevRecorderService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rec: Handler
    private lateinit var recThread: HandlerThread
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var shotter: ScreenShotter? = null
    private var sampler: ConnectionSampler? = null
    private var panel: MarkerPanel? = null
    private lateinit var dir: File
    private val uniqueTs = SessionDir.UniqueTs()
    private var capW = 0; private var capH = 0
    private var seq = 0
    private var startedAtMs = 0L
    private var captureStopped = false

    private val cb = object : MediaProjection.Callback() {
        override fun onStop() { teardownAll() }                          // full stop, not just capture
        override fun onCapturedContentResize(w: Int, h: Int) { if (::rec.isInitialized) rec.post { resizeTo() } }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::rec.isInitialized) rec.post { resizeTo() }
    }

    override fun onDestroy() { live = null; super.onDestroy() }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        when (i?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>(EXTRA_DATA)
                start(i.getIntExtra(EXTRA_CODE, 0), data)
            }
            ACTION_MARK -> mark()
            ACTION_STOP -> teardownAll()
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent?) {
        // Satisfy startForegroundService's "call startForeground promptly" contract for EVERY start.
        val claimed = runCatching { startForegroundMP() }
            .onFailure { breadcrumb("devrec: FGS claim failed: ${it.message}") }.isSuccess
        if (live != null) { breadcrumb("devrec: already recording; ignoring start"); return }
        if (data == null || !claimed) { breadcrumb("devrec: start aborted (data=$data claimed=$claimed)"); finishService(); return }
        captureStopped = false; seq = 0; startedAtMs = 0L
        live = this
        recThread = HandlerThread("devrec").apply { start() }
        rec = Handler(recThread.looper)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(code, data) }
            .getOrElse { breadcrumb("devrec: getMediaProjection threw: ${it.message}"); null }
        if (mp == null) { teardownAll(); return }
        if (BobVpnService.liveSession != null) breadcrumb("devrec: WARNING tier/projection may be active")
        projection = mp
        rec.post {
            mp.registerCallback(cb, rec)
            dir = File(filesDir, "devrec")
            SessionDir.rollPrevious(dir, File(filesDir, "devrec-prev"))
            dir.mkdirs()
            val info = displayInfoNow(); capW = info.first; capH = info.second
            val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)   // maxImages=3
            reader = r
            val v = runCatching {
                mp.createVirtualDisplay("devrec", capW, capH, resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            }.getOrElse { breadcrumb("devrec: createVirtualDisplay threw: ${it.message}"); null }
            if (v == null) { teardownAll(); return@post }
            vd = v
            shotter = ScreenShotter(r, { capW }, { capH }, rec, { System.currentTimeMillis() }, ::breadcrumb)
            sampler = ConnectionSampler(dir, rec, { System.currentTimeMillis() }, uniqueTs,
                { foregroundPkgNow() }, { displayInfoNow().third }, ::appendEvent).also { it.start() }
            startedAtMs = System.currentTimeMillis()
            SessionDir.writeAtomic(File(dir, "meta.json"),
                SessionDir.meta(BuildConfig.VERSION_NAME, android.os.Build.MODEL, startedAtMs).toString())
            main.post {
                runCatching {                                            // panel add can fail (overlay perm); keep recording
                    panel = MarkerPanel(this,
                        onMark = { val ts = System.currentTimeMillis(); rec.post { onMark(ts) } },
                        onStop = { teardownAll() }).also { it.show() }
                }.onFailure { breadcrumb("devrec: panel show failed (use adb mark fallback): ${it.message}") }
            }
            breadcrumb("devrec: recording up ($capW x $capH)")
        }
    }

    private fun mark() { val ts = System.currentTimeMillis(); if (::rec.isInitialized) rec.post { onMark(ts) } }

    private fun onMark(clickTs: Long) {
        if (captureStopped) return                              // a MARK queued before STOP must not run post-teardown
        val s = ++seq
        val ts = uniqueTs.next(clickTs)
        SessionDir.writeAtomic(File(dir, SessionDir.markName(ts, s)), "$ts: $s\n")
        val shot = shotter?.snapshot(File(dir, SessionDir.shotName(ts, s))) ?: ScreenShotter.Shot(false, 0, 0, 0)
        sampler?.sampleAt(ts)                                   // dense frame shares the MARK ts
        if (shot.ok) appendEvent("""{"t":$clickTs,"type":"mark","seq":$s,"shot":"${SessionDir.shotName(ts, s)}","shot_w":${shot.w},"shot_h":${shot.h},"shot_age_ms":${clickTs - shot.acquiredEpochMs}}""")
        else appendEvent("""{"t":$clickTs,"type":"mark_noshot","seq":$s}""")
        breadcrumb("devrec: mark $s (shot=${shot.ok})")
    }

    private fun resizeTo() {
        val v = vd ?: return
        val info = displayInfoNow(); val nw = info.first; val nh = info.second
        if (nw == capW && nh == capH) return
        val old = reader
        val nr = ImageReader.newInstance(nw, nh, PixelFormat.RGBA_8888, 3)
        val ok = runCatching { v.resize(nw, nh, resources.displayMetrics.densityDpi); v.surface = nr.surface }
            .onFailure { breadcrumb("devrec: resize failed: ${it.message}") }.isSuccess
        if (!ok) { runCatching { nr.close() }; return }
        capW = nw; capH = nh; reader = nr
        shotter?.swapReader(nr, rec)
        runCatching { old?.close() }
        breadcrumb("devrec: resized to $nw x $nh")
    }

    private fun appendEvent(line: String) =
        runCatching { File(dir, "events.jsonl").appendText(line + "\n") }

    /** (width, height, rotationDeg) of the default display — Service is non-visual, use DisplayManager. */
    private fun displayInfoNow(): Triple<Int, Int, Int> {
        val disp = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics(); @Suppress("DEPRECATION") disp.getRealMetrics(m)
        val deg = when (disp.rotation) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
        return Triple(m.widthPixels, m.heightPixels, deg)
    }

    /** Self-contained foreground query (debug logging only). 60s window + latest-timestamp tracking. */
    private fun foregroundPkgNow(): String = runCatching {
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return ""
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 60_000, now)
        var pkg = ""; var bestTs = 0L; val e = android.app.usage.UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp >= bestTs) {
                bestTs = e.timeStamp; pkg = e.packageName
            }
        }
        pkg
    }.getOrDefault("")

    private fun startForegroundMP() {
        val ch = "devrec"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "DevRecorder", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, ch).setContentTitle("DevRecorder")
            .setSmallIcon(android.R.drawable.ic_menu_camera).build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    /** Idempotent capture teardown: own flag, NOT shared with the start-guard. Rewrites meta with stop fields. */
    private fun teardownCapture() {
        if (captureStopped) return
        captureStopped = true
        sampler?.stop(); sampler = null
        shotter?.release(); shotter = null
        main.post { panel?.hide(); panel = null }
        runCatching { vd?.release() }; vd = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.unregisterCallback(cb) }
        runCatching { projection?.stop() }; projection = null
        if (::dir.isInitialized) runCatching {
            SessionDir.writeAtomic(File(dir, "meta.json"),
                SessionDir.meta(BuildConfig.VERSION_NAME, android.os.Build.MODEL, startedAtMs,
                    System.currentTimeMillis(), seq).toString())
        }
    }

    private fun teardownAll() {
        if (::rec.isInitialized) rec.post { teardownCapture(); finishService() } else finishService()
    }

    private fun finishService() {
        live = null
        if (::recThread.isInitialized) recThread.quitSafely()
        main.post { @Suppress("DEPRECATION") stopForeground(true); stopSelf() }
    }

    private fun breadcrumb(msg: String) {
        Log.i(TAG, msg)
        runCatching { File(filesDir, "bob-breadcrumbs.log").appendText("${System.currentTimeMillis()}: $msg\n") }
    }

    companion object {
        private const val TAG = "DevRec"; private const val NOTIF_ID = 4242
        const val ACTION_START = "com.bobassist.phase0.DEVREC_START"
        const val ACTION_MARK = "com.bobassist.phase0.DEVREC_MARK"
        const val ACTION_STOP = "com.bobassist.phase0.DEVREC_STOP"
        const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"
        @Volatile var live: DevRecorderService? = null
    }
}
