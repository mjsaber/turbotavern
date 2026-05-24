# Bob Assistant — Android App 设计文档

**Date**: 2026-05-24
**Revision**: v2 (post codex review)
**Status**: Draft (pending user approval)
**Platform**: Android 10+ (API 29+), arm64-v8a primary, armeabi-v7a 兜底
**Tech stack**: Kotlin + Jetpack Compose, mihomo Go core (gomobile .aar), Android VpnService
**License**: GPL-3.0 (受 mihomo core 传染)

---

## 1. Overview

Bob Assistant 是一款独立的 Android App，专门用于跳过《炉石传说》Battlegrounds 模式的战斗结算动画。

**核心机制**：在 Android 上启动 VpnService 接管炉石客户端的网络流量，由内嵌的 mihomo (Clash Meta) core 追踪所有出口连接的元数据；当玩家进入 BG 战斗时，mihomo 会观察到一条新的 socket（炉石客户端在每场战斗开局新建的会话连接，特征：`metadata.process == "com.blizzard.wtcg.hearthstone"` 且 `metadata.host == ""` 且 `network == "tcp"`，destination port 一般为 3724）。玩家点击悬浮按钮，App 通过 gomobile-bind Java API 调用 mihomo core 精确关闭这一条 socket。炉石客户端立即看到 EOF，触发"重连"代码路径；由于服务端已经结算完战斗结果，重连后客户端直接显示结果，60+ 秒的战斗动画被跳过。

**产品名**：Bob Assistant（致敬 BG 模式酒馆老板 Bob）

**目标用户（v1.0）**：
- 海外华人（中文 UI）
- 英文玩家（英文 UI）
- **不服务**中国大陆玩家（v1.0 不内置出口代理配置，大陆玩家无法在使用本 App 期间科学上网）

**发行渠道**：
- GitHub Releases 直接 APK 分发
- 未来：Samsung Galaxy Store + Amazon Appstore（合规渠道兜底）
- **不**投递 Google Play（政策风险见 §10）

**商业模式**：
- v1.0 免费 (验证 PMF)
- 商业化方向（基于 GPL-3.0 约束的现实选择）：**卖官方构建 + 服务**而非本地 DRM
  - 官方构建：签名 APK、自动更新、QA 测试
  - 增值服务：战术包订阅、对战数据分析、出口代理配置（针对大陆用户）
  - 不期待靠"license key 本地校验"作为护城河（GPL 约束下用户有权 fork 移除 license check，且二次分发合法）

---

## 2. Non-Goals (v1.0)

明确**不做**的事，避免 scope creep：

- ❌ 不支持国服 HS（包名/服务器/版本不同，留 v2.0）
- ❌ 不内置 outbound proxy 配置（大陆用户 v1.0 用不了）
- ❌ 不支持构筑模式 / 决斗模式 / 对决模式
- ❌ 不做自动拔线（v1.0 全程手动浮窗触发；自动检测留 v1.1+）
- ❌ 不做对战数据分析、卡牌追踪、replay 解析
- ❌ 不做账号系统 / 云同步 / 后端
- ❌ 不暴露外部 HTTP API / 不与 CMFA 或其他 mihomo 实例互操作
- ❌ 不做 Google Play 上架
- ❌ 不做本地 license key DRM（GPL 约束下意义不大）

---

## 3. Architecture

### 3.1 进程内组件图（单 foreground service 设计）

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Bob Assistant (单进程 APK)                                                │
│                                                                            │
│  ┌────────────────────┐                                                    │
│  │  UI Layer          │                                                    │
│  │  (Activity +       │                                                    │
│  │   Compose)         │  bind/intent                                       │
│  │                    ├──────────────────────┐                            │
│  │ - Onboarding       │                      │                            │
│  │ - MainActivity     │                      ▼                            │
│  │ - Settings/About   │       ┌────────────────────────────────────┐     │
│  └────────────────────┘       │  BobVpnService                     │     │
│                                │  (单一 foreground service，          │     │
│                                │   manifest type=specialUse)        │     │
│                                │                                     │     │
│                                │  ┌──────────────────────────────┐ │     │
│                                │  │ MihomoCore (gomobile .aar)   │ │     │
│                                │  │  - core lifecycle             │ │     │
│                                │  │  - protect() callback         │ │     │
│                                │  │  - connection table           │ │     │
│                                │  └──────────────┬───────────────┘ │     │
│                                │                  │                  │     │
│                                │  TUN fd (from VpnService.Builder)  │     │
│                                │                  │                  │     │
│                                │  ┌──────────────▼───────────────┐ │     │
│                                │  │ OverlayWindow                 │ │     │
│                                │  │ (Service 持有 WindowManager)  │ │     │
│                                │  │  - 浮窗按钮                   │ │     │
│                                │  │  - 战斗状态指示灯              │ │     │
│                                │  └──────────────────────────────┘ │     │
│                                │                                     │     │
│                                │  ┌──────────────────────────────┐ │     │
│                                │  │ ForegroundDetector            │ │     │
│                                │  │ (UsageStatsManager poll)     │ │     │
│                                │  └──────────────────────────────┘ │     │
│                                │                                     │     │
│                                │  ┌──────────────────────────────┐ │     │
│                                │  │ BattleConnectionController    │ │     │
│                                │  │ - select battle conn (safer)  │ │     │
│                                │  │ - kill                        │ │     │
│                                │  └──────────────────────────────┘ │     │
│                                │                                     │     │
│                                │  ┌──────────────────────────────┐ │     │
│                                │  │ NetworkChangeWatcher          │ │     │
│                                │  │ (ConnectivityManager.NC cb)   │ │     │
│                                │  └──────────────────────────────┘ │     │
│                                └────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────────┘

         ┌─────────────────────────────────────────────────────────────┐
         │ 炉石 (com.blizzard.wtcg.hearthstone, v35.4.241958+)         │
         │ 出口流量 → Android VpnService TUN → mihomo → DIRECT 出本机   │
         │ → 暴雪服务器                                                  │
         └─────────────────────────────────────────────────────────────┘
