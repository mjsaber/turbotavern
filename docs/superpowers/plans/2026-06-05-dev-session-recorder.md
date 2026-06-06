# DevRecorder（开发期会话录制器）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现。步骤用 `- [ ]` 复选框跟踪。

**Goal:** 一个 debug-only 的 in-app 会话录制器：屏幕上单个 MARK 按钮（游戏中可点）+ 每个 mark 一张截图 + 全程 connectionsJson/前台/旋转采样，结束后 host 端 pull + analyze 对齐分析。

**Architecture:** 见 spec `docs/superpowers/specs/2026-06-05-dev-session-recorder-design.md`（v4，Codex READY-TO-PLAN）。核心：DevRecorder 持有自己的 MediaProjection（Android 14 单 VirtualDisplay 约束 → 录制时 tier 不同开）；单录制 HandlerThread 串行化捕获/数据，主线程拥有面板 UI；ScreenShotter 持有最新 Image、仅 MARK 时转 PNG；录制格式兼容 `analyze-recording.py`。

**Tech Stack:** Kotlin (JVM17, minSdk29/target35), Android MediaProjection/VirtualDisplay/ImageReader, WindowManager overlay, JUnit4（纯逻辑），Python3 stdlib（analyzer）。

**源集纪律:** `ImagePlaneBitmap` 放 `src/main`（被 main 的 grabber 引用）；其余 DevRecorder 类放 `src/debug`。

> **路径/CWD 约定（codex plan-review CRITICAL #1）：** Android 工程在仓库的 `android/overlay-app/` 下。**所有命令——`./gradlew`、文件路径、以及 `git`——统一以 `cd android/overlay-app` 为 cwd**（git 在子目录可用，`git add` 路径相对该目录，如 `app/...`、`scripts/...`）。下文每步命令默认在此 cwd 执行。

---

## Stage 0：抽出 `ImagePlaneBitmap`（src/main 共享像素 helper）

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/herotier/ImagePlaneBitmap.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/herotier/MediaProjectionGrabber.kt`（改用 helper，行为不变）

- [ ] **Step 1: 写 helper**（把 grabber 现有 row-stride 去 padding 逻辑原样抽出）

```kotlin
package com.bobassist.phase0.herotier

import android.graphics.Bitmap
import android.media.Image

/** Convert a single RGBA_8888 [Image] (plane 0) to a cropped [Bitmap], handling row-stride padding.
 *  Shared by [MediaProjectionGrabber] and the debug ScreenShotter so the pixel path never drifts.
 *  Does NOT close the image. */
object ImagePlaneBitmap {
    fun of(image: Image, captureW: Int, captureH: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * captureW
        val bufferW = captureW + (if (pixelStride > 0) rowPadding / pixelStride else 0)
        val padded = Bitmap.createBitmap(bufferW, captureH, Bitmap.Config.ARGB_8888)
        // duplicate()+rewind: copyPixelsFromBuffer advances the buffer position, so the SAME held
        // Image converted on a later MARK must read from position 0 (codex plan-review CRITICAL #4).
        padded.copyPixelsFromBuffer(plane.buffer.duplicate().apply { rewind() })
        return if (bufferW != captureW) {
            val cropped = Bitmap.createBitmap(padded, 0, 0, captureW, captureH)
            padded.recycle()
            cropped
        } else padded
    }
}
```

- [ ] **Step 2: grabber 改用 helper.** 在 `MediaProjectionGrabber.capture()` 里把内联的 plane→bitmap 段替换为：
```kotlin
            val bitmap = ImagePlaneBitmap.of(image, captureW, captureH)
            val d = displayInfo()
```
（删掉原 `plane/pixelStride/rowStride/rowPadding/bufferW/padded/cropped` 那几行；其余 Transform/Frame 逻辑不动。）

- [ ] **Step 3: 验证 tier 测试仍绿**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.herotier.*"`
Expected: PASS（grabber 行为未变；没有针对它的单测也至少不回归编译）。
Run: `./gradlew :app:compileDebugKotlin -q && echo OK`

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/bobassist/phase0/herotier/ImagePlaneBitmap.kt \
        app/src/main/java/com/bobassist/phase0/herotier/MediaProjectionGrabber.kt
