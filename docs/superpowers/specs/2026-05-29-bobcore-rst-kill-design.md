# Phase 1.5 — bobcore RST-on-kill(快速拔线)设计文档

**Date:** 2026-05-29
**Status:** v3 (post codex review round 2 → READY TO PLAN)
**Layer:** bobcore (Go / gomobile),mihomo v1.19.25 + sing-tun v0.4.18 + gvisor,**只改 Go 层,Kotlin 不动**
**Scope:** 把"拔线"从优雅 FIN 改成对 HS 客户端发 **RST**,消除 HS 端 5-10s 的反应延迟。

---

## Codex Review(round 1, 2026-05-29)

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | **P0** | `rstTunnel` 只实现 `C.Tunnel`,但 `listener/sing_tun.New` 会 `tunnel.(P.Tunnel)`(还调 `RuleProviders()`/`RuleUpdateCallback()`)→ 启动即 panic。 | §3.1 改:wrapper **嵌入联合接口 `fullTunnel{ C.Tunnel; P.Tunnel }`**(`tunnel.Tunnel` 同时满足二者,见 `var _ C.Tunnel/_ P.Tunnel = Tunnel`),只覆写 `HandleTCPConn`,其余方法全部 promote。listener 仅断言 `P.Tunnel`,无 concrete 断言,故安全。 |
| 2 | P1 | `Abort()` 只在 pin 死的 gvisor 具体 TCP endpoint 上才保证发 RST;`tcpip.Endpoint.Abort()` 接口语义是 best-effort。 | §3.3:严格 gate 到 `*gonet.TCPConn`;每次 kill 记 `rst=sent\|fallback`;非 gvisor/非 TCP 一律优雅兜底。 |
| 3 | P1 | 源 `ip:port` 单独不足以唯一标识 —— TCP 身份是 4 元组,源端口可能复用。 | §3.2 改 key = **network + 源 AddrPort + 目的 AddrPort**(`netip.AddrPort`,非字符串拼接)。直连战斗 socket(host="")目的 IP 两端一致,天然命中;域名连接两端表示可能不同 → 未命中即优雅兜底(无害)。 |
| 4 | P1 | `defer Delete(key)` 可能删掉"同 key 复用的新连接"(stale-delete race)。 | §3.2:存**带指针的 entry**,`CompareAndDelete(key, entry)` 条件删除。 |
| 5 | P1 | 反射/unsafe 兜底不"安全",除非每条 panic 路径都被吞掉(字段错、import 路径错、wrapper 类型、接口字段提取错都会崩)。 | §3.3:`extractEndpoint` 包 `defer recover`;校验字段类型可赋给 `tcpip.Endpoint`;解包 upstream wrapper;**启动自检**(self-check)早预警;长期可换方案 B。 |
| 6 | P2 | Abort 后再 `tracker.Close()`,Relay 可能同时关出站 → 双关 race;此时返回 `AlreadyClosed` 误导。 | §3.2:记 `rstSent`;若已发 RST,把 `net.ErrClosed`/"use of closed network connection" 归一化为 Success(仍 log 真实结果)。 |
| 7 | P2 | loopback Go 测试只能证明"Abort≠Close",证明不了 Android 内核真收到 RST、唤醒 epoll、HS 在不读时反应。 | §5:loopback 测试改为**抓包断言出站 TCP 带 RST 标志**(channel endpoint/sniffer);真机验收为决定性证据(保留)。 |
| 8 | P2 | loopback `Read==ECONNRESET` 断言会因对端有缓冲数据而 flaky。 | §5:不靠 `Read`,直接断言 RST 包标志;或带 deadline 循环、EOF 即失败。 |
| 9 | P3 | `v.(net.Conn)` 无校验类型断言,脆。 | §3.2:存 typed `inboundEntry`,kill 路径不做裸断言。 |

---

## 1. Why(问题 + 真机证据)

2026-05-29 真机(OnePlus 10T,国际服 HS)实测:

- **指纹修对后(`host=="" && tcp && port∈{1119,3724}`)绿圈正常**,点击拔线**机制成立**(用户确认:动画会跳过)。
- **但 app 侧不慢**:两次成功拔线(BobTrace cycle 117/505)`tap→close=Success` 全程 **~7–20ms**,`snapshot_ms≈1ms`,`delay_ms=0`(无排队)。
- **慢在 HS 端**:用户体感 **5-10s** 才跳。

