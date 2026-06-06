# 开发期会话录制器（DevRecorder）设计文档

**Date:** 2026-06-05
**Status:** v4（codex round-3 已修：READY-TO-PLAN 的两项 SHOULD-FIX 已并入）
**Layer:** 开发/调试基础设施（`android/overlay-app/`），debug variant 限定，不进 release
**Scope:** 一个**边玩边打点 + 全程后台录制（连接快照 + 截图 + 前台/旋转）**的开发期工具。让"游戏运行中到底发生了什么"可以事后离线复原、和操作时间点对齐分析——不再依赖实时口头同步（已被证明不可靠）。设计成**通用工具**，服务 hero-tier 触发器调查在内的所有后续开发环节。

---

## 1. Why（动机）

调试 hero-tier overlay 时暴露的根本问题：**实时协调不可靠。** 用户在手机上玩横屏炉石，无法同时跑 adb；口头同步"我到选人界面了"在时间上对不齐（OCR 帧、连接快照都是亚秒级事件）。现有 `TestReceiver` 录制器虽然能每 500ms 抓 `connectionsJson` + 写 `MARK-*` 文件，但 **mark 只能由 adb 广播触发**——玩家玩的时候按不了。

**用户原话方案（2026-06-05 确认）：** 提供一个屏幕上的按钮，游戏中点按钮记录时间点；app 全程录所有需要的 log + 截图；结束后我拉取、对照截图（必要时调 Codex 做图像识别）认出每个标记是什么、再分析。**并要求扩展成未来所有开发环节都能用的工具。**

**两个确认的关键决策：**
1. **每次 MARK 顺手存一张截图**（标记自解释；截图还能离线喂 Codex/OCR 验证）。
2. **单个 MARK 按钮**（游戏中盲点、自动编号；事后我对照截图认内容）。

---

## 2. 与现有设施的关系（不重复造轮子）

| 已有 | 复用方式 |
|---|---|
| `TestReceiver` 的连接采样（500ms→`<ts>.json`）+ `MARK-<ts>-<label>.txt` | **录制格式照搬**：DevRecorder 产出同样的数字名 `<ts>.json` + `MARK-*` 文件，再加 `SHOT-*.png` + `meta.json` + `events.jsonl`。`TestReceiver` 还作为 **adb 兜底通道**转发 mark/stop（§5.8）。 |
| `scripts/analyze-recording.py` | 复用并**小修**：当前 `glob("*.json")` 后 `int(stem)` 会被 `meta.json` 崩；改为只认**纯数字名** `.json` 为帧（§5.7）。再扩一节"mark↔SHOT↔最近连接帧"对齐。 |
| `MediaProjectionGrabber` 的 buffer→Bitmap（含 row-stride 去 padding） | 抽出共享 helper `ImagePlaneBitmap`，grabber 与 `ScreenShotter` 共用，避免漂移（§5.6）。 |
| BobVpnService 已验证的 Android 14 投影 FGS/同意顺序 + `VirtualDisplay.resize()` 旋转处理 | DevRecorder 复刻同一套顺序与 resize 策略（§5.2）。 |
| `2026-05-29-bob-regression-harness-design.md`（回归 harness） | **互补**：DevRecorder 产出格式兼容，将来可作 L1 fixture 来源。本 spec 不碰 L1/L2。 |

---

## 3. 一条诚实边界：单投影约束

Android 14 起，**一个 MediaProjection 实例只允许一个 VirtualDisplay**（这正是本轮 tier 旋转 bug 的根因）。因此：

> **DevRecorder 持有自己独立的投影；录制时 tier 功能不同时开。**

后果（写进工具文档，避免误判）：
- 录制时**不需要、也不应该**同时开 tier overlay。两者各自申请投影、不并存；DevRecorder 启动若检测到 tier 投影在跑，breadcrumb 警告（仅警告，不强耦合）。
- 截图拍到的是**原始游戏画面 + 录制面板本身**（见 §5.4：面板会被合成进截图，停在顶边避开英雄名字区）。原始选人截图正好能离线喂 Codex/OCR 验证"名字读得对不对 + 定出名字裁剪框"。
- "tier badge 渲染是否正确压在头像上"是**另一项验证**，单独用 tier 自己的实时链路 + per-frame OCR 日志（§7）做。
- 让 DevRecorder 与 tier 共享一个投影（共享 ScreenSource 改造）是 **phase 2 / out-of-scope**，本期 YAGNI。

