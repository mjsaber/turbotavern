# Phase 1.3 — Bob Android 测试基础设施 设计文档

**Date:** 2026-05-27
**Status:** v2 (post codex review round 1 + user decisions)
**Platform:** Android 10+ (API 29+), arm64-v8a primary
**Tech stack additions:** Robolectric 4.13, Android Emulator (system image of choice)
**Scope:** test infrastructure only — no user-visible behavior change

---

## Codex Review (round 1, 2026-05-27)

| # | Sev | Finding | Disposition (where applied in spec) |
|---|---|---|---|
| 1 | **P1** | MihomoCoreFacade boundary inconsistent (BobVpnService/MainActivity still touch MihomoCore object directly). | §4.2 split into **LifecycleCoreFacade + ConnectionCoreFacade**. BobVpnService composes both; OverlaySession only needs ConnectionCoreFacade through controller lambdas. (user decision) |
| 2 | **P1** | OverlaySession needs an `OverlayUi` interface, not concrete OverlayWindow (Kotlin final class). | §4.1.1 adds `OverlayUi` interface with show/hide/setVisible/applyState/onConfigurationChanged. OverlayWindow implements it. |
| 3 | **P1** | Liveness guard contract must be an explicit OverlaySession invariant + tested. | §4.1 lists "preserves liveness guard" as a contract; §4.7 mandates `OverlaySessionTeardownRaceTest`. |
| 4 | **P1** | Debug `liveController/livePoller/liveTapTrigger` migration is under-specified. | §4.8 (new): collapse to `BobVpnService.liveSession: OverlaySession?`. TestReceiver migrates to `liveSession?.handleTap()/...`. (user decision) |
| 5 | **P1** | Trace schema needs correlation IDs. | §4.4 trace schema rewritten to include `session_id`, `tap_id`/`kill_cycle_id`, `t_ns`, `thread`, `state`, `candidate_count`, `conn_id`, `result`, `dt_ms`. |
| 6 | **P1** | Continuous poll tracing exceeds the "15 logs per kill" assumption. | §4.4 trace gated by **sample window** — every tap fires a ±2s trace window; poll ticks outside the window don't log trace. (user decision) |
| 7 | **P1** | Sim scenarios miss the most likely 5-6s cause (tap and poll share pollHandler — slow snapshot can starve tap). | §4.6 adds 4 new scenarios: `slow_snapshot`, `tap_while_snapshot_inflight`, `tap_at_poll_offsets`, `preexisting_candidate_on_start`. |
| 8 | **P1** | Robolectric clock mixing (System.nanoTime vs SystemClock virtual time). | §4.3 promoted from optional to mandatory: **`Clock` interface injected throughout**, prod uses `SystemClock.elapsedRealtimeNanos`, tests use a `VirtualClock` that mirrors Robolectric's looper time. (user decision) |
| 9 | P2 | HandlerThread Robolectric subtleties — must drain `shadowOf(pollLooper)` and quit cleanly. | §6 expanded. |
| 10 | P2 | Native load risk: MainActivity.version() / BobVpnService.bringUp() still hit native unless wrapped. | §4.2 LifecycleCoreFacade covers the bringUp path; §6 documents that Tier 1 tests never load BobVpnService directly. |
| 11 | P2 | fakeSnapshot location should be on core adapter, not BobVpnService companion. Use AtomicReference<Map>, not volatile mutable map. | §4.5 rewritten: `DebugConnectionCoreOverride` lives next to `RealConnectionCore`. |
| 12 | P2 | Stage ordering should change (Robolectric → OverlayUi → OverlaySession → core facades + Clock + DI → debug injection → trace → tests → sim). | §5 stage table rewritten. |
| 13 | P2 | DoD criterion 4 circular. | §9 rewritten: "trace/sim produces phase-table capable of classifying delay". |
| 14 | P2 | Tier 2 emulator should be mandatory in DoD if it's in the deliverable. | §9 criterion 2 now requires emulator (physical device remains Tier 3). |
| 15 | P3 | Kotlin sealed-class concerns not real. | Accepted (no spec change). |

---

## 1. Why

Phase 1.1 / 1.2 都用"装到 OnePlus 10T 上、打一局 BG、看 log"作为唯一验证手段。这种 dev loop 有几个具体问题：

1. **慢**：一次 iteration ~5 分钟（build install + 启 VPN + 启 HS + 进 BG + 等动画 + 看 log）
2. **不可重复**：BG 战斗时长、对手、socket 出现时机都不可控
3. **依赖物理设备**：USB 断了就停摆；同时调试其他事要拔掉
4. **盲区**：当前观察到的"拔线 5-6 秒延迟"，要测出来源就得多打十几局 BG，太奢侈

目标：把绝大多数 phase 1.x 改动的验证从"打 BG"挪到"./gradlew test"（秒级）或"emulator 跑 sim 脚本"（30 秒级），实体机只留作最终签收。

---

## 2. Non-Goals (本期明确不做)

- ❌ 不上 CI（持续集成）—— 但设计上要让以后接 CI 不需要返工
- ❌ 不写 UI 测试（Espresso / UiAutomator）—— 浮窗是 WindowManager 直接画的，跟 Espresso 模型不匹配
- ❌ 不写 mihomo Go 层的单测 —— 那是 mihomo 上游的事
- ❌ 不实现真的 connection-table 录播回放（Spike D 的 record_start/stop 已经够用，不做录播框架）
- ❌ 不抽象出一个通用"测试 DSL"——直接写代码就行，YAGNI
- ❌ 不重写已有 31 个纯 JVM 单测（已经够好）

