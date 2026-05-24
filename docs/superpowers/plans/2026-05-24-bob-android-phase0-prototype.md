# Bob Assistant Android — Phase 0 Prototype Implementation Plan

> **Revision**: v2 (post codex review — restructured into spike-driven plan)
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 1-2 周内独立验证 "embedded mihomo + Android VpnService + 精确关闭炉石战斗 socket" 的端到端链路。**Phase 0 不是产品**：没有浮窗、没有 i18n、没有完整 onboarding。产出三样：(1) 一个能在 Android arm64 link 进 APK 的 mihomo 封装库；(2) 一个最简 VpnService + 一屏 Compose UI 的验证 app；(3) 五个真机 scenario 的实测报告。

**Architecture:**
- `android/bobcore/` Go module，依赖 **pinned** mihomo (`github.com/metacubex/mihomo@<tag>`)，写薄 wrapper 暴露 5 个函数
- Build pipeline：**Spike A 决定**最终用 gomobile bind 还是 CMFA 同款 `golang-android` Gradle plugin + CMake（codex review 提醒 CMFA 走的不是 gomobile）
- `android/overlay-app/` Android Studio Kotlin project，引入 bobcore native bundle，提供单 Activity + VpnService 验证 UI
- 验证脚本与报告：`android/overlay-app/scripts/`

**Tech Stack:**
- Go 1.22+；mihomo pinned to a specific tag (Spike A 选定)
- Kotlin 2.0+，Jetpack Compose 1.7+，AGP 8.7+
- minSdk 29, targetSdk 35, arm64-v8a only (Phase 0；armv7 留 Phase 1)
- 测试设备：OnePlus 10T (CPH2451, Android 15) 为 primary

**Non-Goals (Phase 0)**:
- ❌ 不做 OverlayWindow / Onboarding / Settings / About / i18n
- ❌ 不做 ForegroundDetector / NetworkChangeWatcher 完整实现 (NetworkChangeWatcher 留到 Phase 1)
- ❌ 不做 license / release CI / split APKs / armv7 build
- ❌ 不做完整 `BattleConnectionController` (Phase 0 用一个简化版 selector 即可，但**不**用 `firstOrNull host==""`，见 Task 11)
- ❌ 不接管 IPv6 (Spike D 期间观察 HS 是否实际使用 IPv6；若使用则 Phase 0 末扩展接管)

---

## ★ 结构变更 (v1 → v2)

v1 是 12 个线性 task；codex review 指出大量代码是 "wishful thinking that won't compile"。v2 改为 **spike-driven**：每个 spike 有量化 exit criteria，spike 通过才写下游代码。

```
Spike A (build pipeline)         Android Studio skeleton (并行)
       │                                  │
       ▼                                  │
Spike B (TUN ingestion)                   │
       │                                  │
       ▼                                  │
Spike C (connection API)                  │
       │                                  │
       ▼                                  │
Spike D (HS fingerprint, log-only)        │
       │                                  │
       └─────────► 整合 + 5 个 scenario 验证 + DoD
```

---

## Pre-flight Checklist

- [ ] **Go**: `go version` 输出 1.22+
- [ ] **Android SDK**: `echo $ANDROID_HOME` 非空；`ls $ANDROID_HOME/ndk` 至少有一个 NDK (建议 26.x 或 27.x)
- [ ] **真机**: `adb devices` 看到 OnePlus 10T
- [ ] **HS 已装并能登录**: `adb shell pm list packages | grep com.blizzard.wtcg.hearthstone` 返回包名
- [ ] **能进 BG 战斗**: 手动一次确认账户状态
- [ ] **gomobile (临时安装，Spike A 可能换掉)**: `go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init` 成功
- [ ] **CMFA 源码 clone**: `git clone https://github.com/MetaCubeX/ClashMetaForAndroid /tmp/cmfa-ref` —— Spike A 必参考其 `core/build.gradle.kts` 和 `core/src/main/golang/`

任一不通过 → 修复后再开始 Spike 0。

---

## Spike 0: Repo skeleton

**目的**: 确定目录结构，不写任何 build pipeline 假设。

**Files:**
- Create: `android/bobcore/.gitignore`
- Create: `android/bobcore/PINNED-VERSIONS.md` (Spike A 后填)
- Create: `android/overlay-app/` (Spike 4 后由 Android Studio 创建)

**Steps:**

- [ ] **0.1 Create dirs**

```bash
cd /Users/jun/code/bob-assist
mkdir -p android/bobcore android/overlay-app/scripts
```

- [ ] **0.2 Init Go module with placeholder**

