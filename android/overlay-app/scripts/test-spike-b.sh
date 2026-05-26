#!/usr/bin/env bash
# Spike B end-to-end test:
#   - force-stop Bob + HS
#   - rebuild + reinstall (optional, with --rebuild)
#   - start Bob VPN via ADB
#   - launch HS
#   - capture logcat for SECS seconds
#   - dump filtered + raw logs to /tmp/spike-b/
#
# Usage:
#   ./test-spike-b.sh              # use already-installed APK
#   ./test-spike-b.sh --rebuild    # build + install first
#   ./test-spike-b.sh --secs 90    # different capture window
set -euo pipefail

BOB_PKG=com.bobassist.phase0
HS_PKG=com.blizzard.wtcg.hearthstone
OUT_DIR=/tmp/spike-b
mkdir -p "$OUT_DIR"

REBUILD=0
SECS=60
while [[ $# -gt 0 ]]; do
    case "$1" in
        --rebuild) REBUILD=1; shift ;;
        --secs) SECS="$2"; shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

if [[ "$REBUILD" -eq 1 ]]; then
    echo "[1/8] Rebuild bobcore.aar"
    ( cd "$(dirname "$0")/../../bobcore" && ./build-aar.sh >/dev/null )
    echo "[2/8] Rebuild APK"
    ( cd "$(dirname "$0")/.." && ./gradlew :app:assembleDebug -q >/dev/null )
    echo "[3/8] Reinstall APK"
    adb install -r "$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk" >/dev/null
fi

echo "[4/8] Force-stop both apps + reset breadcrumb"
adb shell am force-stop "$BOB_PKG"
adb shell am force-stop "$HS_PKG"
adb shell appops set "$BOB_PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>/dev/null || echo "[warn] could not appops-set SAW (OEM restriction?); requires manual grant once via Settings"
adb shell run-as "$BOB_PKG" rm -f files/bob-breadcrumbs.log 2>/dev/null || true
adb logcat -c

echo "[5/8] Launch MainActivity with auto-start flag"
adb shell am start -n "$BOB_PKG/.MainActivity" --ez auto_start true >/dev/null
sleep 5

echo "[7/8] Launch HS"
adb shell monkey -p "$HS_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "[8/8] Capture logcat for $SECS seconds"
# Capture in background, kill after timeout
adb logcat -v threadtime >"$OUT_DIR/raw.log" &
LOGCAT_PID=$!
sleep "$SECS"
kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true

# Filter useful subsets
grep -E "BobVpnService|BobPhase0|GoLog|mihomo|Unity" "$OUT_DIR/raw.log" > "$OUT_DIR/filtered.log" || true
grep -E "Unity" "$OUT_DIR/raw.log" > "$OUT_DIR/unity.log" || true
grep -E "GoLog|mihomo" "$OUT_DIR/raw.log" > "$OUT_DIR/mihomo.log" || true
adb shell run-as "$BOB_PKG" cat files/bob-breadcrumbs.log 2>/dev/null > "$OUT_DIR/breadcrumbs.log" || true

echo "Done."
echo "  raw:         $OUT_DIR/raw.log         ($(wc -l < "$OUT_DIR/raw.log") lines)"
echo "  filtered:    $OUT_DIR/filtered.log    ($(wc -l < "$OUT_DIR/filtered.log") lines)"
echo "  unity:       $OUT_DIR/unity.log       ($(wc -l < "$OUT_DIR/unity.log") lines)"
echo "  mihomo:      $OUT_DIR/mihomo.log      ($(wc -l < "$OUT_DIR/mihomo.log") lines)"
echo "  breadcrumbs: $OUT_DIR/breadcrumbs.log ($(wc -l < "$OUT_DIR/breadcrumbs.log") lines)"