---

## 3. 测试金字塔

```
                           ┌──────────────────────────────┐
                   Tier 3  │  实体机 OnePlus 10T            │  ~5 min/iter
                           │  最终 acceptance + 真 HS 集成   │
                           └──────────────────────────────┘
                       ┌──────────────────────────────────┐
                Tier 2 │  Android Emulator AVD            │  ~30s/iter
                       │  全 Android API + libgojni.so    │
                       │  + 注入式 snapshot, 无真 HS       │
                       └──────────────────────────────────┘
                ┌──────────────────────────────────────────┐
        Tier 1  │  Robolectric 集成测试                     │  秒级/iter
                │  shadow Handler/Looper + Fake MihomoCore │
                │  覆盖：状态机时序 + Handler 调度顺序        │
                └──────────────────────────────────────────┘
                ┌──────────────────────────────────────────┐
        Tier 0  │  纯 JVM 单测 (已有 31 个)                  │  毫秒级
                │  pure-logic, 无任何 Android 依赖           │
                └──────────────────────────────────────────┘
```

**判别原则**：每一层只测在其下面那层测不出的事。

| 层 | 测的事 | 不测的事 |
|---|---|---|
| Tier 0 | 状态机转换、数据解析、纯算法 | 任何 Handler / Service / WindowManager 行为 |
| Tier 1 | Handler 调度顺序、`postDelayed` 计时、协调层 (OverlaySession)、多组件交互 | mihomo 真实耗时、真实 WindowManager 渲染、真 VpnService 生命周期 |
| Tier 2 | 真 libgojni.so 调用耗时、真 WindowManager touch event 延迟、真 VpnService.Builder 行为 | HS 实际重连、真用户体感 |
| Tier 3 | HS 完整重连流程、跨硬件设备验证、真实电池/网络条件 | (无；这是最高保真度) |

---

## 4. 架构变更

### 4.1 抽出 `OverlaySession` 协调层（依赖 `OverlayUi` interface）

**问题**：现在 `BobVpnService` 一个类里塞了 VpnService 生命周期 + mihomo wire-up + OverlayPoller + ForegroundDetector + tap handling。前 3 项依赖 Android Service framework；后 2 项是纯协调逻辑但被困在 Service 里无法独立测。

**方案**：把所有"poller + detector + overlay + controller + handler"的协调逻辑提取到一个新类 `OverlaySession`：

```kotlin
class OverlaySession(
    private val controller: BattleConnectionController,
    private val poller: OverlayPoller,
    private val detector: ForegroundDetector,
    private val overlay: OverlayUi,                // ← interface, not concrete OverlayWindow
    private val pollHandler: Handler,
    private val mainHandler: Handler,
    private val clock: Clock,                       // ← injected (§4.3)
    private val trace: TraceSink,                   // ← injected (§4.4)
    private val breadcrumb: (String) -> Unit = { },
) {
    @Volatile private var started: Boolean = false  // liveness invariant
    
    fun start() { ... }
    fun stop() { /* sets started=false BEFORE clearing fields; guards posted runnables */ }
    fun handleTap() { ... }
    fun handleForegroundChange(isForeground: Boolean) { ... }
    fun handleConfigurationChanged() { ... }
}
```

`BobVpnService` 退化成"提供 mihomo / VpnService / Context，组装一个 OverlaySession"：

```kotlin
class BobVpnService : VpnService() {
    private var session: OverlaySession? = null
    
    override fun onStartCommand(...) {
        // existing VpnService + mihomo setup unchanged
        session = OverlaySession(
            controller = ..., poller = ..., detector = ...,
            overlay = OverlayWindow(this, ...),
            pollHandler = ..., mainHandler = ...,
            clock = AndroidElapsedRealtimeClock,
            trace = TraceSink(BuildConfig.DEBUG),
        )
        session?.start()
    }
}
```

**OverlaySession 不变量（liveness guard contract, codex P1 #3）**：
1. `start()` 完成后 `started == true`；`stop()` 第一步 `started = false`。
2. 任何 `handleX` 方法在 post-handler 跑时必须 `if (!started) return@post`——保证 stop 后未排空的 runnable 是 no-op。
3. `overlay.setVisible(...)` 调用前必须额外检查 `overlay === capturedReference`（identity 比对）——防止 stop 期间 overlay 字段被换。
4. **§4.7 必须包含 `OverlaySessionTeardownRaceTest`**：分别测 stop 之后还有未排空的 tap、foreground-change、configuration-change runnable 进队，断言它们全部 no-op。

**好处**：
- `OverlaySession` 不依赖任何 Android Service / WindowManager 接口
- 可以在 Robolectric 中实例化测试
- VpnService 部分单独保留，只测它跟 mihomo 的接口
- 把 Phase 1.1+1.2 沉淀的所有协调逻辑变得可测

**风险**：
- 这是一次行为不变的重构。缓解：先写 6-8 个 Tier 1 测试覆盖目前的协调行为，确认绿；然后做提取；之后跑同一批测试再次确认绿。

### 4.1.1 `OverlayUi` interface（codex P1 #2）

Kotlin class 默认 final，`OverlayWindow` 在 OverlaySession 构造里直接当具象类型会让假实现做不出来。抽接口：

