# BG Hero-Select Tier Overlay — Design

**Date:** 2026-06-01
**Status:** v4 (Codex round-2 + OCR-engine research addressed; §6.1)
**Scope:** Android overlay feature only. Heroes only. Bundled tier data. No backend.

---

## 1. Goal

During the Battlegrounds **hero-selection phase**, show an **S/A/B/C tier badge** on top of
each offered hero, by reading the hero names off-screen and looking them up in a bundled
tier table. The feature lives inside the existing `overlay-app` (`com.bobassist.phase0`).

One-sentence success criterion: *when the user enters BG hero-select with the feature
enabled, a correctly-colored tier letter appears anchored over each offered hero's name
within a couple of seconds, with **zero wrong badges**, and disappears when the phase ends —
without blocking the user's ability to tap a hero.*

## 2. Scope

### In scope (v1)
- Heroes only (not trinkets).
- Both in-game languages: Traditional Chinese (zhTW) and English (enUS).
- Tier from the **all-players MMR bracket (`100`)**, `last-patch` period.
- Tier data shipped as a **bundled static asset** exported from the existing `data-pipeline`
  SQLite. No network sync, no backend API.
- Auto-triggered, **only during the hero-select window** — see §8 for the trigger and its
  fallback. Not continuous screen capture during normal play.
- Device-independent positioning: OCR the screen, fuzzy-match recognized text against the
  hero-name dictionary, anchor each badge to the matched name's bounding box. **No
  hard-coded pixel regions, no per-device calibration.**
- Merged into the existing overlay-app. New subsystems: `MediaProjection` screen capture,
  a touch-through badge overlay, on-device ML OCR (PP-OCRv5 primary; see §6.1). Reuses the mihomo connection source and the
  host foreground service; **does not modify** the existing kill-button path or its handler.

### Out of scope (v1, deferred)
- Trinkets (same architecture; later spec).
- Backend tier API + on-device online sync (later spec). v1 ships a snapshot.
- Selectable MMR bracket / period (hard-coded to `100` / `last-patch`).
- Showing average-placement numbers (badge is the letter only).
- Non-OnePlus device QA. The design is device-independent by construction, but only the
  user's OnePlus 10T (Android 15, arm64) is a verification target for v1.

## 3. Gating spikes

Two unknowns can only be resolved with the physical device + live Hearthstone. They are
**device-dependent and require the user to run them**; this design cannot self-verify them.
Each produces a short recorded findings note that gates the stage depending on it. All
non-device components (matcher, tier table, normalization, badge-layout math, trigger logic,
coordinator wiring with fakes) are built and unit-tested independently of the spikes.

### Spike A — OCR engine bake-off, accuracy & wrong-badge rate (gates the OCR/matching stages)
- Capture real hero-select frames on the OnePlus 10T in **both** zhTW and enUS clients:
  **≥ 8 frames per locale**, varied heroes including long names, decorative-quote names
  (e.g. `『深沉絕望』尤格薩倫`), and stylized fonts; capture at the moment names are visible.
- **Bake-off** between the two candidate `HeroOcr` impls on the *same* frames (engine choice is
  §6.1): **PP-OCRv5 mobile** (primary; one multilingual rec model covers Traditional Chinese +
  English) vs **ML Kit** (baseline; bundled Latin + Chinese recognizers). Dump per line
  `{text, box, confidence?}`.
- Feed each through the real `HeroMatcher` + `TierTable` and record results per engine.
- **Pass criteria (gate):**
  - **Recall:** every offered hero in the frame resolves to its correct cardId in **≥ 80%**
    of frames per locale (one good frame in the capture loop is enough at runtime).
  - **Precision:** **zero wrong badges** across all spike frames (a wrong cardId is a hard
    fail — see §7 ambiguity rules).
- **Engine selection:** an engine is *eligible* only if it meets the gate (recall ≥80%,
  **zero wrong badges**) on **both** locales. Among eligible engines, prefer the one stronger on
  **Traditional Chinese** (the harder case), then lower latency / smaller APK; if only one is
  eligible, it ships. If **none** is eligible, apply fallbacks in order and record which was
  needed: (1) upscale a loose name band before OCR, (2) light preprocessing (grayscale/
  contrast), (3) the other engine / a different PP-OCRv5 build. **If precision fails** (any
  wrong badge), tighten §7 thresholds
  until zero, even at recall cost.
