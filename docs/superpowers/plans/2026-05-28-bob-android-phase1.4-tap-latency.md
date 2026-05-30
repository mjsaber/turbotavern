# Phase 1.4 — 拔线延迟修复 实现计划

**Date:** 2026-05-28
**Spec:** `docs/superpowers/specs/2026-05-28-bob-android-phase1.4-tap-latency-design.md` (v3, READY TO PLAN)
**Scope:** P1-only —— 缓存上次 poll 选中的 candidate,tap 直接关 cachedId,把 `connectionsJson()` 从 tap 关键路径移除。全程留在 pollHandler 单线程。P2(专用 kill 线程)推迟,见 spec §5.6。
**Approach:** TDD,subagent-driven,每个 task 之间 review。先红后绿。现有 53 个测试不得回归。

**Codex plan review round 1 → NOT READY,已修(round 2 待确认):** #1 trace 声明顺序(Task 4:`trace` 先于 `candidateCache`);#2 sim 回归(Task 7:`slow_snapshot`/`server_rotate` 依赖 tap-cycle 的 `snapshot`/`pick`,已改);#3 teardown trace 顺序(Task 3:保留 `tap_post entry`→guard→`state_check` 结构 + 具名 Runnable);#4 cycle-id 过滤(Task 7);#5 INV-5 trace 测试(Task 6 T9);#6 ManualClock 纳秒(Task 1 测 6)。

---

## 约束与不变量(实现时随时对照 spec §4)

- INV-1(单向):`Ready ⟹ cache.current().candidate != null`。反向不保证(Cooldown 留旧值,无害)。
- INV-2:tap 以 `poller.currentState()` 为门;Ready 分支读到 `candidate==null` 走 `no_candidate_cache_miss` 安全退出。
- INV-3:陈旧 id close 返回 NotFound/AlreadyClosed → 非 Success KillResult,**不进 cooldown**。id 是 UUID 不复用,绝不误杀。
- INV-4:rapid-tap 靠 pollHandler 串行 + 成功后同步 `enterCooldown()`,机制不变。
- INV-5:缓存带 `capturedAtMs`,tap 时 trace 打 `cache_age_ms`。
- 不动:`OverlayPoller`(仍 `snapshot: () -> Int`)、fingerprint、cooldown 时长、前台检测、release/debug variant 结构。

---

## Task 1 — `BattleCandidateCache`(新增)+ 纯 JVM 单测

**Goal:** 新建 `core/BattleCandidateCache.kt`,封装"拍快照→pick→原子写缓存→返回 count",并计时 `snapshot_ms`。

**File:** `app/src/main/java/com/bobassist/phase0/core/BattleCandidateCache.kt`

```kotlin
package com.bobassist.phase0.core

import com.bobassist.phase0.util.Clock
import com.bobassist.phase0.util.TraceSink

/** 一次 poll 的不可变结果。candidate==null ⇒ 当前无可关闭目标。 */
data class CachedReadiness(
    val candidate: BattleConnection.Candidate?,
    val count: Int,
    val capturedAtMs: Long,
)

/**
 * 单线程封闭(pollHandler):poll 每 tick 调 refresh(),tap 路径读 current()。
 * 把 connectionsJson() 从 tap 关键路径移除(Phase 1.4 P1)。
 * 自带可空 trace:refresh() 内开自己的 cycle 发 poll_snapshot(snapshot_ms),
 * 不需要改动 OverlayPoller 的 `snapshot: () -> Int` 签名。
 */
class BattleCandidateCache(
    private val snapshot: () -> String,
    private val clock: Clock,
    private val trace: TraceSink? = null,
) {
    @Volatile private var cached: CachedReadiness = CachedReadiness(null, 0, 0L)

    /** poll 线程每 tick 调一次。返回 candidate 数给状态机(readiness 以 candidate 为准)。 */
    fun refresh(): Int {
        val cycle = trace?.beginCycle()
        val t0 = clock.nowMillis()
        cycle?.emit("poll_snapshot", "entry")
        val json = snapshot()
        val snapshotMs = clock.nowMillis() - t0
        val (cand, n) = BattleConnection.pickWithCount(json)
        cached = CachedReadiness(cand, n, t0)
        cycle?.emit("poll_snapshot", "exit", "snapshot_ms" to snapshotMs, "count" to n, "picked_id" to cand?.id)
        return if (cand != null) n else 0
    }

    fun current(): CachedReadiness = cached
    fun clear() { cached = CachedReadiness(null, 0, clock.nowMillis()) }
}
```

