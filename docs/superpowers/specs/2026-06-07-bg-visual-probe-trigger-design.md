# BG Hero-Select Visual-Probe Trigger (§8.2) — Design

**Status:** READY-TO-PLAN (Codex review 1 incorporated — B1–B4 resolved, N1–N4 folded in)
**Parent:** [`2026-06-01-bg-hero-tier-overlay-design.md`](2026-06-01-bg-hero-tier-overlay-design.md) §8.2
**Unblocked by:** Spike-B result (real recording, see §2)

## 1. Goal

Implement the **§8.2 bounded visual probe** as the **primary** hero-select trigger for the tier
overlay, replacing the manual debug stub `SelectPhaseTrigger(isOpen = { tierForceOpen })` wired at
`BobVpnService.kt:465`. **Open** the overlay when a probe yields **≥ `OPEN_MATCHES`** hero matches;
**close** after `CLOSE_K` consecutive 0-match probes (or foreground-lost / `MAX_WINDOW_MS` /
projection-stop). The open/close decision must live in a **pure, JVM-unit-testable** component — it
is the one piece of the auto-trigger path that is currently unimplemented and untested.

## 2. Context — Spike-B is resolved (no select connection signature)

A real BG match was recorded (archived at
[`android/overlay-app/recordings/2026-06-07-bg-combat-signature/`](../../../android/overlay-app/recordings/2026-06-07-bg-combat-signature)) and analyzed:

- The **only** distinctive `host==""` socket is **`tcp :3724`**, and it appears at **COMBAT**
  (~+280s, after the select marks) and persists — **not during hero-select**. Hero-select shows
  only the normal `battle.net` connections reconnecting.