```kotlin
interface OverlayUi {
    fun show()
    fun hide()
    fun setVisible(visible: Boolean)
    fun applyState(state: OverlayState)
    fun onConfigurationChanged()
}

// OverlayWindow 改为 implements OverlayUi
class OverlayWindow(...) : OverlayUi { ... existing impl ... }

// 测试用：
class FakeOverlayUi : OverlayUi {
    var visible: Boolean = false
    var lastState: OverlayState = OverlayState.WaitingForBattle
    val log: MutableList<String> = mutableListOf()
    override fun show() { visible = true; log += "show" }
    override fun hide() { visible = false; log += "hide" }
    override fun setVisible(v: Boolean) { visible = v; log += "setVisible($v)" }
    override fun applyState(s: OverlayState) { lastState = s; log += "applyState($s)" }
    override fun onConfigurationChanged() { log += "onConfigurationChanged" }
}
```

### 4.2 拆分 `LifecycleCoreFacade` + `ConnectionCoreFacade`（codex P1 #1）

**问题**：`MihomoCore` 是 `object`（单例），背后是 libgojni.so，不能在 JVM 单测里加载。一个接口塞所有方法会让边界混乱：BobVpnService 需要 lifecycle 那套，OverlaySession 只需要 connection 那套。

**方案**：按职责拆两个接口。BobVpnService 同时依赖两个；OverlaySession（通过 `BattleConnectionController` 的 lambda）只接触 connection。

```kotlin
/** 生命周期相关：仅在 BobVpnService.bringUp/tearDown 调一次 */
interface LifecycleCoreFacade {
    fun version(): String
    fun setProtector(service: VpnService)
    fun setup(homeDir: String): Result<Unit>
    fun startTun(fd: Int, stack: String, gateway: String, dns: String): Result<Unit>
    fun stopTun(): Result<Unit>
}

/** 运行时查询/操作：被 OverlaySession 通过 controller 间接使用 */
interface ConnectionCoreFacade {
    fun connectionsJson(): String
    fun closeConnection(id: String): MihomoCore.CloseResult
}
```

生产实现（Real*）薄薄包一下当前的 `MihomoCore object`，**`object RealConnectionCore` 跟 `RealLifecycleCore` 内部直接调 `MihomoCore.xxx()`**——native 加载发生在生产环境运行时，测试环境拿不到。

```kotlin
object RealLifecycleCore : LifecycleCoreFacade {
    override fun version() = MihomoCore.version()
    override fun setProtector(s: VpnService) = MihomoCore.setProtector(s)
    override fun setup(homeDir: String) = MihomoCore.setup(homeDir)
    override fun startTun(fd: Int, stack: String, gateway: String, dns: String) =
        MihomoCore.startTun(fd, stack, gateway, dns)
    override fun stopTun() = MihomoCore.stopTun()
}

object RealConnectionCore : ConnectionCoreFacade {
    override fun connectionsJson() = MihomoCore.connectionsJson()
    override fun closeConnection(id: String) = MihomoCore.closeConnection(id)
}
```

测试用的 fake 见 §4.5（注入式 connection-table 覆盖）。

**好处**：
- 拆分后 OverlaySession 跟 lifecycle 完全解耦
- BobVpnService 同时持有两个 facade，但跟测试无关
- 加新函数时职责清晰
- `CloseResult` 保留在 `MihomoCore.CloseResult.*` —— 是 sealed class，跟 native 无关，可以在 JVM 里自由实例化

**`MihomoCore` object 自身保留不动** —— 真正的 native 入口仍是它，Real* facade 只是薄包装。

### 4.3 `Clock` 抽象（mandatory, codex P1 #8）

**问题**：Robolectric paused mode 默认推进的是 `SystemClock.elapsedRealtimeNanos()`，跟 `System.nanoTime()` 不是同一个时钟。如果代码里混用，测试里时间断言会乱。

**方案**：引入 `Clock` 接口，所有时间读取走注入。

```kotlin
interface Clock {
    fun nowNanos(): Long
    fun nowMillis(): Long = nowNanos() / 1_000_000L
}

/** 生产：底层用 SystemClock.elapsedRealtimeNanos（单调；Robolectric 也 shadow 它） */
object AndroidElapsedRealtimeClock : Clock {
    override fun nowNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}

/** 测试：Robolectric 测试中 SystemClock 由 ShadowLooper 推进，所以测试用同一个时钟就够了 */
// 无需单独 FakeClock —— 直接用 AndroidElapsedRealtimeClock + ShadowLooper.idleFor 即可。
// 纯 JVM 单测如果不依赖 Robolectric，会用一个简单的 ManualClock:
class ManualClock(initial: Long = 0L) : Clock {
    @Volatile var current: Long = initial
    fun advance(deltaNanos: Long) { current += deltaNanos }
    override fun nowNanos(): Long = current
}
```

**注入点**：
- `OverlaySession`：构造参数 `clock: Clock`
- `OverlayPoller`：构造参数 `clock: Clock`（替代任何 `System.nanoTime()` 用于内部时间戳）
- `ForegroundDetector`：当前不读时间，无需注入
- `Trace`：（§4.4）`now_ns` 字段由 clock 提供
- `BattleConnectionController`：已经是无状态，无 clock 需求

