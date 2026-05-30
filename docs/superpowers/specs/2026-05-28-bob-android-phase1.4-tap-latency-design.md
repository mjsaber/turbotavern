# Phase 1.4 — Bob Android 拔线延迟修复 设计文档

**Date:** 2026-05-28
**Status:** v3 (codex review round 2 → READY TO PLAN)
**Platform:** Android 10+ (API 29+), arm64-v8a primary
**Scope:** 修复"拔线后 5-6 秒才生效"的延迟。改动集中在 tap 关键路径,不动 mihomo Go 层。
**Depends on:** Phase 1.3 测试基础设施(`OverlaySession` / `OverlayPoller` / `BattleConnectionController` / trace / sim)。诊断结论见 `scripts/phase0-verification-report.md` §"Task 12 — 5-6s diagnosis RESULTS"。

---

## Codex Review (round 1, 2026-05-28)

codex 抛了 2×P0 + 6×P1,几乎全部指向 **P2(专用 kill 线程)**:JNI 并发未证(#1)、rapid-tap 闸失效(#2)、cooldown 闪烁窗口(#7)、teardown race(#8)、capture-vs-execute 漂移(#9)、以及"在测出 S 之前就上 P2"的流程风险(#11)。跨线程的 cache/state 拆分隐患(#3/#4/#9)正是 **P2 引入的**——只要 kill 留在 pollHandler(P1-only),poll tick 在单线程上原子地更新 state+cache、tap 在同一线程读,这些隐患自然消失。

**核心决定:Phase 1.4 砍到 P1-only,P2 整体推迟到独立 phase**(前置条件:先测出真机 `S` + 通过 JNI 并发压测)。这也回到了用户最初"minimal surface, recommended #1"的取向。

**Round 2(2026-05-28)→ READY TO PLAN。** codex 确认 round-1 P0/P1 全部因 descope 消解,P1-only 路径可以据此规划。剩 3 个小项已修:(r2 #1) INV-1 改为单向"Ready ⟹ 缓存有 candidate"(成功 kill 进 Cooldown 后缓存可留旧值,无害,因 tap 以 state 为门);(r2 #2) 新增 T8 锁定"tap 插队优先于已排队 poll tick"的故意 stale-read 行为;(r2 #3) tap runnable 一次性读 state+readiness 进局部变量再发 trace。

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | **P0** | P2 依赖未证的 JNI 并发:`closeConnection()` 与 `connectionsJson()` 同时跑可能崩/死锁/脏读。 | **P2 整体推迟**(§5.3)。前置条件:真机/emulator 并发压测 + 查 mihomo 源码确认。本期不引入第二条线程,无此风险。 |
| 2 | **P0** | `killGuardUntilNs = now + COOLDOWN` 在 close 失败时仍抑制重试,违背 INV-3。 | 随 P2 推迟而消失。P1-only 下 cooldown 只在 `Success` 后 `poller.enterCooldown()`(同步、单线程),失败不进 cooldown、可立即重试。INV-4 沿用 Phase 1.3 现有机制不变。 |
| 3 | P1 | `Ready ⟺ cache 非空`跨线程不成立(两次独立 volatile 读)。 | P1-only 同线程读,不存在跨线程。**同时**:tap 在 Ready 分支仍**容错**——读到 `null` 就当 `no_candidate` 退出(§4 INV-2、§5.2)。 |
| 4 | P1 | `cached` 与 `cachedCount` 两个独立字段会配错对。 | §5.1 改为**单个不可变 `@Volatile CachedReadiness?`**(candidate+count+capturedAtMs 一体),原子换引用。 |
| 5 | P1 | 若 mihomo 连接 ID 会复用,陈旧 id close 可能误杀别的连接。 | §5.5:Spike D 证据显示 ID 是 **UUID v4**(`7d674f0c-...`),不复用。陈旧 id → `NotFound`,绝不撞别的连接。已记录。 |
| 6 | P1 | readiness 应由 `cand != null` 而非裸 count 决定(防 count>0 但选不出)。 | §5.1:readiness = `candidate != null`。`pickWithCount` 的 `maxByOrNull` 在 size>0 时必非空,二者等价;但 tap 门以 candidate 存在为准,并测 count>0/cand 的边界。 |
| 7 | P1 | P2 下 cooldown 异步回 pollHandler,close 后到 enterCooldown 之间 poll 可能重新发 Ready,UI 闪烁。 | 随 P2 推迟而消失。P1-only:enterCooldown 与 tap 同在 pollHandler 同步执行,无窗口。 |
| 8 | P1 | teardown race 未充分定义:已在跑的 kill lambda 不被 removeCallbacks 取消。 | P1-only 沿用 Phase 1.3 既有 liveness guard(`if (!started) return` 在 close 前;`OverlaySessionTeardownRaceTest` 覆盖)。新增一条用例:tap 已被接受后紧接 stop()。 |
| 9 | P2 | tap 物理时刻到 kill 执行之间 poll 可能改/清缓存,导致关了更新的 candidate 或关空。 | P1-only 下 tap lambda 在 pollHandler 上读缓存,与 poll tick 串行,无中途改动;§5.1 记 `capturedAtMs`,trace 在 tap 时打 cache age 作可观测。 |
| 10 | P2 | 若 snapshot 本身多秒,缓存可能远老于 800ms。 | §5.1 加 `capturedAtMs`;trace 在 tap 打 `cache_age_ms` + `snapshot_ms`,把真实陈旧度暴露出来。 |
| 11 | P2 | 流程风险:可能在回答 S 之前就落 P2。 | 直接砍 P2,Step 0 先测 S。P2 作为独立 phase,前置压测通过才启。 |
| 12 | P3 | `postAtFrontOfQueue` 回退可能重排 tap 到 cooldown-exit/cleanup 之前。 | §5.3:窄范围只用于 tap runnable;新增 ordering 测试(tap vs cooldown-exit:tap 读到 Cooldown 直接拒,重排无害)。 |

---

## 1. Why（问题 + 已确诊根因）

Phase 1.2 上线后用户反馈:**"拔线要等待很久,大概 5、6 秒钟才会生效"**。Phase 1.3 用 sim 把它确诊了:

- App 自身逻辑(tap→pick→close)≈ **1ms**(`cold_start` 3/3)。
- `slow_snapshot`(给 `connectionsJson()` 注入 +1000ms):tap→close = **1075ms**,1:1 被 snapshot 撑大。
- `tap_while_snapshot`(2000ms 的 snapshot 正在跑,tap 在中途按下):tap_post 被延迟 **3662ms** —— **SMOKING GUN**。

**根因(高置信):** overlay tap handler 和 800ms 轮询循环**共用一个 `pollHandler`(单 HandlerThread)**。每次 poll tick 调 `MihomoCore.connectionsJson()`(JNI 把整张活动连接表序列化成 JSON)。真机上 HS BG 局内有大量并发连接(battle.net / blizzard / CDN),这次序列化耗时不小(记 `S`)。当用户 tap 落在一次 snapshot 正在执行的窗口里:

1. tap 被 `pollHandler.post` 排到那次 in-flight snapshot **后面**,干等 ≤ `S`(队列等待);
2. tap lambda 跑起来后,`killBattleSocket()` **自己又调一次 `connectionsJson()`**,再花 ≈ `S`;
3. 然后才 pick + `closeConnection(id)`(这步才 ~1ms)。

所以真机 5-6s ≈ `S(队列里 in-flight 的)` + `S(tap 自己的 snapshot)` + ~1ms。**纯粹是 snapshot 串行化 + tap 自带 snapshot,不是 app 逻辑慢。**

**尚未测到的量:** 真机 live HS BG 局内 `connectionsJson()` 的真实耗时 `S`。sim 用注入延迟复现了机制,但没测真值。本期要先把 `S` 测出来(见 §6 Step 0),因为"够不够只靠缓存"取决于 `S`。

---

## 2. Goals / Non-Goals

### Goals
1. 把 `connectionsJson()` 从 **tap 关键路径**上彻底移除(tap 不再自己拍快照)。
2. 让 tap **不再排在一次 in-flight poll snapshot 后面**等待。
3. 真机上"绿圈点击 → HS 跳过动画"在主观上接近即时(目标 §8 量化)。
4. 先测出真机 `connectionsJson()` 真实耗时 `S`,作为验证基线与回归监控。
5. 保持 Phase 1.3 的可测性:新逻辑必须能在 Robolectric / sim 里复现验证,不需要打 BG。

### Non-Goals
- ❌ 不改 mihomo Go 层(不做"轻量 has-battle-socket 查询"那种跨 Go 边界的优化 —— 那是 fix #4,留作未来)。
- ❌ 不动 fingerprint(`host=="" && tcp && port==3724`)、不动 `BattleConnection.pick` 选择逻辑(newest-by-createdAt 已对)。
- ❌ 不改 cooldown 时长、不改 overlay 视觉/拖拽/前台检测。
- ❌ 不引入新依赖。

---

## 3. 候选方案与取舍

诊断报告列了 4 条 fix path。逐条评估:

| # | 方案 | 能消掉"tap 自带 snapshot"(`S`#2) | 能消掉"排在 in-flight snapshot 后"(`S`#1) | 代价 |
|---|---|---|---|---|
| 1 | **缓存上次 poll 选中的 candidate,tap 直接 `closeConnection(cachedId)`** | ✅ | ❌ | 小,单线程模型不变 |
| 2 | **kill 跑在独立线程(不在 pollHandler 上)** | ❌(单独不够) | ✅ | 中,引入并发 |
| 3 | 降低 poll 压力(降频 / 只在可见且非 cooldown 时 poll) | ❌ | 仅降低碰撞概率,不消最坏情况 | 小 |
| 4 | mihomo 侧加速 `connectionsJson()` | (间接,减小 `S`) | (间接) | 大,跨 Go 边界 |

**关键观察:** 方案 1 单独把延迟从 `S#1 + S#2` 砍到 `S#1`(约腰斩),常见情况(tap 时没有 in-flight snapshot)直接变即时;但**最坏情况仍残留一次 in-flight snapshot 的等待 `S#1`**。要做到"任何时候点都即时",必须同时干掉 `S#1` —— 这只能靠把 kill 从 pollHandler 解耦(方案 2)。`postAtFrontOfQueue` 只能插队**已排队但还没跑**的 tick,**无法抢占正在执行**的 snapshot(Handler 是协作式的,正在跑的 Runnable 必须跑完)。

**本期决定(post codex round 1):做 P1,推迟 P2。**
- **P1(缓存,本期做):** poll 循环每次把 pick 出的 candidate(+count)缓存下来;tap 时读缓存,直接关 `cachedId`,不再拍快照。诊断报告里"recommended #1",改动面最小、明确正确,**且全程留在 pollHandler 单线程内**——无任何新增并发。
- **`postAtFrontOfQueue`(本期做,低风险):** tap runnable 用它插队已排队的 poll tick,削掉"tap 排在已堆积 tick 后面"的部分(snapshot > 800ms 导致 tick 堆积时有效)。仍无法抢占 in-flight snapshot,但不引入线程。
- **P2(专用 kill 线程,推迟到独立 phase):** codex round-1 指出 P2 带来 JNI 并发未证(P0)、rapid-tap 闸失效(P0)、cooldown 闪烁、teardown race、capture 漂移一连串问题。**前置条件:** (a) Step 0 测出真机 `S` 证明残留 `S#1` 确实不可接受;(b) 真机/emulator 跑 `closeConnection`×`connectionsJson` 并发压测(分钟级)证明 mihomo 并发安全。两者都过,再开 P2 phase。

**为什么 P1-only 是干净的:** kill 留在 pollHandler ⇒ poll tick 在同一线程原子地"拍快照→pick→写缓存→改状态",tap lambda 也在这条线程上读缓存+状态,二者串行。codex 的 #3/#4/#7/#9(跨线程不一致)全部不成立;cooldown(INV-4)沿用 Phase 1.3 同步置位机制,一字不改。

---

## 4. 不变量(Invariants）—— 设计的正确性基石

本期(P1-only)必须守住:

- **INV-1（Ready ⟹ 缓存有 candidate;单向)：** 每次 poll tick 在**同一线程同一次调用**里"拍快照→pick→写缓存→由 candidate 是否存在决定状态":选出 candidate ⇒ 写缓存 + 状态可进 `Ready`;选不出 ⇒ 状态回 `WaitingForBattle`。因为读写都在 pollHandler 单线程串行,**只要 tap(也在 pollHandler 上)读到状态 `Ready`,缓存一定有一个 ≤ 一个 poll 周期前看到的 candidate**。readiness 以 `candidate != null` 为准,不以裸 count 为准(codex r1 #6)。
  - **注意是单向(codex r2 #1):** 反向不成立——成功 kill 进入 `Cooldown` 后缓存仍可能留着刚被关掉的旧 candidate(`tick()` 在 Cooldown 早退、不刷新)。这无害,因为 tap 以 `currentState()` 为门:`Cooldown`/`WaitingForBattle` 时根本不读缓存。无需在 cooldown 时主动 `clear()`。

> **Gate-3 codex 残留(已接受 + 已修一半):** 缓存可能在两类窗口内陈旧。
> - **暂停期(已修):** HS 切后台时 poller 暂停、缓存不刷新会变旧。`handleForegroundChange(false)` 现在**同步清缓存**(`candidateCache.clear()`),回到 HS 后 ≤1 poll 的窗口内即使 state 还是 Ready,tap 读到空缓存走 `no_candidate_cache_miss` 安全空操作,不误关、不误进 cooldown。回归测试 T10。
> - **服务器轮换期(已接受、记为有界限制):** 上次 poll 到 tap 之间(≤800ms)服务器新建了一个 3724 socket 且与旧的并存时,缓存仍持旧 id;关旧 id 返回 `Success` → 进 2s cooldown,而新 battle socket 仍开着,这一下"没跳过"。**有界**(≤800ms,下个 poll 必刷新)、**罕见**(Spike D:常态同时只有一个 socket)、**自恢复**(cooldown 退出后再点即中新的)。用户在 gate-3 选择 "Clear cache on pause" 时已确认轮换窗口"bounded & documented"。彻底消除需 OQ-4 的"失败/疑似兜底再快照"或 P2,留作后续。codex 在多轮 re-review 中持续指出此点——这是设计取舍(去掉 tap 自带 snapshot)的已知代价,非未处理缺陷。
- **INV-2（tap 只在 Ready 时动手,且容错）:** tap 先读 `poller.currentState()`,只有 `Ready` 才关闭。`WaitingForBattle`→no_candidate,`Cooldown`→忽略。**即使逻辑上不该发生,若 Ready 分支读到缓存为 `null`,也按 `no_candidate` 安全退出**(codex #3 容错),不抛、不误动作。
- **INV-3（缓存陈旧 → 优雅失败,不误杀）:** 缓存 id 最多约一个 poll 周期旧(若 snapshot 本身多秒则更旧——见 INV-5)。socket 长寿(Spike D:跨多个回合);万一已被服务器轮换,`closeConnection(staleId)` 返回 `NotFound`/`AlreadyClosed`,映射成非 Success 的 `KillResult`,**不进 cooldown**,用户可再点(下一 poll 已刷新)。**不会误杀别的连接**——连接 id 是 UUID v4(§5.5),不复用,陈旧 id 只会 NotFound。
- **INV-4（rapid-tap 仍恰好关 1 次）:** 沿用 Phase 1.3 现有机制——pollHandler 单线程串行 + 成功后**同步** `poller.enterCooldown()`。本期 tap 改走缓存关闭,但仍在 pollHandler 上、cooldown 仍同步,故 `rapid_tap` 行为一字不变。
- **INV-5（缓存陈旧度可观测):** 缓存带 `capturedAtMs`;tap 时 trace 打 `cache_age_ms`(= now − capturedAtMs),把真实陈旧度(尤其 snapshot 多秒时)暴露出来(codex #9/#10)。

---

## 5. 设计细节

### 5.1 新组件:`BattleCandidateCache`(core 包)

缓存的 candidate + count + 时间戳合成**一个不可变对象**,整体原子换引用(codex #4 —— 不用两个独立字段):

```
/** 一次 poll 的不可变结果。candidate==null 表示当前无可关闭目标。 */
data class CachedReadiness(
    val candidate: BattleConnection.Candidate?,   // null ⇒ 无目标
    val count: Int,                               // 命中候选数(telemetry)
    val capturedAtMs: Long,                       // 这次快照拍下的时刻(elapsedRealtime)
)

class BattleCandidateCache(
    private val snapshot: () -> String,
    private val clock: Clock,
) {
    @Volatile private var cached: CachedReadiness = CachedReadiness(null, 0, 0L)

    /** poll 线程每 tick 调一次:拍快照→pick→原子写缓存,返回 candidate 数给状态机。 */
    fun refresh(): Int {
        val t0 = clock.nowMillis()
        val (cand, n) = BattleConnection.pickWithCount(snapshot())
        cached = CachedReadiness(cand, n, t0)      // 单引用原子替换
        return if (cand != null) n else 0          // readiness 以 candidate 为准(codex #6)
    }
    fun current(): CachedReadiness = cached
    fun clear() { cached = CachedReadiness(null, 0, clock.nowMillis()) }
}
```

- `refresh()` / `current()` 本期都在 pollHandler 单线程上调,串行、无并发。`@Volatile` 保留(便宜、且未来 P2 复用时即是正确的可见性保证)。
- `BattleConnection.Candidate` 已带 `id / destinationIp / destinationPort / createdAt`,直接放进 `CachedReadiness`,无需新结构。
- `capturedAtMs` 支撑 INV-5 的 `cache_age_ms` trace。

**接线(BobVpnService.startOverlayAndPolling):**
- 现状 poller 的 `snapshot = { pickWithCount(connectionsJson()).second }`。改成 `snapshot = { cache.refresh() }`(行为等价:仍返回 count,但顺手原子写了缓存)。
- `cache = BattleCandidateCache(snapshot = { ConnectionCoreProvider.get().connectionsJson() }, clock = AndroidElapsedRealtimeClock)`。
- `OverlaySession` 新增构造参数 `candidateCache: BattleCandidateCache`,tap 路径用它。

### 5.2 `BattleConnectionController` 新增"关已知 candidate"路径

保留现有 `killBattleSocket(cycle)`(snapshot+pick+close,继续给 `killBattleSocketDirect` 的 spike-e 回归用)。**新增:**

```
fun killCachedCandidate(cand: BattleConnection.Candidate, candidatesAtKill: Int, cycle: TraceCycle? = null): KillResult {
    cycle?.emit("close", "entry", "conn_id" to cand.id, "cached" to true)
    val r = close(cand.id)               // 不调 snapshot()
    cycle?.emit("close", "exit", "result" to r.toString())
    return when (r) {
        CloseResult.Success      -> KillResult.Success(cand.id, cand.destinationIp, cand.destinationPort, candidatesAtKill)
        CloseResult.AlreadyClosed -> KillResult.AlreadyClosed
        else                     -> KillResult.Failure(r.toString())   // 含 NotFound（陈旧缓存）
    }
}
```

- tap 关键路径上**没有 `snapshot()`**。trace 里 `close` 直接接在 `tap_post` 后,不再有 `snapshot` 子阶段 —— 这是验证 P1 生效的可观测信号。

### 5.3 tap 路径改动(P1-only,本期)

`OverlaySession.handleTap()` 的 `Ready` 分支由"`controller.killBattleSocket(cycle)`(内含 snapshot)"改成"读缓存 → `controller.killCachedCandidate(...)`":

```
pollHandler.postAtFrontOfQueue {            // 插队已排队的 poll tick(codex r1 #12:窄范围,仅 tap)
    if (!started) { cycle.emit("tap_post","exit","result" to "session_stopped"); return@post }
    // 一次性读 state + readiness 进局部变量(codex r2 #3:避免重复读、trace 一致)
    val state = poller.currentState()
    val readiness = candidateCache.current()
    cycle.emit("tap_post", "entry", "delay_ms" to ..., "state" to state,
               "cache_age_ms" to (clock.nowMillis() - readiness.capturedAtMs))
    when (state) {
        Ready -> {
            val cand = readiness.candidate
            if (cand == null) {              // INV-2 容错(codex r1 #3):理论不该发生
                cycle.emit("tap_post","exit","result" to "no_candidate_cache_miss"); return@post
            }
            val result = runCatching { controller.killCachedCandidate(cand, readiness.count, cycle) }
                .getOrElse { ...exception... ; return@post }
            if (result is Success) poller.enterCooldown()   // 同步,与 tap 同线程 → INV-4
        }
        WaitingForBattle -> ...no_candidate...
        Cooldown -> ...cooldown...
    }
}
```

- 关键路径上**没有 `connectionsJson()`**;trace 里 `close` 直接接 `tap_post`,无 `snapshot` 子阶段 —— P1 生效的可观测信号。
- **`postAtFrontOfQueue`(codex #12)**:只包这一个 tap runnable。它把 tap 排到**已排队但未执行**的 poll tick 之前(snapshot > 800ms 致 tick 堆积时显著)。无法抢占**正在执行**的 in-flight snapshot——那部分残留 `S#1` 是 P2 才能消的。与 cooldown-exit 回调的重排无害:tap 读到 `Cooldown` 状态直接拒(已加 ordering 测试,§6 T7)。
- liveness/teardown 沿用 Phase 1.3:`if (!started) return` 在 close 前;`stop()` 仍只 `pollHandler.removeCallbacksAndMessages(null)`,无新线程要拆(codex #8)。

### 5.4 测量探针(Step 0,必做且保留)

在 poll 路径给 `connectionsJson()` 计时,产出真机 `S`:
- `BattleCandidateCache.refresh()` 用注入的 `Clock` 量 `snapshot()` 耗时,通过 trace 发 `poll_snapshot` 阶段的 `snapshot_ms`。
- 真机跑一局 BG,读 `BobTrace` logcat 里的 `snapshot_ms` 分布 → 这就是诊断报告里"唯一剩下的真机确认"。
- 这条探针**长期保留**,作为延迟回归监控(snapshot_ms 飙升即预警)。

### 5.5 连接 id 唯一性(codex #5)

mihomo 的连接 id 是 **UUID v4**(Spike D 证据:`7d674f0c-ac0b-4ca9-b0d5-f61ba33b5e87`、`054917a6-...`),全局唯一、不复用。因此:陈旧缓存 id 在 close 时只可能命中"同一条连接(还活着)"或"无此连接(`NotFound`)",**绝不会撞到一条新的、别的连接**。这就是 INV-3"不误杀"的依据。(若未来 mihomo 改用可复用的短 id,本结论需重验——记为前提。)

### 5.6 Deferred:P2(专用 kill 线程)—— 不在本期

为消掉残留的 `S#1`(tap 排在 in-flight snapshot 后),未来可把 kill 放到独立 `killHandler`。codex round-1 指出这会引入 JNI 并发(P0)、异步 cooldown 的 rapid-tap 闸与 UI 闪烁、capture-vs-execute 漂移、teardown race。**启动 P2 的前置条件(全部满足才做):**
1. Step 0 实测真机 `S` 足够大,证明 P1+postAtFrontOfQueue 后残留仍不可接受(主观/量化)。
2. 真机或 emulator 跑 `closeConnection` × `connectionsJson` **分钟级并发压测**无崩溃/死锁,并最好对照 mihomo 源码确认连接管理器并发安全(OQ-2)。
3. 设计补齐:`killGuardUntilNs` 同步去重(在 killHandler 上,close 前置闸、且只在 `Success` 后维持 cooldown-pending;失败立即清闸允许重试)、cooldown-pending 标志对 poll/tap 双向可见以防 Ready 闪回、kill lambda 内 `started`/generation 二次确认。
这些都留给 P2 的独立 spec。

---

## 6. 验证计划(对应测试金字塔)

### Step 0 — 真机测 `S`(需设备 + HS)
跑 §5.4 探针,记录 live BG 局内 `connectionsJson()` 的 `snapshot_ms` p50/p95。**决定 P2 是否必要。**

### Tier 0/1（Robolectric + 纯 JVM,秒级,无设备)
新增/改动测试,断言:
1. **T1 tap 用缓存不拍快照:** FakeConnectionCore 记录 `connectionsJson` 调用次数;poll 几个 tick 后 tap,断言 tap 期间 `connectionsJson` **未被再调**,`closeConnection(cachedId)` 被调且 id == 上次 poll 选中的 id。
2. **T2 INV-1:** 选出 candidate 的 tick 后 `cache.current().candidate!=null` 且状态 Ready;无候选的 tick 后 `candidate==null` 且状态 WaitingForBattle。另测边界:`pickWithCount` count>0 但选不出(理论上不会,但断言 readiness 以 candidate 为准)。
3. **T3 陈旧缓存优雅失败(INV-3):** 缓存 id 在 close 时返回 `NotFound` → `KillResult.Failure`,**不进 cooldown**,状态机不被破坏。
4. **T4 rapid-tap 仍 1 次(INV-4):** 连点多次,断言 `closeConnection` 恰好 1 次成功、其余被 cooldown 去重(同步置位,与现状一致)。
5. **T5 cache.clear / 状态切换:** 进入 WaitingForBattle / cooldown 退出后的缓存语义符合 INV-1。
6. **T6 tap-after-stop(INV-2/teardown,codex #8):** tap 被接受(post)后紧接 `stop()`,断言 tap lambda 因 `!started` 安全退出,不调 `closeConnection`。
7. **T7 ordering(codex r1 #12):** cooldown 中 tap(`postAtFrontOfQueue`)读到 `Cooldown` 状态 → 被拒,不影响 cooldown-exit 回调;cooldown 正常到期退出。
8. **T8 tap-before-queued-poll(codex r2 #2):** 当下状态 `WaitingForBattle`、一个会发现 candidate 的 poll tick 已排队;tap 插队先跑 → 读到 `WaitingForBattle` 走 no_candidate(**stale read,但绝不误关**)。锁定这一"插队优先于已排队状态转移"的故意行为。

### Tier 2（sim 脚本,emulator/device,~30s)
- 改 `tap_while_snapshot`:断言 tap 后的 trace **不含** `snapshot` 子阶段(P1 已生效);并产出 tap→close 实测。
  - P1-only 预期:tap_post 队列等待仍 ≈ in-flight 注入延迟(`S#1` 未消,需 P2 才消),但 tap lambda 内 close 紧跟 tap_post(省掉了 tap 自带的 `S#2`),且 `cache_age_ms` 被记录。
- `cold_start` / `rapid_tap` / `server_rotate` / `preexisting_candidate` 全回归绿。

### Tier 3（真机 OnePlus 10T,最终签收)
打一局 BG,主观确认拔线明显变快;读 trace 确认 `snapshot_ms`(Step 0 的 `S`)、tap→close 实测、`cache_age_ms`。据此判断是否需要开 P2 phase(§5.6)。

---

## 7. 影响面(改动文件清单,预估)

| 文件 | 改动 |
|---|---|
| `core/BattleCandidateCache.kt` | **新增** |
| `core/BattleConnectionController.kt` | 新增 `killCachedCandidate(...)` |
| `session/OverlaySession.kt` | 构造参数 +`candidateCache`;`handleTap()` Ready 分支改走缓存关闭 + `postAtFrontOfQueue` + `cache_age_ms` trace |
| `core/BattleCandidateCache.kt` 内 `snapshot_ms` | `refresh()` 计时,trace `poll_snapshot`(Step 0) |
| `BobVpnService.kt` | 构造 `BattleCandidateCache`;poller `snapshot` 改 `cache::refresh`;传 `candidateCache` 进 session |
| `src/test/.../FakeConnectionCore.kt` | 记录 `connectionsJson` / `closeConnection` 调用次数 + 可配置 NotFound |
| `src/test/.../integration/*` | 新增 T1–T8 用例 |
| `scripts/sim-bg-kill.sh` / `sim-lib.sh` | `tap_while_snapshot` 断言更新 + `snapshot_ms` / `cache_age_ms` 提取 |

> 不动:`OverlayPoller.kt`(snapshot 语义不变,仍 `() -> Int`)、fingerprint、cooldown、前台检测、release/debug variant 结构。

---

## 8. Definition of Done

1. tap 关键路径上不再出现 `connectionsJson()`(代码 + trace 双重证实:tap 后无 `snapshot` 子阶段)。
2. Step 0 真机 `S`(`snapshot_ms` p50/p95)实测并记录进 `phase0-verification-report.md`。
3. Tier 0/1 新增测试全绿;现有 53 个测试不回归。
4. `tap_while_snapshot` sim 断言更新且通过;其余 sim 场景回归绿。
5. 真机签收:绿圈点击 → HS 跳过动画主观明显变快;
   - **量化:** tap 路径不再含 snapshot(trace 证实);tap lambda 内 tap_post→close < 50ms。残留的总延迟最坏 ≈ `S#1`(一次 in-flight snapshot),由 Step 0 的 `S` 量化,并据此决定是否开 P2 phase(§5.6)。
6. release dex 审计:新增类不得把任何 debug-only 注入路径带进 release(沿用 Phase 1.3 审计命令)。
7. 每个 gate 过 codex review(spec / plan / 实现各一轮)。

---

## 9. Open Questions

- **OQ-1（决定是否开 P2 phase 的核心):** 真机 live BG 局内 `connectionsJson()` 的 `S` 到底多大?(Step 0 测)P1+postAtFrontOfQueue 之后残留的 `S#1` 主观能否接受?
- **OQ-4:** 缓存陈旧窗口内若 socket 刚被服务器轮换,旧 id close 返回 NotFound —— 是否要在 NotFound 时**回退到一次即时 snapshot+pick+close**("缓存优先,失败兜底")?权衡:UX(几乎不丢点击) vs 路径纯净 + 不把 `connectionsJson()` 请回 tap 路径。**本期倾向:不兜底**(让用户再点,下一 poll 已刷新;陈旧极罕见因 socket 长寿)。请 codex 评是否值得加。
- **OQ-2 / OQ-3(已移交 P2 spec):** mihomo `closeConnection`×`connectionsJson` 真并发是否安全、双 cooldown 表示是否会闪烁 —— 仅当未来开 P2 phase 时才需回答,见 §5.6 前置条件。
