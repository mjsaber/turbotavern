package bobcore

import (
	"context"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"

	"github.com/metacubex/gvisor/pkg/buffer"
	"github.com/metacubex/gvisor/pkg/tcpip"
	gonet "github.com/metacubex/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/metacubex/gvisor/pkg/tcpip/header"
	"github.com/metacubex/gvisor/pkg/tcpip/link/channel"
	"github.com/metacubex/gvisor/pkg/tcpip/link/loopback"
	"github.com/metacubex/gvisor/pkg/tcpip/network/ipv4"
	"github.com/metacubex/gvisor/pkg/tcpip/stack"
	"github.com/metacubex/gvisor/pkg/tcpip/transport/tcp"
)

func tcpMeta(srcIP string, srcPort uint16, dstIP string, dstPort uint16) *C.Metadata {
	return &C.Metadata{
		NetWork: C.TCP,
		SrcIP:   netip.MustParseAddr(srcIP),
		SrcPort: srcPort,
		DstIP:   netip.MustParseAddr(dstIP),
		DstPort: dstPort,
	}
}

func TestKeyOf(t *testing.T) {
	if _, ok := keyOf(nil); ok {
		t.Fatal("nil metadata should be rejected")
	}
	if _, ok := keyOf(&C.Metadata{NetWork: C.UDP, SrcIP: netip.MustParseAddr("10.0.0.1"), SrcPort: 1, DstIP: netip.MustParseAddr("10.0.0.2"), DstPort: 2}); ok {
		t.Fatal("UDP should be rejected")
	}
	if _, ok := keyOf(tcpMeta("10.0.0.1", 0, "66.40.188.46", 1119)); ok {
		t.Fatal("zero src port should be rejected")
	}
	if _, ok := keyOf(tcpMeta("10.0.0.1", 5000, "66.40.188.46", 0)); ok {
		t.Fatal("zero dst port should be rejected")
	}
	k, ok := keyOf(tcpMeta("10.0.0.1", 5000, "66.40.188.46", 1119))
	if !ok {
		t.Fatal("valid TCP metadata should produce a key")
	}
	if k.src != netip.AddrPortFrom(netip.MustParseAddr("10.0.0.1"), 5000) ||
		k.dst != netip.AddrPortFrom(netip.MustParseAddr("66.40.188.46"), 1119) {
		t.Fatalf("unexpected key %+v", k)
	}
}

func TestRegistryCompareAndDelete(t *testing.T) {
	inboundByTuple = sync.Map{}
	md := tcpMeta("10.0.0.1", 5001, "66.40.188.46", 1119)
	k, _ := keyOf(md)

	old := &inboundEntry{key: k}
	neu := &inboundEntry{key: k}
	inboundByTuple.Store(k, old)
	inboundByTuple.Store(k, neu) // same key reused by a newer connection

	// The OLD handler's deferred CompareAndDelete must NOT delete the new entry.
	inboundByTuple.CompareAndDelete(k, old)
	v, ok := inboundByTuple.Load(k)
	if !ok || v.(*inboundEntry) != neu {
		t.Fatal("CompareAndDelete(old) wrongly removed the newer entry")
	}
	// The NEW handler's delete removes it.
	inboundByTuple.CompareAndDelete(k, neu)
	if _, ok := inboundByTuple.Load(k); ok {
		t.Fatal("entry should be gone")
	}
}

func TestWrapperImplementsBothInterfaces(t *testing.T) {
	var _ C.Tunnel = (*rstTunnel)(nil)
	var _ P.Tunnel = (*rstTunnel)(nil)
	if _, ok := any(&rstTunnel{}).(P.Tunnel); !ok {
		t.Fatal("rstTunnel must satisfy P.Tunnel (listener does tunnel.(P.Tunnel))")
	}
	if _, ok := any(&rstTunnel{}).(C.Tunnel); !ok {
		t.Fatal("rstTunnel must satisfy C.Tunnel")
	}
}

func TestResetInboundFallback(t *testing.T) {
	// A non-gonet conn must not be touched (no close) and must return false.
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	if resetInbound(a) {
		t.Fatal("resetInbound on a non-gonet conn must return false")
	}
	// a must still be usable (not closed by resetInbound).
	done := make(chan struct{})
	go func() { _, _ = b.Read(make([]byte, 1)); close(done) }()
	if _, err := a.Write([]byte{1}); err != nil {
		t.Fatalf("conn was closed by resetInbound: %v", err)
	}
	<-done
}

func TestUnwrapRobust(t *testing.T) {
	if unwrapTCPConn(nil) != nil {
		t.Fatal("nil -> nil")
	}
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	if unwrapTCPConn(a) != nil {
		t.Fatal("net.Pipe conn is not a gonet.TCPConn")
	}
}

// --- gvisor helpers ---

