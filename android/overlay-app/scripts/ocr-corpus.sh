#!/usr/bin/env bash
# Layer-2 OCR corpus harness: push the downloaded hero card renders to the device, OCR them via the
# OcrProbe receiver (per-locale chunks — the goAsync() broadcast window can't hold ~98 images if the
# app is foreground; we background the app so the window is ~60s), then build a per-locale report.
#
# Prereqs: debug APK installed; renders fetched (`cd data-pipeline && uv run bgtiers fetch-card-renders`).
# Usage: SERIAL=<serial> scripts/ocr-corpus.sh [--strict]
set -uo pipefail
BOB=com.bobassist.phase0
DEV="${SERIAL:+-s $SERIAL}"
cd "$(dirname "$0")/.."                                   # android/overlay-app
RENDERS="${RENDERS:-../../data-pipeline/build/card-renders}"
PROBE="/sdcard/Android/data/$BOB/files/probe"
OUT=/tmp/ocr-corpus; rm -rf "$OUT"; mkdir -p "$OUT"
LOCALES="enUS zhCN zhTW"

if [[ -z "$DEV" && "$(adb devices | grep -cE '\sdevice$')" -gt 1 ]]; then
  echo "Multiple devices attached — set SERIAL=<serial>"; exit 1
fi
[[ -d "$RENDERS" ]] || { echo "no renders at $RENDERS (run: cd data-pipeline && uv run bgtiers fetch-card-renders)"; exit 1; }

# Un-stop the app (manifest receiver won't fire on a stopped package), then send it to background so
# OcrProbe's goAsync() gets the long (~60s) window.
adb $DEV shell am start -n "$BOB/.MainActivity" >/dev/null 2>&1 || { echo "install debug APK first"; exit 1; }
sleep 1; adb $DEV shell input keyevent KEYCODE_HOME >/dev/null 2>&1; sleep 1

# The probe dir MUST be created by the app (uid u0_aXX), not by `adb shell mkdir` — on the emulator's
# scoped-storage/SELinux, the app can't listFiles() a shell-owned Android/data dir (it works on the
# phone, not here). So: remove any shell-owned probe, then one empty broadcast lets OcrProbe mkdir it
# app-owned; afterwards shell can push files into it (shell is in the ext_data_rw group).
adb $DEV shell "rm -rf $PROBE" 2>/dev/null
adb $DEV logcat -c
adb $DEV shell am broadcast -a "$BOB.OCR_PROBE" -p "$BOB" >/dev/null
for i in $(seq 15); do
  adb $DEV logcat -d -s OcrProbe 2>/dev/null | grep -qE 'no PNGs|probe done' && break; sleep 1
done

for loc in $LOCALES; do
  files=( "$RENDERS"/*__"$loc".png )
  [[ -e "${files[0]}" ]] || { echo "[$loc] no renders, skip"; continue; }
  echo "[$loc] pushing ${#files[@]} renders..."
  adb $DEV shell "rm -f $PROBE/*.png $PROBE/out/* 2>/dev/null"   # clear contents, keep app-owned dir
  adb $DEV push "${files[@]}" "$PROBE/" >/dev/null
  adb $DEV logcat -c
  adb $DEV shell am broadcast -a "$BOB.OCR_PROBE" -p "$BOB" >/dev/null
  echo "[$loc] OCR running..."
  for i in $(seq 240); do
    adb $DEV logcat -d -s OcrProbe 2>/dev/null | grep -q 'probe done' && break
    sleep 1
  done
  adb $DEV logcat -d -s OcrProbe 2>/dev/null > "$OUT/$loc.log"
  n=$(grep -c '"file"' "$OUT/$loc.log" 2>/dev/null || echo 0)
  echo "[$loc] logged $n results"
done
adb $DEV shell "rm -rf $PROBE" 2>/dev/null

cp "$RENDERS/missing.json" "$OUT/missing.json" 2>/dev/null || true
python3 scripts/ocr-corpus-report.py "$OUT" "${1:-}"
