# 炉石"一键拔线"工具 — 进展记录

**最终状态**：
- **Mac 上 BG 战斗动画拔线已工作** ✅（Mihomo Party + 修复版 z2z63 hearthstone_skipper）
- **Android 上同方案验证通过** ✅（ClashMetaForAndroid + 手动 curl 触发，浮窗 App 未做）

---

## 当前可用的方案（Mac）

### 架构
```
炉石 Mac 客户端
    ↓ (Mihomo TUN 自动捕获)
Mihomo (Clash Meta) Party
    ↓ 直连 (rules: MATCH,DIRECT)
暴雪服务器

热键/UI                  ↓ DELETE /connections/{id}
HearthStone Skipper ────▶ Mihomo HTTP API (127.0.0.1:9090)
                          按 host=="" filter 锁定 BG 战斗会话连接
                          调 Clash core kill 该 connection
                          → HS 看到 socket 死 → 触发重连
                          → 服务端已预算好结果 → HS 重连即看到战斗结果
                          → 动画被跳过
```

### 关键依赖
| 组件 | 版本 | 来源 |
|---|---|---|
| **Mihomo Party** | 1.9.5 (brew cask `clash-party`) | `brew install --cask mihomo-party` |
| **mihomo core** | v1.19.24 (Meta) | 跟 Mihomo Party 一起 |
| **HearthStone Skipper** | v0.1.2 + 我们的 3 个 patch | fork 自 github.com/z2z63/hearthstone_skipper |
| **Qt6** | 6.11.1 | `brew install qt` |
| **CMake** | 4.0.3 | 已有 |

### Mihomo 关键配置
位置：`~/Library/Application Support/mihomo-party/mihomo.yaml`

```yaml
external-controller: "127.0.0.1:9090"   # 必须，skipper 通过这个调 API
secret: ""                              # 留空（仅本地 loopback）
find-process-mode: always               # 必须，让 skipper 能 filter HS 进程
sniffer:
  enable: false                         # 关掉，让 connections 有空 host 字段
  parse-pure-ip: false
tun:
  enable: true                          # 必须，让 HS 流量经过 mihomo
  device: utun1500
```

profile：`~/Library/Application Support/mihomo-party/profiles/default.yaml`
```yaml
proxies: []
proxy-groups: []
rules:
  - MATCH,DIRECT      # 不真正代理，只让 mihomo 看到/管理连接
```

### Skipper 配置（QSettings plist）
位置：`~/Library/Preferences/com.z2z63-dev.skipper.plist`

```bash
defaults write com.z2z63-dev.skipper external_controller_type "TCPIP"
defaults write com.z2z63-dev.skipper external_controller "127.0.0.1:9090"
defaults write com.z2z63-dev.skipper secret ""
defaults write com.z2z63-dev.skipper unix_socket ""
```

### 系统权限
- **Accessibility**：Skipper 需要，用于检测 HS 窗口焦点变化
- **Local Network**：Sequoia 上首次访问 127.0.0.1 时系统会偷偷登记，需手动到 Privacy 列表打开（macOS 15.3 该选项位置：System Settings → Privacy & Security → Local Network；找不到时点检测先触发登记）
- **Mihomo Party 的 system helper**：装 Mihomo Party 时需输一次密码授权 TUN 网卡

### 触发方式
1. **菜单栏小图标**（最可靠，永远显示）
2. **悬浮按钮**（修复后可用——红色"一键拔线"文字，HS 窗口右上角）

---

## 我们对 z2z63 源码的 3 个 patch

z2z63 v0.1.2 在我们环境下不能直接用，需以下修补：

### Patch 1：QString vs QVariant 比较 bug
**位置**：`src/app_settings.cpp:29`
**问题**：UNIX 分支用 `external_controller_type == "UNIX_DOMAIN"`（QString 比较），TCPIP 分支用 `external_controller_type_ == "TCPIP"`（QVariant 比较，结果不可靠）
**修**：把 `external_controller_type_` 改成 `external_controller_type`