- **Conclusion:** there is **no clean select-phase connection signature** → §8.1 is not viable →
  **§8.2 visual probe is the primary trigger** (the spec anticipated this: §8.2 "Fallback /
  likely-primary", §13 Risk).
- The combat fingerprint `host=="" ∧ tcp ∧ port∈{1119,3724}` remains valid and is **confirmed on
  real data** — it stays the *kill* path; it is also available as an optional forced-close signal
  (§4.3, deferred).

OCR feasibility is already proven pre-game: ML Kit on a real select frame read 4 hero names and
`HeroMatcher` matched all 4 to card IDs + tiers, at ~270–330 ms/frame (well under a 2 s probe).

## 3. Scope

**In:**
- `VisualProbeGate` — new pure state machine (match-count → `Enter`/`Exit`/`None`).
- `HeroTierCoordinator` integration — drive the gate from a capture→OCR→match **probe** while
  closed; the existing §9 capture loop supersedes probing while open and feeds the gate's close.
- Constants `PROBE_MS`, `OPEN_MATCHES`, `CLOSE_K`.
- Wiring in `BobVpnService.enableTier` (`:459–465`); keep `tierForceOpen` as a **debug force-open**.
- Tests (gate: JUnit; coordinator: Robolectric).

**Out (unchanged, already validated):** OCR engine, `HeroMatcher`/`NameKey`/`TierTable`,
`BadgeRenderer`/`BadgeLayout` transform, the kill path, MediaProjection consent/lifecycle.
`SelectPhaseTrigger` (§8.1) is **retained but unwired** (cheap, tested; possible future
combat-fingerprint forced-close).

## 4. Design

### 4.1 `VisualProbeGate` — pure decision core (B5's main deliverable)

No Android imports. One probe result in, one `Transition` out. The gate owns ONLY the match-count
edge logic; the coordinator keeps the orthogonal closes (foreground-lost, `MAX_WINDOW_MS`,
projection-stop) it already has, and calls `forceClose()` to reset the gate on those.

```kotlin
class VisualProbeGate(
    private val openMatches: Int = 2,   // OPEN_MATCHES
    private val closeK: Int = 3,        // CLOSE_K consecutive 0-match probes
) {
    private var open = false
    private var consecutiveZero = 0

    /** Feed one probe's hero-match count. Single-fire edges. */
    fun onProbe(matchCount: Int): Transition = when {
        !open && matchCount >= openMatches -> { open = true; consecutiveZero = 0; Transition.Enter }
        open && matchCount == 0 -> {
            consecutiveZero++
            if (consecutiveZero >= closeK) { open = false; Transition.Exit } else Transition.None
        }
        open -> { consecutiveZero = 0; Transition.None }   // matchCount in 1..(openMatches-1) keeps it open
        else -> Transition.None
    }

    /** Coordinator-driven close (fg-lost / timeout / projection-stop). Idempotent. */
    fun forceClose() { open = false; consecutiveZero = 0 }
}
```

Reuses the existing `Transition { Enter, Exit, None }` enum. **Pure → fully unit-testable** by
feeding match-count sequences.

### 4.2 `HeroTierCoordinator` integration — ONE always-on tick

> **Revision 1 (Codex B1–B3):** the original "two cadences / two ticks" design was unsound — once
> the §9 render loop stops at `MAX_ATTEMPTS` it no longer feeds the gate (so `CLOSE_K`-zeros could
> never fire on a held window) and the held-window foreground guard was dropped, and two ticks race
> at the open edge (double capture). Resolved by collapsing to a **single capture tick**.

The coordinator collapses `pollTick` + `captureTick` into **one tick** that runs while `started`, so
there is exactly **one capture source at any instant** (no double capture) and the gate + foreground
check are fed continuously in **both** states. Each tick:

1. **Strict foreground gate (unchanged):** if `foreground() != Foreground.TRUE` → `closeWindow()` +
   `gate.forceClose()`, repost, return. *This now also guards the held/open window* — subsuming the
   role the continuous `pollTick` used to play (fixes B2).
2. **`capture() → ocr → match`** → `count = badges.size` (skip entirely if `!ocr.isAvailable()`, §4.3).
3. **Decide open/close:** if `forceOpen()` (debug) → force open, **bypass the gate** (§4.5); else
   `gate.onProbe(count)` → `Enter` → `openWindow()` / `Exit` → `closeWindow()` / `None`.
4. **Render** if `open && count > 0`, posted to `mainHandler` with the existing stale-rotation +
   `started && open && rotation` re-checks. (Re-rendering each round keeps badges fresh across
   rerolls.)
5. **Repost at an adaptive interval:**
   - **closed** → `PROBE_MS` (2000 ms);
   - **open**, first `MAX_ATTEMPTS` rounds → `CAPTURE_INTERVAL_MS` (700 ms): snappy first render +
     stabilize as art animates in (preserves the §9 capture-window intent);
   - **open**, after `MAX_ATTEMPTS` rounds → `PROBE_MS`: hold + monitor cheaply. The tick stays
     alive, so `CLOSE_K`-zeros **and** the foreground check remain reachable on a held window
     (fixes B1).

`openWindow()` resets `attempts=0` + arms `windowTimeout(MAX_WINDOW_MS)`; `closeWindow()` clears
badges + cancels the timeout; `onProjectionStopped()`/`stop()` → `closeWindow()` + `gate.forceClose()`.
A single tick means there is **no** `probeTick`/`captureTick` interleaving to cancel (fixes B3).

Preserved invariants: `started` no-op guard, single-fire `Enter`/`Exit` (now owned by the gate),
stale-rotation guard, render-on-`mainHandler` re-check, `MAX_WINDOW_MS`, `onProjectionStopped`,
`removeCallbacksAndMessages(null)` on stop.

### 4.3 Constructor change (surgical) + OCR availability

> **Revision 1 (Codex N2/N3).**

- Replace `trigger: SelectPhaseTrigger` with `gate: VisualProbeGate`, `forceOpen: () -> Boolean = { false }`,
  `probeMs: Long = 2000`.
- **Drop `connectionsJson` from the coordinator constructor entirely** (no dead param): triggering no
  longer reads connections, and the `CombatFingerprint` forced-close is **deferred** (the
  `CLOSE_K`-zeros close already covers heroes-gone). The kill path's own `CombatFingerprint`/
  connection use is untouched; only the tier coordinator stops taking `connectionsJson`.
- `SelectPhaseTrigger` (§8.1) file is **retained but unwired** (cheap, tested; future
  combat-fingerprint forced-close).
- **OCR availability seam:** add `HeroOcr.isAvailable(): Boolean` (default `true`; `MlKitHeroOcr`
  returns `false` if the recognizer/model can't initialize). If `!ocr.isAvailable()`, the coordinator
  logs once and **never captures** (feature inert) — honors §8.2 "if OCR/model unavailable, fallback
  disabled" without spinning a 2 s loop that always throws (a "guarded first recognize" would still
  cost one capture + consent, so prefer the explicit flag).

### 4.4 Constants (explicit)

| const | value | rationale |
|---|---|---|
| `PROBE_MS` | 2000 ms | spec §8.2; OCR ~300 ms ⇒ ~15% duty cycle while foreground |
| `OPEN_MATCHES` | 2 | spec §8.2; ≥2 tier-table hits ⇒ a real select screen, not a stray string |
| `CLOSE_K` | 3 | spec §8.2; ~3 consecutive 0-match probes ⇒ heroes gone |
| `CAPTURE_INTERVAL_MS` / `MAX_ATTEMPTS` / `MAX_WINDOW_MS` | 700 / 8 / 15000 | unchanged (§9) |

### 4.5 Wiring (`BobVpnService.enableTier`, `:459–465`)

Replace:
```kotlin
trigger = SelectPhaseTrigger(isOpen = { tierForceOpen }),
```
with:
```kotlin
gate = VisualProbeGate(),
forceOpen = { BuildConfig.DEBUG && tierForceOpen },
```
**Force-open precedence (Codex B4):** while `forceOpen()` is true the tick **bypasses the gate** —
opens (if not already), renders whatever badges it gets, and does **not** call `gate.onProbe` (so
`CLOSE_K`-zeros cannot close a forced window on a non-hero screen — the regression B4 flagged). On
the `forceOpen()` **true→false falling edge** → `closeWindow()` + `gate.forceClose()`. `tierForceOpen`
stays reset on teardown (`:538`).

## 5. Testing

- **`VisualProbeGateTest` (JUnit, no device) — B5 core:**
  - opens on `onProbe(2)`; does **not** open on `onProbe(1)`;
  - while open: `1,2,3` consecutive zeros → `Exit` exactly on the `CLOSE_K`-th;
  - a non-zero between zeros **resets** the counter (no premature close);
  - single-fire (`Enter`/`Exit` once; subsequent same-state probes → `None`);
  - **no double-Exit (Codex N1):** after a natural `Exit`, a further `onProbe(0)` → `None`;
  - `forceClose()` resets so it can re-open; reopen after close works.
- **`HeroTierCoordinator` (Robolectric, extend existing tests):** drive the per-round badge **count**
  via a **scripted `FakeOcr` (`List<List<OcrLine>>`, one entry per `recognize` call) over a ≥2-hero
  `TierTable` fed through the real `HeroMatcher`** — `matcher` is a concrete `HeroMatcher` (not an
  interface), so this avoids an interface-extraction refactor and is deterministic: `HeroMatcher.match`
  resolves N distinct exact hero-name lines to N badges and `verticalMerge` cannot inflate the count
  (it skips already-resolved lines). *(This intentionally supersedes the earlier Codex-N3 `FakeMatcher`
  idea, which assumed `matcher` was injectable.)* Assert: `PROBE_MS` cadence while closed → open on the
  ≥2 round; `CAPTURE_INTERVAL_MS` for the first `MAX_ATTEMPTS` open rounds then drop to `PROBE_MS`;
  render only when open; `CLOSE_K`-zeros closes a **held** window (post-`MAX_ATTEMPTS`); foreground-lost
  closes a held window; `MAX_WINDOW_MS` closes a window held open by a **steady 1-match** stream (≥1
  but `<OPEN_MATCHES` ⇒ gate returns `None` forever, so only the timeout closes it); `forceOpen`
  opens+bypasses-gate (no `CLOSE_K` close while forced) and closes on the falling edge;
  `!ocr.isAvailable()` → zero captures. Update the `coordinator()` helper + all existing tests for the
  new constructor (drop `connectionsJson`/`trigger`).
- **Robolectric timing (Codex N4):** use a small `probeMs` (like the existing `pollMs=100`) and
  expect multiple `idleFor`/`idle` drain cycles to deterministically interleave probe→open→capture→
  render across the two intervals; extend the `drain()` helper if needed.
- **No new device dependency.** Real-frame OCR is already covered by `OcrProbeReceiver`; the
  emu-smoke harness is unaffected.

## 6. Risks & open decisions

- **Periodic capture while foreground:** the probe screen-captures every 2 s while HS is foreground
  and the feature is enabled — gated behind the explicit per-session enable + consent (spec §8.2);
  document to the user. Closes on foreground-lost ⇒ no background capture.
- **False open** on a non-select screen that OCRs ≥2 hero-name-like strings: mitigated by
  `OPEN_MATCHES=2` over the **tier-table membership** (only real hero names match). Tunable; revisit
  if observed.
- **Battery:** ~300 ms OCR per 2 s ⇒ light; acceptable.
- **Decisions (resolved in Rev 1):** (a) `SelectPhaseTrigger` file **kept, unwired**; (b)
  `CombatFingerprint` forced-close **deferred**; (c) `PROBE_MS` **fixed 2000**; (d) `connectionsJson`
  **dropped** from the coordinator constructor; (e) OCR availability via **`HeroOcr.isAvailable()`**.

---
*Next: Codex review of this spec → plan (`docs/superpowers/plans/2026-06-07-bg-visual-probe-trigger.md`) → TDD implement (gate first, red→green).*
