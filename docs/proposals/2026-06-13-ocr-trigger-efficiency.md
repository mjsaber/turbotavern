# Proposal: OCR trigger efficiency — metric, A/B, and pre-gate

**Date:** 2026-06-13
**Status:** REVIEWED (codex round 1: 5 BLOCKER / 8 SHOULD-FIX / 1 NIT — see "Codex review (round 1)" at the
bottom; those resolutions SUPERSEDE the body where they conflict). **2 strategic decisions pending user input
(★) before implementation.** Originally drafted by a design workflow (1 of 3 design agents survived; synthesis
made 3 repo-specific corrections), hand-extended.
**Scope:** Reduce the battery/thermal cost of the rating overlay's OCR pipeline WITHOUT regressing rating
correctness. The metric + A/B harness is the centerpiece and acceptance gate (per user requirement).

## Problem

`SelectCoordinator.step()` runs a full OCR pass on **every tick, on every screen** — combat, lobby,
matchmaking included — at `probeMs = 2000` ms closed and `captureIntervalMs = 700` ms for the first
`maxAttempts = 8` open frames. Each pass is the dominant cost: `PaddleHeroOcr.recognize()` logs
`timing det=Xms rec=Yms`, **rec dominating** — ~0.6–1.3 s on select screens (4–8 boxes), up to ~2.6 s on busy
combat frames (19 boxes). Combat is the worst case for cost and is provably **never** a select screen, yet it
pays full det+rec every 2 s. This is the overlay's main battery/thermal sink.

The fix is a **cheap pre-gate** in front of det+rec. The user's hard requirement: **measure the saving and
A/B-prove it**, and the optimization must **never miss a hero-select or trinket-shop screen** (a skipped select
= a wrong miss, strictly worse than wasted power). So the metric + A/B harness is built **first**, before any
optimization.

### The crux the metric must resolve (added by hand)

Frame-change skip (stage a below) only skips frames that are (near-)**identical** to the prior processed one.
That kills *static*-screen waste (matchmaking, waiting-for-opponent, combat-result, AFK). But **combat is
animated** (minions move every frame) and **active recruit** changes as the player buys/hovers/rerolls — those
frames differ, so stage (a) alone will **not** skip them. Combat is a large chunk of the waste. Therefore:

- **full SKU** can skip combat for free via the combat-socket signal (stage c).
- **clean SKU** has no network signal, so skipping animated combat needs the visual classifier (stage b), or
  the combat savings are simply not captured.

We do **not** guess whether stage (a) alone hits the target — the metric measures it per stage, and the data
decides whether (b)/(c) are needed. That uncertainty is precisely why the user demanded a metric + A/B.

## Metric (definition / unit / tooling / attribution)

Two tiers. The deterministic tier is the **acceptance gate**; the device tier is **confirmation only**.

### Tier 1 — Deterministic per-match compute (PRIMARY, the gate)

Replay a fixed recorded frame sequence through `SelectCoordinator` and count:

| Metric | Unit | Source of truth | Role |
|---|---|---|---|
| `ocr_invoke_count` | int | counter in `SelectCoordinator`, incremented immediately before `orientedOcr.recognize(frame)` | **primary cost proxy** |
| `aggregate_det_rec_ms` | float ms | sum of `det+rec` parsed from `PaddleHeroOcr`'s existing `timing det=Xms rec=Yms` lines over the replay | **cost magnitude** |
| `skip_count` | int | counter in `SelectCoordinator`, incremented on a pre-gate SKIP | diagnostic |
| `missed_select_count` | int | replay asserts the overlay opened on every labelled select segment | **correctness — must be 0** |

**Why deterministic replay is the gate, not device power:** real-device mAh is confounded by thermal
throttling, GPU state, screen brightness, and co-resident apps — it cannot give a stable, CI-able number that
attributes cost to *our* change. `ocr_invoke_count` × `aggregate_det_rec_ms` is reproducible to the integer/ms,
isolates the OCR change from game/GPU noise, and runs in JVM/Robolectric with no device.

**Attribution:** `ocr_invoke_count` is incremented *only* at the single call site wrapping
`orientedOcr.recognize()`, so it tracks exactly the work the pre-gate elides. `aggregate_det_rec_ms` reuses the
already-present `PaddleHeroOcr` timing line (no new OCR-engine instrumentation).

