# Plan — BG Hero-Select Visual-Probe Trigger (§8.2 / "B5")

**Spec:** [`../specs/2026-06-07-bg-visual-probe-trigger-design.md`](../specs/2026-06-07-bg-visual-probe-trigger-design.md) (Rev 1, Codex-reviewed)
**Status:** READY-TO-IMPLEMENT (Codex plan review incorporated — B1–B3 resolved; N1/N2/N3/N6 folded in)
**Module:** `android/overlay-app` — run gradle from there.

Strict TDD: pure gate first (red→green), then the OCR seam, then the coordinator refactor (with its
tests rewritten), then wiring. **Each stage as a whole** compiles + is green before commit — note
Stage 3 is a single red→green unit: its Step 1 (rewrite tests to the new ctor) intentionally leaves
the test source set **non-compiling** until Step 2 lands the new ctor/`tick` (a ctor mismatch is a
compile error, not an assertion failure), so "red→green" is observed across Steps 1–3, not per step.

---

## Stage 1 — `VisualProbeGate` (pure, JVM, TDD)

**Goal:** the open/close decision core (spec §4.1). No Android imports.

**Files:**
- Create `app/src/main/java/com/bobassist/phase0/herotier/VisualProbeGate.kt`
- Create `app/src/test/java/com/bobassist/phase0/herotier/VisualProbeGateTest.kt`

- [ ] **Step 1 (red):** write `VisualProbeGateTest` first — cases from spec §5:
  opens on `onProbe(2)`; no open on `onProbe(1)`; while open `0,0,0` → `Exit` exactly on the 3rd;
  non-zero between zeros resets the counter; single-fire (`Enter`/`Exit` once, then `None`);
  **no double-Exit** after a natural `Exit` (`onProbe(0)` → `None`); `forceClose()` resets → re-open
  works. Reuse `Transition` (enum at `herotier/Transition.kt`).
- [ ] **Step 2 (green):** implement `VisualProbeGate(openMatches=2, closeK=3)` exactly as spec §4.1
  (`open`/`consecutiveZero` fields; `onProbe(matchCount): Transition`; `forceClose()`).
- [ ] **Step 3:** `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.herotier.VisualProbeGate*"` green.
- [ ] **Commit:** `feat(herotier): VisualProbeGate — pure §8.2 open/close decision (TDD)`

**Success:** gate green; pure (no Android types).

---

## Stage 2 — `HeroOcr.isAvailable()` seam

**Goal:** let the coordinator stay inert when OCR/model can't init (spec §4.3, §8.2), without a
capture-and-throw loop.

**Files:**
- Modify `app/src/main/java/com/bobassist/phase0/herotier/HeroOcr.kt` (add `fun isAvailable(): Boolean = true`)
- Modify `app/src/main/java/com/bobassist/phase0/herotier/MlKitHeroOcr.kt`

- [ ] **Step 1:** add the default-true interface method. In `MlKitHeroOcr`, make recognizer creation
  **lazy-safe so unavailability is observable (Codex N6):** the current ctor builds recognizers
  eagerly (`MlKitHeroOcr.kt:21-24`), so a throwing `getClient` would throw at construction before
  `isAvailable()` could ever return false. Change the default to
  `recognizers: List<TextRecognizer>? = runCatching { listOf(getClient(latin), getClient(chinese)) }.getOrNull()`,
  store the nullable, `override fun isAvailable() = recognizers != null`, and have `recognize` return
  `emptyList()` when `recognizers == null`. Cached (no per-call cost).
- [ ] **Step 2:** `./gradlew :app:compileDebugKotlin` + existing herotier OCR tests green.
- [ ] **Commit:** `feat(herotier): HeroOcr.isAvailable() seam for inert-when-unavailable`

**Success:** compiles; existing OCR/probe tests unaffected (default true).

---

## Stage 3 — `HeroTierCoordinator` → single always-on tick + gate (the core refactor)

**Goal:** replace the two-loop trigger with the one always-on tick of spec §4.2; drop
`connectionsJson`/`trigger`; add `gate`, `forceOpen`, `probeMs`.

**Files:**
- Modify `app/src/main/java/com/bobassist/phase0/herotier/HeroTierCoordinator.kt`
- Modify `app/src/test/java/com/bobassist/phase0/herotier/HeroTierCoordinatorTest.kt`

