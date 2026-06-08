# Plan — PP-OCRv5 OCR engine (Spike A execution)

**Spec:** §6.1 + "Spike A" of [`../specs/2026-06-01-bg-hero-tier-overlay-design.md`](../specs/2026-06-01-bg-hero-tier-overlay-design.md)
(engine choice, the zero-wrong-badge gate, runtime candidates, and the capture-px box-mapping rule
are already designed there; this plan only **executes** Spike A).
**Status:** Stage 0 COMPLETE → **GO**. Stage 1 COMPLETE (runtime=ONNXRuntime-Android 1.22.0,
models vendored, `assembleDebug` green). Stage 2 IN PROGRESS (CTC decoder done+tested). Stages 3–4
not started.

**Runtime decision (Stage 1):** **ONNXRuntime-Android `1.22.0`** — the official AAR runs the *exact*
`.onnx` models validated in Stage 0 from Kotlin (no model conversion, no custom JNI). Models vendored
to `app/src/main/assets/ppocr/` with provenance in `NOTICE.md`. APK delta ≈ +40 MB (18 MB
`libonnxruntime.so` + 21.5 MB models); dropping ML Kit later recovers ≈13.6 MB → ≈26 MB net. Size
levers (tracked, not blocking): int8-quantize the 16.6 MB rec model, reduced-op ORT build.
**Module:** `data-pipeline` (Stage 0, Python/uv) → `android/overlay-app` (Stages 1–4).

### Stage 0 result (2026-06-07) — see [`recordings/ocr-corpus/PPOCRV5-OFFLINE.md`](../../../android/overlay-app/recordings/ocr-corpus/PPOCRV5-OFFLINE.md)
match@cap (comparable to ML Kit through-matcher) on the same card-render corpus, **best config =
PP-OCRv5 unified `ch` rec, rec-only on a cropped name band, + punct/TC→SC normalization**:

| locale | ML Kit | PP-OCRv5+norm |
|---|---|---|
| enUS | .939 | .878 (crop-junk artifact; ~parity expected on real screen) |
| zhCN | .398 | **.908** |
| zhTW | .255 | **.867** |

zhTW lever breakdown: none .673 → +punct .796 → +TC→SC fold .867. `chinese_cht` (v3) is *worse*.
**New workstream this surfaced:** the win needs **(a)** rec-only on a known name band (not full-frame
det), and **(b)** `NameKey` normalization = unify middle-dot `‧·・`/brackets `『』【】[]` + **TC→SC
fold (OpenCC `t2s`)**, applied to both sides — with **data-pipeline parity** and a **precision
re-check** (Layer-1 golden no-confusable-pairs test re-run *under folding*; zero-wrong-badge gate).

## Why now
Layer-2 measured ML Kit on the card-render corpus at **enUS .94 / zhCN .40 / zhTW .26** (emulator,
matcher-cap-confounded — a proxy, not the real-device number). The user judged CJK unacceptable and
chose to switch to PP-OCRv5 (SOTA Chinese on-device OCR; one multilingual rec model covers
SC+TC+EN, ~18,383-char dict). The risk is sinking days into a native (ONNX/TFLite + JNI) Android
port for a number that's partly an emulator/matcher artifact. **Mitigation: prove the engine
offline on the same corpus first (Stage 0), then port only if it clears the bar.**

---

## Stage 0 — Offline bake-off on the existing corpus (Python/uv, self-verifiable) **[GATE]**

**Goal:** measure PP-OCRv5 raw OCR accuracy on `data-pipeline/build/card-renders` (the *same* 98×3
renders ML Kit ran on) — apples-to-apples, offline, no device. Decides whether the Android port is
worth doing.

**Approach:** `rapidocr-onnxruntime` (PP-OCRv5 ONNX models; pure onnxruntime — mirrors the
`RapidAI/RapidOcrAndroidOnnx` Android path the spec names, so a positive result transfers). Run via
`uv run --with rapidocr-onnxruntime` so nothing is added to `pyproject` until the engine is chosen.

- [ ] **Metric — isolate OCR from the matcher.** Per render, run det+rec, take the recognized
  line(s), compare to the ground-truth hero name for that `cardId__locale` (names from
  `herotier_v1.json`). Report per locale: **exact-match rate** and **char-error-rate (CER)**. This
  answers the user's own question ("OCR 抓错 vs 数据错") cleanly — it does **not** depend on the
  matcher cap bug, unlike the Layer-2 number.