### Tier 2 — On-device CPU/battery (CONFIRMATION, not the gate)

Direction check on a real device after Tier 1 passes:

- **Battery delta (rolls up CPU+GPU+radio, mAh):**
  ```
  adb shell dumpsys batterystats --reset
  # scripted match playthrough for fixed wall-clock (~5 min), screen brightness locked
  adb shell dumpsys batterystats --charged com.turbotavern.full | grep -E "Computed drain|Uid .*turbotavern"
  ```
- **Per-process CPU time (jiffies, cheaper to sample):**
  ```
  adb shell cat /proc/$(pidof com.turbotavern.full)/stat   # fields 14 utime + 15 stime, pre/post
  ```
- **OCR-invocation confirmation:** count `ocr` breadcrumbs in `filesDir/bob-breadcrumbs.log` (or logcat
  `PaddleHeroOcr timing` lines) over the live run to confirm the device sees the predicted invocation drop.

Device numbers are supporting evidence with noise caveats; they do **not** block merge alone.

## A/B method (arms / fairness / reproducibility / pass criterion)

**Arms — one source, two behaviors via a single flag (no fork):**
- **Baseline:** `SelectCoordinator` with the pre-gate disabled (`frameSkipGate = null`) — OCR every tick.
- **Optimized:** same coordinator with `frameSkipGate` wired in.

The flag is a constructor param (`frameSkipGate: FrameSkipGate? = null`), so both arms run identical code paths
except the gate. For live device A/B it flips at runtime via an `OverlayService` intent extra
(`ACTION_SET_FRAME_SKIP_ENABLED`) — same install tests both arms, no rebuild.

**Fairness:** identical frame sequence + order, no randomness; the replay grabber feeds the exact same `Frame`
list to both arms; same cadence params; both arms share the same fake OCR-timing model in Tier 1 so
`aggregate_det_rec_ms` differences come only from invocation count, not per-call jitter.

**Reproducibility:** the replay corpus is a checked-in, labelled fixture. The benchmark runs each arm N=5 times
and asserts per-arm numbers are stable (variance 0 for `ocr_invoke_count` — the pipeline is deterministic).

**Pass criterion (numeric):**
1. `ocr_invoke_count(optimized) ≤ 0.5 × ocr_invoke_count(baseline)` — **≥50% fewer OCR invocations** over a
   full match. *Measured per stage (a / a+b / a+c) so the data shows which stage earns the reduction; if stage
   (a) alone falls short for clean, that's a finding, not a failure — it tells us (b) is needed.*
2. `aggregate_det_rec_ms(optimized) < aggregate_det_rec_ms(baseline)`, tracking the invocation drop.
3. `missed_select_count(optimized) == 0` (hard gate).
4. Tier-2 device run shows optimized mAh ≤ baseline mAh (direction confirmation, not a hard threshold).

Failing (1) blocks only if no stage combination reaches it; failing (3) always blocks.

## Correctness guard (missed-select-rate must be 0)

Non-negotiable: a skipped select is worse than wasted power.

1. **Conservatism of frame-change skip:** the gate only SKIPs a frame whose downscaled hash is (near-)identical
   to the prior *processed* frame. Hero names / trinket offers cannot appear/disappear without changing pixels,
   so an identical frame cannot be a new select screen.
2. **The arbiter is untouched and skips are invisible to it.** A SKIP feeds **no** probe to
   `arbiter.onProbe()` — it is not a zero-match probe. `VisualProbeGate` closes only after `closeK = 3`
   *consecutive zero-match probes*; a skipped frame contributes neither open nor close, so a held window is
   never spuriously closed or held open. `openMatches = 2` / `closeK = 3` semantics preserved exactly.
3. **Orthogonal closes unchanged:** `maxWindowMs = 15_000` timeout, foreground-loss, projection-stop all
   bypass the gate.
4. **Deterministic regression test:** replay over the labelled corpus asserts that for **every** labelled
   hero-select and trinket-shop segment the overlay opens (renders ≥1) in the *optimized* arm
   (`missed_select_count == 0`), and that baseline and optimized produce **identical** `SelectWindow`
   transition sequences and render-call sets, differing only in `ocr_invoke_count` / `skip_count`.

## Optimization architecture