---

## 4. 模块结构

```
app/src/debug/java/com/bobassist/phase0/devrec/
├── DevRecorderActivity.kt     调试入口 Activity：MediaProjection 同意 → 启动 service
├── DevRecorderService.kt      FGS(mediaProjection)：投影 + VD + ImageReader + 采样 + 面板编排（单录制线程）
├── ScreenShotter.kt           持有最新 Image（listener 每帧 acquire+close），MARK 时才转 PNG
├── ConnectionSampler.kt       500ms 采样 connectionsJson + 前台 + 旋转
├── MarkerPanel.kt             可点、可拖的悬浮窗（[MARK] / [STOP]）— UI 在主线程
└── SessionDir.kt              纯逻辑：会话目录布局 + 文件名 + meta 推导 + 唯一 ts（JUnit 可测）

app/src/main/java/com/bobassist/phase0/herotier/ImagePlaneBitmap.kt  共享像素 helper（**src/main**：grabber 与 ScreenShotter 都引用）
app/src/debug/AndroidManifest.xml   新增 <activity DevRecorderActivity> + <service DevRecorderService>
app/src/debug/.../TestReceiver.kt   扩 devrec_mark / devrec_stop（adb 兜底，转发给运行中的 service）
scripts/dev-record.sh               host：start（拉起同意）/ mark（兜底）/ stop / pull / analyze
scripts/analyze-recording.py        数字名帧过滤修正 + mark↔SHOT 对齐节
```

> **源集纪律（codex round-2 #1）：** `ImagePlaneBitmap` 被 `src/main` 的 `MediaProjectionGrabber` 引用，**必须放 `src/main`**（main 不能依赖 debug；debug 可依赖 main）；其余 DevRecorder 类全在 `src/debug`，引用这个 main 的 helper 没问题。

**为什么核心放 `src/debug`：** 纯开发工具，绝不进 release。`src/debug` 有独立 manifest，可声明 debug-only 的 activity/service，且天然被 release 排除——比"放 main 用 `BuildConfig.DEBUG` 守"更干净。

---

## 5. 组件与接口

### 5.0 线程与所有权模型（贯穿全 §5，codex round-1 #4/#5 + round-2 #3/#4）
**两条线程，职责清晰：**
- **录制 HandlerThread（`"devrec"`）拥有并串行化**所有捕获/数据：projection 取得后的 VD/ImageReader 创建、`OnImageAvailableListener` 回调（每帧 acquire+close、持有最新 Image，§5.6）、MARK 时的 PNG 转换、连接采样、旋转 resize、`onStop`、STOP、reader/VD close、所有文件写。`OnImageAvailableListener` 用**绑定该线程的 Handler** 注册，故回调天然在该线程、与 close 不竞争。
- **主线程拥有 `MarkerPanel` 的 WindowManager UI**（codex round-2 #4：WindowManager add/update/remove + View 只能主线程）：show/hide/拖动都在主线程。按钮 `onClick`（主线程）只做两件事：**当场 `clickTs=now` 打时间戳** + `post` 到录制线程 `onMark(seq, clickTs)`。
  - 这样**即使录制线程正忙（MARK 时转 PNG），mark 的时间戳仍是点击时刻、永不被 backlog 污染**（round-2 #3）。
- `MediaProjection.Callback`、`onConfigurationChanged` 一律 **post 到录制线程**。
- `stop()` **幂等**：标志位 + `removeCallbacksAndMessages(null)`，重复调用无副作用。
- 文件名用 **单调唯一 ts 生成器**（同毫秒则 +1），写 **temp 文件再 rename**，`events.jsonl` 由录制线程**单写者**追加——杜绝撞名/半写。