```

### 3.2 关键架构决策（rationale；★ = post-review 修订）

**D1: 内嵌 mihomo Go core，不依赖 CMFA 或其他 App**
- 用户只装一个 APK
- mihomo 通过 `gomobile bind -target=android` 输出 .aar
- Kotlin 调 gomobile 生成的 Java API（底层是 JNI，但用户不写 JNI 代码）
- 写一个**薄 Go wrapper package**：只 export 需要的几个函数，不要 bind 整个 mihomo（gobind 类型支持有限，panic 跨边界会直接 crash 进程）

**D2: ★ v1.0 fork mihomo 但保留 HTTP server，不做删瘦身**（codex review 反馈）
- 原计划"删 HTTP server"在产品稳定后再做。先内嵌完整 core，HTTP server 仍存在但只 listen 在 `127.0.0.1:0`（随机端口），不暴露给外部
- 优点：与上游 rebase 摩擦小；万一 Go API 路径有 bug，可以通过 HTTP fallback
- 用 Go wrapper 函数包出我们需要的几个 entry point，底层调 mihomo 现有的 connection manager

**D3: ★ profile 用 structured generator，不写死 raw YAML**（codex review 反馈）
- Kotlin 侧用 data class + YAML serializer (`com.charleskorn.kaml`) 生成 profile
- 保留少量 hidden/debug knobs（不在主 UI 暴露，但放 SharedPreferences + 隐藏的 debug screen）：
  - `tun.stack`: `mixed` (default) / `gvisor` / `system`
  - IPv6 on/off
  - DNS 模式：`system` (默认，避免隐私争议) / `doh` (debug only)
  - filter strategy 详见 §4.5
  - log-level

**D4: ★ 单 foreground service，合并 VpnService + Overlay + Detector**（codex review 反馈）
- 原 spec 是 3 个 service（BobVpnService、OverlayService、ForegroundDetector），codex 提醒会被部分 OEM 杀
- 改为单一 `BobVpnService extends VpnService`，内部持有 Overlay 的 WindowManager 引用和 Detector 的 coroutine
- foreground notification 一个就够，权限 + 兼容性都简单
- Manifest 声明 `android:foregroundServiceType="specialUse"` + property `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Hearthstone Battlegrounds animation skipper" />`（Android 14+ 要求）

**D5: ★ 不把 Bob 自身加入 `addAllowedApplication`**（codex review 坚决反对原方案）
- 原 spec 错误地把 `addAllowedApplication(packageName)` 加进去 → 会造成 self-loop
- 改：v1.0 只 `addAllowedApplication("com.blizzard.wtcg.hearthstone")`
- App 自己的网络访问（License 校验 / 更新检查 / 反馈上传，未来才有）走系统默认网络，不进 TUN
- 若未来需要 App 自身流量走 TUN，必须先实现 `VpnService.protect(socket)` 回调（mihomo Go 端 → Kotlin 端 → `VpnService.protect()`），见 §4.1

**D6: 用 UsageStatsManager 检测前台 App（降级为 optional）**（codex review 反馈）
- HS 不在前台时 hide 浮窗、停止 connection 轮询
- 需要 `PACKAGE_USAGE_STATS` 权限（用户手动到设置开）
- **降级**：权限拒绝时浮窗常驻显示，功能依然可用；不作为核心依赖

**D7: ★ 监听 ConnectivityManager，处理双卡/Wi-Fi 切换**（codex review 反馈）
- 注册 `ConnectivityManager.NetworkCallback`
- 默认网络变化时：通知 mihomo（重启或刷新出口路由）；通知用户战斗 socket 状态可能丢失

**D8: ★ IPv6 显式处理**（codex review 反馈）
- v1.0 选择：**禁用 IPv6**——VpnService.Builder 不 `addAddress(v6)` / 不 `addRoute("::/0")`
- 若 HS 实际走 IPv6，可能漏抓战斗 socket；prototype 阶段必须验证（见 §12 Open Q）
- 若 prototype 发现 HS 用 IPv6，则改为 `addAddress("fd00:bob::1", 64) + addRoute("::/0", 0)` 并验证 mihomo IPv6 path
- 不做"让 IPv6 流量走系统默认"——VpnService 配置后 IPv6 流量在 allowed app 上要么进 TUN 要么完全 block，不能旁路

**D9: ★ DNS：默认走系统 DNS，不走第三方 DoH**（codex review 反馈：与隐私文案冲突）
- v1.0 profile：`dns.enable: false` 或 `nameserver: [system]`（沿用系统 DNS）
- 这样隐私文案"不上传任何流量到第三方"无矛盾
- 副作用：mihomo connection table 里 host 字段可能更少填上（依赖 sniffer，但我们已关 sniffer）——不影响战斗 socket 检测（战斗 socket 本来就是 host=""）
- DoH 选项放 debug knobs（D3），不默认开

---

## 4. Component Breakdown

每个组件单一职责，可独立测试。

### 4.1 `MihomoCore` (Kotlin facade + Go .aar)