**风险**：
- 每个 Phase 1.1/1.2 测试都需要在构造时多传一个 Clock。缓解：构造里默认值 `clock: Clock = AndroidElapsedRealtimeClock` 让现有调用不变。
- ManualClock 不被 ShadowLooper 推进——如果在 Robolectric 测试里也想推进 ManualClock，得在测试里 `clock.advance(...)` + `shadowOf(looper).idleFor(...)` 一起调（postDelayed 自动用的是 Looper 时钟，clock 自己是另一回事）。Robolectric 测试**不应该用 ManualClock**——直接用 `AndroidElapsedRealtimeClock` 即可，ShadowLooper 推进就够了。

### 4.4 时间戳 instrumentation + sample 窗口（codex P1 #5 + #6）

**目标**：每个关键路径打可解析的 trace log，调试时不需要重新编译就能看到瓶颈在哪。但 poll tick 每 800ms 一次，常开 trace 会刷屏——只在 **±2s tap 窗口** 内 emit。

**Trace schema**（codex P1 #5）：

```
trace session=<sid> cycle=<cid> phase=<name> event=<entry|exit> t_ns=<mono> thread=<name> state=<...> candidate_count=<n> conn_id=<id> result=<...> dt_ms=<since_entry>
```

字段含义：
- `session` (sid)：单调递增 int，service.start() 时分配。所有 trace 隶属于某一个 session（重启后归零）。
- `cycle` (cid)：单调递增 int，每次 `handleTap()` 入口分配一个新 cycle id；poll tick 复用最近一次 cycle id（如果在 2s 窗口内）。让 tap → close → cooldown 整条链能 grep 出来。
- `phase`：枚举：`tap`, `tap_post`, `state_check`, `kill`, `snapshot`, `pick`, `close`, `cooldown_enter`, `cooldown_exit`, `poll_tick`, `fg_change`, `setVisible`。
- `event`：`entry` / `exit`。
- `t_ns`：来自注入的 `Clock.nowNanos()`，单调。
- `thread`：`main` / `pollHandler` / etc.
- `state`：当时的 OverlayState 字符串。
- `candidate_count`：仅 `phase=pick exit` 时填。
- `conn_id`：仅 `phase=close` 时填。
- `result`：仅 exit 事件填，记录 phase 结果。
- `dt_ms`：仅 exit 事件填，等于 `(exit.t_ns - entry.t_ns) / 1e6`。

**Sample 窗口逻辑**（codex P1 #6）：

```kotlin
class TraceSink(
    private val enabled: Boolean,         // BuildConfig.DEBUG
    private val clock: Clock,
    private val windowMs: Long = 2_000L,
) {
    @Volatile private var openUntilNs: Long = 0
    @Volatile private var currentCycleId: Long = 0
    private val cycleCounter = AtomicLong(0)
    
    /** 由 handleTap 触发：开新 cycle id + 把窗口推到 now+windowMs */
    fun beginCycle(): Long {
        if (!enabled) return 0
        val cid = cycleCounter.incrementAndGet()
        currentCycleId = cid
        openUntilNs = clock.nowNanos() + windowMs * 1_000_000L
        return cid
    }
    
    /** 由所有 phase 调用：只在窗口期内打 log */
    fun emit(phase: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!enabled || clock.nowNanos() > openUntilNs) return
        // log.i(TAG, formatted_string)
    }
}
```

**具体 emit 位置**（每个都 entry + exit）：

| 位置 | phase | 测什么 |
|---|---|---|
| `OverlaySession.handleTap` 入口 | `tap` | 用户点击到处理开始 |
| `pollHandler.post { lambda }` 入口（lambda 第一行） | `tap_post` | Handler 队列等待时间 |
| `poller.currentState()` 读取后 | `state_check` | 状态机分支 |
| `controller.killBattleSocket()` | `kill` | 业务调用总耗时 |
| `ConnectionCoreFacade.connectionsJson()` | `snapshot` | mihomo 单次 snapshot 耗时（**5-6s 假设关键点**） |
| `BattleConnection.pickWithCount()` | `pick` | candidate 解析耗时 |
| `ConnectionCoreFacade.closeConnection()` | `close` | mihomo close 耗时 |
| `OverlayPoller.enterCooldown()` | `cooldown_enter` / `cooldown_exit` | 状态机延迟 |
| `OverlayPoller.tick()` | `poll_tick` | poll 周期实际间隔（**只在 tap 窗口内**） |
| `handleForegroundChange` | `fg_change` | 前后台切换延迟 |
| `OverlayUi.setVisible` | `setVisible` | UI 操作主线程延迟 |

**预算**：tap 一次 = ~20 entries+exits + ±2s 内的 poll_tick（最多 5 个 tick × 2 = 10 条）= ~30 条/ tap。logcat 限速 50 条/秒/进程，没问题。

**好处**：之后任何延迟问题都能直接拿 logcat grep 时间戳算出来，再用 `cycle=<id>` 把一条链聚合成时间瀑布图。

### 4.5 Debug-only Snapshot 注入（codex P2 #11）

**目标**：通过 ADB broadcast 注入任意 connection-table JSON，绕开真 mihomo。

**方案**：在 `RealConnectionCore` 旁边放一个 debug-only override 层。release 构建里这个文件不参与编译。