### 5.1 DevRecorderActivity（同意 + 启动）
- 极简 Activity（一个 "Start Recording" 按钮）。
- **强制整屏**（codex round-1 #3）：API 34+ 用 `mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`，避免用户被引导去选"单个应用窗口"导致只录 app 自己。
- `onActivityResult`：`resultCode/data` → `startForegroundService(DevRecorderService, ACTION_START, resultCode, data)`。
- 可由 `scripts/dev-record.sh start`（`am start` 这个 exported activity）拉起；用户点按钮 + 授权一次。

### 5.2 DevRecorderService（FGS mediaProjection）
启动顺序**严格复刻 BobVpnService 已验证的 Android 14 流程**（全程在录制线程，全程 runCatching+breadcrumb，绝不崩）：
1. `startForeground(type=mediaProjection)` **先**。
2. `getMediaProjection(resultCode, data)`；`registerCallback(callback, recorderHandler)`（API34+ 必须，先于 createVirtualDisplay）。
3. `createVirtualDisplay(显示尺寸, AUTO_MIRROR, reader.surface)`（**仅此一次**）。
4. 启动 `ConnectionSampler`、显示 `MarkerPanel`、记 `meta.json`（开始）。
- **旋转/内容尺寸变化（两路都处理）：**
  - `MediaProjection.Callback.onCapturedContentResize(w,h)`（API34+，整屏内容尺寸变化的权威信号）；
  - 以及 `onConfigurationChanged`（兜底）。
  两路都走同一 `resizeTo(w,h)`：若尺寸变了，**复用同一 VirtualDisplay**——`vd.resize(w,h,dpi)` + 新 ImageReader（重挂 listener）+ `vd.surface=newReader.surface` + 关旧 reader（**不**二次 `createVirtualDisplay`）。
- **`MediaProjection.Callback.onStop`：** 用户在系统投影通知里停 → `stop()`。
- **`ACTION_STOP`**（面板 STOP / 脚本 / onStop）→ 停采样、收面板、释放 VD/reader、`projection.stop()`、写 `meta.json`（结束）、`stopForeground`+`stopSelf`。
- 持有 `@Volatile companion var live: DevRecorderService?`，供 `TestReceiver` 兜底转发 mark/stop（§5.8）。

### 5.3 ConnectionSampler
- 跑在录制线程的周期 runnable，每 `INTERVAL_MS=500`：
  - `runCatching { ConnectionCoreProvider.get().connectionsJson() }`（**用 provider 而非裸 `MihomoCore`**，可被 debug override；core 未起/异常时记一条 `events.jsonl: {"type":"sample_error"}`，不写空帧）。
  - 成功且为 JSON 数组 → 写数字名 `<ts>.json`。
  - 追加 `events.jsonl`：`{"t":<ts>,"type":"sample","fg":"<前台包名>","rot":<0|90|180|270>}`（旋转**映射成角度**，不直接存 `Surface.ROTATION_*` 常量，codex round-1 NTH #2）。
- **前提：** 连接数据需要 mihomo core 在跑（先 Start VPN）。截图不依赖 core，采样缺连接数据也继续。

### 5.4 MarkerPanel（悬浮面板）
- `TYPE_APPLICATION_OVERLAY` 窗口，**可点击**：`FLAG_NOT_FOCUSABLE`，**不**加 `FLAG_NOT_TOUCHABLE`（按钮要接触摸；与 tier badge 的 touch-through 相反）。
- 两个按钮：**[MARK]**（大、显眼、自动编号）、**[STOP]**；可拖（`ACTION_MOVE` 更新 `LayoutParams.x/y`）。需要 `SYSTEM_ALERT_WINDOW`（app 已用于 kill 按钮，已授权）。
- **UI 在主线程**（codex round-2 #4）：面板 add/update/拖动都走主线程。
- **面板会被合成进截图**（codex round-1 #10）：本设计**显式接受**，不做"截图前藏面板等一帧"——静态画面不产新帧、等帧不可靠。缓解：默认把面板停在**屏幕顶边中部**（选人界面那里没有英雄名字条），需要时用户可临时拖开。
- **MARK 点击**（主线程）：当场 `clickTs=now` + `seq++`，`post` 给录制线程 `onMark(seq, clickTs)`（§5.5）。**STOP → ACTION_STOP**。

