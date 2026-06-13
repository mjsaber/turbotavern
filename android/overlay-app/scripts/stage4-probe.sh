#!/usr/bin/env bash
# Stage-4 on-device OCR A/B: push real hero-select frame(s) through the OcrProbe receiver, which runs
# BOTH "mlkit" and "ppocr" (PP-OCRv5) and logs per-(image,engine) reads+matches. Validates the
# PaddleHeroOcr Android glue (Bitmap+ORT) against the offline Python reference.
#
# Usage: SERIAL=<serial> scripts/stage4-probe.sh [frame.png ...]   (defaults to recordings/WechatIMG48.jpg)
set -uo pipefail
BOB=com.turbotavern.full
BOB_NS=com.turbotavern
DEV="${SERIAL:+-s $SERIAL}"
cd "$(dirname "$0")/.."
PROBE="/sdcard/Android/data/$BOB/files/probe"
FRAMES=("$@"); [[ ${#FRAMES[@]} -eq 0 ]] && FRAMES=(recordings/WechatIMG48.jpg)

# background the app so OcrProbe's goAsync() gets the long (~60s) window
adb $DEV shell am start -n "$BOB/$BOB_NS.MainActivity" >/dev/null 2>&1 || { echo "install debug APK first"; exit 1; }
sleep 1; adb $DEV shell input keyevent KEYCODE_HOME >/dev/null 2>&1; sleep 1

# let the app create the probe dir app-owned, then push frames into it
adb $DEV shell "rm -rf $PROBE" 2>/dev/null
adb $DEV shell am broadcast -a "$BOB_NS.OCR_PROBE" -p "$BOB" >/dev/null
for i in $(seq 15); do
  adb $DEV logcat -d -s OcrProbe 2>/dev/null | grep -qE 'no PNGs|probe done' && break; sleep 1
done
for f in "${FRAMES[@]}"; do adb $DEV push "$f" "$PROBE/" >/dev/null; echo "pushed $f"; done

adb $DEV logcat -c
adb $DEV shell am broadcast -a "$BOB_NS.OCR_PROBE" -p "$BOB" >/dev/null
echo "OCR running (mlkit + ppocr)..."
for i in $(seq 120); do
  adb $DEV logcat -d -s OcrProbe 2>/dev/null | grep -q 'probe done' && break; sleep 1
done
adb $DEV logcat -d -s OcrProbe 2>/dev/null