```kotlin
// app/src/debug/java/com/bobassist/phase0/core/DebugConnectionCoreOverride.kt
object DebugConnectionCoreOverride : ConnectionCoreFacade {
    private val snapshotOverride = AtomicReference<String?>(null)
    private val closeOverrides = AtomicReference<Map<String, MihomoCore.CloseResult>>(emptyMap())
    private val closeDelayMs = AtomicReference<Long>(0L)
    
    fun setSnapshot(json: String?) { snapshotOverride.set(json) }
    fun setCloseResult(id: String, result: MihomoCore.CloseResult) {
        val curr = closeOverrides.get()
        closeOverrides.set(curr + (id to result))
    }
    fun setCloseDelay(ms: Long) { closeDelayMs.set(ms) }
    fun clearAll() {
        snapshotOverride.set(null)
        closeOverrides.set(emptyMap())
        closeDelayMs.set(0)
    }
    
    override fun connectionsJson(): String =
        snapshotOverride.get() ?: RealConnectionCore.connectionsJson()
    
    override fun closeConnection(id: String): MihomoCore.CloseResult {
        val delay = closeDelayMs.get()
        if (delay > 0) Thread.sleep(delay)
        return closeOverrides.get()[id] ?: RealConnectionCore.closeConnection(id)
    }
}
```

`BobVpnService` 在 debug 变体构造 OverlaySession 时使用 `DebugConnectionCoreOverride`；release 变体使用 `RealConnectionCore`。可以用 `src/debug/java/.../ConnectionCoreProvider.kt` vs `src/main/java/.../ConnectionCoreProvider.kt` 做变体选择。

**TestReceiver 新命令**：
```
sim_set_snapshot --es json '[...]'        → DebugConnectionCoreOverride.setSnapshot(json)
sim_clear_snapshot                          → DebugConnectionCoreOverride.setSnapshot(null)
sim_set_close --es id <id> --es result <r>  → DebugConnectionCoreOverride.setCloseResult(...)
sim_set_close_delay --es ms <n>             → DebugConnectionCoreOverride.setCloseDelay(...)
sim_clear_all                               → DebugConnectionCoreOverride.clearAll()
```

**关键约束**：`DebugConnectionCoreOverride` 文件只放在 `src/debug/`——release APK 完全不含。`aapt2 dump` 验证无 `DebugConnectionCoreOverride` 符号出现在 release dex 中。

### 4.6 仿真脚本 `scripts/sim-bg-kill.sh`（codex P1 #7：扩到 8 个场景）

预设 8 个场景，每个 30 秒能验完。前 4 个是我原本设计；后 4 个是 codex P1 #7 指出用来诊断 5-6s 拔线延迟的关键场景。

```bash
# 原有 4 个：
./sim-bg-kill.sh cold_start         # 0 conn → 1 conn → tap → 0 conn, 测整条延迟链
./sim-bg-kill.sh rapid_tap          # 10 个 tap 在 1 秒内连发，测 cooldown 是否吞
./sim-bg-kill.sh server_rotate      # 模拟服务端 rotate socket, 测 newest 算法
./sim-bg-kill.sh permission_revoke  # 模拟 mid-session usage-access 撤销

# 诊断 5-6s 延迟新增 4 个：
./sim-bg-kill.sh slow_snapshot          # 注入 sim_set_close_delay=1000，看 tap 是否被 snapshot 阻塞
./sim-bg-kill.sh tap_while_snapshot     # 在 connectionsJson() 调用中途 fire tap，测 pollHandler 队列等待
./sim-bg-kill.sh tap_at_poll_offsets    # tap timing 跟 poll tick 偏移 0/200/400/600/800ms 各打一次，看哪个延迟最差
./sim-bg-kill.sh preexisting_candidate  # service 启动时 snapshot 已经有 candidate, 测从启动到 Ready 的延迟（vs cold_start 的从 0→1）
```

每个场景的输出：
- 每个 phase 的耗时表（用 4.4 的 trace log 解析）
- PASS/FAIL（基于阈值，例如"tap → closeConnection 必须 < 50ms"）
- artifact 落盘到 `/tmp/sim/<scenario>/<timestamp>/`

可以在 emulator 或实体机上跑（同一份脚本）。

### 4.7 Robolectric 测试用例

新增 `app/src/test/java/com/bobassist/phase0/integration/`：

| 文件 | 测什么 |
|---|---|
| `OverlaySessionTapTest.kt` | tap 在 Waiting/Ready/Cooldown 各自的行为；tap 到 closeConnection 的实际 Handler 延迟（用 SystemClock + ShadowLooper） |
| `OverlaySessionCooldownTest.kt` | Success 后 2s cooldown 精确时长；cooldown 期间 tap/poll 被吞 |
| `OverlaySessionForegroundTest.kt` | 前后台切换时 overlay show/hide 时序；切回 HS 时 lastState 保持 |
| `OverlaySessionTeardownRaceTest.kt` | **codex P1 #3 必须**：stop() 之后排队的 tap/foreground-change/configuration-change runnable 全部 no-op；overlay 不会被重新 setVisible |