A pre-gate inserted in `step()` **after** `grabber.capture()` (capture can't be skipped — `Frame.bitmap` is
the only pixel source; capture ~50–100 ms vs OCR 600–2600 ms, so eliding OCR is the win) and **before**
`orientedOcr.recognize(frame)`:

```kotlin
val frame = grabber.capture() ?: return
if (frameSkipGate?.onFrame(frame) == GateDecision.SKIP) {
    skipCount++; runCatching { frame.bitmap.recycle() }
    return                                   // no recognize(), no match, no arbiter probe
}
ocrInvokeCount++
val lines = runCatching { orientedOcr.recognize(frame) } ...
```

**`FrameSkipGate`** — new pure-logic class in `herotier/` (testable like `VisualProbeGate`), composing:

- **(a) Frame-change skip (SKU-portable baseline):** maintain a downscaled grayscale average-hash (e.g. 16×16)
  of the *last processed* frame; SKIP if the current frame's Hamming distance is below a small threshold.
  Kills static-screen waste. No SKU dependency. Delivers whatever reduction static screens are worth.
- **(b) Cheap visual classifier (SKU-portable, GUARDED add-on):** reject frames *implausible* as a select
  screen via downscaled color/anchor sampling at the hero/trinket panel regions. **Scoped conservatively:**
  it may only skip frames it is highly confident are not select screens, and it **ships only if it hits
  `missed_select_count == 0` on the corpus**; otherwise it stays disabled and we ship (a) [+ (c) for full].
  This is the lever that lets *clean* skip animated combat.
- **(c) Combat short-circuit — RETRACTED as designed; see BLOCKER B1 in the round-1 resolutions.** The
  `{1119, 3724}` socket (`core/BattleConnection.kt`, via `CombatFingerprint`) is a **long-lived BG game
  socket, not a combat-vs-recruit phase detector** — so "force SKIP while the socket is present" would skip
  later recruit/trinket screens too. There is no validated phase signal today; do NOT wire this as a skip.
  Demoted to a diagnostic `battleSocketPresent` behind a new `src/main` `CombatSignalBinding` seam (clean
  returns false; full derives it) — usable only if/when labelled replay proves no select segment ever overlaps
  it. ★ Decision A below.

No change to `SelectWindowArbiter`, `VisualProbeGate`, the hero stabilizer, or the render/transform path.

## Instrumentation

- **`SelectCoordinator`:** `var ocrInvokeCount = 0` / `var skipCount = 0`; increment at the two sites above;
  throttled breadcrumb (every Nth skip) `select: frame-skip total=$skipCount ocr=$ocrInvokeCount`. Counters
  read directly by the benchmark.
- **`PaddleHeroOcr`:** no change — existing `timing det=Xms rec=Yms` is the `aggregate_det_rec_ms` source.
- **`FrameSkipGate`:** optional `debug: Boolean = false` → injected logger of prior/current hash + decision;
  default false = zero prod overhead.
- **Benchmark harness:** Robolectric test driving the replay grabber + reading counters; a regex parser
  (`timing det=(\d+)ms rec=(\d+)ms`) for the device-tier `aggregate_det_rec_ms`.

## Files

New:
- `app/src/main/java/com/turbotavern/herotier/FrameSkipGate.kt` — average-hash, Hamming threshold, optional
  `combatActive` lambda + debug logger.
- `app/src/test/java/com/turbotavern/herotier/FrameSkipGateTest.kt` — plain JUnit: identical bitmaps SKIP;
  differing PROCEED; hash stable; `combatActive=true` forces SKIP; threshold boundary.
- `app/src/test/java/com/turbotavern/trinket/OcrTriggerBenchmarkTest.kt` — Robolectric A/B benchmark: replay
  corpus through both arms; assert criteria (1)+(3) and identical transition sequences.
- a `ReplayGrabber` (`ScreenGrabber` serving a fixed labelled `Frame` list; promote/mirror the existing
  `SGrabber` test fake) so benchmark and live replay share it.
- `app/src/test/resources/recordings/match-sequence/` — checked-in labelled corpus (PNGs + `labels.json`
  marking hero-select / recruit / combat / trinket). **Curated new** — the existing
  `recordings/2026-06-07-bg-combat-signature/` is conn-JSON + 8 PNGs, not a full match. Seed with real frames
  where available (`recordings/select-live-035.png` + the live combat/lobby screenshots captured this session).