### 5.5 onMark(seq, clickTs)（录制线程）
`clickTs` 是主线程点击时刻（§5.0/§5.4），即这条 mark 的语义时间。文件名用 `ts = 唯一化(clickTs)`（同毫秒撞名则 +1，仅保证文件名唯一；events 里仍记原始 `clickTs`）。全部 temp-rename、单写者：
1. `MARK-<ts>-<seq>.txt`（`<ts>: <seq>`，复用 analyze 的 mark 解析，label=序号）。
2. `SHOT-<ts>-<seq>.png`：调 `ScreenShotter.snapshot()` ——**此刻**把持有的最新 Image 转 PNG（§5.6）。无 Image（首帧前）→ 记 `events.jsonl: {"type":"mark_noshot","seq":...}`，mark 仍写（不静默丢）。
3. `<ts>.json`：立刻补一帧密集连接快照（mark 时刻状态，免得落在两次采样之间）。
4. 追加 `events.jsonl`：`{"t":<clickTs>,"type":"mark","seq":<seq>,"shot":"SHOT-...png","shot_w":W,"shot_h":H,"rot":R,"shot_age_ms":<clickTs - 该 Image 的 acquire epoch ms>}`（per-shot 尺寸/旋转/新鲜度；新鲜度用 acquire 时记的 epoch ms，不用 `Image.getTimestamp()` 的单调 ns，codex round-1 #9 / round-2 #2）。
- `Log.i` + breadcrumb，脚本可 grep 确认。

### 5.6 ScreenShotter（持有最新 Image，MARK 时才转 PNG；codex round-1 #2 + round-2 #2/#3）
**关键设计：持有 Image，而非每帧转 Bitmap。** 去掉了 v2 的节流（Codex 指其为"唯一不值的复杂度"），也避免每帧全屏 Bitmap 分配压垮录制线程。
- DevRecorder 的 reader 用 **`maxImages=3`**（codex round-3：持有 1 张 + 保留 `acquireLatestImage` 丢弃旧帧所需的 2-image margin）。
- 在录制线程注册 `ImageReader.OnImageAvailableListener`，**先 acquire、非空才替换**（避免队列已被排空时把上一张好 `held` 丢掉）：
  ```
  val next = reader.acquireLatestImage()
  if (next != null) { val old = held; held = next; heldAcquiredEpochMs = now; old?.close() }
  ```
  - **不在此处转 Bitmap**——只 acquire/close，开销极小，**稳态持续排空 reader**。`held` 永远是**最近产生的一帧**。
  - AUTO_MIRROR 只在画面变化时产帧，故静态选人界面的 `held` 就是当前画面（不会 stale）；动画时也只是多 acquire+close 几次，无 Bitmap 开销。
  - `held` 仅在录制线程读写，无锁。
- `snapshot()`（MARK 时，录制线程调）：把 `held` 经 **`ImagePlaneBitmap`** 转 `Bitmap` → `compress(PNG)` 到 temp 再 rename → recycle bitmap；返回 `{ok, w, h, heldAcquiredEpochMs}`；`held==null`→`ok=false`。**转换只在偶发的 MARK 发生，不在每帧。**
- teardown/resize：先 `setOnImageAvailableListener(null)` + `held?.close()`，再 close/换 reader（避免 acquire 与 close 竞争）。
- `ImagePlaneBitmap`（src/main 共享 helper）：plane buffer + rowStride 去 padding → `Bitmap`（把现 `MediaProjectionGrabber.capture()` 里的像素逻辑抽出，两边共用、行为不变）。