- [ ] **Step 1 — rewrite tests (leaves test source non-compiling until Step 2; see header).** Drive
  the window by **match count**, not an external `openSignal`:
  - **count seam (Codex B1):** table with **≥2 heroes**; extend `FakeOcr` to a scripted
    `List<List<OcrLine>>` (one entry per `recognize` call) over the real `HeroMatcher`. This is
    deterministic (`HeroMatcher.match` → N distinct exact lines = N badges; `verticalMerge` skips
    resolved lines) and **supersedes the earlier `FakeMatcher` idea** — `matcher` is a concrete
    `HeroMatcher`, not an interface, so a fake would force an interface-extraction refactor we avoid.
  - new `coordinator()` signature: `gate = VisualProbeGate()`, `forceOpen = { forceOpenFlag }`,
    `probeMs = 100`, `captureIntervalMs = 50`, `maxAttempts = 3`, `maxWindowMs = 10_000`
    (drop `connectionsJson`/`trigger`).
  - **old→new test mapping (Codex N2 — semantics that change):**
    - `enterForegroundTrueShowsBadge` → open requires a **≥2-match** round (was `openSignal=true`).
    - `retriesOnEmptyThenShows` → still valid: scripted `[0],[0],[2,…]` opens on the 3rd; assert
      closed-state probing captured before open.
    - `exitClearsAndStopsCapturing` → "exit" is now **`CLOSE_K` zeros after open**, not `openSignal=false`.
    - `windowTimeoutCloses` (Codex B3) → feed a **steady 1-match** stream (≥1 but `<OPEN_MATCHES`):
      the gate returns `None` forever so the window is held open and **only `MAX_WINDOW_MS` closes it**
      (the old `emptyList()` feed would now close via `CLOSE_K` and silently lose timeout coverage).
    - `captureLoopBoundedByMaxAttempts` → reframed: capture **does not stop** post-`maxAttempts`; it
      **drops to `probeMs`**. Assert cadence change, not a hard stop.
    - port verbatim (intent unchanged): `foregroundUnknown/False NeverCaptures` (gate precedes capture),
      `foregroundLostMidWindowCloses`, `staleRotationFrameDropped`, `rotationChangeBeforeRenderDropsBadges`,
      `projectionStopCloses`, `stopRemovesCallbacks`.
  - **new cases:** `CLOSE_K`-zeros closes a **held** window (post-`maxAttempts` scripted zeros);
    foreground-lost closes a held window (post-`maxAttempts`); `forceOpen` opens + **bypasses gate**
    (steady zeros do NOT close) and **closes on the falling edge**; `!ocr.isAvailable()` → zero captures.
  - **drain recipe (Codex N3):** do **not** use one big `drain(N)`; per round do
    `shadowOf(ht.looper).idleFor(interval, MS)` then `shadowOf(mainLooper).idle()`, and assert
    `captureCount`/`renderCount` deltas per regime (reuse the split-idle pattern already in
    `rotationChangeBeforeRenderDropsBadges`). This deterministically interleaves probe→open→
    capture→render across the `captureIntervalMs`→`probeMs` regime change.
- [ ] **Step 2 — implement (green).** Single `tick` runnable per spec §4.2:
  1. `if (foreground() != TRUE) { closeWindow(); gate.forceClose(); repost(probeMs); return }`
  2. `if (!ocr.isAvailable()) { if (!loggedInert) breadcrumb(...); return }` (no repost → inert)
  3. `frame = grabber.capture() ?: repost; return`; `count = match(ocr.recognize(frame)).size`
  4. force-open precedence (Codex N1 — name the edge state `wasForced: Boolean`): at tick top
     `val fo = forceOpen()`; then
     `if (wasForced && !fo) { wasForced=false; closeWindow(); gate.forceClose() }`  // falling edge wins this tick, skip gate
     `else if (fo) { wasForced=true; if (!open) openWindow() }`                      // forced: bypass gate, do NOT call onProbe
     `else when (gate.onProbe(count)) { Enter→openWindow(); Exit→closeWindow(); None→{} }`
  5. render if `open && count>0` (mainHandler, existing rotation/started/open re-checks)
  6. repost interval = `!open → probeMs`; `open && attempts<maxAttempts → captureIntervalMs (attempts++)`;
     `open → probeMs`
  - `openWindow()`: `open=true; attempts=0; arm windowTimeout(maxWindowMs)`.
  - `closeWindow()`: `open=false; cancel windowTimeout; mainHandler.clear()`.
  - `onProjectionStopped()`/`stop()`: `closeWindow()+gate.forceClose()`; `stop()` keeps
    `removeCallbacksAndMessages(null)`.
- [ ] **Step 3:** `./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.herotier.*"` green.
- [ ] **Commit:** `feat(herotier): single always-on visual-probe tick in HeroTierCoordinator`

**Success:** all herotier unit tests green; the held-window `CLOSE_K` + forceOpen-bypass cases pass.

---

## Stage 4 — Wire into `BobVpnService`

**Goal:** make the visual probe the production trigger (spec §4.5).

**Files:**
- Modify `app/src/main/java/com/bobassist/phase0/BobVpnService.kt` (`enableTier`, `:459–465`)

- [ ] **Step 1:** replace `trigger = SelectPhaseTrigger(isOpen = { tierForceOpen })` (+ the
  `connectionsJson = …` arg to the coordinator) with `gate = VisualProbeGate()` +
  `forceOpen = { BuildConfig.DEBUG && tierForceOpen }`. Remove now-unused imports
  (`SelectPhaseTrigger` import only if nothing else uses it — the file stays in the tree). Keep
  `tierForceOpen` + `ACTION_TIER_FORCE_OPEN/CLOSE` + teardown reset (`:538`).
- [ ] **Step 2:** `./gradlew :app:compileDebugKotlin` + **full** `:app:testDebugUnitTest` green.
- [ ] **Commit:** `feat(herotier): wire VisualProbeGate as the production select trigger (§8.2)`

**Success:** whole debug unit suite green; `tierForceOpen` debug path still opens the overlay.

---

## Stage 5 — Verification (no new device dependency)

- [ ] `./gradlew :app:testDebugUnitTest` (whole suite) green.
- [ ] Optional manual on-device later: enable tier + `ACTION_TIER_FORCE_OPEN` still renders; and a
  real select screen opens the overlay (real OCR already validated via `OcrProbeReceiver`).
- [ ] `emu-smoke.sh` unaffected (devrec path); no change needed.

## Self-review notes
- Spec coverage: §4.1→Stage1; §4.3 OCR seam→Stage2 + ctor change→Stage3; §4.2 tick→Stage3;
  §4.5 wiring→Stage4; §5 tests→Stages1&3.
- Surgical: kill path / matcher / renderer / BadgeLayout / OCR engine untouched; `SelectPhaseTrigger`
  file retained, unwired; coordinator ctor loses `connectionsJson`/`trigger`, gains `gate`/`forceOpen`/`probeMs`.
- Type consistency: `VisualProbeGate.onProbe/forceClose`, `HeroOcr.isAvailable`,
  `HeroTierCoordinator(gate, forceOpen, probeMs, …)` referenced identically across stages.
