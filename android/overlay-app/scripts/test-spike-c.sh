#!/usr/bin/env bash
# Spike C end-to-end:
#   - start Bob VPN + HS (reuses logic from test-spike-b.sh)
#   - wait for HS to be "logged in" (Unity log signal)
#   - broadcast snapshot → parse JSON → assert ≥1 connection
#   - broadcast kill <id> → assert result=Success
#   - broadcast snapshot → assert id no longer present
#   - broadcast kill <fake-id> → assert result=NotFound
#
# Result: prints PASS/FAIL per assertion, exits 0 iff all pass.
set -uo pipefail

BOB_PKG=com.bobassist.phase0
HS_PKG=com.blizzard.wtcg.hearthstone
OUT_DIR=/tmp/spike-c
mkdir -p "$OUT_DIR"

REBUILD=0
HS_WAIT=60
while [[ $# -gt 0 ]]; do
    case "$1" in
        --rebuild) REBUILD=1; shift ;;
        --hs-wait) HS_WAIT="$2"; shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

cd "$(dirname "$0")/.."

PASS=0; FAIL=0
ok()  { echo "PASS: $*"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $*"; FAIL=$((FAIL+1)); }

# Need jq for JSON parsing.
if ! command -v jq >/dev/null; then
    echo "FATAL: jq not on PATH. brew install jq"; exit 1
fi

if [[ "$REBUILD" -eq 1 ]]; then
    echo "[1/9] Rebuild bobcore + APK"
    ( cd ../bobcore && ./build-aar.sh >/dev/null )
    ./gradlew :app:assembleDebug -q >/dev/null
    adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
fi

echo "[2/9] Force-stop both apps"
adb shell am force-stop "$BOB_PKG"
adb shell am force-stop "$HS_PKG"
adb logcat -c

echo "[3/9] Launch MainActivity with auto-start"
adb shell am start -n "$BOB_PKG/.MainActivity" --ez auto_start true >/dev/null
sleep 4

echo "[4/9] Launch HS"
adb shell monkey -p "$HS_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "[5/9] Wait up to ${HS_WAIT}s for Unity 'logged in' signal"
DEADLINE=$(( $(date +%s) + HS_WAIT ))
LOGGED_IN=0
while [[ $(date +%s) -lt $DEADLINE ]]; do
    if adb logcat -d -s Unity:I 2>/dev/null | grep -q "We are now logged in"; then
        LOGGED_IN=1; break
    fi
    sleep 3
done
if [[ $LOGGED_IN -eq 1 ]]; then ok "HS logged in"; else fail "HS not logged in within ${HS_WAIT}s"; fi

echo "[6/9] Broadcast snapshot"
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd snapshot >/dev/null
sleep 1
SNAP_LINE=$(adb logcat -d -s SpikeC:I 2>/dev/null | grep -E "snapshot=" | tail -1)
echo "$SNAP_LINE" > "$OUT_DIR/snap1.log"
SNAP_JSON=$(echo "$SNAP_LINE" | sed -E 's/.*snapshot=//')
if [[ -z "$SNAP_JSON" || "$SNAP_JSON" == "[]" ]]; then
    fail "snapshot returned empty"
    CONN_COUNT=0
else
    CONN_COUNT=$(echo "$SNAP_JSON" | jq '. | length')
    if [[ "$CONN_COUNT" -ge 1 ]]; then ok "snapshot has $CONN_COUNT connection(s)"; else fail "snapshot has 0 connections"; fi
fi

if [[ "$CONN_COUNT" -ge 1 ]]; then
    PICK_ID=$(echo "$SNAP_JSON" | jq -r '.[0].id')
    PICK_HOST=$(echo "$SNAP_JSON" | jq -r '.[0].host')
    PICK_DST=$(echo "$SNAP_JSON" | jq -r '.[0].destinationIp + ":" + (.[0].destinationPort|tostring)')
    echo "  picked id=$PICK_ID  host=$PICK_HOST  dst=$PICK_DST"

    echo "[7/9] Broadcast kill $PICK_ID"
    adb logcat -c
    adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd kill --es id "$PICK_ID" >/dev/null
    sleep 1
    KILL_LINE=$(adb logcat -d -s SpikeC:I 2>/dev/null | grep -E "kill id=" | tail -1)
    echo "$KILL_LINE" > "$OUT_DIR/kill.log"
    if echo "$KILL_LINE" | grep -q "result=Success"; then ok "kill returned Success"; else fail "kill: $KILL_LINE"; fi

    echo "[8/9] Snapshot again — id should be gone"
    adb logcat -c
    adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd snapshot >/dev/null
    sleep 1
    SNAP2_JSON=$(adb logcat -d -s SpikeC:I 2>/dev/null | grep -E "snapshot=" | tail -1 | sed -E 's/.*snapshot=//')
    echo "$SNAP2_JSON" > "$OUT_DIR/snap2.log"
    if [[ -n "$SNAP2_JSON" ]] && ! echo "$SNAP2_JSON" | jq -e --arg id "$PICK_ID" '.[] | select(.id == $id)' >/dev/null; then
        ok "killed id no longer in snapshot"
    else
        fail "killed id still present"
    fi
fi

echo "[9/9] Kill unknown id → expect NotFound"
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd kill --es id "00000000-0000-0000-0000-000000000000" >/dev/null
sleep 1
NF_LINE=$(adb logcat -d -s SpikeC:I 2>/dev/null | grep -E "kill id=" | tail -1)
if echo "$NF_LINE" | grep -q "result=NotFound"; then ok "unknown id returns NotFound"; else fail "unknown id: $NF_LINE"; fi

echo
echo "=== summary: pass=$PASS fail=$FAIL ==="
exit $(( FAIL > 0 ? 1 : 0 ))