- Harness: a debug screen / script that loads a captured PNG, runs OCR + matcher, prints
  the table — buildable without the device; the *capture + run on-device* is the user step.

### Spike B — hero-select trigger signal (gates `SelectPhaseTrigger` vs fallback)
- **Current combat fingerprint (from code, not assumption):** `BattleConnection.pickWithCount`
  matches `metadata.host == "" ∧ metadata.network == "tcp" ∧ metadata.destinationPort ∈
  {1119, 3724}`; a 2026-05 recording showed 3724 matching 0/834 frames while **1119**
  carried the live socket. So "combat" is
  already a known signature; **hero-select is an earlier phase whose signature is unknown
  and may not exist as a distinct connection at all.**
- Using the existing connection-table recording tooling (commit `4ba6c1d`), record the
  mihomo connection table across a full match start with explicit **queue → hero-select →
  first-combat** boundary markers (operator notes the wall-clock of each transition).
- **Determine:** is there a connection feature present during hero-select that is **stable**
  and **distinguishable from both idle menus and the combat socket**? Record the exact
  predicate if yes.
- **Gate / outcome routing:**
  - **If a clean select signature exists** → `SelectPhaseTrigger` primary path (§8.1).
  - **If not** (likely, since the distinctive socket may only appear at combat) → the
    **visual-probe fallback (§8.2) becomes the primary trigger.** This is an expected,
    supported outcome, not a failure.

Both spikes are independent and can run in parallel.

## 4. Architecture

New module package: `com.bobassist.phase0.herotier`. The feature is **fully self-contained**
and isolated from the existing kill-button runtime:

- It runs on its **own dedicated `HandlerThread("herotier")`** (`htHandler`). It does **not**
  touch `OverlaySession`'s `pollHandler` (which `OverlaySession` exclusively owns and clears
  in `stop()`), nor `OverlayPoller` (whose `snapshot` is `() -> Int`, not JSON).
- It reads connections via the **same raw source the kill path uses** — the
  `connectionsJson: () -> String` lambda backed by `MihomoCore` — but on its **own poll
  loop** at `HT_POLL_MS` (e.g. 800ms). Two independent cheap reads of the in-memory
  connection table; zero coupling to the existing loop. (A later optimization could fan one
  shared pump to both consumers; not required for v1 and out of scope to keep blast radius
  minimal.)
- The host foreground service constructs `HeroTierCoordinator`, owns the `MediaProjection`
  session, and forwards `MainActivity`'s consent result. The existing kill-button wiring is
  untouched.

```
                         host foreground service (BobVpnService)
                          owns: MediaProjection session + consent
                                        │
        connectionsJson():String ───────┤ (same MihomoCore source as kill path)
                                        ▼
   own HandlerThread "herotier" ─► HeroTierCoordinator
                                        │  SelectPhaseTrigger.update(json) → Enter/Exit
                          (Enter) ──────┤
                                        ▼
   ScreenGrabber.capture():Frame ─► HeroOcr.recognize(Frame) ─► [OcrLine{text,box,conf?}]
                                                                     │ HeroMatcher + TierTable
                                                                     ▼
                                                        [HeroBadge{cardId,tier,box}]
                                                                     │ BadgeLayout.place(box,transform)
                                                                     ▼  (post to mainHandler)
                                                        TierOverlay.show(badges)  (per-badge windows)
                          (Exit / fg-lost / timeout / projection-stop) ─► TierOverlay.clear()
```

### Components (each: one responsibility, well-defined interface, independently testable)

