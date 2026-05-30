# Phase 1.2 — ForegroundDetector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Hide the overlay when Hearthstone is NOT in the foreground; show it (and resume polling) when HS returns to the foreground. This eliminates the Phase-1.1 UX wart where the overlay floated over every other app while VPN was running.

**Architecture:** Add `ForegroundDetector` — a small polling component that uses `UsageStatsManager.queryEvents()` (API 29+) to identify the current foreground package. The detector lives inside `BobVpnService` alongside the existing `OverlayPoller`. The service reacts to `ForegroundDetector` state changes by calling `OverlayWindow.setVisible(...)` and pausing/resuming `OverlayPoller`. If the user does NOT grant `PACKAGE_USAGE_STATS`, the detector reports HS as "always foreground" (spec D6 degraded mode) — the overlay stays visible all the time, same as Phase 1.1.

**Tech Stack:** Kotlin, `UsageStatsManager` + `AppOpsManager`, JUnit 4 for unit tests. No new gomobile / Go work.

---

## Codex Review (round 1, 2026-05-25)

| # | Sev | Topic | Disposition |
|---|---|---|---|
| 1 | **P1** | `OverlayWindow.show()` always starts gray; reappearing while poller is already Ready/Cooldown would show gray until the next state transition. | Task 4 Step 1: `OverlayWindow` gains a `lastState` field updated by `applyState()`; `show()` re-applies `lastState` after `wm.addView`. |
| 2 | **P1** | Permission-revoked degraded mode only works if usage access was missing at startup. After detector transitions to `false`, revoking permission keeps overlay hidden — violates spec D6. | Task 5 Step 3: `detectorTick` checks `hasUsageAccessPermission()` every tick; if missing, calls `det.reset()` (which forces `isTargetForeground=true`) instead of `det.tick()`. |
| 3 | P2 | `handleForegroundChange` thread-correctness fragile to future callers. | Task 5 Step 3: `handleForegroundChange` posts `poller?.pause()/resume()` calls onto `pollHandler` so confinement holds regardless of caller thread. |
| 4 | P2 | Manual smoke test didn't cover the two P1 cases. | Task 8 Steps 7a (revoke permission mid-session) and 7b (visual state preserved across hide/show) added. |
| 5 | P2 | `UsageStatsManager.queryEvents` can return null on R+ when user is locked; original code called `.hasNextEvent()` without null-check. | Task 5 Step 1: `queryForegroundPackage` uses `.getOrNull() ?: return null` before iterating. |
| 6 | P3 | File-structure bullet contradicted Task 6 ("gate Start on usage-access" vs. actual code keeping `startBtn.isEnabled = canOverlay`). | Removed the misleading phrase from the File Structure section. |
| 7 | P3 | Test scripts used the friendly appop alias `GET_USAGE_STATS`; OPSTR canonical form is `android:get_usage_stats`. | Task 7 Step 1 updated to use the canonical OPSTR string. |
| 8 | P3 | `setVisible` comment said "no-op before initial show" but the code called `show()`. | Task 4 Step 2: comment rewritten to match (calls `show()` when not yet attached). |

### Round 2 (2026-05-25)