git commit -m "refactor(herotier): extract ImagePlaneBitmap (shared by grabber + devrec)"
```

---

## Stage 1：`SessionDir`（纯逻辑，TDD）

会话目录布局 + 文件名 + 唯一 ts + meta。纯函数，JUnit 可测、无 Android。

**Files:**
- Create: `app/src/debug/java/com/bobassist/phase0/devrec/SessionDir.kt`
- Test: `app/src/test/java/com/bobassist/phase0/devrec/SessionDirTest.kt`

> 注：测试放 `src/test`，它能看到 `src/debug` 吗？**能** —— debug 单元测试源集 `testDebug` 同时编译 `src/test` + `src/debug`。本计划所有 devrec 单测用 `./gradlew :app:testDebugUnitTest`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.bobassist.phase0.devrec

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SessionDirTest {
    private fun tmp() = File.createTempFile("devrec", "").let { it.delete(); it.mkdirs(); it }

    @Test fun frameNameIsNumericStem() =
        assertEquals("1780700000000.json", SessionDir.frameName(1780700000000))

    @Test fun markAndShotNames() {
        assertEquals("MARK-1780700000000-3.txt", SessionDir.markName(1780700000000, 3))
        assertEquals("SHOT-1780700000000-3.png", SessionDir.shotName(1780700000000, 3))
    }

    @Test fun uniqueTsMonotonicAndCollisionBump() {
        val u = SessionDir.UniqueTs()
        assertEquals(1000L, u.next(1000))
        assertEquals(1001L, u.next(1000))   // same ms -> +1
        assertEquals(1002L, u.next(1000))   // same ms again -> +1 again
        assertEquals(2000L, u.next(2000))   // jumps forward to real time
        assertEquals(2001L, u.next(1500))   // never goes backward
    }

    @Test fun rollPreviousMovesOldSession() {
        val root = tmp(); val dir = File(root, "devrec"); dir.mkdirs()
        File(dir, "1.json").writeText("[]")
        val prev = File(root, "devrec-prev")
        SessionDir.rollPrevious(dir, prev)
        assertFalse(File(dir, "1.json").exists())
        assertTrue(File(prev, "1.json").exists())
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.devrec.SessionDirTest"`
Expected: FAIL（`SessionDir` 未定义）。

- [ ] **Step 3: 实现**

```kotlin
package com.bobassist.phase0.devrec

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

    /** Atomic discrete-file write: temp + rename (codex plan-review SHOULD-FIX). NOT for events.jsonl (append log). */
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
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.devrec.SessionDirTest"`
Expected: PASS。

- [ ] **Step 5: Commit**
```bash
git add app/src/debug/java/com/bobassist/phase0/devrec/SessionDir.kt \
        app/src/test/java/com/bobassist/phase0/devrec/SessionDirTest.kt
git commit -m "feat(devrec): SessionDir pure helpers (filenames, unique ts, roll-prev)"
```

---

## Stage 2：`analyze-recording.py` 兼容修正 + mark↔SHOT 节

**Files:**
- Modify: `scripts/analyze-recording.py`

- [ ] **Step 1: 数字名帧过滤**（修 `meta.json` 会崩的 bug）。把 `load()` 里的 frame glob 改为只认纯数字 stem：
```python
import re
NUM = re.compile(r"^\d+\.json$")
def load(d):
    frames = []
    for f in sorted((p for p in glob.glob(os.path.join(d, "*.json")) if NUM.match(os.path.basename(p))),
                    key=lambda p: int(os.path.basename(p)[:-5])):
        ts = int(os.path.basename(f)[:-5])
        try: conns = json.load(open(f))
        except Exception: conns = []
        frames.append((ts, conns))
    marks = []
    for f in glob.glob(os.path.join(d, "MARK-*.txt")):
        parts = os.path.basename(f)[5:-4].split("-", 1)
        marks.append((int(parts[0]), parts[1] if len(parts) > 1 else ""))
    return frames, sorted(marks)
```

