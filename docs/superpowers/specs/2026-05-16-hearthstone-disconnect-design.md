# 炉石安卓一键拔线 App — 设计方案

**日期**：2026-05-16
**状态**：已 codex review，已采纳建议
**作者**：jun + Claude

## 1. 背景

炉石玩家在劣势/掉血动画/失误操作时，常用"拔线"技巧：临时切断游戏与服务器的网络连接，待几秒后服务端自动断开当前回合判定，玩家重新连上服务器即可回到当前对局。**炉石服务端保留对局状态约 60 秒**，单局重连次数约 8-10 次为上限。

桌面端有多个现成工具（HsReconnectTool、HDT-Reconnector、炉石拔线助手等）。**Android 平台目前没有独立 App**，相关功能都被奇游、UU、迅游等加速器集成。

本项目做一个 Android 原生 App，给**美服炉石玩家（不开加速器）** 提供桌面级的一键拔线体验。

## 2. 用户场景与核心交互

- **目标用户**：作者本人 + 朋友圈分享，单人项目
- **核心场景**：炉石全屏运行中，玩家发现需要拔线 → **点一下悬浮按钮** → 炉石断网 N 秒（默认 5s）→ 网络自动恢复 → 重新连接服务器后回到对局
- **不做的事**：自动判断对局态势、自动拔线、加速器功能、PC 端

## 3. 关键技术决策

| 决策 | 取舍 |
|---|---|
| **原生 Kotlin Android** | 不用 RN/Flutter。VpnService/Overlay/UsageStats 都是深度 Android API，桥接层只会添加麻烦 |
| **VpnService 黑洞模式** | 唯一非 root 路径。Android `VpnService` 是本地 TUN 拦截器（NetGuard 同原理），**不转发流量到任何远程服务器** |
| **按需启动 VPN** | 每次点按钮启动 `DropVpnService`，N 秒后自动停。状态栏钥匙图标只在拔线时出现 |
| **`addAllowedApplication(炉石包名)`** | 只把炉石流量送进 VPN 黑洞，其他 App（微信、浏览器）不受影响 |
| **`UsageStatsManager.queryEvents()` 轮询前台** | 不用 AccessibilityService（权限引导太重）。事件流比 `queryUsageStats` 聚合统计更可靠 |
| **悬浮按钮** | `TYPE_APPLICATION_OVERLAY`。SYSTEM_ALERT_WINDOW 权限。位置 clamp 到显示边界 |
| **minSdk 26 / targetSdk 35** | 覆盖现役主流机型；适配 Android 14/15 的 FGS 类型要求 |

## 4. 架构总览

```
                   ┌──────────────────────────────┐
                   │   OverlayService (前台服务)  │
                   │   foregroundServiceType=     │
                   │       specialUse             │
                   │   持有通知 + 协调子组件      │
                   └──┬────────┬────────┬─────────┘
                      │        │        │
                      ▼        ▼        ▼
            ┌─────────────┐ ┌─────────┐ ┌──────────────────┐
            │ Foreground  │ │ Overlay │ │   Disconnect     │
            │  Detector   │ │ Window  │ │   Controller     │
            │(queryEvents)│ │(悬浮按钮)│ │ (状态机+计数)   │
            └─────────────┘ └─────────┘ └────────┬─────────┘
                                                 │ 启停
                                                 ▼
                                       ┌──────────────────┐
                                       │  DropVpnService  │
                                       │  foregroundServ- │
                                       │  iceType=        │
                                       │    shortService  │
                                       │  (TUN + 丢包)     │
                                       └──────────────────┘
```

## 5. 组件清单

| 组件 | 职责 | 关键 API |
|---|---|---|
| `SettingsActivity` (Compose) | 引导页 / 设置页 / 权限状态卡片 / 启停开关 | Jetpack Compose |
| `OverlayService` | 前台服务，持有通知和子组件实例 | `Service.startForeground` |
| `ForegroundDetector` | 1.5s 轮询 `queryEvents()`，判断炉石前台/后台，带 debounce | `UsageStatsManager` |
| `OverlayWindow` | 悬浮按钮：绘制、拖动、点击、倒数环、位置 clamp | `WindowManager`, `Canvas` |
| `DisconnectController` | 状态机：Idle → Preparing → Starting → Active → Stopping → Idle/Failed；管理本会话计数 | (纯 Kotlin) |
| `DropVpnService` | `VpnService` 子类。建 TUN、`addAllowedApplication`、丢包、超时自停、实现 `onRevoke()` | `VpnService.Builder` |
| `PermissionGate` | 启动前检查 4 项权限是否齐全 | (纯 Kotlin) |
| `Prefs` | SharedPreferences：默认时长（默认 5s）、按钮 (x,y) | `SharedPreferences` |

