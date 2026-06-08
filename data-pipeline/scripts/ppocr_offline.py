#!/usr/bin/env python3
"""Stage-0 offline OCR bake-off: run a PaddleOCR-family engine (RapidOCR/onnxruntime) on the SAME
card-render corpus ML Kit scored (.94/.40/.26) and report per-locale raw-OCR accuracy vs ground truth.

The hero name is small stylized text on an ornate banner; full-card DBNet detection misses/splits it,
so we crop the known name band and run REC-ONLY — isolating recognition (the engine choice) from
detection, and mirroring the on-device plan (rec on a known name region).

Two metrics per locale:
- exact: normalized read == normalized truth (strict; sensitive to banner-decoration crop junk).
- match@cap: Levenshtein(read, truth) <= max(1, floor(FUZZY_RATIO*len)) after edge-noise strip —
  mirrors the runtime HeroMatcher (with the min-1 cap fix), so it's comparable to ML Kit's
  through-matcher baseline.

Usage (no install needed):
  uv run --no-project --with rapidocr --with pillow --python 3.12 python scripts/ppocr_offline.py \
      [--version PP-OCRv5|PP-OCRv4] [--rec-lang ch|chinese_cht] \
      [--out ../android/overlay-app/recordings/ocr-corpus/PPOCRV5-OFFLINE.md]
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import unicodedata
from pathlib import Path

import numpy as np
from PIL import Image

LOCALES = ["enUS", "zhCN", "zhTW"]
MLKIT_BASELINE = {"enUS": 0.939, "zhCN": 0.398, "zhTW": 0.255}  # Layer-2 emulator card-render run
BAND = (0.10, 0.50, 0.90, 0.605)   # x0,y0,x1,y1 fractions of the card render — the name banner
UPSCALE = 3
FUZZY_RATIO = 0.2                   # matches HeroMatcher.fuzzyRatio
EDGE = "|丨｜ \t\r\n"               # HeroMatcher.stripEdgeNoise chars (+ whitespace)
DOTS = "·‧・⋅•∙‐"                   # middle-dot / separator variants → unify
BRACKETS = "『』「」【】〔〕[]（）()《》"  # CJK/ASCII bracket variants → drop
FOLD = "none"                       # none | punct | tcsc  (set from --fold)
_CC = None                          # lazy OpenCC t2s converter


def _tcsc(s: str) -> str:
    global _CC
    if _CC is None:
        from opencc import OpenCC
        _CC = OpenCC("t2s")
    return _CC.convert(s)


def norm(s: str) -> str:
    """NFKC + casefold + collapse whitespace; --fold adds punct/TC→SC folding (both sides)."""
    s = unicodedata.normalize("NFKC", s).casefold()
    if FOLD in ("punct", "tcsc"):
        s = "".join("·" if c in DOTS else "" if c in BRACKETS else c for c in s)
    if FOLD == "tcsc":
        s = _tcsc(s)
    return "".join(s.split())


def lev(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def matched(read: str, truth: str) -> tuple[bool, int]:
    """(within matcher tolerance?, edit distance). Edge-strip read, then length-proportional cap."""
    r = norm(read.strip(EDGE))
    t = norm(truth)
    d = lev(r, t)
    cap = max(1, math.floor(FUZZY_RATIO * len(t)))
    return d <= cap, d


def load_truth(asset: Path) -> dict[str, dict[str, str]]:
    d = json.loads(asset.read_text(encoding="utf-8"))
    return {h["cardId"]: h["names"] for h in d["heroes"]}


def build_engine(version: str, rec_lang: str):
    from rapidocr import RapidOCR, OCRVersion, LangRec
    ver = OCRVersion.PPOCRV5 if version == "PP-OCRv5" else OCRVersion.PPOCRV4
    return RapidOCR(params={
        "Det.ocr_version": ver,
        "Rec.ocr_version": ver,
        "Rec.lang_type": LangRec(rec_lang),
    })


def band_crop(png: Path) -> np.ndarray:
    im = Image.open(png).convert("RGB")
    w, h = im.size
    x0, y0, x1, y1 = BAND
    c = im.crop((int(x0 * w), int(y0 * h), int(x1 * w), int(y1 * h)))
    c = c.resize((c.width * UPSCALE, c.height * UPSCALE))
    return np.array(c)


def best_read(engine, png: Path) -> str:
    """rec-only on the band crop → recognized text ('' if nothing)."""
    res = engine(band_crop(png), use_det=False, use_cls=False, use_rec=True)
    txts = getattr(res, "txts", None)
    return txts[0] if txts else ""


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--renders", default="build/card-renders")
    ap.add_argument("--asset", default="../android/overlay-app/app/src/main/assets/herotier_v1.json")
    ap.add_argument("--version", default="PP-OCRv5", choices=["PP-OCRv5", "PP-OCRv4"])
    ap.add_argument("--rec-lang", default="ch")
    ap.add_argument("--fold", default="none", choices=["none", "punct", "tcsc"])
    ap.add_argument("--out", default="../android/overlay-app/recordings/ocr-corpus/PPOCRV5-OFFLINE.md")
    args = ap.parse_args(argv)
    global FOLD
    FOLD = args.fold

    truth = load_truth(Path(args.asset))
    engine = build_engine(args.version, args.rec_lang)
    renders = Path(args.renders)
    label = f"{args.version} rec ({args.rec_lang}), rec-only on name band, fold={args.fold}"

    per = {loc: {"exact": 0, "match": 0, "total": 0, "miss": []} for loc in LOCALES}
    for loc in LOCALES:
        for png in sorted(renders.glob(f"*__{loc}.png")):
            card = png.name.split("__", 1)[0]
            gt = truth.get(card, {}).get(loc)
            if not gt:
                continue
            read = best_read(engine, png)
            ok, dist = matched(read, gt)
            d = per[loc]
            d["total"] += 1
            d["exact"] += int(norm(read) == norm(gt))
            d["match"] += int(ok)
            if not ok:
                d["miss"].append((card, gt, read, dist))
            print(f"  {loc} {card}: d={dist} read={read!r} gt={gt!r}", file=sys.stderr)

    L = [f"# Offline OCR bake-off — {label}\n",
         "Raw-OCR on the **same card-render corpus** ML Kit ran on (Stage 0, offline onnxruntime — no "
         "device). rec-only on the cropped name band isolates recognition from detection. "
         "**match@cap** mirrors the runtime fuzzy matcher (edge-strip + `max(1,⌊0.2·len⌋)` cap), so it "
         "is comparable to the ML Kit through-matcher baseline; **exact** is stricter (sensitive to "
         "banner-decoration crop junk, a render artifact absent on the real select screen).\n",
         "\n| locale | match@cap | exact | total | match-rate | exact-rate | ML Kit baseline |",
         "|---|---|---|---|---|---|---|"]
    for loc in LOCALES:
        d = per[loc]
        mr = d["match"] / d["total"] if d["total"] else 0.0
        er = d["exact"] / d["total"] if d["total"] else 0.0
        L.append(f"| {loc} | {d['match']} | {d['exact']} | {d['total']} | {mr:.3f} | {er:.3f} | "
                 f"{MLKIT_BASELINE[loc]:.3f} |")
    for loc in LOCALES:
        miss = per[loc]["miss"]
        if not miss:
            continue
        L.append(f"\n## {loc} misses — beyond fuzzy cap ({len(miss)})\n")
        L.append("| cardId | ground truth | OCR read | edit dist |\n|---|---|---|---|")
        for card, gt, read, dist in sorted(miss, key=lambda x: -x[3]):
            L.append(f"| {card} | {gt} | {read or '∅'} | {dist} |")

    outp = Path(args.out)
    outp.parent.mkdir(parents=True, exist_ok=True)
    outp.write_text("\n".join(L) + "\n", encoding="utf-8")

    print(f"\n=== offline bake-off: {label} ===")
    for loc in LOCALES:
        d = per[loc]
        mr = d["match"] / d["total"] if d["total"] else 0.0
        er = d["exact"] / d["total"] if d["total"] else 0.0
        print(f"  {loc}: match {d['match']}/{d['total']}={mr:.3f}  exact={er:.3f}  "
              f"(ML Kit {MLKIT_BASELINE[loc]:.3f})")
    print(f"wrote {outp}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