Modified:
- `app/src/main/java/com/turbotavern/trinket/SelectCoordinator.kt` — `frameSkipGate` param; SKIP/PROCEED
  branch; counters; throttled breadcrumb.
- `app/src/main/java/com/turbotavern/OverlayService.kt` — instantiate `FrameSkipGate` in `startCoordinator()`;
  handle `ACTION_SET_FRAME_SKIP_ENABLED` for live A/B.
- `app/src/test/java/com/turbotavern/trinket/SelectCoordinatorTest.kt` — frame-skip case (held screen → OCR
  once; combat frames → 0 OCR with combat gate; trinket frame → opens; `missed_select == 0`).
- `src/full` provider — supply `combatActive` from `BobVpnService.liveSession` + `:3724` signature; clean keeps
  default `{ false }`.

## Tests / verification

**Deterministic (CI gate — `./gradlew :app:testCleanDebugUnitTest`):**
1. `FrameSkipGateTest` — gate logic.
2. `SelectCoordinatorTest` new case — gate wired, correctness preserved.
3. `OcrTriggerBenchmarkTest` — prints baseline vs optimized `ocr_invoke_count` / `aggregate_det_rec_ms` /
   `missed_select_count`; **fails the build** unless (1) and (3) hold and transition sequences are identical.
4. All existing `trinket/` + `herotier/` suites stay green.

**Live gate (device confirmation, reported with caveats):** one APK, scripted match per arm via
`ACTION_SET_FRAME_SKIP_ENABLED`, `dumpsys batterystats` mAh + `/proc/<pid>/stat` CPU + breadcrumb `ocr` count;
confirm optimized ≤ baseline.

## Risks

- **Too-aggressive skip closes a fast select.** Mitigated: skip elides only probes on *unchanged* frames; a
  select transition changes pixels and is never skipped; the corpus test catches any miss. No separate cooldown
  knob (dropped as redundant given hash-change skipping).
- **Hash false-positive (distinct screens hash equal).** Mitigated: 16×16 average-hash + small Hamming
  threshold; corpus test surfaces collisions; add a secondary pixel-diff only if observed.
- **Capture not skipped.** By design — capture is cheap vs OCR; documented in the gate KDoc.
- **Full-SKU combat coupling.** If `BobVpnService` is slow/dead to report combat, the visual gate still applies
  to static combat-result screens; clean never depends on it; seam defaults `{ false }`.
- **Rotation mid-select.** The stale-rotation guard drops rotated frames before render; a rotation changes the
  hash → forces a real probe, so the gate inherits rotation safety.
- **Stage (a) alone may underperform for clean** (animated combat not skipped). This is measured, not assumed;
  if the metric shows it, stage (b) is the remedy and is gated on `missed_select_count == 0`.

## Out of scope

- Per-call OCR latency (already optimized: det downscale + batched rec, commits `8572afe` / `05e18b4`).
- Skipping `grabber.capture()` itself (cheap; not the bottleneck).
- New ONNX models, GPU/NNAPI delegation.
- A network select-signature (Spike B: select has no conn signature — `[]` in the combat-signature JSON corpus).

## Order of work

Each step its own commit, codex-reviewed before the next.

1. **Metric harness + baseline.** `ReplayGrabber`, labelled corpus, counters + breadcrumb, and
   `OcrTriggerBenchmarkTest` running the **baseline** arm only; record stable baseline numbers (5 runs).
2. **`FrameSkipGate` (TDD).** `FrameSkipGateTest` red → implement green. Not yet wired.
3. **Wire gate + A/B.** Add param + SKIP branch; extend `SelectCoordinatorTest`; flip benchmark to both arms;
   enforce (1)+(3). Measure stage (a) reduction.
4. **Stage (b) visual classifier — only if the metric shows (a) is short for clean** and only if it holds
   `missed_select_count == 0`.
5. **Full-SKU combat enhancement.** Inject `combatActive` from the `src/full` seam; clean stays default;
   GPL/dedup guards green; combat frames OCR 0× in the full-SKU replay.
6. **Device confirmation (docs/results).** `ACTION_SET_FRAME_SKIP_ENABLED`; on-device `dumpsys batterystats` /
   `/proc/stat` A/B; record mAh + CPU + breadcrumb counts, with noise caveats.

---