**职责**：封装内嵌 mihomo core 的生命周期与 API。

**Go 端 Wrapper Package 设计**：
新建一个 Go module，`bobcore/`，依赖 fork 的 mihomo，export 以下函数：

```go
// bobcore/bobcore.go

package bobcore

// Init 初始化 mihomo，profile path 为 APK 解压到 cacheDir 的 yaml
func Init(profilePath string) error

// SetProtector 注册 socket protect callback，Kotlin 侧实现
//   Go 内部所有 net.Dial 之前会 call protectorFd(fd int) error
func SetProtector(p Protector)

// Start 接管 TUN，开始转发
func Start(tunFd int) error

// Stop 停止 mihomo，关闭所有 connections，清理 goroutines
func Stop() error

// Shutdown 彻底清理（idempotent），Init 之后无法再 Init 必须 Shutdown
func Shutdown() error

// Connections 返回 JSON-encoded connection table
func Connections() []byte

// CloseConnection 精确关一条 connection
//   返回值含义见 Kotlin 端 CloseResult enum
func CloseConnection(id string) int

// Status 返回 core 状态 JSON：{running, version, lastError, tunFdValid}
func Status() []byte

// LogTail 返回最近 N 行 log
func LogTail(n int) []byte

// SetLogLevel: "error" / "warning" / "info" / "debug"
func SetLogLevel(level string)
```

`Protector` 是一个 Go interface，Kotlin 端实现，Go 通过 gomobile reverse bind 调用：

```go
type Protector interface {
    Protect(fd int) bool
}
```

**Kotlin 端 facade**：

```kotlin
object MihomoCore {
    fun init(context: Context): Result<Unit>
    fun setProtector(vpnService: VpnService)  // 内部把 VpnService.protect 接到 Go Protector
    fun start(tunFd: Int): Result<Unit>
    fun stop(): Result<Unit>
    fun shutdown(): Result<Unit>
    fun connections(): List<Connection>
    fun closeConnection(id: String): CloseResult
    fun status(): CoreStatus
    fun logTail(n: Int = 100): List<String>
    fun setLogLevel(level: LogLevel)
}

data class Connection(
    val id: String,
    val process: String,
    val host: String,
    val destinationIP: String,
    val destinationPort: Int,
    val network: String,         // "tcp" / "udp"
    val createdAt: Long,         // epoch ms
)

sealed class CloseResult {
    object Success : CloseResult()
    object NotFound : CloseResult()
    object AlreadyClosed : CloseResult()
    object CoreStopped : CloseResult()
    data class InternalError(val message: String) : CloseResult()
}

data class CoreStatus(
    val running: Boolean,
    val version: String,
    val tunFdValid: Boolean,
    val lastError: String?,
)
```

**TUN fd ownership**：
- Kotlin 侧 `VpnService.Builder.establish()` 返回 `ParcelFileDescriptor`
- 调 `pfd.detachFd()` 拿到 raw int，**所有权交给 Go**；Kotlin 不再 close 这个 pfd
- Go 端在 `Stop()` / `Shutdown()` 时 close 该 fd
- 注意：establish() 返回的 fd 默认 non-blocking；mihomo 的 TUN stack 接受这种 fd 需要 prototype 验证（§12）

**编译命令**（Phase 0 实际固化在 `android/bobcore/build-aar.sh`，详见 `android/bobcore/PINNED-VERSIONS.md`）：
```bash
# 在 bobcore/ 目录
go tool golang.org/x/mobile/cmd/gomobile bind \
  -target=android/arm64 \
  -androidapi 29 \
  -javapkg com.bobassist.gomobile \
  -ldflags="-s -w" -trimpath \
  -o ../overlay-app/app/libs/bobcore.aar \
  .
# Phase 1 再加 `,android/arm` (armv7-a) 进 target 列表
```

**Gradle 引入**：`implementation files('libs/bobcore.aar')`

**测试**：
- 单元测试：mock gomobile facade，验证 Kotlin 层 JSON 解析、CloseResult 映射
- Prototype 阶段集成测试（见 §11 Phase 0）：起一个 hello-world Android App，启动 mihomo，访问 example.com，验证 connection table 出现 example.com

### 4.2 `BobVpnService` (Android VpnService)

**职责**：唯一持有 VpnService、唯一持有 mihomo core、唯一持有浮窗 WindowManager 的 single foreground service。

**生命周期**：
- `onStartCommand(START_STICKY)` → `Builder.establish()` 拿到 fd → `MihomoCore.setProtector(this)` → `MihomoCore.start(fd)` → 启动浮窗 + ForegroundDetector + NetworkChangeWatcher
- `onDestroy()` → 反向清理

**Builder 配置**：
```kotlin
Builder()
    .setSession("Bob Assistant")
    .addAddress("10.99.0.1", 30)
    // .addAddress(...IPv6) ← v1.0 不加，见 D8
    .addRoute("0.0.0.0", 0)
    // .addRoute("::/0", 0) ← v1.0 不加
    .addDnsServer("8.8.8.8")           // 形式上需要一个；实际用系统 DNS（profile 里 dns.enable: false）
    .setMtu(1500)
    .addAllowedApplication("com.blizzard.wtcg.hearthstone")
    // .addAllowedApplication(packageName) ← v1.0 不加，见 D5
    .setBlocking(false)
    .establish()
```