- [ ] **Step 2: 别在无连接帧时早退**（core 关着也可能有 marks+shots，codex plan-review SHOULD-FIX）。把 `main()` 开头的
```python
    if not frames:
        print("no frames"); return
    t0 = frames[0][0]
```
改为：
```python
    if not frames and not marks:
        print("no frames, no marks"); return
    t0 = frames[0][0] if frames else marks[0][0]   # fall back to first mark as time origin
```
并把后续依赖 `frames` 的小节（lifespans/指纹/change-log）各自加 `if frames:` 守卫，使无帧时只打印 marks↔SHOT 节。

- [ ] **Step 3: 新增 marks↔SHOT 对齐节.** 在 `main()` 末尾追加：
```python
    # marks with screenshots: align each mark to its SHOT file + nearest frame
    shots = {}  # seq -> filename
    for p in glob.glob(os.path.join(d, "SHOT-*.png")):
        b = os.path.basename(p)[5:-4]            # <ts>-<seq>
        seq = b.split("-", 1)[1] if "-" in b else b
        shots[seq] = os.path.basename(p)
    frame_ts = [t for t, _ in frames]
    def nearest(ts):
        return min(frame_ts, key=lambda x: abs(x - ts)) if frame_ts else None
    print("\n=== marks with screenshots ===")
    for mts, lbl in marks:
        nf = nearest(mts)
        nrel = f"+{rel(nf):.1f}s" if nf is not None else "-"
        print(f"  MARK +{rel(mts):7.1f}s seq={lbl:>3}  shot={shots.get(lbl,'(none)')}  nearest_frame={nrel}")
    metap = os.path.join(d, "meta.json")
    if os.path.exists(metap):
        print("\n=== meta ===\n" + open(metap).read())
```

- [ ] **Step 4: 自检**（用现有录制确认不崩 + 数字名仍解析；cwd = android/overlay-app）

Run: `cp -r recordings/2026-05-29-session1/spike-d /tmp/an && touch /tmp/an/meta.json && python3 scripts/analyze-recording.py /tmp/an | tail -20`
Expected: 正常打印 lifespans/指纹/change-log + 新的 marks 节，**不被 `meta.json` 崩**。

- [ ] **Step 5: Commit**
```bash
git add scripts/analyze-recording.py
git commit -m "feat(devrec): analyze-recording.py numeric-stem frames + marks↔SHOT section"
```

---

## Stage 3：`ScreenShotter`（持有最新 Image，MARK 时转 PNG）

**Files:**
- Create: `app/src/debug/java/com/bobassist/phase0/devrec/ScreenShotter.kt`

设备相关（ImageReader），不做单测；逻辑结构清晰、device 阶段验证。

- [ ] **Step 1: 实现.** 全部方法在录制线程调用（§5.0/§5.6）。

```kotlin
package com.bobassist.phase0.devrec

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import com.bobassist.phase0.herotier.ImagePlaneBitmap
import java.io.File

/**
 * Holds the latest captured [Image] (cheap: acquire+close per frame, no Bitmap), converting to PNG
 * only at MARK time. All calls on the recorder thread ([handler]); [held] has no lock. Spec §5.6.
 */
class ScreenShotter(
    private var reader: ImageReader,
    private val captureW: () -> Int,
    private val captureH: () -> Int,
    handler: Handler,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private var held: Image? = null
    private var heldAcquiredEpochMs = 0L

    private val listener = ImageReader.OnImageAvailableListener { r ->
        // acquire-first, replace held only on non-null (keep last good image if queue drained)
        val next = runCatching { r.acquireLatestImage() }.getOrNull()
        if (next != null) { val old = held; held = next; heldAcquiredEpochMs = nowMs(); runCatching { old?.close() } }
    }

    init { reader.setOnImageAvailableListener(listener, handler) }

    data class Shot(val ok: Boolean, val w: Int, val h: Int, val acquiredEpochMs: Long)

    /** Convert the held image to a PNG at [out] (temp+rename). Returns ok=false if no frame yet. */
    fun snapshot(out: File): Shot {
        val img = held ?: return Shot(false, 0, 0, 0)
        return runCatching {
            val bmp = ImagePlaneBitmap.of(img, captureW(), captureH())
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(out)
            val s = Shot(true, bmp.width, bmp.height, heldAcquiredEpochMs)
            bmp.recycle(); s
        }.getOrElse { log("devrec: shot failed: ${it.message}"); Shot(false, 0, 0, 0) }
    }

    /** Rotation: point at a new reader. Caller resizes the VD + closes the old reader (§5.6 teardown). */
    fun swapReader(newReader: ImageReader, handler: Handler) {
        runCatching { reader.setOnImageAvailableListener(null, null) }
        held?.let { runCatching { it.close() } }; held = null
        reader = newReader
        reader.setOnImageAvailableListener(listener, handler)
    }

    fun release() {
        runCatching { reader.setOnImageAvailableListener(null, null) }
        held?.let { runCatching { it.close() } }; held = null
    }
}
```

