# Phase 1.1 — Overlay Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Phase-0 ADB `kill_battle` broadcast with a draggable, on-screen overlay button so a real user can skip an HS Battlegrounds battle animation without a computer attached.

**Architecture:** Stay on the single-foreground-service design from Phase 0 (D4 in spec). `BobVpnService` gains ownership of a new `OverlayWindow` (WindowManager-backed circular button) and a periodic poll loop. A new `BattleConnectionController` holds the kill business logic that both the overlay tap and the debug `kill_battle` broadcast call into. Polling cadence is 800 ms (spec §4.3); during the brief Cooldown window after a tap, polling pauses to avoid state thrash. No new Go/mihomo work — Phase 0 `bobcore.aar` is unchanged.

**Tech Stack:** Kotlin (no Compose for the overlay — `WindowManager` + plain `View` for token thrift and to avoid pulling Compose runtime into a `WindowManager` window), JUnit 4 for new unit tests. Existing `gomobile bind` artifact (`bobcore.aar`) is reused as-is.

---

## Codex Review (round 1, 2026-05-25) — findings + dispositions

Before execution starts the plan went through `codex exec` review against the real Phase 0 source files. The table below tracks every finding and where in this plan it's now addressed. Subagent executors: if a step's code disagrees with this table, the table wins — there was a reason it was added.

| # | Sev | Topic | Disposition |
|---|---|---|---|
| 1 | **P0** | Real overlay tap didn't call the kill controller (only emitted Cooldown). | Architecture redesigned: state machine no longer auto-cooldowns on tap. `BobVpnService.handleOverlayTap()` checks `currentState()`, calls `controller.killBattleSocket()` if Ready, then calls `poller.enterCooldown()` only on Success. See Task 7 Step 3. |
| 2 | P1 | `OverlayPoller` state mutated from two threads. | All poller mutations confined to `pollHandler`. `handleOverlayTap()` posts to `pollHandler` before reading/writing. Task 7 Step 3. State machine docstring documents the contract. |
| 3 | P1 | `tick()` calls `snapshot()` even during Cooldown. | `OverlayPoller.tick()` early-returns when `state == Cooldown`. Test in OverlayPollerTest pins this. Task 6 Step 3. |
| 4 | P1 | `bringUp()` + `startOverlayAndPolling()` not idempotent — repeated starts leak windows/threads. | `bringUp()` early-returns when `coreRunning`; `startOverlayAndPolling()` early-returns when `overlayRunning`. Task 7 Step 2. |
| 5 | P1 | Snapshot call during teardown can crash on the poll thread. | Snapshot lambda wrapped in `runCatching { ... }.getOrElse { 0 }`. Task 7 Step 3. |
| 6 | P1 | `FLAG_LAYOUT_NO_LIMITS` + unclamped drag can drop the button off-screen permanently. | Flag removed; coordinates clamped to `currentWindowMetrics`-derived safe bounds on drag end and on `show()`. Task 5 Step 1. |
| 7 | P2 | `defaultX()` computed at construction (stale dimensions for landscape HS). | Moved to `show()` time using `WindowManager.currentWindowMetrics`. Task 5 Step 1. |
| 8 | P2 | Debug `overlay_state` didn't actually log state. | Now logs `state=Waiting\|Ready\|Cooldown` via `OverlayPoller.currentState()`. Task 8 Step 2. |
| 9 | P2 | Debug `overlay_tap` was an alias for `kill_battle`, not the same code path as the real tap. | New `BobVpnService.liveTapTrigger` exposes `handleOverlayTap()` to the debug receiver, so `overlay_tap` exercises the exact production path (state gate + cooldown semantics). Task 8 Step 3. |
| 10 | P2 | Permission gate broke existing auto-start test scripts. | Manual-test recipe adds `adb shell appops set $BOB_PKG SYSTEM_ALERT_WINDOW allow` step before auto-start. Task 10 Step 1 callout. |
| 11 | P2 | "Every tap → Cooldown" test conflicted with "gray tap = no state change" smoke step. | Resolved by removing the tap-driven cooldown transition entirely; cooldown is only entered on a successful kill. Both tests + smoke step now consistent. Tasks 2, 6, 10. |
| 12 | P2 | Spec §4.3 "Permission warning" state silently dropped. | Made explicit: the overlay is ONLY created when both VPN authorization and `SYSTEM_ALERT_WINDOW` are granted (gated in MainActivity Task 9). A user who lacks permissions never sees the overlay at all — there is no "permission-warning" overlay state to display, because the overlay doesn't exist in that situation. Surfaced in `MainActivity` text + the `Grant Overlay Permission` button instead. |
| 13 | P3 | Start button not actually disabled. | `startBtn.isEnabled = hasOverlayPermission()`, refreshed in `onResume()`. Task 9. |
| 14 | P3 | `recreate()` after permission grant flickers. | Replaced with `onResume()` + `refreshPermissionUi()`. Task 9 Step 2. |
| 15 | P3 | Git paths assumed repo root. | Step instructions now say which cwd; commit blocks include the matching relative pathspec. Tasks 10 Step 10, 11 Step 5. |

### Round 2 (2026-05-25)
Round 2 confirmed zero P0/P1 issues remain. Five housekeeping items were raised and all addressed:

| # | Sev | Topic | Disposition |
|---|---|---|---|
| 16 | P2 | Round-1 #10 (appops grant) was aspirational, not a concrete script edit. | New Task 10 Step 1a explicitly edits all four `test-spike-*.sh` scripts to insert the appops line; includes a dedicated commit. |
| 17 | P2 | `OverlayWindow` clamp didn't handle orientation change or `ACTION_CANCEL`. | `OverlayWindow.onConfigurationChanged()` added + wired from `BobVpnService.onConfigurationChanged`. `ACTION_CANCEL` now treated like `ACTION_UP` for clamp purposes (Task 5 + Task 7 Step 6). |
| 18 | P3 | Task 11 `git diff` assumed repo-root cwd. | Step 1 now starts with explicit `cd /Users/jun/code/bob-assist`. |
| 19 | P3 | Stale test counts ("6 / 7 / 13+"). | Corrected to 7 / 9 / 6 = 22 total. |
| 20 | P3 | Stale debt #NEW 11 and stale `recreate()` reference in manual-test step. | Both removed. |

If a hypothetical round 3 finds more, append another section here.

---

## Scope Split: Phase 1 broken into ≤8 plans

The spec (`docs/superpowers/specs/2026-05-24-bob-assistant-android-design.md` §11 Phase 1) is large. Each subsystem below should get its own plan. This file covers only **1.1**.