| Component | Responsibility | Interface (sketch) | Tested by |
|---|---|---|---|
| `SelectPhaseTrigger` | From successive connection snapshots, emit Enter/Exit edges for the select window. Pure. | `fun update(connectionsJson: String): Transition` (`Enter`/`Exit`/`None`) | JUnit (fixture JSON) |
| `ScreenGrabber` | Capture one frame on demand from the live projection, with transform metadata. | `interface { fun capture(): Frame? }` | Fake in coordinator tests; impl manual |
| `Frame` | Captured bitmap + transform to screen space. | `Bitmap, captureW/H, displayBounds:Rect, rotation:Int` | (data) |
| `HeroOcr` | OCR a frame → recognized lines. **Boxes are in capture-bitmap pixel space (raw)** — `HeroOcr` does NOT transform to screen space. | `interface { fun recognize(frame: Frame): List<OcrLine> }` | Fake in coordinator tests; impl = Spike A |
| `OcrLine` | One recognized line; `box` in **capture-bitmap pixels**. | `text:String, box:Rect, confidence:Float?` | (data) |
| `HeroMatcher` | Map OCR lines → hero badges via exact/fuzzy match with ambiguity rejection. Pure. | `fun match(lines: List<OcrLine>): List<HeroBadge>` | JUnit |
| `TierTable` | Load bundled asset → per-locale `nameKey → (cardId,tier)` maps. | `fun lookup(nameKey: String): HeroTier?` | JUnit (fixture asset) |
| `NameKey` | Normalize a name for matching. Byte-for-byte parity with `data-pipeline`. Pure. | `fun of(raw: String): String` | JUnit (shared vectors) |
| `BadgeLayout` | Compute a badge rect from a name box + frame transform. Pure. | `fun place(box: Rect, t: Frame.Transform): Rect` | JUnit |
| `TierOverlay` | Add/remove small per-badge touch-through windows. | `fun show(badges); fun clear()` | Robolectric (fake WM) |
| `HeroTierCoordinator` | Own `htHandler`; run trigger poll, capture loop, retries, isolation, lifecycle. | `start()/stop()`, gating predicates | Robolectric (fakes) |

### Reused unchanged
- `connectionsJson: () -> String` (MihomoCore) — the raw connection source.
- Host foreground service (`BobVpnService`) — extended (not rewired) to own projection +
  coordinator; **kill-button path and its `pollHandler` are not modified.**
- `SYSTEM_ALERT_WINDOW` permission (already declared) for the overlay windows.

## 5. MediaProjection: consent, foreground service, lifecycle (Critical #2)

1. **Permissions / manifest.** Add `<uses-permission
   android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>`. Change the
   service's `android:foregroundServiceType` from `specialUse` to `specialUse|mediaProjection`.
2. **Consent handoff.** `MainActivity` calls
   `MediaProjectionManager.createScreenCaptureIntent()`; on result it passes `(resultCode,
   data Intent)` to the host service via a start intent. The service builds the
   `MediaProjection` from it.
3. **FGS type timing.** The service may only claim the `mediaProjection` FGS type **after**
   it holds consent. Flow: service is already foreground as `specialUse`; when consent
   arrives it **re-calls `startForeground(NOTIF_ID, notif,
   FOREGROUND_SERVICE_TYPE_SPECIAL_USE or FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`** before
   creating the `VirtualDisplay`. (On API 34+ the projection-type claim must accompany an
   active consented projection.)
4. **Capture setup.** One long-lived `VirtualDisplay` at **default-display resolution**
   backed by an `ImageReader` (RGBA_8888). `ScreenGrabber.capture()` pulls the latest image,
   copies to a `Bitmap`, and stamps the `Frame.Transform`.
5. **`MediaProjection.Callback.onStop`.** Register a callback; on `onStop` (user revokes via
   the system, or projection invalidated) → tear down VirtualDisplay/ImageReader, clear
   badges, set feature state to "needs consent", drop the `mediaProjection` FGS type.
6. **Configuration changes** (rotation/fold/multi-display): **resize the existing
   VirtualDisplay** (`VirtualDisplay.resize()` / refresh `ImageReader` surface via
   `setSurface`) — do **not** create a second `createVirtualDisplay()`. The `Frame.Transform`
   is recomputed from current display metrics each capture, so rotation is handled.