- [ ] **Output:** `recordings/ocr-corpus/PPOCRV5-OFFLINE.md` — per-locale table vs the ML Kit
  baseline (.94/.40/.26), plus a per-miss list (expected vs read).
- [ ] **Gate to proceed:** PP-OCRv5 must (a) **beat ML Kit on CJK by a wide margin** and
  (b) reach **≥ ~0.85 exact-match on zhCN and zhTW** on these *clean* renders. (Clean renders are
  easier than real frames, so this is **necessary, not sufficient** — real-frame validation is
  Stage 4.) If it fails (a), stop and reconsider (visual matching, preprocessing). Record the result
  either way.

**No Android code in this stage.** Deliverable is the report + a go/no-go.

---

## Stage 1 — Runtime decision + model vendoring (gated on Stage 0 = go)

**Goal:** pick the Android runtime and vendor the models with provenance.

- [ ] Decide runtime by integration cost / latency / APK delta. Candidates (spec §6.1):
  **ONNX Runtime-Android** (mirrors the proven Stage-0 path), **TFLite/LiteRT** (`iFleey/PPOCRv5-Android`,
  Apache-2.0 FP16 det+rec+dict), **ncnn** (`nihui/ncnn-android-ppocrv5`). Prefer the lowest-native-surface
  path that hits latency. Record the choice + rationale in this plan.
- [ ] Vendor `det` + `rec` models + dict into `app/src/main/assets/ppocr/`; record **source URL,
  license (Apache-2.0), sha256, size** in a `NOTICE`/README next to them (spec risk #7).
- [ ] Confirm APK-size delta is acceptable (<~20 MB target).

---

## Stage 2 — `PaddleHeroOcr : HeroOcr` (TDD on the pure pieces)

**Goal:** a second `HeroOcr` impl, behind the existing seam, returning `OcrLine`s with
**capture-bitmap-pixel** boxes (spec §6.1 box note).

- [ ] Pipeline: frame → det preproc (letterbox to model input) → det → **postproc to boxes** →
  per-box crop → rec preproc (h=48) → rec logits → **CTC greedy decode** (dict) → `OcrLine`, with
  detector boxes **mapped back to capture px**.
- [ ] **JVM-testable pure functions (TDD, no device):** CTC greedy decode (fixture logits → string),
  letterbox box-remap (model-space box → capture px), dict load. These run on JUnit/Robolectric.
- [ ] Native inference + det-map postproc are integration code (not JVM-unit-testable); cover by the
  Stage-0 parity harness reused on-device + the Stage-4 device run.
- [ ] `isAvailable()` false if models fail to load → coordinator stays inert (existing seam).

---

## Stage 3 — Engine selection wiring

**Goal:** choose the engine at construction without deleting the ML Kit fallback.

- [ ] `BobVpnService` builds `PaddleHeroOcr` as the tier OCR; ML Kit stays as a compile-time
  fallback (the `HeroOcr` seam already supports this — no other code changes).
- [ ] Full `:app:testDebugUnitTest` green; `assembleDebug` ok; APK-size delta noted.

---

## Stage 4 — On-device validation (**USER STEP** — gates ship)

**Goal:** the only meaningful accuracy number — real device, real frames. Cannot be self-verified
(native inference doesn't run on Robolectric; the emulator's SwiftShader is exactly what we're
escaping).

- [ ] Capture ≥8 real hero-select frames per locale (zhTW + enUS, incl. long/decorative names) via
  the existing `OcrProbeReceiver` harness.
- [ ] Run PP-OCRv5 + the real `HeroMatcher`; record per-locale recall + **any** wrong badge.
- [ ] **Ship gate (Spike A):** recall ≥80%/locale **and zero wrong badges** on both locales. If
  precision fails, tighten §7 thresholds until zero (even at recall cost) before shipping.

---

## Notes
- Stage 0 is fully offline and reversible — start there; commit the report regardless of outcome.
- The matcher cap-for-short-names weakness (Layer-2 finding) is **orthogonal** and tracked
  separately; Stage 0's raw-OCR metric deliberately sidesteps it.
- Get this plan an independent review before Stage 1 (the first expensive/native commitment); Stage 0
  needs no review (measure-only).
