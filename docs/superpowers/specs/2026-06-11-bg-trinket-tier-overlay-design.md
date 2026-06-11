# BG Trinket-Tier Overlay — Design

Status: data + logic layer LANDED (branch `autonomous/three-pillars-hardening`); overlay UX + on-device wiring PENDING a product decision (§4).

The trinket-recommendation pillar mirrors the hero-tier overlay: detect the select screen, OCR the
offered names, look them up in a bundled tri-locale tier table, and draw guidance. The difference is
trinkets are CHOSEN from a small offered set, so the value is "which of these is best", not a per-card
absolute tier.

## 1. What exists today (tested)

- **Data export** (`data-pipeline/src/bgtiers/export_trinkets.py`): per-class (lesser/greater) percentile
  tiers + `avgPlacement`, `_MIN_DATA_POINTS=100` noise floor. 10 pytest cases.
- **Shipped asset** `app/src/main/assets/trinkettier_v1.json`: 209 trinkets, 100% tri-locale (enUS/zhCN/zhTW).
- **Kotlin** (`com.bobassist.phase0.trinket`): `TrinketTable` (class-hint disambiguation of shared
  lesser/greater names, separator-fold aliases), `TrinketMatcher` (mirrors `HeroMatcher`),
  `TrinketRecommender` (ranks the offered set by `avgPlacement`, flags the single best),
  `SelectWindowArbiter` (hero/trinket window mutual exclusion). `TrinketTableGoldenTest` validates all
  209 × 3 locales self-match + dict-decodability + no-wrong on the real asset.

## 2. Recommendation model (decided + implemented)

Rank the 2–3 trinkets ACTUALLY offered against each other by stored `avgPlacement` (lower is better);
highlight the single best. The coarse S/A/B/C tier is shown as secondary context but does NOT decide
the pick (two offered trinkets often share a tier). Intra-offer only — never an absolute verdict. This
is `TrinketRecommender.rank()`; it returns rank 1..N with exactly one `isBest`.

## 3. Detection trigger (decided + implemented)

The select phase has no connection signature (combat is the only socket signature — see
`memory/bg-combat-socket-signature.md`), so a visual probe is the only trigger. `SelectWindowArbiter`
owns two `VisualProbeGate`s (hero + trinket) with a mode guard: at most one window open, no cross-fire.
The trinket gate opens on ≥2 trinket-dictionary matches. Threshold (`shortLen`/`fuzzyCap`/`ambigMargin`)
re-tuning for short zh trinket names is PENDING on-device frames (§5).

## 4. OPEN — overlay UX (needs your pick)

How should the recommendation be drawn over the trinket-offer screen? Options:

- **(A) Highlight-best only.** A green ring / checkmark on the single best offered trinket; nothing on the
  others. Lowest clutter, hardest to argue with, fastest to ship. Matches "tell me what to pick".
- **(B) Best + per-card tier letter.** Ring on the best AND a small S/A/B/C badge on each offered trinket
  (reusing `BadgeLayout`/`BadgeRenderer` from heroes). More info; slightly busier.
- **(C) Ranked numbers.** A 1/2/3 rank chip on each offered trinket. Most explicit ordering; busiest.

Recommendation: **(A)** for v1 (ship the clearest signal), add (B) as a setting later. This choice
determines the badge layout + renderer work; everything upstream is already built and tested.

## 5. Remaining build steps (after §4 is chosen)

1. `TrinketBadgeLayout` — position the highlight (and any tier badges) from the offered OCR boxes
   (reuse hero `BadgeLayout` geometry). Unit-testable.
2. Renderer/overlay view for the chosen UX (reuse `BadgeRenderer`/`BadgeView` where possible).
3. `TrinketCoordinator` — wire OCR → `TrinketMatcher` (with the offer's class hint) → `TrinketRecommender`
   → `SelectWindowArbiter` → overlay, mirroring `HeroTierCoordinator`; the existing MediaProjection
   capture + OCR pipeline is shared. Robolectric coordinator test with fakes.
4. Determine the offer's class (lesser vs greater) per turn — from the screen, or default to trying both
   class hints and taking the unambiguous resolution.
5. On-device tuning: capture real zhTW + enUS trinket-select frames, confirm `PaddleHeroOcr` reads the
   trinket font, tune gate/matcher thresholds under a ZERO-wrong-recommendation gate; add the frames as
   a Layer-2 recall fixture.
6. Refresh the bundled asset on a real patch cadence (shared remote-refresh path with heroes).