## 6. AndroidManifest 关键声明

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
                 tools:ignore="ProtectedPermissions"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

<!-- Android 11+ package visibility -->
<queries>
    <package android:name="com.blizzard.wtcg.hearthstone"/>
</queries>

<application ...>
    <service
        android:name=".overlay.OverlayService"
        android:foregroundServiceType="specialUse"
        android:exported="false">
        <property
            android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="hearthstone_disconnect_button_overlay"/>
    </service>

    <service
        android:name=".vpn.DropVpnService"
        android:foregroundServiceType="shortService"
        android:permission="android.permission.BIND_VPN_SERVICE"
        android:exported="false">
        <intent-filter>
            <action android:name="android.net.VpnService"/>
        </intent-filter>
    </service>
</application>
```

**关键点**：
- `OverlayService` 是 `specialUse`（长期持有悬浮窗 + 监听前台，没有任何标准 FGS 类型符合）
- `DropVpnService` 是 `shortService`（≤3 分钟，符合 Android 14+ 短任务限制），但因为 VPN 服务还有自己的 manifest 要求（`BIND_VPN_SERVICE` + intent-filter），两者并存
- `<queries>` 不可省略，否则 `getPackageInfo` 在未安装时直接抛 `NameNotFoundException`，无法区分"未安装"和"无可见性"

## 7. DisconnectController 状态机

```
            ┌─────────────────────────────────────┐
            │              Idle                   │◀───┐
            └─────────────────┬───────────────────┘    │
                              │ user taps button       │
                              ▼                        │
            ┌─────────────────────────────────────┐    │
            │   Preparing (调 VpnService.prepare()) │    │
            └────┬──────────────────────────┬──────┘    │
       null │ (已授权)            非空 Intent │ (未授权)  │
            ▼                              ▼          │
   ┌─────────────────┐            ┌─────────────────┐ │
   │    Starting      │            │     Failed      │─┤
   │ (startService)   │            │ (Toast + 通知)   │ │
   └────┬─────────┬──┘            └─────────────────┘ │
        │ ok      │ exception                          │
        ▼         ▼                                    │
┌──────────────┐ ┌─────────────────┐                  │
│   Active     │ │     Failed      │──────────────────┤
│ (倒数N秒,    │ │                 │                  │
│  counter+1)  │ └─────────────────┘                  │
└──────┬───────┘                                       │
       │ timer expire OR onRevoke()                    │
       ▼                                               │
