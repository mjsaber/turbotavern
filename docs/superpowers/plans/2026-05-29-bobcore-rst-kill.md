# Phase 1.5 — bobcore RST-on-kill 实现计划

**Date:** 2026-05-29
**Spec:** `docs/superpowers/specs/2026-05-29-bobcore-rst-kill-design.md` (v3, READY TO PLAN)
**Layer:** `android/bobcore/` (Go),只改 Go;Kotlin/指纹/overlay 不动。
**Build:** `./build-aar.sh`(gomobile bind → `overlay-app/app/libs/bobcore.aar`)。改动全是**未导出**符号,只有已存在的 `CloseConnection` 被改(签名不变)→ gomobile 绑定面不变。
**Test:** `go test ./...`(host 构建,**不需要设备/gomobile**,_test.go 不进 AAR)。验证用 gvisor 内存栈。

**Codex plan review round 1 → NOT READY,已修(round 2 待确认):** (P0#1) 自检不能依赖 `_test.go` helper → 改**首条真实连接上惰性自检**(无合成 loopback 进生产代码);(P0#2) 补全 imports(`P=constant/provider`、`sync/atomic`);(P1#3) RST 抓包测试改**双 stack + 配对 channel + InjectInbound**;(P1#4) `TestExtractEndpointBadType` 走 `unwrapTCPConn`/`resetInbound`,不给 `extractEndpoint` 传错类型;(P1#5) **`resetInbound` 失败/禁用时绝不碰入站**(保持现 ordering,交给 tracker.Close→Relay);(P2#6) 加 `*C.Metadata` 指针二级 key;(P2#7) `Abort` panic 也只返回 false、不关连接(= 现行为兜底)。

## 已核验的事实(写代码直接用)
- `gonet.TCPConn` 字段:`deadlineTimer`(嵌入)、`wq *waiter.Queue`、**`ep tcpip.Endpoint`**、`readMu`、`read`。反射目标字段名 = `ep`,类型 `tcpip.Endpoint`。
- `C.Metadata`:`SrcIP netip.Addr` / `DstIP netip.Addr` / `SrcPort uint16` / `DstPort uint16` / `NetWork`;便利方法 `SourceAddrPort() netip.AddrPort`、`AddrPort() netip.AddrPort`(目的)。
- `tunnel.Tunnel` = `tunnel{}` 值,`var _ C.Tunnel = Tunnel` + `var _ P.Tunnel = Tunnel`。listener 仅 `tunnel.(P.Tunnel)`,无 concrete 断言。
- gvisor import 路径:`github.com/metacubex/gvisor/...`(`pkg/tcpip`、`pkg/tcpip/adapters/gonet`、`pkg/tcpip/stack`、`pkg/tcpip/link/channel`、`pkg/tcpip/header`、`pkg/tcpip/transport/tcp`、`pkg/tcpip/network/ipv4`)。

---

## Task 1 — 入站注册表 + tunnel wrapper + 接线

**File:** `android/bobcore/bobcore.go`
**新增 imports:** `net/netip`、`sync`、`sync/atomic`、`reflect`、`unsafe`、`errors`、`P "github.com/metacubex/mihomo/constant/provider"`、`"github.com/metacubex/gvisor/pkg/tcpip"`、`gonet "github.com/metacubex/gvisor/pkg/tcpip/adapters/gonet"`(`net`/`strings`/`statistic`/`C`/`tunnel`/`sing_tun`/`log` 已有)。

```go
type fullTunnel interface { C.Tunnel; P.Tunnel }

type connKey struct {
    network string          // 只登记 "tcp"
    src     netip.AddrPort
    dst     netip.AddrPort
}
type inboundEntry struct {
    key  connKey
    conn net.Conn
}

var (
    inboundByTuple sync.Map   // connKey       -> *inboundEntry  (主)
    inboundByMeta  sync.Map    // *C.Metadata   -> *inboundEntry  (二级,指针精确,免 metadata 字段漂移 codex P2#6)
)

func loadInbound(md *C.Metadata) (*inboundEntry, bool) {
    if md != nil {                              // 先按 metadata 指针(若 HandleTCPConn 与 tracker 同一指针 → 精确命中)
        if v, ok := inboundByMeta.Load(md); ok {
            if e, ok := v.(*inboundEntry); ok { return e, true }
        }
    }
    if k, ok := keyOf(md); ok {                 // 再按 4 元组
        if v, ok := inboundByTuple.Load(k); ok {
            if e, ok := v.(*inboundEntry); ok { return e, true }
        }
    }
    return nil, false
}

// keyOf 仅对合法 TCP metadata 返回 ok=true。
func keyOf(md *C.Metadata) (connKey, bool) {
    if md == nil || md.NetWork != C.TCP { return connKey{}, false }
    if md.SrcPort == 0 || md.DstPort == 0 { return connKey{}, false }   // AddrPort.IsValid 只验地址(codex r2#1)
    src := md.SourceAddrPort()
    dst := md.AddrPort()
    if !src.IsValid() || !dst.IsValid() { return connKey{}, false }
    return connKey{network: "tcp", src: src, dst: dst}, true
}

type rstTunnel struct { fullTunnel }   // 嵌入,promote 全部方法

var (
    rstEnabled    atomic.Bool   // 惰性自检结果
    selfCheckOnce sync.Once
)

func (t *rstTunnel) HandleTCPConn(conn net.Conn, md *C.Metadata) {
    // 惰性自检(codex P0#1):首条真实连接上试取 ep,确定 RST 是否可用。只读,不扰动连接。
    selfCheckOnce.Do(func() {
        if tc := unwrapTCPConn(conn); tc != nil && extractEndpoint(tc) != nil {
            rstEnabled.Store(true)
        }
        log.Infoln("[bobcore] rst kill enabled=%v", rstEnabled.Load())
    })
    if k, ok := keyOf(md); ok {
        e := &inboundEntry{key: k, conn: conn}
        inboundByTuple.Store(k, e)
        inboundByMeta.Store(md, e)
        defer func() {
            inboundByTuple.CompareAndDelete(k, e)   // 条件删,防 stale-delete
            inboundByMeta.CompareAndDelete(md, e)
        }()
    }
    t.fullTunnel.HandleTCPConn(conn, md)
}
```

**接线:** `StartTun` 里 `sing_tun.New(options, &rstTunnel{fullTunnel: tunnel.Tunnel})`(替换原 `tunnel.Tunnel`)。

**编译期断言**(放文件里,防 P0 回归):
```go
var _ C.Tunnel = (*rstTunnel)(nil)
var _ P.Tunnel = (*rstTunnel)(nil)
```

> 注:`keyOf`、`unwrapTCPConn`、`extractEndpoint` 在 Task 2 定义(Task 1 的 `HandleTCPConn`/`loadInbound` 引用它们)——两个 Task 同一文件,合并编译验证。

**Verify:** `cd android/bobcore && go build ./...` 通过;`go vet ./...` 干净。

---

## Task 2 — `resetInbound` / `extractEndpoint` / 解包

**File:** `android/bobcore/bobcore.go`

```go
// 返回 true 仅当确实对 gvisor TCP endpoint 调了 Abort()。
// 关键(codex P1#5/P2#7):任何失败/panic 都【绝不动入站连接】,直接返回 false —
// 让 CloseConnection 后续的 tracker.Close()→Relay 按【现行为】收尾(优雅 FIN)。不改 ordering。
func resetInbound(c net.Conn) (rstAttempted bool) {
    defer func() { if recover() != nil { rstAttempted = false } }()
    tc := unwrapTCPConn(c)
    if tc == nil { return false }       // 非 gonet → 不碰,留给 tracker.Close
    ep := extractEndpoint(tc)
    if ep == nil { return false }       // 反射失败 → 不碰
    ep.Abort()                          // 发 RST
    return true
}

// 直接断言;必要时解一层 mihomo wrapper(Upstream())。
// 实现期先 log 首条连接的 reflect.TypeOf(conn) 确认真实类型,再决定要不要解包。
func unwrapTCPConn(c net.Conn) *gonet.TCPConn {
    for i := 0; c != nil && i < 4; i++ {
        if tc, ok := c.(*gonet.TCPConn); ok { return tc }
        u, ok := c.(interface{ Upstream() any })
        if !ok { return nil }
        next, ok := u.Upstream().(net.Conn)
        if !ok { return nil }
        c = next
    }
    return nil
}

var endpointType = reflect.TypeOf((*tcpip.Endpoint)(nil)).Elem()

func extractEndpoint(tc *gonet.TCPConn) (ep tcpip.Endpoint) {
    defer func() { if recover() != nil { ep = nil } }()
    f := reflect.ValueOf(tc).Elem().FieldByName("ep")
    if !f.IsValid() || f.Type() != endpointType { return nil }   // 字段名/类型校验
    p := unsafe.Pointer(f.UnsafeAddr())
    return *(*tcpip.Endpoint)(p)
}
```

> 自检不在这里 —— 改为 Task 1 `HandleTCPConn` 里**首条真实连接惰性自检**(`selfCheckOnce`),无需在生产代码构造合成 loopback(codex P0#1)。

**Verify:** `go build ./...` 通过。

---

## Task 3 — `CloseConnection` 接入 RST

**File:** `android/bobcore/bobcore.go`(新增 import:`errors`)

按 spec §3.2 改 `CloseConnection`:tracker 拿到后——

```go
rstAttempted := false
if rstEnabled.Load() {                                  // 仅自检通过才尝试(否则完全不碰入站,= 现行为,codex P1#5)
    if e, ok := loadInbound(tracker.Info().Metadata); ok {
        rstAttempted = resetInbound(e.conn)
    }
}
err := tracker.Close()                                  // 始终关出站
// err==nil → 0;否则 errors.Is(net.ErrClosed)+字符串兜底归一化:
//   rstAttempted && closedNoise → 0(双关噪声归一);closedNoise → 2;其它 → 4
if rstEnabled.Load() { log.Infoln("[bobcore] CloseConnection(%s) rst=%v", id, rstAttempted) }  // 仅 RST 启用时打日志(codex r2#4)
```

保留现有 `running`/`id==""`/`tracker==nil` 检查与返回码(1/2/3/4)。**`rstEnabled==false` 时 RST 路径与新日志全跳过,return/close 行为等于改前。**

**Verify:** `go build ./...` 通过;现有 `CloseConnection` 行为(非命中路径)等价于改前。

---

## Task 4 — Go 单测(gvisor 内存栈,无设备)

**File:** `android/bobcore/rstkill_test.go`(新建)

共用 helper:
```go
// 两个 gvisor stack,各挂一个 channel.Endpoint,互相用 InjectInbound 把对方写出的包泵进来
// (codex P1#3:单 stack 环回不一定经过 channel link,RST 抓不到)。返回 server 侧 *gonet.TCPConn。
func newPairedStacks(t *testing.T) (cliConn, srvConn *gonet.TCPConn, srvOut *channel.Endpoint, stop func())
```
泵包 goroutine:`for { pkt := ep.Read(); ... }`,**重新打包再注入**(codex r2#2:`channel.Endpoint.Read()` 的包头在 header 存储区,直接 `InjectInbound` 会让对端从 `Data()` 解析丢 IP 头)——`buf := pkt.ToBuffer(); newPkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buf}); peer.InjectInbound(proto, newPkt); pkt.DecRef()`。双向各一条 goroutine,直到 stop。读阻塞用 `context`/`time` 超时。

用例:
1. **`TestKeyOf`**:nil / 非 TCP / 残缺 metadata → ok=false;合法 TCP → 4 元组正确(`SourceAddrPort`/`AddrPort`)。
2. **`TestRegistryCompareAndDelete`**:Store entry,模拟"同 key 复用"再 Store 新 entry;旧 entry 的 `CompareAndDelete` **不**删新的;`loadInbound` 取到新的;`inboundByMeta` 指针 key 同理。
3. **`TestExtractEndpoint`**:`newPairedStacks` 拿到的 `*gonet.TCPConn` → `extractEndpoint` 非 nil 且实现 `tcpip.Endpoint`。
4. **`TestAbortSendsRST`(核心,抓包)**:配对栈建连接,**先 drain 完握手/任何数据、连接空闲**;对 server `*gonet.TCPConn` 的 ep `Abort()` → 读 `srvOut` channel 写出的包,`header.IPv4`+`header.TCP` 解析,断言出现 **`Flags()&header.TCPFlagRst != 0`**。对照组:另起一对,空闲后 `Close()` → 断言出 **FIN、无 RST**(codex r2#2:空闲已建立连接避免 unread-data 触发 RST)。
5. **`TestResetInboundFallback`**:传 `net.Pipe()` 一端(非 gonet)→ `resetInbound` 返回 false、**且未关闭该 conn**(codex P1#5:不碰)、不 panic。
6. **`TestUnwrapAndExtractRobust`**:`unwrapTCPConn` 对非 gonet / 多层 wrapper / nil 返回 nil(不传错类型给 `extractEndpoint`,codex P1#4);`extractEndpoint` 只接 `*gonet.TCPConn`,对正常/已关闭实例都不 panic。
7. **`TestWrapperImplementsBothInterfaces`**:编译期 `var _ C.Tunnel/_ P.Tunnel = (*rstTunnel)(nil)` + 运行期 `_, ok := any(&rstTunnel{}).(P.Tunnel); assert ok`(防 P0#1 回归)。

**Verify:** `cd android/bobcore && go test ./... -run . -count=1` 全绿。若 #4 抓包在 gvisor API 上过繁琐,**降级**为断言 `Abort` vs `Close` 在 channel 上产出**不同** TCP 标志(RST vs 非 RST),并以"真机 <1s 跳过"为决定性证据(codex P2#7 认可)。

---

## Task 5 — gomobile 重编 + 装机 + 真机验收

1. `cd android/bobcore && ./build-aar.sh` → 重新生成 `overlay-app/app/libs/bobcore.aar`(需 `ANDROID_NDK_HOME`/`ANDROID_HOME`)。
2. `cd ../overlay-app && ./gradlew :app:assembleDebug` → `adb -s e85c3473 install -r app/build/outputs/apk/debug/app-debug.apk`。
3. 重启 bob VPN + HS(让连接走新 TUN),logcat 确认 `[bobcore] rst kill enabled=true`。
4. **真机验收(决定性):** 进 BG,战斗动画中点绿圈 → **目测 < 1s 跳过**(对比改前 5-10s)。logcat:`CloseConnection(...) rst=true`。
5. **回归:** 多局游玩、登录/菜单无异常断连;VPN 稳定;故意把 `rstEnabled` 关掉(或破坏反射)→ 退优雅关、不崩。

**Verify:** 用户确认战斗中拔线 <1s;`rst=true` 出现在 log;无回归。

---

测试文件额外 imports:`testing`、`context`、`time`、`github.com/metacubex/gvisor/pkg/tcpip`(root)、`.../tcpip/{stack,header}`、`.../link/channel`、`.../network/ipv4`、`.../transport/tcp`、`.../adapters/gonet`。

## 依赖图
```
T1(注册表+wrapper+惰性自检) ──┐
T2(resetInbound/extract/解包)─┼─► T3(CloseConnection 接入) ──► T4(Go 单测) ──► T5(重编+真机)
                              ┘     (T1/T2 同文件,合并 go build 验证)
```

## DoD（对齐 spec §5)
1. `go build ./...` + `go vet` 干净;`go test ./...` 全绿(含抓包 RST 证明 + 反射字段断言 + 兜底 + 双接口)。
2. `build-aar.sh` 重编通过,APK 装上,log 显示 `rst kill enabled=true`。
3. 真机:战斗中拔线 <1s 跳过(用户确认);无回归。
4. RST 失败安全兜底(破坏反射 → 仍优雅关、不崩)。
5. 实现过 codex review(gate 3)。
```
