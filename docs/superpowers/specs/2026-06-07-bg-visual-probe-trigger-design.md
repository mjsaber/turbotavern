# BG Hero-Select Visual-Probe Trigger (§8.2) — Design

**Status:** READY-TO-PLAN (pending Codex review)
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

### 4.2 `HeroTierCoordinator` integration

The coordinator changes its **trigger source** from `connectionsJson` to a visual probe. Today it
has two cadences (slow `pollTick` for triggering; fast `captureTick` for the §9 render loop). B5
unifies them into one capture→OCR→match step at two cadences:

- **Closed** → probe at `PROBE_MS` (2000 ms): `capture → ocr → match`, **do not render**, feed
  `gate.onProbe(badges.size)`. On `Enter` → `openWindow()`.
- **Open** → existing §9 loop at `CAPTURE_INTERVAL_MS` (700 ms), `MAX_ATTEMPTS`: `capture → ocr →
  match → render`, **also** feed `gate.onProbe(badges.size)` so `CLOSE_K` consecutive 0-match
  rounds close the window (heroes locked in / left the screen). This is the spec's "post-open
  capture loop supersedes probing until close".
- **Orthogonal closes (unchanged):** foreground-lost guard, `windowTimeout` (`MAX_WINDOW_MS`),
  `onProjectionStopped()` → each calls `closeWindow()` **and** `gate.forceClose()`.

Refactor: extract the capture→OCR→match body so probe (no render) and §9 (render) share it and both
return the match count. **Strict foreground gate is unchanged** — no capture (probe or §9) unless
`foreground() == Foreground.TRUE`.

**OCR/model unavailable** → the feature is inert: log once, never probe (spec §8.2). Detect via a
constructor flag or a guarded first `recognize`; do not spin a 2 s capture loop that always fails.

### 4.3 Trigger abstraction & optional forced-close

- Replace the `trigger: SelectPhaseTrigger` constructor param with `gate: VisualProbeGate` +
  `probeMs: Long = 2000`. `connectionsJson` is **no longer needed for triggering**.
- **Optional (deferred):** keep `connectionsJson` + `CombatFingerprint.present(json)` as a
  forced-close (combat started ⇒ select is over). Not required for B5 — the `CLOSE_K`-zeros close
  already covers heroes-gone. Flagged here so the plan can decide; default = omit.

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
with the gate, and pass a **debug force-open** hook so manual `ACTION_TIER_FORCE_OPEN/CLOSE`
(`:118–119`) still works for on-device testing:
```kotlin
gate = VisualProbeGate(),
forceOpen = { BuildConfig.DEBUG && tierForceOpen },   // honored as an unconditional open in the loop
```
When `forceOpen()` is true the coordinator opens (and renders) regardless of probe count — preserves
the current manual debug workflow. `tierForceOpen` stays reset on teardown (`:538`).

## 5. Testing

- **`VisualProbeGateTest` (JUnit, no device) — B5 core:**
  - opens on `onProbe(2)`; does **not** open on `onProbe(1)`;
  - while open: `1,2,3` consecutive zeros → `Exit` exactly on the `CLOSE_K`-th;
  - a non-zero between zeros **resets** the counter (no premature close);
  - single-fire (`Enter`/`Exit` once; subsequent same-state probes → `None`);
  - `forceClose()` resets so it can re-open; reopen after close works.
- **`HeroTierCoordinator` (Robolectric, extend existing tests):** scripted fake `grabber/ocr/matcher`
  returning a match-count sequence — assert probe cadence while closed, switch to capture cadence on
  open, render only when open, `CLOSE_K`-zeros closes, foreground-lost closes mid-window,
  OCR-unavailable stays inert (no captures).
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
- **Decisions for the plan:** (a) keep §8.1 `SelectPhaseTrigger` file unwired vs delete — recommend
  keep; (b) add `CombatFingerprint` forced-close now vs defer — recommend defer; (c) `PROBE_MS`
  fixed 2000 vs adaptive — recommend fixed.

---
*Next: Codex review of this spec → plan (`docs/superpowers/plans/2026-06-07-bg-visual-probe-trigger.md`) → TDD implement (gate first, red→green).*