根因(源码确认):
- `bobcore.CloseConnection` → `tracker.Close()` 只关**出站**(到服务器)那条。
- mihomo `Relay`(`common/net/sing.go`)在出站关闭后,通过 `left.Close()` 关**入站(面向 HS)**那条 —— 入站是 `gonet.TCPConn`,其 `Close()` 是**优雅 FIN**。
- HS 在播战斗动画时**没在读这条 socket**(结果早缓冲好),半关的 FIN 不会唤醒它的事件循环;sing-tun 还给入站 endpoint 设了 **keepalive idle/interval = 15s**。所以 HS 要等心跳/下次读(秒级到十几秒)才发觉 → 才跳。
- **CMFA 当年快**,用户确认其 close 落成 **RST**:RST 触发对端内核 `EPOLLERR`,**立刻**唤醒 HS 网络层 → 秒跳。

**结论:要快,就得让拔线对 HS 那侧发 RST,而不是 FIN。** 能力存在 —— gvisor `endpoint.Abort()` 就是发 RST(sing-tun 自己在 handler 出错时就用它)。

---

## 2. Goals / Non-Goals

### Goals
1. `CloseConnection(id)` 在关连接时,对**该连接面向 HS 的入站 endpoint 发 RST**(`Abort`),让 HS 立刻感知断线。
2. 真机:战斗动画中拔线 → **< 1s** 跳过(目标对齐 CMFA 体感),从 5-10s 砍掉。
3. **零回归 / 安全兜底**:拿不到入站连接、或 RST 路径失败时,回退到现在的优雅关闭,行为不比现在差。
4. 只改 bobcore Go 层;Kotlin / 指纹 / overlay / Phase 1.4 全不动。

### Non-Goals
- ❌ 不改 `system`/`mixed` TUN 栈(只支持我们在用的 **gvisor**;非 gvisor 走兜底)。
- ❌ 不动 UDP 路径。
- ❌ 本期不 fork sing-tun(先用自包含方案验证;若需要再议,见 §6)。
- ❌ 不改连接选择/指纹/状态机。

---

## 3. 设计

### 3.1 入站连接注册表(零反射拿到入站 conn)

bobcore 现在把 mihomo 全局 `tunnel.Tunnel`(实现 `C.Tunnel`)直接传给 `sing_tun.New`。`C.Tunnel` 接口:

```go
HandleTCPConn(conn net.Conn, metadata *C.Metadata)   // 每条 TCP 连接一次,阻塞到连接结束
HandleUDPPacket(packet C.UDPPacket, metadata *C.Metadata)
```