**Foreground notification**：
- 通知标题："Bob Assistant"
- 副标题：动态——"等待炉石启动" / "炉石已就绪" / "战斗中可拔线"
- 通知 action 按钮："停止"
- Manifest 声明：
  ```xml
  <service
      android:name=".vpn.BobVpnService"
      android:foregroundServiceType="specialUse"
      android:permission="android.permission.BIND_VPN_SERVICE"
      android:exported="false">
      <property
          android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
          android:value="Bob Assistant: Battlegrounds animation skipper" />
      <intent-filter>
          <action android:name="android.net.VpnService" />
      </intent-filter>
  </service>
  ```

**测试**：
- 真机：启动 service → 看到通知栏图标 → `adb shell dumpsys vpn` 应显示 Bob Assistant 持有 vpn
- 真机：`adb shell ps -A | grep bob` → 单一进程
- 真机：切回桌面 5 分钟，service 不被杀（在主流 ROM 上）

### 4.3 `OverlayWindow` (浮窗，组件 in BobVpnService)

**职责**：浮窗 UI 渲染 + 用户点击事件分发。

**权限**：`SYSTEM_ALERT_WINDOW`（用户在 Settings 手动开）

**Window 配置**：
- `TYPE_APPLICATION_OVERLAY` (API 26+)
- 默认位置：屏幕右上角
- 用户可拖动改位置，位置存 SharedPreferences

**浮窗状态 (3 色指示灯)**：
| 状态 | 颜色 | 触发条件 | UX 提示 |
|---|---|---|---|
| Idle | 灰色 | HS 前台但没检测到战斗 socket | 浮窗按钮可点击但 tap 无效果 + Toast |
| Ready | 黄色脉冲 | 检测到唯一/可识别的战斗 connection | 浮窗按钮高亮，建议点击 |
| Cooldown | 红色（短暂 2s） | 刚拔过 | 不可点击 |
| Permission warning | 红色叹号 | mihomo 未启动 / 权限缺失 | 点击拉起 MainActivity |

Ready state 是 v1.0 的核心 UX：在没有自动拔线的情况下，让用户**清晰地看到"现在可以拔了"**，避免靠用户自己猜时机。

**轮询机制**：
- HS 在前台时，每 800ms 调一次 `MihomoCore.connections()`
- HS 不在前台时，停止轮询（省电）
- 单次 connections() 调用预期 < 5ms（in-process call）

**点击行为**：
- tap → `BattleConnectionController.killBattleSocket()`
- Success → Toast "已拔线" + 1 次震动 + Cooldown 状态 2s
- NoBattleConnection → Toast "没找到战斗会话，可能战斗未开始或已结束"
- 其他错误 → Toast "拔线失败：${reason}"

**测试**：
- Service 起来后浮窗显示
- 模拟 connection 列表注入 → 指示灯变色
- tap → 验证 controller 被调

### 4.4 `ForegroundDetector` (前台检测，可选)

**职责**：检测当前前台 App 是不是炉石；状态变化时通知 OverlayWindow 与 BattleConnectionController。

**实现**：
- `UsageStatsManager.queryEvents(now - 5s, now)`
- 取最新的 `ACTIVITY_RESUMED` 事件，判断 `packageName == "com.blizzard.wtcg.hearthstone"`
- 每 2 秒 poll 一次

**权限**：`PACKAGE_USAGE_STATS`（特殊权限，必须用户去 Settings 开）

**降级行为（D6）**：
- 权限拒绝时，`isHearthstoneForeground` 永远为 true（即浮窗常驻）
- 不阻止 App 启动 / 不阻止拔线功能

**对外 API**：
```kotlin
class ForegroundDetector(context: Context) {
    val isHearthstoneForeground: StateFlow<Boolean>
    fun start()
    fun stop()
}
```

### 4.5 `BattleConnectionController` (核心业务逻辑)

**职责**：把"用户想跳过当前战斗动画"翻译成"找出并关闭正确的 connection"。重命名自 v1 的 `KillService`（避免与 Android Service 概念混淆）。

**核心 API**：
```kotlin
class BattleConnectionController(private val core: MihomoCore) {
    suspend fun snapshot(): BattleSocketState
    suspend fun killBattleSocket(): KillResult

    sealed class BattleSocketState {
        object NotFound : BattleSocketState()
        data class UniqueCandidate(val conn: Connection) : BattleSocketState()
        data class MultipleCandidates(val conns: List<Connection>) : BattleSocketState()
    }

    sealed class KillResult {
        data class Success(val closedConn: Connection) : KillResult()
        object NoBattleConnection : KillResult()
        data class Ambiguous(val candidates: List<Connection>) : KillResult()
        data class Failure(val reason: String) : KillResult()
    }
}
```

**★ Safer Selector（codex review 反馈）**：

不再用 `firstOrNull { process==HS && host=="" }`，改为多条件评分：

```kotlin
fun selectBattleSocket(all: List<Connection>): BattleSocketState {
    val candidates = all.filter { c ->
        c.process == "com.blizzard.wtcg.hearthstone"
            && c.host == ""
            && c.network == "tcp"
            && c.destinationPort !in EXCLUDED_PORTS    // 排除已知主会话端口
    }

    return when {
        candidates.isEmpty() -> BattleSocketState.NotFound
        candidates.size == 1 -> BattleSocketState.UniqueCandidate(candidates[0])
        else -> {
            // 优先选 port == 3724 的；其次选最新的（createdAt 最大）
            val port3724 = candidates.filter { it.destinationPort == 3724 }
            when {
                port3724.size == 1 -> BattleSocketState.UniqueCandidate(port3724[0])
                port3724.isNotEmpty() -> BattleSocketState.MultipleCandidates(port3724)
                else -> BattleSocketState.MultipleCandidates(candidates.sortedByDescending { it.createdAt })
            }
        }
    }
}

// 排除已知 HS 主会话端口（防误杀）
private val EXCLUDED_PORTS = setOf(443, 1119, 80)
```