```bash
cd android/bobcore
cat > go.mod <<'EOF'
module github.com/mjsaber/bob-assist/android/bobcore

go 1.22
EOF
```

- [ ] **0.3 .gitignore**

```
# android/bobcore/.gitignore
*.aar
*.so
*.dylib
build/
.gradle/
```

- [ ] **0.4 Commit**

```bash
git add android/bobcore
git commit -m "phase0(repo): bobcore Go module skeleton"
```

---

## Spike A: AAR / native library build pipeline

**目的**: **解决 codex review #4 — gomobile bind 是不是真的能搞定 mihomo？还是必须走 CMFA 同款 `golang-android` Gradle plugin + CMake?**

这是 Phase 0 最大未知，必须先于其他工作完成。预期 1-3 天。

**Exit criteria (量化)**:
- [ ] 有一份 wrapper 暴露 `func Version() string` 返回 `"0.0.1-prototype"`
- [ ] wrapper 依赖 mihomo 至少一个真实包（即 `import _ "github.com/metacubex/mihomo/log"` 之类），确保 mihomo 在依赖图里
- [ ] 编出 arm64-v8a 的 native artifact (.aar 或 .so)，体积 5-40 MB 之间
- [ ] Android Studio 工程引入该 artifact 后能 `assembleDebug` 成功
- [ ] APK 安装到 OnePlus 10T 后，从 Java/Kotlin 调用 `Version()` 拿到正确字符串
- [ ] 选定的 toolchain + mihomo tag + 关键 build tags 记录到 `android/bobcore/PINNED-VERSIONS.md`

**注**: 上面所有都达成才算 Spike A 通过；任一条不达成都不进 Spike B。

**Steps:**

- [ ] **A.1 Pin mihomo version**

调研当前 mihomo stable tag (写 plan 时为 v1.19.x)。在 bobcore go.mod 锁定：

```bash
cd android/bobcore
# 假设选 v1.19.24（codex review 提到的版本，公开有 docs）
echo "Pinning mihomo to v1.19.24 (verify on https://github.com/MetaCubeX/mihomo/releases first)"
```

实操时：先查 CMFA 当前用的 tag，对齐之；如果 CMFA 不用 tag 用 commit，那就 commit-pin。

- [ ] **A.2 Add minimum import to force dependency**

```go
// android/bobcore/bobcore.go
package bobcore

import (
    // 强制 mihomo 进依赖图。具体 import 路径 Spike A.4 时根据可编译性选定
    _ "github.com/metacubex/mihomo/log"
)

func Version() string {
    return "0.0.1-prototype"
}
```

- [ ] **A.3 go mod tidy with import present**

```bash
go mod tidy
cat go.mod
```

Expected: `go.mod` 含 `github.com/metacubex/mihomo v1.19.24`（或 chosen tag）。

注：codex review #3 提示——空 import 才能让 tidy 留住依赖。

- [ ] **A.4 Attempt: gomobile bind path**

```bash
cd android/bobcore
gomobile init  # if not done
gomobile bind \
    -target=android/arm64 \
    -androidapi 24 \
    -javapkg com.bobassist.gomobile \
    -ldflags="-s -w" -trimpath \
    -o /tmp/spike-a/bobcore.aar \
    .
```

- [ ] **A.5 Inspect AAR**

```bash
unzip -l /tmp/spike-a/bobcore.aar
# Expected: 含 jni/arm64-v8a/libgojni.so 或 libbobcore.so
# 用 javap 看导出 class:
mkdir -p /tmp/spike-a/extracted && cd /tmp/spike-a/extracted
unzip -o /tmp/spike-a/bobcore.aar
javap -cp classes.jar com.bobassist.gomobile.Bobcore
```

把 `javap` 输出的真实 method 签名记录到 `PINNED-VERSIONS.md`（Spike 4 Kotlin facade 要按这个写）。

- [ ] **A.6 Decision branch**

读 `gomobile bind` 输出和 AAR 内容：

**(a) 若 gomobile bind 成功且 AAR 含完整 arm64 .so + Java classes** → 继续 A.7
**(b) 若 gomobile bind 报错** (常见: mihomo 内部用了 gobind 不支持的类型 / `unsafe.Pointer` 暴露 / 复杂 generics) → fallback 到 A.6b
**(c) 若 AAR 编出但调用 crash** → 看 logcat，先判断 (b) 或 (c)

**A.6b Fallback: CMFA 同款 build pipeline**

参考 `/tmp/cmfa-ref/core/build.gradle.kts` + `/tmp/cmfa-ref/core/src/main/golang/`：