### Patch 2：TCPIP 加载条件反了
**位置**：`src/app_settings.cpp:31`
**问题**：`if (external_controller.isValid() || external_controller.toString().isEmpty()) return {};`——任何场景都会 return 空，导致 TCPIP 配置永远无法 load
**修**：改成 `if (!external_controller.isValid() || external_controller.toString().isEmpty())`

### Patch 3：Float button 检测不到 Metal 全屏游戏
**位置**：`src/platform/window_listener.mm`
**问题**：用 AXUIElementCopyAttributeValue(kAXWindowsAttribute) 找 HS 窗口，全屏 Metal 游戏返回 0 个窗口
**修**：加 CGWindowListCopyWindowInfo 作 fallback，按 PID + 取最大 window rect

### 其他 quality-of-life
- `src/logger.cpp` 加 `logger->flush_on(spdlog::level::trace)`——否则 spdlog 缓冲 4KB 才写盘，调试不友好

---

## 失败路径汇总（不要再走）

### Android 早期方案（Stage 0-7 + Stage 7 真机测试）
完整原生 Kotlin App 已实现（spec/plan/code 全有），但**HS Android 客户端对任何系统级网络中断都崩溃式回主菜单**。无法靠 App 层修复。

- VpnService 启停 → CONNECTIVITY_CHANGE 触发 HS 退主菜单
- adb svc wifi/data 切换 → 同上
- iptables (需 root) → 用户手机未 root + bootloader 锁定，转向 Mac

**根因**：早期方案是"断 HS 全部流量"，HS 客户端检测到全局无网→ 退主菜单。
后续 Android 重启时改走 z2z63 同款"应用层关单 socket"思路（见下），验证成功。

详见 `docs/superpowers/specs/2026-05-16-hearthstone-disconnect-design.md` + plan 文档 + Android 阶段 commit 历史

### Mac 自研 scapy RST (mac/hs-rst.py)
**1-3 次实验中 50% 卡 UI / 25% 退出 / 25% 干净恢复**——HS Mac 客户端对外部 RST 反应不稳，无法生产化。

变体测过：
- 单轮双向 RST
- 单轮 inbound only RST
- 多轮 RST（waves）
- pf 隔离旧 4-tuple + scapy RST 复合方案

### Mac Mihomo + Python 自写 kill
killing 全部 HS 连接或仅 1119 都让 HS 退出。**问题不在我们的实现，而是 z2z63 的 filter（host=="" + 关闭 sniffer/fake-IP）锁定的是 BG 战斗会话连接（特定的、非 1119、非 3724 的某条 socket）**，不是普通的游戏协议连接。

### Mac 飞行模式切换
OnePlus/Mac 上飞行模式默认不带下 WiFi。programmatic toggle 在 macOS 10+ 需 root 或 system extension。pass。

---

## Android 验证方案（2026-05 重启）

### 验证结果
**OnePlus 10T (CPH2451, Android 15, arm64-v8a)** + **ClashMetaForAndroid v2.11.28-meta** + 国际服 HS (`com.blizzard.wtcg.hearthstone`, v35.4.241958)：z2z63 同款 filter 一次成功跳过 BG 战斗动画。

### 关键差异（vs macOS z2z63）
| 维度 | macOS | Android |
|---|---|---|
| filter 字段 | `metadata.processPath endsWith ".../Hearthstone"` | `metadata.process == "com.blizzard.wtcg.hearthstone"` |
| 战斗 socket 端口 | 1119 内 + 某条 host="" | **3724**（host=""，新增） |
| 战斗 socket 出现时机 | BG 战斗开始 | BG 战斗开始（一致） |
| API 暴露 | mihomo Party 默认 unix socket，需手动开 TCP | **CMFA 默认完全关，必须在 App 设置 → 覆盖 (Override) → 外部控制器开启** |
| host=="" 出现条件 | 关 sniffer + 关 fake-IP | 开 fake-IP 也行（只要游戏纯 IP 直连那条 socket） |

