# Bob Assistant Phase 0 — Pinned Versions

Records build toolchain decisions during Spike A.

**Status**: **PASS** (2026-05-24)
- gomobile bind succeeds with compile-probe imports covering mihomo
  TUN / dialer / statistic (libgojni.so 28.4 MB uncompressed → linker pulled
  real mihomo code in, not just `mihomo/log`)
- `./gradlew :app:assembleDebug` produces a 29 MB APK with `lib/arm64-v8a/libgojni.so` bundled
- **On-device smoke test (OnePlus 10T, Android 15)**: APK installed,
  MainActivity launched, logcat shows
  `I BobPhase0: Bobcore.version() = 0.0.1-prototype` → Java→JNI→Go round-trip works end-to-end

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

## Spike B findings — overrides earlier assumptions

The Phase 0 plan's "no Protector needed because Bob isn't in allowed list"
turned out to be **wrong**. Even though `addAllowedApplication` only enrolls
HS, mihomo's DIRECT outbound dial is initiated from Bob's process — those
sockets are NOT in the VPN's allowed list, but they ARE routed through the
TUN that mihomo itself owns (because routing matches by destination, not by
source uid in this case). Without `VpnService.protect(fd)`, every DIRECT
SYN re-enters the TUN → self-loop → HS sees ECONNREFUSED.

**Current implementation (Spike B verified):**
- **Protector REQUIRED**: bobcore installs `dialer.DefaultSocketHook` that
  forwards every outbound socket fd to a Kotlin Protector implementation,
  which calls `VpnService.protect(fd)`. Mirrors CMFA's
  `delegate/init.go:50-58`.
- **TUN driven via `sing_tun.New` directly**, NOT `executor.ApplyConfig`
  with a `tun:` block. Mirrors CMFA's `core/src/main/golang/native/tun/tun.go`.
  - Reason: mihomo's `parseTun` ignores `tun.inet4-address` from YAML and
    derives it from `dns.fake-ip-range` (default 198.18.0.1/16). On Android
    with VpnService owning the actual TUN address (10.99.0.1), the
    auto-derived 198.18.0.1 bind fails with "cannot assign requested address".
- **VpnService.Builder DOES specify `addDnsServer(10.99.0.2)`**. mihomo
  internal DNS resolver answers, forwarding to upstream `8.8.8.8 / 1.1.1.1`.
  Phase 0 only; see "Known Phase 0 debts" below.
- **TUN stack = `gvisor`**, hard-coded in BobVpnService and validated in
  bobcore (StartTun rejects unknown stacks). `mixed` / `system` stacks
  silently drop TCP packets through an external fd on Android VpnService.
  DNS UDP worked under `mixed` because UDP goes through gvisor even in mixed
  mode; the TCP-via-system-stack path is broken.
- **Build tag `cmfa`** (gomobile bind invocation):
  - Excludes mihomo's `server_android.go` (which reads /data/system/packages.xml,
    permission-denied for non-root apps).
  - Also disables mihomo's internal Android process resolver. With
    `find-process-mode: off` the connection metadata will have empty `process`
    and empty `Uid` (no UID resolution either). For Spike C/D filtering: since
    only HS is in `addAllowedApplication`, every connection mihomo dispatches
    can be treated as HS — no need for per-connection UID lookup at Phase 0.
- **VpnService allowed apps**: only HS package. Bob itself is NOT in the
  allowed list, which is why Bob's MainActivity / Service control traffic
  bypasses the TUN via system default network.

## Known Phase 0 debts (must address before Phase 1 / shipping)

1. **DNS upstream is `8.8.8.8 + 1.1.1.1`**, contradicts the spec privacy
   line "we don't ship any data to third-party servers". Phase 1 must move
   to `nameserver: [system]` driven by Kotlin reading
   `ConnectivityManager.getActiveNetwork().LinkProperties.dnsServers` and
   passing into mihomo via a small Go API addition. Without that, every
   HS DNS query leaks to Google/Cloudflare.

2. **MainActivity `auto_start` intent extra** is debug-only and currently
   gated by `BuildConfig.DEBUG`. The Activity itself is exported, so the
   gate is the only protection. Production build must drop this branch
   entirely or move it behind a signature-permission-protected receiver.

3. **`metadata.UID` and `metadata.Process` are NOT populated** under the
   `cmfa` build tag + `find-process-mode: off`. Spike C/D filtering must
   NOT rely on these fields. The valid Phase 0 filter is "any TCP
   connection from mihomo's connection table is HS" because only HS is
   in `addAllowedApplication`. If Phase 1 wants to support multiple apps
   per tunnel, a JNI process resolver (mirroring CMFA's
   `delegate.findPackageName`) becomes required.

4. **Targets arm64-v8a only**; armv7-a left for Phase 1.

5. **Single foreground service type** declared as `specialUse` with the
   subtype property. Production must add a clearer human-readable subtype
   description for Play Store / OEM review (Play Store is anyway off the
   table — but Samsung Galaxy Store etc. still inspect this).

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
