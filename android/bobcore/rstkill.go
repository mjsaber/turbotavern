package bobcore

// Phase 1.5 — RST-on-kill.
//
// CloseConnection() must make Hearthstone notice the dropped battle socket
// *immediately*. mihomo's tracker.Close() only closes the outbound conn; the
// Relay then closes the inbound (HS-facing) gonet.TCPConn with a graceful FIN.
// During the BG combat animation HS isn't reading that socket, so a FIN is not
// noticed until its ~15s keepalive/heartbeat — that is the 5-10s skip lag.
//
// Fix: on kill, send a TCP RST to the inbound endpoint (gvisor endpoint.Abort)
// so HS's kernel raises EPOLLERR and the client reacts at once.
//
// To reach the inbound conn we wrap mihomo's C.Tunnel and register each inbound
// conn (keyed by *Metadata pointer + 4-tuple) for the duration of HandleTCPConn.
// gonet.TCPConn only exposes a graceful Close(); the RST-capable endpoint.Abort
// lives on the unexported `ep tcpip.Endpoint` field, reached by reflect+unsafe.
// Every failure path is contained and falls back to the unchanged graceful path.

import (
	"net"
	"net/netip"
	"reflect"
	"sync"
	"sync/atomic"
	"unsafe"

	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"

	"github.com/metacubex/gvisor/pkg/tcpip"
	gonet "github.com/metacubex/gvisor/pkg/tcpip/adapters/gonet"
)

// fullTunnel is the union of interfaces mihomo's global tunnel.Tunnel satisfies.
// listener/sing_tun does `tunnel.(P.Tunnel)`, so the wrapper must carry both.
type fullTunnel interface {
	C.Tunnel
	P.Tunnel
}

type connKey struct {
	network  string
	src, dst netip.AddrPort
}

type inboundEntry struct {
	key  connKey
	conn net.Conn // inbound (HS-facing) gonet.TCPConn
}

var (
	inboundByTuple sync.Map // connKey      -> *inboundEntry
	inboundByMeta  sync.Map // *C.Metadata  -> *inboundEntry (exact: NewTCPTracker keeps the same pointer)

	rstEnabled    atomic.Bool
	selfCheckOnce sync.Once
)

// keyOf returns a 4-tuple key for a live TCP connection, or ok=false.
func keyOf(md *C.Metadata) (connKey, bool) {
	if md == nil || md.NetWork != C.TCP {
		return connKey{}, false
	}
	if md.SrcPort == 0 || md.DstPort == 0 {
		return connKey{}, false
	}
	src := md.SourceAddrPort()
	dst := md.AddrPort()
	if !src.IsValid() || !dst.IsValid() {
		return connKey{}, false
	}
	return connKey{network: "tcp", src: src, dst: dst}, true
}

// loadInbound finds the registered inbound conn for a connection's metadata,
// trying the exact *Metadata pointer first, then the 4-tuple.
func loadInbound(md *C.Metadata) (*inboundEntry, bool) {
	if md != nil {
		if v, ok := inboundByMeta.Load(md); ok {
			if e, ok := v.(*inboundEntry); ok {
				return e, true
			}
		}
	}
	if k, ok := keyOf(md); ok {
		if v, ok := inboundByTuple.Load(k); ok {
			if e, ok := v.(*inboundEntry); ok {
				return e, true
			}
		}
	}
	return nil, false
}

// rstTunnel wraps mihomo's tunnel.Tunnel: it registers each inbound TCP conn so
// CloseConnection can RST it, then delegates. All other methods are promoted.
type rstTunnel struct {
	fullTunnel
}

var (
	_ C.Tunnel = (*rstTunnel)(nil)
	_ P.Tunnel = (*rstTunnel)(nil)
)

func (t *rstTunnel) HandleTCPConn(conn net.Conn, md *C.Metadata) {
	// Lazy self-check on the first real connection: confirm we can reach the
	// gvisor endpoint, else leave RST disabled (graceful fallback everywhere).
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
			inboundByTuple.CompareAndDelete(k, e)
			inboundByMeta.CompareAndDelete(md, e)
		}()
	}
	t.fullTunnel.HandleTCPConn(conn, md)
}

// resetInbound sends a TCP RST to the inbound conn via the gvisor endpoint.
// Returns true only when Abort() was actually invoked. On ANY failure or panic
// it touches nothing and returns false, leaving teardown to the unchanged
// tracker.Close()->Relay path (graceful FIN, current behavior).
func resetInbound(c net.Conn) (rstAttempted bool) {
	defer func() {
		if recover() != nil {
			rstAttempted = false
		}
	}()
	tc := unwrapTCPConn(c)
	if tc == nil {
		return false
	}
	ep := extractEndpoint(tc)
	if ep == nil {
		return false
	}
	ep.Abort()
	return true
}

// unwrapTCPConn returns the underlying *gonet.TCPConn, peeling a few mihomo
// wrapper layers if present. Returns nil for non-gvisor conns.
func unwrapTCPConn(c net.Conn) *gonet.TCPConn {
	for i := 0; c != nil && i < 4; i++ {
		if tc, ok := c.(*gonet.TCPConn); ok {
			return tc
		}
		u, ok := c.(interface{ Upstream() any })
		if !ok {
			return nil
		}
		next, ok := u.Upstream().(net.Conn)
		if !ok {
			return nil
		}
		c = next
	}
	return nil
}

var endpointType = reflect.TypeOf((*tcpip.Endpoint)(nil)).Elem()

// extractEndpoint reads gonet.TCPConn's unexported `ep tcpip.Endpoint` field.
// Returns nil (never panics) if the field is missing or not a tcpip.Endpoint.
func extractEndpoint(tc *gonet.TCPConn) (ep tcpip.Endpoint) {
	defer func() {
		if recover() != nil {
			ep = nil
		}
	}()
	f := reflect.ValueOf(tc).Elem().FieldByName("ep")
	if !f.IsValid() || f.Type() != endpointType {
		return nil
	}
	p := unsafe.Pointer(f.UnsafeAddr())
	return *(*tcpip.Endpoint)(p)
}
