# Bob 回归测试模块(sim + 流量回放)设计文档

**Date:** 2026-05-29
**Status:** v1(待 codex review）
**Layer:** 测试基础设施(`android/overlay-app/` 下),不改产品代码(`bobcore` / Kotlin 主体 / 指纹 / overlay 全不动）
**Scope:** 把现有零散的 `sim-bg-kill.sh` + `sim-lib.sh` + `analyze-recording.py` + 录制目录,收敛成一个**独立、可复用、可跨多台 Android 设备**的回归测试模块,让"核心跳过功能有没有退化"以后**不必每次进游戏**就能验。

---

## 1. Why(动机 + 现状缺口)

当前我们踩过三类问题,但没有任何一套能"回放检查"住它们(详见会话分析):

| 历史问题 | 谁该抓 | 现状 |
|---|---|---|
| **A. 指纹端口 3724→1119** | 录制回放 + 指纹逻辑 | ⚠️ 半覆盖:`analyze-recording.py` 用 **Python 重写**的指纹回测,会和 Kotlin `pick()` 漂移;`sim-bg-kill.sh` 的假快照端口**写死 3724**;**没有任何单测引用录制**。 |
| **B. tap 延迟(Phase 1.4)** | 设备侧 sim | ✅ `sim-bg-kill.sh` 覆盖(设备上测 tap→close、断言 tap 路径不再 snapshot)。 |
| **C. 慢跳过 = FIN 而非 RST(Phase 1.5)** | — | ❌ connection-table 回放**天然抓不到**(录制里没有包级数据/关闭时机/对端反应)。只有 Go 的 `TestAbortSendsRST` 守住"Abort=RST"。 |

**现状的三个具体痛点:**
1. **指纹逻辑没有自动回归。** 834 帧真实录制(`recordings/2026-05-29-session1/`)现在只被一个一次性 Python 脚本读,不进 `./gradlew test`。改了 Kotlin、Python 没跟,就漏判。
2. **设备侧 harness 是 macOS bash 3.2**,单设备假设硬编码(`adb` 默认设备)、无法跨设备并发、无结构化报告。扩到多设备会越来越难维护。
3. **没有"一条命令跑全套 + 出报告"的入口**,也没有设备清单(谁是被测设备、型号、备注)。

**目标场景(用户原话):** 以后扩展到多种 Android 设备时,都能用这个功能做 regression test。

---

## 2. 核心设计原则:分两层 + 一条诚实边界

回归要验的东西本质分成两类,**必须分层**,否则会写出"看起来在测、其实测不到"的东西:

### 验证矩阵(本模块的骨架)

| 关注点 | 层 | 需要真机? | 需要真实 HS 服务器? | 证明了什么 | 本模块覆盖 |
|---|---|---|---|---|---|
| 指纹:选对战斗 socket | **L1 JVM 离线回放** | 否 | 否(录制数据) | 在录制数据上指纹逻辑没退化 | ✅ 新增 |
| tap 路径延迟 / 缓存 / 冷却 / 前台门控 | **L2 设备侧 sim** | 是 | 否(注入假快照) | 该设备上 app 行为正确 | ✅ 重写 |
| 关闭是 RST 不是 FIN(线级) | Go 单测 | 否 | 否(gvisor 配对栈) | wire 上确实是 RST | ✅ 已有,纳入入口 |
| RST 路径在该设备上启用 | **L2 设备侧 sim** | 是 | 否 | 自检在该硬件/ROM 上 fire 了 | ✅ 新增断言 |
| **HS 真的 <1s 跳过** | **手动 live** | 是 | **是** | 端到端产品效果 | ❌ **无法自动化** |

### 一条诚实边界(写进模块文档,避免误判"全绿=没问题")

- **L1 只能在"录制反映当前 build"的前提下抓回归。** 服务器下次又改端口/协议,在你**重新抓一份录制之前**对 L1 是不可见的。→ 模块必须把"录制新鲜度"显式化(录制带 build 标识 + 日期;过期发警告),并保留 `analyze-recording.py` 作为**人工探索新录制**的工具。
- **L2 不接真实 HS,验不了"HS 真的秒跳"。** 它只能验:(a) RST 路径**已启用**(自检日志);(b) app 侧关闭**快**。最后一行(真 HS 反应)只能靠真人进一局,**本模块不假装能测它**,但会在报告里把它列为"需手动确认"的一项,给出操作清单。

> 设计取舍(已与用户确认 2026-05-29):
> - 指纹回放断言跑在 **JVM,喂真 `BattleConnection.pick()`**;Python 仅保留探索/可视化,不再做断言来源。**单一事实来源 = Kotlin。**
> - 设备侧 harness **重写为 Python**(便于 `-s serial` 参数化、跨设备、结构化报告)。
> - 多设备做到**设备矩阵配置**(`devices.yaml`)+ 一条命令跑全矩阵 + 汇总。

---

## 3. 模块结构

```
android/overlay-app/
├── app/src/test/.../core/BattleConnectionRecordingTest.kt   ← L1 新增:录制驱动的 JVM 回归
├── app/src/test/resources/recordings/                       ← L1 录制 fixtures(见 §4.2 取舍)
│   └── 2026-05-29-session1/ { <epoch_ms>.json, MARK-*.txt, meta.json }
├── regression/                                              ← L2 新模块根(替代 scripts/sim-*.sh)
│   ├── bobsim/                       (Python 包,无第三方依赖,仅标准库)
│   │   ├── __init__.py
│   │   ├── __main__.py               `python -m bobsim run ...` 入口
│   │   ├── adb.py                    单设备 adb 封装(每个实例绑定 -s <serial>)
│   │   ├── testreceiver.py           sim_* / overlay_* 广播(移植 sim-lib.sh)
│   │   ├── trace.py                  BobTrace 解析(移植 parse_trace / tap_to_close_dt 等 awk)
│   │   ├── scenarios.py              8 个现有场景 + rst_enabled,纯函数(device)->ScenarioResult
│   │   ├── runner.py                 跨设备编排 + 报告
│   │   └── devices.py                devices.yaml 加载/校验
│   ├── devices.yaml                  设备矩阵清单
│   └── reports/<timestamp>/<device>/...   每次运行产物 + summary.md
├── scripts/analyze-recording.py      ← 保留,降级为"探索工具"(不再是断言来源)
└── scripts/sim-bg-kill.sh, sim-lib.sh ← 退役(见 §7 迁移)
```

**为什么 L1 放 `app/src/test`、L2 放 `regression/`:** L1 必须能 `import` 真的 `BattleConnection`,只能活在 gradle 测试源集里;L2 是设备编排,和 gradle 解耦更干净(可单独 `python -m bobsim` 跑,不必起 gradle)。

---

## 4. L1 — 离线流量回放(JVM,设备无关)

### 4.1 测什么
`BattleConnectionRecordingTest`:遍历每个录制目录,逐帧把 `<epoch_ms>.json`(就是 `Connections()` 的原始 JSON,正好是 `pick()` 的入参)喂给**真的** `BattleConnection.pickWithCount()`,按 `MARK-*.txt` 标注的战斗/菜单窗口断言:

- **战斗窗口内**:至少有一帧 `pick() != null`(选得到战斗 socket),且端口 ∈ `{1119,3724}`、`host==""`。
- **菜单/选人窗口内**(进战斗前):`pick() == null`(不误选),否则会"还没进战斗就拔线"。
- **回归锚点**:把"旧指纹 `{3724}` 在本录制 Ready 帧数 = 0"也断言进去——这正是端口漂移的护栏(若某天 `{3724}` 又能匹配,说明录制/指纹有人动过,强制复核)。

### 4.2 录制 fixtures 的取舍(codex 重点看这里)

录制 834 帧 × JSON,体积不大但非平凡。两个放法:

- **方案 A(推荐):复制进 `app/src/test/resources/recordings/`。** 测试自包含、CI 可跑、不依赖 `scripts/` 相对路径;代价是录制数据存两份(源 `recordings/` 留作"原始采集归档",test/resources 留作"被锁定的 fixture")。
- 方案 B:测试读 `../../recordings/` 相对路径。省一份拷贝,但把测试和仓库布局耦合,CI/沙箱里易碎。

→ 取 **A**。每个 fixture 录制加一个 `meta.json`:`{ app_version, captured_at, device, hs_build, combat_windows:[{start_ms,end_ms}], menu_windows:[...] }`。**战斗/菜单窗口从 MARK 文件推导一次、固化进 meta.json**,这样断言不靠脆弱的文件名解析,也让"这份录制证明了什么"自描述。

### 4.3 不做什么
- L1 不模拟关闭时机、不碰 FIN/RST(那是 L2 + Go 单测的事)。
- L1 不引第三方库,用项目已有的 `org.json`(`pick()` 已经用它)。

---

## 5. L2 — 设备侧 sim(Python,设备相关)

### 5.1 移植原则:行为等价,先不改语义
把 `sim-lib.sh` 的设备原语和 `sim-bg-kill.sh` 的 8 个场景**逐一移植**到 Python,**断言阈值/语义保持一致**(codex 之前几轮在 bash 里修过的坑要原样带过来,不重新发明):

- 设备 shell 引号灾难:`am broadcast --es json <JSON>` 必须把整条设备命令拼成一个串、对每个动态值做设备 sh 单引号转义(`sim-lib.sh` 的 `_shq`/`_bob_broadcast` 注释)。→ `testreceiver.py` 必须复刻这套引号策略,**用 `subprocess` 列表参数 + 显式构造设备命令串**,不能依赖 host shell。
- `overlay_state` 不能 `logcat -c`(会冲掉 BobTrace tap-cycle 行);用"广播前行数快照 + 等更新行"避免读到 stale(sim-lib.sh round-3 P2)。
- `tap_at_poll_offsets` 跨样本要保前台 override、只清 snapshot/close override(round-5 P2)。
- 冷却等待匹配的是 `Waiting` 不是 `WaitingForBattle`(round-4 P2)。
- `--rebuild` 时 build/install 失败必须中止,不能验到旧 APK(round-2 P2)。

### 5.2 场景集
现有 8 个:`cold_start / rapid_tap / server_rotate / permission_revoke / slow_snapshot / tap_while_snapshot / tap_at_poll_offsets / preexisting_candidate`。
**新增 1 个 `rst_enabled`**:启动服务 → 抓 logcat 断言出现 `[bobcore] rst kill enabled=true`(Phase 1.5 自检)。这是把"C 类问题在该设备上的可自动化部分"纳进回归。

**修正 fixture 端口:** 假快照里加入 1119(`ONE_CAND_JSON` 等),或参数化端口,避免再写死 3724 让真端口漂移悄悄漏过。

### 5.3 单设备封装
`adb.py` 每个实例持有 `serial`,所有命令走 `adb -s <serial> ...`。`testreceiver.py`/`scenarios.py` 只通过这个实例操作设备——这是"多设备"落地的关键:并发跑多台,就是并发实例化多个 `Device`。

### 5.4 报告
每个场景产出 `ScenarioResult{name, device, passed, checks:[{desc, ok, detail}], artifacts_dir}`,沿用 bash 的 `PASS:/FAIL:` 语义。每台设备一个目录(trace、phase-table、原始 logcat);跨设备 `summary.md` 出一张"场景 × 设备"通过矩阵。

---

## 6. 多设备矩阵(`devices.yaml`)

```yaml
# regression/devices.yaml
devices:
  - name: oneplus-10t
    serial: e85c3473        # adb -s 用
    model: "OnePlus 10T"
    os: "OxygenOS 14 / Android 14"
    notes: "主测机,Phase 1.5 真机验过"
# 以后加机器:再 append 一项
```

入口:
- `python -m bobsim run --device oneplus-10t` 跑单台全场景。
- `python -m bobsim run --all` 跑矩阵里所有**当前在线**的设备(离线的标 SKIPPED 并在 summary 里点名,不静默)。
- `python -m bobsim run --device X --scenario cold_start` 跑单场景。
- `python -m bobsim list` 列矩阵 + 在线状态。

**范围纪律(避免过度设计):** `devices.yaml` 只放 `name/serial/model/os/notes`。**不**引入 per-device 能力 profile、不做条件跳过 DSL、不做远程设备池——现在只有 1 台,这些是 YAGNI。需要时再加。

---

## 7. 迁移 / 退役

- `sim-bg-kill.sh` + `sim-lib.sh`:Python 版**逐场景验证行为等价(同一台设备上新旧各跑一遍、结果一致)后**再删。删除单独成 commit,便于回滚。
- `analyze-recording.py`:**保留**,但在文件头注明"探索工具,非回归断言来源;回归断言见 `BattleConnectionRecordingTest`"。删掉/弃用它里面 Python 重写的指纹 `fp_match` 的"断言"含义(留作可视化打印可以,但不得被当成事实来源)。
- `phase0-verification-report.md`:报告生成迁到 `regression/reports/`;旧文件保留为历史快照。

---

## 8. Goals / Non-Goals

### Goals
1. 指纹回归进 `./gradlew test`:录制驱动、喂真 `pick()`、无设备、CI 可跑。
2. 设备侧 sim 重写为 Python,`-s serial` 参数化,8+1 场景行为等价。
3. `devices.yaml` 矩阵 + 一条命令跑全矩阵 + per-device & 汇总报告。
4. 把"录制新鲜度"和"HS 真反应需手动确认"两条边界**显式写进模块**,不让"全绿"被误读成"端到端没问题"。
5. 不改任何产品代码;不引第三方运行时依赖(Python 仅标准库)。

### Non-Goals
- ❌ 不做 pcap 级录制(要覆盖 C 类的"真 HS 反应"需抓包 + 真游戏,属录制格式升级,本期不做)。
- ❌ 不做 CI 真机农场对接 / 远程设备池。
- ❌ 不做 per-device 能力 profile / 条件 DSL(只 1 台,YAGNI)。
- ❌ 不改指纹/状态机/overlay/bobcore 行为。
- ❌ 不替换 Go 单测(`TestAbortSendsRST` 仍是 C 类线级护栏,只是被入口纳入"跑全套")。

---

## 9. 成功判据

- [ ] `./gradlew :app:testDebugUnitTest` 含新 `BattleConnectionRecordingTest`,在现有录制上:战斗窗口 Ready、菜单窗口不 Ready、旧 `{3724}` Ready=0,全绿。
- [ ] `python -m bobsim run --device oneplus-10t` 在真机上 8 场景与旧 bash 结果一致 + `rst_enabled` 通过;产出 per-device 报告 + summary.md。
- [ ] `python -m bobsim run --all` 能跑矩阵、对离线设备显式 SKIPPED。
- [ ] `analyze-recording.py` 头部声明降级;`sim-*.sh` 在等价性验证后删除(独立 commit)。
- [ ] 模块 README 写明验证矩阵 + 两条诚实边界 + "手动确认 HS 秒跳"的操作清单。

---

## 10. 风险

| 风险 | 缓解 |
|---|---|
| Python 重写引入与 bash 不一致的回归 | §7:同设备新旧对跑、断言阈值逐条照搬、退役单独 commit 可回滚。 |
| 设备 shell 引号在 Python `subprocess` 下重现 bash 的坑 | §5.1:复刻 `_shq`/`_bob_broadcast` 策略,加一条"含 `{ } " 空格` 的 JSON 往返"自检。 |
| L1 录制过期被误读为"没问题" | §2 边界 + meta.json 新鲜度 + README 显式声明。 |
| L2 在某设备上 flaky(时序) | 沿用 bash 已加的等待/快照策略;flaky 阈值(如 spread<200ms)原样保留,不收紧。 |
| 录制数据双份漂移 | §4.2:源 `recordings/` 为归档、test/resources 为锁定 fixture;只有显式"刷新 fixture"动作才同步,正常不动。 |