### 5.7 SessionDir（纯逻辑，JUnit 可测）
- 会话根 `files/devrec/`。**start 时不删旧**（codex round-1 #12）：若已存在，整体 rename 为 `files/devrec-prev/`（保留一份上次；再上次丢弃）。扁平布局：`<ts>.json`（纯数字名）/ `MARK-*` / `SHOT-*.png` / `events.jsonl` / `meta.json`。
- `meta.json`：`{schemaVersion, app_version, captured_at, device_model, started_at_ms, stopped_at_ms, mark_count, note}`。
- **analyzer 兼容修正**：`analyze-recording.py` 改为只把 **stem 为纯数字**的 `.json` 当帧（跳过 `meta.json`）；`MARK-*.txt`/`SHOT-*.png`/`events.jsonl` 各自处理。

### 5.8 host 端 + adb 兜底
- `scripts/dev-record.sh`：
  - `start [--rebuild]`：（可选 build/install）→ `am start <DevRecorderActivity>`，提示用户点 Start + 授权。
  - `mark [seq]` / `stop`：**兜底通道**——`am broadcast com.bobassist.phase0.TEST --es cmd devrec_mark|devrec_stop`；`TestReceiver` 转发给 `DevRecorderService.live`（service **不导出**，codex round-1 #6/#7）。正常用屏幕按钮；悬浮窗授不上时用这个。
  - `pull`：`run-as ... tar files/devrec` → `/tmp/devrec/`（含 PNG）。
  - `analyze`：跑 `analyze-recording.py /tmp/devrec`，并列 `meta.json` + 每个 mark 的 SHOT 路径。
- `analyze-recording.py` 扩 `=== marks with screenshots ===`：每 mark 打印 `+<rel>s seq=<n> shot=<path> shot_age_ms=<a> nearest_frame=<ts>`。

---

## 6. 错误处理

| 场景 | 处理 |
|---|---|
| 用户拒绝投影（resultCode≠OK） | Activity 提示，不启动 service。 |
| Android 14 顺序/类型 | startForeground(mediaProjection) 先于 getMediaProjection；registerCallback 先于 createVirtualDisplay；全程 runCatching+breadcrumb。 |
| 用户误选"单应用窗口" | `createConfigForDefaultDisplay()` 强制整屏，规避。 |
| 旋转 / 内容尺寸变化 | `onCapturedContentResize` + `onConfigurationChanged` 两路 → `vd.resize()`+换 reader，不二次 createVirtualDisplay。 |
| MARK 时 holder 空（首帧前） | mark 照写，记 `mark_noshot`，不静默。 |
| reader/VD 竞争 | §5.0 单录制线程串行化；close 前先停 listener；stop 幂等。 |
| 毫秒撞名 / 半写 | 唯一 ts + temp-rename + 单写者。 |
| SYSTEM_ALERT_WINDOW 未授权 | 面板加不上 → breadcrumb 提示；`dev-record.sh mark/stop` adb 兜底仍可用。 |
| core 未运行 | 连接采样记 `sample_error`，截图/标记不受影响；文档提示先开 VPN。 |
| start 覆盖未拉取的会话 | 旧会话 rename 到 `devrec-prev`，不直接删。 |

---

## 7. 附带：tier 实时验证用的 OCR 日志（小改动，main 内 debug-gated）

与 DevRecorder 并行的一处**独立小改**（不属 recorder）：`HeroTierCoordinator.captureOnce` 每帧记一条 breadcrumb——识别到的 OCR 行文本 + 匹配到的 cardId/tier 数量（`BuildConfig.DEBUG` 守）。tier 实时验证时录一局即可看到"实时 OCR 读到什么、匹配几个"，不靠截图。

---

## 8. 测试策略

- **纯逻辑（JUnit，无 Robolectric）：** `SessionDir`（目录布局、数字名/MARK/SHOT 文件名格式、meta 推导、唯一 ts 单调性 + 同毫秒 +1）、`events.jsonl` 行格式、`analyze-recording.py` 跳过 `meta.json` 只认数字名帧。
- **设备侧（手动 + 脚本断言）：** 投影启动顺序、整屏强制、旋转 resize、MARK 三件物落盘、面板可点可拖、PNG 可解码且非全黑、adb 兜底转发。`dev-record.sh` 自检：start→mark×N→stop→pull，断言 `MARK` 数 == 点击数，且 `SHOT 数 + mark_noshot 数 == MARK 数`（§10 与 §5.5 对齐，codex round-1 #8）。
- **截图正确性：** pull 后人工/Codex 看一张 PNG 确认是真实游戏画面、尺寸=屏幕分辨率（含顶边面板）。