func newLoopbackStack(t *testing.T) (*stack.Stack, tcpip.Address) {
	t.Helper()
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol},
	})
	if err := s.CreateNIC(1, loopback.New()); err != nil {
		t.Fatalf("CreateNIC: %v", err)
	}
	addr := tcpip.AddrFrom4([4]byte{127, 0, 0, 1})
	pa := tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: addr.WithPrefix()}
	if err := s.AddProtocolAddress(1, pa, stack.AddressProperties{}); err != nil {
		t.Fatalf("AddProtocolAddress: %v", err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	return s, addr
}

// dialLoopbackTCP returns a connected client *gonet.TCPConn over a loopback stack.
func dialLoopbackTCP(t *testing.T) (*gonet.TCPConn, func()) {
	t.Helper()
	s, addr := newLoopbackStack(t)
	full := tcpip.FullAddress{NIC: 1, Addr: addr, Port: 19119}
	ln, err := gonet.ListenTCP(s, full, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("ListenTCP: %v", err)
	}
	accepted := make(chan net.Conn, 1)
	go func() {
		c, _ := ln.Accept()
		accepted <- c
	}()
	conn, err := gonet.DialTCP(s, full, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("DialTCP: %v", err)
	}
	var srv net.Conn
	select {
	case srv = <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("accept timed out")
	}
	cleanup := func() {
		_ = conn.Close()
		if srv != nil {
			_ = srv.Close()
		}
		_ = ln.Close()
		s.Close()
	}
	return conn, cleanup
}

func TestExtractEndpointLoopback(t *testing.T) {
	conn, cleanup := dialLoopbackTCP(t)
	defer cleanup()

	ep := extractEndpoint(conn)
	if ep == nil {
		t.Fatal("extractEndpoint returned nil for a real *gonet.TCPConn (reflection layout regression?)")
	}
	// resetInbound on the real conn must take the Abort path.
	if !resetInbound(conn) {
		t.Fatal("resetInbound should return true for a real *gonet.TCPConn")
	}
	// Abort again / on a torn-down endpoint must not panic.
	func() {
		defer func() {
			if r := recover(); r != nil {
				t.Fatalf("second Abort panicked: %v", r)
			}
		}()
		ep.Abort()
	}()
}

// TestAbortSendsRST proves Abort() emits a TCP RST (vs Close()'s FIN) by sniffing
// packets between two paired channel-endpoint stacks.
func TestAbortSendsRST(t *testing.T) {
	gotRST := runAbortAndSniff(t, func(c *gonet.TCPConn) { extractEndpoint(c).Abort() })
	if !gotRST {
		t.Fatal("Abort() did not produce a TCP RST")
	}
	gotRSTOnClose := runAbortAndSniff(t, func(c *gonet.TCPConn) { _ = c.Close() })
	if gotRSTOnClose {
		t.Fatal("Close() on an idle connection unexpectedly produced a RST (expected FIN)")
	}
}

// runAbortAndSniff sets up client/server stacks linked by paired channel
// endpoints, establishes an idle TCP connection, runs action on the SERVER conn,
// and reports whether a RST segment was observed leaving the server stack.
func runAbortAndSniff(t *testing.T, action func(*gonet.TCPConn)) bool {
	t.Helper()
	const mtu = 1500
	cliLink := channel.New(16, mtu, "")
	srvLink := channel.New(16, mtu, "")

	newStack := func(link *channel.Endpoint, ip [4]byte) (*stack.Stack, tcpip.Address) {
		s := stack.New(stack.Options{
			NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
			TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol},
		})
		if err := s.CreateNIC(1, link); err != nil {
			t.Fatalf("CreateNIC: %v", err)
		}
		addr := tcpip.AddrFrom4(ip)
		pa := tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber, AddressWithPrefix: addr.WithPrefix()}
		if err := s.AddProtocolAddress(1, pa, stack.AddressProperties{}); err != nil {
			t.Fatalf("AddProtocolAddress: %v", err)
		}
		s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
		return s, addr
	}

	cliStack, cliAddr := newStack(cliLink, [4]byte{10, 0, 0, 1})
	srvStack, srvAddr := newStack(srvLink, [4]byte{10, 0, 0, 2})
	defer cliStack.Close()
	defer srvStack.Close()

	rstSeen := make(chan struct{}, 1)
	stop := make(chan struct{})
	// pump copies packets from `from` link out-queue into `to` stack, inspecting
	// for RST flags. Repacketize so the receiver parses the IP header from data.
	pump := func(from, to *channel.Endpoint, watch bool) {
		for {
			select {
			case <-stop:
				return
			default:
			}
			pkt := from.Read()
			if pkt == nil {
				time.Sleep(time.Millisecond)
				continue
			}
			buf := pkt.ToBuffer()
			pkt.DecRef()
			b := buf.Flatten()
			if watch && isRST(b) {
				select {
				case rstSeen <- struct{}{}:
				default:
				}
			}
			np := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(b)})
			to.InjectInbound(ipv4.ProtocolNumber, np)
			np.DecRef()
		}
	}
	go pump(cliLink, srvLink, false) // client -> server
	go pump(srvLink, cliLink, true)  // server -> client (watch for RST)
	defer close(stop)

	full := tcpip.FullAddress{NIC: 1, Addr: srvAddr, Port: 19119}
	ln, err := gonet.ListenTCP(srvStack, full, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("ListenTCP: %v", err)
	}
	defer ln.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, _ := ln.Accept()
		accepted <- c
	}()
	dialFull := tcpip.FullAddress{NIC: 1, Addr: srvAddr, Port: 19119}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	cli, err := gonet.DialContextTCP(ctx, cliStack, dialFull, ipv4.ProtocolNumber)
	_ = cliAddr
	if err != nil {
		t.Fatalf("DialTCP: %v", err)
	}
	defer cli.Close()
	var srv net.Conn
	select {
	case srv = <-accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("accept timed out")
	}
	// Let the handshake settle; keep the connection idle (no unread data).
	time.Sleep(50 * time.Millisecond)

	action(srv.(*gonet.TCPConn))

	select {
	case <-rstSeen:
		return true
	case <-time.After(500 * time.Millisecond):
		return false
	}
}

func isRST(ipPacket []byte) bool {
	if len(ipPacket) < header.IPv4MinimumSize {
		return false
	}
	ip := header.IPv4(ipPacket)
	if ip.TransportProtocol() != tcp.ProtocolNumber {
		return false
	}
	tcpHdr := header.TCP(ip.Payload())
	if len(tcpHdr) < header.TCPMinimumSize {
		return false
	}
	return tcpHdr.Flags().Contains(header.TCPFlagRst)
}