*Provenance: drafted by the `ocr-efficiency-design` workflow (deterministic-replay design + synthesis; 2 of 3
design agents failed to emit structured output — the `Explore` agentType does not reliably call the schema
tool, a workflow-authoring note for next time). Hand-reviewed; the animated-combat/clean-SKU crux and the
per-stage measurement framing were added on review.*

---

## Codex review (round 1) — resolutions

5 BLOCKER / 8 SHOULD-FIX / 1 NIT. These SUPERSEDE the body where they conflict. **★ = needs a product decision
before implementation.**

- **B1 — `combatActive` is not a phase signal ★.** `{1119,3724}` (`BattleConnection`/`CombatFingerprint`) is a
  long-lived game socket, not combat-vs-recruit. → Stage (c) "skip combat via socket" is **invalid**. Renamed
  `battleSocketPresent`, diagnostic-only behind a `src/main` `CombatSignalBinding` seam; may gate skips only
  after labelled replay proves no hero/trinket segment overlaps it. **Decision A:** with no phase signal, the
  "full SKU skips combat for free" win is retracted — animated-combat skipping now rests on the visual
  classifier (stage b) for *both* SKUs (harder; must prove 0 missed selects). Accept, or hunt for a real phase
  signal (e.g. a distinct UI/audio/log cue) first?
- **B2 — `ocr_invoke_count` double-counts.** `OrientedOcr` may call the base OCR twice (90°+270°). → Count at
  the **base `HeroOcr`** via a metrics-decorator below `OrientedOcr`; track wrapper-probes and engine-calls
  separately.
- **B3 — `aggregate_det_rec_ms` tautological under a constant fake ★.** Robolectric can't run ONNX. → Make it
  REAL: capture real per-frame det/rec timings on-device **once**, annotate them into the labelled corpus; the
  replay sums each processed frame's RECORDED cost (skipped frames' recorded cost = the saving). **Decision B:**
  confirm this is the bar — it's the honest version of "measure power" and adds a one-time device capture pass
  to build the corpus.
- **B4 — `FrameSkipGate.reset()` coverage.** Reset on start, stop, every forced close (foreground-loss,
  timeout, projection-stop), natural close to NONE, rotation/rebuild, and every A/B arm boundary — the same
  discipline the hero stabilizer just learned (and the same class of bug codex caught there).
- **B5 — average-hash is heuristic, not proof.** State it honestly. Add a **forced-probe heartbeat**: every N
  ticks force a real OCR regardless of hash, bounding any missed select to ≤ the heartbeat interval. Add
  replay cases for fades, loading→select transitions, one-frame legibility; optional ROI/pixel-diff.
- **SF6 — `missed_select_count` too weak.** Assert first-open frame index/latency vs baseline + visible content
  within tolerance, not just "rendered ≥1 in the segment."
- **SF7 — render-call-set equality is the wrong check.** Compare externally visible window/content state per
  replay frame (a skip legitimately produces no render call).
- **SF8 — counters cross-thread.** `AtomicInteger`, or a `stats()` snapshot posted to the handler.
- **SF9 — runtime A/B flip.** Reset/rebuild gate+coordinator on each arm switch; log arm/session id; discard
  warmup frames; never mix-arm counters.
- **SF10 — device A/B fairness.** ABBA/randomized order, warmup, cooldown, fixed brightness; mAh directional
  only; report CPU time + OCR counts.
- **SF11 — full-SKU injection.** `src/main` `CombatSignalBinding` interface (clean=false), full provides it; no
  `BobVpnService` import from shared code (mirror `KillFeatureHolder`).
- **SF12 — stale socket fact (fixed inline).** Use `CombatFingerprint.present()` / `BattleConnection.pickWithCount()`;
  ports `{1119,3724}`, not `:3724` alone.
- **SF13 — ≥50% target.** Per-stage/per-SKU acceptance, not a universal gate: stage (a) reports static-screen
  savings @ 0 regressions; full (c) only if validated; clean (b) needs its own corpus-backed target + safety.
- **NIT14 — gate overhead.** Add `gate_eval_count` / `gate_eval_ms` / `capture_count` / wrapper-vs-engine OCR
  counts so the benchmark proves **net** compute savings, not just fewer OCR entries.

**Pending before implementation:** Decisions A (combat signal) and B (real-timing corpus). Once resolved, fold
these into the body and re-run a codex critique, then implement in the staged order.
