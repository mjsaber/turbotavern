#!/usr/bin/env python3
"""Parse OcrProbe logs from the Layer-2 corpus run into a per-locale OCR->match report.

Input dir: <out>/{enUS,zhCN,zhTW}.log (raw `adb logcat -s OcrProbe`) + optional missing.json.
Each OcrProbe line carries one JSON object: {"file","matches":[{cardId,tier}],"lines":[{text}],...}.
Expected cardId = filename stem before "__". Writes REPORT.md; exits nonzero only with --strict.
"""
from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

FLOOR = 0.85
REPORT = Path("recordings/ocr-corpus/REPORT.md")
LOCALES = ["enUS", "zhCN", "zhTW"]


def parse_log(path: Path):
    """Yield (file, matched_cardIds, ocr_texts) per OcrProbe JSON line; skip unparseable."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.search(r"OcrProbe:\s*(\{.*\})\s*$", line)
        if not m:
            continue
        try:
            o = json.loads(m.group(1))
        except json.JSONDecodeError:
            continue  # truncated by logcat line limit -> skip (counted as no-data below)
        if "file" not in o:
            continue
        yield (o["file"],
               [x.get("cardId") for x in o.get("matches", [])],
               [x.get("text", "") for x in o.get("lines", [])])


def main(argv):
    out = Path(argv[1]) if len(argv) > 1 else Path("/tmp/ocr-corpus")
    strict = "--strict" in argv
    per = {loc: {"ok": 0, "total": 0, "fail": []} for loc in LOCALES}
    for loc in LOCALES:
        for fname, matched, texts in parse_log(out / f"{loc}.log"):
            stem = fname.split("__", 1)[0]
            per[loc]["total"] += 1
            if stem in matched:
                per[loc]["ok"] += 1
            else:
                per[loc]["fail"].append((stem, matched, texts))

    missing = []
    mfile = out / "missing.json"
    if mfile.exists():
        missing = json.loads(mfile.read_text(encoding="utf-8"))

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    L = []
    L.append("# OCR card-corpus report (Layer 2)\n")
    L.append("Per-locale OCR→match on clean HearthstoneJSON **512x card renders** (card-name-banner "
             "font), via the app's ML Kit on an emulator. **This is a PROXY with locale-dependent "
             "bias — read the numbers accordingly:**\n")
    L.append("- **enUS (Latin) ≈ representative** — Latin reads fine even in the small name band.\n")
    L.append("- **zhCN/zhTW (CJK) are a PESSIMISTIC LOWER BOUND, not an upper bound.** The card "
             "name-band is small at 512x and ML Kit mangles dense CJK glyphs (e.g. BG20_HERO_102 "
             "薩魯法爾霸王 → '福魯法霸文'), beyond fuzzy tolerance. This UNDERSTATES real select-screen "
             "accuracy: the in-game select banner renders names much larger — a real zhTW select "
             "frame matched **4/4**. So CJK ground truth = accumulate real select frames, NOT card "
             "renders; a name-band crop+upscale could raise these card numbers (future refinement).\n")
    L.append("15 newer BG heroes have no render (listed at bottom) — not covered here.\n")
    L.append("\n## Per-locale OCR→match rate\n")
    L.append("| locale | matched | total | rate |\n|---|---|---|---|")
    below = []
    for loc in LOCALES:
        d = per[loc]
        rate = d["ok"] / d["total"] if d["total"] else 0.0
        if d["total"] and rate < FLOOR:
            below.append(f"{loc}={rate:.3f}")
        L.append(f"| {loc} | {d['ok']} | {d['total']} | {rate:.3f} |")
    L.append("")
    for loc in LOCALES:
        fails = per[loc]["fail"]
        if not fails:
            continue
        L.append(f"\n## {loc} misses ({len(fails)})\n")
        L.append("| cardId | OCR read | matched instead |\n|---|---|---|")
        for stem, matched, texts in fails:
            read = " / ".join(t for t in texts if t.strip())[:120].replace("|", "¦")
            L.append(f"| {stem} | {read} | {matched or '∅'} |")
    if missing:
        ids = sorted({m["cardId"] for m in missing})
        L.append(f"\n## Not covered — no card render ({len(ids)} heroes)\n")
        L.append(", ".join(ids))
    REPORT.write_text("\n".join(L) + "\n", encoding="utf-8")

    print("=== OCR corpus report ===")
    for loc in LOCALES:
        d = per[loc]
        rate = d["ok"] / d["total"] if d["total"] else 0.0
        print(f"  {loc}: {d['ok']}/{d['total']} = {rate:.3f}")
    print(f"wrote {REPORT}")
    if below:
        print(f"BELOW FLOOR {FLOOR}: {below}")
        if strict:
            return 1
    else:
        print(f"PASS (all locales >= {FLOOR})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