- 不用 gomobile bind
- 写一份 cgo wrapper Go 文件（`//export FuncName`）
- `go build -buildmode=c-shared -tags="foss with_gvisor" -o libbobcore.so ./...`
- 在 Android 项目里通过 CMake / Gradle prebuild rule 把 .so 放到 `app/src/main/jniLibs/arm64-v8a/`
- Kotlin 用 JNI 手写 binding (`System.loadLibrary("bobcore")` + `external fun version(): String`)

Spike A 决策点：如果 A.6b 比 gomobile 简单，直接走 A.6b。

- [ ] **A.7 Wire into Android sample**

```bash
# Spike 4 (Android Studio skeleton) 可能并行做，假设那边已经 hooked
cp /tmp/spike-a/bobcore.aar /Users/jun/code/bob-assist/android/overlay-app/app/libs/
```

Sample MainActivity 加一行：

```kotlin
// 路径以 javap 看到的 namespace 为准
import com.bobassist.gomobile.Bobcore
Log.i("SpikeA", "Version = ${Bobcore.version()}")
```

`adb logcat -s SpikeA` 应输出 `Version = 0.0.1-prototype`。

- [ ] **A.8 Record decisions**

```bash
cat > android/bobcore/PINNED-VERSIONS.md <<EOF
# Phase 0 Pinned Versions

- mihomo: <tag>
- mihomo commit: <sha>
- Go: $(go version)
- Build toolchain (chosen in Spike A): <gomobile-bind | cmfa-style-cgo>
- Build tags: <foss with_gvisor cmfa ...>
- AAR/SO path layout: <...>
- Java/Kotlin namespace: <com.bobassist.gomobile.Bobcore | com.bobassist.bobcore>
- Date: $(date -I)
EOF
git add android/bobcore/PINNED-VERSIONS.md android/bobcore/go.mod android/bobcore/go.sum android/bobcore/bobcore.go
git commit -m "phase0(spike-a): pin mihomo + AAR build pipeline (toolchain=...)"
```

- [ ] **A.9 If Spike A fails after 3 days → STOP**

如果 3 天没解决 → 升级问题给 user，重新讨论是否：
- 切到 fork CMFA + 改名 (放弃"内嵌"原则)
- 切到不内嵌 mihomo 的方案（回到 v1 spec 之前讨论排除的 A 选项）

---

## Spike B: TUN external fd ingestion

**目的**: 把 Android `VpnService.Builder.establish()` 返回的 fd 交给 mihomo TUN listener，让 mihomo 真正接管流量。

**前置**: Spike A 通过 (有可调用的 Version)。

**Exit criteria**:
- [ ] mihomo profile 写明 `tun.file-descriptor: <fd>`（codex review #2 指出的正确 API）；具体字段名以 `github.com/metacubex/mihomo/listener/config.Tun` 源码为准
- [ ] Android VpnService 起来后调 `MihomoCore.start(fd)` 不报错
- [ ] **真机验证流量被接管**: HS 包名加 `addAllowedApplication`；HS 访问 example.com (或登录暴雪) 时，能在 mihomo log 里看到对应的 connection 被处理（log-level=info 临时开）
- [ ] 没有 self-loop（Bob 本身的 outbound 不进 TUN，因为没把 Bob 加 allowed app）

**Steps:**

- [ ] **B.1 Locate mihomo TUN API**

```bash
cd /tmp/cmfa-ref
# 看 CMFA 怎么启动 TUN
grep -r "file-descriptor\|FileDescriptor" core/src/main/golang/ | head
```

读源码确认 mihomo TUN 入口（codex 指 `listener/sing_tun.New(options LC.Tun, ...)` 或类似）。

