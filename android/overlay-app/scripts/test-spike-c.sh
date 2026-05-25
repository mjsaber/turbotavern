#!/usr/bin/env bash
# Spike C end-to-end:
#   - start Bob VPN + HS (reuses logic from test-spike-b.sh)
#   - wait for HS "logged in" (Unity log signal)
#   - broadcast snapshot → assert ≥1 conn
#   - broadcast kill <id> → assert Success
#   - broadcast snapshot → assert id gone
#   - broadcast kill <fake-id> → assert NotFound
#   - broadcast stop_core → broadcast kill <any> → assert CoreStopped
#
# Exits 0 iff all assertions pass.
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

if ! command -v jq >/dev/null; then
    echo "FATAL: jq not on PATH. brew install jq"; exit 1
fi

# wait_for_log <tag:level> <pattern> <timeout_sec>
# Polls `adb logcat -d -s tag` until pattern matches; echoes matching line.
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

if [[ "$REBUILD" -eq 1 ]]; then
    echo "[1/10] Rebuild bobcore + APK"
    ( cd ../bobcore && ./build-aar.sh >/dev/null )
    ./gradlew :app:assembleDebug -q >/dev/null
    adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
fi

echo "[2/10] Force-stop both apps"
adb shell am force-stop "$BOB_PKG"
adb shell am force-stop "$HS_PKG"
adb logcat -c

echo "[3/10] Launch MainActivity with auto-start"
adb shell am start -n "$BOB_PKG/.MainActivity" --ez auto_start true >/dev/null
sleep 4

echo "[4/10] Launch HS"
adb shell monkey -p "$HS_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo "[5/10] Wait up to ${HS_WAIT}s for Unity 'logged in'"
if wait_for_log "Unity:I" "We are now logged in" "$HS_WAIT" >/dev/null; then
    ok "HS logged in"
else
    fail "HS not logged in within ${HS_WAIT}s"
    echo "=== summary: pass=$PASS fail=$FAIL ==="; exit 1
fi

echo "[6/10] Broadcast snapshot"
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd snapshot >/dev/null
SNAP_LINE=$(wait_for_log "SpikeC:I" "snapshot=" 5)
[[ -n "$SNAP_LINE" ]] || { fail "no snapshot log"; SNAP_LINE=""; }
echo "$SNAP_LINE" > "$OUT_DIR/snap1.log"
SNAP_JSON=$(echo "$SNAP_LINE" | sed -E 's/.*snapshot=//')
CONN_COUNT=$(echo "$SNAP_JSON" | jq -e '. | length' 2>/dev/null || echo 0)
if [[ "$CONN_COUNT" -ge 1 ]]; then
    ok "snapshot has $CONN_COUNT connection(s)"
else
    fail "snapshot has 0 connections"
fi

PICK_ID=""
if [[ "$CONN_COUNT" -ge 1 ]]; then
    PICK_ID=$(echo "$SNAP_JSON" | jq -r '.[0].id')
    PICK_HOST=$(echo "$SNAP_JSON" | jq -r '.[0].host')
    PICK_DST=$(echo "$SNAP_JSON" | jq -r '.[0].destinationIp + ":" + (.[0].destinationPort|tostring)')
    echo "  picked id=$PICK_ID  host=$PICK_HOST  dst=$PICK_DST"

    echo "[7/10] Broadcast kill $PICK_ID → expect Success"
    adb logcat -c
    adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd kill --es id "$PICK_ID" >/dev/null
    KILL_LINE=$(wait_for_log "SpikeC:I" "kill id=" 5)
    echo "$KILL_LINE" > "$OUT_DIR/kill.log"
    if echo "$KILL_LINE" | grep -q "result=Success"; then
        ok "kill returned Success"
    else
        fail "kill: $KILL_LINE"
    fi

    echo "[8/10] Snapshot again — id should be gone"
    adb logcat -c
    adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd snapshot >/dev/null
    SNAP2_LINE=$(wait_for_log "SpikeC:I" "snapshot=" 5)
    SNAP2_JSON=$(echo "$SNAP2_LINE" | sed -E 's/.*snapshot=//')
    echo "$SNAP2_JSON" > "$OUT_DIR/snap2.log"
    if [[ -n "$SNAP2_JSON" ]] && ! echo "$SNAP2_JSON" | jq -e --arg id "$PICK_ID" '.[] | select(.id == $id)' >/dev/null; then
        ok "killed id no longer in snapshot"
    else
        fail "killed id still present"
    fi
fi

echo "[9/10] Kill unknown id → expect NotFound"
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd kill --es id "00000000-0000-0000-0000-000000000000" >/dev/null
NF_LINE=$(wait_for_log "SpikeC:I" "kill id=" 5)
if echo "$NF_LINE" | grep -q "result=NotFound"; then
    ok "unknown id returns NotFound"
else
    fail "unknown id: $NF_LINE"
fi

echo "[10/10] stop_core then kill → expect CoreStopped"
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd stop_core >/dev/null
STOP_LINE=$(wait_for_log "SpikeC:I" "stop_core result=" 5)
echo "  stop_core: $STOP_LINE"
adb logcat -c
adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd kill --es id "deadbeef-dead-dead-dead-deaddeaddead" >/dev/null
CS_LINE=$(wait_for_log "SpikeC:I" "kill id=" 5)
if echo "$CS_LINE" | grep -q "result=CoreStopped"; then
    ok "kill after stop_core returns CoreStopped"
else
    fail "after stop_core: $CS_LINE"
fi

echo
echo "=== summary: pass=$PASS fail=$FAIL ==="
exit $(( FAIL > 0 ? 1 : 0 ))