- [ ] **Step 2: 编译**

Run: `./gradlew :app:compileDebugKotlin -q && echo OK`
Expected: OK。

- [ ] **Step 3: Commit**
```bash
git add app/src/debug/java/com/bobassist/phase0/devrec/ScreenShotter.kt
git commit -m "feat(devrec): ScreenShotter holds latest Image, PNG only at MARK"
```

---

## Stage 4：`ConnectionSampler`（500ms 连接 + 前台 + 旋转）

**Files:**
- Create: `app/src/debug/java/com/bobassist/phase0/devrec/ConnectionSampler.kt`

- [ ] **Step 1: 实现.** 录制线程周期 runnable。

```kotlin
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

    /** Write a connection frame at exactly [ts] (so onMark's dense frame shares the MARK timestamp,
     *  codex plan-review SHOULD-FIX on alignment). Atomic via SessionDir.writeAtomic. */
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
```

- [ ] **Step 2: 编译**: `./gradlew :app:compileDebugKotlin -q && echo OK`

- [ ] **Step 3: Commit**
```bash
git add app/src/debug/java/com/bobassist/phase0/devrec/ConnectionSampler.kt
git commit -m "feat(devrec): ConnectionSampler (500ms frames + fg/rotation events)"
```

---

## Stage 5：`MarkerPanel`（可点可拖悬浮窗，主线程）

**Files:**
- Create: `app/src/debug/java/com/bobassist/phase0/devrec/MarkerPanel.kt`

照搬 `overlay/OverlayWindow.kt` 的 TYPE_APPLICATION_OVERLAY + 拖动模式，但用 LinearLayout 容器 + 拖动手柄 + 两个 Button；**主线程拥有**（WindowManager 只能主线程）。

- [ ] **Step 1: 实现**

```kotlin
package com.bobassist.phase0.devrec

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Touchable floating panel: [≡][MARK][STOP]. Drag via the ≡ handle; buttons fire callbacks.
 * MAIN-THREAD ONLY (WindowManager). Buttons' onClick run on main; caller stamps clickTs there.
 */
class MarkerPanel(
    private val context: Context,
    private val onMark: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // touchable (no NOT_TOUCHABLE), never steals keys
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 8.dp() }

    fun show() {
        if (view != null) return
        val handle = TextView(context).apply {
            text = "≡"; setTextColor(Color.WHITE); setPadding(24, 16, 24, 16)
            setBackgroundColor(0xAA000000.toInt()); setOnTouchListener(DragListener())
        }
        val mark = Button(context).apply { text = "MARK"; setOnClickListener { onMark() } }
        val stop = Button(context).apply { text = "STOP"; setOnClickListener { onStop() } }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(handle); addView(mark); addView(stop)
        }
        wm.addView(row, lp); view = row
    }

    fun hide() { view?.let { runCatching { wm.removeView(it) } }; view = null }

    private inner class DragListener : View.OnTouchListener {
        private var tx = 0f; private var ty = 0f; private var sx = 0; private var sy = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { tx = e.rawX; ty = e.rawY; sx = lp.x; sy = lp.y; return true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    runCatching { wm.updateViewLayout(v, lp) }; return true   // v is non-null (the touched view)
                }
            }
            return false
        }
    }

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()
}
```