**Ambiguous 处理**：
- 浮窗在多候选状态时显示橙色（与 yellow 区分）
- tap 时优先 kill port 3724 的；其他候选 log 到诊断 buffer
- v1.0 不让用户手动选哪条（避免 UI 复杂）

**测试**：
- 单元测试：构造各种 connection list fixture，断言 selector 输出
- 集成：真机进 BG 战斗 → snapshot 检查 candidates → kill → 战斗动画跳过

### 4.6 `NetworkChangeWatcher` (新增组件)

**职责**：监听网络变化（双卡切换、Wi-Fi ↔ 移动数据、飞行模式恢复），通知 mihomo 刷新出口路由。

**实现**：
```kotlin
class NetworkChangeWatcher(
    private val context: Context,
    private val onDefaultNetworkChanged: () -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { onDefaultNetworkChanged() }
        override fun onLost(network: Network) { onDefaultNetworkChanged() }
        override fun onCapabilitiesChanged(...) { ... }
    }

    fun start() = cm.registerDefaultNetworkCallback(callback)
    fun stop() = cm.unregisterNetworkCallback(callback)
}
```

**触发的响应**：
- mihomo 内部本来就监听 routing table 变化，但内嵌场景下 Go runtime 看到的网络信息可能滞后
- 收到回调后调 `MihomoCore.refreshInterface()`（新增的 Go API）
- 短期内（5s 内）发生连续切换时 debounce

### 4.7 UI Activities

#### `OnboardingActivity` (首次启动)

6 步引导（增加一步：解释 GPL 与不上 Play Store）：

1. 欢迎页 — "Bob Assistant 帮你跳过战棋结算动画"
2. 解释技术原理 — "我们会要求 VPN 权限来观察你的网络连接"
3. **隐私 + 开源声明** — "本 App 开源 (GPL-3.0)，不收集任何数据。源码：github.com/mjsaber/bob-assist"
4. 授权 VpnService
5. 授权 SYSTEM_ALERT_WINDOW
6. 授权 PACKAGE_USAGE_STATS（明确可选）

每一步都允许 "稍后"，但 step 4 拒绝则核心功能完全不可用、UI 会一直引导用户回来开。

#### `MainActivity` (主页)

- 大开关：启动/停止 VPN（同时启动/停止 Overlay 等子组件）
- 状态卡片（每项可点击查看详情）：
  - VPN 状态
  - 浮窗权限
  - 使用情况权限（可选标记）
  - HS 检测状态
  - 战斗 socket 检测
- 底栏入口：设置 | 诊断 | 关于
- ~~今日拔线次数~~ → **砍掉**（codex 建议；非必要 UI，且数据持久化会增加复杂度）

#### `SettingsActivity`

- 语言切换（中文/English）
- 浮窗大小（小/中/大）
- 浮窗位置（拖动后保存的坐标，可"恢复默认"）
- ~~自动启动 / boot 启动~~ → **砍掉**（codex 建议；v1.1 再加）

#### `DiagnosticsActivity` (新增，codex 建议)

- mihomo 状态 (running/version/lastError)
- 当前 connection table snapshot（实时刷新）
- 最近 100 行 mihomo log
- "Export Diagnostics" → 打包 zip → share intent
- 重要 debug knob：DNS mode 切换（system/DoH）、IPv6 toggle、log level

#### `AboutActivity`

- 版本号
- 开源声明 + 源码地址
- GPL-3.0 全文（assets 内嵌）
- 致谢：z2z63、mihomo 上游、CMFA
- 隐私政策：明确"不收集任何数据，不上传任何流量到第三方"——本句必须与 default profile 配置一致（D9：默认走系统 DNS）

---

## 5. Key Flows

### 5.1 首次启动 Onboarding（6 步，见 §4.7）

### 5.2 日常使用 (一次拔线)

```
1. App 已运行（VpnService 持有 mihomo + Overlay + Detector + NetWatcher）
2. 用户启动炉石
3. ForegroundDetector 检测到 HS 前台 → Overlay show + 800ms 轮询启动
4. 用户进 BG 主菜单 → 进战斗
5. 战斗开始，HS 客户端新建 socket → mihomo connection table 出现新条目
   → BattleConnectionController.snapshot() 命中 UniqueCandidate / Ambiguous
   → Overlay 变 Yellow (Ready) / Orange (Ambiguous)
6. 用户点浮窗
   → killBattleSocket() → MihomoCore.closeConnection(id) → CloseResult.Success
   → Toast + 震动 + Cooldown 2s
   → HS 看到 EOF → 重连 → 跳过动画
```

### 5.3 网络变化时

```
1. NetworkChangeWatcher.onDefaultNetworkChanged()
2. MihomoCore.refreshInterface() → mihomo 内部清 routing cache，按新 default network 重建 outbound
3. 已有 connections 中所有 DIRECT outbound 会被刷掉（被动 EOF）
   - 副作用：如果用户此刻在战斗中，战斗 socket 自然 EOF → 也跳过动画（无意中提前拔）
   - 这个副作用 v1.0 可接受
```

### 5.4 异常流（详见 §8）

---

## 6. Data Flow

### 6.1 炉石流量路径（仅 HS 流量进 TUN）