| Plan | Scope | Why separable |
|---|---|---|
| **1.1 (this)** | Overlay button + SYSTEM_ALERT_WINDOW permission flow + poll loop + state machine | Self-contained UX upgrade; ships a usable product without the others |
| 1.2 | `ForegroundDetector` (UsageStatsManager) | Pure read-only API, gates overlay visibility/poll cadence |
| 1.3 | `NetworkChangeWatcher` + `bobcore.RefreshInterface()` | Needs new Go API |
| 1.4 | DNS forwarding via system `LinkProperties.dnsServers` (debt #1) | Profile generator + Go API |
| 1.5 | TUN-health watchdog (debt #7) | Cross-cuts service lifecycle |
| 1.6 | Full Onboarding flow (6 steps, spec §4.7) | Pure UI; can ship after core works |
| 1.7 | i18n (zh-Hans + en) + Settings/Diagnostics/About | Pure UI; benefits from real strings being in place |
| 1.8 | Profile generator (D3 structured YAML) | Refactor; no user-visible change |

**Out of scope for 1.1** (do NOT touch):
- ForegroundDetector — overlay is visible whenever VPN is up (acceptable Phase-0→1 step)
- Network change handling — Phase 0 behavior preserved
- DNS — still `8.8.8.8 / 1.1.1.1` per Phase 0 debt #1
- OEM watchdog — still relies on user restarting before play
- Onboarding/Settings/About — `MainActivity` stays minimal, gets one new inline permission prompt
- i18n — Phase 1.1 strings are hard-coded English; Phase 1.7 extracts to `strings.xml`
- Custom drawable design — use shape XML; brand work later
- Spec §4.3 nice-to-haves NOT shipped in 1.1: Toast on idle-state tap, vibration on Success, animated pulse on Ready, Ambiguous (orange) visual state. The 2 s red-flash cooldown gives sufficient tactile feedback for 1.1; the rest move to 1.7 when strings + design tokens land.

---

## Architecture Notes

### Why `BobVpnService` owns the overlay (not a separate `OverlayService`)
- Spec D4: single foreground service is mandated for OEM-kill survivability.
- `WindowManager` doesn't need a `Service` — `getSystemService(Context.WINDOW_SERVICE)` works from any `Context`, but the lifecycle host must outlive a tap. The service IS that host.
- Same coroutine/Handler hosts the poll loop, so no IPC needed between detector and overlay.

### State machine (3 visual states — drop spec's "Ambiguous orange" for 1.1)

The state machine is driven by TWO inputs only: poll ticks and an explicit `enterCooldown()` call from the service after a successful kill. **Taps do NOT drive the state machine directly** — the service decides whether a tap leads to a kill and whether that kill leads to cooldown. This separation keeps the state machine a pure visual-state automaton (easy to test) and lets the service own the side-effecting kill logic.

```
                  ┌──────────────────┐
   poll: 0 cand   │  WaitingForBattle│  (gray dot)
   ──────────────►│                  │
                  └────────┬─────────┘
                           │ poll: ≥1 candidate
                           ▼
                  ┌──────────────────┐
                  │      Ready       │  (green dot)
                  │                  │
                  └────────┬─────────┘
                           │ service.enterCooldown() after Success kill
                           ▼
                  ┌──────────────────┐
                  │    Cooldown      │  (red dot, 2 s; polling paused)
                  │                  │
                  └────────┬─────────┘
                           │ 2 s timer expires → back to WaitingForBattle
                           ▼
```

Rationale for dropping Ambiguous:
- `BattleConnection.pickWithCount` already disambiguates via `maxByOrNull { it.createdAt }`. The user-facing decision ("kill the newer one") is already made.
- Showing orange would force a 4th color in the UI without a different user action — confusing, not useful for 1.1. Spec §4.5 Ambiguous handling is preserved structurally (log multi-count to breadcrumb) but not surfaced visually.

### Cooldown semantics
- Enter cooldown ONLY on a successful kill (`KillResult.Success`). Taps on Waiting (no candidate) or during Cooldown do NOT enter cooldown — they are no-ops at the state level. This avoids the contradiction "every tap turns the button red even when nothing was killed."
- Polling **fully pauses** during cooldown — `tick()` early-returns without calling `snapshot()`. Avoids the race where the closed connection is still in the next mihomo snapshot for ~100 ms before cleanup.
- After 2 s: timer fires, state reverts to WaitingForBattle, the very next tick will move to Ready if a candidate re-appears.

### Thread confinement (P1 #2 from codex review)
ALL state mutations of `OverlayPoller` happen on a single thread — the poll `HandlerThread`. Specifically:
- `tick()` is posted by the periodic Runnable on `pollHandler`.
- The user-tap handler in `BobVpnService` posts to `pollHandler` before reading state or calling `enterCooldown()`.
- The Cooldown-expiry callback is scheduled via `pollHandler.postDelayed` so it also runs on the same thread.
- Only `onStateChange` callbacks are posted to the main looper for `WindowManager` mutations.

This eliminates the unsynchronized-mutation race codex flagged. `currentState()` is read by `TestReceiver` via `@Volatile var state` — safe for read-only consumers.

### Permission UX (deliberately minimal)
- `MainActivity` adds a third visible button: **"Grant Overlay Permission"**, shown only when `Settings.canDrawOverlays(this) == false`.
- Clicking it launches `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` via `Intent` for the user to toggle in OS settings. On return (`onActivityResult`) re-check.
- **`Start VPN` is blocked** (button greyed out, status text explains) until both VPN permission AND overlay permission are granted. Reason: starting VPN without overlay would put us in the same Phase-0 state (need ADB), which is no improvement.
- This is NOT the full 6-step Onboarding from spec §4.7 — that's Phase 1.6. This is the absolute minimum to make the overlay usable.

### Debug parity preserved
- `TestReceiver`'s `kill_battle` broadcast continues to work — both it and the overlay tap call the new `BattleConnectionController.killBattleSocket()`. This keeps `test-spike-e.sh` operational for regression coverage of the underlying mechanism.
- A new debug-only intent action `overlay_state` is added so `adb` scripts can read the current overlay state for headless verification.

---

## File Structure

**Create:**
- `app/src/main/java/com/bobassist/phase0/overlay/OverlayState.kt` — sealed class (WaitingForBattle / Ready / Cooldown)
- `app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt` — `WindowManager` host, draggable view, click + drag detection
- `app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt` — `HandlerThread`-driven 800 ms poll loop translating snapshots into state transitions
- `app/src/main/java/com/bobassist/phase0/core/BattleConnectionController.kt` — kill logic wrapper returning typed `KillResult`
- `app/src/main/res/drawable/overlay_circle_waiting.xml` — gray circle shape
- `app/src/main/res/drawable/overlay_circle_ready.xml` — green circle shape
- `app/src/main/res/drawable/overlay_circle_cooldown.xml` — red circle shape
- `app/src/test/java/com/bobassist/phase0/overlay/OverlayStateTest.kt` — state transition unit tests
- `app/src/test/java/com/bobassist/phase0/overlay/OverlayPollerTest.kt` — poll-loop → state-transition tests with a fake clock + fake snapshot supplier
- `app/src/test/java/com/bobassist/phase0/core/BattleConnectionControllerTest.kt` — controller unit tests

**Modify:**
- `app/src/main/AndroidManifest.xml` — add `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`
- `app/src/main/java/com/bobassist/phase0/MainActivity.kt` — overlay permission check + gating Start
- `app/src/main/java/com/bobassist/phase0/BobVpnService.kt` — own `OverlayWindow` + `OverlayPoller`
- `app/src/debug/java/com/bobassist/phase0/TestReceiver.kt` — route `kill_battle` through the new controller; add `overlay_state` introspection command
- `app/build.gradle.kts` — add `testImplementation("junit:junit:4.13.2")` and `testImplementation("org.json:json:20231013")` (we use `org.json.JSONArray` inside controller; on JVM tests we need the desktop polyfill since Android's `android.jar` org.json is stubbed)

---

## Task 1: Manifest + Gradle test dependencies

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add SYSTEM_ALERT_WINDOW permission**

Add inside `<manifest>`, alongside existing `<uses-permission>` lines:

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

- [ ] **Step 2: Add JUnit + JSON test deps to gradle**

Inside `dependencies { ... }` block in `app/build.gradle.kts`, append:

```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20231013")
```

Also append inside `android { ... }` block (so `src/test` is wired):

```kotlin
testOptions {
    unitTests.isReturnDefaultValues = true
}
```

- [ ] **Step 3: Verify gradle still syncs and builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. APK builds; new manifest permission appears in `aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk` output.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/build.gradle.kts
git commit -m "phase1.1(overlay): add SYSTEM_ALERT_WINDOW permission and JUnit test deps"
```

---

## Task 2: `OverlayState` sealed class with transition tests

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/overlay/OverlayState.kt`
- Create: `app/src/test/java/com/bobassist/phase0/overlay/OverlayStateTest.kt`

- [ ] **Step 1: Write failing test**

Create `app/src/test/java/com/bobassist/phase0/overlay/OverlayStateTest.kt`:

```kotlin
package com.bobassist.phase0.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStateTest {

    @Test
    fun `WaitingForBattle stays Waiting when no candidates`() {
        val next = OverlayState.WaitingForBattle.onPoll(candidateCount = 0)
        assertTrue(next is OverlayState.WaitingForBattle)
    }

    @Test
    fun `WaitingForBattle moves to Ready when candidates appear`() {
        val next = OverlayState.WaitingForBattle.onPoll(candidateCount = 1)
        assertEquals(OverlayState.Ready, next)
    }

    @Test
    fun `Ready stays Ready while candidates persist`() {
        val next = OverlayState.Ready.onPoll(candidateCount = 2)
        assertEquals(OverlayState.Ready, next)
    }

    @Test
    fun `Ready falls back to Waiting when candidates disappear`() {
        val next = OverlayState.Ready.onPoll(candidateCount = 0)
        assertTrue(next is OverlayState.WaitingForBattle)
    }

    @Test
    fun `Cooldown ignores poll updates regardless of candidate count`() {
        val cool = OverlayState.Cooldown
        assertEquals(cool, cool.onPoll(candidateCount = 0))
        assertEquals(cool, cool.onPoll(candidateCount = 1))
        assertEquals(cool, cool.onPoll(candidateCount = 7))
    }

    @Test
    fun `Cooldown after 2s timer transitions to WaitingForBattle on next poll source`() {
        // The state machine itself doesn't know about the timer — the
        // OverlayPoller fires the explicit transition. This test pins down
        // the SHAPE of the contract: onPoll alone never exits Cooldown.
        assertEquals(OverlayState.Cooldown, OverlayState.Cooldown.onPoll(candidateCount = 1))
    }

    @Test
    fun `each state exposes the correct visual identifier`() {
        assertEquals(OverlayState.Visual.WAITING, OverlayState.WaitingForBattle.visual)
        assertEquals(OverlayState.Visual.READY, OverlayState.Ready.visual)
        assertEquals(OverlayState.Visual.COOLDOWN, OverlayState.Cooldown.visual)
    }
}
```

- [ ] **Step 2: Run test to verify it fails (no class yet)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.overlay.OverlayStateTest"`
Expected: compilation failure (`OverlayState` does not exist).

- [ ] **Step 3: Implement `OverlayState`**

Create `app/src/main/java/com/bobassist/phase0/overlay/OverlayState.kt`:

```kotlin
package com.bobassist.phase0.overlay

/**
 * Pure visual-state machine for the floating overlay button.
 *
 * Inputs: only poll-driven candidate counts and explicit cooldown commands
 * from the host. Taps do NOT live in this state machine — the host service
 * (BobVpnService) decides on each tap whether to attempt a kill, and only
 * calls OverlayPoller.enterCooldown() when a kill actually succeeded. This
 * keeps the state machine side-effect-free and trivially unit-testable.
 *
 * Transitions:
 *   WaitingForBattle --(poll: ≥1 candidate)----> Ready
 *   Ready            --(poll: 0 candidates)----> WaitingForBattle
 *   any              --(host: enterCooldown)---> Cooldown        (in OverlayPoller, not here)
 *   Cooldown         --(timer: 2 s)------------> WaitingForBattle (in OverlayPoller, not here)
 */
sealed class OverlayState {

    /** Drives `OverlayWindow.applyState` to swap the circle drawable. */
    abstract val visual: Visual

    /** Called on each poll tick. Returns next state. Cooldown ignores polls. */
    open fun onPoll(candidateCount: Int): OverlayState = this

    object WaitingForBattle : OverlayState() {
        override val visual = Visual.WAITING
        override fun onPoll(candidateCount: Int) =
            if (candidateCount >= 1) Ready else this
    }

    object Ready : OverlayState() {
        override val visual = Visual.READY
        override fun onPoll(candidateCount: Int) =
            if (candidateCount >= 1) this else WaitingForBattle
    }

    /**
     * Cooldown is an `object` (not a data class) for 1.1 — the 2 s duration
     * is fixed and exit is owned by [OverlayPoller]'s scheduleAfter callback.
     */
    object Cooldown : OverlayState() {
        override val visual = Visual.COOLDOWN
        override fun onPoll(candidateCount: Int) = this
    }

    enum class Visual { WAITING, READY, COOLDOWN }

    companion object {
        const val COOLDOWN_MS = 2_000L
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.overlay.OverlayStateTest"`
Expected: all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/overlay/OverlayState.kt \
        app/src/test/java/com/bobassist/phase0/overlay/OverlayStateTest.kt
git commit -m "phase1.1(overlay): OverlayState sealed class with transition unit tests"
```

---

## Task 3: `BattleConnectionController` with kill-result unit tests

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/core/BattleConnectionController.kt`
- Create: `app/src/test/java/com/bobassist/phase0/core/BattleConnectionControllerTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/bobassist/phase0/core/BattleConnectionControllerTest.kt`:

```kotlin
package com.bobassist.phase0.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleConnectionControllerTest {

    @Test
    fun `returns NoCandidate when snapshot has no battle socket`() {
        val ctrl = BattleConnectionController(
            snapshot = { """[]""" },
            close = { error("should not be called") },
        )
        assertTrue(ctrl.killBattleSocket() is BattleConnectionController.KillResult.NoCandidate)
    }

    @Test
    fun `closes the unique candidate and returns Success`() {
        var closedId: String? = null
        val ctrl = BattleConnectionController(
            snapshot = { ONE_CANDIDATE_JSON },
            close = { id ->
                closedId = id
                MihomoCore.CloseResult.Success
            },
        )
        val r = ctrl.killBattleSocket()
        assertTrue(r is BattleConnectionController.KillResult.Success)
        assertEquals("abc-1", closedId)
        assertEquals(1, (r as BattleConnectionController.KillResult.Success).candidatesAtKill)
    }

    @Test
    fun `with two candidates picks newest and reports count`() {
        var closedId: String? = null
        val ctrl = BattleConnectionController(
            snapshot = { TWO_CANDIDATE_JSON },
            close = { id ->
                closedId = id
                MihomoCore.CloseResult.Success
            },
        )
        val r = ctrl.killBattleSocket() as BattleConnectionController.KillResult.Success
        assertEquals("newer-id", closedId)  // createdAt 2000 > 1000
        assertEquals(2, r.candidatesAtKill)
    }

    @Test
    fun `propagates AlreadyClosed result`() {
        val ctrl = BattleConnectionController(
            snapshot = { ONE_CANDIDATE_JSON },
            close = { MihomoCore.CloseResult.AlreadyClosed },
        )
        assertTrue(ctrl.killBattleSocket() is BattleConnectionController.KillResult.AlreadyClosed)
    }

    @Test
    fun `wraps unexpected close failures as Failure`() {
        val ctrl = BattleConnectionController(
            snapshot = { ONE_CANDIDATE_JSON },
            close = { MihomoCore.CloseResult.CoreStopped },
        )
        val r = ctrl.killBattleSocket()
        assertTrue(r is BattleConnectionController.KillResult.Failure)
    }

    @Test
    fun `wraps malformed snapshot as NoCandidate`() {
        val ctrl = BattleConnectionController(
            snapshot = { "this is not json" },
            close = { error("should not be called") },
        )
        assertTrue(ctrl.killBattleSocket() is BattleConnectionController.KillResult.NoCandidate)
    }

    companion object {
        private val ONE_CANDIDATE_JSON = """
            [{"id":"abc-1","host":"","network":"tcp","destinationIp":"66.40.189.110",
              "destinationPort":3724,"createdAt":1000}]
        """.trimIndent()

        private val TWO_CANDIDATE_JSON = """
            [
              {"id":"older-id","host":"","network":"tcp","destinationIp":"66.40.189.1",
               "destinationPort":3724,"createdAt":1000},
              {"id":"newer-id","host":"","network":"tcp","destinationIp":"66.40.189.2",
               "destinationPort":3724,"createdAt":2000}
            ]
        """.trimIndent()
    }
}
```

- [ ] **Step 2: Run tests, expect compilation failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.core.BattleConnectionControllerTest"`
Expected: compile error (`BattleConnectionController` not defined).

- [ ] **Step 3: Implement `BattleConnectionController`**

Create `app/src/main/java/com/bobassist/phase0/core/BattleConnectionController.kt`:

```kotlin
package com.bobassist.phase0.core

/**
 * Wraps "find current HS battle socket and close it" into a single typed call.
 *
 * Injection-friendly: takes a `snapshot` lambda returning the raw connections
 * JSON and a `close` lambda taking an id and returning [MihomoCore.CloseResult].
 * In production, both lambdas thunk into [MihomoCore]; in tests, they take
 * fixture JSON / fixed results.
 */
class BattleConnectionController(
    private val snapshot: () -> String,
    private val close: (String) -> MihomoCore.CloseResult,
) {

    sealed class KillResult {
        /** A candidate was found and closed cleanly. */
        data class Success(
            val closedId: String,
            val destinationIp: String,
            val destinationPort: Int,
            val candidatesAtKill: Int,
        ) : KillResult()

        /** Snapshot had no battle socket matching the BattleConnection filter. */
        object NoCandidate : KillResult()

        /** mihomo says the connection was already closed when we tried. */
        object AlreadyClosed : KillResult()

        /** Anything else (CoreStopped, NotFound after we just saw it, InternalError). */
        data class Failure(val reason: String) : KillResult()
    }

    fun killBattleSocket(): KillResult {
        val (cand, count) = BattleConnection.pickWithCount(snapshot())
        if (cand == null) return KillResult.NoCandidate
        return when (val r = close(cand.id)) {
            MihomoCore.CloseResult.Success ->
                KillResult.Success(
                    closedId = cand.id,
                    destinationIp = cand.destinationIp,
                    destinationPort = cand.destinationPort,
                    candidatesAtKill = count,
                )
            MihomoCore.CloseResult.AlreadyClosed -> KillResult.AlreadyClosed
            else -> KillResult.Failure(r.toString())
        }
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.core.BattleConnectionControllerTest"`
Expected: all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/core/BattleConnectionController.kt \
        app/src/test/java/com/bobassist/phase0/core/BattleConnectionControllerTest.kt
git commit -m "phase1.1(overlay): BattleConnectionController + unit tests"
```

---

## Task 4: Drawable resources for the three circle states

**Files:**
- Create: `app/src/main/res/drawable/overlay_circle_waiting.xml`
- Create: `app/src/main/res/drawable/overlay_circle_ready.xml`
- Create: `app/src/main/res/drawable/overlay_circle_cooldown.xml`

- [ ] **Step 1: Waiting (gray semi-transparent)**

Create `app/src/main/res/drawable/overlay_circle_waiting.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#99666666" />
    <stroke android:width="2dp" android:color="#CCFFFFFF" />
</shape>
```

- [ ] **Step 2: Ready (green pulse — solid color, no animation for 1.1)**

Create `app/src/main/res/drawable/overlay_circle_ready.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#CC2EA043" />
    <stroke android:width="2dp" android:color="#FFFFFFFF" />
</shape>
```

- [ ] **Step 3: Cooldown (red)**

Create `app/src/main/res/drawable/overlay_circle_cooldown.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#CCCF222E" />
    <stroke android:width="2dp" android:color="#FFFFFFFF" />
</shape>
```

- [ ] **Step 4: Build to confirm drawables compile**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable/overlay_circle_*.xml
git commit -m "phase1.1(overlay): three-state circle drawables"
```

---

## Task 5: `OverlayWindow` — WindowManager host with drag + tap

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt`

- [ ] **Step 1: Create the class**

Create `app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt`:

```kotlin
package com.bobassist.phase0.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.bobassist.phase0.R

/**
 * Owns the floating overlay button: WindowManager view + drag + tap detection +
 * state-driven appearance. Stateless w.r.t. battle detection — caller pushes
 * state in via [applyState], caller is notified of taps via [onTap].
 *
 * Position is persisted to SharedPreferences under [PREFS_FILE]; restored on
 * [show], saved + clamped on every drag end.
 *
 * Coordinates are stored in raw window pixels, anchored to TOP|START so that
 * `layoutParams.x` increments monotonically as the user drags right (drag
 * math stays trivial). For "default top-right" the initial x is computed
 * from current screen width at [show] time (P2 #7 from codex review).
 *
 * NOT thread-safe; all methods must run on the main looper of the host Service.
 * The `WindowManager` enforces this at runtime if violated.
 */
class OverlayWindow(
    private val context: Context,
    private val onTap: () -> Unit,
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private var view: TextView? = null
    private val layoutParams = WindowManager.LayoutParams(
        SIZE_DP.dp(context), SIZE_DP.dp(context),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        // FLAG_NOT_FOCUSABLE: never steal keystrokes from HS.
        // NOT using FLAG_LAYOUT_NO_LIMITS — that flag allowed the button to be
        // dragged under the status bar / notch and back into unrecoverable
        // territory; we clamp ourselves to safe bounds on drag end instead.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // x/y deferred to show() so we have current WindowMetrics.
    }

    fun show() {
        if (view != null) return
        val initial = currentSafeBounds()
        layoutParams.x = clamp(prefs.getInt(KEY_X, defaultX(initial)), initial.left, initial.right - SIZE_DP.dp(context))
        layoutParams.y = clamp(prefs.getInt(KEY_Y, DEFAULT_Y_DP.dp(context)), initial.top, initial.bottom - SIZE_DP.dp(context))

        val v = TextView(context).apply {
            text = "BG"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = context.getDrawable(R.drawable.overlay_circle_waiting)
            setOnTouchListener(TapAndDragListener())
        }
        wm.addView(v, layoutParams)
        view = v
        Log.i(TAG, "show at x=${layoutParams.x} y=${layoutParams.y}")
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    fun applyState(state: OverlayState) {
        val v = view ?: return
        val drawableRes = when (state.visual) {
            OverlayState.Visual.WAITING -> R.drawable.overlay_circle_waiting
            OverlayState.Visual.READY -> R.drawable.overlay_circle_ready
            OverlayState.Visual.COOLDOWN -> R.drawable.overlay_circle_cooldown
        }
        v.background = context.getDrawable(drawableRes)
    }

    /**
     * Safe area in raw screen pixels, accounting for system bars + display
     * cutout. Computed at show() / drag-end time so it reflects the current
     * orientation (P1 #6 from codex review).
     */
    private fun currentSafeBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
        } else {
            // Pre-R fallback: use raw screen size minus a conservative 24dp inset.
            val dm = context.resources.displayMetrics
            val pad = 24.dp(context)
            Rect(pad, pad, dm.widthPixels - pad, dm.heightPixels - pad)
        }
    }

    private fun defaultX(safe: Rect): Int =
        safe.right - SIZE_DP.dp(context) - DEFAULT_INSET_DP.dp(context)

    private fun clamp(value: Int, min: Int, max: Int): Int =
        when {
            min >= max -> min   // degenerate; happens on tiny test screens
            value < min -> min
            value > max -> max
            else -> value
        }

    private inner class TapAndDragListener : View.OnTouchListener {
        private var startTouchX = 0f
        private var startTouchY = 0f
        private var startWinX = 0
        private var startWinY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = e.rawX
                    startTouchY = e.rawY
                    startWinX = layoutParams.x
                    startWinY = layoutParams.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startTouchX
                    val dy = e.rawY - startTouchY
                    if (!dragging && (kotlin.math.abs(dx) > DRAG_SLOP_PX || kotlin.math.abs(dy) > DRAG_SLOP_PX)) {
                        dragging = true
                    }
                    if (dragging) {
                        layoutParams.x = startWinX + dx.toInt()
                        layoutParams.y = startWinY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, layoutParams) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        finalizeDrag(v)
                    } else if (e.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                        onTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    /**
     * Called on drag end OR on configuration change. Clamps the current
     * layoutParams to today's safe bounds, applies the new geometry, and
     * persists. Safe to call when no drag is in flight — clamp is a no-op
     * if already inside bounds.
     */
    private fun finalizeDrag(v: View) {
        val safe = currentSafeBounds()
        val viewSize = SIZE_DP.dp(context)
        layoutParams.x = clamp(layoutParams.x, safe.left, safe.right - viewSize)
        layoutParams.y = clamp(layoutParams.y, safe.top, safe.bottom - viewSize)
        runCatching { wm.updateViewLayout(v, layoutParams) }
        prefs.edit()
            .putInt(KEY_X, layoutParams.x)
            .putInt(KEY_Y, layoutParams.y)
            .apply()
    }

    /**
     * Host (BobVpnService) calls this on a configuration change (orientation,
     * fold, multi-display move). Re-clamps the existing position so the
     * button doesn't end up under the new system bars / outside the new
     * screen rect.
     */
    fun onConfigurationChanged() {
        view?.let { finalizeDrag(it) }
    }

    private fun Int.dp(ctx: Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BobOverlay"
        private const val SIZE_DP = 56
        private const val DEFAULT_INSET_DP = 24   // margin from right edge
        private const val DEFAULT_Y_DP = 120
        private const val DRAG_SLOP_PX = 12  // px, not dp, to avoid surprise on hi-dpi

        private const val PREFS_FILE = "bob_overlay_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}
```

- [ ] **Step 2: Confirm build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Note: `OverlayWindow` is currently unused — that's fine, the next task wires it.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt
git commit -m "phase1.1(overlay): OverlayWindow with WindowManager + drag/tap detection"
```

---

## Task 6: `OverlayPoller` — 800 ms snapshot loop with unit tests

**Files:**
- Create: `app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt`
- Create: `app/src/test/java/com/bobassist/phase0/overlay/OverlayPollerTest.kt`

The poller is a small state machine wrapper that:
- Calls a `snapshot: () -> Int` (returning the candidate count) every 800 ms, BUT skips `snapshot()` entirely while in Cooldown (P1 #3 from codex review).
- Calls `onStateChange: (OverlayState) -> Unit` only when the state CHANGES.
- Schedules the Cooldown → Waiting transition via a `scheduleAfter` lambda (so tests can use a fake one).
- Exposes an `enterCooldown()` method the service calls AFTER a successful kill.
- Exposes `currentState()` for read-only consumers (debug `overlay_state` broadcast).

- [ ] **Step 1: Write failing test**

Create `app/src/test/java/com/bobassist/phase0/overlay/OverlayPollerTest.kt`:

```kotlin
package com.bobassist.phase0.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPollerTest {

    @Test
    fun `emits initial state on start`() {
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { 0 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> error("not scheduled in this test") },
        )
        poller.start()
        assertEquals(listOf<OverlayState>(OverlayState.WaitingForBattle), emitted)
    }

    @Test
    fun `transitions Waiting to Ready when poll sees a candidate`() {
        val emitted = mutableListOf<OverlayState>()
        var count = 0
        val poller = OverlayPoller(
            snapshot = { count },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> },
        )
        poller.start()
        count = 1
        poller.tick()
        assertEquals(listOf<OverlayState>(OverlayState.WaitingForBattle, OverlayState.Ready), emitted)
    }

    @Test
    fun `does not re-emit when poll repeats same state`() {
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> },
        )
        poller.start()      // emits WaitingForBattle
        poller.tick()       // emits Ready
        poller.tick()       // stays Ready, no emit
        poller.tick()       // stays Ready, no emit
        assertEquals(2, emitted.size)
    }

    @Test
    fun `enterCooldown moves state to Cooldown and schedules the exit timer`() {
        val scheduled = mutableListOf<Long>()
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { delayMs, _ -> scheduled += delayMs },
        )
        poller.start()
        poller.tick()                 // Ready
        poller.enterCooldown()
        assertEquals(OverlayState.Visual.COOLDOWN, emitted.last().visual)
        assertEquals(listOf(OverlayState.COOLDOWN_MS), scheduled)
    }

    @Test
    fun `cooldown timer callback returns state to WaitingForBattle`() {
        val emitted = mutableListOf<OverlayState>()
        var expireCallback: (() -> Unit)? = null
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, cb -> expireCallback = cb },
        )
        poller.start()
        poller.tick()
        poller.enterCooldown()
        expireCallback!!()                          // simulate 2 s timer fire
        assertEquals(OverlayState.WaitingForBattle, emitted.last())
    }

    @Test
    fun `tick during Cooldown does NOT call snapshot or re-emit`() {
        var snapshotCalls = 0
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { snapshotCalls++; 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { _, _ -> },
        )
        poller.start()                              // emit Waiting; snapshotCalls=0
        poller.tick()                               // snapshotCalls=1, emit Ready
        poller.enterCooldown()                      // emit Cooldown; snapshotCalls=1
        val callsAfterCooldown = snapshotCalls
        val emittedSize = emitted.size
        poller.tick()                               // MUST early-return; no snapshot, no emit
        poller.tick()                               // same
        assertEquals(callsAfterCooldown, snapshotCalls)
        assertEquals(emittedSize, emitted.size)
    }

    @Test
    fun `enterCooldown is idempotent — second call during cooldown is a no-op`() {
        val scheduled = mutableListOf<Long>()
        val emitted = mutableListOf<OverlayState>()
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { emitted += it },
            scheduleAfter = { d, _ -> scheduled += d },
        )
        poller.start()
        poller.tick()
        poller.enterCooldown()
        val sizeBeforeRedundantCall = emitted.size
        poller.enterCooldown()                      // should be ignored
        assertEquals(sizeBeforeRedundantCall, emitted.size)
        assertEquals(1, scheduled.size)             // only one timer scheduled
    }

    @Test
    fun `currentState reflects latest emission`() {
        val poller = OverlayPoller(
            snapshot = { 1 },
            onStateChange = { },
            scheduleAfter = { _, _ -> },
        )
        assertEquals(OverlayState.WaitingForBattle, poller.currentState())
        poller.start()
        assertEquals(OverlayState.WaitingForBattle, poller.currentState())
        poller.tick()
        assertEquals(OverlayState.Ready, poller.currentState())
        poller.enterCooldown()
        assertEquals(OverlayState.Cooldown, poller.currentState())
    }

    @Test
    fun `companion exposes pollIntervalMs constant`() {
        assertEquals(800L, OverlayPoller.POLL_INTERVAL_MS)
    }
}
```

- [ ] **Step 2: Run test, expect compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.overlay.OverlayPollerTest"`
Expected: compile error.

- [ ] **Step 3: Implement `OverlayPoller`**

Create `app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt`:

```kotlin
package com.bobassist.phase0.overlay

/**
 * Drives [OverlayState] transitions from poll snapshots + explicit
 * enterCooldown commands from the host service.
 *
 * Pure logic; no Handler/Looper inside. Caller wires:
 *   - `snapshot`     : poll source returning candidate count
 *   - `onStateChange`: invoked when the state CHANGES (suppresses no-op repeats)
 *   - `scheduleAfter`: schedule the Cooldown-expiry callback (postDelayed in prod)
 *
 * Thread model: this class is NOT internally synchronized. The host MUST
 * confine all calls (`start`, `tick`, `enterCooldown`, the scheduleAfter
 * callback) to a single thread — see BobVpnService where everything posts
 * to `pollHandler`. The only thread-crossing read is `currentState()`,
 * backed by @Volatile for cheap consumer-side visibility.
 */
class OverlayPoller(
    private val snapshot: () -> Int,
    private val onStateChange: (OverlayState) -> Unit,
    private val scheduleAfter: (delayMs: Long, callback: () -> Unit) -> Unit,
) {

    @Volatile
    private var state: OverlayState = OverlayState.WaitingForBattle
    private var started = false

    fun start() {
        if (started) return
        started = true
        onStateChange(state)
    }

    /**
     * Periodic tick. Early-returns during Cooldown — snapshot is NOT called,
     * avoiding both wasted JNI work and the post-kill connection-table race.
     */
    fun tick() {
        if (!started) return
        if (state == OverlayState.Cooldown) return
        emit(state.onPoll(snapshot()))
    }

    /**
     * Host calls this AFTER a successful kill. Idempotent: calling while
     * already in Cooldown is a no-op (does NOT reschedule the timer).
     */
    fun enterCooldown() {
        if (!started) return
        if (state == OverlayState.Cooldown) return
        emit(OverlayState.Cooldown)
        scheduleAfter(OverlayState.COOLDOWN_MS) { exitCooldown() }
    }

    private fun exitCooldown() {
        if (state != OverlayState.Cooldown) return  // stale callback after teardown/restart
        emit(OverlayState.WaitingForBattle)
    }

    /** Snapshot of the latest emitted state. Safe to read from any thread. */
    fun currentState(): OverlayState = state

    private fun emit(next: OverlayState) {
        if (next == state) return
        state = next
        onStateChange(next)
    }

    companion object {
        const val POLL_INTERVAL_MS = 800L
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.overlay.OverlayPollerTest"`
Expected: all 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt \
        app/src/test/java/com/bobassist/phase0/overlay/OverlayPollerTest.kt
git commit -m "phase1.1(overlay): OverlayPoller (state-machine driver) with 9 unit tests"
```

---

## Task 7: Wire `OverlayWindow` + `OverlayPoller` into `BobVpnService`

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/BobVpnService.kt`

- [ ] **Step 1: Add overlay + poller fields and idempotency flag**

Edit `app/src/main/java/com/bobassist/phase0/BobVpnService.kt`. Add these imports near the top with existing ones:

```kotlin
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.bobassist.phase0.core.BattleConnection
import com.bobassist.phase0.core.BattleConnectionController
import com.bobassist.phase0.overlay.OverlayPoller
import com.bobassist.phase0.overlay.OverlayState
import com.bobassist.phase0.overlay.OverlayWindow
```

Inside the `BobVpnService` class, add these properties next to `pfd` / `coreRunning`:

```kotlin
private var overlay: OverlayWindow? = null
private var poller: OverlayPoller? = null
private var pollThread: HandlerThread? = null
private var pollHandler: Handler? = null
private val mainHandler = Handler(Looper.getMainLooper())
@Volatile private var overlayRunning = false   // idempotency guard (P1 #4)
private val controller: BattleConnectionController by lazy {
    BattleConnectionController(
        snapshot = { MihomoCore.connectionsJson() },
        close = { id -> MihomoCore.closeConnection(id) },
    )
}
```

- [ ] **Step 2: Make `bringUp()` idempotent (P1 #4 from codex review)**

At the top of `bringUp()`, before any work begins, add:

```kotlin
if (coreRunning) {
    breadcrumb("bringUp called while already running; ignoring")
    return
}
```

This prevents re-entrant bring-up from leaking a second TUN fd, OverlayWindow, or HandlerThread on repeat `ACTION_START` intents (sticky restarts, double-tap from UI).

- [ ] **Step 3: Start overlay + poller on successful TUN bring-up**

In `bringUp()`, inside the `.onSuccess` block (currently logs `"MihomoCore.startTun OK"`), append after the existing log lines:

```kotlin
liveController = controller
startOverlayAndPolling()
```

Add the new private methods to the class:

```kotlin
private fun startOverlayAndPolling() {
    if (overlayRunning) {
        breadcrumb("startOverlayAndPolling called while already running; ignoring")
        return
    }

    val ow = OverlayWindow(this, onTap = { handleOverlayTap() })
    overlay = ow
    mainHandler.post {
        runCatching { ow.show() }
            .onFailure {
                Log.e(TAG, "overlay show failed", it)
                breadcrumb("overlay show failed: ${it.message}")
            }
    }

    val ht = HandlerThread("BobOverlayPoll").apply { start() }
    val handler = Handler(ht.looper)
    pollThread = ht
    pollHandler = handler

    val p = OverlayPoller(
        snapshot = {
            // Guarded against teardown races (P1 #5): if mihomo has stopped,
            // count as 0 candidates so the next tick safely emits Waiting.
            runCatching {
                BattleConnection.pickWithCount(MihomoCore.connectionsJson()).second
            }.getOrElse { err ->
                breadcrumb("poll snapshot failed: ${err.message}")
                0
            }
        },
        onStateChange = { state ->
            mainHandler.post { ow.applyState(state) }
        },
        scheduleAfter = { delayMs, cb ->
            handler.postDelayed(cb, delayMs)
        },
    )
    poller = p

    val tick = object : Runnable {
        override fun run() {
            p.tick()
            handler.postDelayed(this, OverlayPoller.POLL_INTERVAL_MS)
        }
    }
    handler.post {
        p.start()
        handler.postDelayed(tick, OverlayPoller.POLL_INTERVAL_MS)
    }
    overlayRunning = true
    breadcrumb("overlay + poller started")
}

/**
 * User tapped the overlay. Confined to pollHandler so all state reads/writes
 * happen on a single thread (P1 #2). Performs the kill if Ready, ignores
 * tap otherwise. Enters Cooldown ONLY on Success — failures stay Ready so
 * the user can try again.
 */
private fun handleOverlayTap() {
    val handler = pollHandler ?: return
    val p = poller ?: return
    val ctrl = controller
    handler.post {
        when (p.currentState()) {
            OverlayState.Ready -> {
                val result = runCatching { ctrl.killBattleSocket() }
                    .getOrElse {
                        breadcrumb("overlay tap kill threw: ${it.message}")
                        return@post
                    }
                breadcrumb("overlay tap result=$result")
                if (result is BattleConnectionController.KillResult.Success) {
                    Log.i(TAG, "overlay kill success: id=${result.closedId} dst=${result.destinationIp}:${result.destinationPort}")
                    p.enterCooldown()
                } else {
                    // NoCandidate / AlreadyClosed / Failure — stay Ready,
                    // user can try again. No cooldown.
                    Log.i(TAG, "overlay kill non-success: $result")
                }
            }
            OverlayState.WaitingForBattle -> {
                breadcrumb("overlay tap ignored (no candidate)")
            }
            OverlayState.Cooldown -> {
                breadcrumb("overlay tap ignored (cooldown)")
            }
        }
    }
}
```

- [ ] **Step 4: Tear down overlay + poller in `tearDown()`**

Update `tearDown()` to also clean up overlay/poller. Replace its body with:

```kotlin
private fun tearDown() {
    overlayRunning = false
    liveController = null

    pollHandler?.removeCallbacksAndMessages(null)
    pollThread?.quitSafely()
    pollThread = null
    pollHandler = null
    poller = null

    overlay?.let { ow ->
        mainHandler.post { runCatching { ow.hide() } }
    }
    overlay = null

    if (coreRunning) {
        runCatching { MihomoCore.stopTun() }
            .onFailure { Log.e(TAG, "MihomoCore.stopTun failed", it) }
        coreRunning = false
    }
    runCatching { pfd?.close() }
    pfd = null
}
```

- [ ] **Step 5: Expose `liveController` and `livePoller` for the debug receiver**

Add to `companion object`:

```kotlin
@Volatile var liveController: BattleConnectionController? = null
    internal set

@Volatile var livePoller: OverlayPoller? = null
    internal set
```

(`internal set` allows the service instance methods in the same module to assign; outside the module the references are read-only.)

In `bringUp()` `.onSuccess` block, before `startOverlayAndPolling()`, also set `liveController = controller`. Inside `startOverlayAndPolling()`, after `poller = p`, also set `livePoller = p`. In `tearDown()`, already clears `liveController = null`; also add `livePoller = null`.

- [ ] **Step 6: Forward configuration changes to the overlay**

Add an `onConfigurationChanged` override on the service so orientation/fold/multi-display changes re-clamp the overlay position (P2 #2 from codex round 2):

```kotlin
override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    overlay?.let { ow ->
        mainHandler.post { runCatching { ow.onConfigurationChanged() } }
    }
}
```

No manifest change is needed — `Service.onConfigurationChanged` is always delivered for the full configuration; no `android:configChanges` attribute is required on `<service>`.

- [ ] **Step 7: Build to verify wiring compiles**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/BobVpnService.kt
git commit -m "phase1.1(overlay): wire OverlayWindow + OverlayPoller into BobVpnService (P0 #1, P1 #2/4/5, P2 #2)"
```

---

## Task 8: Route debug `kill_battle` through the controller; add `overlay_state` introspection

**Files:**
- Modify: `app/src/debug/java/com/bobassist/phase0/TestReceiver.kt`

- [ ] **Step 1: Replace the inline kill logic with controller call**

Open `app/src/debug/java/com/bobassist/phase0/TestReceiver.kt`. Replace the `killBattle()` method body:

```kotlin
private fun killBattle() {
    val ctrl = BobVpnService.liveController ?: run {
        Log.i(TAG, "kill_battle service_down")
        return
    }
    when (val r = ctrl.killBattleSocket()) {
        is BattleConnectionController.KillResult.Success ->
            Log.i(
                TAG,
                "kill_battle n=${r.candidatesAtKill} id=${r.closedId} " +
                    "dst=${r.destinationIp}:${r.destinationPort} result=Success",
            )
        BattleConnectionController.KillResult.NoCandidate ->
            Log.i(TAG, "kill_battle no_candidate (n=0)")
        BattleConnectionController.KillResult.AlreadyClosed ->
            Log.i(TAG, "kill_battle result=AlreadyClosed")
        is BattleConnectionController.KillResult.Failure ->
            Log.i(TAG, "kill_battle result=Failure reason=${r.reason}")
    }
}
```

Add the import at the top:

```kotlin
import com.bobassist.phase0.core.BattleConnectionController
```

(Keep the old `BattleConnection` import only if still used elsewhere; the inline `pickWithCount` call is now gone.)

- [ ] **Step 2: Add `overlay_state` command exposing the actual poller state**

In the `when (cmd)` block, add a new branch:

```kotlin
"overlay_state" -> {
    val poller = BobVpnService.livePoller
    val state = poller?.currentState()?.let { stateLabel(it) } ?: "no_poller"
    val live = BobVpnService.liveController != null
    Log.i(TAG, "overlay_state state=$state service_alive=$live")
}
```

Add this private helper at file scope (top-level or in the receiver class body):

```kotlin
private fun stateLabel(s: com.bobassist.phase0.overlay.OverlayState): String =
    when (s) {
        com.bobassist.phase0.overlay.OverlayState.WaitingForBattle -> "Waiting"
        com.bobassist.phase0.overlay.OverlayState.Ready -> "Ready"
        com.bobassist.phase0.overlay.OverlayState.Cooldown -> "Cooldown"
    }
```

- [ ] **Step 3: Add `overlay_tap` that exercises the SAME service path as a real tap**

Expose the production tap handler from `BobVpnService` so the debug receiver invokes the identical code path (P2 #9 from codex review). In `BobVpnService` companion object, add:

```kotlin
@Volatile var liveTapTrigger: (() -> Unit)? = null
    internal set
```

In `startOverlayAndPolling()` after `overlayRunning = true`, also assign:

```kotlin
liveTapTrigger = { handleOverlayTap() }
```

In `tearDown()`, alongside the other live-* clears, add:

```kotlin
liveTapTrigger = null
```

Then in the receiver `when` block, add:

```kotlin
"overlay_tap" -> {
    // Routes through the SAME service.handleOverlayTap() the real overlay
    // tap uses — same state-gating, same cooldown semantics, same kill
    // controller. Headless equivalent of a finger on glass.
    val trigger = BobVpnService.liveTapTrigger
    if (trigger == null) {
        Log.i(TAG, "overlay_tap service_down")
    } else {
        trigger()
        Log.i(TAG, "overlay_tap dispatched")
    }
}
```

Keep the old `kill_battle` command as-is — Spike E's regression test still uses it to probe the controller WITHOUT the state gate.

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/debug/java/com/bobassist/phase0/TestReceiver.kt
git commit -m "phase1.1(overlay): route debug kill_battle through controller; add overlay_state/overlay_tap"
```

---

## Task 9: `MainActivity` — gate Start on overlay permission

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/MainActivity.kt`

- [ ] **Step 1: Refactor `buildLayout` to keep button references + add overlay permission button**

Edit `app/src/main/java/com/bobassist/phase0/MainActivity.kt`. Add imports:

```kotlin
import android.net.Uri
import android.provider.Settings
```

Promote the buttons from local variables to class fields so `onResume()` can re-evaluate their state:

```kotlin
private lateinit var startBtn: Button
private lateinit var grantOverlayBtn: Button
```

Replace the body of `buildLayout()` so it assigns the field versions and includes the new overlay-permission button:

```kotlin
private fun buildLayout(): View {
    statusView = TextView(this).apply {
        text = "bobcore ${MihomoCore.version()}\nstatus: idle"
        textSize = 16f
        setPadding(40, 20, 40, 20)
    }
    startBtn = Button(this).apply {
        text = "Start VPN"
        setOnClickListener { onStartClicked() }
    }
    val stopBtn = Button(this).apply {
        text = "Stop VPN"
        setOnClickListener { onStopClicked() }
    }
    grantOverlayBtn = Button(this).apply {
        text = "Grant Overlay Permission"
        setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            // No result needed; onResume() re-checks when Settings closes.
            startActivity(intent)
        }
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 80, 40, 40)
        addView(statusView)
        addView(startBtn)
        addView(stopBtn)
        addView(grantOverlayBtn)
    }
}
```

- [ ] **Step 2: Add `onResume()` re-evaluating permission UI**

Add the override (P3 #14 from codex review — `onResume` beats `recreate()` for permission round-trip):

```kotlin
override fun onResume() {
    super.onResume()
    refreshPermissionUi()
}

private fun refreshPermissionUi() {
    val canOverlay = hasOverlayPermission()
    grantOverlayBtn.visibility = if (canOverlay) View.GONE else View.VISIBLE
    startBtn.isEnabled = canOverlay
    if (!canOverlay) {
        statusView.text = "bobcore ${MihomoCore.version()}\nOverlay permission required to start."
    }
}

private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)
```

- [ ] **Step 3: Gate `onStartClicked` on overlay permission**

Replace `onStartClicked()` with:

```kotlin
private fun onStartClicked() {
    if (!hasOverlayPermission()) {
        statusView.text = "${statusView.text}\nOverlay permission required."
        return
    }
    val prepare = VpnService.prepare(this)
    if (prepare != null) {
        startActivityForResult(prepare, REQ_VPN_AUTHORIZE)
        statusView.text = "${statusView.text}\nasking VPN authorization..."
    } else {
        launchService()
    }
}
```

The existing `onActivityResult` for `REQ_VPN_AUTHORIZE` stays unchanged. No new request code needed (we use `startActivity` for the overlay permission and let `onResume` rebuild the UI).

- [ ] **Step 4: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/MainActivity.kt
git commit -m "phase1.1(overlay): gate Start on SYSTEM_ALERT_WINDOW permission (P3 #13/#14)"
```

---

## Task 10: End-to-end manual smoke test on device

**Files:**
- (No code; this task documents the manual verification step.)

- [ ] **Step 1: Install latest APK**

Run:
```bash
cd /Users/jun/code/bob-assist/android/overlay-app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Performing Streamed Install` → `Success`.

> **Auto-grant overlay for headless test scripts (P2 #10 from codex review round 1, made concrete in round 2):**
> The existing `test-spike-b/c/d/e.sh` scripts call `MainActivity --ez auto_start true`, which under the new gate fails silently because overlay permission isn't granted. We add the appops grant inline in those scripts as part of this Task; see Step 1a below.

- [ ] **Step 1a: Update existing test scripts to auto-grant SYSTEM_ALERT_WINDOW**

Edit each of `scripts/test-spike-b.sh`, `scripts/test-spike-c.sh`, `scripts/test-spike-d.sh`, `scripts/test-spike-e.sh`. After the existing `adb shell am force-stop "$HS_PKG"` line (the second `force-stop` in each script), insert:

```bash
adb shell appops set "$BOB_PKG" SYSTEM_ALERT_WINDOW allow >/dev/null
```

This grants the SAW permission programmatically so the auto-start path exercises the production code path (which now requires overlay permission to launch the VPN). The grant is per-uid and persists across re-installs of the same APK.

Build to verify no syntax errors:
```bash
bash -n scripts/test-spike-b.sh scripts/test-spike-c.sh scripts/test-spike-d.sh scripts/test-spike-e.sh
```
Expected: silent success.

Commit (from `android/overlay-app/`):
```bash
git add scripts/test-spike-b.sh scripts/test-spike-c.sh scripts/test-spike-d.sh scripts/test-spike-e.sh
git commit -m "phase1.1(overlay): auto-grant SYSTEM_ALERT_WINDOW in test scripts (P2 #10)"
```

- [ ] **Step 2: Launch Bob, grant overlay permission**

Open Bob Phase 0 from the launcher. Tap **Grant Overlay Permission** → toggle Bob in Settings → press the back button to return. On return, `MainActivity.onResume()` re-checks the permission; the **Grant Overlay Permission** button hides itself and the **Start VPN** button becomes enabled.

- [ ] **Step 3: Start VPN, confirm overlay appears**

Tap **Start VPN**. Accept VPN authorization. A gray "BG" circle should appear at the top-right of the screen (default position). Check breadcrumb:
```bash
adb shell run-as com.bobassist.phase0 cat files/bob-breadcrumbs.log | tail -10
```
Expect to see `overlay + poller started`.

- [ ] **Step 4: Launch HS, wait for login**

```bash
adb shell monkey -p com.blizzard.wtcg.hearthstone -c android.intent.category.LAUNCHER 1
```
Wait until HS reaches the main menu. The overlay should still be gray (no battle socket yet).

- [ ] **Step 5: Enter BG combat, observe state change**

Enter Battlegrounds, pick a hero, wait for combat round 1 to start.
Within ~1 s of the battle socket appearing in the mihomo connection table, the overlay should turn **green**.

To verify programmatically:
```bash
adb shell am broadcast -a com.bobassist.phase0.TEST -p com.bobassist.phase0 --es cmd snapshot
adb logcat -d -s SpikeC:I | grep snapshot | tail -1
```
Confirm `host:"" network:"tcp" destinationPort:3724` entry present.

- [ ] **Step 6: Tap the overlay during combat animation**

Tap the green "BG" circle. Expected:
- Circle flashes red for 2 s.
- HS combat animation cuts to the result screen (the Phase 0 user-confirmed "全过" behavior).
- After 2 s, circle returns to gray (poller will re-promote to green when the next battle's socket appears).

- [ ] **Step 7: Drag-to-reposition sanity check**

Drag the circle to a new screen position. Force-stop the service:
```bash
adb shell am force-stop com.bobassist.phase0
```
Re-open and Start VPN — overlay should reappear at the dragged position (read from SharedPreferences).

- [ ] **Step 8: No-battle tap (no-op verified by state machine + logs)**

Stop the BG game. Wait for green → gray transition. Tap the gray circle.

Expected behavior (matches state-machine contract in Task 2 — gray tap is a no-op at the state level):
- Circle stays gray (no red flash, no cooldown).
- Logcat shows `BobVpnService: ... overlay tap ignored (no candidate)` breadcrumb but no `kill_*` call (no `BattleConnectionController.killBattleSocket()` invocation).
- `adb shell am broadcast -a com.bobassist.phase0.TEST -p $BOB_PKG --es cmd overlay_state` then `adb logcat -d -s SpikeC:I | tail -1` should still read `state=Waiting`.

The overlay does not surface a user-visible toast here in 1.1 (logging only). Phase 1.2 ForegroundDetector + spec §4.3 add a toast for idle taps.

- [ ] **Step 9: Document outcomes**

Append a short section to `scripts/phase0-verification-report.md` (path relative to `android/overlay-app/`, the cwd from Step 1) under a new "## Phase 1.1 — Overlay Button (date)" heading. Capture: which device, which steps PASS/FAIL, breadcrumbs file copy, any anomaly.

- [ ] **Step 10: Commit the report update only if anything was logged**

Commit from the cwd `android/overlay-app/`:
```bash
git add scripts/phase0-verification-report.md
git commit -m "phase1.1(overlay): manual smoke test report on $(date +%Y-%m-%d)"
```

---

## Task 11: Codex review of the Phase 1.1 diff

**Files:**
- (No code; this triggers external review per user's standing instruction "每一个实现都要 codex review".)

- [ ] **Step 1: Generate diff against the Phase-0-final commit**

From the repo root:
```bash
cd /Users/jun/code/bob-assist
git log --oneline | grep -m1 'Spike E\|PHASE 0 COMPLETE' | awk '{print $1}'
```
Note the commit SHA. Then (still in repo root):
```bash
git diff <SHA>..HEAD -- android/overlay-app android/bobcore > /tmp/phase1.1-overlay-diff.patch
wc -l /tmp/phase1.1-overlay-diff.patch
```

- [ ] **Step 2: Run codex CLI review**

```bash
codex review /tmp/phase1.1-overlay-diff.patch \
  --instructions "Phase 1.1 of Bob Assistant Android. Spec: docs/superpowers/specs/2026-05-24-bob-assistant-android-design.md. Plan: docs/superpowers/plans/2026-05-25-bob-android-phase1-overlay-button.md. Phase 0 (already shipped) provides VPN + mihomo + kill_battle via ADB broadcast. This change adds a WindowManager-backed overlay button that uses the same controller. Look for: WindowManager lifecycle leaks, HandlerThread shutdown safety, race conditions between bringUp and tearDown, missing main-thread enforcement, SharedPreferences misuse, drag-vs-click detection edge cases."
```

- [ ] **Step 3: Address P0/P1 findings inline (if any)**

For each P0/P1 issue codex raises:
- Diagnose root cause (don't paper over).
- Add a test if the issue is logic-level.
- Commit the fix with message `phase1.1(overlay): address codex review — <one-line summary>`.

- [ ] **Step 4: Re-run codex on the final diff**

After the fix commits, regenerate the diff and re-run codex review. Iterate until codex reports no P0/P1 issues (P2/P3 may be deferred to a follow-up plan if non-blocking).

- [ ] **Step 5: Append review summary to the verification report**

Add to `android/overlay-app/scripts/phase0-verification-report.md` under the Phase 1.1 section:
- Codex round count
- Each P0/P1 issue + how addressed (link to commit)
- Any deferred P2/P3 items

From the repo root `/Users/jun/code/bob-assist`:
```bash
git add android/overlay-app/scripts/phase0-verification-report.md
git commit -m "phase1.1(overlay): codex review summary"
```

(If you happen to be cwd'd to `android/overlay-app`, the pathspec becomes `scripts/phase0-verification-report.md`. Git operates on the working tree regardless of cwd; the pathspec must match the cwd.)

---

## Phase 1.1 Exit Criteria

All of these must hold before declaring Phase 1.1 done:

1. `./gradlew :app:testDebugUnitTest` passes — 22 unit tests across `OverlayStateTest` (7), `OverlayPollerTest` (9), `BattleConnectionControllerTest` (6).
2. `./gradlew :app:assembleDebug` passes with no new warnings (warning baseline = post-Phase-0 state).
3. Manual smoke test (Task 10) end-to-end PASS on the OnePlus 10T.
4. Codex review final round shows zero P0/P1 issues.
5. `test-spike-e.sh` still passes (regression — the `kill_battle` broadcast routes through the new controller and must give the same Success/post-kill-snapshot results).
6. Drag position persists across service restart.
7. Default position visible on first install: top-right of screen, 24dp inset from the right edge, 120dp from the top.

If any criterion fails, the corresponding fix is itself a TDD cycle within Phase 1.1; do not advance to 1.2 until all green.

---

## Carrying-forward debts NOT solved by 1.1

These remain open from Phase 0 and are explicitly OUT of scope for 1.1:

| # | Debt | Phase 1 plan that owns it |
|---|---|---|
| 1 | DNS uses 8.8.8.8/1.1.1.1 | 1.4 |
| 2 | `--ez auto_start true` is debug-only | (kept indefinitely; debug-only is correct) |
| 3 | UID/Process empty under cmfa tag | 1.2 (process resolver) |
| 4 | arm64-v8a only | (release-phase concern, deferred) |
| 5 | specialUse subtype placeholder | 1.6 (Onboarding) |
| 6 | TestReceiver open to any app | (keep debug-only; production removes via build variant) |
| 7 | VpnService 5h OEM kill | 1.5 (watchdog) |
| 8 | Scenario 5 untested | 1.3 (NetworkChangeWatcher) |
| 9 | Spike E (b)(f)(g) un-automated | Address (g) screenrecord in this plan's Task 10 step 9; (b)(f) deferred to 1.3 |
| 10 | `BattleConnection.pick` newest tiebreaker | RESOLVED in Phase 0 commit 0c545de |