- [ ] **Step 2: 编译**: `./gradlew :app:compileDebugKotlin -q && echo OK`

- [ ] **Step 3: Commit**
```bash
git add app/src/debug/java/com/bobassist/phase0/devrec/MarkerPanel.kt
git commit -m "feat(devrec): MarkerPanel touchable draggable overlay (MARK/STOP)"
```

---

## Stage 6：`DevRecorderService` + `DevRecorderActivity` + 调试 manifest

这是把前面拼起来的编排层。**严格复刻 BobVpnService 的 Android 14 投影顺序 + resize 处理**（见 `BobVpnService.enableTier/startTierPipeline/onTierConfigChanged/displayInfoNow`）。

**Files:**
- Create: `app/src/debug/java/com/bobassist/phase0/devrec/DevRecorderService.kt`
- Create: `app/src/debug/java/com/bobassist/phase0/devrec/DevRecorderActivity.kt`
- Modify: `app/src/debug/AndroidManifest.xml`

- [ ] **Step 1: DevRecorderActivity（同意 + 启动）**

```kotlin
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
```

- [ ] **Step 2: DevRecorderService.** 投影顺序/resize 复刻 BobVpnService；编排 sampler/shotter/panel；onMark 三件物。完整代码：

```kotlin
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

class DevRecorderService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rec: Handler                 // recorder thread
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
        override fun onStop() { teardownAll() }                         // full stop, not just capture (CRITICAL #3)
        override fun onCapturedContentResize(w: Int, h: Int) { rec.post { resizeTo() } }   // recompute from display
    }

    override fun onBind(i: Intent?): IBinder? = null

    // Rotation fallback for pre-34 / when onCapturedContentResize doesn't fire (spec §5.2).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::rec.isInitialized) rec.post { resizeTo() }
    }

    override fun onDestroy() { live = null; super.onDestroy() }   // clear stale live even if STOP path didn't run

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        when (i?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION") val data = i.getParcelableExtra<Intent>(EXTRA_DATA)
                start(i.getIntExtra(EXTRA_CODE, 0), data)
            }
            ACTION_MARK -> mark()
            ACTION_STOP -> { teardownAll() }
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent?) {
        if (data == null) { stopSelf(); return }
        if (live != null) { breadcrumb("devrec: already recording; ignoring start"); return }   // dup-start guard
        captureStopped = false; seq = 0; startedAtMs = 0L     // reset per-session state (Service may be reused)
        live = this
        recThread = HandlerThread("devrec").apply { start() }
        rec = Handler(recThread.looper)
        // FGS claim must be quick + must not crash if the type claim fails (codex plan-review SHOULD-FIX).
        if (!runCatching { startForegroundMP() }.onFailure { breadcrumb("devrec: FGS claim failed: ${it.message}") }.isSuccess) {
            teardownAll(); return
        }
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
            val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)   // maxImages=3 (§5.6)
            reader = r
            val v = runCatching {
                mp.createVirtualDisplay("devrec", capW, capH, resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            }.getOrElse { breadcrumb("devrec: createVirtualDisplay threw: ${it.message}"); null }
            if (v == null) { main.post { teardownAll() }; return@post }
            vd = v
            shotter = ScreenShotter(r, { capW }, { capH }, rec, { System.currentTimeMillis() }, ::breadcrumb)
            sampler = ConnectionSampler(dir, rec, { System.currentTimeMillis() }, uniqueTs,
                { foregroundPkgNow() }, { displayInfoNow().third }, ::appendEvent).also { it.start() }
            startedAtMs = System.currentTimeMillis()
            SessionDir.writeAtomic(File(dir, "meta.json"),
                SessionDir.meta(BuildConfig.VERSION_NAME, android.os.Build.MODEL, startedAtMs).toString())
            main.post {
                runCatching {                                           // panel add can fail (overlay perm); keep recording
                    panel = MarkerPanel(this,
                        onMark = { val ts = System.currentTimeMillis(); rec.post { onMark(ts) } },
                        onStop = { teardownAll() }).also { it.show() }
                }.onFailure { breadcrumb("devrec: panel show failed (use adb mark fallback): ${it.message}") }
            }
            breadcrumb("devrec: recording up ($capW x $capH)")
        }
    }

    private fun mark() { val ts = System.currentTimeMillis(); rec.post { onMark(ts) } }   // adb fallback path

    private fun onMark(clickTs: Long) {
        if (captureStopped) return                            // a MARK queued before STOP must not run post-teardown
        val s = ++seq
        val ts = uniqueTs.next(clickTs)
        SessionDir.writeAtomic(File(dir, SessionDir.markName(ts, s)), "$ts: $s\n")
        val shot = shotter?.snapshot(File(dir, SessionDir.shotName(ts, s))) ?: ScreenShotter.Shot(false,0,0,0)
        sampler?.sampleAt(ts)                                  // dense frame shares the MARK ts (alignment)
        if (shot.ok) appendEvent("""{"t":$clickTs,"type":"mark","seq":$s,"shot":"${SessionDir.shotName(ts,s)}","shot_w":${shot.w},"shot_h":${shot.h},"shot_age_ms":${clickTs - shot.acquiredEpochMs}}""")
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

    private fun startForegroundMP() {
        val ch = "devrec"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "DevRecorder", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, ch).setContentTitle("DevRecorder")
            .setSmallIcon(android.R.drawable.ic_menu_camera).build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    /** Idempotent capture teardown (CRITICAL #2): own flag, NOT shared with start-guard. Rewrites meta with stop fields. */
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

    /** Self-contained foreground query (debug logging only; not the kill-gate, so no need to share impl).
     *  60s window + latest-timestamp tracking (codex plan-review): a game foregrounded >10s ago still resolves. */
    private fun foregroundPkgNow(): String = runCatching {
        val usm = getSystemService(android.app.usage.UsageStatsManager::class.java) ?: return ""
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(now - 60_000, now)
        var pkg = ""; var bestTs = 0L; val e = android.app.usage.UsageEvents.Event()
        while (ev.hasNextEvent()) { ev.getNextEvent(e)
            if (e.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp >= bestTs) {
                bestTs = e.timeStamp; pkg = e.packageName } }
        pkg
    }.getOrDefault("")

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
```

