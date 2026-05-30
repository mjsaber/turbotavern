# Phase 1.3 — Test Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the test pyramid (Robolectric Tier 1 + Emulator Tier 2 + sim scripts) and trace instrumentation that turns "play a BG round to validate" into "./gradlew test" or "sim-bg-kill.sh scenario". Then use it to diagnose the 5-6s tap-to-skip delay.

**Architecture:** Spec at `docs/superpowers/specs/2026-05-27-bob-android-test-infrastructure-design.md`. Key moves: extract `OverlaySession` coordinator class; introduce `OverlayUi`/`Clock` interfaces and `Lifecycle/ConnectionCoreFacade`; add `TraceSink` with ±2s sample window; add debug-only `DebugConnectionCoreOverride` for snapshot/close injection; replace `liveController/livePoller/liveTapTrigger` with single `liveSession`.

**Tech Stack:** Kotlin, Robolectric 4.13, AndroidX Test 1.5, JUnit 4. Android Emulator (arm64-v8a AVD on Apple Silicon). No new gomobile / Go work.

---

## Codex Review Tables

### Plan round 1 (2026-05-27)

| # | Sev | Topic | Disposition |
|---|---|---|---|
| 1 | **P0** | Task 7 ConnectionCoreProvider duplicate-class: putting same FQCN in both src/main and src/debug fails AGP source-set merge. | Task 7 fixed: main file moves to `src/release/`, debug file stays in `src/debug/`. Each variant gets exactly one. |
| 2 | **P1** | Task 10 tests assume OverlaySession owns poll scheduling; Task 4 leaves it in BobVpnService. | Task 4 expanded: **OverlaySession owns the periodic tick Runnable + detectorTick Runnable**. BobVpnService just creates session + handler thread + calls `session.start()`. |
| 3 | **P1** | Teardown race test "poll tick after stop does not call snapshot" needs session liveness inside the Runnable. | Task 4 + 8: poll/detector tick Runnable bodies check `if (!session.started) return` before calling tick. |
| 4 | **P1** | Trace not wired into controller because controller is a lazy service field built before TraceSink exists. | Task 5 + 6: controller construction MOVED INTO `startOverlayAndPolling()`, takes both the trace and the connection facade at construction. Drop the `by lazy` field. |
| 5 | **P1** | slow_snapshot / tap_while_snapshot scenarios can't be simulated — debug override only delays close, not connectionsJson. | Task 7 expanded: DebugConnectionCoreOverride gains `snapshotDelayMs` + `sim_set_snapshot_delay --es ms <n>` broadcast command. |
| 6 | **P1** | tap_at_poll_offsets not controllable from shell — poll tick is internal to BobVpnService. | Task 7 expanded: new `sim_force_tick` command triggers an immediate `OverlayPoller.tick()` posted to pollHandler so sim scripts can drive controlled timing. |
| 7 | **P1** | No-HS emulator sim — ForegroundDetector will pause poller because Settings/launcher isn't HS. | Task 7 expanded: `sim_set_foreground --es value true\|false` command that calls `detector.reset()` or forces a fake false transition. **Plus**: TestReceiver gates this on `BuildConfig.DEBUG` only. |
| 8 | **P1** | Task 9 says "remove `kill` if no caller" — but test-spike-c.sh DOES call it. | Task 9 corrected: `kill` (by id) is KEPT in TestReceiver. Only `kill_battle`, `overlay_tap`, `overlay_state` are migrated to liveSession. `kill` continues to call `RealConnectionCore.closeConnection(id)` directly. |
| 9 | P2 | Task 4 service refactor instructions remove mainHandler but service still needs one for onConfigurationChanged routing. | Task 4 explicit: `mainHandler` stays; `onConfigurationChanged` routes to `session?.handleConfigurationChanged()`; `session = null` cleared in tearDown. |
| 10 | P2 | sim-bg-kill.sh waits for "overlay + poller started" breadcrumb that Task 4 may replace. | Task 4: KEEP the old breadcrumb string after `session.start()` returns so scripts don't break. New breadcrumb `session started` is in addition. |
| 11 | P2 | Trace window is post-tap only; can't see pre-tap poll ticks (poll-wait classification weakened). | Task 5: TraceSink adds a small **pre-tap ring buffer** (last 5 tick traces buffered; flushed by beginCycle). |
| 12 | P2 | Release/no-native verification commands too loose. | Task 7 + Exit Criteria use `unzip -p app-release.apk classes*.dex \| strings \| grep -E 'DebugConnectionCoreOverride\|sim_'` (empty = pass) and `-verbose:class \| grep 'com.bobassist.gomobile.bobcore.Bobcore'` (empty = no native load). |
| 13 | P3 | Task 4-before-5-before-6 ordering OK. | Accepted, no change. |

### Plan round 4 (2026-05-27)

| # | Sev | Topic | Disposition |
|---|---|---|---|
| 32 | P1 | Task 7 Files list STILL has `src/main/.../ConnectionCoreProvider.kt`. | Removed. |
| 33 | P1 | Old `BuildConfig.DEBUG` snippet in Task 7 still present in parallel to the corrected `ForegroundOverrideHolder` version. | Old snippet deleted. |
| 34 | P1 | Task 5 references `ConnectionCoreProvider.get()` but provider doesn't exist until Task 7. Use direct `MihomoCore` in Task 5; switch to `RealConnectionCore` in Task 6; switch to `ConnectionCoreProvider.get()` in Task 7. | Task 5 controller snapshot/close lambdas use `MihomoCore.connectionsJson()` / `MihomoCore.closeConnection(id)` directly during stage 5.4. Task 6 swaps to `RealConnectionCore.*`. Task 7 swaps to `ConnectionCoreProvider.get().*`. |
| 35 | P1 | Task 7 `sim_force_tick` references `BobVpnService.liveSession` (not introduced until Task 9). | Task 7 sim_force_tick uses `BobVpnService.livePoller` (existing Phase 1.2 field). Task 9 migrates to liveSession.poller. |
| 36 | P1 | `DebugConnectionCoreOverride.foregroundOverride()` collides with `foregroundOverride` private property. | Renamed property to `foregroundOverrideRef`. Method body: `override fun foregroundOverride(): Boolean? = foregroundOverrideRef.get()`. |
| 37 | P1 | `IntegrationFactory.kt` not in Task 10 Files list / commit. | Added. |

### Plan round 3 (2026-05-27)

| # | Sev | Topic | Disposition |
|---|---|---|---|
| 21 | P1 | Task 7 "Files" list still creates `src/main/.../ConnectionCoreProvider.kt`. | Removed from Task 7 file list. |
| 22 | P1 | BobVpnService.queryForegroundPackage snippet still references `DebugConnectionCoreOverride` directly under `BuildConfig.DEBUG` — breaks release compilation. | Snippet rewritten to use `ForegroundOverrideHolder.get().foregroundOverride()` only. No `BuildConfig.DEBUG` gate needed; the variant indirection handles it. |
| 23 | P1 | `object DebugConnectionCoreOverride : ConnectionCoreFacade` snippet missing the `ForegroundOverrideProvider` interface declaration. | Updated to `: ConnectionCoreFacade, ForegroundOverrideProvider`. |
| 24 | P1 | Task 4 tearDown snippet sets `liveSession = null`, but liveSession is introduced in Task 9. Intermediate commit (after Task 4, before 9) won't compile. | Task 4 tearDown drops the `liveSession = null` line; Task 4 keeps existing `liveController/livePoller/liveTapTrigger` assignments. Task 9 cleanly migrates them. |
| 25 | P1 | Task 5 removes lazy controller field but `bringUp()` still has `liveController = controller`. Won't compile mid-task. | Task 5 keeps `liveController = controller` after local construction (where `controller` is now a local val). Task 9 removes both. |
| 26 | P1 | Task 10 integration-test snippet uses old OverlaySession signature (missing trace param). | Task 10 Step 0 (new): write a shared `IntegrationFactory.kt` test helper that constructs OverlaySession with all final params including a disabled TraceSink. All 4 test files use this factory. |
| 27 | P2 | `pollHandler.removeCallbacksAndMessages(null)` requires the handler to be session-exclusive. | Added explicit contract comment on OverlaySession's pollHandler param: "MUST be exclusively owned by this OverlaySession. Other subsystems must NOT post to it." |
| 28 | P3 | File Structure mentioned "value class" for TraceCycle; implementation is regular class. | "value class" wording removed; just say "value object". |
| 29 | P3 | Test count: TraceSinkTest now has 6 tests, not 5. | Wording corrected. |
| 30 | P3 | Stop-between-tap-and-post: posted lambda may be removed before running, no `session_stopped` line emitted. | Documented as acceptable behavior in OverlaySession kdoc. |
| 31 | P3 | Disabled trace still allocates `"key" to value` Pairs at call-site. | Documented as negligible; not optimized. |

### Plan round 2 (2026-05-27)

