# Bob Assistant Phase 0 — Pinned Versions

Records build toolchain decisions made during Spike A.

## Versions

- **mihomo**: `v1.19.25` (https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.25)
- **Go**: 1.25.6 (system); `go.mod` declares 1.25.0 minimum after `go get -tool`
- **golang.org/x/mobile**: `v0.0.0-20260520154334-0e4426e1883d`
- **NDK**: 27.1.12297006 (at `$ANDROID_HOME/ndk/27.1.12297006`)
- **gomobile**: installed at `/Users/jun/go/bin/gomobile` (not on default PATH; build script must `export PATH="$HOME/go/bin:$PATH"`)

## Build toolchain decision (Spike A)

**Chosen**: `gomobile bind` (not CMFA-style cgo c-shared).

Rationale:
- gomobile bind succeeds out of the box on `import _ "github.com/metacubex/mihomo/log"`
- AAR is self-contained (.aar + Java wrappers automatic)
- CMFA-style would need golang-android Gradle plugin + CMake + handwritten JNI bridge — more moving parts, more places to break
- Codex review warned this might not scale to full mihomo API surface — re-evaluate at Spike B if gomobile chokes on TUN listener types

Phase 0 fallback trigger: if Spike B (TUN ingestion) hits gobind unsupported-type errors, switch to CMFA-style (Spike A.6b in plan). Estimated cost: +2 days.

## gomobile bind invocation

```bash
export PATH="$HOME/go/bin:$PATH"
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.1.12297006"
gomobile bind \
    -target=android/arm64 \
    -androidapi 24 \
    -javapkg com.bobassist.gomobile \
    -ldflags="-s -w" \
    -trimpath \
    -o /tmp/spike-a/bobcore.aar \
    .
```

## Generated Java API surface (verified via `javap`)

Java class: **`com.bobassist.gomobile.bobcore.Bobcore`** (note the extra `.bobcore` segment from the Go package name)

```java
public abstract class com.bobassist.gomobile.bobcore.Bobcore {
    public static native java.lang.String version();
    public static void touch();
    private static native void _init();
}
```

Naming conventions observed:
- Go `func Version() string` → Java `version()` (lowerCamelCase)
- `_init()` is gomobile-internal initialization
- `touch()` is a no-op that forces class loading

Kotlin import path: `import com.bobassist.gomobile.bobcore.Bobcore`
Kotlin call: `Bobcore.version()` returns `"0.0.1-prototype"`

## AAR composition

```
bobcore.aar (760 KB)
├── AndroidManifest.xml (146 B)
├── classes.jar (10 KB)
│   ├── com/bobassist/gomobile/bobcore/Bobcore.class
│   └── go/* (gomobile runtime wrappers)
├── jni/arm64-v8a/libgojni.so (1.8 MB)
└── proguard.txt
```

Note: Phase 0 AAR is small because only `mihomo/log` is in the import graph. Spike B/C will add TUN/statistic imports → expect 15-30 MB.

## Phase 0 simplifications

Per codex review of plan v2, Phase 0 deliberately:
- Does **not** add Bob's own package to `addAllowedApplication` → no self-loop → no Protector callback needed (plan §Spike B.7)
- Does **not** specify a DNS server in VpnService.Builder; profile has `dns.enable: false` → system DNS used
- Targets **arm64-v8a only** (armv7 left for Phase 1)

## Date

2026-05-24