> **自包含，不动 BobVpnService（除只读引用 `liveSession` 做"tier 在跑"警告）。** 前台查询 + breadcrumb 都在 DevRecorderService 内自带（debug-only 日志，非 kill-gate，无需共享实现 → 无漂移风险）。main 侧改动仅 Stage 0 的 `ImagePlaneBitmap` + Stage 9 的 §7 OCR 日志。

- [ ] **Step 3: 调试 manifest.** 在 `app/src/debug/AndroidManifest.xml` `<application>` 内加：
```xml
        <activity android:name="com.bobassist.phase0.devrec.DevRecorderActivity"
            android:exported="true" android:label="DevRecorder" />
        <service android:name="com.bobassist.phase0.devrec.DevRecorderService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
```
（`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限 main manifest 已有，合并即可。）

- [ ] **Step 4: 编译**: `./gradlew :app:assembleDebug 2>&1 | tail -5`（Expected: BUILD SUCCESSFUL）

- [ ] **Step 5: Commit**
```bash
git add app/src/debug/java/com/bobassist/phase0/devrec/DevRecorderService.kt \
        app/src/debug/java/com/bobassist/phase0/devrec/DevRecorderActivity.kt \
        app/src/debug/AndroidManifest.xml
git commit -m "feat(devrec): DevRecorderService + Activity + manifest (projection, panel, marks)"
```

---

## Stage 7：`TestReceiver` adb 兜底（devrec_mark / devrec_stop）

**Files:**
- Modify: `app/src/debug/java/com/bobassist/phase0/TestReceiver.kt`

- [ ] **Step 1:** 在 `when (cmd)` 加两条，转发给运行中的 service（不导出 service）：
```kotlin
            "devrec_mark" -> {
                val s = com.bobassist.phase0.devrec.DevRecorderService.live
                if (s == null) Log.i(TAG, "devrec_mark: not recording")
                else { context.startService(android.content.Intent(context, s::class.java)
                    .setAction(com.bobassist.phase0.devrec.DevRecorderService.ACTION_MARK)); Log.i(TAG, "devrec_mark dispatched") }
            }
            "devrec_stop" -> {
                val s = com.bobassist.phase0.devrec.DevRecorderService.live
                if (s == null) Log.i(TAG, "devrec_stop: not recording")
                else { context.startService(android.content.Intent(context, s::class.java)
                    .setAction(com.bobassist.phase0.devrec.DevRecorderService.ACTION_STOP)); Log.i(TAG, "devrec_stop dispatched") }
            }