7. **Token lifetime.** Consent dies with the process / explicit stop; on next launch the
   user re-grants. The feature is inert until granted.

## 6. Foreground gating (Critical #3)

The kill button's `ForegroundDetector` is **optimistic** (`isTargetForeground = true` by
default; stays true when usage access is missing) — fine for a button, unsafe for capturing
the screen. Hero-tier uses a **strict gate** built on the same detector but inverted defaults:

- Capture is allowed **only when foreground is known-true**: usage-access permission granted
  **and** the latest foreground query == Hearthstone. Unknown/null ⇒ treated as **not**
  foreground.
- If usage access is missing or revoked, hero-tier **never captures**, clears any badges,
  and surfaces a one-time "grant usage access to enable tier overlay" hint. (The kill button
  keeps its own optimistic behavior; the two gates are independent.)
- On foreground-lost (HS backgrounded) mid-window: stop the capture loop and clear badges.

## 6.1 OCR engine (research-backed; finalized by the Spike-A bake-off)

`HeroOcr` is an interface returning `List<OcrLine>` (capture-pixel boxes), so the engine is
swappable and the rest of the pipeline is engine-agnostic. Two candidate impls; Spike A picks
the winner on real frames under the zero-wrong-badge gate.

- **Primary: PP-OCRv5 mobile (PaddleOCR).** A **single multilingual recognition model**
  (SVTRv2 head, ~18,383-char dict) covers **Traditional Chinese + English** (also Simplified /
  Japanese / Pinyin) — so one model handles both in-game languages; no per-locale model
  needed. Pipeline = DBNet detection (gives the name **boxes** we anchor to) → rec per line.
  Published mobile accuracy: Traditional Chinese ≈ 0.72, English ≈ 0.88 on hard scene text.
  **Hypothesis (Spike A tests it):** large, high-contrast hero-name UI text recognizes better
  than benchmark scene text, with residual errors recovered by the closed-dictionary fuzzy
  match (§7). On-device runtimes with PP-OCRv5 Android ports: **LiteRT/TFLite**
  (`iFleey/PPOCRv5-Android`, Apache-2.0, FP16 det/rec + dict, GPU/XNNPACK) and **ncnn**
  (`nihui/ncnn-android-ppocrv5`). ONNX Runtime (`RapidAI/RapidOcrAndroidOnnx`) is a mature
  Android OCR reference but **not confirmed as a PP-OCRv5 port** — only after verifying
  PP-OCRv5 ONNX model compatibility. PP-OCR models are Apache-2.0. The exact runtime is chosen
  in Spike A by integration effort + latency + APK size.