### Android 端验证 setup
```yaml
# /tmp/cmfa-setup/hs-skipper.yaml （CMFA URL import 用）
mixed-port: 7890
mode: rule
external-controller: 0.0.0.0:9090   # 注意：CMFA 默认会覆盖，必须 App 内开 Override
secret: ""
find-process-mode: always
tun: { enable: true, stack: mixed, auto-route: true, auto-detect-interface: true, dns-hijack: [any:53] }
dns: { enable: true, ipv6: false, enhanced-mode: fake-ip, fake-ip-range: 198.18.0.1/16,
       fake-ip-filter: ["*", "+.lan", "+.local"], nameserver: [https://doh.pub/dns-query] }
sniffer: { enable: false, parse-pure-ip: false }
proxies: [{name: dummy, type: socks5, server: 127.0.0.1, port: 65530}]  # CMFA 要求至少 1 个 proxy
proxy-groups: []
rules: [MATCH,DIRECT]
```

### Android 端验证操作流（手动）
```bash
# 一次性 setup
adb install cmfa-2.11.28-meta-arm64-v8a-release.apk
# CMFA 主页 → Settings → Override → External Controller 打开，地址 0.0.0.0:9090，secret 空
# 把 yaml 通过 URL import：
python3 -m http.server 8765 --directory /tmp/cmfa-setup &
adb reverse tcp:8765 tcp:8765
# CMFA → Profiles → "+" → URL → http://127.0.0.1:8765/hs-skipper.yaml
# 选中 profile → 启用 VPN（首次授权）
adb forward tcp:9091 tcp:9090   # 避开 Mac 上 mihomo-party 的 9090

# 验证 API 通
curl -s http://127.0.0.1:9091/version

# 进 BG 战斗动画时触发拔线（脚本见 /tmp/cmfa-setup/skip.sh）：
curl -s http://127.0.0.1:9091/connections \
  | jq -r '.connections[]
           | select(.metadata.process == "com.blizzard.wtcg.hearthstone"
                    and .metadata.host == "")
           | .id' | head -1 \
  | xargs -I{} curl -X DELETE "http://127.0.0.1:9091/connections/{}"
```

### Android 端待做（生产化）
1. 写一个 Android Overlay App（类似 z2z63 浮窗）调本机 mihomo HTTP API 触发 kill
2. 考虑直接内嵌 mihomo core .so（Go），用户只装一个 App，不依赖 CMFA
3. 处理 CMFA / 自带 mihomo 跟其他 VPN/代理的冲突（同时只能一个 VpnService）

---

## 当前 repo 结构

见 [`README.md`](README.md) 仓库结构段。

## TODO

### Mac
- [x] 把修补后的 z2z63 source fork 到 `github.com/mjsaber/hearthstone_skipper`，3 个 patch 已合 main
- [x] Mihomo 配置文件 snapshot 入 repo（`mac/mihomo/`）
- [x] 写 README.md 介绍如何在新机器上 setup 整套（[`mac/README.md`](mac/README.md)）
- [x] git commit bob-assist 当前工作（commit `6994b7a`，cross-platform 重构）
- [ ] 可选：写 Hammerspoon 脚本绑定全局热键，避开点菜单栏
- [ ] 可选：调浮动按钮的位置/字号（z2z63 默认是 30px 红字，可能太显眼）

### Android
- [x] Android 端 setup snapshot 入 repo（[`android/cmfa/`](android/cmfa/): `hs-skipper.yaml` + `skip.sh` + `setup.md`）
- [x] 之前的 Stage 0-7 老 Kotlin VpnService 实现从 working tree 删除（git 历史保留）
- [ ] 写 Android Overlay App（一键拔线浮窗，直接调本机 mihomo HTTP API）— TODO 详见 [`android/overlay-app/README.md`](android/overlay-app/README.md)
- [ ] 调研：能否内嵌 mihomo core .so，让用户只装一个 App