```

- [ ] **Step 2: 编译 + Commit**
```bash
./gradlew :app:compileDebugKotlin -q && echo OK
git add app/src/debug/java/com/bobassist/phase0/TestReceiver.kt
git commit -m "feat(devrec): adb fallback devrec_mark/devrec_stop via TestReceiver"
```

---

## Stage 8：`scripts/dev-record.sh`（start/mark/stop/pull/analyze）

**Files:**
- Create: `scripts/dev-record.sh`（chmod +x）

- [ ] **Step 1: 写脚本**（照 `test-spike-d.sh` 的风格；`-s "$SERIAL"` 可选）

```bash
#!/usr/bin/env bash
set -uo pipefail
BOB=com.bobassist.phase0
DEV="${SERIAL:+-s $SERIAL}"
HOST_OUT=/tmp/devrec
cd "$(dirname "$0")/.."

case "${1:-}" in
  start)
    [[ "${2:-}" == "--rebuild" ]] && { ./gradlew :app:assembleDebug -q && adb $DEV install -r app/build/outputs/apk/debug/app-debug.apk; }
    adb $DEV shell am start -n "$BOB/com.bobassist.phase0.devrec.DevRecorderActivity"
    echo "Tap 'Start Recording' + grant full-screen capture. Then play; MARK on screen, or: $0 mark"
    ;;
  mark) adb $DEV shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB" --es cmd devrec_mark >/dev/null; echo "marked (fallback)";;
  stop) adb $DEV shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB" --es cmd devrec_stop >/dev/null; echo "stopped";;
  pull)
    rm -rf "$HOST_OUT"; mkdir -p "$HOST_OUT"
    adb $DEV shell "run-as $BOB sh -c 'tar cf - files/devrec 2>/dev/null'" > "$HOST_OUT.tar" 2>/dev/null
    tar xf "$HOST_OUT.tar" -C "$HOST_OUT" --strip-components=2 2>/dev/null || true
    echo "pulled -> $HOST_OUT  (frames=$(ls $HOST_OUT/*.json 2>/dev/null|grep -cE '/[0-9]+\.json') shots=$(ls $HOST_OUT/SHOT-*.png 2>/dev/null|wc -l|tr -d ' ') marks=$(ls $HOST_OUT/MARK-*.txt 2>/dev/null|wc -l|tr -d ' '))"
    ;;
  analyze) python3 scripts/analyze-recording.py "$HOST_OUT";;
  *) echo "Usage: SERIAL=<serial> $0 {start [--rebuild]|mark|stop|pull|analyze}"; exit 1;;
esac
```

- [ ] **Step 2:** `chmod +x scripts/dev-record.sh`; Commit
```bash
git add scripts/dev-record.sh
git commit -m "feat(devrec): dev-record.sh host harness (start/mark/stop/pull/analyze)"
```

---

## Stage 9：tier 实时 OCR 日志（spec §7 附带小改）

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/herotier/HeroTierCoordinator.kt`

