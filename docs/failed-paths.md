# Failed paths

所有试过但没成功的方案，按时间倒序。每条记录策略、变体、根因，避免下次重走。

---

## 1. Android: VpnService 整个断 HS 网络

**时间**：2026-05 上半月（早期方案）

### 策略
原生 Kotlin App，`VpnService.Builder.addAllowedApplication("com.blizzard.wtcg.hearthstone")` 建 per-app TUN。"拔线"的实现：启停 VPN service / 启 VPN 后丢所有 packets。

### 变体
- VPN service start/stop 循环
- VPN 上把所有 packet 直接 drop
- 通过 ADB shell `svc wifi disable` + `enable` 切换全机 WiFi
- 通过 ADB shell `svc data disable` + `enable` 切换 cell
- WiFi + cell 同时切（用户提议）
- 飞行模式 toggle（Android 11+ 需 system app 权限，跳过）
- iptables/nftables（需 root，用户手机未 root + bootloader 锁定，跳过）

### 失败模式
**任何一种都让 HS 客户端直接退到主菜单**。无论恢复网络多快，HS 已经决定 fail-fast。

### 根因
HS Android 客户端对 ConnectivityManager 广播的"网络断开"事件做激进 fail-fast 处理。这是「全局无网络」分支，跟「单 socket 死」分支完全不同。

### 关键教训
**粒度错了**。我们以为"断 HS 网络"= 触发 BG 拔线，实际触发的是"游戏断网退出"完全不同的代码路径。BG 战斗动画跳过必须走"单 socket EOF"那一支。

### 替代方案
转向 Clash + 单 socket DELETE，2026-05-24 在同样设备上验证通过。见 [`android/README.md`](../android/README.md)。

---

## 2. Mac: scapy + pf TCP RST 注入

**时间**：2026-05 中

### 策略
- `lsof` 列 HS 进程的 ESTABLISHED TCP 4-tuple
- libpcap sniff 拿当前 SEQ
- scapy forge 两条 TCP RST 包（in/out 方向，spoof src IP），通过 raw socket 注入
- 同时用 macOS `pf` anchor 临时 block 旧 4-tuple，避免乱序 ACK 干扰本机 TCP 栈

老代码在 git history（`mac/hs-rst.py`, `mac/hs-drop.sh`），commit fed2bd2 之前。

### 变体
- 单轮双向 RST
- 单轮 inbound only RST
- 多轮 RST waves（不同间隔 100ms / 500ms / 1s）
- `pf block drop quick` + scapy RST 复合
- `pf block return-rst quick`（让内核自己回 RST 包）
- pf 持续 hold 3s + 单次 RST
- pf hold + RST wave

### 失败数据
3 次实验中：
- 50% 卡 UI（HS 不响应 RST，socket 留在 ESTABLISHED 但游戏 UI 冻结）
- 25% HS 直接退出
- 25% 干净恢复（实际成功跳过动画）

### 根因
HS Mac 客户端对外部 RST 的反应跟具体的 SEQ/window 状态强相关，无法稳定复现"干净 ECONNRESET"路径。RFC 5961 challenge ACK 机制在某些 macOS 内核版本上让 RST 被拒。pf `return-rst` 在 macOS 上也不可靠——RST 包并不总是真正递交本机 TCP 栈。

### 替代方案
转向 z2z63 思路：mihomo userspace TCP 关 socket，模拟"对端干净关闭"。100% 稳定。

---

## 3. Mac Mihomo + 自写 Python kill（pre-z2z63）

**时间**：2026-05 中后

### 策略
mihomo + 自写脚本，列 HS 连接 → 杀全部 / 只杀 1119（Battle.net 游戏协议端口）。

### 失败模式
杀全部或仅 1119 都让 HS 退出。

### 根因
1119 是 Battle.net **游戏主协议**端口，杀这条 = HS 看到"游戏服务器断了" → 退出。这跟"BG 战斗 socket 断了"不是同一回事——**BG 战斗用的是另一条 socket**（host="" 那条）。

z2z63 的 filter `host == ""` 找到的恰恰是 BG 战斗会话连接（一条纯 IP 直连，没经过 DNS 查询），杀这条触发的是"单战斗 socket 重连"分支，HS 拉战斗结果重画。

### 关键教训
"杀 HS 的连接"≠"跳过 BG 战斗动画"。必须精确锁定**战斗会话那一条** socket。

---

## 4. Mac 飞行模式 toggle

programmatic toggle 在 macOS 10+ 需要 root + system extension（NEFilterDataProvider）。个人项目门槛过高，跳过。

---

## 5. Mac 调 OS API 直接关别人 socket

调研过 macOS 是否有 user-space API 关另一个进程的 socket。结论：没有。`close(fd)` syscall 只能关自己的 fd；`launchctl kill -SIGUSR1` 之类对 HS 无意义；强行 kill 进程跟"动画跳过"无关。唯一的"官方"路径是 Network Extension framework，需 Developer ID + 系统扩展，pass。

---

## 信号/反信号汇总

什么 HS 客户端会 fail-fast 退主菜单：
- ConnectivityManager 广播 NO_NETWORK（Android）
- HS 的"游戏主协议"socket（1119 等已知端口）被 RST 或被关
- 多条 HS socket 同时死

什么 HS 客户端会走"单 socket 重连"温和分支（也就是我们要的）：
- 一条 **非主协议** socket 收到干净 FIN/EOF（mihomo `close()` 是干净的；外部 RST 不稳）
- 其他 HS 连接保持活跃
- 单条 socket 死后，HS 立刻看到那条 socket EOF 并发起对该 session 的重连请求

这两类反应是 HS 客户端代码里完全不同的两条路径，**任何"拔线"工具都必须确保只触发后者**。
