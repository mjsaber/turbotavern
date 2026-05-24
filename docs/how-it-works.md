# How it works

## 问题

炉石酒馆战旗（BG）每回合战斗的动画播放短则十几秒、长则一分钟。每天玩一局 8 个回合等于花几分钟看动画。

## 关键观察

战斗动画期间，HS 客户端跟战斗服务器维持**一条专用 TCP 连接**，从这条 socket 上流式接收动画事件。但**服务端在战斗开始时就把整场战斗结果算完了**——客户端的动画只是在「播放」一段已知结果。

如果断掉那条 socket，HS 客户端会触发「单连接重连」分支：向服务端重新请求当前战斗状态——拿到的就是那个已经算好的最终结果，**动画整段被跳过**。

注意"单连接重连"是关键词：

- 「单条 socket 断开」走 HS 客户端的一个分支：温和重连，从服务端拿最新战局状态
- 「全局网络挂掉」走另一个完全不同的分支：弹错误对话框 → 退主菜单

两个分支共享几乎没有代码。这就是为什么粒度选错（"断 HS 全部网络"）会让方案彻底失败，详见 [failed-paths.md](failed-paths.md)。

## 实现挑战

操作系统不让 user-space 程序：
1. 列举某个进程的所有 TCP socket（带 metadata 比如对端域名、流量）
2. 精确关掉**单一条** TCP socket，不影响该进程其他连接

要么走 root（`iptables`、TCP RST 注入），要么找个已经接管了 TCP 流量的 user-space 程序来代劳。

我们选 **Clash/Mihomo** 当那个 user-space 程序——它本来就是为代理而设计的：TUN 模式接管系统 TCP 流量、在 user-space 重组 TCP 状态机、维护一张 connection 表、提供 HTTP API 列出和操作连接。

我们用它的两个能力：
1. **GET `/connections`**：列出所有活跃 TCP/UDP，带 process、host、IP、流量等 metadata
2. **DELETE `/connections/{id}`**：精确关掉某一条 socket（mihomo userspace `close()` 那个 fd，对游戏进程是一个干净的 EOF）

完全不需要 Clash 的「代理」功能（rule 写 `MATCH,DIRECT`，直接走本地网卡）。Clash 在这里是**用户态 TCP 中转层**，跟翻墙无关。

## Skip 协议

两个平台共用同一套思路，三步：

1. **把 HS 流量塞给 Mihomo**
   - macOS: Mihomo Party + TUN
   - Android: ClashMetaForAndroid + TUN
2. **GET `/connections`，filter 出「BG 战斗会话 socket」**
   - 共同条件：`metadata.process` / `processPath` 是 HS && `metadata.host == ""`
   - `host == ""` 是关键：HS 在 BG 战斗开始时打开一条**纯 IP 直连**的连接（不查 DNS），所以 mihomo 反查不到 host
3. **DELETE `/connections/{id}`**
   - HS 看到那条 socket 突然 EOF，走重连分支拉战斗结果，动画跳过

## 平台差异

| | macOS (Mihomo Party) | Android (CMFA) |
|---|---|---|
| Filter 字段 | `metadata.processPath endsWith "Hearthstone.app/Contents/MacOS/Hearthstone"` | `metadata.process == "com.blizzard.wtcg.hearthstone"` |
| 战斗 socket 端口 | 一条 host="" 直 IP（端口可变） | 3724（host=""，新增） |
| External Controller 开启 | profile 写 `external-controller` 即可（需开 TCP 而非默认 unix socket） | profile 写没用，**必须在 CMFA App 设置 → Override 里手动开** |
| Profile proxies 数组可为空 | 是 | 否（CMFA 强制至少 1 个 entry） |
| 触发 UI | z2z63 浮窗 / 菜单栏 | 当前是手动 ADB + curl；Overlay App 待写 |

## Reference

- [z2z63/hearthstone_skipper](https://github.com/z2z63/hearthstone_skipper) — 思路来源 + macOS 浮窗实现
- [我们的 fork (mjsaber)](https://github.com/mjsaber/hearthstone_skipper) — 含 3 个 bug fix
- [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) — Android 端 TCP 接管层