**包一层(嵌入联合接口,promote 全部方法 —— 修 codex P0 #1):**

```go
// tunnel.Tunnel(= tunnel{})同时满足 C.Tunnel 与 P.Tunnel;listener 会 tunnel.(P.Tunnel)。
type fullTunnel interface {
    C.Tunnel
    P.Tunnel
}

type connKey struct {           // 4 元组(codex P1 #3)
    network    string           // "tcp"
    src, dst   netip.AddrPort
}
type inboundEntry struct {      // typed,kill 路径不做裸断言(codex P3 #9)
    key  connKey
    conn net.Conn               // 入站 gonet.TCPConn(面向 HS)
}

type rstTunnel struct {
    fullTunnel                  // 嵌入 → C.Tunnel + P.Tunnel 全部方法 promote
}

func (t *rstTunnel) HandleTCPConn(conn net.Conn, md *C.Metadata) {
    k, ok := keyOf(md)          // 取不出合法 4 元组 → 不注册,直接委托
    if ok {
        e := &inboundEntry{key: k, conn: conn}
        inboundConns.Store(k, e)
        defer inboundConns.CompareAndDelete(k, e)   // 条件删除,防 stale-delete(codex P1 #4)
    }
    t.fullTunnel.HandleTCPConn(conn, md)            // 委托 mihomo,阻塞到连接结束
}
// HandleUDPPacket / Providers / RuleProviders / RuleUpdateCallback ... 全走 promote,不覆写。
```

`StartTun` 改成 `sing_tun.New(options, &rstTunnel{fullTunnel: tunnel.Tunnel})`。

- **key = (network, srcAddrPort, dstAddrPort)**:用 `netip.AddrPort`,非字符串拼接。源端口唯一 + 目的兜底,避免端口复用误伤。
- `inboundConns` 用普通 `sync.Map`(Go 的 `sync.Map` 非泛型,codex r2 #3)+ typed load helper(`load(k) (*inboundEntry, bool)`)。进入即存、`CompareAndDelete` 条件删,无泄漏、无 stale 删。

### 3.2 kill 时发 RST

```go
func CloseConnection(id string) int {
    // ...现有 running / id / tracker 检查不变...
    tracker := statistic.DefaultManager.Get(id)
    if tracker == nil { return 1 }

    rstAttempted := false
    if k, ok := keyOf(tracker.Info().Metadata); ok {
        if v, ok := inboundConns.Load(k); ok {
            rstAttempted = resetInbound(v.(*inboundEntry).conn)   // §3.3:RST 入站,返回是否真发了 RST
        }
    }
    // 仍关出站(停服务器侧流量);Relay 会因两侧关闭收尾。
    err := tracker.Close()
    if err == nil { return 0 }
    msg := err.Error()
    closedNoise := errors.Is(err, net.ErrClosed) ||                       // 先按错误类型(codex r2 #4)
        strings.Contains(msg, "use of closed network connection") ||      // 再字符串兜底 mihomo/gvisor wrapper
        strings.Contains(msg, "already closed")
    if rstAttempted && closedNoise {
        return 0      // 已发 RST 达成目标,出站的双关噪声归一化为 Success(codex P2 #6)
    }
    if closedNoise { return 2 }                              // AlreadyClosed(现有语义)
    log.Warnln("[bobcore] CloseConnection(%s) err: %s", id, msg)
    return 4
}
```

- 顺序:**先 RST 入站(HS 立刻知道)**,再关出站。
- 找不到入站 conn / `keyOf` 失败 → 跳过 RST,仍 `tracker.Close()`(兜底 = 现行为)。
- `tracker.Info().Metadata` 的 Src/Dst 与 `HandleTCPConn` 入口的一致性见 OQ-1。

### 3.3 `resetInbound`:对 `gonet.TCPConn` 发 RST

`gonet.TCPConn` 只暴露优雅 `Close()`(FIN),发 RST 的 `endpoint.Abort()` 在其未导出字段 `ep tcpip.Endpoint` 上。两条路:

- **方案 A(本期,自包含):反射 + unsafe 取出 `ep` 调 `Abort()`**。`resetInbound` 返回是否走了 Abort 路径(rstAttempted —— Abort 在非连接态可能 no-op,故不号称必定发出,codex r2 #1)。
  ```go
  // 返回 true 仅当确实对 gvisor TCP endpoint 调了 Abort()。
  func resetInbound(c net.Conn) (rstAttempted bool) {
      defer func() { _ = recover() }()            // 吞掉一切 panic(codex P1 #5)
      tc, ok := unwrapTCPConn(c)                  // 解包可能的 upstream/统计 wrapper
      if !ok { gracefulClose(c); return false }   // 非 *gonet.TCPConn → 优雅兜底
      ep := extractEndpoint(tc)                    // 反射取 ep,校验可赋给 tcpip.Endpoint
      if ep == nil { _ = tc.Close(); return false }
      ep.Abort()                                  // 发 RST
      return true
  }
  ```
  - **严格 gate**(codex P1 #2):只对解包后确为 `*github.com/metacubex/gvisor/.../gonet.TCPConn` 的连接走 Abort;其它一律优雅关。
  - **panic 全吞**(codex P1 #5):`extractEndpoint` 与 `resetInbound` 都 `defer recover`;字段名/类型(`ep` 必须可赋给 `tcpip.Endpoint`)校验失败即返回 nil → 兜底。
  - **启动自检**:`Setup`/`StartTun` 时跑一次 `selfCheckEndpointExtraction()`——构造一个 gvisor loopback `*gonet.TCPConn`,确认能取出非 nil `ep`;失败则把全局 `rstEnabled=false`,所有 kill 走优雅兜底(early warning,绝不带病上)。
- **方案 B(治本,留作 §6):** go.mod `replace` 一个小补丁 sing-tun / 加导出 helper 暴露 `Abort` 或注册 endpoint —— 不用反射。本期不做,先用 A 最低成本验证 RST 能把 5-10s 砍成秒级。

> 反射依赖 `gonet.TCPConn{ wq; ep }` 的字段名/布局(gvisor `v0.0.0-20251227095601`,go.mod pin 死)。pin + 全吞 panic + 启动自检 + 兜底:**最坏退化为今天的优雅关,绝不崩、不卡。**

### 3.4 不变量

- **INV-1(零回归 + 启动自检):** RST 路径任何一步失败(类型不符 / 反射 panic / 未命中)→ 回退 `tracker.Close()`(= 现行为);启动自检不过则全局禁用 RST。绝不因新代码崩溃或卡住。
- **INV-2(只 RST 被点的那条):** 只对 `CloseConnection(id)` 命中的连接发 RST;其它连接的正常关闭路径(Relay 的 `left.Close()`)不变,普通流量不受影响。
- **INV-3(4 元组定位):** 注册表按 (network, srcAddrPort, dstAddrPort) 索引;命中才 RST,未命中优雅兜底,不会误伤别的连接。
- **INV-4(生命周期 + 条件删):** 进入即存、`CompareAndDelete` 条件删(只删自己存的那个 entry),无泄漏、无 stale-delete。
- **INV-5(并发安全):** `Abort()` 对已关/正在关的 endpoint 是 no-op(不 panic);`CloseConnection` 与 `HandleTCPConn` 的 `defer CompareAndDelete` 并发安全(见 OQ-5)。

---

## 4. 影响面

| 文件 | 改动 |
|---|---|
| `android/bobcore/bobcore.go` | 新增 `rstTunnel` wrapper + `inboundConns sync.Map` + `srcKey` + `resetInbound` + `extractEndpoint`;`StartTun` 传 wrapper;`CloseConnection` 增加 RST 步骤 |
| `android/bobcore/*_test.go` | 新增 Go 单测(注册表 + 反射取 ep + gvisor loopback RST 验证) |
| AAR | `gomobile bind` 重编 `libs/bobcore.aar`;`overlay-app` 重装 |

Kotlin 侧 `MihomoCore.closeConnection` / Phase 1.4 / 指纹 **全不改**。

---

## 5. 验证计划

### Go 单测(不需要设备)
1. **注册表 + 条件删**:模拟 `HandleTCPConn` 进/出,断言存/删;构造"同 key 复用"场景断言 `CompareAndDelete` **不**误删新 entry(codex P1 #4)。`keyOf` 对 nil/残缺 metadata 返回 ok=false。
2. **反射取 ep(回归预警)**:gvisor `stack`+`gonet` 建 loopback `*gonet.TCPConn`,断言 `extractEndpoint` 取出非 nil 且可赋给 `tcpip.Endpoint`;`selfCheckEndpointExtraction()` 返回 true。
3. **RST 抓包断言(codex P2 #7/#8)**:用 gvisor `channel.Endpoint`(可观测发出的包)建连接,`Abort()` 后**断言发出的 TCP 段带 `RST` 标志位**;对照组 `Close()` 发 `FIN`。**不靠 `Read==ECONNRESET`**(避免缓冲 flaky)。
4. **兜底/不崩**:传入非 `*gonet.TCPConn`(普通 `net.Pipe` 或 stub)→ `resetInbound` 返回 false、走 `Close()`、不 panic;`extractEndpoint` 对错误类型/被破坏字段返回 nil(模拟反射失败)。
5. **wrapper 接口完整性**:编译期 `var _ C.Tunnel = (*rstTunnel)(nil)` + `var _ P.Tunnel = (*rstTunnel)(nil)`;运行期断言 `(*rstTunnel)(nil)`...实际用实例断言 `tunnel.(P.Tunnel)` 不 panic(防 codex P0 #1 回归)。

### 真机验收(OnePlus 10T)
- 进 BG,战斗动画中点绿圈;**目测跳过 < 1s**(对比改前 5-10s)。
- BobTrace 仍显示 app 侧 ~ms;新增一条 breadcrumb/log 标明"RST 路径已走 vs 兜底"。
- 回归:普通游玩(登录、菜单、多局)无异常断连;VPN 稳定。

### DoD
1. Go 单测全绿(含 loopback RST 证明 + 反射字段断言 + 兜底)。
2. `gomobile bind` 重编通过,APK 装上。
3. 真机:战斗中拔线 < 1s 跳过(用户确认);无回归。
4. RST 失败时安全兜底(故意破坏反射 → 仍优雅关、不崩)。
5. 每个 gate 过 codex review。

---

## 6. Open Questions(codex 重点看)

- **OQ-1(key 一致性,实现期验证):** `HandleTCPConn` 入口 metadata 的 Src/Dst 与 `CloseConnection` 时 `tracker.Info().Metadata` 的 Src/Dst 是否一致?源地址全程不变(几乎确定);目的对**直连战斗 socket(host="")两端都是同一直连 IP**,必然一致 → 战斗 socket 必命中。域名连接两端目的表示可能不同 → 未命中即优雅兜底(我们只需要战斗 socket 命中,可接受)。实现期加日志确认实际命中率。
- **OQ-2(反射 vs fork):** 本期方案 A(反射+自检+兜底)先验证;若 codex 仍认为 unsafe 不可接受,退方案 B(sing-tun `replace` 补丁)。倾向 A。
- **OQ-3(Abort 语义,单测验证):** `endpoint.Abort()` 在连接态发 RST(codex 已确认 `resetConnectionLocked(&ErrAborted{})`);§5 测试 3 抓包坐实。Abort 后仍 `tracker.Close()` 收尾,双关噪声按 §3.2 归一化。
- **OQ-4(RST 真能解决 —— 核心假设):** 方案 A 就是**最低成本证实/证伪**。若真机 RST 仍慢,则问题不在 FIN/RST 而在 HS 行为,需重判(不再投入 B)。真机验收是唯一决定性证据(codex P2 #7)。
- **OQ-5(并发):** Load 到正在结束的 conn 并 Abort —— gvisor `Abort()` 对已关 endpoint 是 no-op(§INV-5);`defer recover` 再兜一层。