```
HS App
  ↓ TCP/UDP packet (IPv4 only at v1.0)
Linux kernel (Android)
  ↓ allowed-app filter → 仅炉石包名的流量被路由到 TUN
TUN fd (held by mihomo core via gomobile)
  ↓ packet read
mihomo TUN stack (mixed mode)
  ↓ Layer 4 reassembly
mihomo connection manager (本地 connection table 注册一个 entry)
  ↓ rule matching → MATCH, DIRECT
mihomo direct outbound: net.Dial(...)
  ↓ ⚡ Go Protector.Protect(fd) → Kotlin VpnService.protect(fd)
      └─ fd 被标记 "bypass VPN"，不会回到 TUN
  ↓ syscall connect()
Kernel (default network = wifi/cell)
  ↓
暴雪服务器
```

**关键**：
- 只有炉石的流量进 TUN（D5 `addAllowedApplication`）
- mihomo 自身的 outbound `net.Dial` 必须先 `VpnService.protect(fd)`（D5 + 4.1 Protector），否则 self-loop
- 系统 DNS：HS 进程发起的 DNS query 也会被 TUN 接管（因为 HS 进了 allowed app），由 mihomo `dns.enable: false` 时透传到系统

### 6.2 拔线协议

```
User tap floating button
  ↓
OverlayWindow.onClick()
  ↓
BattleConnectionController.killBattleSocket()
  ↓
MihomoCore.connections() → List<Connection>
  ↓
selectBattleSocket() → UniqueCandidate / MultipleCandidates
  ↓
若 UniqueCandidate：MihomoCore.closeConnection(id) → CloseResult
若 MultipleCandidates：选 port==3724 优先，否则取 newest
  ↓
Go side: connMgr.Lookup(id).Close()
  ↓
mihomo: TUN write FIN/RST → kernel deliver EOF 给 HS
  ↓
HS: socket read returns 0 → 触发 reconnect logic
  ↓
HS reconnect → server returns battle result → 跳过动画
```

---

## 7. UI Screens (low-fidelity)

### Main Screen

```
┌──────────────────────────────────────┐
│  Bob Assistant         [设置] [诊断] │
├──────────────────────────────────────┤
│                                       │
│         ┌──────────────────┐         │
│         │      [大开关]     │         │
│         │       启动        │         │
│         └──────────────────┘         │
│                                       │
│   状态：未启动                        │
│                                       │
│   ✓ VPN 已授权                        │
│   ⚠ 浮窗权限未授权 →                  │
│   ⊙ 使用情况未授权（可选）            │
│                                       │
│   ────────────────────────           │
│   炉石未运行                          │
│   (启动炉石后浮窗自动显示)              │
│                                       │
└──────────────────────────────────────┘
```

### Overlay (running 时)

```
        ┌────────┐
        │   ●    │  ← 圆形浮窗，半透明
        │   BG   │   ● Gray idle / Yellow ready / Orange ambiguous / Red cooldown
        └────────┘
```

文字 "BG" 是品牌识别（避开 Blizzard 商标）；fallback 用图标。

---

## 8. Error Handling

| 错误场景 | 处理 |
|---|---|
| VPN 授权被拒 | Onboarding 标红，主开关不能开；MainActivity 显示"无法启动" |
| 浮窗权限被拒 | 主开关可启 VPN，但浮窗不显示；UI 提示 |
| Usage Stats 权限被拒 | 浮窗常驻显示；不影响拔线（D6 降级） |
| mihomo init 失败 | 不启 VpnService，弹错误对话框 + 一键导出 diagnostics |
| mihomo crash (Go panic / native) | 自动重启最多 3 次/10 分钟；超阈值通知用户"请重启 App" |
| `closeConnection` AlreadyClosed | snapshot 再尝试一次；仍失败则 Toast |
| `closeConnection` NotFound | 该 connection 已被自然清理；Toast "战斗会话已结束" |
| HS 不在前台但用户点浮窗（权限漏洞） | 仍执行 kill（不依赖前台） |
| VpnService 被其他 VPN 抢占 | foreground notification 状态更新，主开关回 OFF；Toast 提示 |
| BattleConnectionController.Ambiguous | 自动选 port 3724，log 到 diagnostics buffer |
| 网络变化 (双卡/WiFi 切换) | NetworkChangeWatcher 触发 refresh；用户感知最小化 |
| IPv6 流量被 HS 使用（v1.0 不接管） | prototype 阶段必须验证；v1.0 假设 HS Android 国际服走 IPv4 |

---

## 9. Permissions

| 权限 | 类别 | 用途 | 必需性 |
|---|---|---|---|
| `INTERNET` | normal | mihomo direct outbound | 必需 |
| `FOREGROUND_SERVICE` | normal | VpnService 跑 foreground | 必需 |
| `FOREGROUND_SERVICE_SPECIAL_USE` (API 34+) | normal | Android 14+ specialUse type | 必需 |
| `POST_NOTIFICATIONS` (API 33+) | runtime | foreground notification | 必需 |
| `BIND_VPN_SERVICE` | signature | declared in manifest | 必需 |
| `SYSTEM_ALERT_WINDOW` | special | 浮窗 | 强烈推荐 |
| `PACKAGE_USAGE_STATS` | special | 前台检测 | **可选**（D6 降级） |
| ~~`QUERY_ALL_PACKAGES`~~ | ~~normal (sensitive)~~ | ~~检测炉石是否安装~~ | **删除** — 改用 `<queries><package android:name="com.blizzard.wtcg.hearthstone"/></queries>` |
| `ACCESS_NETWORK_STATE` | normal | NetworkChangeWatcher | 必需 |
| ~~`RECEIVE_BOOT_COMPLETED`~~ | | v1.1 才加 | 不在 v1.0 |

