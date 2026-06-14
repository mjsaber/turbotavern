# Proposal: Rating reliability — Usage-Access gate + hero-badge flicker

**Date:** 2026-06-13
**Status:** REVIEWED (codex adversarial design review 2026-06-13: 6 SHOULD-FIX folded in below; core design validated — LinkedHashMap re-put order, miss semantics, handler→main snapshot safety, and KillFeature-seam variant discriminator all confirmed correct). Ready to implement.
**Scope:** Two P0 issues found in a live on-device session (OnePlus CPH2451, full SKU, debug build). Both
are about the *rating overlay* (hero-tier + trinket-tier badges); 拔线 is unaffected.

The other findings from the same session (debug build shipped, unbounded breadcrumb log, OCR-on-every-screen
battery cost, diagnostic blind spot) are **out of scope here** and tracked separately.

---

## Bug 1 — Usage Access silently required, but presented as optional

### Evidence
- `appops get com.turbotavern.full GET_USAGE_STATS` → `Default mode: default` (not `MODE_ALLOWED`).
- `ForegroundQuery.hasUsageAccessPermission()` returns false → `queryForegroundPackage()` returns `null`
  → `StrictForeground.of(null, …)` = `UNKNOWN`.
- `SelectCoordinator.step()` (line ~93): `if (foreground() != Foreground.TRUE) { closeAll(); return }` —
  on `UNKNOWN` with no open window it **logs nothing and returns**. The capture→OCR→match→render cycle never
  runs. Zero breadcrumbs; the overlay looks "on" but produces no rating.

### Why it reaches users
- The full SKU is a **separate package** (`com.turbotavern.full`); Usage Access is per-package, so it does
  **not** carry over from a previously-installed clean build (or any prior install).
- `MainActivity` labels Usage Access **"recommended"** (`status_recommended`) and the copy
  (`perm_usage_why`) says *"Recommended — hides the overlay when Hearthstone is not in front."* — which
  understates it: without the permission the overlay produces **no rating at all**.
- `startBtn.isEnabled = canOverlay` — Start is gated **only** on the overlay (draw-over-apps) permission.
  A user can grant overlay, skip Usage Access, tap Start, and get a running service that silently never rates.

### Design

**Principle:** never let the rating overlay start in a state where it can only fail silently. But 拔线 (full
SKU) genuinely works without Usage Access, so do not hard-lock the whole app behind it.

**Chosen approach — honest copy + a Start-time decision dialog (soft gate):**

1. **Truthful onboarding copy.** Change `perm_usage_why` in all three locales to state that Usage Access is
   **required for ratings** (the overlay needs it to know Hearthstone is in front; without it no badges show).
   Keep wording that 拔线 still works without it (full SKU only). Show the row status as **Required**
   (`status_required`) when missing, not `status_recommended`.
   - The hero/trinket overlay is the **entire** clean SKU, so for clean it is unconditionally required.
   - For full it is required *for ratings* but not for 拔线.

