# Real Card-Image OCR Corpus (Layer 2) — Design

**Status:** READY-TO-PLAN (Codex review incorporated — B1 chunking/ANR, B2 skip probe/out, B3 512x; N3/N4/N6/N7/N9 folded in)
**Phase:** 2 of 2 (Layer 1 = offline matching golden test, done)
**Modules:** `data-pipeline` (fetch) + `android/overlay-app` (OcrProbe, emu harness)

## 1. Goal

Characterize **real ML Kit OCR → matcher** behavior on **localized Hearthstone card renders** for all
available heroes (**98/113**) × {enUS, zhCN, zhTW}, producing a **per-locale OCR→match report** + a
regression floor, and a list of which hero names ML Kit misreads. This answers the half Layer 1 can't:
*Layer 1 proved the matcher is correct given text; Layer 2 tests whether OCR produces text close
enough.* It is **not a pure-CI unit test** — ML Kit needs a device/emulator — so the deliverable is a
**reusable harness + a committed report**, run on demand (emulator), not a PR gate.

## 2. Context

The zhTW miss was an OCR-noise problem the matcher couldn't absorb. Layer 1 locks matching; this layer
measures OCR accuracy at scale. Source: **HearthstoneJSON art CDN**
`art.hearthstonejson.com/v1/render/latest/{locale}/512x/{cardId}.png` — verified **98/113 render per
locale** (512×776; B3 — 512x not 256x so the small CJK name band isn't under-resolved, which would
conflate "OCR can't read it" with "we fed a thumbnail"). The 15 newer `BGxx_HERO_*` 404 (no
collectible render → need real game frames, out of scope). The render shows the name in the HS
**card-name banner** font. **Caveat (stated up front):** that font ≈ but ≠ the in-game **select-screen**
banner, AND the render is a clean static image (no motion/scaling/background), so results are an
**optimistic UPPER BOUND** on real-screen accuracy, not a prediction; real select frames accrue
separately (we have zhTW + zhCN, one each).

## 3. Scope

**In:** a `data-pipeline` fetch step for the renders; a host harness (push → `OcrProbe` → parse →
report); a per-locale match-rate report + soft floor; reuse the existing `OcrProbe` receiver and the
`emu-smoke` AVD (or a connected device).
**Out:** select-screen ground truth; the 15 unrendered heroes; pixel-precise name cropping (OCR the
whole card, §4.4); any matcher/OCR change (Layer 2 only **measures** — fixes would be their own spec).

## 4. Design

### 4.1 Fetch — `data-pipeline` `bgtiers fetch-card-renders` subcommand (N4)
Reuse existing infra: add `art_url_template:
"https://art.hearthstonejson.com/v1/render/latest/{locale}/512x/{cardId}.png"` under `hsjson:` in
`sources.yaml` (absent today); a new argparse subcommand (pattern `cli.py:111-121`) that reads cardIds
from the shipped `herotier_v1.json`, loops {enUS,zhCN,zhTW}, and downloads via `fetch.fetch_url`
(retry/backoff, `fetch.py:9`) with a small `time.sleep` politeness gap. Save present renders to
`data-pipeline/build/card-renders/{cardId}__{locale}.png` (cardIds are `[A-Za-z0-9_]+`, single
underscores — `__` is a safe separator, N2); record HTTP 404s in `build/card-renders/missing.json`.
**Idempotent** (skip already-downloaded). Unit test via `httpx.MockTransport` (`test_fetch.py:5`):
assert the URL template, skip-existing, and 404→manifest — **no network in CI**.