---

## 10. Compliance & Risks

### 10.1 法律与平台

- **GPL-3.0 合规**：
  - 必须开源，源码地址：github.com/mjsaber/bob-assist
  - 二进制分发（APK）时附 corresponding source 链接 (GitHub Releases 同一 tag)
  - License 全文随 App 提供（AboutActivity 内嵌）
  - 修改 mihomo 时保留 upstream copyright；fork 公开
  - GPL 不阻止商业售卖，但用户有权 modify + redistribute → **本地 license DRM 在 GPL 下意义不大**
- **Google Play 不可上架**：见 §10 之前讨论
- **Blizzard 商标 / EULA**：
  - 不使用 "Hearthstone" / "Battlegrounds" / "Blizzard" 商标在 App 名称、商店描述
  - 不打包 / 截图 HS 美术资源
  - README 明示"使用风险自担"——Blizzard EULA 第 1.B.iii 禁止 "third-party software that intercepts or modifies the Blizzard service"。本 App 不修改游戏数据 / 不读内存 / 不发包，只关 socket，但用户使用本 App 仍可能被 Blizzard 单方面认定违反 EULA
- **隐私政策（v1.0）**：
  - "本 App 不收集任何用户数据，不上传任何流量到第三方服务器"
  - 必须与 default profile 配置（D9：系统 DNS）一致，否则文案与实际不符
  - 反馈 / 更新检查也走系统网络（不进 TUN），明确告知

### 10.2 技术风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| HS 客户端版本更新后改变战斗 socket 特征 | 拔线失效 | DiagnosticsActivity 内嵌"导出 connection snapshot"功能；用户反馈 |
| OEM 杀后台 (小米/华为/OPPO/vivo) | foreground service 被停 | README 中提供各家电池白名单设置教程；监控通知重启行为 |
| Android 16+ 进一步限制 VpnService | 兼容性 break | minSdk 29、targetSdk 跟随当年最新；持续维护 |
| gomobile 在某些 ROM 上 crash | 启动失败 | 本地 logger + Diagnostics 导出；崩溃在 cacheDir 留 dump |
| mihomo TUN 处理 non-blocking fd 异常 | 启动失败 | Phase 0 prototype 必须验证；必要时 fallback to blocking mode |
| TUN fd double-close | Native crash | detachFd() 明确所有权转 Go；Kotlin 不持有 pfd |
| Go Protector 回调 deadlock | 网络全断 | Kotlin 端 protect() 调用必须非阻塞；timeout 1s 后强制 return false |
| HS 使用 IPv6 而我们没接管 | 拔线失效 | Phase 0 验证；若需要则启 IPv6 path |
| Wi-Fi ↔ 移动数据切换 | mihomo outbound 失效 | NetworkChangeWatcher + refreshInterface |
| 双 VpnService 冲突 | 用户用不了我们 | UI 明确告知"开启会停止其他 VPN" |

### 10.3 产品/市场风险

- 桌面端有 5+ 免费替代方案 → UX 必须明显胜过 GitHub 开源工具
- 移动端是空白市场 → first-mover 优势，但也意味着没有用户教育
- 国服用户被排除 → 失去最大潜在市场，需要 v1.x 加 outbound proxy 支持
- GPL 约束下本地 DRM 不可行 → 商业化靠官方构建 + 服务

---

## 11. ★ Implementation Phasing (post-review 新增)

codex review 提示："MVP 偏大，真正未知最大的是 core，不是 UI"。采纳：在写 UI 前先做 core prototype。

### Phase 0 — mihomo .aar Prototype（1-2 周）

**目标**：独立验证最大未知，无 UI。

- 创建 `bobcore/` Go module，依赖 fork mihomo
- 写 Go wrapper：Init/Start/Stop/Connections/CloseConnection/Status/SetProtector
- `gomobile bind` 编出 `bobcore.aar`
- 写一个 minimal "hello world" Android App：
  - 一个 button "Start VPN"
  - VpnService 起来后调 MihomoCore.start()
  - log connection table 每 2 秒
  - 一个 button "Close first HS connection"
- 真机验证：
  - 启动 HS 国际服 → connection table 是否能看到 HS 流量
  - 进 BG 战斗 → host=="" 的 socket 是否如预期出现
  - close 该 socket → HS 是否如期跳过动画
  - 切换 Wi-Fi/移动数据时行为
  - IPv6 是否被 HS 使用（关键开放问题）

**Exit criteria**：上述 5 个真机场景全部通过。

### Phase 1 — Product MVP（4-6 周）

- 完整 Onboarding / MainActivity / Settings / Diagnostics / About
- OverlayWindow + Ready/Ambiguous/Cooldown 状态
- BattleConnectionController safer selector
- NetworkChangeWatcher
- i18n (中文 + English)
- 真机矩阵测试（Pixel / Samsung / OnePlus / Xiaomi / OPPO）
- GitHub Releases CI

### Phase 2 — Polish & 发布（2-3 周）

- 真实用户 beta（10-20 人小范围邀请）
- 反馈迭代
- v1.0 公开发布

### Phase 3+ (后续版本)

按优先级：
1. **自动拔线** (v1.1)
2. **快捷方式** (v1.1) — Quick Settings Tile
3. **大陆用户支持** (v2.0) — 内置 outbound proxy 订阅配置
4. **国服支持** (v2.0)
5. **战术包订阅** (v2.x) — 商业化增值
6. **Wear OS companion** (v3.x)