2. **Start-time guard (no-recursion split — codex #5).** Split the current `onStartClicked()` body into
   `continueStartAfterUsageDecision()` (the existing overlay-check → `kill.isRunning()` shortcut → VPN
   consent/start → projection flow, unchanged). `onStartClicked()` becomes: overlay gate first (as today),
   then **usage gate BEFORE the `kill.isRunning()` shortcut** — so a full user with the VPN already running
   cannot slip straight into projection + silent rating failure. If `!hasUsageAccessPermission()`, show the
   dialog instead of calling `continueStartAfterUsageDecision()`. The "Start anyway" button calls
   `continueStartAfterUsageDecision()` **directly** (never `onStartClicked()` again — that would re-show the
   dialog).
   - Dialog buttons:
     - **[Open settings]** → `ACTION_USAGE_ACCESS_SETTINGS`. The "grant → ratings in ~2 s, no restart" claim
       only holds **if the overlay service is already running** (coordinator re-checks permission each tick).
       At the *pre-start* dialog nothing is running yet (codex #3), so set a one-shot `pendingStart` flag and,
       in `onResume()`, if Usage Access is now granted and `pendingStart`, auto-continue
       `continueStartAfterUsageDecision()` (clear the flag). If the user returns without granting, the flag is
       cleared and they simply tap Start again.
     - **[Start anyway]** (full only) → `continueStartAfterUsageDecision()`.
   - **Variant-aware** (codex #4): clean shows **[Open settings]** + **[Cancel]** (no "Start anyway" —
     nothing works without the permission); full shows **[Open settings]** + **[Start anyway]**. Selected via
     the existing kill seam: add `providesKillFeature(): Boolean` (clean=false, full=true) — NOT
     `BuildConfig.FLAVOR` (codex confirmed the seam is the right discriminator).

**Rejected alternative — hard block** (treat Usage Access exactly like overlay perm, disable Start): simplest,
but locks out full-SKU users who only want 拔线. Rejected for full; *effectively* what clean gets via the
variant-aware dialog.

**Optional follow-up (NOT in this change):** a persistent "ratings off — grant Usage Access" hint in the
foreground notification while the overlay runs without the permission. Deferred to keep this surgical.

### Files
- `app/src/main/java/com/turbotavern/MainActivity.kt` — usage-row status (Required when missing),
  `onStartClicked()` split into a usage-gate + `continueStartAfterUsageDecision()`, the dialog, the
  `pendingStart` flag + `onResume()` continuation, variant-aware buttons + body.
- `app/src/main/res/values{,-zh-rCN,-zh-rTW}/strings.xml` — rewrite `perm_usage_why` to "required for ratings";
  add dialog strings `usage_required_title`, `action_open_settings`, `action_start_anyway`, and **two** body
  strings selected at runtime (codex #4) — `usage_required_body_full` ("…拔线 still works without it…") and
  `usage_required_body_overlay` (clean: no 拔线 claim). Runtime selection via `providesKillFeature()` avoids
  duplicating 3 locales × 2 flavors as resource overrides.
- Kill seam (`KillFeature` interface + clean/full `KillFeatureHolder`) — add `providesKillFeature(): Boolean`
  (clean=false, full=true) so both the buttons and the body are variant-aware without flavor reflection.
- **Debug `auto_start` scripts (codex #7, NIT):** `--ez auto_start true` currently assumes Start proceeds
  after overlay permission; with the new gate it would hit the dialog. The `EXTRA_AUTO_START` path already
  guards on `BuildConfig.DEBUG`; for the e2e/smoke scripts, grant `GET_USAGE_STATS` via `appops` before
  launch (when the device permits it; the OnePlus rig blocks shell appops, so on-device it stays manual).

### Tests / verification
- **Robolectric** (`MainActivityTest`; `ShadowAppOpsManager` as in `ForegroundQueryTest`). Cases (codex #6):
  - Usage Access denied → `onStartClicked()` does **not** start the overlay service, surfaces the dialog;
    granted → proceeds.
  - **clean** (`providesKillFeature()==false`): denied → dialog has Open-settings + Cancel (no Start-anyway);
    Cancel starts nothing; Open-settings starts nothing but arms `pendingStart`.
  - **full** (`providesKillFeature()==true`): denied → Start-anyway proceeds into the projection flow.
  - **full with `kill.isRunning()==true` and Usage Access missing** → still gated by the dialog (the
    no-recursion regression: must not shortcut into projection).
  - If MainActivity proves awkward under Robolectric, extract the pure gate decision
    (`needsUsagePrompt(hasUsage, providesKill, killRunning)`) and unit-test that; keep the Activity wiring thin.
- **Live gate (the real one):** on the phone — revoke Usage Access for `com.turbotavern.full`, relaunch,
  confirm (a) the row reads Required, (b) tapping Start shows the dialog instead of silently starting,
  (c) granting + returning to Hearthstone produces ratings within ~2 s. "Fixed" is claimed only after this.

---

## Bug 2 — Hero badges flicker (4↔3) from per-frame OCR jitter

### Evidence
- Live hero-select (16:10): hero match count per frame = `4,4,4,3,3,4,3,4`. Window stayed `HERO` throughout
  (the **window** was stable; only the matched **set** flickered).
- Screenshots: Deathwing's "A" badge is present at 16:10:21, **gone** at 16:10:26 — it is **not** a single
  fixed hero failing; a *different* hero drops on different frames (phone-side OCR jitter: green hover-glow,
  armor-number overlap, small name font).
- `TierOverlay.show()` calls `clear()` then re-adds a window per badge **every frame**. `SelectCoordinator`
  renders only the **current** frame's set, so any single-frame OCR miss removes that badge for that frame —
  visible blink.

This is distinct from the earlier zhTW-separator fix (a data/normalization miss). This is frame-to-frame
recognition stability.

### Design

**A small, pure, unit-tested temporal stabilizer between match and render** — render the *recently-seen*
union instead of the bare current frame, so a transient miss does not drop a badge.

```kotlin
/** Holds an item across up to [maxMisses] consecutive absent frames so transient OCR misses don't
 *  blink the badge. Pure + deterministic; keyed by [keyOf]. Insertion order is preserved. */
class BadgeStabilizer<T>(private val keyOf: (T) -> String, private val maxMisses: Int = 2) {
    private data class Entry<T>(val item: T, var misses: Int)
    private val held = LinkedHashMap<String, Entry<T>>()
    /** Feed this frame's matches; returns the set to render (seen ∪ recently-held). */
    fun update(current: List<T>): List<T> {
        val seen = HashSet<String>(current.size)
        for (item in current) { held[keyOf(item)] = Entry(item, 0); seen.add(keyOf(item)) } // refresh box, reset misses
        val it = held.values.iterator()                                                      // (LinkedHashMap put on existing key keeps order)
        val keysIt = held.keys.iterator()
        // age out the misses; drop past tolerance
        val drop = ArrayList<String>()
        for ((k, e) in held) if (k !in seen) { e.misses++; if (e.misses > maxMisses) drop.add(k) }
        drop.forEach { held.remove(it) }
        return held.values.map { it.item }
    }
    fun reset() { held.clear() }
}
```
*(Final code will iterate cleanly; sketch above shows intent. A re-seen item resets its counter to 0 and
updates to the fresh box; a held-but-missed item keeps its last-seen box.)*

**Wiring (`SelectCoordinator`):**
- Hold one `BadgeStabilizer<HeroBadge>(keyOf = { it.cardId })`.
- The **gate is unchanged** — `arbiter.onProbe(heroBadges.size, trinketRecs.size)` still sees the **raw**
  count. The window's open/close hysteresis is the arbiter's job and was already stable in the capture; we
  only stabilize what gets **rendered**, which is the minimal, surgical fix.
- **Update the stabilizer ONLY after the stale-rotation guard passes** (codex #1). Matching happens at line
  ~136 but the `currentRotation() != rotationDeg → drop` guard is at line ~141. If we updated the stabilizer
  before that guard, a dropped rotated frame would still reset misses / replace held boxes, and a later valid
  frame could then render those stale boxes against the current transform. So: only when `active == HERO`
  **and** the rotation guard has passed, compute `val stabilized = heroStabilizer.update(heroBadges)` on the
  coordinator handler thread and post that immutable snapshot to main (render `stabilized`, not raw).
- **Reset coverage (codex #2)** — reset the hero stabilizer on every path that ends a hero window:
  - `onTransition(prev == HERO, now != HERO)` — normal HERO→NONE / HERO→TRINKET (the `now==NONE` early return
    runs *after* `onTransition`, so it's covered).
  - `closeAll()` — **unconditionally** (foreground-loss, window-timeout, `onProjectionStopped()`).
  - `stop()` — bypasses both `onTransition` and `closeAll`, so reset there too; also reset `wasForced` in
    `stop()` to match the old `HeroTierCoordinator` (prevents a stale forced state surviving `stop(); start()`).
  - This guarantees no hero state leaks across two separate hero-selects in one app session.

**Held-box validity:** a held badge renders its last-seen box with the *current* frame's transform. During
hero-select the display does not rotate, and the existing stale-rotation guard (`currentRotation() !=
rotationDeg → drop frame`) already prevents rendering across a rotation change, so a held box stays valid for
the few frames it lives. Documented as an explicit assumption.

**Scope decision — heroes only, not trinkets.** Trinkets were rock-solid 4/4 every frame in the capture, and
the trinket shop *can* change its offer set (refresh/next-tier), where a union would briefly show stale + new
trinkets overlapping in the same slots. Applying the stabilizer only to heroes fixes the observed bug without
risking a trinket regression. Trinket stabilization is a deliberate non-goal; revisit only if trinket flicker
is ever observed (and then with reroll-aware handling). This follows "surgical changes / no speculative work."

**maxMisses default = 2.** Open-window cadence is 700 ms (first 8 frames) then 2000 ms. maxMisses=2 holds a
badge ~1.4 s (snappy phase) to ~4 s (hold phase) of misses — long enough to bridge jitter, short enough that
a genuinely-gone hero (post-pick) clears promptly. Injectable for tests/tuning.

### Files
- `app/src/main/java/com/turbotavern/trinket/BadgeStabilizer.kt` (new; generic, lives with the coordinator).
- `app/src/main/java/com/turbotavern/trinket/SelectCoordinator.kt` — hold a hero stabilizer, render its
  output for HERO frames, reset on leave-HERO/close. ~10 lines, no gate changes.

### Tests / verification
- **`BadgeStabilizerTest` (pure JUnit, deterministic):**
  1. item seen every frame → always present.
  2. item missed 1 then 2 frames (maxMisses=2) → still present (no flicker).
  3. item missed 3 consecutive frames → dropped.
  4. miss then re-seen resets the counter (proves *consecutive*, not cumulative).
  5. new item appears immediately.
  6. held-but-missed item retains its **last-seen** value; re-seen item updates to the **new** value (box).
  7. insertion order stable across holds.
  8. `reset()` clears everything.
  9. full-set swap (reroll-like): old ages out within maxMisses+1 frames, new present immediately
     (documents why trinkets are excluded).
- **`SelectCoordinatorTest` — flicker regression + lifecycle (codex #6):**
  - flicker: feed `[4 heroes] → [3 heroes, one missing] → [4 heroes]`; assert the hero renderer receives
    **4** badges on the middle frame too.
  - **stale-rotation frame does NOT update the stabilizer** (a dropped rotated frame must not reset misses
    or replace held boxes).
  - `stop(); start()` on the same coordinator does **not** resurrect old badges.
  - projection/foreground close (`onProjectionStopped()` / foreground≠TRUE) resets hero state.
  - forced `HERO → TRINKET` and the force-open falling edge reset the stabilizer.
  - close/reopen a hero window does not leak heroes from the previous select.
- **Live gate:** play a real game on the phone; confirm hero badges hold steady across the select window
  (no blink) and still clear after pick. The unit test is the deterministic gate; the live run is the
  closing gate for this live-found bug.

---

## Out of scope (tracked, not in this change)
- Ship a **release** build (R8, quiet logging) for production; the installed APK is `DEBUGGABLE`.
- **`bob-breadcrumbs.log`** unbounded growth + OCR text in debug → add rotation/size cap; gate OCR-text dump.
- **OCR runs on every screen every 2 s** (rec up to ~2.6 s on busy combat) → battery; needs a cheaper
  pre-gate before full OCR.
- **Diagnostic blind spot:** `ocr-text` logs only on total-miss frames; also dump when matches < expected.

## Order of work
1. Bug 2 (`BadgeStabilizer` + wiring + tests) — self-contained, deterministic, highest visible quality win.
2. Bug 1 (copy + Start-time dialog + seam + test) — stops the silent-failure every install hits.
Each lands as its own commit; each gets a codex review of the implementation per the process rule.