### 4.2 OCR run — host harness `scripts/ocr-corpus.sh`
**Requires `SERIAL=`** (N7 — the user's real phone *and* the emu AVD may both be attached; reuse the
`DEV="${SERIAL:+-s $SERIAL}"` idiom from `dev-record.sh:12`; the reproducible target is `emu-smoke`'s
AVD). Ensure the debug APK is installed; launch the app once (clears the manifest-receiver "stopped"
state). Then, **per locale (MANDATORY chunking — B1):** a single `OcrProbe` broadcast over all ~294
images would exceed the `goAsync()` BroadcastReceiver/ANR window (~294×300ms ≈ 90s ≫ ~10–60s — the
work is detached so it may finish but the system can ANR/reclaim). So push **one locale's ~98 images**
to the probe dir, `adb logcat -c`, broadcast `com.bobassist.phase0.OCR_PROBE`, wait for `OcrProbe:
probe done`, dump that chunk's `OcrProbe` JSON lines, clear the probe dir, repeat for the next locale.
**Skip/clear `probe/out` (B2):** the receiver writes one annotated PNG per image there (~doubles work
+ ~294 files); the harness does NOT pull it and `rm -rf`s it before/after (host-side only — no receiver
change, Layer 2 only measures). Quote all globs (`"$PROBE"/*.png`) to avoid the zsh "no matches"
abort the repo's other scripts already guard against (N8).

### 4.3 Report — `scripts/ocr-corpus-report.py`
Parse the `OcrProbe` lines (`{"file","matches":[{cardId,tier}…]…}`); the **expected** cardId is the
filename stem before `__`. Per file: matched = expected ∈ matches. Aggregate **per locale**
(matched/total) and overall; list every failure with what OCR read (the `lines` field) so misses are
diagnosable. Write `android/overlay-app/recordings/ocr-corpus/REPORT.md` (committed) whose header states
results are an **optimistic upper bound** (clean render, card-banner font ≠ select-screen). Print a
clear per-locale PASS/BELOW-`FLOOR` banner (start `FLOOR=0.85`, per-locale per Layer-1 N2); **exit 0 by
default** (manual diagnostic — a nonzero exit just makes the operator's shell look failed, N6), and
only exit nonzero under an explicit `--strict` flag. Treat the first run as **calibration**, not a
baseline.

### 4.4 Whole-card OCR (decision)
OCR the **whole** render, not a cropped name band: simpler and robust; the only other text on a hero
card is the mana cost / stat numerals, which never match a hero (Layer-1 negative-corpus + short-key
exact-only). The render's banner name and `herotier_v1.json` come from the **same HSJSON source**, so
the expected match is valid (N3). **Expected measured noise:** some names use `‧` (U+2027) in the asset
vs `・` (U+30FB) in the render — NOT NFKC-equivalent, and `NameKey` keeps punctuation (parity), so it's
1 edit that the fuzzy cap absorbs. That's precisely the OCR-noise class Layer 2 measures — report it as
an expected hit, not a surprise. Crop-to-band deferred unless whole-card produces false matches.

## 5. Verification
Run `data-pipeline` fetch (downloads ~294 PNGs), then `scripts/ocr-corpus.sh` against the emulator,
then the report. Commit `REPORT.md` with the per-locale match rates + failure list. The harness is
re-runnable; the `data-pipeline` fetch has a CI unit test (mocked HTTP); the OCR run itself is
**manual/on-demand** (device-bound), not a PR gate.

## 6. Risks & decisions (resolved)
- **Font proxy / upper bound:** card-banner ≠ select-banner + clean static render → optimistic upper
  bound, stated in §2 + the report header.
- **Coverage:** 98/113 (15 newer heroes have no render) — report lists them as "not covered here".
- **Broadcast window (not memory):** chunk per locale (~98/broadcast) because of the `goAsync()` ANR
  window (B1/N1); memory is fine (one bitmap at a time, recycled).
- **Resolved decisions:** (a) `FLOOR=0.85` **per-locale** (Layer-1 N2); (b) **whole-card** OCR
  (§4.4); (c) fetch = **`bgtiers` subcommand** reusing `fetch_url`+`MockTransport` (N4); (d) renders
  live in `build/card-renders/` — **already gitignored** by the root `build/` rule (N5); commit only
  `REPORT.md` + `missing.json`; (e) **512x** renders (B3); (f) harness **exit 0** unless `--strict`
  (N6); requires `SERIAL=` (N7).

---
*Next: Codex review → plan → impl (fetch + harness + report, TDD the fetch/report logic) → review → run once + commit REPORT.*
