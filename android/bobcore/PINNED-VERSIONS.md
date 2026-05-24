# Bob Assistant Phase 0 — Pinned Versions

Records build toolchain decisions during Spike A.

**Status**: **provisional** — gomobile bind succeeds with compile-probe imports
covering mihomo TUN / dialer / statistic; AAR call from Android Studio
`assembleDebug` + on-device `Bobcore.version()` smoke test still pending.

## Versions

- **mihomo**: `v1.19.25` (https://github.com/MetaCubeX/mihomo/releases/tag/v1.19.25)
- **Go**: 1.25.6 (system); `go.mod` declares 1.25.0 minimum
  - **Phase 0 build host requires Go 1.25+**. This is a build-time constraint
    (driven by current `golang.org/x/mobile` requiring Go 1.25); it does NOT
    affect the Android runtime. CI must use Go 1.25+.
- **golang.org/x/mobile**: `v0.0.0-20260520154334-0e4426e1883d` (pinned via go.mod `tool` directive)
- **NDK**: 27.1.12297006 (detected at runtime; `build-aar.sh` picks newest under `$ANDROID_HOME/ndk/`)
- **gomobile / gobind**: pinned in this module's `go.mod` via Go 1.24+ `tool` directive; invoked via `go tool golang.org/x/mobile/cmd/gomobile`. No reliance on PATH `gomobile`.

## Build toolchain decision (Spike A)

**Chosen**: `gomobile bind` (not CMFA-style cgo c-shared).

Rationale:
- gomobile bind succeeds out of the box on Phase 0's wrapper, including blank
  imports for `mihomo/listener/sing_tun`, `mihomo/tunnel/statistic`, and
  `mihomo/component/dialer` (the three high-risk packages for Spike B/C).
- AAR is self-contained (libgojni.so + Java wrappers automatic), no
  hand-written JNI bridge.
- CMFA-style would need golang-android Gradle plugin + CMake + bridge.c +
  bridge.h + main.c — more moving parts.

**Phase 0 fallback trigger** (re-evaluate at Spike B if any of these happen):

- gomobile bind fails when the wrapper exports a function whose signature uses
  a Go type unsupported by gobind (interface with methods returning
  channels/maps/generics, `unsafe.Pointer`, custom struct with private fields).
- Cannot wire `VpnService.protect(fd)` as a gomobile reverse-bind callback at
  acceptable latency (Spike B.7).
- Android arm64-v8a compile/link fails after adding real (non-blank)
  mihomo calls.

The previous wording ("gomobile chokes on TUN listener types") was wrong:
gobind does not inspect mihomo's internal types, only the surface we export.

## gomobile bind invocation

```bash
# Driven by build-aar.sh:
go tool golang.org/x/mobile/cmd/gomobile bind \
    -target=android/arm64 \
    -androidapi 29 \
    -javapkg com.bobassist.gomobile \
    -ldflags="-s -w" \
    -trimpath \
    -o ../overlay-app/app/libs/bobcore.aar \
    .
```

- `-androidapi 29` matches the Phase 0 plan `minSdk 29`. (Spike A v1 used 24
  to widen AAR compatibility — corrected to align with plan.)
- `-javapkg` forces the Java namespace; without it the package would default
  from the module path.

## Generated Java API surface (verified via `javap`)

Java class: **`com.bobassist.gomobile.bobcore.Bobcore`** (note the extra
`.bobcore` segment from the Go package name)

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

Kotlin call: `Bobcore.version()` returns `"0.0.1-prototype"`.

## AAR composition (with compile-probe imports)

```
bobcore.aar (9.4 MB compressed)
├── AndroidManifest.xml
├── classes.jar (~10 KB; Java wrappers + go runtime helpers)
├── jni/arm64-v8a/libgojni.so (~28.4 MB uncompressed)
└── proguard.txt
```

The 28 MB uncompressed shared library indicates mihomo's TUN/statistic/dialer
code paths were actually linked in. This is the main signal that gomobile
bind handles mihomo's complexity — and the main reason Spike A is considered
provisionally successful before on-device smoke test.

## Phase 0 simplifications

Per codex review of plan v2, Phase 0 deliberately:
- Does **not** add Bob's own package to `addAllowedApplication` → no self-loop
  → no Protector callback needed (plan §Spike B.7)
- Does **not** specify a DNS server in VpnService.Builder; profile has
  `dns.enable: false` → system DNS used
- Targets **arm64-v8a only** (armv7 left for Phase 1)

## CMFA NDK comparison

CMFA `core/build.gradle.kts` targets NDK 29.x with `golang-android` Gradle
plugin; we use NDK 27.1 because it's what's already on the dev host. If
Spike B encounters TUN crashes on-device that look NDK/kernel-related, try
bumping to NDK 29.x to match CMFA's known-good config.

## What this Spike does NOT prove

- gomobile bind handling **exported** functions with mihomo types in
  signature (Phase 0 wrapper is `Version() string` only)
- Bob.version() actually returning the correct string when called from a real
  APK on a real device (smoke test pending)
- mihomo TUN listener accepting an external fd from VpnService (Spike B)
- HS connection table being populated correctly (Spike C/D)
- AAR loading correctly under Android Studio's R8/proguard (Spike 4)

## Date

2026-05-24