> `trace` 默认 null(纯 JVM 单测不需要)。BobVpnService 传入 `TraceSink(enabled=BuildConfig.DEBUG,...)`,使 `snapshot_ms` 在 debug 真机可读(Step 0)。注意:`refresh()` 每 tick 开一个独立 cycle —— 这区别于 tap 的 cycle,trace 里以 cycle id 区分。这不改 §spec 的"trace 以 tap 为 ±窗口采样"假设吗?**确认:** poll_snapshot 是常驻轻量(每 tick 1 对 entry/exit);若担心量,可只在 `trace.enabled` 且采样窗口内发。Task 4 落地时与现有 TraceSink 采样策略对齐(见 Task 4 决策点)。

**Test:** `app/src/test/java/com/bobassist/phase0/core/BattleCandidateCacheTest.kt`(纯 JVM,用 `ManualClock` + 直接喂 JSON 字符串)
1. `refresh with one candidate caches it and returns 1` —— 一个合法 battle socket JSON → `current().candidate!=null`、id 对、count==1、返回 1。
2. `refresh with empty caches null and returns 0` —— `[]` → candidate==null、count==0、返回 0。
3. `refresh with two candidates picks newest-by-createdAt` —— 两个 3724 socket,createdAt 不同 → 选 createdAt 大的(对齐 `BattleConnection.pickWithCount`)。
4. `current reflects latest refresh` —— 连续 refresh 不同 JSON,current 跟最后一次。
5. `clear resets to empty` —— refresh 后 clear → candidate==null、count==0。
6. `capturedAtMs comes from clock` —— ⚠️ `ManualClock(initial)` 单位是**纳秒**,`capturedAtMs` 走 `nowMillis()=nowNanos()/1_000_000`。用 `ManualClock(1_234_000_000L)`(=1234ms)→ 断言 `capturedAtMs == 1234L`(codex r2-plan #6)。
7. `count positive but cand null edge` —— (理论不会,但锁定语义)用一个 `pickWithCount` 会返回 count>0 的输入是不可能的(maxByOrNull 在非空时必非空),所以此用例实为:断言"count==0 ⟺ candidate==null"的等价性(喂 `[]` 与喂一个 host!="" 的非候选,都得到 candidate==null/返回 0)。

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*BattleCandidateCacheTest"` 全绿;`./gradlew :app:assembleDebug` 编译过。

---

## Task 2 — `BattleConnectionController.killCachedCandidate(...)` + 单测

**Goal:** 新增不拍快照、直接关已知 candidate 的方法。保留 `killBattleSocket()` 原样(spike-e 回归用)。

**File:** `app/src/main/java/com/bobassist/phase0/core/BattleConnectionController.kt`(追加方法,不改现有)

```kotlin
fun killCachedCandidate(
    cand: BattleConnection.Candidate,
    candidatesAtKill: Int,
    cycle: TraceCycle? = null,
): KillResult {
    cycle?.emit("close", "entry", "conn_id" to cand.id, "cached" to true)
    val r = close(cand.id)                       // 不调 snapshot()
    cycle?.emit("close", "exit", "result" to r.toString())
    return when (r) {
        MihomoCore.CloseResult.Success ->
            KillResult.Success(cand.id, cand.destinationIp, cand.destinationPort, candidatesAtKill)
        MihomoCore.CloseResult.AlreadyClosed -> KillResult.AlreadyClosed
        else -> KillResult.Failure(r.toString())  // 含 NotFound（陈旧缓存)/CoreStopped/InternalError
    }
}
```

**Test:** 追加到现有 `BattleConnectionControllerTest.kt`
1. `killCachedCandidate success maps to Success with candidate fields` —— close 返回 Success → `KillResult.Success`,closedId/ip/port/candidatesAtKill 对。
2. `killCachedCandidate does NOT call snapshot` —— 注入一个会抛异常或计数的 snapshot lambda,断言 killCachedCandidate 期间 snapshot **零调用**(关键:证明不拍快照)。
3. `killCachedCandidate NotFound maps to Failure (stale cache, INV-3)` —— close 返回 NotFound → `KillResult.Failure("NotFound")`。
4. `killCachedCandidate AlreadyClosed maps to AlreadyClosed`。

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*BattleConnectionControllerTest"` 全绿(含原有 6 个)。

---

## Task 3 — `OverlaySession.handleTap()` 改走缓存关闭 + `postAtFrontOfQueue`

**Goal:** Ready 分支由 `controller.killBattleSocket(cycle)` 改为读 `candidateCache.current()` → `controller.killCachedCandidate(...)`;tap runnable 改 `postAtFrontOfQueue`;一次性读 state+readiness 进局部变量。

**File:** `app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt`

- 构造函数新增参数 `private val candidateCache: BattleCandidateCache`(放在 `controller` 之后、保持其余顺序)。
- `handleTap()` —— **保留现有 trace 结构**(codex r2-plan #3),只改 Ready 分支的 kill 实现 + post 方式:
  ```kotlin
  fun handleTap() {
      val cycle = trace.beginCycle()
      cycle.emit("tap", "entry", "state" to poller.currentState())
      val tapEntryNs = clock.nowNanos()
      val tapRunnable = Runnable {                         // 具名 Runnable:return@Runnable 不随 API 名漂移
          cycle.emit("tap_post", "entry", "delay_ms" to (clock.nowNanos() - tapEntryNs) / 1_000_000L)
          if (!started) {                                  // 保持 entry→guard 顺序(teardown trace 不丢)
              cycle.emit("tap_post", "exit", "result" to "session_stopped"); return@Runnable
          }
          val state = poller.currentState()                // 读一次
          val readiness = candidateCache.current()         // 读一次
          cycle.emit("state_check", "exit", "state" to state,
                     "cache_age_ms" to (clock.nowMillis() - readiness.capturedAtMs))   // INV-5
          when (state) {
              OverlayState.Ready -> {
                  val cand = readiness.candidate
                  if (cand == null) {                       // INV-2 容错
                      cycle.emit("tap_post","exit","result" to "no_candidate_cache_miss"); return@Runnable
                  }
                  val result = runCatching { controller.killCachedCandidate(cand, readiness.count, cycle) }
                      .getOrElse {
                          breadcrumb("overlay tap kill threw: ${it.message}")
                          cycle.emit("kill","exit","result" to "exception","msg" to it.message); return@Runnable
                      }
                  cycle.emit("tap_post","exit","result" to result::class.simpleName)
                  breadcrumb("overlay tap result=$result")
                  if (result is BattleConnectionController.KillResult.Success) {
                      Log.i(TAG, "overlay kill success: id=${result.closedId}"); poller.enterCooldown()
                  } else Log.i(TAG, "overlay kill non-success: $result")
              }
              OverlayState.WaitingForBattle -> { cycle.emit("tap_post","exit","result" to "no_candidate"); breadcrumb("overlay tap ignored (no candidate)") }
              OverlayState.Cooldown -> { cycle.emit("tap_post","exit","result" to "cooldown"); breadcrumb("overlay tap ignored (cooldown)") }
          }
      }
      pollHandler.postAtFrontOfQueue(tapRunnable)            // 仅这一个 runnable 插队
  }
  ```
- `killBattleSocketDirect()` / `forceTickNow()` / 其余方法不动。

> 与现状的差异仅 3 处:(1) `controller.killBattleSocket(cycle)` → 读缓存 + `controller.killCachedCandidate(cand, readiness.count, cycle)`;(2) `pollHandler.post{}` → 具名 Runnable + `postAtFrontOfQueue`;(3) `state_check` 多带 `cache_age_ms`。`tap_post entry`→`!started` guard→`state_check` 的顺序与字段名全部保留,Phase 1.3 的 trace/sim 解析不受影响。

**Test:** 现有 `OverlaySessionTapTest`(4 个)+ `OverlaySessionCooldownTest`(3 个)必须仍绿(它们 idleFor 一个 tick 填充缓存后 tap)。本 task 不加新用例(新用例集中在 Task 6),只确保重构后回归绿 + 编译。

**Verify:** `./gradlew :app:testDebugUnitTest`(全量 53)全绿;`assembleDebug` 过。

> 依赖:Task 3 需要 `IntegrationFactory` 已构造 `candidateCache` 并接线,否则现有集成测试编不过。因此 **Task 3 与 Task 4 合并验证**(改 session 的同时改 BobVpnService + IntegrationFactory),但代码改动分文件描述。先红(改 session 签名→IntegrationFactory 编译错)再绿(Task 4 补齐接线)。

---

## Task 4 — 接线:`BobVpnService` + `IntegrationFactory`

**Goal:** 真正把 cache 插进 poll→tap 数据流。

**File A:** `app/src/main/java/com/bobassist/phase0/BobVpnService.kt`(`startOverlayAndPolling`)
- ⚠️ **顺序(codex r2-plan #1):** 现状 `val trace = TraceSink(...)` 在 controller 之后(≈ line 187)。构造 `candidateCache` 必须在 `trace` **之后**(它要引用 trace)。把 `candidateCache` 放在 `trace` 声明之后、`poller` 之前:
  ```kotlin
  val trace = TraceSink(enabled = BuildConfig.DEBUG, clock = AndroidElapsedRealtimeClock)   // 已存在,保持
  val candidateCache = BattleCandidateCache(
      snapshot = { ConnectionCoreProvider.get().connectionsJson() },
      clock = AndroidElapsedRealtimeClock,
      trace = trace,                          // ← 让 snapshot_ms 在 debug 真机可读(Step 0)
  )
  ```
- poller 的 `snapshot` 由 `{ runCatching { pickWithCount(connectionsJson()).second }.getOrElse{...0} }` 改为 `{ runCatching { candidateCache.refresh() }.getOrElse { err -> breadcrumb("poll snapshot failed: ${err.message}"); 0 } }`(保留 teardown-race 的 getOrElse 0 兜底)。
- `OverlaySession(...)` 构造新增 `candidateCache = candidateCache`。
- controller 的 `snapshot/close` lambda **不变**(`killBattleSocketDirect` 仍用得到)。

**snapshot_ms trace(已在 Task 1 定型):** `BattleCandidateCache(snapshot, clock, trace)` 自带可空 `trace`,`refresh()` 内开自己的 cycle 发 `poll_snapshot` —— **不动 `OverlayPoller`**。BobVpnService 这里把 `trace`(= 上面构造的 `TraceSink(enabled=BuildConfig.DEBUG,...)`)传进 cache;`IntegrationFactory` 传 `factory.trace`(enabled=false)或 null。
- ⚠️ 现有 poll tick 的 cycle 由 `OverlayPoller.tick()` 自己 begin/emit(`poll_tick`),与 cache 的 `poll_snapshot` cycle 是**两个独立 cycle**。这没问题(trace 以 cycle id 区分),但要在实现时确认不会因每 tick 多开一个 cycle 把 trace 量翻倍到影响性能 —— `poll_snapshot` 只 2 行 emit,且 enabled 仅 debug。若 codex/实测担心,退化为"仅在 enabled 时开"。

**File B:** `app/src/test/java/com/bobassist/phase0/integration/IntegrationFactory.kt`
- 新增 `val candidateCache = BattleCandidateCache(snapshot = { fakeConn.connectionsJson() }, clock = clock)`(trace 传 null 或 factory.trace)。
- poller 的 `snapshot` 改为 `{ candidateCache.refresh() }`。
- `OverlaySession(...)` 新增 `candidateCache = candidateCache`。

**Verify:** `./gradlew :app:testDebugUnitTest`(全量,含 Task 3 重构后的 session)全绿;`assembleDebug` 过。**这是 Task 3+4 的联合绿灯。**

---

## Task 5 — `FakeConnectionCore` 增加 connectionsJson 调用计数

**Goal:** 让测试能断言"tap 期间不拍快照"。

**File:** `app/src/test/java/com/bobassist/phase0/util/FakeConnectionCore.kt`
- 增加 `val snapshotCallLog: MutableList<Long> = Collections.synchronizedList(mutableListOf())`(或简单 `AtomicInteger snapshotCalls`)。
- `connectionsJson()` 内记录一次(时间戳 elapsedRealtimeNanos)。
- 保留 `snapshotJson` / `closeCallLog` / `closeResults` / `closeDelayMs` 不变。

**Verify:** 编译过;不破坏现有用例(只新增字段)。

---

## Task 6 — 新增集成测试 T1–T8(Robolectric)

**Goal:** 覆盖 spec §6 Tier 0/1 的 8 条断言。新建 `OverlaySessionCacheTest.kt`(T1–T5)+ 补充 teardown/ordering 用例到合适文件。沿用 `OverlaySessionTapTest` 的 `drainBoth` / `oneCandidateJson` 模式。

| 用例 | 断言 |
|---|---|
| **T1** tap 用缓存不拍快照 | 起 + idle 一个 tick(填缓存)→ 记 `snapshotCalls` 基线 → `handleTap()` + drain → 断言 tap 后 `snapshotCalls` **未增加**,`closeCallLog` 有 1 条且 id == 缓存 id。 |
| **T2** INV-1 单向 | candidate JSON → tick 后 `candidateCache.current().candidate!=null` 且 `currentState()==Ready`;`[]` → tick 后 candidate==null 且 `WaitingForBattle`。 |
| **T3** 陈旧缓存优雅失败 | `closeResults[id]=NotFound` → Ready 后 tap → `closeCallLog` 1 条,**状态仍 Ready(未进 Cooldown)**,无异常。 |
| **T4** rapid-tap 1 次 | Ready 后连续 `handleTap()` 多次 + drain → `closeCallLog` 恰好 1、状态 Cooldown。 |
| **T5** 状态切换缓存语义 | Ready(缓存非空)→ 快照变 `[]` → tick → `WaitingForBattle`;cooldown 退出后回 Waiting,缓存语义符合 INV-1。 |
| **T6** tap-after-stop | Ready → `handleTap()` 紧接 `session.stop()` → drain → `closeCallLog` 空(`!started` 早退)。 |
| **T7** cooldown 中 tap 被拒 | 进 Cooldown → `handleTap()`(postAtFrontOfQueue)→ 被拒,不影响 cooldown-exit;idleFor(COOLDOWN_MS) 后回 Waiting。 |
| **T8** tap-before-queued-poll(stale read) | 状态 Waiting + 设好 candidate JSON 但**尚未 drain 那个会发现它的 tick**;直接 `handleTap()`(插队先跑)→ 读到 Waiting 走 no_candidate,`closeCallLog` 空(**stale read 但绝不误关**)。 |
| **T9** trace 输出 INV-5(codex r2-plan #5) | 用 **enabled 的 TraceSink**(捕获输出,参考 `TraceSinkTest` 的捕获方式)跑一个 tick + 一次 Ready tap → 断言出现 `poll_snapshot exit ... snapshot_ms=...` 且 tap 的 `state_check exit ... cache_age_ms=...`。防止字段被实现漏掉。 |

**Verify:** `./gradlew :app:testDebugUnitTest`(全量,目标 53 + 新增 ≈ 9 集成 + 7 cache 单测 + 4 controller,全绿)。

---

## Task 7 — sim 脚本更新(多场景,codex r2-plan #2/#4)

**Goal:** 修正所有依赖"tap 自身 cycle 里有 `snapshot`/`pick` 阶段"的场景——这些阶段在 P1 后从 tap cycle 消失(只留在 poll 路径,且 poll 用**不同的 phase 名 `poll_snapshot`**,不会与 tap cycle 的旧 `snapshot` 名混淆)。

**File:** `scripts/sim-bg-kill.sh`(+ 必要时 `sim-lib.sh`)

逐场景:
- **`tap_while_snapshot`**:tap cycle 里**不再有** `snapshot` 阶段。新增断言:tap cycle(含 `tap entry` 的 cycle)内 `phase=snapshot` 计数为 0。保留 `tap_post delay_ms`(残留排队 `S#1` 仍在,delay_ms ≥ 1500 仍成立——in-flight 的 **poll** snapshot 还在堵 pollHandler)。
- **`slow_snapshot`**:现断言"tap cycle 的 `snapshot` dt ≥ 1000"会失败(tap 不再拍快照)。**改为**:(a) tap cycle 内无 `snapshot` 阶段;(b) 从 **poll cycle** 提 `poll_snapshot exit snapshot_ms` ≥ 1000。⚠️(codex r2-plan #1):启动期在 `sim_set_snapshot_delay 1000` **之前**已有快照会产出小的 snapshot_ms;**不能**用首次匹配的 `extract_field`。做法:在 `sim_set_snapshot_delay 1000` 之后、延迟 `sim_force_tick` 之前 `adb logcat -c` 清一次,或扫描所有 `poll_snapshot exit` 行取 **max** snapshot_ms 来断言 ≥1000。`tap→close` 不再恒 ≥1000(取决于 tap 是否撞上 in-flight poll),**放宽**为只记录、不强断言阈值。
- **`server_rotate`**:现 `wait_for_trace pick exit` + `extract_field pick exit picked_id` 失效(tap 不再 pick)。**改为**断言 tap cycle 的 `close entry conn_id == sim-B`(证明关掉的是缓存里 newest-by-createdAt 选中的那个)。需保证设了 `TWO_CAND_OLD_NEW` 后**有一个 poll tick 刷新过缓存**再 tap(已有 `sim_force_tick` + `sleep 0.5`)。
- **审计其余场景**:`cold_start` / `rapid_tap` / `tap_at_poll_offsets` / `preexisting_candidate` 用的是 `tap_to_close_dt_ms`(close exit − tap entry)与 close 计数,`close` 阶段仍存在,**不受影响**;但仍要 grep 确认没有别处 `wait_for_trace ... (snapshot|pick)` 落在 tap cycle 上。
- phase-table 提取增加 `poll_snapshot snapshot_ms` 与 tap 的 `cache_age_ms`(来自 `state_check`)。
- "tap cycle 无 snapshot"的判断**必须按 cycle id 过滤**(沿用 slow_snapshot 已有的 `tap_cyc[cyc]` awk 模式),不能全局 grep `snapshot`(虽然 phase 名已是 `poll_snapshot` 不同,但按 cycle 过滤更稳)。

**Verify:** `bash -n scripts/sim-bg-kill.sh scripts/sim-lib.sh`;有设备时跑 `tap_while_snapshot` / `slow_snapshot` / `server_rotate` / `cold_start` / `rapid_tap`(无设备记 PENDING,同 Phase 1.3 惯例)。

---

## Task 8 — Step 0 测量探针落地说明 + DoD 收尾

**Goal:** 确认 `snapshot_ms` trace 在真机可读;更新文档。

- `snapshot_ms` 已由 Task 4 (a) 方案在 `BattleCandidateCache.refresh()` 内发出(`poll_snapshot` 阶段)。
- release dex 审计:`unzip -p app-release-unsigned.apk classes*.dex | strings | grep -E 'BattleCandidateCache|CachedReadiness|killCachedCandidate'` —— 这些是**生产类**(非 debug-only),出现在 release 是预期;关键是确认没有把任何新的 `sim_*` / debug 注入路径带进 release(沿用 Phase 1.3 命令,grep `sim_set|sim_force|DebugConnectionCoreOverride` 仍应无匹配)。
- 真机(有设备时):跑一局 BG,读 `BobTrace` logcat 的 `snapshot_ms` p50/p95 + tap→close + `cache_age_ms`,记入 `phase0-verification-report.md` 新增 "Phase 1.4" 段;据 OQ-1 判断是否需开 P2 phase。**无设备则标 PENDING USER。**

**Verify:** 全量 `./gradlew :app:testDebugUnitTest` 绿;`assembleDebug` + `assembleRelease` 过;dex 审计无 debug 泄漏。

---

## 测试运行命令汇总

```bash
cd /Users/jun/code/bob-assist/android/overlay-app
./gradlew :app:testDebugUnitTest                      # 全量单测 + 集成
./gradlew :app:assembleDebug :app:assembleRelease     # 双 variant 编译
bash -n scripts/sim-bg-kill.sh scripts/sim-lib.sh     # sim 语法
# 有设备时:
./scripts/sim-bg-kill.sh cold_start --rebuild
./scripts/sim-bg-kill.sh tap_while_snapshot
./scripts/sim-bg-kill.sh rapid_tap
```

## Task 依赖图

```
T1(cache)──┐
T2(controller)─┤
            ├─► T3(session)+T4(接线)[联合绿] ──► T6(集成测试 T1-T8)
T5(fake)────┘                                         │
                                                T7(sim)─┤─► T8(Step0/DoD)
```

## Definition of Done(对齐 spec §8)

1. tap 关键路径无 `connectionsJson()`(T1 + controller T2#2 双证)。
2. `snapshot_ms` 探针可读(真机记录 PENDING 设备)。
3. 现有 53 测试不回归 + 新增全绿。
4. `tap_while_snapshot` sim 断言更新且语法过(设备运行 PENDING)。
5. 双 variant 编译;dex 审计无 debug 泄漏。
6. 实现过 codex review(gate 3)。