---

## 12. Open Questions

需要在 Phase 0 prototype 阶段或具体实现中解决：

1. **mihomo Go core 接受 non-blocking TUN fd 是否需改造**：establish() 默认 non-blocking，mihomo 上游 TUN stack 是否能直接吃？需 prototype 验证；若不行则 `setBlocking(true)` 或 dup fd 自己包装
2. **战斗 socket 检测判据稳定性**：`host=="" && process==HS && tcp && port==3724` 在不同 ROM/Android 版本是否一致？Phase 0 多机型验证
3. **HS Android 是否使用 IPv6**：若是，v1.0 选择 disable IPv6 会导致战斗 socket 漏抓 → 必须改为接管 IPv6
4. **TUN stack 选择**：`mixed` (default) / `gvisor` / `system`，Phase 0 选最稳的
5. **APK 体积**：mihomo .so (arm64) 约 8-12 MB + Kotlin/Compose runtime → 估计 15-20 MB；考虑 split APKs (arm64 / armv7)
6. **Onboarding 直观度**：是否需要权限引导动画 / 视频？v1.0 beta 后看反馈
7. **gomobile reverse bind (Go 调 Kotlin) 性能**：Protector 回调每次出口 socket 都触发，量是否过大？Phase 0 测一下
8. **mihomo HTTP server 监听 127.0.0.1:0 是否会被任何静态安全扫描标红**：APK 第三方扫描 (e.g. Pithus)；如果有问题再瘦身
9. **HS 反作弊**：Battle.net Authentication 是否检测客户端层异常？理论上不该，但需观察 beta 用户是否封号
10. **Diagnostics 导出的数据脱敏**：connection table 含 destination IP；隐私文案需明示用户导出诊断时会包含这些

---

## 13. References

### 桌面端实现
- z2z63/hearthstone_skipper (macOS): https://github.com/z2z63/hearthstone_skipper
- mjsaber/hearthstone_skipper fork: https://github.com/mjsaber/hearthstone_skipper
- Breekys/Hearthstone_Battlegrounds_Skip: https://github.com/Breekys/Hearthstone_Battlegrounds_Skip
- haoruan/HDT-Reconnector: https://github.com/haoruan/HDT-Reconnector

### Android VPN / Go 实现参考
- ClashMetaForAndroid (CMFA): https://github.com/MetaCubeX/ClashMetaForAndroid
- mihomo upstream: https://github.com/MetaCubeX/mihomo
- gomobile bind docs: https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile
- Android VpnService docs: https://developer.android.com/develop/connectivity/vpn
- Android FGS types: https://developer.android.com/develop/background-work/services/fgs/service-types

### 政策与渠道
- Google Play VpnService policy: https://support.google.com/googleplay/android-developer/answer/12564964
- Google Play Device and Network Abuse: https://support.google.com/googleplay/android-developer/answer/16559646
- Lemon Squeezy license keys: https://docs.lemonsqueezy.com/help/licensing/generating-license-keys
- GPL-3.0: https://www.gnu.org/licenses/gpl-3.0.html

### 本仓库
- 跨平台原理: `docs/how-it-works.md`
- 失败路径汇总: `docs/failed-paths.md`
- Android 验证 setup: `android/cmfa/setup.md`
- macOS 端: `mac/README.md`

---

## 14. ★ Codex Review Summary

本 spec 的 v2 修订基于 OpenAI Codex CLI (gpt-5.5) 在 2026-05-24 的 review (`/tmp/codex-review-bob-android.md`)。主要采纳的修订点：

| Review Issue | v1 状态 | v2 处理 |
|---|---|---|
| `addAllowedApplication(packageName)` self-loop | bug | D5 删除，加 Protector callback |
| API 不完整 (cleanup/status/logger/error enum) | 5 函数 | §4.1 扩展为完整 facade |
| 三个 service 合并 | 3 service | D4 单一 BobVpnService |
| profile 写死 vs structured | 写死 raw YAML | D3 structured generator + hidden knobs |
| DNS 隐私冲突 | doh.pub / dns.google | D9 默认 system DNS |
| IPv6 遗漏 | 没提 | D8 显式禁用 + prototype 验证 |
| 双卡/Wi-Fi 切换 | 没处理 | §4.6 NetworkChangeWatcher |
| TUN fd ownership | 没说 | §4.1 detachFd 明确所有权 |
| safer filter selector | firstOrNull 风险 | §4.5 candidate scoring |
| KillService 命名 | 不准 | 改为 BattleConnectionController |
| UsageStats 必需 → 可选 | 必需 | D6 降级为 optional |
| Ready state UX | 模糊 | §4.3 Yellow/Orange/Cooldown 明确 |
| QUERY_ALL_PACKAGES | 申请 | §9 删除，改 manifest queries |
| 本地 license DRM 长期 | 长期方向 | §1 改为"卖官方构建+服务" |
| MVP 范围 | 一次性 | §11 Phase 0 prototype + Phase 1 MVP |

未采纳：
- Codex 建议 "Tech MVP 先做 Overlay + CMFA HTTP API" — 用户明确要求 v1.0 内嵌 mihomo 不依赖 CMFA，所以跳过这个中间层，但用 Phase 0 prototype 隔离技术风险
- Codex 建议 "fork mihomo 但不删 HTTP server" — 部分采纳（D2 v1.0 保留，但 listen 在 127.0.0.1:0；瘦身留 v1.x 之后）