- **Baseline: ML Kit on-device text recognition** (bundled Latin + Chinese). Near-zero
  integration, free, offline. ML Kit lists Chinese (incl. `zh-Hant`) as supported, **but
  Traditional accuracy on stylized game text is unproven** and community issues report Chinese
  recognition errors / traditional↔simplified confusion (googlesamples/mlkit #421). Treat it as
  a *risk to validate in Spike A*, not a guaranteed-good baseline; ships only if it clears the
  zero-wrong-badge gate on both locales.

**Box coordinate note:** raw PP-OCR-style runtimes return detector boxes in the model's
preprocessed input space (e.g. letterboxed 640×640); **that impl maps boxes back to
capture-bitmap pixels** before returning. ML Kit already returns boxes in input-bitmap (=
capture) pixels, so no mapping is needed there. Either way `OcrLine.box` is in **capture
pixels** (distinct from `BadgeLayout`'s capture→screen transform, §9.3); the Spike-A harness
draws a sanity overlay to confirm box alignment per engine.

## 7. Matching (`HeroMatcher` + `NameKey`) (Critical, Should-fix #2/#4)

### 7.1 Normalization — byte-for-byte parity with `data-pipeline`
`NameKey.of` ports `normalize_name_key` **exactly**: `NFKC → casefold → drop lone
surrogates → collapse internal whitespace to single spaces → trim`. **No punctuation
stripping** (decorative marks like `『』`, commas, apostrophes are preserved on *both* the
exporter and the app side, so keys align). A **shared test-vector file** `[{in, out}]` is
asserted by **both** the Python and Kotlin test suites to guarantee parity. Canonical path:
`data-pipeline/tests/fixtures/namekey_vectors.json`, mirrored byte-identically to
`android/overlay-app/app/src/test/resources/namekey_vectors.json` (the Kotlin test reads the
mirror; the plan adds a tiny sync-guard test asserting the two files match). Any change must
update the canonical file, the mirror, and re-run both suites.

### 7.2 Match per OCR line/element (not block)
Match at **recognized-line granularity** (one detected text line per candidate; for engines
that expose sub-line elements, also try element-level candidates as a fallback). **Never**
match a whole multi-line block (blocks can fuse multiple UI strings). For each candidate
string `s` with `k = NameKey.of(s)`:

1. **Exact** hit in either locale map → accept.
2. **Short names:** if `k.length ≤ SHORT_LEN` (e.g. 3; many zhTW names are 2–4 chars) →
   **exact only, no fuzzy** (fuzzy on tiny strings is too dangerous).
3. **Fuzzy** (only for `k.length > SHORT_LEN`): Levenshtein to each key; let `d1` = best
   distance (key `b1`), `d2` = second-best. Accept `b1` iff
   `d1 ≤ min(FUZZY_CAP, floor(FUZZY_RATIO·len))` **and** `d2 − d1 ≥ AMBIG_MARGIN`
   (e.g. `FUZZY_RATIO = 0.2`, `FUZZY_CAP = 2`, `AMBIG_MARGIN = 2`). Otherwise **reject** —
   a missing badge is acceptable, a wrong badge is not.
4. **Dedup:** same cardId from multiple lines → keep the higher-confidence (or first) box.

Constants are tunable and **finalized by Spike A** under the zero-wrong-badge gate. The
dictionary is ~110 heroes × 2 locales; linear scan per line is trivial.

## 8. Triggering (`SelectPhaseTrigger`) (Should-fix #1/#5)

### 8.1 Primary — connection signature (only if Spike B finds one)
Pure function over successive `connectionsJson` snapshots; emits `Enter` on the rising edge
of the recorded select predicate, `Exit` on the falling edge (or transition to the combat
fingerprint `host=="" ∧ network=="tcp" ∧ port∈{1119,3724}`), `None` otherwise. Single-fire
per edge.

### 8.2 Fallback / likely-primary — bounded visual probe
If no clean select signature exists, trigger visually. **Constants (explicit):** while HS is
foreground **and** the feature is enabled **and** consent + usage-access are held, probe at
`PROBE_MS` (e.g. 2000ms): capture one frame → OCR → matcher. **Open** when a probe yields
**≥ 2** hero matches. **Close** after `CLOSE_K` (e.g. 3) consecutive probes with **0**
matches, or on foreground-lost, or `MAX_WINDOW_MS`. **Duty cycle:** at most one capture per
`PROBE_MS`; the post-open capture loop (§9) supersedes probing until close. **If OCR/model is
unavailable**, the fallback is disabled and the feature is inert (logged once). This is
periodic screen capture while HS is foreground — it is gated behind the explicit per-session
feature enable + consent, and documented to the user as such.

The coordinator depends only on the `Transition` interface; which path is wired is decided
after Spike B and recorded in the plan.

## 9. Capture window & rendering

1. **Open** (from §8) → bounded capture loop on `htHandler`: up to `MAX_ATTEMPTS` (e.g. 8) at
   `CAPTURE_INTERVAL_MS` (e.g. 700ms). Each round: `capture → recognize → match`. On the
   first round yielding ≥1 badge, render; keep a few more rounds to stabilize as art/animation
   settles, then stop capturing and hold. Whole window capped by `MAX_WINDOW_MS` (e.g. 15s).
2. **Render** posts to `mainHandler`. Each `HeroBadge` becomes a **small per-badge overlay
   window** (Critical #4) sized to the badge, positioned by `BadgeLayout`:
   - `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`, **and**
     `LayoutParams.alpha ≤ InputManager.getMaximumObscuringOpacityForTouch()` so taps pass
     through to HS on Android 12+. Small windows (not a full-screen sheet) minimize overlap
     with the portrait tap target; combined with the existing kill-button window, total
     obscuring opacity over any point stays under the cap.
   - A **manual on-device tap-through test** is a required gate (§11).
3. **Coordinate transform** (Critical #5): the capture→screen transform happens in **exactly
   one place** — `BadgeLayout.place(box, transform)`. `HeroOcr`/`HeroMatcher` pass boxes
   through untransformed (in capture-bitmap pixels). `BadgeLayout` maps the OCR box from
   capture-bitmap space to screen space using **`scaleX = displayW/captureW`,
   `scaleY = displayH/captureH`, plus rotation and any letterbox offset** carried on
   `Frame.Transform` — not a single uniform `scale`. Capturing at default-display resolution
   keeps scales ≈1, but the transform is applied unconditionally.
4. **Colors:** `S=#FFD700` gold, `A=#A335EE` purple, `B=#3B82F6` blue, `C=#9CA3AF` gray;
   white letter with a contrasting outline for legibility over arbitrary art.
5. **Close** (§8 Exit / fg-lost / `MAX_WINDOW_MS` / projection-stop) → `clear()` removes all
   badge windows and stops the loop. Window can re-open next match.

## 10. Tier data asset & exporter (Should-fix #6)

### Asset (`app/src/main/assets/herotier_v1.json`)
```json
{
  "schemaVersion": 1, "bracket": "100", "period": "last-patch",
  "generatedAt": "2026-06-01T00:00:00Z",
  "heroes": [
    { "cardId": "BG_HERO_001", "tier": "S",
      "names": { "zhTW": "斯尼德", "enUS": "Sneed" } }
  ]
}
```
At load, `TierTable` builds `nameKey(zhTW)→(cardId,tier)` and `nameKey(enUS)→(cardId,tier)`;
both are searched at match time.

### Exporter (`data-pipeline`, the only new pipeline code; acquisition untouched)
`v_latest_stats` exposes `card_id`/`name`/`entity_type` but **not** `entity_id`, so the
exporter re-joins `entity` to reach `entity_name`:
```sql
SELECT v.card_id,
       v.name                              AS en_name,   -- entity.name (default = enUS)
       en.name                             AS zh_name,   -- nullable → fallback to en_name
       v.avg_placement
FROM v_latest_stats v
JOIN entity e        ON e.card_id = v.card_id AND e.entity_type = v.entity_type
LEFT JOIN entity_name en ON en.entity_id = e.entity_id AND en.locale = 'zhTW'
WHERE v.entity_type = 'hero'
  AND v.source      = 'firestone'
  AND v.mmr_bracket = '100'
  AND v.time_period = 'last-patch'
  AND v.mode        = 'solo'      -- literal (load.py _MODE)
  AND v.region      = 'global'    -- literal (load.py _REGION)
ORDER BY v.avg_placement ASC, v.card_id ASC;   -- deterministic tie-break
```
`mode='solo'`/`region='global'` are the literal constants the loader writes (`load.py`
`_MODE`/`_REGION`). **Before** the main query the Python exporter runs `SELECT DISTINCT
mode, region FROM v_latest_stats WHERE entity_type='hero' AND source='firestone' AND
mmr_bracket='100' AND time_period='last-patch'` and **asserts exactly one row equal to
`('solo','global')`**, raising and aborting if not — so a future multi-mode/region dataset
fails loudly instead of silently mixing dimensions. Tier from rank position `p = (i+0.5)/n`
over the
ordered rows: `S: p<0.12`, `A: 0.12≤p<0.35`, `B: 0.35≤p<0.68`, `C: p≥0.68`. No sample
filter for heroes (min sample is high). `zhTW` falls back to `en_name` when null; the test
asserts that fallback. Mirrors the validated percentile methodology.

## 11. Testing strategy (Should-fix #7)

**Automated (CI, no device):**
- `NameKey`: JUnit over the **shared vector file** (parity with Python). Edge cases:
  fullwidth/half-width, decorative quotes, lone surrogates, whitespace collapse, casefold.
- `HeroMatcher`: JUnit — exact, short-name exact-only, fuzzy accept, fuzzy reject on
  ambiguity margin, wrong-input rejection, both locales, dedup.
- `TierTable`: JUnit — asset parse, both-locale maps, missing-zhTW fallback.
- `BadgeLayout`: JUnit — scaleX/scaleY, rotation, letterbox offset.
- `SelectPhaseTrigger`: JUnit — rising/falling edges, single-fire, combat-vs-select
  distinction, fixture connection JSON.
- `TierOverlay`: Robolectric with a **fake/Shadow `WindowManager`** — assert add/remove
  counts and per-badge `LayoutParams` (type, flags, alpha ≤ cap). Drawing math lives in
  `BadgeLayout`; the view is dumb.
- `HeroTierCoordinator`: Robolectric with **fakes** for `ScreenGrabber`/`HeroOcr`/
  `TierOverlay` — open→capture loop→badges shown; close→cleared; retry on empty OCR; window
  timeout; foreground-lost close; projection-stop close.
- Exporter: pytest over a fixture SQLite — tier cuts, tie-break order, locale fallback, JSON
  shape, single-mode/region assertion.

**Manual on-device gates (cannot be automated; checklist in the plan):**
- Overlay permission + projection consent flow.
- **Tap-through:** can actually pick a hero with badges shown (Critical #4 verification).
- Projection stop (revoke) → badges clear, feature goes inert, no crash.
- Rotation mid-window → badges reposition / clear cleanly.
- OCR engine + models load and run on the device (both languages).
- Spike A (OCR accuracy/precision) and Spike B (trigger) as above.

## 12. Error handling & isolation
- **Projection denied/stopped:** feature silently inert; kill button + rest of app
  unaffected; breadcrumb logged; re-enable re-requests consent.
- **OCR throws / empty:** that round yields no badges; capture loop retries within the
  window; gives up after `MAX_ATTEMPTS` without crashing.
- **OCR engine/model init fails:** log + disable the feature (inert), one-time hint; the
  kill button and rest of the app are unaffected. (Models ship in the APK, so this is rare.)
- **Per-round isolation:** each capture→ocr→match round is independent; one bad frame never
  breaks the window or leaks state.
- **Handler ownership:** `htHandler` is owned solely by `HeroTierCoordinator`; `stop()` may
  `removeCallbacksAndMessages(null)` on it without affecting the kill path.

## 13. Risks
1. **OCR accuracy on stylized Chinese (highest).** Mitigated by dictionary fuzzy-match +
   Spike A's fallback ladder, gated on zero wrong badges.
2. **No clean hero-select connection signature (likely).** Mitigated by the bounded
   visual-probe trigger (§8.2) as the supported primary path.
3. **Touch obscuring rules** silently eating taps. Mitigated by per-badge small windows +
   alpha cap + a mandatory manual tap-through gate.
4. **MediaProjection UX/FGS friction** on Android 14+. One long-lived consented projection
   minimizes prompts; exact FGS handling specified in §5.
5. **Capture/display coordinate drift.** Mitigated by full transform metadata in §9.3.
6. **Spikes need the user's device.** Flagged as user-executed steps with prepared harnesses.
7. **OCR engine integration cost (PP-OCRv5).** Bundling det+rec+dict+native libs grows the APK
   and adds an arm64-only `.so`; the exact model **license/notice/checksum** must be recorded
   when vendoring. Mitigated by measuring size in Spike A and shipping ML Kit if PP-OCRv5's cost
   isn't justified.
8. **OCR latency vs the capture loop.** PP-OCRv5's larger dictionary makes it slower than v4;
   det→rec p95 must fit within `CAPTURE_INTERVAL_MS` (or the loop just takes more attempts).
   Measured per engine in Spike A and used as a selection input.

## 14. Resolved assumptions
- Badges hold until window close (we don't detect the individual pick); acceptable since the
  overlay is touch-through and the phase ends shortly after.
- Offered-hero count (2 vs 4) is implicit — we badge whatever names match; no fixed count.