| # | Sev | Topic | Disposition |
|---|---|---|---|
| 14 | P1 | File Structure section still lists `src/main/.../ConnectionCoreProvider.kt` — reintroduces P0 duplicate-class. | File Structure section corrected: only `src/release/` and `src/debug/` listed. |
| 15 | P1 | `DebugOverrides` / `NoOpDebugOverrides` / `DebugOverridesProvider` exist in main+release; release-dex grep for `Debug*` will fail. | Renamed in main+release to `ForegroundOverrideProvider` + `NoOpForegroundOverrideProvider`. `DebugConnectionCoreOverride` (debug-only) implements `ForegroundOverrideProvider` for the foreground bool. |
| 16 | P1 | `sim_set_foreground true` only sets an atomic; if poller was already paused, force_tick early-returns. | `sim_set_foreground` ALSO calls `BobVpnService.liveSession?.handleForegroundChange(parsed)` for non-null values so the poller is immediately resumed/paused. |
| 17 | P1 | `OverlaySession.stop()` liveness incomplete — initial start-post not guarded; cooldown expiry callbacks not cleared. | `stop()` calls `pollHandler.removeCallbacksAndMessages(null)` after setting started=false. Initial start-post lambda guarded with `if (!started) return`. |
| 18 | P1 | TraceSink global cycleId loses attribution under rapid taps; `windowMs=2000` drops late-firing trace during a 5-6s delay. | TraceSink redesigned: `beginCycle()` returns a `TraceCycle` value object; `cycle.emit(...)` records against that cycle. Each cycle has its own openUntilNs. Default windowMs bumped to **10_000ms** for 5-6s diagnosis. Pre-tap ring buffer **dropped** (codex says it's misleading; poll_tick gets its own cycle). |
| 19 | P1 | Same — global currentCycleId race. | (Same fix as #18 — TraceCycle is scoped.) |
| 20 | P2 | Release dex verification `unzip -p $f` where `$f` is already a dex — false-pass. | Replaced with `strings $f \| grep ...` for intermediate dex; for built APK use `unzip -p app-release.apk classes*.dex \| strings`. Path uses `app-release-unsigned.apk` if signing not configured. |

### Code round (placeholder, filled after codex exec review --base on the diff)
_(empty until code is complete and reviewed)_

---

## File Structure

**Create:**
- `app/src/main/java/com/bobassist/phase0/overlay/OverlayUi.kt`
- `app/src/main/java/com/bobassist/phase0/util/Clock.kt`
- `app/src/main/java/com/bobassist/phase0/util/TraceSink.kt` (includes `TraceCycle` value class)
- `app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt`
- `app/src/main/java/com/bobassist/phase0/core/CoreFacades.kt` (LifecycleCoreFacade + ConnectionCoreFacade + Real*)
- `app/src/main/java/com/bobassist/phase0/core/ForegroundOverrideProvider.kt` (interface only; main package)
- `app/src/debug/java/com/bobassist/phase0/core/DebugConnectionCoreOverride.kt` (debug-only override; implements ForegroundOverrideProvider)
- `app/src/debug/java/com/bobassist/phase0/core/ConnectionCoreProvider.kt` (debug-only; returns DebugConnectionCoreOverride)
- `app/src/release/java/com/bobassist/phase0/core/ConnectionCoreProvider.kt` (release-only; returns RealConnectionCore)
- `app/src/debug/java/com/bobassist/phase0/core/ForegroundOverrideHolder.kt` (debug-only; returns DebugConnectionCoreOverride)
- `app/src/release/java/com/bobassist/phase0/core/ForegroundOverrideHolder.kt` (release-only; returns no-op)
- `app/src/test/java/com/bobassist/phase0/integration/OverlaySessionTapTest.kt`
- `app/src/test/java/com/bobassist/phase0/integration/OverlaySessionCooldownTest.kt`
- `app/src/test/java/com/bobassist/phase0/integration/OverlaySessionForegroundTest.kt`
- `app/src/test/java/com/bobassist/phase0/integration/OverlaySessionTeardownRaceTest.kt`
- `app/src/test/java/com/bobassist/phase0/integration/RobolectricSmokeTest.kt` (hello-world toolchain check)
- `app/src/test/java/com/bobassist/phase0/util/TraceSinkTest.kt`
- `app/src/test/java/com/bobassist/phase0/util/FakeOverlayUi.kt` (test helper, shared)
- `app/src/test/java/com/bobassist/phase0/util/FakeConnectionCore.kt`
- `scripts/sim-bg-kill.sh`
- `scripts/sim-lib.sh` (shared bash helpers — adb, logcat grep, jq phase-table extraction)

**Modify:**
- `app/build.gradle.kts` — Robolectric + AndroidX Test deps
- `app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt` — implement `OverlayUi`
- `app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt` — accept `Clock` via constructor
- `app/src/main/java/com/bobassist/phase0/BobVpnService.kt` — compose OverlaySession; drop `liveController/livePoller/liveTapTrigger`; use facades
- `app/src/main/java/com/bobassist/phase0/MainActivity.kt` — `MihomoCore.version()` → `RealLifecycleCore.version()` (or leave; not critical)
- `app/src/debug/java/com/bobassist/phase0/TestReceiver.kt` — all paths through `liveSession`; new `sim_*` commands

---

## Task 1: Robolectric 4.13 dependencies + hello-world smoke test (stage 5.1)

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/bobassist/phase0/integration/RobolectricSmokeTest.kt`

- [ ] **Step 1: Add Robolectric deps**

In `app/build.gradle.kts`, inside `android { ... }` block (alongside `testOptions`):

```kotlin
testOptions {
    unitTests.isReturnDefaultValues = true
    unitTests.isIncludeAndroidResources = true   // required for Robolectric
}
```

Inside `dependencies { ... }`:

```kotlin
testImplementation("org.robolectric:robolectric:4.13")
testImplementation("androidx.test:core:1.5.0")
testImplementation("androidx.test.ext:junit:1.1.5")
```

- [ ] **Step 2: Write Robolectric hello-world smoke**

Create `app/src/test/java/com/bobassist/phase0/integration/RobolectricSmokeTest.kt`:

```kotlin
package com.bobassist.phase0.integration

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
class RobolectricSmokeTest {

    @Test
    fun `main looper exists and is idle initially`() {
        // codex r1 plan snippet used scheduler.size() which throws in PAUSED mode.
        // PAUSED-mode-compatible: assert the looper exists and is idle.
        assertNotNull(Looper.getMainLooper())
        assertTrue(shadowOf(Looper.getMainLooper()).isIdle())
    }

    @Test
    fun `SystemClock advances when ShadowLooper idleFor is called`() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS)
        val t1 = SystemClock.elapsedRealtimeNanos()
        assertEquals(2_000L * 1_000_000L, t1 - t0)
    }
}
```

- [ ] **Step 3: Run, verify both tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.integration.RobolectricSmokeTest"`
Expected: 2 tests pass. First Robolectric run downloads Android jars (~30 MB), so ~1 minute. Subsequent runs ~3s.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/test/java/com/bobassist/phase0/integration/RobolectricSmokeTest.kt
git commit -m "phase1.3(test-infra): Robolectric 4.13 toolchain + hello-world smoke"
```

---

## Task 2: `OverlayUi` interface (stage 5.2)

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/overlay/OverlayUi.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt`
- Create: `app/src/test/java/com/bobassist/phase0/util/FakeOverlayUi.kt`

- [ ] **Step 1: Define interface**

Create `app/src/main/java/com/bobassist/phase0/overlay/OverlayUi.kt`:

```kotlin
package com.bobassist.phase0.overlay

/**
 * Renderer contract for the overlay button. Production: [OverlayWindow].
 * Tests: FakeOverlayUi in src/test.
 *
 * All methods must be called on the main looper of the host.
 */
interface OverlayUi {
    fun show()
    fun hide()
    fun setVisible(visible: Boolean)
    fun applyState(state: OverlayState)
    fun onConfigurationChanged()
}
```

- [ ] **Step 2: Make `OverlayWindow` implement it**

In `OverlayWindow.kt`, change the class declaration:

```kotlin
class OverlayWindow(
    private val context: Context,
    private val onTap: () -> Unit,
) : OverlayUi {
    // ... existing fields & methods ...
    
    // Mark existing methods with `override`:
    override fun show() { ... existing impl ... }
    override fun hide() { ... existing impl ... }
    override fun setVisible(visible: Boolean) { ... existing impl ... }
    override fun applyState(state: OverlayState) { ... existing impl ... }
    override fun onConfigurationChanged() { ... existing impl ... }
}
```

- [ ] **Step 3: Add `FakeOverlayUi` test helper**

Create `app/src/test/java/com/bobassist/phase0/util/FakeOverlayUi.kt`:

```kotlin
package com.bobassist.phase0.util

import com.bobassist.phase0.overlay.OverlayState
import com.bobassist.phase0.overlay.OverlayUi

class FakeOverlayUi : OverlayUi {
    @Volatile var visible: Boolean = false
        private set
    @Volatile var lastState: OverlayState = OverlayState.WaitingForBattle
        private set
    val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override fun show() { visible = true; log += "show" }
    override fun hide() { visible = false; log += "hide" }
    override fun setVisible(v: Boolean) { visible = v; log += "setVisible($v)" }
    override fun applyState(s: OverlayState) { lastState = s; log += "applyState($s)" }
    override fun onConfigurationChanged() { log += "onConfigurationChanged" }
}
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests (32+) green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/overlay/OverlayUi.kt \
        app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt \
        app/src/test/java/com/bobassist/phase0/util/FakeOverlayUi.kt
git commit -m "phase1.3(test-infra): extract OverlayUi interface; OverlayWindow implements it; add FakeOverlayUi"
```

---

## Task 3: `Clock` interface + inject into `OverlayPoller` (stage 5.3)

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/util/Clock.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt`

- [ ] **Step 1: Define Clock**

Create `app/src/main/java/com/bobassist/phase0/util/Clock.kt`:

```kotlin
package com.bobassist.phase0.util

import android.os.SystemClock

/**
 * Monotonic clock abstraction. Production uses [AndroidElapsedRealtimeClock]
 * which delegates to SystemClock.elapsedRealtimeNanos (advances with
 * Robolectric ShadowLooper.idleFor). Tests requiring controlled time can
 * also use [ManualClock] in pure-JVM contexts.
 */
interface Clock {
    fun nowNanos(): Long
    fun nowMillis(): Long = nowNanos() / 1_000_000L
}

