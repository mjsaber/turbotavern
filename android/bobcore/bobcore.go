// Package bobcore is the embedded mihomo facade for Bob Assistant Android.
//
// Phase 0: only Version() is exported. Real functions (Init/Start/Stop/
// Connections/CloseConnection) come in Spike B/C after mihomo internal
// APIs are pinned by reading source.
package bobcore

import (
	// Compile-only probe imports. Phase 0 does not call into mihomo yet,
	// but these blank imports force gomobile bind to actually compile and
	// link the high-risk packages we will need in Spike B/C/D. If gomobile
	// bind fails here, we know to bail to the CMFA-cgo fallback before
	// we sink time into Spike B.
	_ "github.com/metacubex/mihomo/component/dialer"   // Protector hook target (Spike B.7)
	_ "github.com/metacubex/mihomo/listener/sing_tun"  // TUN external-fd entrypoint (Spike B)
	_ "github.com/metacubex/mihomo/log"                // basic logging
	_ "github.com/metacubex/mihomo/tunnel/statistic"   // connection table API (Spike C)
)

// Version returns the bobcore build version. Phase 0 sentinel string used
// by Spike A to prove Java/Kotlin can reach Go land via gomobile bind or
// cgo c-shared (whichever Spike A picks).
func Version() string {
	return "0.0.1-prototype"
}