| # | Sev | Topic | Disposition |
|---|---|---|---|
| 9 | P2 | Queued `setVisible(true)` posted to mainHandler could outlive `tearDown` and re-attach the window after stop. | Task 5 Step 3 `handleForegroundChange` now captures `ow` and guards the posted runnable with `if (!overlayRunning || overlay !== capturedOverlay) return@post`. |
| 10 | P3 | Posting pause/resume from pollHandler back onto pollHandler can let one in-flight tick race ahead. | Accepted as bounded one-tick race (max: one extra `connectionsJson()` call). Cost vs added complexity not worth gating. Documented here. |
| 11 | P3 | Task 8 Step 2 still used the friendly alias `GET_USAGE_STATS` (round-1 P3 #7 leftover). | Replaced with canonical `android:get_usage_stats`. |
| 12 | P3 | Exit criteria omitted Steps 7a/7b (the codex P1 regression checks). | Exit criterion 3 updated to include Steps 7a + 7b explicitly. |

---

## Scope (what 1.2 ships and what it does NOT)

**In scope:**
- New `ForegroundDetector` class + 5+ unit tests
- New `PACKAGE_USAGE_STATS` permission in manifest
- `MainActivity` adds a third permission button ("Grant Usage Access"), behaves like the overlay permission grant (Settings deep-link, `onResume` re-check)
- `OverlayWindow.setVisible(visible: Boolean)` — toggles `View.VISIBILITY`/window attach without destroying state
- `BobVpnService` owns `ForegroundDetector`; on detector state change posts an overlay show/hide and tells `OverlayPoller` to pause/resume polling
- `OverlayPoller` gains `pause()` and `resume()` so polling stops when HS is backgrounded (battery + JNI savings)
- Degraded mode: if usage-access permission absent or revoked, detector reports `isTargetForeground = true` and never transitions — overlay stays visible (spec D6)
- Smoke test on OnePlus 10T: open Bob → grant perms → open HS → see overlay; switch to another app → overlay hides; switch back → overlay reappears

**Out of scope (still deferred to later 1.x plans):**
- Auto-kill on combat detection (1.1 deferred)
- Network change watcher (1.3)
- DNS forwarding (1.4)
- OEM watchdog (1.5)
- Onboarding flow / Settings / Diagnostics / About / i18n (1.6, 1.7, 1.8)

---

## File Structure

**Create:**
- `app/src/main/java/com/bobassist/phase0/foreground/ForegroundDetector.kt`
- `app/src/test/java/com/bobassist/phase0/foreground/ForegroundDetectorTest.kt`

**Modify:**
- `app/src/main/AndroidManifest.xml` — add `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />` (with `xmlns:tools` if not already present)
- `app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt` — add `setVisible(visible: Boolean)`
- `app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt` — add `pause()` and `resume()` methods + an internal `paused` flag; `tick()` early-returns while paused
- `app/src/test/java/com/bobassist/phase0/overlay/OverlayPollerTest.kt` — add 2 tests for pause/resume
- `app/src/main/java/com/bobassist/phase0/BobVpnService.kt` — own ForegroundDetector, wire onChange callbacks, post overlay show/hide + poller pause/resume
- `app/src/main/java/com/bobassist/phase0/MainActivity.kt` — third permission UI; usage-access is OPTIONAL — `Start VPN` is NOT gated on it (only overlay permission gates Start). When usage-access is missing, the overlay falls back to "always visible" (Phase 1.1 behavior).

---

## Architecture Notes

### Why `UsageStatsManager.queryEvents` not `getRunningAppProcesses`
- `ActivityManager.getRunningAppProcesses()` returns only the caller's own process on Android 9+ — useless for detecting HS.
- `UsageStatsManager.queryEvents(beginTime, endTime)` returns app usage events including `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED`. The most recent `ACTIVITY_RESUMED` event identifies the current foreground package. Works on Android 10+ (our minSdk is 29).

### Detector signature (injection-friendly for tests)
```kotlin
class ForegroundDetector(
    /** Provider returning the current foreground package name, or null if unknown. */
    private val queryForegroundPackage: () -> String?,
    private val targetPackage: String,
    private val onChange: (Boolean) -> Unit,
) {
    @Volatile var isTargetForeground: Boolean = true  // optimistic default (D6)
        private set

    fun tick() { ... }
    fun reset() { ... }   // call when permission revoked → reverts to optimistic mode
}
```

The host (`BobVpnService`) provides the `UsageStatsManager`-backed implementation of `queryForegroundPackage`. Tests use a mutable variable.

### Why the default is `true` (HS-foreground-assumed)
If we haven't yet detected anything (first tick), or if the user denied usage-access, we want the overlay to be **visible**, not hidden. Hiding it as the default would create a worse UX than Phase 1.1 had (which always showed). The detector only HIDES when it positively detects a foreground app that is NOT HS.

### Poll cadence
- 2 seconds (matches spec §4.4). `UsageStatsManager.queryEvents` is cheap; 2 s is enough to feel responsive without burning battery.
- Detector tick runs on the same `pollHandler` thread the OverlayPoller uses — no new thread needed.

### Permission detection
```kotlin
fun hasUsageAccessPermission(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java)
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        applicationInfo.uid,
        packageName,
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
```
Note: `OPSTR_GET_USAGE_STATS` is the appop string. `unsafeCheckOpNoThrow` (API 29+) returns the mode without throwing.

### Permission grant flow
- Identical pattern to overlay permission: open `Settings.ACTION_USAGE_ACCESS_SETTINGS`, let user toggle, re-check in `onResume`.
- Note: `Settings.ACTION_USAGE_ACCESS_SETTINGS` brings up the LIST of all apps that requested usage access; user must find Bob Phase 0 manually. There is no per-app deep-link API for this setting on Android. Show a one-line hint in the UI explaining this.

### Pause vs hide semantics
- **Pause (poller)**: stop calling `MihomoCore.connectionsJson()` every 800 ms. Saves JNI work + DEX cycles. Resume when HS returns.
- **Hide (overlay)**: window is removed from `WindowManager` (so it can't accidentally receive touches or render). Re-added on resume at the saved position.

We could alternatively use `view.visibility = GONE` to keep the window attached but invisible. The downside is the window still receives configuration changes and consumes a window-token slot. Removing the view is cleaner and what Phase 1.1's `hide()` already does.

---

## Task 1: Manifest permission

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add `xmlns:tools` to `<manifest>` if not present, then the new permission**

Open `app/src/main/AndroidManifest.xml`. If the root `<manifest ...>` tag doesn't already declare `xmlns:tools`, add it:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
```

Then add inside `<manifest>` next to the other `<uses-permission>` lines:

```xml
<uses-permission
    android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
```

The `tools:ignore="ProtectedPermissions"` suppresses the lint warning — this permission is normally signature/system, but Android grants it via the special "Usage Access" Settings page on user toggle, which is exactly the flow we use.

- [ ] **Step 2: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Permission appears in `aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Commit**

From `android/overlay-app/`:
```bash
git add app/src/main/AndroidManifest.xml
git commit -m "phase1.2(foreground): add PACKAGE_USAGE_STATS permission"
```

---

## Task 2: `ForegroundDetector` class + unit tests (TDD)

**Files:**
- Create: `app/src/test/java/com/bobassist/phase0/foreground/ForegroundDetectorTest.kt`
- Create: `app/src/main/java/com/bobassist/phase0/foreground/ForegroundDetector.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/bobassist/phase0/foreground/ForegroundDetectorTest.kt`:

```kotlin
package com.bobassist.phase0.foreground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDetectorTest {

    @Test
    fun `default state is target-foreground=true (optimistic)`() {
        val detector = ForegroundDetector(
            queryForegroundPackage = { null },
            targetPackage = HS,
            onChange = { },
        )
        assertTrue(detector.isTargetForeground)
    }

    @Test
    fun `transitions to false when foreground is some other app`() {
        var current: String? = null
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        current = "com.example.notes"
        detector.tick()
        assertFalse(detector.isTargetForeground)
        assertEquals(listOf(false), changes)
    }

    @Test
    fun `transitions back to true when HS returns to foreground`() {
        var current: String? = "com.example.notes"
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.tick()                  // → false (changed)
        current = HS
        detector.tick()                  // → true (changed)
        assertTrue(detector.isTargetForeground)
        assertEquals(listOf(false, true), changes)
    }

    @Test
    fun `no onChange call when state stays the same`() {
        var current: String? = HS
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.tick()                  // HS already assumed → no change
        detector.tick()
        detector.tick()
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test
    fun `null query result preserves previous state (no events)`() {
        var current: String? = "com.example.notes"
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.tick()                  // → false
        current = null                   // no recent events
        detector.tick()                  // → no change, stays false
        detector.tick()
        assertFalse(detector.isTargetForeground)
        assertEquals(listOf(false), changes)
    }

    @Test
    fun `reset reverts to optimistic true and notifies`() {
        var current: String? = "com.example.notes"
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { current },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.tick()                  // → false
        detector.reset()                 // → true (optimistic)
        assertTrue(detector.isTargetForeground)
        assertEquals(listOf(false, true), changes)
    }

    @Test
    fun `reset is idempotent when already true`() {
        val changes = mutableListOf<Boolean>()
        val detector = ForegroundDetector(
            queryForegroundPackage = { null },
            targetPackage = HS,
            onChange = { changes += it },
        )
        detector.reset()
        detector.reset()
        assertTrue(detector.isTargetForeground)
        assertEquals(emptyList<Boolean>(), changes)
    }

    companion object {
        private const val HS = "com.blizzard.wtcg.hearthstone"
    }
}
```

- [ ] **Step 2: Run tests, confirm compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.foreground.ForegroundDetectorTest"`
Expected: compile failure (no `ForegroundDetector` class yet).

- [ ] **Step 3: Implement `ForegroundDetector`**

Create `app/src/main/java/com/bobassist/phase0/foreground/ForegroundDetector.kt`:

```kotlin
package com.bobassist.phase0.foreground

/**
 * Polling detector for whether [targetPackage] is the current foreground app.
 *
 * Pure logic; no Android dependencies — the host injects [queryForegroundPackage]
 * which (in production) wraps UsageStatsManager.queryEvents and returns the
 * latest ACTIVITY_RESUMED package name, or null if no recent events.
 *
 * Default state is `isTargetForeground = true` (optimistic). Reasons:
 * 1. If the user denies PACKAGE_USAGE_STATS, every tick returns null → we
 *    never transition away from "HS foreground", so the overlay stays visible
 *    (spec D6 degraded mode).
 * 2. If the detector hasn't ticked yet, we'd rather show the overlay than
 *    hide it — better to over-show than miss combat.
 *
 * Thread model: all methods must be called from a single thread (the host's
 * pollHandler in BobVpnService). isTargetForeground is @Volatile so consumers
 * on other threads can read it.
 */
class ForegroundDetector(
    private val queryForegroundPackage: () -> String?,
    private val targetPackage: String,
    private val onChange: (Boolean) -> Unit,
) {

    @Volatile var isTargetForeground: Boolean = true
        private set

    fun tick() {
        val current = queryForegroundPackage() ?: return  // unknown → keep state
        val next = (current == targetPackage)
        if (next == isTargetForeground) return
        isTargetForeground = next
        onChange(next)
    }

    /**
     * Revert to optimistic "foreground=true" state. Call when permission was
     * just revoked, or when the host wants to force a re-show.
     */
    fun reset() {
        if (isTargetForeground) return
        isTargetForeground = true
        onChange(true)
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
```

- [ ] **Step 4: Run tests, confirm all 7 pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.foreground.ForegroundDetectorTest"`
Expected: 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/foreground/ForegroundDetector.kt \
        app/src/test/java/com/bobassist/phase0/foreground/ForegroundDetectorTest.kt
git commit -m "phase1.2(foreground): ForegroundDetector class with 7 unit tests"
```

---

## Task 3: `OverlayPoller.pause()` / `resume()` + tests

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt`
- Modify: `app/src/test/java/com/bobassist/phase0/overlay/OverlayPollerTest.kt`

- [ ] **Step 1: Add 2 new failing tests**

Append to `OverlayPollerTest.kt` (inside the existing class), before the closing brace:

```kotlin
@Test
fun `tick during pause does NOT call snapshot or re-emit`() {
    var snapshotCalls = 0
    val emitted = mutableListOf<OverlayState>()
    val poller = OverlayPoller(
        snapshot = { snapshotCalls++; 1 },
        onStateChange = { emitted += it },
        scheduleAfter = { _, _ -> },
    )
    poller.start()                  // emit Waiting
    poller.tick()                   // emit Ready (snapshotCalls=1)
    poller.pause()
    val callsAfterPause = snapshotCalls
    val emittedAfterPause = emitted.size
    poller.tick()
    poller.tick()
    assertEquals(callsAfterPause, snapshotCalls)
    assertEquals(emittedAfterPause, emitted.size)
}

@Test
fun `resume after pause allows tick to run again`() {
    var count = 0
    val emitted = mutableListOf<OverlayState>()
    val poller = OverlayPoller(
        snapshot = { count },
        onStateChange = { emitted += it },
        scheduleAfter = { _, _ -> },
    )
    poller.start()
    count = 1
    poller.tick()                   // → Ready
    poller.pause()
    count = 0
    poller.tick()                   // suppressed
    assertEquals(OverlayState.Ready, poller.currentState())
    poller.resume()
    poller.tick()                   // now runs; count=0 → Waiting
    assertEquals(OverlayState.WaitingForBattle, poller.currentState())
}
```

- [ ] **Step 2: Run tests, confirm 2 fail to compile (`pause`/`resume` missing)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.overlay.OverlayPollerTest"`
Expected: compile failure.

- [ ] **Step 3: Implement `pause()` and `resume()`**

Edit `OverlayPoller.kt`. Add a private `paused` field:

```kotlin
private var paused: Boolean = false
```

Modify `tick()` to early-return when paused (the existing Cooldown early-return stays):

```kotlin
fun tick() {
    if (!started) return
    if (paused) return
    if (state == OverlayState.Cooldown) return
    emit(state.onPoll(snapshot()))
}
```

Add the new methods inside the class:

```kotlin
fun pause() {
    paused = true
}

fun resume() {
    paused = false
}
```

- [ ] **Step 4: Run tests, confirm all 11 pass**

(9 from Phase 1.1 + 2 new = 11 total in OverlayPollerTest.)

Run: `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.overlay.OverlayPollerTest"`
Expected: 11 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/overlay/OverlayPoller.kt \
        app/src/test/java/com/bobassist/phase0/overlay/OverlayPollerTest.kt
git commit -m "phase1.2(foreground): OverlayPoller pause/resume + 2 new unit tests"
```

---

## Task 4: `OverlayWindow.setVisible(...)` + smoke verification

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt`

- [ ] **Step 1: Add `lastState` field so `show()` doesn't lose Ready/Cooldown after re-attach (codex P1 #1)**

Edit `OverlayWindow.kt`. Add a private field at the top of the class (alongside `view`):

```kotlin
private var lastState: OverlayState = OverlayState.WaitingForBattle
```

Modify `applyState` so it remembers the latest state even when the view is detached:

```kotlin
fun applyState(state: OverlayState) {
    lastState = state
    val v = view ?: return
    val drawableRes = when (state.visual) {
        OverlayState.Visual.WAITING -> R.drawable.overlay_circle_waiting
        OverlayState.Visual.READY -> R.drawable.overlay_circle_ready
        OverlayState.Visual.COOLDOWN -> R.drawable.overlay_circle_cooldown
    }
    v.background = context.getDrawable(drawableRes)
}
```

Modify `show()` to re-apply `lastState` after the view is added (the initial drawable is still WAITING by default, then immediately overwritten by `applyState(lastState)`):

```kotlin
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
    applyState(lastState)  // re-apply remembered state after re-attach
    Log.i(TAG, "show at x=${layoutParams.x} y=${layoutParams.y} state=$lastState")
}
```

- [ ] **Step 2: Add `setVisible` method**

Add public method (after `applyState`):

```kotlin
/**
 * Show or hide the window without destroying the OverlayWindow instance.
 * Calling setVisible(true) when not yet shown calls [show] internally
 * (NOT a no-op — corrects the host's intent if they push state before
 * the first show). The remembered `lastState` is preserved across hide/show
 * cycles so transitions like Ready → hide → show stay Ready.
 */
fun setVisible(visible: Boolean) {
    if (visible) {
        if (view == null) show()
    } else {
        hide()
    }
}
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/overlay/OverlayWindow.kt
git commit -m "phase1.2(foreground): OverlayWindow.setVisible + remember last state across hide/show (codex P1 #1)"
```

---

## Task 5: Wire `ForegroundDetector` into `BobVpnService`

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/BobVpnService.kt`

The detector ticks every 2 s on the same `pollHandler` as the OverlayPoller. On `onChange(isForeground)`:
- If false (HS backgrounded): post `overlay.setVisible(false)` on main + `poller.pause()` on pollHandler
- If true (HS foregrounded): post `overlay.setVisible(true)` on main + `poller.resume()` on pollHandler

- [ ] **Step 1: Add imports + permission helper + provider lambda**

Add imports near the top:

```kotlin
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.bobassist.phase0.foreground.ForegroundDetector
```

Add a private helper method inside the class:

```kotlin
private fun hasUsageAccessPermission(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java) ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        applicationInfo.uid,
        packageName,
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Queries UsageStatsManager for the latest ACTIVITY_RESUMED event in the
 * last 60 s. Returns the foreground package name, or null if the query
 * returned an empty/null events stream — that is INTERPRETED BY THE
 * DETECTOR AS "no recent events, keep previous state". This function does
 * NOT inspect the permission state — the caller (detectorTick below) is
 * responsible for distinguishing "permission missing" vs "permission OK
 * but no events" and calling [ForegroundDetector.reset] vs [tick]
 * accordingly. See codex P1 #2.
 */
private fun queryForegroundPackage(): String? {
    val usm = getSystemService(UsageStatsManager::class.java) ?: return null
    val now = System.currentTimeMillis()
    // queryEvents can return null when the user is locked (R+) — handle it.
    val events = runCatching { usm.queryEvents(now - 60_000L, now) }
        .getOrNull() ?: return null
    var latestTs = 0L
    var latestPkg: String? = null
    val ev = UsageEvents.Event()
    while (events.hasNextEvent()) {
        events.getNextEvent(ev)
        if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && ev.timeStamp >= latestTs) {
            latestTs = ev.timeStamp
            latestPkg = ev.packageName
        }
    }
    return latestPkg
}
```

- [ ] **Step 2: Add detector field**

Inside the class body, alongside the existing `poller`/`overlay`/`pollHandler` fields:

```kotlin
private var detector: ForegroundDetector? = null
```

- [ ] **Step 3: Start the detector inside `startOverlayAndPolling()`**

In `startOverlayAndPolling()`, AFTER `poller = p` and the existing `handler.post { p.start(); handler.postDelayed(tick, ...) }` block, add:

```kotlin
val det = ForegroundDetector(
    queryForegroundPackage = { queryForegroundPackage() },
    targetPackage = HS_PACKAGE,
    onChange = { isForeground ->
        handleForegroundChange(isForeground)
    },
)
detector = det

val detectorTick = object : Runnable {
    override fun run() {
        // Codex P1 #2 — permission-revoked degraded mode.
        // If the user revokes Usage Access AFTER the detector has already
        // transitioned to "HS=false", we MUST force the detector back to
        // optimistic "true" so the overlay reappears (spec D6 degraded
        // mode). reset() is a no-op when already true.
        if (hasUsageAccessPermission()) {
            det.tick()
        } else {
            det.reset()
        }
        handler.postDelayed(this, ForegroundDetector.POLL_INTERVAL_MS)
    }
}
handler.postDelayed(detectorTick, ForegroundDetector.POLL_INTERVAL_MS)
```

Add a private helper method. Codex P2 #3: post poller mutations to pollHandler so future callers of `handleForegroundChange` from other threads (e.g., MainActivity broadcasting a permission-revoked event in a future plan) cannot break confinement.

```kotlin
private fun handleForegroundChange(isHsForeground: Boolean) {
    breadcrumb("foreground change: HS=$isHsForeground")
    pollHandler?.post {
        if (isHsForeground) poller?.resume() else poller?.pause()
    }
    val capturedOverlay = overlay
    if (capturedOverlay != null) {
        mainHandler.post {
            // Codex round-2 P2 — queued setVisible could outlive teardown
            // and re-attach the window after the service stopped. Guard
            // with overlayRunning + reference identity so a posted task
            // is dropped if anything has changed.
            if (!overlayRunning || overlay !== capturedOverlay) return@post
            runCatching { capturedOverlay.setVisible(isHsForeground) }
        }
    }
}
```

Note: when re-showing the overlay (`isHsForeground == true`), `OverlayWindow.show()` re-applies `lastState` automatically (codex P1 #1 fix in Task 4). No explicit `applyState` call needed from the service.

- [ ] **Step 4: Clear detector in `tearDown()`**

In `tearDown()`, alongside the other `live*` and overlay/poller clears, add:

```kotlin
detector = null
```

(The `pollHandler?.removeCallbacksAndMessages(null)` already drops any scheduled `detectorTick` Runnable.)

- [ ] **Step 5: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/BobVpnService.kt
git commit -m "phase1.2(foreground): wire ForegroundDetector into BobVpnService (hide overlay when HS not foreground)"
```

---

## Task 6: `MainActivity` — third permission button

**Files:**
- Modify: `app/src/main/java/com/bobassist/phase0/MainActivity.kt`

- [ ] **Step 1: Add usage-access state + helpers**

Add imports:

```kotlin
import android.app.AppOpsManager
```

Inside the class, alongside `startBtn` and `grantOverlayBtn`:

```kotlin
private lateinit var grantUsageBtn: Button
```

Add helper methods:

```kotlin
private fun hasUsageAccessPermission(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java) ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        applicationInfo.uid,
        packageName,
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
```

- [ ] **Step 2: Add the usage-access button to `buildLayout`**

In `buildLayout()`, after the `grantOverlayBtn` block, add:

```kotlin
grantUsageBtn = Button(this).apply {
    text = "Grant Usage Access (optional)"
    setOnClickListener {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }
}
```

And `addView(grantUsageBtn)` inside the returned LinearLayout, after `addView(grantOverlayBtn)`.

- [ ] **Step 3: Refresh UI based on usage-access state**

Extend `refreshPermissionUi()` to include the usage-access button:

```kotlin
private fun refreshPermissionUi() {
    val canOverlay = hasOverlayPermission()
    val canUsage = hasUsageAccessPermission()
    grantOverlayBtn.visibility = if (canOverlay) View.GONE else View.VISIBLE
    grantUsageBtn.visibility = if (canUsage) View.GONE else View.VISIBLE
    startBtn.isEnabled = canOverlay  // usage-access is OPTIONAL — does not gate Start
    val statusLines = mutableListOf("bobcore ${MihomoCore.version()}")
    if (!canOverlay) statusLines += "Overlay permission required to start."
    if (!canUsage) statusLines += "Usage access NOT granted — overlay will stay visible even when HS is closed."
    statusView.text = statusLines.joinToString("\n")
}
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/bobassist/phase0/MainActivity.kt
git commit -m "phase1.2(foreground): add Grant Usage Access button + onResume re-check"
```

---

## Task 7: Update Phase-1.1 test scripts to also auto-grant usage-access

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` — (no further changes; already done in Task 1)
- Modify: `scripts/test-spike-b.sh`, `c.sh`, `d.sh`, `e.sh`

- [ ] **Step 1: Insert appops grant lines**

After each script's existing `adb shell appops set "$BOB_PKG" SYSTEM_ALERT_WINDOW allow ... || echo ...` line (added in Phase 1.1), insert a parallel line for `GET_USAGE_STATS`:

```bash
adb shell appops set "$BOB_PKG" android:get_usage_stats allow >/dev/null 2>/dev/null || echo "[warn] could not appops-set android:get_usage_stats (OEM restriction?); requires manual grant once via Settings"
```

(Use the canonical OPSTR string `android:get_usage_stats` rather than the friendly alias `GET_USAGE_STATS` — both are accepted by recent `appops` CLI versions but the OPSTR form is unambiguous. See codex round-1 P3 #7.)

- [ ] **Step 2: Verify shell syntax**

Run: `bash -n scripts/test-spike-b.sh scripts/test-spike-c.sh scripts/test-spike-d.sh scripts/test-spike-e.sh`
Expected: silent success.

- [ ] **Step 3: Commit**

From `android/overlay-app/`:
```bash
git add scripts/test-spike-b.sh scripts/test-spike-c.sh scripts/test-spike-d.sh scripts/test-spike-e.sh
git commit -m "phase1.2(foreground): best-effort auto-grant GET_USAGE_STATS in test scripts"
```

---

## Task 8: Manual smoke test on device

**Files:** (none — this task is install + ADB + observation.)

- [ ] **Step 1: Build + install**

```bash
cd /Users/jun/code/bob-assist/android/overlay-app
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Try to programmatically grant usage-access (likely fails on OxygenOS)**

```bash
adb shell appops set com.bobassist.phase0 android:get_usage_stats allow
```

If this returns a SecurityException (OxygenOS / ColorOS), prepare for manual grant:
```bash
adb shell am start -a android.settings.USAGE_ACCESS_SETTINGS
```

User must navigate to Bob Phase 0 in the Usage Access list and toggle ON.

- [ ] **Step 3: Auto-start the service**

```bash
adb shell am force-stop com.bobassist.phase0
adb shell run-as com.bobassist.phase0 rm -f files/bob-breadcrumbs.log 2>/dev/null
adb shell am start -n com.bobassist.phase0/.MainActivity --ez auto_start true
sleep 6
adb shell run-as com.bobassist.phase0 cat files/bob-breadcrumbs.log | tail -25
```

Expected breadcrumb tail:
- `overlay + poller started`
- `foreground change: HS=false` within 2 s of service start (because the test launcher / Settings app is foreground, not HS).

If you see `HS=false` and the overlay disappears immediately — that confirms detector works.

- [ ] **Step 4: Open HS, confirm overlay reappears**

```bash
adb shell monkey -p com.blizzard.wtcg.hearthstone -c android.intent.category.LAUNCHER 1
```

Wait ~3 s after HS reaches login. Breadcrumb tail should now show `foreground change: HS=true`. Overlay should reappear (gray, since no battle socket yet).

- [ ] **Step 5: Background HS, confirm overlay hides**

Press home, or open another app. Within 2-4 s breadcrumb should show `foreground change: HS=false` and overlay disappears.

- [ ] **Step 6: Return to HS, confirm overlay reappears**

Bring HS back to foreground. Within 2-4 s `HS=true` and overlay reappears.

- [ ] **Step 7: Degraded mode test — usage permission denied AT STARTUP**

Revoke usage access via Settings (Bob Phase 0 → Usage Access → OFF). Restart the service:
```bash
adb shell am force-stop com.bobassist.phase0
adb shell am start -n com.bobassist.phase0/.MainActivity --ez auto_start true
```

Expected: overlay stays visible all the time. Detector ticks call `reset()` every 2 s; state stays optimistic `isTargetForeground=true`.

- [ ] **Step 7a: Codex P1 #2 — revoke permission MID-SESSION**

Re-grant usage access, restart service, open HS, then background HS (overlay hides). With overlay hidden, navigate to Settings → Bob Phase 0 → Usage Access → toggle OFF. Within 2 s the detector tick should now see no permission, call `reset()`, fire onChange(true), and the overlay should REAPPEAR even though HS is still backgrounded.

Verify via:
```bash
adb shell run-as com.bobassist.phase0 cat files/bob-breadcrumbs.log | tail -10
```
Expected: a `foreground change: HS=true` breadcrumb after the toggle.

- [ ] **Step 7b: Codex P1 #1 — visual state preserved across hide/show**

Open HS, enter BG combat. Wait for overlay to turn GREEN (Ready). Background HS — overlay hides. Return to HS — overlay should reappear and **stay green** (NOT revert to gray for one tick). Watch the screen during the re-show transition; gray-flash would mean `lastState` wasn't persisted.

- [ ] **Step 8: Document in verification report**

Append a "Phase 1.2 — ForegroundDetector (DATE)" section to `scripts/phase0-verification-report.md` recording PASS/FAIL of each step.

Commit (from `android/overlay-app/`):
```bash
git add scripts/phase0-verification-report.md
git commit -m "phase1.2(foreground): manual smoke test report on $(date +%Y-%m-%d)"
```

---

## Task 9: Codex code review

- [ ] **Step 1: From repo root, generate diff against Phase 1.1 final**

```bash
cd /Users/jun/code/bob-assist
PHASE_1_1_HEAD=$(git log --oneline | grep -m1 'phase1.1(overlay): codex code-review summary' | awk '{print $1}')
git diff "$PHASE_1_1_HEAD..HEAD" -- android/overlay-app > /tmp/phase1.2-diff.patch
wc -l /tmp/phase1.2-diff.patch
```

- [ ] **Step 2: Run codex review**

```bash
codex exec review --base "$PHASE_1_1_HEAD" --title "Phase 1.2 — ForegroundDetector"
```

- [ ] **Step 3: Address P0/P1 issues, re-run**

For each P0/P1 finding: diagnose, fix, commit with `phase1.2(foreground): address codex review — <one-line>`. Re-run codex until clean.

- [ ] **Step 4: Append codex summary to verification report**

```bash
git add android/overlay-app/scripts/phase0-verification-report.md
git commit -m "phase1.2(foreground): codex review summary"
```

---

## Phase 1.2 Exit Criteria

1. `./gradlew :app:testDebugUnitTest` passes — 31 unit tests (was 22; +7 ForegroundDetector + 2 OverlayPoller pause/resume).
2. `./gradlew :app:assembleDebug` clean.
3. Manual smoke test Task 8 Steps 1, 3, 4, 5, 6, 7, **7a**, **7b** all PASS on the OnePlus 10T. (Steps 7a and 7b verify the codex round-1 P1 regressions and are required for exit.)
4. Codex code review final round shows zero P0/P1.
5. `test-spike-e.sh` regression still passes (the underlying controller path is unchanged).
6. Degraded mode (no usage-access permission) preserves Phase 1.1 behavior exactly: overlay always visible.

---

## Carrying-forward debts (NOT solved by 1.2)

| # | Debt | Phase 1 plan that owns it |
|---|---|---|
| 1 | DNS uses 8.8.8.8/1.1.1.1 | 1.4 |
| 7 | VpnService 5h OEM kill | 1.5 |
| 8 | Scenario 5 untested (network change) | 1.3 |
| NEW 12 | `Settings.ACTION_USAGE_ACCESS_SETTINGS` is not per-app — user must scroll/find Bob in the list manually. No fix at the Android-API level on Android 14/15. | (UX docs in Onboarding 1.6) |
| NEW 13 | OxygenOS / ColorOS blocks `adb shell appops set ... GET_USAGE_STATS allow` (same root cause as the SYSTEM_ALERT_WINDOW issue from 1.1). Test scripts now print a `[warn]` line and proceed; user grants once via Settings. | (release-side; not code) |