┌──────────────┐                                       │
│   Stopping   │───────────────────────────────────────┘
│ (pfd.close,  │
│  stopService)│
└──────────────┘
```

**关键规则**：
- 计数器仅在进入 `Active` 时 +1（授权失败不计数）
- 倒数仅在 `Active` 时显示真实时间；`Preparing/Starting` 阶段按钮显示"准备中"小动画
- `pfd.close()` 幂等：`if (!isClosed) { isClosed = true; pfd.close() }`
- `DropVpnService.onRevoke()` 被调（系统/用户撤销 VPN 授权、其他 VPN App 抢占）→ 立即回 `Idle` + 通知用户

## 8. 数据流（典型用户路径）

1. **首次打开** → SettingsActivity 引导页依次请求：悬浮窗权限 → 使用情况访问 → VPN 授权 → 通知权限
2. **点"启动拔线助手"** → OverlayService 启动 → ForegroundDetector 每 1.5s 调 `queryEvents()`
3. **炉石进入前台**（ACTIVITY_RESUMED 事件，连续 2 次确认 debounce） → OverlayWindow 显示按钮在上次位置
4. **用户点按钮** → DisconnectController 进入 Preparing：
   - `VpnService.prepare()` == null → Starting
   - `startService(DropVpnService)` → `establish()` 返回非空 ParcelFileDescriptor → Active
   - 启动 N 秒倒计时 + 计数器 +1 + 按钮显示倒数环 + 置灰
5. **倒计时结束** → Stopping → `pfd.close()` + `stopService()` → Idle，按钮恢复
6. **炉石进入后台**（ACTIVITY_PAUSED/STOPPED，debounce） → 隐藏按钮、计数器清零

## 9. 错误处理

| 场景 | 处理 |
|---|---|
| VPN 授权被撤销（`prepare() != null`） | 状态→Failed；按钮显示红色叉号 2 秒；通知"VPN 授权失效，点击修复" → 点通知打开 SettingsActivity。**绝不**从服务弹 transparent Activity 触发系统授权框（破坏游戏全屏） |
| UsageStats 权限被撤销 | 检测不到前台事件 → 启动后 5s 仍无事件 → 通知"使用情况权限失效，点击修复" |
| Overlay 权限被撤销 | OverlayService 启动时检查 `Settings.canDrawOverlays()` → false 则停止服务 + 通知提示 |
| 重复点击按钮 | 状态 != Idle 时按钮置灰、忽略点击事件 |
| 炉石被杀 | `onRevoke()` 不会触发；VPN 自然倒计时结束停止；下次 ForegroundDetector 检测到 STOPPED 事件即隐藏按钮 |
| `VpnService.Builder.establish()` 返回 null | 状态→Failed；Toast 提示"VPN 建立失败"；记录日志 |
| `startForegroundService` 抛 `ForegroundServiceStartNotAllowedException` | 状态→Failed；Toast 提示；记录日志 |
| 炉石未安装 | SettingsActivity 顶部红条："炉石未安装，无法启动" + 启动按钮置灰 |
| 显示旋转/分屏 | `onConfigurationChanged` 中重新 clamp 按钮位置到新边界 |

## 10. 测试

- **单元测试**：
  - `DisconnectController` 状态机：所有转换、计数器逻辑、`onRevoke` 处理
  - `Prefs`：读写默认值
  - `PermissionGate`：各权限组合下的判断
  - `ForegroundDetector` 的 debounce 逻辑（mock `UsageStatsManager`）

- **仪器测试**：
  - `OverlayWindow` 位置 clamp（mock 不同 display 尺寸）
  - `ForegroundDetector` 在真实 `UsageStatsManager` 下的事件解析

- **手测清单**（真机）：
  - [ ] 4 个权限引导流程顺畅
  - [ ] 炉石前台时按钮出现（≤ 2 秒延迟可接受）
  - [ ] 炉石后台时按钮消失
  - [ ] 点按钮 5 秒内炉石确实掉线（用 `adb shell tcpdump` 或观察游戏 UI）
  - [ ] 5 秒后炉石自动重连成功，回到当前对局
  - [ ] 计数器在炉石进入后台后清零
  - [ ] 旋转屏幕后按钮位置 clamp 正确
  - [ ] 撤销 VPN 授权后点按钮：不弹系统框、走 Failed 路径、有通知提示

## 11. 已知风险与未来工作

| 风险 | 缓解 / 后续 |
|---|---|
| **MIUI/ColorOS 等国产 ROM 游戏模式可能屏蔽悬浮窗** | MVP 不做。后续可加 Quick Settings Tile 作 fallback。目标用户美服 Pixel/Samsung，风险较低 |
| **炉石包名变更**（地区版本差异、未来更新） | debug build 提供 `BuildConfig.HEARTHSTONE_PACKAGE_OVERRIDE`。release 版本写死 `com.blizzard.wtcg.hearthstone` |
| **暴雪可能在客户端检测异常断线** | 单局上限 ~8-10 次，超过服务端拒绝重连。UI 计数器达 8 次时变红警告 |
| **VPN 槽位互斥**：用户同时开加速器/其他 VPN | 启动时检测 `VpnService.prepare() != null` 不一定能区分原因；MVP 仅在 Failed 状态提示用户。后续可考虑用 `IConnectivityManager` 查询当前 VPN |
| **合规风险**：暴雪 EULA 灰色地带 | 不在应用商店上架，仅做 sideload。不收集任何用户数据，不联网 |

## 12. MVP 边界（明确不做）

- 自动拔线（基于游戏事件） — 需要图像识别/读内存，复杂度爆炸
- 多游戏支持（魔兽世界等）— 后续可扩，先聚焦炉石
- 对局识别 — 用前台/后台切换近似替代
- Quick Settings Tile / 通知 action — 见已知风险
- 云同步设置 — 单机即可
- 国际化（i18n）— 只支持中文（自用项目）

## 13. 实施顺序（草案，正式 plan 由 writing-plans skill 产出）

1. 项目脚手架（Kotlin + Compose + Hilt 可选）
2. SettingsActivity + 4 项权限引导
3. `OverlayService` + 通知
4. `OverlayWindow`（基础显示、拖动、位置 clamp）
5. `ForegroundDetector` + 显隐联动
6. `DropVpnService`（黑洞 + 短服务自停）
7. `DisconnectController` 状态机 + UI 联动（倒数环、计数器）
8. 错误处理 + 通知修复链路
9. 手测清单走完一轮
