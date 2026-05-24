// Package bobcore is the embedded mihomo facade for Bob Assistant Android.
//
// Phase 0: only Version() is exported. Real functions (Init/Start/Stop/
// Connections/CloseConnection) come in Spike B/C after mihomo internal
// APIs are pinned by reading source.
package bobcore

import (
	// Force mihomo into the dependency graph so `go mod tidy` keeps it.
	// We pick `log` because it has minimal transitive deps and is stable.
	_ "github.com/metacubex/mihomo/log"
)

// Version returns the bobcore build version. Phase 0 sentinel string used
// by Spike A to prove Java/Kotlin can reach Go land via gomobile bind or
// cgo c-shared (whichever Spike A picks).
func Version() string {
	return "0.0.1-prototype"
}