也参考 [mihomo listener/config Tun docs](https://pkg.go.dev/github.com/metacubex/mihomo/listener/config) 确认字段名。

- [ ] **B.2 Define profile generator (Kotlin)**

```kotlin
// android/overlay-app/app/src/main/java/com/bobassist/phase0/core/ProfileGenerator.kt
object ProfileGenerator {
    fun phase0Profile(tunFd: Int): String = """
mode: rule
log-level: info
find-process-mode: always
tun:
  enable: true
  stack: mixed
  file-descriptor: $tunFd
  auto-route: false
  auto-detect-interface: false
dns:
  enable: false
sniffer:
  enable: false
proxies: []
proxy-groups: []
rules:
  - MATCH,DIRECT
""".trimIndent()
}
```

注：精确 YAML 字段名（如 `file-descriptor` vs `fd`）按 B.1 confirm。

- [ ] **B.3 Bobcore Start/Stop with explicit TUN config**

```go
// android/bobcore/bobcore.go
// Start: 接收 profile YAML 内容（包含已填入的 file-descriptor）
// 解析 profile → 应用到 mihomo executor → 启动 listener/dispatcher
// 返回 ""=success 或 errStr
func Start(profileYaml string) string {
    // 具体实现以 mihomo `hub/executor` 或 `config.Parse` API 为准
    // Spike B 期间 read mihomo source for accurate names
}

func Stop() string {
    // 调 mihomo cleanup，关闭 TUN listener
}
```

⚠️ 这里不再放 wishful skeleton；Spike B 期间 read 上游源码后**真实写出实现**。

- [ ] **B.4 Kotlin: build profile with detached fd**

```kotlin
// In BobVpnService
val pfd = builder.establish() ?: error("VpnService.Builder.establish returned null")
val fd = pfd.detachFd()
val profileYaml = ProfileGenerator.phase0Profile(fd)
val err = Bobcore.start(profileYaml)
if (err.isNotEmpty()) error("Bobcore.start: $err")
```

- [ ] **B.5 FD ownership闭环 (codex #8)**

定义清楚:
- `establish()` 返回 `ParcelFileDescriptor`
- 立刻 `detachFd()` → raw int fd，所有权转 Go
- Kotlin 不再 close pfd（已 detached）
- Go `Stop()` 必须 close 这个 fd（不依赖 GC）
- 如果 `Bobcore.start(profile)` 报错返回 → Kotlin 端用 `ParcelFileDescriptor.adoptFd(fd).close()` 回收

```kotlin
val err = Bobcore.start(profileYaml)
if (err.isNotEmpty()) {
    // Recover the fd and close it
    runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
    error("Bobcore.start: $err")
}
```

- [ ] **B.6 Real-device smoke test**

```bash
# 1. Install + launch
adb install -r app-debug.apk
adb shell am start -n com.bobassist.phase0/.MainActivity
# 2. Authorize VPN
# 3. tap "Start VPN" in app
# 4. Launch HS, log in
# 5. In another adb shell:
adb logcat -s GoLog:* mihomo:* BobVpnService:*
# 6. Look for lines indicating mihomo dispatched HS traffic
```

Expected: mihomo log shows e.g. `[TCP] com.blizzard.wtcg.hearthstone --> ... match Match using DIRECT`.

- [ ] **B.7 Address codex review #1 (Protector)**

由于 Phase 0 不加 `addAllowedApplication(packageName)`，**Bob 自身流量不进 TUN**（codex #1 指出 docs 明确说未加入 allowed list 的 app "as if VPN wasn't running"）。所以 Phase 0 **不需要 Protector callback**。

如果 Spike B 实测发现 Bob 自身仍 self-loop，再走 mihomo `dialer.DefaultSocketHook` (codex 指出的真实 API)；具体函数签名查 `github.com/metacubex/mihomo/component/dialer` docs。

把这个决定写入 `PINNED-VERSIONS.md` 的 "Phase 0 simplifications" 段。

- [ ] **B.8 Commit**

```bash
git add android/bobcore android/overlay-app/app/src/main/java/com/bobassist/phase0/core
git commit -m "phase0(spike-b): TUN external fd ingestion validated on device"
```

---

## Spike C: Connection table snapshot & precise close

**目的**: 确认 mihomo 真实暴露的 connection enumeration / close API（codex review #5 #6：`statistic.DefaultManager.Snapshot/Get` 等是猜的，需 reading source）。

**前置**: Spike B 通过。

**Exit criteria**:
- [ ] `Bobcore.Connections()` 返回 JSON 数组，含至少 1 条 mihomo 正在追踪的 connection（HS 在前台时应该有多条）
- [ ] JSON 字段 `id, process, host, destinationIp, destinationPort, network, createdAt` 全部非空（除非真实数据本身就空，如 host）
- [ ] `Bobcore.CloseConnection(id)` 在 valid id 上返回 `0` (Success)；在 unknown id 上返回 `1` (NotFound)；core stopped 时返回 `3`
- [ ] **真机验证 close 效果**: 让 HS 在主菜单 idle；snapshot 找一条 HS connection；调 close；下一次 snapshot 该 id 不在列表里
- [ ] `statistic` 包真实 API 名称写入 `PINNED-VERSIONS.md`

**Steps:**

- [ ] **C.1 Read mihomo statistic package**

```bash
cd /tmp/cmfa-ref  # 或 go env GOPATH 下的 mihomo
# 查 mihomo 怎么暴露 connections
go doc github.com/metacubex/mihomo/tunnel/statistic
# 或：
grep -rn "DefaultManager" core/src/main/golang/ | head
```

记录真实 method：可能是 `Snapshot()`, `Connections()`, `Range(func)`, `Get(id)` 中的几个，名字以源码为准。CMFA `core/` 里 connection list 是怎么对外暴露的就照抄。

- [ ] **C.2 Implement Connections (real, not skeleton)**

按 C.1 找到的真实 API 写 `Connections()`、`CloseConnection(id)`。注意：
- mihomo metadata 字段名（`Process` / `ProcessPath` / `DstIP` / `DstPort` / `NetWork`）以源码为准
- `c.ID()` 是否字符串还是 UUID 类型
- `Close()` 是否返回 error

- [ ] **C.3 Mutex-based protector (codex review #6 — 不用 atomic.Value)**

(仅当 Spike B.7 决定需要 Protector 时；Phase 0 大概率不需要)

如需，用 `sync.Mutex` 包裹 `var p Protector`，避免 `atomic.Value.Store(nil)` panic。

- [ ] **C.4 Kotlin facade — final binding names**

Spike A.5 已经用 `javap` 拿到 .aar 实际导出的 Java class/method 名。这里把 facade 按真实名写。

不要再写 `Bobcore.init_`、`bobcore.Bobcore` 这种**猜的**导出名（codex review #5）。

```kotlin
// 实际类/方法名以 javap 输出为准
// 这里用占位 <BobcoreClass>.<methodName> 提醒不要复制粘贴
```

- [ ] **C.5 Real-device test**

```
1. App running, VPN on
2. Launch browser, visit https://example.com  (走 mihomo TUN, 应该被追踪)
3. App UI 显示 connections（至少 1 条 example.com 相关）
4. Tap "Close first connection" → 该 connection ID 消失
```

- [ ] **C.6 Commit**

```bash
git commit -am "phase0(spike-c): connection table snapshot + close validated"
```

---

## Spike D: HS battle socket fingerprint (log-only)

**目的**: **不**做任何 kill。只观察 HS 在 Android 上的 connection pattern，确认 v1.0 spec §4.5 的 selector 在真机上工作。

**前置**: Spike C 通过。

**Exit criteria**:
- [ ] 提供一个 "Snapshot now" 按钮，把当前 connection list dump 到 logcat 和 `/sdcard/Android/data/<pkg>/files/snapshots/<timestamp>.json`
- [ ] HS 主菜单 idle：snapshot 一次，确认 HS connections 中**没有** `host=="" && tcp && port==3724` 的条目
- [ ] HS BG 战斗开始：在战斗动画前 5 秒 + 战斗动画中 + 结算后各 snapshot 一次
- [ ] 比对 3 个 snapshot：战斗中那次**应有恰好 1 条** `process==HS && host=="" && tcp && port==3724`（CMFA 验证的 fingerprint）
- [ ] 记录到 `phase0-verification-report.md` Section "HS Fingerprint Findings"
- [ ] **不**在 Spike D 做 kill 验证

**Steps:**

- [ ] **D.1 Add snapshot dump button**

```kotlin
Button(onClick = {
    scope.launch {
        val conns = MihomoCore.connections()
        val file = File(getExternalFilesDir(null), "snapshots/${System.currentTimeMillis()}.json")
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().apply {
            conns.forEach { put(JSONObject().apply {
                put("id", it.id); put("process", it.process); put("host", it.host)
                put("destIp", it.destinationIp); put("destPort", it.destinationPort)
                put("network", it.network); put("createdAt", it.createdAt)
            }) })
        }.toString(2))
        status = "snapshot → $file"
    }
}) { Text("Snapshot now") }
```

- [ ] **D.2 Run scenarios (5 snapshots minimum)**

按 exit criteria 收集 snapshots，每个 snapshot 同时观察 logcat。

- [ ] **D.3 Verify IPv6 question (spec §12 Q3)**

检查所有 snapshot 中是否有 `destinationIp` 含 `:` (IPv6) 的条目。

- [ ] **D.4 Record findings**

```bash
adb pull /sdcard/Android/data/com.bobassist.phase0/files/snapshots /tmp/phase0-snapshots
ls /tmp/phase0-snapshots
```

填入 `phase0-verification-report.md`。

- [ ] **D.5 Commit**

```bash
git commit -am "phase0(spike-d): HS battle socket fingerprint confirmed on device"
```

---

## Spike 4: Android Studio skeleton (并行)

**目的**: 创建最小 Android Studio 工程。可与 Spike A 并行。

**前置**: 无（除 Pre-flight）。

**Exit criteria**:
- [ ] Android Studio project 能 `assembleDebug` 成功（在 bobcore.aar 准备好之前先用空 stub）
- [ ] APK 安装到 OnePlus 10T 能启动，显示一屏 "Bob Phase 0"
- [ ] AndroidManifest 含 VpnService + INTERNET + FOREGROUND_SERVICE + queries

**Steps:**

- [ ] **4.1 Android Studio new project**

用 Android Studio "Empty Activity (Compose)" template:
- Name: `Bob Assistant Phase 0`
- Package: `com.bobassist.phase0`
- Save location: `/Users/jun/code/bob-assist/android/overlay-app`
- Language: Kotlin, Build: Kotlin DSL
- minSdk 29

- [ ] **4.2 AndroidManifest.xml**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <queries>
        <package android:name="com.blizzard.wtcg.hearthstone" />
    </queries>

    <application
        android:label="Bob Assistant"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.BobAssistantPhase0">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <service
            android:name=".BobVpnService"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:foregroundServiceType="specialUse"
            android:exported="false">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Bob Assistant: Hearthstone Battlegrounds animation skipper" />
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

- [ ] **4.3 app/build.gradle.kts**

关键片段：

```kotlin
android {
    namespace = "com.bobassist.phase0"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bobassist.phase0"
        minSdk = 29
        targetSdk = 35
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}

dependencies {
    // Spike A 完成后填:
    // implementation(files("libs/bobcore.aar"))  // if gomobile route
    // 或者 jniLibs 配置 + 手写 JNI binding（若走 CMFA route）
}
```

- [ ] **4.4 First build (no bobcore)**

```bash
cd android/overlay-app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.bobassist.phase0/.MainActivity
```

- [ ] **4.5 Skeleton MainActivity (stub UI before Spike A wires in)**

```kotlin
package com.bobassist.phase0

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(Modifier.padding(16.dp)) {
                Text("Bob Phase 0 (skeleton)")
                Text("Waiting for bobcore integration (Spike A→B→C→D)")
            }
        }
    }
}
```

- [ ] **4.6 Commit**

```bash
git commit -am "phase0(spike-4): Android Studio skeleton (waiting on bobcore)"
```

---

## Spike E: Integration & 5 real-device scenarios

**目的**: 把 Spike B/C/D 的实现整合进 MainActivity + BobVpnService 完整流程，跑 5 个 scenarios。

**前置**: Spike A/B/C/D 全部通过。

**Steps:**

- [ ] **E.1 BobVpnService final wiring**

整合 Spike B 的 TUN ingestion + Spike C 的 connection API + Spike B.5 的 fd ownership。代码片段在 Spike B/C 已就位；这里整合到单一 service。

- [ ] **E.2 MainActivity UI**

按 v1 plan Task 10 的 Compose 结构，但 **kill button 的 selector 必须用 safer version (codex review 反对意见 #5)**：

```kotlin
fun selectBattleSocket(all: List<Connection>): Connection? {
    val candidates = all.filter {
        it.process == "com.blizzard.wtcg.hearthstone"
            && it.host == ""
            && it.network == "tcp"
            && it.destinationPort == 3724
            && it.destinationPort !in setOf(443, 80, 1119)
    }
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return candidates[0]
    // Tie-break: newest by createdAt
    return candidates.maxByOrNull { it.createdAt }
}
```

注：在 Phase 0 中 port 3724 是必须命中的——CMFA Android 端 fingerprint。

- [ ] **E.3 五个真机 scenarios**

详细 checklist 在 `scripts/phase0-verification-report.md` (Spike F)。

- [ ] **E.4 Commit**

```bash
git commit -am "phase0(spike-e): integration + safer selector"
```

---

## Spike F: Verification report

**Files:**
- Create: `android/overlay-app/scripts/verify-phase0.sh`
- Create: `android/overlay-app/scripts/phase0-verification-report.md`

**Steps:**

- [ ] **F.1 Helper script**

```bash
#!/usr/bin/env bash
# android/overlay-app/scripts/verify-phase0.sh
set -e
PKG=com.bobassist.phase0
case "${1:-help}" in
  install)  adb install -r ../app/build/outputs/apk/debug/app-debug.apk ;;
  launch)   adb shell am start -n $PKG/.MainActivity ;;
  logcat)   adb logcat -c && adb logcat -s GoLog:* BobVpnService:* mihomo:* ;;
  vpnstate) adb shell dumpsys vpn ;;
  hsstate)  adb shell pidof com.blizzard.wtcg.hearthstone ;;
  pull-snaps) adb pull /sdcard/Android/data/$PKG/files/snapshots /tmp/phase0-snapshots ;;
  *) echo "Usage: $0 {install|launch|logcat|vpnstate|hsstate|pull-snaps}" ;;
esac
```

- [ ] **F.2 Verification report template (codex review #5 — Scenario 4 PASS 更客观)**

```markdown
# Phase 0 Verification Report

Date: __________
Device: OnePlus 10T (CPH2451, Android 15)
HS version: __________
mihomo tag: __________
bobcore commit: __________
Build toolchain: gomobile bind | cmfa-style

## Scenario 1: VPN start + bobcore boots
Pass criteria:
- Foreground notification appears within 3s of tap Start
- `adb shell dumpsys vpn` shows session "Bob Assistant"
- Logcat: no fatal GoLog / BobVpnService crashes
Status: __________
Logcat excerpt: __________

## Scenario 2: HS traffic appears in connection table
Pass criteria:
- ≥ 3 connections with `process == com.blizzard.wtcg.hearthstone`
- Most have non-empty `host` (DNS resolved through system)
Status: __________
Snapshot file: __________

## Scenario 3: Battle socket detection (host="", port 3724)
Pass criteria:
- Pre-battle snapshot: 0 conn matches `tcp && host=="" && port==3724`
- During-battle snapshot: exactly 1 conn matches
- Post-battle snapshot: 0 conn matches (or stale entry only)
Status: __________
Snapshots: __________

## Scenario 4: Kill battle socket → HS skips animation (codex objective criteria)
Pass criteria (ALL must hold):
- (a) `closeConnection(id)` returns code 0 (Success) within 200ms
- (b) Same id is NOT present in next snapshot
- (c) Exactly 1 connection killed (not multiple)
- (d) Within 10s, HS displays post-battle / next-tavern UI
- (e) HS does NOT return to login screen / main menu
- (f) Next BG round playable without restart
- (g) Screen recording + logcat with timestamps captured

Status: __________
Video clip: __________
Logcat excerpt: __________

## Scenario 5: Network change resilience
Pass criteria:
- Toggle Wi-Fi off → mobile data takes over
- HS may briefly stutter but does not crash
- Wi-Fi back on within 30s
- Next BG round: kill battle socket still works

Status: __________

## Open Questions Follow-up (must answer all)
- Q1 (spec §12 Q1): mihomo accepts non-blocking detached fd?  __________
- Q2 (spec §12 Q2): fingerprint stable across rooms/devices? (single device tested)  __________
- Q3 (spec §12 Q3): HS Android uses IPv6?  yes / no  __________
- Q4 (spec §12 Q4): TUN stack choice that worked: mixed / gvisor / system  __________
- Q7 (spec §12 Q7): If Protector used, callback latency in HS frame budget?  __________

## Phase 0 Exit Decision
[ ] All 5 scenarios Pass + all Q answered → proceed to Phase 1 plan
[ ] Any scenario Fail → see "Failure Mode" section below

## Failure Mode (if applicable)
- Failed scenario(s): __________
- Root cause: __________
- Required spec / approach changes: __________
```

- [ ] **F.3 Run scenarios, fill report**

人工跑场景。

- [ ] **F.4 Commit**

```bash
git add android/overlay-app/scripts
git commit -m "phase0(spike-f): verification script + report"
```

---

## Spike G: Exit decision

**Steps:**

- [ ] **G.1 Compare report against Definition of Done (below)**

- [ ] **G.2 Update PROGRESS.md with Phase 0 outcome**

- [ ] **G.3 Update spec §12 Open Questions — mark resolved or update with new findings**

- [ ] **G.4 If all green → start Phase 1 plan (separate doc)**

- [ ] **G.5 Commit**

```bash
git add PROGRESS.md docs/superpowers/specs/
git commit -m "phase0: exit decision + spec/progress update"
```

---

## Risk Matrix (updated per codex review)

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| **gomobile bind 不能搞定 mihomo (codex #4)** | **高** | Spike A 失败 | Fallback to CMFA-style cgo + JNI；3 天没解决就升级到用户 |
| **mihomo TUN external-fd API 与预期不符 (codex #2)** | 中 | Spike B 失败 | 必须读 mihomo source；可参考 CMFA `core/src/main/golang/native/tun/` |
| **mihomo statistic.DefaultManager API 签名与预期不符 (codex #5/#6)** | 中 | Spike C 推迟 | Spike A 完成后 `go doc` 锁定真实 method 名才写 facade |
| **gomobile 命名约定与猜测不符 (codex #5)** | 中 | Kotlin facade 编译失败 | Spike A.5 `javap` 验证后才写 facade |
| **HS Android 战斗 socket 与 Mac/CMFA 验证版本不同** | 低 | Scenario 3 失败 | Spike D log-only 验证；与 PROGRESS.md 记录的 OnePlus 10T 验证 cross-check |
| **HS 使用 IPv6** | 低 | Phase 0 假设 IPv4-only | Spike D 明确 Q3 答案；若 yes 则 Phase 0 末加 IPv6 接管 |
| **OEM (Android 15 OnePlus) VpnService 限制** | 低 | Scenario 1 失败 | CMFA 之前在 OnePlus 跑通；应该 OK |
| **gomobile reverse-callback (Protector) 性能** | 中 | (仅用到时) HS 卡帧 | Spike B 选择不加 Bob 自身到 allowed app → 不用 Protector，绕过此风险 |
| **AAR / SO 体积超 30MB** | 中 | Phase 0 仍可接受 | 不限制；Phase 1 再 strip / build tag 优化 |
| **3 个高/中风险叠加 → 1-2 周做不完** | 中 | timeline 失守 | Spike A 必须 ≤ 3 天；任何 spike 卡 ≥ 3 天升级 |

---

## Definition of Done (Phase 0)

Phase 0 完成 = 以下全部满足:

- [ ] Spike A: bobcore native bundle 编译成功，arm64-v8a；从 Java/Kotlin 调用 `Version()` 返回 `"0.0.1-prototype"`
- [ ] Spike B: VpnService.establish + mihomo TUN external-fd ingestion 跑通；HS 流量在 mihomo log 里可见
- [ ] Spike C: connection table API 真实可用；可以 close 任意 id
- [ ] Spike D: HS battle socket fingerprint `{process==HS, host=="", tcp, port==3724}` 在战斗时唯一出现；其他时机不出现
- [ ] Spike E: 完整 integration on OnePlus 10T
- [ ] Spike F: 5 个 scenarios 全 Pass（Scenario 4 按 codex 客观判据全部 g 项满足）
- [ ] spec §12 Open Questions Q1/Q2/Q3/Q4 均有明确答案，写入 spec
- [ ] `android/bobcore/PINNED-VERSIONS.md` 含完整 toolchain + tag 决定
- [ ] git history 干净，commits 按 spike 分割

完成后再写 Phase 1 plan（产品 MVP）。

---

## ★ Codex Review Summary

本 plan 的 v2 修订基于 OpenAI Codex CLI 在 2026-05-24 的 review (`/tmp/codex-review-bob-plan.md`)。主要采纳：

| Review Issue | v1 状态 | v2 处理 |
|---|---|---|
| #1 dialer hook API 名错 (`DefaultOptions` 不存在) | wishful code | Spike B.7 改用 `DefaultSocketHook`；且 Phase 0 不加 Bob 自身到 allowed app 后**不需要** Protector |
| #2 `Start()` 是空 skeleton，profile 缺 `tun.file-descriptor` | wishful | Spike B 重写：profile 含 `tun.file-descriptor`，mihomo TUN entry 走源码确认的 API |
| #3 `go get + go mod tidy` 在没 import 时会删依赖 | bug | Spike A.2 先加 blank import，A.3 再 `go mod tidy` |
| #4 gomobile bind 不是已验证路径（CMFA 用 cgo + Gradle plugin） | wishful | Spike A 主路径试 gomobile，Fallback A.6b CMFA-style cgo；3 天卡住升级 |
| #5 `init` 不是 Java 关键字；猜命名 | bug | Spike A.5 `javap` 后才写 facade，加 `-javapkg` 固定包名 |
| #6 `atomic.Value.Store(nil)` 会 panic | bug | Spike C.3 用 mutex（若需 Protector） |
| #7 DNS 自相矛盾 (8.8.8.8 vs 系统 DNS) | bug | Spike B.2 profile `dns.enable: false`；VpnService.Builder 不加 `addDnsServer` |
| #8 fd ownership 没闭环 | gap | Spike B.5 明确：detach 后所有权归 Go；start 失败 Kotlin 用 `ParcelFileDescriptor.adoptFd(fd).close()` 回收 |
| Scenario 4 PASS 不够客观 | 模糊 | Spike F.2 改成 7 条 (a-g) 全部满足 |
| 漏 IPv6 Q3 | gap | Spike D.3 明确验证 + DoD 列入 |
| 7 处 blocking placeholder | wishful | 改为 spike-driven plan：源码确认后才写实现，spike 失败有明确 fallback |
| 高风险叠加 → timeline 风险 | gap | Risk Matrix 加 "3 风险叠加" 行；每个 spike 设 3 天上限 |

**未采纳**:
- Codex 建议 "Phase 0 就 fork mihomo" → 部分采纳：若 Spike A 主路径过，不 fork；Fallback A.6b 路径需要时 fork。这样保留 Phase 0 尽量简单的原则
- Codex 提议把 "Spike A→B→C→D" 完全串行 → 采纳了主链串行，但 Spike 4 (Android skeleton) 与 A 并行