---

## 9. Goals / Non-Goals

### Goals
1. debug-only in-app 会话录制器：屏幕上单个 MARK 按钮，游戏中可点、自动编号。
2. 每个 mark 落 `MARK` + `SHOT.png`（最新帧 holder，非 stale）+ 密集连接快照；全程 500ms 连接 + 前台 + 旋转采样。
3. 录制格式兼容 `analyze-recording.py`（数字名帧）与回归 harness L1 fixture 约定。
4. host 端一条命令 pull + analyze，mark↔截图↔连接帧对齐。
5. 通用：任何"某时刻屏幕 + 网络状态"的开发都能用同一工具；屏幕按钮 + adb 双通道。

### Non-Goals
- ❌ 不与 tier 共享投影（单投影约束 → 录制时 tier 不同开；共享 ScreenSource 留 phase 2）。
- ❌ 不做连续视频/逐帧截图（只在 mark 截）。
- ❌ 不进 release variant。
- ❌ 不改 tier/kill/bobcore 产品行为（§7 仅加一行 debug 日志；`MediaProjectionGrabber` 仅抽 helper、行为不变）。
- ❌ 不做多会话管理/云上传（单会话 + 一份 `devrec-prev` 备份，手动 pull）。

---

## 10. 成功判据

- [ ] debug build：开 DevRecorderActivity → 授权（整屏，无"选应用窗口"）→ 悬浮 [MARK]/[STOP] 出现在炉石之上、可点可拖。
- [ ] 进炉石走一局，点 N 次 MARK → STOP；`dev-record.sh pull` 后：`MARK-*` 数 == 点击数；`SHOT-*.png` 数 + `mark_noshot` 数 == MARK 数；每张 PNG 可解码、非全黑、尺寸=屏幕分辨率。
- [ ] `<ts>.json` 帧贯穿全程（含每个 mark 时刻密集帧）；`events.jsonl` 有 sample/mark 行。
- [ ] `analyze-recording.py` 在该录制上：连接生命周期 + 指纹回放 + **新的 mark↔SHOT 对齐节**，**不被 `meta.json` 崩**。
- [ ] 竖→横进炉石不杀录制：breadcrumb 见 `devrec: resized`，无 `createVirtualDisplay` 二次异常。
- [ ] 旧 `TestReceiver` 录制路径不受影响；`MediaProjectionGrabber` 抽 helper 后 tier 截屏行为不变（现有 tier 测试仍绿）。

---

## 11. 风险

| 风险 | 缓解 |
|---|---|
| 单投影约束被忘记、录制时又开 tier | §3 写死边界 + 启动检测 tier 投影则警告。 |
| Android 14 投影顺序/旋转/选窗坑 | §5 逐条复刻已验证流程 + `createConfigForDefaultDisplay` + `onCapturedContentResize`。 |
| 最新帧 stale / reader stall | §5.6 listener 持续 acquire+close 排空、held=最近帧；MARK 时才转 PNG。**稳态不 stall**；仅 MARK 编码瞬间可能对采集瞬时背压，`shot_age_ms` 暴露新鲜度。 |
| reader/VD 生命周期竞争 | §5.0 单录制线程串行化 + 幂等 stop。 |
| 悬浮按钮入图遮挡名字 | 默认停顶边无名字区；可拖；adb 兜底不依赖面板。 |
| 截图 buffer→Bitmap 与 grabber 漂移 | §5.6 抽 `ImagePlaneBitmap` 共享，单一实现。 |
| 撞名/半写/进程死 | 唯一 ts + temp-rename + 单写者。 |
| start 覆盖丢数据 | §5.7 旧会话 rename 到 `devrec-prev`。 |
| PNG 体积（2412×1080） | 只在 mark 截、单会话；十几张可接受。 |
