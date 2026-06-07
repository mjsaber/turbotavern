# Hero-Name Matching Golden Test (Layer 1) — Design

**Status:** READY-TO-PLAN (Codex review incorporated — B1/B2 resolved, N1/N2 folded in; Codex pre-ran the matcher over the real 339-name corpus: T1/T2/T3 all pass today — 0 wrong, recall 0.94, 0 false-positives, 0 ambiguous)
**Phase:** 1 of 2 (Layer 2 = real card-image OCR corpus, separate spec)
**Module:** `android/overlay-app`

## 1. Goal

An **offline, exhaustive, CI** golden test of hero-name → `cardId` matching across **all 113 heroes ×
{enUS, zhCN, zhTW}**, that (a) proves every canonical name resolves to its own hero, (b) proves the
fuzzy/edge-noise tolerance **never resolves to the WRONG hero** anywhere in the 339-name space, and
(c) reports the confusable-name set. No device, no network — it catches the class of bug that shipped
(zhTW names missing because matching was only checked on one lucky zhCN frame).

## 2. Context

The zhTW overlay failed because the matcher's fuzzy path was dead for short CJK names (cap/ambigMargin
dead-zone) + OCR's leading "|" frame stroke — and this slipped through because "validation" was a
single clean zhCN frame that only exercised exact matches. Remediation: stop validating on one sample;
**test the whole name space + a deterministic OCR-noise model** in CI. The data pipeline already gives
every name in every locale — the bundled asset `app/src/main/assets/herotier_v1.json` has
`names{enUS,zhCN,zhTW}` for all 113 heroes (0 missing, verified). Real-frame **OCR accuracy** (does ML
Kit read the stylized font) is a different question → **Layer 2** (separate spec), because card-render
fonts only approximate the select-screen banner and ML Kit needs a device/emulator.

## 3. Scope

**In:** a Robolectric golden test over the bundled asset; a helper to load the asset; a deterministic
OCR-noise model; any **real matcher fixes** the test surfaces (e.g. a tolerance value that causes a
wrong match somewhere, or a genuinely-ambiguous name pair).
**Out:** ML Kit OCR accuracy, card-image downloads, real game frames (all Layer 2); **no `NameKey`
change** (byte-parity with data-pipeline); no tier-value/data correctness (that's the pipeline's job —
this test is matching/consistency only, and is self-referential to the table by design).

## 4. Design

### 4.1 Corpus
`@RunWith(RobolectricTestRunner)` + `@Config(sdk=[…])` (the plain-JUnit `classLoader.getResource`
idiom in `HeroMatcherTest` will NOT see a main asset — Codex confirmed the Robolectric path works
because `unitTests.isIncludeAndroidResources=true` + merged assets). Load `herotier_v1.json` via
`ApplicationProvider.getApplicationContext().assets.open("herotier_v1.json")` (same as
`BobVpnService` reads it in production), build the `TierTable` + **`HeroMatcher(table)` with NO param
overrides** (B1 — track whatever ships; current defaults `shortLen=3, fuzzyCap=2, fuzzyRatio=0.2,
ambigMargin=2`), and the flat list `corpus = [(cardId, locale, name)]` over non-blank names.

### 4.2 T1 — self-match (exhaustive, hard assert)
For every `(cardId, locale, name)`: `matcher.match([OcrLine(name, BOX)])` must contain `cardId`.
Collect **all** failures into a readable list (`cardId/locale "name" -> got=[…]`) and assert it's empty.
Catches: `NameKey` breakage, cross-hero canonical-name collisions, table dup/ambiguity that hides a
real hero.

### 4.3 T2 — noise never resolves WRONG (exhaustive, hard assert; the safety net for the tolerance change)
For every name, for each perturbation `p` in:
- `"|" + name`, `name + "|"` (card-frame stroke, both ends),
- each single-character **deletion** (only when `len(NameKey) > shortLen + 1`),

assert `matcher.match([OcrLine(p)])` contains **no** badge whose `cardId` differs from the source
hero's. (Correct-or-empty; **never wrong** — "a missing badge beats a wrong one".) This is what
guarantees the loosened tolerance + edge-strip don't mis-attribute any hero across the whole space.

### 4.4 T3 — recall report (soft assert)
Over the T2 perturbation set, measure the fraction that still recover the **correct** hero; log
per-locale recall. Assert overall recall ≥ a **conservative floor** (start `0.60`) so a future change
that tanks fuzzy recall fails CI, without being brittle to a few inherently-ambiguous names.

### 4.5 Ambiguity audit (hard assert)
Per locale, compute distinct-hero name pairs within `cap` edit distance (the confusable set).
**Hard-assert the count == 0** (current data has 0 — Codex verified), with the offending pairs in the
failure message (N2 — `== 0` is *less* brittle than a committed N and immediately surfaces any data
update that introduces a collision; an ambiguous key would also fail T1, this names *why*). Also
`log()` the set for visibility.

### 4.6 Negative corpus — non-hero UI never yields a badge (hard assert; B2)
The shipped bug was a *miss*, but §2's remediation *loosened* tolerance, so the new risk is a
**false-positive** on the long UI strings that share the select screen. Curate a small negative corpus
of real select-screen chrome across locales and assert `match([OcrLine(s)])` is **empty** for each:
- zhTW: `選擇一名英雄`, `重骰`, `確定`, `元素，惡魔，機械，海盜，野獸`
- zhCN: `选择一个英雄`, `重掷`, `确定`, the zhCN tribe-keyword bar
- enUS: `Choose Your Hero`, `Refresh`, `Confirm`

Codex verified these all resolve to no badge today — this locks that in against future loosening.

## 5. Verification
`./gradlew :app:testDebugUnitTest --tests "com.bobassist.phase0.herotier.HeroTableGoldenTest"` green.
**Expected first-run result: GREEN** — Codex pre-ran the matcher over the real corpus and all four
properties already hold (0 wrong, recall 0.94, 0 FP, 0 ambiguous). This test is the **regression net**
the §2 post-mortem demanded (it locks current safety), not a red-bug-reproducer — a green first run is
the success criterion, not a no-op. If a future matcher/data change breaks a property, it fails here in
CI with no device. Any real issue → fix the matcher (never NameKey).

## 6. Risks & decisions (resolved)
- **Perf:** non-issue (N4) — each `match()` gets ONE line so `verticalMerge` is a no-op; ~18k matches
  ran <1s in Codex's port. No sampling/capping needed.
- **Self-referential:** corpus = the table, so this cannot catch wrong *data* (e.g. a wrong tier) —
  only matching/consistency. Stated, accepted (data correctness is the pipeline's domain).
- **Resolved decisions:** (a) T3 recall floor = **0.60** committed as a regression guard (actual 0.94,
  big headroom, not brittle — N1); (b) ambiguity audit = **hard-assert == 0** (N2); (c) confusable-
  **substitution** table (勾↔匀, 復↔複, 爾-drop) **deferred to Layer 2**'s real-OCR error model (N6) —
  deletion + edge-`|` already exercises the dead-zone. Deletion gate is on **NameKey length > shortLen+1**.

---
*Next: Codex review → plan → TDD impl (write the golden test; fix anything real it surfaces) → review.*