- [ ] **Step 1:** 在 `captureOnce()` 得到 `badges` 后加一行（BuildConfig.DEBUG 守，复用 breadcrumb）：
```kotlin
        if (com.bobassist.phase0.BuildConfig.DEBUG) {
            val lines = runCatching { ocr.recognize(frame) }.getOrNull()   // 注意：避免重复 OCR，见下
        }
```
**实现注意：** 不要二次 OCR。改为在现有 `matcher.match(ocr.recognize(frame))` 处拆开：
```kotlin
        val ocrLines = runCatching { ocr.recognize(frame) }
            .getOrElse { breadcrumb("herotier: ocr failed: ${it.message}"); emptyList() }   // don't swallow silently
        val badges = runCatching { matcher.match(ocrLines) }
            .getOrElse { breadcrumb("herotier: match failed: ${it.message}"); emptyList() }
        if (com.bobassist.phase0.BuildConfig.DEBUG)
            breadcrumb("herotier: ocr=${ocrLines.size} lines [${ocrLines.take(6).joinToString("|"){ it.text }}] matched=${badges.size}")
```
（把原 `runCatching { matcher.match(ocr.recognize(frame)) }` 一行展开成上面三段，行为等价 + 多一行调试日志。）

- [ ] **Step 2:** 编译 + 现有 coordinator 测试绿
```bash
./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.herotier.HeroTierCoordinator*" && echo OK
```

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/bobassist/phase0/herotier/HeroTierCoordinator.kt
git commit -m "feat(herotier): debug breadcrumb of OCR lines + match count per frame"
```

---

## Stage 10：on-device 验收（手动 + 脚本）

- [ ] **Step 1:** 装机：`SERIAL=192.168.86.23:40187 scripts/dev-record.sh start --rebuild`
- [ ] **Step 2:** 手机点 "Start Recording" + 授权（确认是**整屏**、没有"选单个应用"）。悬浮 [≡][MARK][STOP] 出现。
- [ ] **Step 3:** 先 Start VPN（让连接数据有值），进炉石排一局战棋，沿途点 MARK：排队/选人出现/选定英雄/进战斗各点一次。STOP。
- [ ] **Step 4:** `SERIAL=... scripts/dev-record.sh pull` → 断言 `MARK 数 == 点击数`，`SHOT 数 + mark_noshot 数 == MARK 数`。
- [ ] **Step 5:** 看一张 `SHOT-*.png`：可解码、非全黑、尺寸=屏幕分辨率、是真实游戏画面（含顶边面板）。
- [ ] **Step 6:** `scripts/dev-record.sh analyze` → 连接生命周期 + 指纹回放 + marks↔SHOT 节，不崩。
- [ ] **Step 7:** breadcrumb 确认竖→横进炉石时 `devrec: resized to 2412x1080`，无 createVirtualDisplay 二次异常。
- [ ] **Step 8:** 把这次录制交给我/Codex 分析：找选人阶段的连接签名（决定 tier 自动触发），并用选人 SHOT 验证 OCR 读名 + 定名字裁剪框。

---

## Self-review notes
- Spec §覆盖：§5.0→Stage6 线程模型；§5.1→Stage6 Activity；§5.2→Stage6 service；§5.3→Stage4；§5.4→Stage5；§5.5→Stage6 onMark；§5.6→Stage3；§5.7→Stage1；§5.8→Stage7/8；§6 错误处理→分散在各 service 路径；§7→Stage9。
- 类型一致：`SessionDir.frameName/markName/shotName/UniqueTs.next/rollPrevious/meta`、`ScreenShotter.Shot{ok,w,h,acquiredEpochMs}/snapshot/swapReader/release`、`ConnectionSampler.sampleOnce/start/stop`、`MarkerPanel.show/hide`、`DevRecorderService.ACTION_*/EXTRA_*/live/foregroundPkgNow/breadcrumbStatic` 在各 Stage 一致引用。
- 自包含纪律：DevRecorder 不动 BobVpnService（仅只读 `liveSession` 做警告）；前台查询/breadcrumb 自带（debug 日志，非 kill-gate）。main 改动仅 `ImagePlaneBitmap`（Stage0）+ §7 OCR 日志（Stage9）。无占位符/TODO 进 commit。
```