object AndroidElapsedRealtimeClock : Clock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/** For pure-JVM tests only; do NOT use in Robolectric tests (use AndroidElapsedRealtimeClock + ShadowLooper.idleFor instead). */
class ManualClock(initial: Long = 0L) : Clock {
    @Volatile private var current: Long = initial
    fun advance(deltaNanos: Long) { current += deltaNanos }
    override fun nowNanos(): Long = current
}
```

- [ ] **Step 2: OverlayPoller — inject Clock (default = AndroidElapsedRealtimeClock for backward compat)**

In `OverlayPoller.kt`, change constructor to accept optional `clock`:

```kotlin
class OverlayPoller(
    private val snapshot: () -> Int,
    private val onStateChange: (OverlayState) -> Unit,
    private val scheduleAfter: (delayMs: Long, callback: () -> Unit) -> Unit,
    private val clock: com.bobassist.phase0.util.Clock = com.bobassist.phase0.util.AndroidElapsedRealtimeClock,
) {
    // ... no changes to body yet — Clock is added but unused. §4.4 trace will use it.
```

This is a minimal-friction injection point; existing tests don't need updating because clock defaults.

- [ ] **Step 3: Build + run existing OverlayPollerTest**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.overlay.OverlayPollerTest"`
Expected: 11 tests still green (Clock unused so no behavior change).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/util/Clock.kt \
        app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt
git commit -m "phase1.3(test-infra): Clock interface + inject into OverlayPoller"
```

---

## Task 4: Extract `OverlaySession` coordinator (stage 5.4)

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/BobVpnService.kt`

This is a behavior-preserving refactor. Run the existing test-spike-e regression suite before/after to verify.

- [ ] **Step 1: Create OverlaySession with the coordination logic lifted from BobVpnService**

Create `app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt`:

```kotlin
package com.bobassist.phase0.session

import android.os.Handler
import android.util.Log
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.foreground.ForegroundDetector
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.overlay.OverlayState
import com.bobassist.phase0.overlay.OverlayUi
import com.bobassist.phase0.util.Clock

/**
 * Coordinates the runtime collaboration between OverlayPoller, ForegroundDetector,
 * OverlayUi, and BattleConnectionController. Single-thread-confined to [pollHandler]
 * for state-machine mutations; main-thread for UI updates.
 *
 * Lifecycle:
 *   start() must be called once after construction.
 *   stop() must be called when the host service tears down; after stop(), all
 *   subsequent posted runnables are no-ops (liveness guard).
 *
 * Note (codex round-3 P3 #30): if stop() runs before a posted tap lambda
 * executes, removeCallbacksAndMessages(null) removes the lambda entirely —
 * so the cycle's `session_stopped` exit line is NOT guaranteed to appear in
 * the trace. The cycle's tap entry line ALWAYS appears (it logs before
 * post). This is intentional.
 *
 * No Android-Service dependencies — instances can be exercised in Robolectric.
 *
 * **pollHandler contract (codex round-3 P2 #27)**: pollHandler MUST be
 * exclusively owned by this OverlaySession instance. No other subsystem may
 * post to it. OverlaySession.stop() calls removeCallbacksAndMessages(null)
 * which would silently cancel any non-session runnables.
 */
class OverlaySession(
    val controller: BattleConnectionController,
    val poller: OverlayPoller,
    val detector: ForegroundDetector,
    private val overlay: OverlayUi,
    private val pollHandler: Handler,
    private val mainHandler: Handler,
    private val clock: Clock,
    private val breadcrumb: (String) -> Unit = { },
) {
    @Volatile private var started: Boolean = false

    fun start() {
        if (started) return
        started = true
        breadcrumb("OverlaySession.start")
        // overlay.show() happens via foreground detector callback (initial isTargetForeground=true → fires show)
        // OR via initial state push; for now, mirror Phase 1.2: show eagerly + let detector hide if needed.
        mainHandler.post { runCatching { overlay.show() } }
    }

    fun stop() {
        if (!started) return
        started = false
        breadcrumb("OverlaySession.stop")
        mainHandler.post { runCatching { overlay.hide() } }
    }

    /**
     * User tapped the overlay. Confined to pollHandler so all state reads/writes
     * happen on a single thread. Enters Cooldown ONLY on Success.
     */
    fun handleTap() {
        val handler = pollHandler
        handler.post {
            if (!started) return@post
            when (poller.currentState()) {
                OverlayState.Ready -> {
                    val result = runCatching { controller.killBattleSocket() }
                        .getOrElse {
                            breadcrumb("overlay tap kill threw: ${it.message}")
                            return@post
                        }
                    breadcrumb("overlay tap result=$result")
                    if (result is BattleConnectionController.KillResult.Success) {
                        Log.i(TAG, "overlay kill success: id=${result.closedId}")
                        poller.enterCooldown()
                    } else {
                        Log.i(TAG, "overlay kill non-success: $result")
                    }
                }
                OverlayState.WaitingForBattle -> breadcrumb("overlay tap ignored (no candidate)")
                OverlayState.Cooldown -> breadcrumb("overlay tap ignored (cooldown)")
            }
        }
    }

    /**
     * Direct kill (bypasses state machine) — for spike-e regression compatibility.
     * Do NOT use this from production code paths.
     */
    fun killBattleSocketDirect(): BattleConnectionController.KillResult {
        return controller.killBattleSocket()
    }

    fun handleForegroundChange(isHsForeground: Boolean) {
        breadcrumb("foreground change: HS=$isHsForeground")
        pollHandler.post {
            if (!started) return@post
            if (isHsForeground) poller.resume() else poller.pause()
        }
        val capturedOverlay = overlay
        mainHandler.post {
            if (!started) return@post
            runCatching { capturedOverlay.setVisible(isHsForeground) }
        }
    }

    fun handleConfigurationChanged() {
        if (!started) return
        mainHandler.post {
            if (!started) return@post
            runCatching { overlay.onConfigurationChanged() }
        }
    }

    companion object {
        private const val TAG = "OverlaySession"
    }
}
```

- [ ] **Step 2: OverlaySession owns periodic tick + detectorTick scheduling (codex P1 #2 / #3)**

Expand `OverlaySession` to own the polling Runnables. This means the tests in Task 10 can drive `session.start()` and the ticks fire automatically via the Handler, without the test needing to know BobVpnService's wiring.

Add to OverlaySession constructor:
```kotlin
private val hasUsageAccessPermission: () -> Boolean,
```

And inside the class:
```kotlin
private var pollTick: Runnable? = null
private var detectorTick: Runnable? = null

fun start() {
    if (started) return
    started = true
    breadcrumb("OverlaySession.start")
    mainHandler.post { runCatching { overlay.show() } }
    
    val pTick = object : Runnable {
        override fun run() {
            if (!started) return        // codex P1 #3: session liveness guard
            poller.tick()
            pollHandler.postDelayed(this, OverlayPoller.POLL_INTERVAL_MS)
        }
    }
    val dTick = object : Runnable {
        override fun run() {
            if (!started) return        // codex P1 #3
            if (hasUsageAccessPermission()) detector.tick() else detector.reset()
            pollHandler.postDelayed(this, ForegroundDetector.POLL_INTERVAL_MS)
        }
    }
    pollTick = pTick
    detectorTick = dTick
    pollHandler.post {
        if (!started) return@post          // codex round-2 P1 #17: guard initial start-post
        poller.start()
        pollHandler.postDelayed(pTick, OverlayPoller.POLL_INTERVAL_MS)
        pollHandler.postDelayed(dTick, ForegroundDetector.POLL_INTERVAL_MS)
    }
}

fun stop() {
    if (!started) return
    started = false       // P1 #3 — any in-flight Runnable's first line `if (!started) return` will now exit
    breadcrumb("OverlaySession.stop")
    // codex round-2 P1 #17: nuke ALL queued runnables on pollHandler — not just
    // pollTick/detectorTick but also OverlayPoller's scheduled cooldown exit
    // callback and any handleTap/forceTickNow posts.
    pollHandler.removeCallbacksAndMessages(null)
    pollTick = null
    detectorTick = null
    mainHandler.post { runCatching { overlay.hide() } }
}
```

- [ ] **Step 3: Refactor BobVpnService to use OverlaySession**

In `BobVpnService.kt`:
1. Remove the `handleOverlayTap()` and `handleForegroundChange()` private methods (now in OverlaySession).
2. Remove `overlay`, `poller`, `detector` private fields (now owned by OverlaySession).
3. **Keep** `mainHandler` (codex P2 #9: needed for the OverlaySession constructor + onConfigurationChanged routing).
4. **Keep** the `BattleConnectionController by lazy { ... }` field for now — Task 5 will move it; here we just pass `this.controller` to OverlaySession.
5. Add `private var session: OverlaySession? = null`.
5a. Add transitional companion field for sim_force_tick (codex round-6 P1):
```kotlin
companion object {
    // existing live* fields ...
    @Volatile var livePollHandler: android.os.Handler? = null
        internal set
}
```
Task 9 removes this field after liveSession migration.
6. Override `onConfigurationChanged`:
   ```kotlin
   override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
       super.onConfigurationChanged(newConfig)
       session?.handleConfigurationChanged()
   }
   ```
7. Rewrite `startOverlayAndPolling()`:

```kotlin
private fun startOverlayAndPolling() {
    if (overlayRunning) return
    
    val ow = OverlayWindow(this, onTap = { session?.handleTap() })
    val ht = HandlerThread("BobOverlayPoll").apply { start() }
    val pollHandler = Handler(ht.looper)
    pollThread = ht
    this.pollHandler = pollHandler
    
    val poller = OverlayPoller(
        snapshot = {
            runCatching { BattleConnection.pickWithCount(MihomoCore.connectionsJson()).second }
                .getOrElse { 0 }
        },
        onStateChange = { state -> mainHandler.post { ow.applyState(state) } },
        scheduleAfter = { delayMs, cb -> pollHandler.postDelayed(cb, delayMs) },
        clock = AndroidElapsedRealtimeClock,
    )
    val detector = ForegroundDetector(
        queryForegroundPackage = { queryForegroundPackage() },
        targetPackage = HS_PACKAGE,
        onChange = { isFg -> session?.handleForegroundChange(isFg) },
    )
    val session = OverlaySession(
        controller = controller,
        poller = poller,
        detector = detector,
        overlay = ow,
        pollHandler = pollHandler,
        mainHandler = mainHandler,
        clock = AndroidElapsedRealtimeClock,
        hasUsageAccessPermission = { hasUsageAccessPermission() },
        breadcrumb = { msg -> breadcrumb(msg) },
    )
    this.session = session
    
    // codex round-5 P1: transitional live* assignments — TestReceiver and Task 7
    // sim_force_tick read these. Task 9 collapses them into liveSession.
    liveController = controller
    livePoller = poller
    liveTapTrigger = { session.handleTap() }
    livePollHandler = pollHandler          // for sim_force_tick (Task 7)
    
    session.start()       // OverlaySession now owns tick scheduling internally
    overlayRunning = true
    breadcrumb("overlay + poller started")    // KEEP old wording for sim script backward compat (codex P2 #10)
    breadcrumb("session started")             // ALSO emit new wording
}
```

8. In `tearDown()`, FIRST clear `session = null` (codex P2 #9), THEN call old code path. The HandlerThread quit is still owned by the service.

```kotlin
private fun tearDown() {
    overlayRunning = false
    // codex round-3 P1 #24: liveSession is introduced in Task 9, NOT here.
    // Until then, keep existing liveController/livePoller/liveTapTrigger clears.
    liveController = null
    livePoller = null
    liveTapTrigger = null
    livePollHandler = null
    session?.stop()
    session = null
    pollHandler?.removeCallbacksAndMessages(null)
    pollThread?.quitSafely()
    pollThread = null
    pollHandler = null
    // ... existing mihomo + pfd teardown ...
}
```

- [ ] **Step 3: Build + run all existing tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 32+ tests still green.

- [ ] **Step 4: Sanity-check on device — install + auto-start + overlay_state**

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bobassist.phase0
adb shell am start -n com.bobassist.phase0/.MainActivity --ez auto_start true
sleep 6
adb shell run-as com.bobassist.phase0 cat files/bob-breadcrumbs.log | tail -10
```
Expected breadcrumbs include BOTH `overlay + poller started` (kept for sim-script back-compat) AND `OverlaySession.start` + `session started`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt \
        app/src/main/java/com/bobassist/phase0/BobVpnService.kt
git commit -m "phase1.3(test-infra): extract OverlaySession coordinator (owns tick scheduling + liveness guards); behavior-preserving refactor"
```

---

## Task 5: `TraceSink` + tap-path trace points (stage 5.5)

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/util/TraceSink.kt`
- Create: `app/src/test/java/com/bobassist/phase0/util/TraceSinkTest.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt` (add trace emits in handleTap)
- Modify: `app/src/main/java/com/bobassist/phase0/core/BattleConnectionController.kt` (add trace around snapshot+pick+close)

- [ ] **Step 1: Write TraceSink + TraceCycle test (TDD red)**

Create `app/src/test/java/com/bobassist/phase0/util/TraceSinkTest.kt`:

```kotlin
package com.bobassist.phase0.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSinkTest {

    @Test
    fun `disabled sink returns disabled cycle that emits nothing`() {
        val sink = TraceSink(enabled = false, clock = ManualClock(), output = capture())
        val cycle = sink.beginCycle()
        assertFalse(cycle.enabled)
        cycle.emit("tap", "entry")
        cycle.emit("close", "exit")
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `enabled cycle within window emits formatted line with its own cycle id`() {
        val clock = ManualClock(initial = 1_000_000_000L)
        val sink = TraceSink(enabled = true, clock = clock, output = capture())
        val cycle = sink.beginCycle()
        cycle.emit("tap", "entry", "state" to "Ready")
        assertEquals(1, captured.size)
        assertTrue(captured[0].contains("phase=tap"))
        assertTrue(captured[0].contains("event=entry"))
        assertTrue(captured[0].contains("cycle=${cycle.cycleId}"))
        assertTrue(captured[0].contains("state=Ready"))
    }

    @Test
    fun `each cycle has its own openUntil — late emit on old cycle is dropped`() {
        val clock = ManualClock(initial = 0L)
        val sink = TraceSink(enabled = true, clock = clock, output = capture(), windowMs = 2_000L)
        val c1 = sink.beginCycle()
        clock.advance(2_100L * 1_000_000L)   // c1 window expired
        c1.emit("poll_tick", "entry")
        assertTrue("c1 emit after expiry should be dropped", captured.isEmpty())
    }

    @Test
    fun `concurrent cycles do not interfere — rapid-tap attribution`() {
        // Verifies codex round-2 P1 #6: TraceCycle is scoped, so a posted
        // runnable from cycle 1 emits with cycle=1 even after cycle 2 begins.
        val clock = ManualClock(initial = 0L)
        val sink = TraceSink(enabled = true, clock = clock, output = capture(), windowMs = 10_000L)
        val cycle1 = sink.beginCycle()
        val cycle2 = sink.beginCycle()       // simulates rapid second tap before first's lambda ran
        cycle1.emit("tap_post", "entry")      // first tap's posted runnable executes
        cycle2.emit("tap_post", "entry")
        assertEquals(2, captured.size)
        assertTrue(captured[0].contains("cycle=${cycle1.cycleId}"))
        assertTrue(captured[1].contains("cycle=${cycle2.cycleId}"))
        assertTrue(cycle2.cycleId > cycle1.cycleId)
    }

    @Test
    fun `default window is 10s — long delays still captured`() {
        // Validates the 5-6s diagnosis use case: a tap whose close phase
        // fires 6s after entry MUST still log.
        val clock = ManualClock(initial = 0L)
        val sink = TraceSink(enabled = true, clock = clock, output = capture())  // default 10s
        val cycle = sink.beginCycle()
        clock.advance(6_000L * 1_000_000L)
        cycle.emit("close", "exit", "result" to "Success")
        assertEquals(1, captured.size)
    }

    @Test
    fun `cycle ids are monotonic`() {
        val clock = ManualClock()
        val sink = TraceSink(enabled = true, clock = clock, output = capture())
        val a = sink.beginCycle()
        val b = sink.beginCycle()
        val c = sink.beginCycle()
        assertTrue(b.cycleId > a.cycleId)
        assertTrue(c.cycleId > b.cycleId)
    }

    private val captured = mutableListOf<String>()
    private fun capture(): (String) -> Unit = { captured += it }
}
```

- [ ] **Step 2: Implement TraceSink + TraceCycle (green)**

Create `app/src/main/java/com/bobassist/phase0/util/TraceSink.kt`:

```kotlin
package com.bobassist.phase0.util

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-cycle trace handle returned by [TraceSink.beginCycle]. Carries its own
 * window expiry so that posted-runnable emits from rapid taps stay correctly
 * attributed (each tap gets its own cycle).
 */
class TraceCycle internal constructor(
    val sessionId: Long,
    val cycleId: Long,
    private val openUntilNs: Long,
    private val clock: Clock,
    private val output: (String) -> Unit,
) {
    val enabled: Boolean = cycleId > 0    // 0 == disabled sentinel

    fun emit(phase: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        if (clock.nowNanos() > openUntilNs) return
        val sb = StringBuilder()
        sb.append("trace session=").append(sessionId)
            .append(" cycle=").append(cycleId)
            .append(" phase=").append(phase)
            .append(" event=").append(event)
            .append(" t_ns=").append(clock.nowNanos())
            .append(" thread=").append(Thread.currentThread().name)
        for ((k, v) in fields) {
            if (v == null) continue
            sb.append(' ').append(k).append('=').append(v)
        }
        output(sb.toString())
    }
}

/**
 * Structured trace emitter with per-tap cycle scoping.
 *
 * Usage: call [beginCycle] at the start of an event of interest (a tap, a
 * poll tick, a fg change). Use the returned [TraceCycle] to emit subsequent
 * phases. Each cycle has its own [windowMs] (default 10 s — long enough to
 * capture a worst-case 5-6 s tap-to-skip delay).
 *
 * Disabled (release builds): all beginCycle returns the disabled sentinel
 * cycle; emit is a no-op. No allocation per call (other than the cycle obj
 * if enabled).
 */
class TraceSink(
    private val enabled: Boolean,
    private val clock: Clock,
    private val output: (String) -> Unit = { Log.i(TAG, it) },
    private val sessionId: Long = sessionCounter.incrementAndGet(),
    val windowMs: Long = 10_000L,    // codex round-2 P1 #18: bumped from 2s for 5-6s diagnosis
) {
    private val cycleCounter = AtomicLong(0)
    private val disabledCycle = TraceCycle(sessionId, 0, 0, clock) { }

    fun beginCycle(): TraceCycle {
        if (!enabled) return disabledCycle
        val cid = cycleCounter.incrementAndGet()
        val openUntil = clock.nowNanos() + windowMs * 1_000_000L
        return TraceCycle(sessionId, cid, openUntil, clock, output)
    }

    companion object {
        private const val TAG = "BobTrace"
        private val sessionCounter = AtomicLong(0)
    }
}
```

- [ ] **Step 3: Run test, verify 5 pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.util.TraceSinkTest"`
Expected: 5 tests pass.

- [ ] **Step 4: Wire `TraceSink` into `OverlaySession` constructor (TraceCycle-scoped)**

Modify `OverlaySession.kt`: add `private val trace: TraceSink` to constructor. In `handleTap`, capture a per-tap cycle:

```kotlin
fun handleTap() {
    val cycle = trace.beginCycle()
    cycle.emit("tap", "entry", "state" to poller.currentState())
    val tapEntryNs = clock.nowNanos()
    pollHandler.post {
        cycle.emit("tap_post", "entry", "delay_ms" to (clock.nowNanos() - tapEntryNs) / 1_000_000L)
        if (!started) {
            cycle.emit("tap_post", "exit", "result" to "session_stopped")
            return@post
        }
        val state = poller.currentState()
        cycle.emit("state_check", "exit", "state" to state)
        when (state) {
            OverlayState.Ready -> {
                val result = runCatching { controller.killBattleSocket(cycle) }
                    .getOrElse {
                        breadcrumb("overlay tap kill threw: ${it.message}")
                        cycle.emit("kill", "exit", "result" to "exception", "msg" to it.message)
                        return@post
                    }
                cycle.emit("tap_post", "exit", "result" to result::class.simpleName)
                breadcrumb("overlay tap result=$result")
                if (result is BattleConnectionController.KillResult.Success) {
                    Log.i(TAG, "overlay kill success: id=${result.closedId}")
                    poller.enterCooldown()
                } else {
                    Log.i(TAG, "overlay kill non-success: $result")
                }
            }
            OverlayState.WaitingForBattle -> {
                cycle.emit("tap_post", "exit", "result" to "no_candidate")
                breadcrumb("overlay tap ignored (no candidate)")
            }
            OverlayState.Cooldown -> {
                cycle.emit("tap_post", "exit", "result" to "cooldown")
                breadcrumb("overlay tap ignored (cooldown)")
            }
        }
    }
}
```

The `cycle` value is captured by the lambda closure — even if 10 rapid taps each begin their own cycle, each lambda emits against its own. This is the codex round-2 P1 #19 fix.

(Note: `OverlaySession` constructor gains `clock: Clock` and `trace: TraceSink`. BobVpnService passes `AndroidElapsedRealtimeClock` and `TraceSink(enabled = BuildConfig.DEBUG, clock = AndroidElapsedRealtimeClock)`.)

- [ ] **Step 5: Wire trace around BattleConnectionController snapshot/pick/close**

In `BattleConnectionController.kt`, add a `cycle: TraceCycle? = null` parameter to `killBattleSocket()` (default null = silent):

```kotlin
fun killBattleSocket(cycle: TraceCycle? = null): KillResult {
    cycle?.emit("snapshot", "entry")
    val snapshotJson = snapshot()
    cycle?.emit("snapshot", "exit")
    
    cycle?.emit("pick", "entry")
    val (cand, count) = BattleConnection.pickWithCount(snapshotJson)
    cycle?.emit("pick", "exit", "candidate_count" to count, "picked_id" to cand?.id)
    if (cand == null) return KillResult.NoCandidate
    
    cycle?.emit("close", "entry", "conn_id" to cand.id)
    val r = close(cand.id)
    cycle?.emit("close", "exit", "result" to r.toString())
    
    return when (r) {
        MihomoCore.CloseResult.Success ->
            KillResult.Success(cand.id, cand.destinationIp, cand.destinationPort, count)
        MihomoCore.CloseResult.AlreadyClosed -> KillResult.AlreadyClosed
        else -> KillResult.Failure(r.toString())
    }
}
```

**Critical (codex round-1 P1 #4)**: BobVpnService's `controller by lazy { ... }` is built BEFORE TraceSink exists per session. Move controller construction INSIDE `startOverlayAndPolling()`:

In BobVpnService, REMOVE the lazy controller field. In `startOverlayAndPolling()`, before creating OverlaySession:

```kotlin
// codex round-4 P1 #34: Task 6 introduces RealConnectionCore; Task 7 introduces
// ConnectionCoreProvider. In Task 5 we still call MihomoCore directly.
// Task 6 swaps these two lambdas to RealConnectionCore.{connectionsJson,closeConnection},
// then Task 7 swaps again to ConnectionCoreProvider.get().{connectionsJson,closeConnection}.
val controller = BattleConnectionController(
    snapshot = { MihomoCore.connectionsJson() },
    close = { id -> MihomoCore.closeConnection(id) },
)
val trace = TraceSink(enabled = BuildConfig.DEBUG, clock = AndroidElapsedRealtimeClock)
// codex round-3 P1 #25: until Task 9, keep the old liveController assignment.
liveController = controller
// then pass both `controller` and `trace` to OverlaySession
```

The trace is passed to OverlaySession; OverlaySession.handleTap creates a TraceCycle from it and threads the cycle through to `controller.killBattleSocket(cycle)`.

- [ ] **Step 6: Pre-tap ring buffer DROPPED (codex round-2 P1 #18 says it's misleading)**

The earlier draft included a ring buffer that buffered emits outside cycle windows. Codex correctly noted: stale prefix data is more confusing than helpful, and the 10s default cycle window already covers everything we need. The pre-tap ring buffer is NOT implemented.

For poll-tick visibility (the original motivation for the buffer), `OverlayPoller.tick()` is updated in Task 8 to call `trace.beginCycle()` for each tick — giving poll ticks their own short-lived cycles. Logcat then naturally shows them as their own grep-able `cycle=N phase=poll_tick` lines.

- [ ] **Step 7: Build + run all tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 6 new TraceSinkTest pass + all old tests still green. BattleConnectionController signature added a default-null param, so existing tests still compile.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/util/TraceSink.kt \
        app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt \
        app/src/main/java/com/bobassist/phase0/core/BattleConnectionController.kt \
        app/src/main/java/com/bobassist/phase0/BobVpnService.kt \
        app/src/test/java/com/bobassist/phase0/util/TraceSinkTest.kt
git commit -m "phase1.3(test-infra): TraceSink + TraceCycle scoped per tap (10s default window); controller takes optional cycle; controller moved into session construction"
```

---

## Task 6: `LifecycleCoreFacade` + `ConnectionCoreFacade` + Real* implementations (stage 5.6)

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/core/CoreFacades.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/BobVpnService.kt` (use Real*)

- [ ] **Step 1: Create the facades file**

Create `app/src/main/java/com/bobassist/phase0/core/CoreFacades.kt`:

```kotlin
package com.bobassist.phase0.core

import android.net.VpnService

/**
 * Lifecycle ops on the embedded mihomo. Called once per VpnService start/stop;
 * NOT on the hot path. Separated from [ConnectionCoreFacade] so test code that
 * only needs connection inspection doesn't accidentally pull in the native
 * mihomo lifecycle path.
 */
interface LifecycleCoreFacade {
    fun version(): String
    fun setProtector(service: VpnService)
    fun setup(homeDir: String): Result<Unit>
    fun startTun(fd: Int, stack: String, gateway: String, dns: String): Result<Unit>
    fun stopTun(): Result<Unit>
}

/**
 * Runtime connection-table inspection + close. Used by [BattleConnectionController]
 * on the hot path. In debug builds, [DebugConnectionCoreOverride] can intercept
 * to inject fake snapshots / close results.
 */
interface ConnectionCoreFacade {
    fun connectionsJson(): String
    fun closeConnection(id: String): MihomoCore.CloseResult
}

object RealLifecycleCore : LifecycleCoreFacade {
    override fun version() = MihomoCore.version()
    override fun setProtector(service: VpnService) { MihomoCore.setProtector(service) }
    override fun setup(homeDir: String) = MihomoCore.setup(homeDir)
    override fun startTun(fd: Int, stack: String, gateway: String, dns: String) =
        MihomoCore.startTun(fd, stack, gateway, dns)
    override fun stopTun() = MihomoCore.stopTun()
}

object RealConnectionCore : ConnectionCoreFacade {
    override fun connectionsJson() = MihomoCore.connectionsJson()
    override fun closeConnection(id: String) = MihomoCore.closeConnection(id)
}
```

- [ ] **Step 2: BobVpnService — route through facades**

In `BobVpnService.kt`:
1. Replace `MihomoCore.setProtector(this)` with `RealLifecycleCore.setProtector(this)`.
2. Replace `MihomoCore.setup(cacheDir.absolutePath)` with `RealLifecycleCore.setup(...)`.
3. Replace `MihomoCore.startTun(...)` / `MihomoCore.stopTun()` with `RealLifecycleCore.*`.
4. Replace `MihomoCore.connectionsJson()` (in poller snapshot lambda) with `RealConnectionCore.connectionsJson()`. Same for `MihomoCore.closeConnection(...)` in controller.
5. In MainActivity, replace `MihomoCore.version()` with `RealLifecycleCore.version()`.

- [ ] **Step 3: Build + run all tests + smoke install**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` — all green.
Install + verify the app still starts and the overlay still appears.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/core/CoreFacades.kt \
        app/src/main/java/com/bobassist/phase0/BobVpnService.kt \
        app/src/main/java/com/bobassist/phase0/MainActivity.kt
git commit -m "phase1.3(test-infra): introduce LifecycleCoreFacade + ConnectionCoreFacade + Real* impls"
```

---

## Task 7: `DebugConnectionCoreOverride` + ConnectionCoreProvider variant + TestReceiver `sim_*` commands (stage 5.7)

**Files:**
- Create: `app/src/release/java/com/bobassist/phase0/core/ConnectionCoreProvider.kt`
- Create: `app/src/release/java/com/bobassist/phase0/core/ForegroundOverrideHolder.kt`
- Create: `app/src/main/java/com/bobassist/phase0/core/ForegroundOverrideProvider.kt` (interface + NoOpForegroundOverride)
- Create: `app/src/debug/java/com/bobassist/phase0/core/ConnectionCoreProvider.kt`
- Create: `app/src/debug/java/com/bobassist/phase0/core/ForegroundOverrideHolder.kt`
- Create: `app/src/debug/java/com/bobassist/phase0/core/DebugConnectionCoreOverride.kt`
- Modify: `app/src/debug/java/com/bobassist/phase0/TestReceiver.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/BobVpnService.kt` (use ConnectionCoreProvider + ForegroundOverrideHolder)

- [ ] **Step 1: Release-mode provider (codex P0 — must NOT be in src/main)**

AGP does NOT let `src/debug/` override a same-FQCN file in `src/main/` — both get compiled, causing duplicate-class error. Use **both `src/debug/` and `src/release/`**, leave `src/main/` clean.

Create `app/src/release/java/com/bobassist/phase0/core/ConnectionCoreProvider.kt`:

```kotlin
package com.bobassist.phase0.core

/** Release builds always use RealConnectionCore. */
object ConnectionCoreProvider {
    fun get(): ConnectionCoreFacade = RealConnectionCore
}
```

- [ ] **Step 2: Debug-mode override (with snapshotDelayMs + force_tick + foreground override)**

Create `app/src/debug/java/com/bobassist/phase0/core/DebugConnectionCoreOverride.kt`:

```kotlin
package com.bobassist.phase0.core

import java.util.concurrent.atomic.AtomicReference

// codex round-3 P1 #23 + round-4 P1 #36: implements BOTH facades; renames
// private property to `foregroundOverrideRef` to avoid collision with the
// interface method `foregroundOverride()`.
object DebugConnectionCoreOverride : ConnectionCoreFacade, ForegroundOverrideProvider {
    private val snapshotOverride = AtomicReference<String?>(null)
    private val closeOverrides = AtomicReference<Map<String, MihomoCore.CloseResult>>(emptyMap())
    private val snapshotDelayMs = AtomicReference(0L)      // codex P1 #5
    private val closeDelayMs = AtomicReference(0L)
    private val foregroundOverrideRef = AtomicReference<Boolean?>(null)  // codex P1 #7 + round-4 P1 #36

    fun setSnapshot(json: String?) { snapshotOverride.set(json) }
    fun setSnapshotDelay(ms: Long) { snapshotDelayMs.set(ms) }
    fun setCloseResult(id: String, result: MihomoCore.CloseResult) {
        while (true) {
            val curr = closeOverrides.get()
            val next = curr + (id to result)
            if (closeOverrides.compareAndSet(curr, next)) return
        }
    }
    fun setCloseDelay(ms: Long) { closeDelayMs.set(ms) }
    fun setForeground(v: Boolean?) { foregroundOverrideRef.set(v) }
    override fun foregroundOverride(): Boolean? = foregroundOverrideRef.get()
    fun clearAll() {
        snapshotOverride.set(null)
        closeOverrides.set(emptyMap())
        snapshotDelayMs.set(0)
        closeDelayMs.set(0)
        foregroundOverrideRef.set(null)
    }

    override fun connectionsJson(): String {
        val delay = snapshotDelayMs.get()
        if (delay > 0) Thread.sleep(delay)
        return snapshotOverride.get() ?: RealConnectionCore.connectionsJson()
    }

    override fun closeConnection(id: String): MihomoCore.CloseResult {
        val delay = closeDelayMs.get()
        if (delay > 0) Thread.sleep(delay)
        return closeOverrides.get()[id] ?: RealConnectionCore.closeConnection(id)
    }
}
```

Create `app/src/debug/java/com/bobassist/phase0/core/ConnectionCoreProvider.kt`:

```kotlin
package com.bobassist.phase0.core

object ConnectionCoreProvider {
    fun get(): ConnectionCoreFacade = DebugConnectionCoreOverride
}
```

Debug variant compiles main + debug sources; release variant compiles main + release sources. The same FQCN only appears once per variant — no duplicate-class error.

- [ ] **Step 3: BobVpnService — use ConnectionCoreProvider.get() + foreground override wiring**

In `BobVpnService.kt`:
1. Replace all `RealConnectionCore.connectionsJson()` / `RealConnectionCore.closeConnection(id)` with `ConnectionCoreProvider.get().connectionsJson()` / `.closeConnection(id)` (debug variant transparently gets the override).
2. In `queryForegroundPackage()`, FIRST check the variant-routed foreground override (no `BuildConfig.DEBUG` gate needed — `ForegroundOverrideHolder` is variant-resolved):

(codex round-2 P1 #15 + round-4 P1 #33: name MUST NOT contain "Debug" — release-dex grep would catch it. Use `ForegroundOverrideProvider`.)

Create `app/src/main/java/com/bobassist/phase0/core/ForegroundOverrideProvider.kt`:
```kotlin
package com.bobassist.phase0.core
interface ForegroundOverrideProvider {
    /** Returns null = use real detector; true = force HS foreground; false = force not. */
    fun foregroundOverride(): Boolean?
}
object NoOpForegroundOverride : ForegroundOverrideProvider {
    override fun foregroundOverride(): Boolean? = null
}
```

Create `app/src/release/java/com/bobassist/phase0/core/ForegroundOverrideHolder.kt`:
```kotlin
package com.bobassist.phase0.core
object ForegroundOverrideHolder { fun get(): ForegroundOverrideProvider = NoOpForegroundOverride }
```

Create `app/src/debug/java/com/bobassist/phase0/core/ForegroundOverrideHolder.kt`:
```kotlin
package com.bobassist.phase0.core
object ForegroundOverrideHolder { fun get(): ForegroundOverrideProvider = DebugConnectionCoreOverride }
```

Make `DebugConnectionCoreOverride` (debug variant) implement `ForegroundOverrideProvider` so its `foregroundOverride()` method serves both APIs.

In `BobVpnService.queryForegroundPackage()` (no `BuildConfig.DEBUG` gate needed — `ForegroundOverrideHolder` is variant-resolved):
```kotlin
private fun queryForegroundPackage(): String? {
    val fakeFg = ForegroundOverrideHolder.get().foregroundOverride()
    if (fakeFg != null) return if (fakeFg) HS_PACKAGE else "com.example.notbob"
    // ... existing real query ...
}
```

Codex round-3 P1 #22: This compiles in BOTH debug and release variants because the holder is resolved from the variant-specific source set. The main code never names `DebugConnectionCoreOverride` directly.

- [ ] **Step 4: TestReceiver — sim_* commands (includes 3 new: snapshot_delay, force_tick, set_foreground)**

In `TestReceiver.kt`, add new branches:

```kotlin
"sim_set_snapshot" -> {
    val json = intent.getStringExtra("json")
    DebugConnectionCoreOverride.setSnapshot(json)
    Log.i(TAG, "sim_set_snapshot len=${json?.length ?: 0}")
}
"sim_clear_snapshot" -> {
    DebugConnectionCoreOverride.setSnapshot(null)
    Log.i(TAG, "sim_clear_snapshot")
}
"sim_set_snapshot_delay" -> {                                       // codex P1 #5
    val ms = intent.getStringExtra("ms")?.toLongOrNull() ?: return
    DebugConnectionCoreOverride.setSnapshotDelay(ms)
    Log.i(TAG, "sim_set_snapshot_delay ms=$ms")
}
"sim_set_close" -> {
    val id = intent.getStringExtra("id") ?: return
    val resultStr = intent.getStringExtra("result") ?: "Success"
    val r = when (resultStr) {
        "Success" -> MihomoCore.CloseResult.Success
        "NotFound" -> MihomoCore.CloseResult.NotFound
        "AlreadyClosed" -> MihomoCore.CloseResult.AlreadyClosed
        "CoreStopped" -> MihomoCore.CloseResult.CoreStopped
        else -> MihomoCore.CloseResult.InternalError(-1)
    }
    DebugConnectionCoreOverride.setCloseResult(id, r)
    Log.i(TAG, "sim_set_close id=$id result=$r")
}
"sim_set_close_delay" -> {
    val ms = intent.getStringExtra("ms")?.toLongOrNull() ?: return
    DebugConnectionCoreOverride.setCloseDelay(ms)
    Log.i(TAG, "sim_set_close_delay ms=$ms")
}
"sim_set_foreground" -> {                                           // codex P1 #7 + round-2 P1 #16
    val v = intent.getStringExtra("value")
    val parsed: Boolean? = when (v?.lowercase()) {
        "true" -> true
        "false" -> false
        "null", "clear" -> null
        else -> null
    }
    DebugConnectionCoreOverride.setForeground(parsed)
    // codex round-5 P1: during transitional Tasks 7-8 (before Task 9),
    // liveSession doesn't exist yet — rely on detectorTick (every 2s) to
    // observe the override. Task 9 rewrites this branch to call
    // liveSession?.handleForegroundChange(parsed) for immediate drive.
    Log.i(TAG, "sim_set_foreground value=$parsed")
}
"sim_force_tick" -> {                                               // codex P1 #6 + round-4 P1 #35
    // Until Task 9 introduces liveSession, use a transitional companion
    // field `livePollHandler: Handler?` on BobVpnService (set in startOverlayAndPolling,
    // cleared in tearDown). Task 9 migrates this to liveSession.forceTickNow().
    val poller = BobVpnService.livePoller ?: run {
        Log.i(TAG, "sim_force_tick service_down"); return
    }
    val handler = BobVpnService.livePollHandler ?: run {
        Log.i(TAG, "sim_force_tick no_handler"); return
    }
    handler.post { runCatching { poller.tick() } }
    Log.i(TAG, "sim_force_tick dispatched")
}
```

Add to BobVpnService companion (transitional, removed in Task 9):
```kotlin
@Volatile var livePollHandler: android.os.Handler? = null
    internal set
```
Set after `pollHandler = pollHandler` in `startOverlayAndPolling`; clear in `tearDown`.
"sim_clear_all" -> {
    DebugConnectionCoreOverride.clearAll()
    Log.i(TAG, "sim_clear_all")
}
```

OverlaySession gains a `forceTickNow()` method:
```kotlin
/** Debug: trigger an immediate poller.tick() on pollHandler. */
fun forceTickNow() {
    pollHandler.post {
        if (!started) return@post
        poller.tick()
    }
}
```

- [ ] **Step 5: Build + smoke**

Run: `./gradlew :app:assembleDebug` — verify debug build picks up DebugConnectionCoreOverride.

Verify release variant **does NOT** contain DebugConnectionCoreOverride (codex round-2 P2 #20 tightened — use `strings` on the dex directly, not `unzip -p`):
```bash
./gradlew :app:assembleRelease 2>&1 | tail -5   # may fail signing — that's OK if intermediates produced
# Inspect release dex strings for debug symbols (empty = pass):
find app/build/intermediates -name "classes*.dex" -path "*release*" | while read f; do
  strings "$f" | grep -E 'DebugConnectionCoreOverride|sim_set|sim_force|sim_clear_all' && echo "FAIL: $f leaks debug symbol" || true
done
# Also check the built APK if signing produced one (name may be app-release-unsigned.apk):
RELEASE_APK=$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)
if [[ -n "$RELEASE_APK" ]]; then
  unzip -p "$RELEASE_APK" 'classes*.dex' | strings | grep -E 'DebugConnectionCoreOverride|sim_set|sim_force' && echo "FAIL: release APK leaks debug symbol" || echo "release APK clean"
fi
echo "release dex inspection done"
```

Note: `ForegroundOverrideProvider` is in main+release variants and IS expected to appear in the release dex — that's why we renamed away from `DebugOverrides*`. Only `DebugConnectionCoreOverride` and `sim_*` strings are forbidden in release.

Install debug + test ADB injection. Codex round-6 P1: during Task 7 (before Task 9), `sim_set_foreground` only sets the AtomicRef — the detector observes it on next tick (~2s interval). Smoke flow MUST wait for that detector tick before calling sim_force_tick:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.bobassist.phase0
adb shell am start -n com.bobassist.phase0/.MainActivity --ez auto_start true
sleep 6
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd sim_set_foreground --es value true
sleep 3  # IMPORTANT: wait for next detectorTick to observe the override and resume poller
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd sim_set_snapshot --es json '[{"id":"fake-1","host":"","network":"tcp","destinationPort":3724,"createdAt":99999}]'
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd sim_force_tick
sleep 1
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd overlay_state
sleep 1
adb logcat -d -s SpikeC:I | grep overlay_state
# Expect state=Ready (injected snapshot has a battle candidate; detector saw override; poller running)
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd sim_clear_all
```
(After Task 9, sim_set_foreground will immediately drive the session — the `sleep 3` won't be needed.)

- [ ] **Step 6: Commit**

```bash
git add app/src/release/java/com/bobassist/phase0/core/ConnectionCoreProvider.kt \
        app/src/release/java/com/bobassist/phase0/core/ForegroundOverrideHolder.kt \
        app/src/main/java/com/bobassist/phase0/core/ForegroundOverrideProvider.kt \
        app/src/debug/java/com/bobassist/phase0/core/DebugConnectionCoreOverride.kt \
        app/src/debug/java/com/bobassist/phase0/core/ConnectionCoreProvider.kt \
        app/src/debug/java/com/bobassist/phase0/core/ForegroundOverrideHolder.kt \
        app/src/debug/java/com/bobassist/phase0/TestReceiver.kt \
        app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt \
        app/src/main/java/com/bobassist/phase0/BobVpnService.kt
git commit -m "phase1.3(test-infra): debug-only sim_* commands (snapshot/close/snapshot_delay/close_delay/force_tick/set_foreground); release variant excludes all debug paths"
```

---

## Task 8: Trace points for remaining 8 phases (stage 5.8)

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/foreground/ForegroundDetector.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt`
- Modify: `app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt`

- [ ] **Step 1: OverlayPoller — trace tick + cooldown via per-call TraceCycle**

Add `trace: TraceSink? = null` constructor param. Each `tick()` and `enterCooldown()` gets its own short-lived cycle (own cycle id, own 10s window — cheap, but lets logcat grep `phase=poll_tick` independently):

```kotlin
fun tick() {
    if (!started) return
    if (paused) return
    if (state == OverlayState.Cooldown) return
    val cycle = trace?.beginCycle()
    cycle?.emit("poll_tick", "entry")
    emit(state.onPoll(snapshot()))
    cycle?.emit("poll_tick", "exit", "state" to state)
}

fun enterCooldown() {
    if (!started) return
    if (state == OverlayState.Cooldown) return
    val cycle = trace?.beginCycle()
    cycle?.emit("cooldown_enter", "entry")
    emit(OverlayState.Cooldown)
    scheduleAfter(OverlayState.COOLDOWN_MS) { exitCooldown() }
}

private fun exitCooldown() {
    if (state != OverlayState.Cooldown) return
    val cycle = trace?.beginCycle()
    cycle?.emit("cooldown_exit", "entry")
    emit(OverlayState.WaitingForBattle)
}
```

BobVpnService passes the same TraceSink to OverlayPoller.

- [ ] **Step 2: ForegroundDetector — trace state changes via per-call cycle**

Add `trace: TraceSink? = null` param. Each transition gets its own cycle:

```kotlin
fun tick() {
    val current = queryForegroundPackage() ?: return
    val next = (current == targetPackage)
    if (next == isTargetForeground) return
    val cycle = trace?.beginCycle()
    cycle?.emit("fg_change", "entry", "from" to isTargetForeground, "to" to next)
    isTargetForeground = next
    onChange(next)
    cycle?.emit("fg_change", "exit")
}
```

Same pattern for `reset()` — wrap in a cycle.

- [ ] **Step 3: OverlaySession.handleForegroundChange — trace setVisible call via own cycle**

```kotlin
fun handleForegroundChange(isHsForeground: Boolean) {
    val cycle = trace.beginCycle()
    cycle.emit("fg_change", "entry", "is_fg" to isHsForeground)
    breadcrumb("foreground change: HS=$isHsForeground")
    pollHandler.post {
        if (!started) return@post
        if (isHsForeground) poller.resume() else poller.pause()
    }
    val capturedOverlay = overlay
    mainHandler.post {
        if (!started) return@post
        cycle.emit("setVisible", "entry", "visible" to isHsForeground)
        runCatching { capturedOverlay.setVisible(isHsForeground) }
        cycle.emit("setVisible", "exit")
    }
}
```

- [ ] **Step 4: Run all tests, build**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: all green. OverlayPoller / ForegroundDetector tests don't pass `trace`, so default null kicks in — silent.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt \
        app/src/main/java/com/bobassist/phase0/foreground/ForegroundDetector.kt \
        app/src/main/java/com/bobassist/phase0/session/OverlaySession.kt
git commit -m "phase1.3(test-infra): trace points for poll_tick/cooldown_enter/cooldown_exit/fg_change/setVisible"
```

---

## Task 9: `liveSession` migration — drop `liveController/livePoller/liveTapTrigger` (stage 5.9)

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/BobVpnService.kt`
- Modify: `app/src/debug/java/com/bobassist/phase0/TestReceiver.kt`

- [ ] **Step 1: BobVpnService companion — single liveSession**

Replace the three `@Volatile var live*` fields with:

```kotlin
@Volatile var liveSession: OverlaySession? = null
    internal set
```

In `startOverlayAndPolling()`, after `this.session = session`:
```kotlin
liveSession = session
```

In `tearDown()`, first line:
```kotlin
liveSession = null
```

- [ ] **Step 2: TestReceiver — re-route the 3 overlay-related commands through liveSession; KEEP `kill`/`stop_core`/`record_*`**

codex P1 #8: `test-spike-c.sh` calls `cmd kill --es id <id>` three times. Do NOT remove. Same for `stop_core` and `record_*` from Spike D. Only the overlay-related commands AND the sim_* commands that previously used transitional live* fields migrate to liveSession.

codex round-5 P1: **also rewrite `sim_force_tick` and `sim_set_foreground`** in TestReceiver:

```kotlin
// REWRITTEN in Task 9:
"sim_force_tick" -> {
    val session = BobVpnService.liveSession ?: run { Log.i(TAG, "sim_force_tick service_down"); return }
    session.forceTickNow()
    Log.i(TAG, "sim_force_tick dispatched")
}
"sim_set_foreground" -> {
    val v = intent.getStringExtra("value")
    val parsed: Boolean? = when (v?.lowercase()) {
        "true" -> true; "false" -> false; "null", "clear" -> null; else -> null
    }
    DebugConnectionCoreOverride.setForeground(parsed)
    if (parsed != null) BobVpnService.liveSession?.handleForegroundChange(parsed)
    Log.i(TAG, "sim_set_foreground value=$parsed")
}
```

Also remove the transitional `livePollHandler` companion field from BobVpnService (no longer needed; `liveSession.forceTickNow` posts to its own pollHandler internally).

In `TestReceiver.kt`:

```kotlin
// MIGRATED to liveSession:
"kill_battle" -> {
    val session = BobVpnService.liveSession ?: run {
        Log.i(TAG, "kill_battle service_down"); return
    }
    when (val r = session.killBattleSocketDirect()) {
        is BattleConnectionController.KillResult.Success ->
            Log.i(TAG, "kill_battle n=${r.candidatesAtKill} id=${r.closedId} dst=${r.destinationIp}:${r.destinationPort} result=Success")
        BattleConnectionController.KillResult.NoCandidate ->
            Log.i(TAG, "kill_battle no_candidate (n=0)")
        BattleConnectionController.KillResult.AlreadyClosed ->
            Log.i(TAG, "kill_battle result=AlreadyClosed")
        is BattleConnectionController.KillResult.Failure ->
            Log.i(TAG, "kill_battle result=Failure reason=${r.reason}")
    }
}
"overlay_tap" -> {
    val session = BobVpnService.liveSession ?: run {
        Log.i(TAG, "overlay_tap service_down"); return
    }
    session.handleTap()
    Log.i(TAG, "overlay_tap dispatched")
}
"overlay_state" -> {
    val session = BobVpnService.liveSession
    val state = session?.poller?.currentState()?.let { stateLabel(it) } ?: "no_session"
    Log.i(TAG, "overlay_state state=$state service_alive=${session != null}")
}

// KEPT as-is (spike-c regression):
"snapshot" -> Log.i(TAG, "snapshot=${ConnectionCoreProvider.get().connectionsJson()}")
"kill" -> {
    val id = intent.getStringExtra("id") ?: run { Log.w(TAG, "kill: missing id"); return }
    Log.i(TAG, "kill id=$id result=${ConnectionCoreProvider.get().closeConnection(id)}")
}
"stop_core" -> { /* unchanged */ }
"record_start" -> { /* unchanged */ }
"record_stop" -> { /* unchanged */ }
"record_mark" -> { /* unchanged */ }
"version" -> Log.i(TAG, "version=${RealLifecycleCore.version()}")
```

Run `bash -n scripts/test-spike-*.sh` to confirm no syntax breakage; do NOT need to change spike scripts.

- [ ] **Step 3: Build + run all tests + test-spike-e regression**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` — all green.

Run on device (assuming HS available):
```bash
bash scripts/test-spike-e.sh   # Phase 1.1 regression — kill_battle must still skip animation
```
This depends on you being able to enter a BG round; alternative is to verify just the kill_battle log-path via injection:
```bash
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd sim_set_snapshot --es json '[{"id":"fake-1","host":"","network":"tcp","destinationPort":3724,"createdAt":99999}]'
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd sim_set_close --es id "fake-1" --es result Success
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd kill_battle
adb logcat -d -s SpikeC:I | grep kill_battle
# Expect: "kill_battle ... id=fake-1 ... result=Success"
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/BobVpnService.kt \
        app/src/debug/java/com/bobassist/phase0/TestReceiver.kt
git commit -m "phase1.3(test-infra): replace liveController/livePoller/liveTapTrigger with single liveSession; spike-e regression compatible"
```

---

## Task 10: Robolectric integration tests (stage 5.10)

**Files:**
- Create: `app/src/test/java/com/bobassist/phase0/util/FakeConnectionCore.kt`
- Create: `app/src/test/java/com/bobassist/phase0/integration/IntegrationFactory.kt` (codex round-4 P1 #37)
- Create: `app/src/test/java/com/bobassist/phase0/integration/OverlaySessionTapTest.kt`
- Create: `app/src/test/java/com/bobassist/phase0/integration/OverlaySessionCooldownTest.kt`
- Create: `app/src/test/java/com/bobassist/phase0/integration/OverlaySessionForegroundTest.kt`
- Create: `app/src/test/java/com/bobassist/phase0/integration/OverlaySessionTeardownRaceTest.kt`

- [ ] **Step 0: Shared `IntegrationFactory` test helper (codex round-3 P1 #26)**

Create `app/src/test/java/com/bobassist/phase0/integration/IntegrationFactory.kt`:

```kotlin
package com.bobassist.phase0.integration

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.foreground.ForegroundDetector
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.session.OverlaySession
import com.bobassist.phase0.util.AndroidElapsedRealtimeClock
import com.bobassist.phase0.util.FakeConnectionCore
import com.bobassist.phase0.util.FakeOverlayUi
import com.bobassist.phase0.util.TraceSink

class IntegrationFactory {
    val pollThread = HandlerThread("test-poll").apply { start() }
    val pollHandler = Handler(pollThread.looper)
    val mainHandler = Handler(Looper.getMainLooper())
    val clock = AndroidElapsedRealtimeClock
    val trace = TraceSink(enabled = false, clock = clock)   // tests don't need trace output
    val fakeConn = FakeConnectionCore()
    val fakeOverlay = FakeOverlayUi()
    val controller = BattleConnectionController(
        snapshot = { fakeConn.connectionsJson() },
        close = { id -> fakeConn.closeConnection(id) },
    )
    val poller = OverlayPoller(
        snapshot = {
            com.bobassist.phase0.core.BattleConnection
                .pickWithCount(fakeConn.connectionsJson()).second
        },
        onStateChange = { state -> mainHandler.post { fakeOverlay.applyState(state) } },
        scheduleAfter = { delayMs, cb -> pollHandler.postDelayed(cb, delayMs) },
        clock = clock,
        trace = trace,
    )
    var hsForegroundOverride: Boolean = true   // tests can flip this
    val detector = ForegroundDetector(
        queryForegroundPackage = {
            if (hsForegroundOverride) "com.blizzard.wtcg.hearthstone" else "com.example.notbob"
        },
        targetPackage = "com.blizzard.wtcg.hearthstone",
        onChange = { isFg -> session.handleForegroundChange(isFg) },
    )
    val session: OverlaySession by lazy {
        OverlaySession(
            controller = controller,
            poller = poller,
            detector = detector,
            overlay = fakeOverlay,
            pollHandler = pollHandler,
            mainHandler = mainHandler,
            clock = clock,
            trace = trace,
            hasUsageAccessPermission = { true },
            breadcrumb = { },
        )
    }
    
    fun tearDown() {
        session.stop()
        pollThread.quitSafely()
    }
}
```

All 4 integration tests use `IntegrationFactory` to construct a fresh world per test; `@After fun cleanup() = factory.tearDown()` ensures the HandlerThread is reaped.

- [ ] **Step 1: Test helper — FakeConnectionCore**

Create `app/src/test/java/com/bobassist/phase0/util/FakeConnectionCore.kt`:

```kotlin
package com.bobassist.phase0.util

import com.bobassist.phase0.core.ConnectionCoreFacade
import com.bobassist.phase0.core.MihomoCore

class FakeConnectionCore : ConnectionCoreFacade {
    @Volatile var snapshotJson: String = "[]"
    val closeCallLog: MutableList<Pair<Long, String>> =
        java.util.Collections.synchronizedList(mutableListOf())
    val closeResults: MutableMap<String, MihomoCore.CloseResult> =
        java.util.Collections.synchronizedMap(mutableMapOf())
    @Volatile var closeDelayMs: Long = 0L

    override fun connectionsJson(): String = snapshotJson
    override fun closeConnection(id: String): MihomoCore.CloseResult {
        closeCallLog.add(android.os.SystemClock.elapsedRealtimeNanos() to id)
        if (closeDelayMs > 0) Thread.sleep(closeDelayMs)
        return closeResults[id] ?: MihomoCore.CloseResult.Success
    }
}
```

- [ ] **Step 2: OverlaySessionTapTest**

Create `OverlaySessionTapTest.kt` with at least 4 tests:
- `tap on WaitingForBattle is no-op (no close call, no state change)`
- `tap on Ready calls closeConnection with the picked candidate id`
- `tap on Cooldown is suppressed`
- `tap on Ready that returns Success transitions to Cooldown`

Each test: build a fully-wired OverlaySession with FakeOverlayUi + FakeConnectionCore + real OverlayPoller + real ForegroundDetector (with queryForegroundPackage = { HS_PACKAGE } so it stays foreground) + real Handlers, drive via `ShadowLooper.idleFor`, assert `fakeConnectionCore.closeCallLog`.

- [ ] **Step 3: OverlaySessionCooldownTest**

At least 3 tests:
- `Success kill → Cooldown lasts exactly 2000ms then back to Waiting`
- `Tick during cooldown does NOT call connectionsJson`
- `Tap during cooldown is suppressed`

Use `shadowOf(pollLooper).idleFor(2_000, MILLISECONDS)`.

- [ ] **Step 4: OverlaySessionForegroundTest**

At least 3 tests:
- `Detector reports HS=false → overlay.setVisible(false) called on main looper`
- `Detector reports HS=true → overlay.setVisible(true) called; poller resumes`
- `Hide and re-show preserves lastState (e.g., Ready stays Ready)`

- [ ] **Step 5: OverlaySessionTeardownRaceTest** (codex P1 #3 required)

At least 4 tests:
- `Tap posted after stop() does NOT call controller`
- `Foreground change posted after stop() does NOT call setVisible`
- `Configuration change posted after stop() does NOT call overlay.onConfigurationChanged`
- `Poll tick after stop() does NOT call snapshot`

Methodology: call `session.handleTap()` (which posts to pollHandler), then immediately call `session.stop()`, then `shadowOf(pollLooper).idle()`. Assert the controller/UI was not invoked.

- [ ] **Step 6: Run all integration tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.integration.*"`
Expected: 4 test classes, 14+ tests total, all green.

Run full suite: `./gradlew :app:testDebugUnitTest`
Expected: 36+ (previous count + 14 new Robolectric) — all green.

- [ ] **Step 7: Commit**

```bash
git add app/src/test/java/com/bobassist/phase0/util/FakeConnectionCore.kt \
        app/src/test/java/com/bobassist/phase0/integration/IntegrationFactory.kt \
        app/src/test/java/com/bobassist/phase0/integration/OverlaySessionTapTest.kt \
        app/src/test/java/com/bobassist/phase0/integration/OverlaySessionCooldownTest.kt \
        app/src/test/java/com/bobassist/phase0/integration/OverlaySessionForegroundTest.kt \
        app/src/test/java/com/bobassist/phase0/integration/OverlaySessionTeardownRaceTest.kt
git commit -m "phase1.3(test-infra): 4 Robolectric integration tests for OverlaySession (tap/cooldown/foreground/teardown-race)"
```

---

## Task 11: `sim-bg-kill.sh` + 8 scenarios (stage 5.11)

**Files:**
- Create: `scripts/sim-lib.sh` (shared helpers)
- Create: `scripts/sim-bg-kill.sh`

- [ ] **Step 1: Shared helpers (sim-lib.sh)**

Create `scripts/sim-lib.sh` with:
- `BOB_PKG=com.bobassist.phase0`
- Each helper wraps `adb shell am broadcast -a com.bobassist.phase0.TEST -p $BOB_PKG --es cmd <name> ...`:
  - `sim_set_snapshot json`
  - `sim_clear_snapshot`
  - `sim_set_snapshot_delay ms`
  - `sim_set_close_delay ms`
  - `sim_set_foreground bool`
  - `sim_force_tick`
  - `sim_clear_all`
  - `overlay_tap`
  - `overlay_state` (and parses last logcat line)
- `wait_for_state(expected, timeout_s)` — polls `overlay_state` until match or timeout
- `parse_trace(log_file)` — `awk` over a logcat dump to produce a phase × dt_ms table, grouped by `cycle=<id>`

- [ ] **Step 2: Main script `sim-bg-kill.sh`**

8 scenarios. Each scenario:
1. Force-stop, install if `--rebuild` flag, start auto_start
2. Wait for `overlay + poller started` breadcrumb (kept for backward compat per codex P2 #10)
3. **Always** call `sim_set_foreground true` first (codex P1 #7) so poller doesn't pause on emulator/no-HS
4. Run scenario-specific injection sequence
5. Capture logcat lines tagged `BobTrace`
6. Parse + print phase table (one row per cycle, columns = phase, dt_ms)
7. Assert thresholds; output PASS/FAIL summary
8. `sim_clear_all` at end

**Scenario details** (the 4 new ones use the codex-mandated sim commands):

| Scenario | Sequence | PASS criteria |
|---|---|---|
| `cold_start` | sim_set_foreground true → sim_set_snapshot [{1 cand}] → sim_force_tick → wait state=Ready → overlay_tap → wait trace `close exit` | total `tap→close exit` dt_ms < 50ms |
| `rapid_tap` | sim_set_foreground true → sim_set_snapshot [{1 cand}] → sim_force_tick → 10 × overlay_tap in 200ms | exactly 1 `close entry` logged (rest dropped by cooldown) |
| `server_rotate` | sim_set_snapshot [{cand A, createdAt=100}] → sim_force_tick → sim_set_snapshot [{cand A, B (B newer)}] → sim_force_tick → overlay_tap | `pick exit picked_id=B` (newer wins) |
| `permission_revoke` | sim_set_snapshot [{1 cand}] → sim_force_tick → sim_set_foreground false → wait detector tick (~2s) → assert overlay hidden | overlay setVisible(false) seen in trace |
| `slow_snapshot` (codex P1 #5) | sim_set_snapshot_delay 1000 → sim_set_snapshot [{1 cand}] → sim_force_tick → overlay_tap | `snapshot` dt_ms ≥ 1000ms; total `tap→close exit` ≥ 1000ms (proves snapshot delays tap path) |
| `tap_while_snapshot` (codex P1 #5) | sim_set_snapshot_delay 2000 → sim_force_tick (kicks off long snapshot) → wait 200ms → overlay_tap (fires while snapshot still running) | `tap_post entry delay_ms` ≥ 1500ms — proves pollHandler queue serializes tap behind in-flight snapshot |
| `tap_at_poll_offsets` (codex P1 #6) | For each offset in [0, 200, 400, 600]: sim_set_snapshot [{1 cand}] → sim_force_tick → sleep offset → overlay_tap → record tap→close exit dt_ms → **wait for state==Waiting (2.5s for cooldown to expire)** → sim_clear_all → next iteration | max−min < 200ms (proves no surprising poll-thread starvation) |
| `preexisting_candidate` | **Special flow** (codex round-6 P1): the generic force-stop would nuke any in-memory override before service start. Instead: (1) auto-start service, wait for `overlay + poller started`; (2) sim_set_foreground true + wait 3s; (3) sim_set_snapshot [{1 cand}] and IMMEDIATELY sim_force_tick — measure dt_ms between sim_set_snapshot dispatch and state=Ready. ALSO can use `adb shell am broadcast --include-stopped-packages -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd sim_set_snapshot ...` if needing to set override before service start. | state goes Ready within 1 poll tick (~800ms) |

- [ ] **Step 3: Run cold_start on device**

```bash
cd /Users/jun/code/bob-assist/android/overlay-app
./scripts/sim-bg-kill.sh cold_start --rebuild
```
Expected: PASS, phase table printed.

- [ ] **Step 4: (Tier 2 milestone) Run cold_start on emulator**

Requires arm64-v8a AVD on Apple Silicon. If not yet installed:
```bash
sdkmanager "system-images;android-34;google_apis;arm64-v8a"
avdmanager create avd -n bob_test -k "system-images;android-34;google_apis;arm64-v8a"
$ANDROID_HOME/emulator/emulator -avd bob_test &
adb wait-for-device
```
Then run the same `./scripts/sim-bg-kill.sh cold_start --rebuild`.

- [ ] **Step 5: Commit**

```bash
git add scripts/sim-lib.sh scripts/sim-bg-kill.sh
chmod +x scripts/sim-bg-kill.sh
git commit -m "phase1.3(test-infra): sim-bg-kill.sh with 8 scenarios; cold_start verified on device + emulator"
```

---

## Task 12: 5-6s diagnosis with sim infrastructure (stage 5.12)

**Files:**
- Modify: `scripts/phase0-verification-report.md` (append Phase 1.3 section)

- [ ] **Step 1: Run the 4 diagnostic scenarios**

```bash
./scripts/sim-bg-kill.sh slow_snapshot
./scripts/sim-bg-kill.sh tap_while_snapshot
./scripts/sim-bg-kill.sh tap_at_poll_offsets
./scripts/sim-bg-kill.sh preexisting_candidate
```

For each, capture the phase table output.

- [ ] **Step 2: Analyze**

Look for:
- Does `tap_at_poll_offsets` show variance > 500ms between offsets? → polling rate is a factor
- Does `slow_snapshot` show that tap waits for snapshot to finish? → tap is queued behind snapshot in pollHandler
- Does `tap_while_snapshot` confirm the same? → strong evidence
- Does `preexisting_candidate` show fast tap-to-close (< 100ms)? → state-gate is the only delay

- [ ] **Step 3: Write diagnosis**

Append to `scripts/phase0-verification-report.md` under a new `## Phase 1.3 — Test Infra + 5-6s Diagnosis` section:
- Phase table for each scenario
- Conclusion: which phase(s) contribute to the 5-6s
- Proposed fix path (specific commits / parameter changes for Phase 1.4)

- [ ] **Step 4: Commit**

```bash
git add scripts/phase0-verification-report.md
git commit -m "phase1.3(test-infra): diagnosis report for 5-6s tap-to-skip delay"
```

---

## Phase 1.3 Exit Criteria (from spec §9)

1. `./gradlew :app:testDebugUnitTest` runs ≥35 tests (31 existing + 14 new Robolectric), all green.
2. `scripts/sim-bg-kill.sh cold_start` passes on Android emulator (mandatory, Tier 2).
3. OverlaySession extraction preserves all Phase 1.1+1.2 manual scenarios; `liveSession` migration keeps spike-e + spike-c regression green (kill, kill_battle, stop_core, record_* commands).
4. trace/sim produces a phase table classifying delay into poll-wait / handler-queue-wait / snapshot / parse / close / cooldown / HS-reconnect. If the 5-6s isn't reproduced via sim, the report says so explicitly.
5. Release APK dex contains no `sim_*` / `DebugConnectionCoreOverride` symbols (codex round-2 P1 #15: `ForegroundOverrideProvider` IS in release and is expected — only debug-specific names are forbidden):
   ```bash
   RELEASE_APK=$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)
   unzip -p "$RELEASE_APK" 'classes*.dex' | strings \
     | grep -E 'DebugConnectionCoreOverride|sim_set|sim_force|sim_clear_all' && exit 1 || echo "release dex clean"
   ```
6. Robolectric tests do NOT load `libgojni.so` (codex P2 #12 tightened):
   ```bash
   ./gradlew :app:testDebugUnitTest -Dorg.gradle.jvmargs="-verbose:class" 2>&1 \
     | grep -E 'libgojni|com.bobassist.gomobile.bobcore.Bobcore' && exit 1 || echo "no native load in tests"
   ```
   (`MihomoCore$CloseResult` references ARE expected — that's a pure-Kotlin sealed class, no native triggered.)

---

## Carrying-forward debts (NOT solved by 1.3)

| # | Debt | Where it goes |
|---|---|---|
| 5-6s delay fix itself | Implementation of whatever 1.3's diagnosis identifies | Phase 1.4 |
| 1, 7, 8 from Phase 1.2 carry list | DNS / watchdog / NetworkChangeWatcher | Phase 1.4–1.6 |
| OEM appops grant restriction | OxygenOS blocks `appops set` — same as 1.1/1.2 | release-side, not code |
