# CMFA setup（详细步骤 + 截图说明）

这是 [`../README.md`](../README.md) 第 1-4 步的展开版。

## 1. 装 CMFA

```bash
curl -L -o cmfa.apk \
  https://github.com/MetaCubeX/ClashMetaForAndroid/releases/download/v2.11.28/cmfa-2.11.28-meta-arm64-v8a-release.apk
adb install cmfa.apk
```

验证安装：`adb shell pm list packages | grep clash` 应输出 `package:com.github.metacubex.clash.meta`。

## 2. 开 External Controller

CMFA 主页 → 顶部右侧 齿轮图标 → 进设置页。

往下找到 **「覆盖 / Override」** 节（不是 profile 里的 override，是 App 全局的 override）：

| 项 | 值 |
|---|---|
| 外部控制器 / External Controller | **打开** |
| 地址 / Address | `0.0.0.0:9090` |
| 密钥 / Secret | 留空 |

回主页（不需要重启 CMFA，下次 mihomo core 启动时生效）。

## 3. Push profile + 导入

profile 文件：[`hs-skipper.yaml`](hs-skipper.yaml)。关键内容：

```yaml
external-controller: 0.0.0.0:9090   # 会被 CMFA Override 覆盖，但留着无害
mode: rule
find-process-mode: always           # 必须，否则 metadata.process 字段是空
tun: { enable: true, ... }
sniffer: { enable: false }          # 必须关，让战斗 socket 的 host 字段为空
proxies:
  - {name: dummy, type: socks5, server: 127.0.0.1, port: 65530}  # CMFA 强制
rules:
  - MATCH,DIRECT                    # 不真走代理，本地直连
```

CMFA 的文件浏览器只看得见自己 sandbox（Scoped Storage），所以 `adb push` 到 `/sdcard/Download/` 它选不到。走 URL import：

```bash
# 在 PC（不是手机）上跑：
python3 -m http.server 8765 --directory android/cmfa &
adb reverse tcp:8765 tcp:8765

# 测试手机能拉到：
adb shell 'curl -s http://127.0.0.1:8765/hs-skipper.yaml | head -3'
# 应输出前 3 行 yaml
```

CMFA 里：
1. 配置 / Profiles 页 → 右下 「+」 按钮
2. 选 **「URL」** （不是「文件」）
3. URL: `http://127.0.0.1:8765/hs-skipper.yaml`
4. 名称：随便（推荐 `hs-skipper`）
5. 保存
6. 在 profile 列表里点选 hs-skipper（左边圆圈变实心）

## 4. 启动 VPN

回 CMFA 主页 → 顶部大开关打开 → 首次弹 VPN 授权 → 允许。

VPN 启动后顶部应该变绿。

## 5. 验证

```bash
adb forward tcp:9091 tcp:9090  # 把手机的 9090 映射到 PC 的 9091
curl http://127.0.0.1:9091/version
# 应输出 {"meta":true,"version":"alpha-...-cmfa-2.11.28.meta"}
```

如果返回 `Empty reply from server`：步骤 2 没开 External Controller Override。

## 6. 找 HS UID（可选，filter 备用）

```bash
adb shell dumpsys package com.blizzard.wtcg.hearthstone | grep appId
# 输出 appId=10386（你的可能不同）
# UID 通常 = 10000 + appId 的某个映射，但用包名 filter 更直接
```

我们的 [`skip.sh`](skip.sh) 用 `metadata.process == "com.blizzard.wtcg.hearthstone"` filter，不依赖 UID。
