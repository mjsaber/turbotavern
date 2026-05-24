// Package bobcore is the embedded mihomo facade for Bob Assistant Android.
//
// Phase 0 surface (Spike A/B):
//   - Version() string                          — sentinel for Spike A
//   - Setup(homeDir string) string              — Spike B: home dir + log forwarder + empty mihomo config
//   - StartTun(fd, stack, gateway, dns) string  — Spike B: hand-wire sing_tun.New (CMFA-style)
//   - StopTun() string                          — Spike B: close TUN listener
//
// Design follows CMFA's core/src/main/golang/native/tun/tun.go: instead of
// feeding a full YAML profile to executor.ApplyConfig (which auto-derives
// TUN inet4-address from dns.fake-ip-range and tries to bind 198.18.0.1 on
// an Android TUN owned by VpnService with a different address), we:
//   1. Initialize mihomo with an empty config so its tunnel/dispatcher loop is wired.
//   2. Construct an LC.Tun ourselves with explicit FileDescriptor + Inet4Address.
//   3. Call sing_tun.New(options, tunnel.Tunnel) directly.
package bobcore

import (
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"syscall"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/executor"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"

	_ "github.com/metacubex/mihomo/tunnel/statistic"
)

// Protector is implemented by the Kotlin side: passes the fd to
// android.net.VpnService.protect(fd) and returns true if the socket has
// been marked to bypass the VPN tunnel.
type Protector interface {
	Protect(fd int) bool
}

var (
	mu           sync.Mutex
	inited       bool
	tunListener  io.Closer
	logForwarder *logSubscription

	protectorMu sync.RWMutex
	protector   Protector
)

// SetProtector installs the Kotlin-side VpnService.protect callback. Must
// be called BEFORE Setup so mihomo's first dialer hook invocation already
// sees a real protector.
func SetProtector(p Protector) {
	protectorMu.Lock()
	defer protectorMu.Unlock()
	protector = p
}

func currentProtector() Protector {
	protectorMu.RLock()
	defer protectorMu.RUnlock()
	return protector
}

// Version returns the bobcore build version.
func Version() string {
	return "0.0.1-prototype"
}

// Setup prepares mihomo's home dir, starts the log forwarder, and applies
// an empty config so mihomo's internal tunnel/dispatcher state is wired.
// Idempotent.
func Setup(homeDir string) string {
	mu.Lock()
	defer mu.Unlock()
	if inited {
		return ""
	}
	if homeDir == "" {
		return "homeDir is empty"
	}
	C.SetHomeDir(homeDir)
	logForwarder = startLogForwarder()

	// CMFA-style socket hook: every outbound dial from mihomo's DIRECT
	// outbound must be protect()'ed by the Kotlin VpnService, otherwise the
	// SYN packet re-enters the TUN that mihomo itself owns → self-loop
	// → dial never completes → HS sees DNS resolve OK but no TCP response.
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		p := currentProtector()
		if p == nil {
			return fmt.Errorf("bobcore: no Protector installed; call SetProtector first")
		}
		var protectErr error
		controlErr := conn.Control(func(fd uintptr) {
			if !p.Protect(int(fd)) {
				protectErr = fmt.Errorf("VpnService.protect(%d) returned false", int(fd))
			}
		})
		if controlErr != nil {
			return controlErr
		}
		return protectErr
	}

	// Minimal YAML: empty proxies / rules MATCH,DIRECT, with a real DNS
	// upstream so mihomo can answer the queries it hijacks from the TUN
	// (sing_tun rewrites HS's DNS-to-10.99.0.2:53 packets into mihomo's
	// internal DNS resolver — that resolver needs a real upstream to
	// actually return IPs, otherwise HS sees DNS timeout and never builds
	// the TCP socket to Blizzard).
	//
	// `tun.enable: false` here so mihomo's parseTun does NOT try to spin
	// up its own TUN listener at fake-ip-range; we drive sing_tun.New
	// directly from StartTun below.
	const minimalYaml = `
mode: rule
log-level: info
find-process-mode: off
tun:
  enable: false
dns:
  enable: true
  ipv6: false
  enhanced-mode: redir-host
  nameserver:
    - 8.8.8.8
    - 1.1.1.1
sniffer:
  enable: false
proxies: []
proxy-groups: []
rules:
  - MATCH,DIRECT
`
	cfg, err := config.Parse([]byte(minimalYaml))
	if err != nil {
		return "config.Parse: " + err.Error()
	}
	executor.ApplyConfig(cfg, true)
	inited = true
	return ""
}

// StartTun hands the VpnService.Builder fd to mihomo and starts the TUN
// listener with the explicit gateway/dns we want — mirroring CMFA's
// tun.Start.
//
//   fd       — detached VpnService fd
//   stack    — "system" / "gvisor" / "mixed"
//   gateway  — comma-separated CIDRs, e.g. "10.99.0.1/30"
//   dns      — comma-separated DNS hijack targets, e.g. "10.99.0.2"
func StartTun(fd int, stack, gateway, dns string) string {
	mu.Lock()
	defer mu.Unlock()
	if !inited {
		return "Setup not called"
	}
	if tunListener != nil {
		return "already running"
	}

	tunStack, ok := C.StackTypeMapping[strings.ToLower(stack)]
	if !ok {
		tunStack = C.TunSystem
	}

	var prefix4 []netip.Prefix
	for _, gatewayStr := range strings.Split(gateway, ",") {
		gatewayStr = strings.TrimSpace(gatewayStr)
		if gatewayStr == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(gatewayStr)
		if err != nil {
			return fmt.Sprintf("parse gateway %q: %s", gatewayStr, err.Error())
		}
		if prefix.Addr().Is4() {
			prefix4 = append(prefix4, prefix)
		}
	}
	if len(prefix4) == 0 {
		return "no IPv4 gateway"
	}

	var dnsHijack []string
	for _, dnsStr := range strings.Split(dns, ",") {
		dnsStr = strings.TrimSpace(dnsStr)
		if dnsStr == "" {
			continue
		}
		dnsHijack = append(dnsHijack, net.JoinHostPort(dnsStr, "53"))
	}

	options := LC.Tun{
		Enable:              true,
		Device:              sing_tun.InterfaceName,
		Stack:               tunStack,
		DNSHijack:           dnsHijack,
		AutoRoute:           false,
		AutoDetectInterface: false,
		Inet4Address:        prefix4,
		MTU:                 9000,
		FileDescriptor:      fd,
	}

	listener, err := sing_tun.New(options, tunnel.Tunnel)
	if err != nil {
		return "sing_tun.New: " + err.Error()
	}
	tunListener = listener
	log.Infoln("[bobcore] TUN listener started fd=%d gateway=%s stack=%s", fd, gateway, stack)
	return ""
}

// StopTun closes the TUN listener. Idempotent.
func StopTun() string {
	mu.Lock()
	defer mu.Unlock()
	if tunListener == nil {
		return ""
	}
	listener := tunListener
	tunListener = nil
	if err := listener.Close(); err != nil {
		return "listener.Close: " + err.Error()
	}
	log.Infoln("[bobcore] TUN listener stopped")
	return ""
}

type logSubscription struct {
	stop chan struct{}
}

func startLogForwarder() *logSubscription {
	sub := log.Subscribe()
	s := &logSubscription{stop: make(chan struct{})}
	go func() {
		defer log.UnSubscribe(sub)
		for {
			select {
			case <-s.stop:
				return
			case ev, ok := <-sub:
				if !ok {
					return
				}
				fmt.Printf("[mihomo:%s] %s\n", ev.Type(), ev.Payload)
			}
		}
	}()
	return s
}