每个测试模式：

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)   // codex P2 #9: 显式声明
class OverlaySessionTapTest {
    @Test
    fun `tap on Ready state dispatches to closeConnection within 20ms`() {
        val fakeConn = FakeConnectionCore().apply { snapshotJson = ONE_CANDIDATE_JSON }
        val fakeUi = FakeOverlayUi()
        // 使用 AndroidElapsedRealtimeClock — Robolectric paused mode shadows SystemClock
        val clock = AndroidElapsedRealtimeClock
        // 单独的 pollHandler thread 在 Robolectric 里仍是真线程；测试需要 drain
        val pollThread = HandlerThread("test-poll").apply { start() }
        val pollHandler = Handler(pollThread.looper)
        val mainHandler = Handler(Looper.getMainLooper())
        
        val session = OverlaySession(controller = ..., poller = ..., detector = ...,
            overlay = fakeUi, pollHandler = pollHandler, mainHandler = mainHandler,
            clock = clock, trace = TraceSink(true, clock))
        session.start()
        
        // 触发一次 poll 让 state 转 Ready
        shadowOf(pollThread.looper).idleFor(800, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OverlayState.Ready, session.poller.currentState())
        
        val t0 = clock.nowNanos()
        session.handleTap()
        shadowOf(pollThread.looper).idleFor(100, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        
        val callT = fakeConn.closeCallLog.first().first
        assertTrue("tap to close took ${(callT - t0)/1e6} ms", (callT - t0) < 20_000_000L)  // 20ms in ns
        
        pollThread.quitSafely()
    }
}
```

要点（融入 codex P2 #9 反馈）：
- `@LooperMode(LooperMode.Mode.PAUSED)` 显式声明（4.13 默认就是 PAUSED，但显式让测试不受 default 变化影响）
- 后台 looper 要分别 `shadowOf(thread.looper).idleFor(...)` 推进
- 用 `AndroidElapsedRealtimeClock`（Robolectric shadow 后会跟着 ShadowLooper 推进），**不**用 `ManualClock`
- 每个测试结束 `pollThread.quitSafely()` 防止后台线程跨测试泄漏

### 4.8 `liveSession` 迁移（codex P1 #4）

抽出 OverlaySession 后，BobVpnService 的三个老 companion 字段 `liveController` / `livePoller` / `liveTapTrigger` 合并成一个：

```kotlin
companion object {
    @Volatile var liveSession: OverlaySession? = null
        internal set
}
```

`startOverlayAndPolling()` 成功后赋值 `liveSession = session`；`tearDown()` 第一行清 `liveSession = null`。

`TestReceiver` 全部命令统一通过 `liveSession?.handleX()` 进入（生产路径）：

| 命令 | 新实现 |
|---|---|
| `kill_battle` | `liveSession?.killBattleSocketDirect()` —— 不走状态机门控的"直接 kill"，跟 Phase 1.1 spike-e 行为兼容 |
| `overlay_tap` | `liveSession?.handleTap()` —— 走完整状态机门控 + cooldown |
| `overlay_state` | `liveSession?.poller?.currentState()` |
| `snapshot` | 仍直接 `RealConnectionCore.connectionsJson()`（debug 模式下走 DebugConnectionCoreOverride） |
| `sim_*` | 直接调 `DebugConnectionCoreOverride.xxx`（不需要 session） |

`OverlaySession` 需要新增一个 `killBattleSocketDirect()` 方法，绕过状态机直接调 controller —— 仅给 spike-e regression test 用。也加 `KDoc` 警告"不要在生产路径调用"。

### 4.7 Robolectric 测试用例

新增 `app/src/test/java/com/bobassist/phase0/integration/`：

| 文件 | 测什么 |
|---|---|
| `OverlaySessionTapTest.kt` | tap 在 Waiting/Ready/Cooldown 各自的行为；tap 到 closeConnection 的实际 Handler 延迟 |
| `OverlaySessionCooldownTest.kt` | Success 后 2s cooldown 精确时长；cooldown 期间 tap/poll 被吞 |
| `OverlaySessionForegroundTest.kt` | 前后台切换时 overlay show/hide 时序；切回 HS 时 lastState 保持 |
| `OverlaySessionTeardownRaceTest.kt` | tearDown 期间 in-flight tap/snapshot 被 liveness guard 拒绝 |

每个测试模式：

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class OverlaySessionTapTest {
    @Test
    fun `tap on Ready state dispatches to closeConnection within 20ms`() {
        val fake = FakeMihomoCore().apply { 
            snapshotJson = ONE_CANDIDATE_JSON  // host="" tcp port=3724
        }
        val session = OverlaySession(...构造各依赖, 用 fake...)
        session.start()
        ShadowLooper.idleFor(800, TimeUnit.MILLISECONDS)  // 触发一次 poll
        assertEquals(OverlayState.Ready, session.poller.currentState())
        
        val t0 = ShadowLooper.totalTime()
        session.handleTap()
        ShadowLooper.idleFor(100, TimeUnit.MILLISECONDS)
        val t1 = fake.closeCallLog.first().first  // closeConnection 被调用的时刻
        assertTrue((t1 - t0) < 20)  // ms
    }
}
```

---

## 5. 实施分期（codex P2 #12 重排）

依赖关系：OverlayUi interface 必须在 OverlaySession 抽出前到位；Clock 必须在 OverlayPoller 用时间戳前到位；Trace 跟 Clock 共依赖；Robolectric 工具链是测试前提。

| Stage | 工作 | 交付 |
|---|---|---|
| 5.1 | 引入 Robolectric 4.13 + minimal "hello robolectric" 测试 | 1 个能跑的 Robolectric 测试 + LooperMode PAUSED 声明 + AndroidElapsedRealtimeClock + ShadowLooper.idleFor 范例 |
| 5.2 | 加 `OverlayUi` interface；`OverlayWindow` 实现它 | 行为不变；老 1.1/1.2 测试仍绿 |
| 5.3 | 加 `Clock` interface + `AndroidElapsedRealtimeClock`；OverlayPoller 注入 Clock | 行为不变；为 5.5 trace 准备 |
| 5.4 | 抽出 `OverlaySession` 协调层 | 行为不变重构；老 1.1/1.2 单测仍绿；不需要 facade（BattleConnectionController 已是 lambda） |
| 5.5 | 加 `TraceSink` + 5 个 phase 的 trace 入口/出口（仅 tap-path：`tap, tap_post, kill, snapshot, close`） | Tap 路径可观测 |
| 5.6 | 加 LifecycleCoreFacade + ConnectionCoreFacade + Real* 实现 | BobVpnService 走 facade；生产代码无行为变化 |
| 5.7 | 加 debug-only `DebugConnectionCoreOverride` + TestReceiver `sim_*` 命令 | ADB 注入式 snapshot/close |
| 5.8 | 加剩下 8 个 phase 的 trace 入口（poll_tick / pick / cooldown_* / fg_change / setVisible 等） | 完整 trace 覆盖 |
| 5.9 | 迁移 `liveSession`，删 `liveController/livePoller/liveTapTrigger` | TestReceiver 全部走 liveSession；Phase 1.1 spike-e 测试仍绿 |
| 5.10 | 写 4 个 Robolectric 集成测试 (`OverlaySessionTapTest/CooldownTest/ForegroundTest/TeardownRaceTest`) | Tier 1 跑得通 |
| 5.11 | 写 `sim-bg-kill.sh` + 8 个场景 | Tier 2 跑得通；先 emulator 验证 cold_start，后实体机 |
| 5.12 | 用 Tier 1 + 2 实测 5-6s 拔线延迟，输出 phase 时间表 | 报告说明：哪个 phase 慢、根因推测 |
| 5.13 | （Phase 1.4 范畴，不在本 spec DoD 内）修复延迟 | 单独 plan |

5.1-5.11 是 phase 1.3 的核心；5.12 是 phase 1.3 的"应用"，结果落在 verification report 里。

---

## 6. Robolectric 关键约束（codex P2 #9 / #10 扩展）

1. **arm64 native lib (libgojni.so) 不能在 host JVM 加载**。
   - `MihomoCore object` 内部直接调 `Bobcore` 静态方法，一旦该 class 被 JVM 加载就会触发 native 加载 → UnsatisfiedLinkError。
   - Tier 1 测试 **绝不** 直接引用 `MihomoCore`、`RealLifecycleCore`、`RealConnectionCore` —— 这些都会拖入 native 路径。
   - 引用 `MihomoCore.CloseResult` 是安全的（只是嵌套 sealed class，class init 不调 Bobcore）—— 现有 31 个单测已经在用，验证过。
   - 任何 Robolectric 路径经过 `BobVpnService.bringUp()` 或 `MainActivity` 都会 boot native —— 所以 Tier 1 **不写 BobVpnService 或 MainActivity 的 Robolectric 测试**。

2. **Robolectric 不 shadow gomobile 生成的 `Bobcore` Java 类** —— 同上，绕开就行。

3. **`ShadowLooper` 默认 PAUSED（4.13）**：
   - `Handler.post {}` 提交的任务**不会自动跑**，必须显式调 `ShadowLooper.idle()`。
   - `postDelayed(cb, 2000)` 只在 `shadowOf(looper).idleFor(2000, MILLISECONDS)` 推进虚拟时间后才触发。
   - 显式声明 `@LooperMode(LooperMode.Mode.PAUSED)`，不要依赖 default 不变。

4. **`HandlerThread` 在 Robolectric 中是真线程，不是 shadow**：
   - 后台 Looper 跟主 Looper 是不同实例 —— 要分别 `shadowOf(pollThread.looper).idleFor(...)` + `shadowOf(Looper.getMainLooper()).idle()`。
   - 每个测试 `@After` 或 finally 块 `pollThread.quitSafely()`，否则会跨测试泄漏。
   - 推进顺序：先 pollHandler.idle (让 tap 进入 lambda)，再 mainHandler.idle (让 setVisible 跑) —— 顺序错会卡。

5. **时钟一致性（P1 #8）**：
   - `SystemClock.elapsedRealtimeNanos()` 在 Robolectric PAUSED 模式下被 shadow，跟 ShadowLooper.idleFor 推进的虚拟时间一致。**所有时间断言用 Clock.nowNanos() 间接读 SystemClock**。
   - **绝不混用 `System.nanoTime()`**（host JVM 真实时钟，跟 ShadowLooper 完全无关）。

6. **`WindowManager`**：Robolectric 有 `ShadowWindowManager` 但只支持基本 add/remove。我们 OverlayWindow 的拖拽/clamp 逻辑里读 `wm.currentWindowMetrics`——这部分 Robolectric 行为可能不完全。**OverlaySession 用 FakeOverlayUi**（§4.1.1），不依赖真 OverlayWindow。`OverlayWindow` 单独的 WindowManager 测试如果需要，单写 Tier 2 emulator 验证。

7. **`AppOpsManager.unsafeCheckOpNoThrow`**：Robolectric 有 `ShadowAppOpsManager`，默认所有 op 返回 `MODE_ALLOWED`。要测降级模式（permission denied），显式 `Shadows.shadowOf(appOps).setMode(OPSTR_GET_USAGE_STATS, uid, MODE_IGNORED)`。

8. **`UsageStatsManager`**：Robolectric `ShadowUsageStatsManager`——可以注入事件。OK。

9. **测试隔离**：每个 Robolectric 测试方法之间，Looper 状态、SystemClock、companion object 静态字段都不会自动 reset。`@Before` 显式重置或用 fresh instances。`DebugConnectionCoreOverride.clearAll()` 在每个 sim 测试前调一次。

---

## 7. Emulator 关键约束

1. **arm64 AVD on Apple Silicon**：本机是 M-series Mac，需要装 arm64-v8a system image（Android Studio → SDK Manager → System Images → arm64-v8a）。
2. **VpnService 在 emulator 里能跑**：emulator 提供完整 VpnService 实现，但路由的"对外网"是 emulator 宿主机网络。我们的测试场景不关心包真转发到哪——只关心 service 起得来 + WindowManager 浮窗能画 + mihomo libgojni.so 能加载。所以 VpnService 路由是否生效不重要。
3. **mihomo libgojni.so**：emulator system image 是 arm64-v8a，跟我们 APK 的 abi 匹配。能跑。
4. **首次启动慢**：~30-60 秒。后续保留 snapshot 启动可以 <10s。
5. **不需要 HS**：我们用 4.5 的注入式 snapshot，完全不用 HS。
6. **adb 用法跟实体机完全一致**：`scripts/sim-bg-kill.sh` 能不改一行就在 emulator 上跑。

---

## 8. Open Questions（codex round-1 已大部分回答）

| # | 问题 | 当前答案（codex r1 输入 + 用户决策） |
|---|---|---|
| 1 | MihomoCoreFacade 边界 | **拆成 LifecycleCore + ConnectionCore**（用户选）。§4.2 |
| 2 | OverlaySession 是否管 HandlerThread 生命周期 | 否——线程归 BobVpnService。OverlaySession 只是 Handler 的使用者。 |
| 3 | Robolectric 跟现有纯 JVM 单测共存？ | 共存。Robolectric 不强制所有测试走它的 runner。现有 31 个继续用 default JUnit。 |
| 4 | ShadowLooper paused vs default | **PAUSED 显式声明**——4.13 默认就是，但显式声明不让 default 变化破坏测试。 |
| 5 | sim_set_snapshot 注入对运行中 poller 安全？ | 安全。`AtomicReference<String?>` + 每次 connectionsJson() 是原子读 —— 跟真 mihomo 无锁快照语义一致。 |
| 6 | trace logcat 限速 | **解决方案：sample 窗口 ±2s**（codex P1 #6）。估算 30 条/tap，远低于 50/秒限速。 |
| 7 | OverlaySession 抽出后被测点少了 tearDown 路径 | 接受。tearDown 路径从未引起 bug，且 Tier 2 emulator smoke 会接住。 |
| 8 | 是否给 BobVpnService 写 Robolectric 测试？ | 否（codex P2 同意）。会触发 native load。 |
| 9 | 5-6s 延迟的 root cause 这套设施能否区分？ | **能**。Tier 1 测 gray-tap 吞掉延迟 + Tier 2 sim 测 slow_snapshot/tap_while_snapshot_inflight 直接 reproduce poll-thread 阻塞。HS 重连只能 Tier 3 实测。 |
| 10 | trace 格式：JSON vs plain | **plain key=value**（codex 同意），加 cycle_id 关联。 |
| 11 | OverlaySession 覆盖率指标 | 不强制——infrastructure 可用即可。 |
| 12 | 如果发现 mihomo Go 层慢，是否在 1.3 加 Go instrumentation？ | 不在 1.3 范围 —— 写进 debt list 转 1.4。 |

**仍待 codex round 2 review 的新点**：见 §6 expanded 后的 Robolectric pitfall 列表，以及 §4.4 sample-window 实现是否有竞态。

---

## 9. Definition of Done（codex P2 #13 / #14 修正）

1. `./gradlew :app:testDebugUnitTest` 跑出至少 35 个测试（31 现有 + 4 新 Robolectric），全绿
2. `scripts/sim-bg-kill.sh cold_start` **在 Android emulator 上**能跑通并输出 phase 时间表（emulator 是 Tier 2 的强制保底；实体机是 Tier 3 acceptance）
3. `OverlaySession` 抽出后，原 Phase 1.1 + 1.2 所有手测场景仍然 PASS（用同样的 ADB 命令验证）。`liveSession` 迁移后 spike-e 回归测试仍绿。
4. **trace/sim 能输出 phase 时间表，把延迟分类为：poll-wait, handler-queue-wait, snapshot, parse, close, cooldown, HS-reconnect**。如果 sim 跑不出 5-6s 现象，verification report 明确说明"未复现"，并保留实体机+真 HS 验证留给后续。诊断本身的结论不是 DoD 的硬条件——infrastructure 可用是 DoD。
5. release APK 完全不含任何 sim_* / DebugConnectionCoreOverride 路径（用 `aapt2 dump strings | grep -i sim` 验证空）。
6. Robolectric 测试在 host JVM 下 **不加载 libgojni.so**（用 `-verbose:jni` 或 `-Djava.library.path=/nonexistent` 验证）。

---

## 10. References

- spec v2: `docs/superpowers/specs/2026-05-24-bob-assistant-android-design.md`
- phase 0 plan: `docs/superpowers/plans/2026-05-24-bob-android-phase0-prototype.md`
- phase 1.1 plan: `docs/superpowers/plans/2026-05-25-bob-android-phase1-overlay-button.md`
- phase 1.2 plan: `docs/superpowers/plans/2026-05-25-bob-android-phase1.2-foreground-detector.md`
- Robolectric: https://robolectric.org/
- Android Emulator (Apple Silicon): https://developer.android.com/studio/run/emulator-acceleration#vm-mac

---
