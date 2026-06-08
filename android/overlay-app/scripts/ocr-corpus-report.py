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
        seen = set()
        for fname, matched, texts in parse_log(out / f"{loc}.log"):
            if fname in seen:
                continue  # dedup: a file logged twice (e.g. a 2nd OCR engine) must not double-count
            seen.add(fname)
            stem = fname.split("__", 1)[0]
            per[loc]["total"] += 1
            if stem in matched:
                per[loc]["ok"] += 1
            else:
                per[loc]["fail"].append((stem, matched, texts))
    # Per-locale totals should be equal (same heroes pushed); a mismatch means dropped/truncated
    # logcat lines silently shrank a denominator -> the rate would be misleading.
    nonzero = {loc: per[loc]["total"] for loc in LOCALES if per[loc]["total"]}
    total_warn = (f"WARNING: per-locale totals differ {nonzero} — dropped/truncated logcat lines? "
                  "rates may be unreliable." if len(set(nonzero.values())) > 1 else "")

    missing = []
    mfile = out / "missing.json"
    if mfile.exists():
        missing = json.loads(mfile.read_text(encoding="utf-8"))

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    L = []
    L.append("# OCR card-corpus report (Layer 2)\n")
    L.append("Per-locale OCR→match on clean HearthstoneJSON **512x card renders** (card-name-banner "
             "font), via the app's ML Kit on an **emulator**. A PROXY with locale-dependent bias:\n")
    L.append("- **enUS (Latin) ≈ representative.**\n")
    L.append("- **zhCN/zhTW (CJK) are LOW — and this is NOT primarily a band-size artifact** (a "
             "tested hypothesis that failed): cropping+upscaling 10 failing zhTW names to large, "
             "clearly-legible text recovered only ~2/10 (e.g. 餅乾大廚→餅敦大廚, 鉤牙船長→釣牙船長 — "
             "one wrong glyph even on big crisp text). Two **measured** causes: (1) ML Kit CJK "
             "recognition quality **on this emulator (SwiftShader)** — may differ on a real device, "
             "**unverified**; (2) the matcher's fuzzy cap `floor(0.2·len)` is **0 for ≤4-char names** "
             "(the most common CJK hero-name length), so a single mis-glyph is unrecoverable.\n")
    L.append("- **Don't over-read these:** a single real zhTW select frame matched 4/4, but one "
             "anecdote can't make this corpus a 'lower/upper bound'. To quantify real CJK accuracy: "
             "re-run on a **real device** + accumulate real select frames. The short-name zero-cap is "
             "a separate matcher tunable (out of this measure-only layer's scope).\n")
    L.append("15 newer BG heroes have no render (listed at bottom) — not covered here.\n")
    if total_warn:
        L.append(f"\n> ⚠ {total_warn}\n")
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

    if total_warn:
        print(total_warn)
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
