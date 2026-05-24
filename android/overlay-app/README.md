# Android Overlay App (TODO)

## 目标

写一个手机端浮窗 App，浮在炉石之上，点击触发拔线——免去 ADB + PC 端手动 curl。

## 用户故事

1. 装一个 APK（要么是这个独立 App，要么内嵌 mihomo core）
2. 启动 CMFA 并打开 VPN（或者：用内嵌方案，根本不需要 CMFA）
3. 启动炉石，浮窗自动浮在炉石之上
4. 进 BG 战斗动画那一刻点浮窗 → 动画跳过

## 技术要点

- **浮窗权限**：`Settings.canDrawOverlays()` + `TYPE_APPLICATION_OVERLAY` window type
- **HS 检测**：监听 `UsageStatsManager.queryEvents` 拿到当前前台 App，是 HS 时浮窗 show，否则 hide。需 `PACKAGE_USAGE_STATS` 权限
- **拔线**：本地 `OkHttpClient` 调 `http://127.0.0.1:9090/connections` 和 `DELETE /connections/{id}`
- **Filter 逻辑**（跟 macOS Skipper 一致）：
  ```kotlin
  conn.metadata.process == "com.blizzard.wtcg.hearthstone"
    && conn.metadata.host == ""
  ```

## 实现选项

### A. 纯客户端 App（推荐起点）
- 用户装：CMFA + 这个 App
- 这个 App 只做浮窗 + 调 CMFA 的 HTTP API
- 几百行 Kotlin + Compose
- **缺点**：用户要装两个 App，且 CMFA 的 External Controller Override 设置要手动开

### B. 内嵌 mihomo core .so
- 用户只装一个 App
- App 内部启动 mihomo core（Go 编出来的 .so），暴露 API 给自己
- 同样需要 VpnService 权限
- **优点**：用户体验最干净；可以预置好的 profile + Override 设置
- **缺点**：工作量大，Go cross-compile 到 Android 的 toolchain 配置烦

参考：CMFA 自己的 `core/` 模块就是 mihomo Go 代码 + JNI bridge，可以借鉴。

### C. 直接 fork CMFA + 加浮窗模块
- CMFA 本身是开源 Kotlin App
- Fork 然后加一个 OverlayService + 浮窗 UI
- **优点**：mihomo 接管 + 浮窗触发都在一个 App 里
- **缺点**：跟 CMFA 上游 diverge 维护成本

## 当前推荐路径

先 A（最小验证），跑通 + 用得习惯了再考虑 B/C。

## 参考实现

macOS 端 `HearthStone Skipper.app`（z2z63 + 我们 fork）：
- 浮窗：`src/float_button.cpp`
- HS 窗口检测：`src/platform/window_listener.mm`（OBJC + AX API + CGWindowList fallback）
- API 调用 + filter：`src/skipper.cpp`
- Filter 条件就是上面 Kotlin 那两行的 C++ 版本

```cpp
// src/skipper.cpp:69-78
for (auto obj : doc["connections"].toArray()) {
    if (obj.toObject()["metadata"].toObject()["processPath"].toString()
            .endsWith("Hearthstone.app/Contents/MacOS/Hearthstone")
        && obj.toObject()["metadata"].toObject()["host"] == "") {
        connection_to_kill = obj.toObject()["id"].toString().toStdString();
        break;
    }
}
```
