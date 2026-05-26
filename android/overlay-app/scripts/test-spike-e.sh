#!/usr/bin/env bash
# Spike E end-to-end: kill_battle skips HS BG animation.
#
# Human-in-the-loop because there is no programmatic way to drive HS into a
# combat round. The script wraps every other piece in automation:
#   - start Bob VPN + HS (reuses Spike B flow)
#   - start adb screen recording
#   - wait for user to say "ready" (enter combat animation)
#   - snapshot, assert ≥1 candidate (codex Scenario-4 c)
#   - broadcast kill_battle, assert Success (a)
#   - snapshot, assert killed id is gone (b)
#   - prompt user to confirm whether HS skipped animation (d/e)
#   - stop screen recording, pull to /tmp/spike-e/ (g)
#   - prompt user whether next BG round playable (f)
set -uo pipefail

BOB_PKG=com.bobassist.phase0
HS_PKG=com.blizzard.wtcg.hearthstone
OUT_DIR=/tmp/spike-e
DEVICE_RECORDING=/sdcard/spike-e.mp4
mkdir -p "$OUT_DIR"

cd "$(dirname "$0")/.."

PASS=0; FAIL=0
ok()  { echo "PASS: $*"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL+1)); }

if ! command -v jq >/dev/null; then echo "FATAL: jq required"; exit 1; fi

wait_for_log() {
    local tag="$1" pat="$2" timeout="$3"
    local deadline=$(( $(date +%s) + timeout ))
    while [[ $(date +%s) -lt $deadline ]]; do
        local line
        line=$(adb logcat -d -s "$tag" 2>/dev/null | grep -E "$pat" | tail -1)
        if [[ -n "$line" ]]; then echo "$line"; return 0; fi
        sleep 0.5
    done
    return 1
}

REBUILD=0
[[ "${1:-}" == "--rebuild" ]] && REBUILD=1

if [[ "$REBUILD" -eq 1 ]]; then
    echo "[1] Rebuild bobcore + APK"
    ( cd ../bobcore && ./build-aar.sh >/dev/null )
    ./gradlew :app:assembleDebug -q >/dev/null
    adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
fi

echo "[2] Force-stop both apps, clear logcat"
adb shell am force-stop "$BOB_PKG"
adb shell am force-stop "$HS_PKG"
adb shell appops set "$BOB_PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>/dev/null || echo "[warn] could not appops-set SAW (OEM restriction?); requires manual grant once via Settings"
adb shell appops set "$BOB_PKG" android:get_usage_stats allow >/dev/null 2>/dev/null || echo "[warn] could not appops-set android:get_usage_stats (OEM restriction?); requires manual grant once via Settings"
adb shell rm -f "$DEVICE_RECORDING"
adb logcat -c

echo "[3] Launch Bob VPN (auto_start) + HS"
adb shell am start -n "$BOB_PKG/.MainActivity" --ez auto_start true >/dev/null
sleep 4
adb shell monkey -p "$HS_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "[4] Wait up to 90s for HS 'logged in'"
if wait_for_log "Unity:I" "We are now logged in" 90 >/dev/null; then
    ok "HS logged in"
else
    fail "HS not logged in"; echo "summary: pass=$PASS fail=$FAIL"; exit 1
fi

echo
echo "================================================================"
echo "  HUMAN ACTION: enter BG mode, pick hero, get into combat round 1."
echo "  When the battle animation STARTS, type 'go' and press Enter."
echo "================================================================"
read -r -p "  > " sig
[[ "$sig" == "go" ]] || { echo "aborted"; exit 1; }

echo "[5] Start screen recording (180s max)"
adb shell "screenrecord --time-limit 180 $DEVICE_RECORDING" &
SR_PID=$!
sleep 2  # let screenrecord warm up

echo "[6] Pre-kill snapshot — assert ≥1 candidate"
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd snapshot >/dev/null
PRE_LINE=$(wait_for_log "SpikeC:I" "snapshot=" 5)
PRE_JSON=$(echo "$PRE_LINE" | sed -E 's/.*snapshot=//')
echo "$PRE_JSON" > "$OUT_DIR/pre.json"
PRE_HITS=$(echo "$PRE_JSON" | jq '[.[] | select(.host=="" and .network=="tcp" and .destinationPort==3724)] | length' 2>/dev/null || echo 0)
if [[ "$PRE_HITS" -ge 1 ]]; then
    ok "pre-kill candidates n=$PRE_HITS"
    PICK_ID=$(echo "$PRE_JSON" | jq -r '[.[] | select(.host=="" and .network=="tcp" and .destinationPort==3724)] | max_by(.createdAt) | .id')
    echo "  expected kill id: $PICK_ID"
else
    fail "no battle socket candidate present at kill time"
    PICK_ID=""
fi

echo "[7] Broadcast kill_battle"
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd kill_battle >/dev/null
KILL_LINE=$(wait_for_log "SpikeC:I" "kill_battle " 5)
echo "$KILL_LINE" > "$OUT_DIR/kill.log"
echo "  $KILL_LINE"
if echo "$KILL_LINE" | grep -q "result=Success"; then ok "kill_battle Success"; else fail "kill_battle: $KILL_LINE"; fi
ACTUAL_ID=$(echo "$KILL_LINE" | sed -E 's/.* id=([^ ]+) .*/\1/')

echo "[8] Post-kill snapshot — assert killed id is gone"
sleep 1
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd snapshot >/dev/null
POST_LINE=$(wait_for_log "SpikeC:I" "snapshot=" 5)
POST_JSON=$(echo "$POST_LINE" | sed -E 's/.*snapshot=//')
echo "$POST_JSON" > "$OUT_DIR/post.json"
if [[ -n "$ACTUAL_ID" ]] && ! echo "$POST_JSON" | jq -e --arg id "$ACTUAL_ID" '.[] | select(.id == $id)' >/dev/null 2>&1; then
    ok "killed id gone from post-kill snapshot"
else
    fail "killed id still present in post-kill snapshot"
fi

echo
echo "================================================================"
echo "  HUMAN ACTION: did HS skip the animation and show the result?"
echo "  Answer 'yes' if HS jumped to results without re-login,"
echo "  'no' otherwise."
echo "================================================================"
read -r -p "  > " ans
case "$ans" in
    yes) ok "user-confirmed: HS skipped to results, no re-login" ;;
    *)   fail "user-confirmed: HS did NOT skip cleanly" ;;
esac

echo
echo "================================================================"
echo "  HUMAN ACTION: try a next BG round. Is it playable without restart?"
echo "  Answer 'yes' / 'no'."
echo "================================================================"
read -r -p "  > " ans2
case "$ans2" in
    yes) ok "user-confirmed: next round playable" ;;
    *)   fail "user-confirmed: next round broken" ;;
esac

echo "[9] Stop screen recording + pull"
adb shell pkill -SIGINT screenrecord 2>/dev/null || true
wait "$SR_PID" 2>/dev/null || true
sleep 1
adb pull "$DEVICE_RECORDING" "$OUT_DIR/spike-e.mp4" 2>&1 | tail -2
adb shell rm -f "$DEVICE_RECORDING"

echo
echo "=== artifacts ==="
ls -lh "$OUT_DIR"/
echo
echo "=== summary: pass=$PASS fail=$FAIL ==="
exit $(( FAIL > 0 ? 1 : 0 ))
