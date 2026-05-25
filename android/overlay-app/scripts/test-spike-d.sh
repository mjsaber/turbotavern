#!/usr/bin/env bash
# Spike D: HS battle socket fingerprint capture (human-in-the-loop).
#
# This script captures snapshots while a human plays HS into a BG combat
# round, then auto-analyzes the snapshot timeline to confirm the
# fingerprint host=="" && tcp && port==3724 only appears during battle.
#
# Three sub-commands:
#   start   — build (optional), launch Bob+HS, start in-app recorder
#   mark    — write a labelled marker to the on-device record dir
#   stop    — stop recorder, pull files to /tmp/spike-d/, analyze
#
# Typical session:
#   ./test-spike-d.sh start
#   <wait for HS to load to main menu>
#   ./test-spike-d.sh mark menu
#   <enter BG mode, hero pick, into round 1>
#   ./test-spike-d.sh mark battle_start
#   <battle animation playing>
#   ./test-spike-d.sh mark battle_end
#   <results screen>
#   ./test-spike-d.sh stop
set -uo pipefail

BOB_PKG=com.bobassist.phase0
HS_PKG=com.blizzard.wtcg.hearthstone
HOST_OUT=/tmp/spike-d
DEVICE_DIR=/data/user/0/$BOB_PKG/files/spike-d

cd "$(dirname "$0")/.."

cmd_start() {
    local rebuild=0
    [[ "${1:-}" == "--rebuild" ]] && rebuild=1
    if [[ "$rebuild" -eq 1 ]]; then
        echo "[start] Rebuild bobcore + APK"
        ( cd ../bobcore && ./build-aar.sh >/dev/null )
        ./gradlew :app:assembleDebug -q >/dev/null
        adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    fi
    echo "[start] Force-stop both apps"
    adb shell am force-stop "$BOB_PKG"
    adb shell am force-stop "$HS_PKG"
    adb logcat -c
    echo "[start] Launch MainActivity with auto-start"
    adb shell am start -n "$BOB_PKG/.MainActivity" --ez auto_start true >/dev/null
    sleep 4
    echo "[start] Launch HS"
    adb shell monkey -p "$HS_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 2
    echo "[start] record_start (snapshots every 500ms to $DEVICE_DIR/)"
    adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd record_start >/dev/null
    sleep 1
    adb logcat -d -s SpikeC:I 2>&1 | grep record_start | tail -1
    echo "Now play HS. Call:"
    echo "  ./test-spike-d.sh mark <label>"
    echo "  ./test-spike-d.sh stop   # when done"
}

cmd_mark() {
    local label="${1:-mark}"
    adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd record_mark --es label "$label" >/dev/null
    sleep 0.3
    adb logcat -d -s SpikeC:I 2>&1 | grep "record_mark label=$label" | tail -1
}

cmd_stop() {
    echo "[stop] record_stop"
    adb shell am broadcast -a com.bobassist.phase0.TEST -p "$BOB_PKG" --es cmd record_stop >/dev/null
    sleep 1
    adb logcat -d -s SpikeC:I 2>&1 | grep record_stop | tail -1

    echo "[stop] Pull snapshots to $HOST_OUT/"
    rm -rf "$HOST_OUT"; mkdir -p "$HOST_OUT"
    # Use staging dir on /sdcard so adb pull works without run-as quirks.
    adb shell "run-as $BOB_PKG sh -c 'tar cf - files/spike-d 2>/dev/null'" > "$HOST_OUT.tar" 2>/dev/null
    if [[ -s "$HOST_OUT.tar" ]]; then
        # tar puts files under files/spike-d/, strip 2 leading components.
        tar xf "$HOST_OUT.tar" -C "$HOST_OUT" --strip-components=2 2>/dev/null || true
    fi
    local snaps=$(ls "$HOST_OUT"/*.json 2>/dev/null | wc -l | tr -d ' ')
    local marks=$(ls "$HOST_OUT"/MARK-*.txt 2>/dev/null | wc -l | tr -d ' ')
    echo "[stop] pulled snapshots=$snaps marks=$marks"

    cmd_analyze
}

cmd_analyze() {
    if ! command -v jq >/dev/null; then
        echo "FATAL: jq not on PATH"; exit 1
    fi
    echo
    echo "=== Markers (chronological) ==="
    ls "$HOST_OUT"/MARK-*.txt 2>/dev/null | sort | while read f; do
        cat "$f"
    done

    echo
    echo "=== Snapshots with HS battle-socket fingerprint (host==\"\" && tcp && port==3724) ==="
    local hit_count=0
    for f in $(ls "$HOST_OUT"/*.json 2>/dev/null | sort); do
        local ts=$(basename "$f" .json)
        local matches=$(jq -c '[.[] | select(.host == "" and .network == "tcp" and .destinationPort == 3724)]' "$f" 2>/dev/null)
        if [[ "$matches" != "[]" && -n "$matches" ]]; then
            echo "$ts: $matches"
            hit_count=$((hit_count+1))
        fi
    done
    echo "(total snapshots with fingerprint hits: $hit_count)"

    echo
    echo "=== IPv6 check: any conn with ':' in destinationIp ==="
    local ipv6_count=0
    for f in $(ls "$HOST_OUT"/*.json 2>/dev/null | sort); do
        local count=$(jq -r '[.[] | select(.destinationIp | contains(":"))] | length' "$f" 2>/dev/null)
        ipv6_count=$((ipv6_count + count))
    done
    echo "IPv6 connections across all snapshots: $ipv6_count"
    if [[ $ipv6_count -eq 0 ]]; then
        echo "→ HS Android uses IPv4 only (spec §12 Q3 answered: NO)"
    else
        echo "→ HS Android uses IPv6 — Phase 1 MUST add IPv6 route"
    fi

    echo
    echo "=== HS-related host distribution (top 20) ==="
    cat "$HOST_OUT"/*.json 2>/dev/null \
        | jq -r '.[] | .host' \
        | sort | uniq -c | sort -rn | head -20
}

case "${1:-}" in
    start) shift; cmd_start "$@" ;;
    mark)  shift; cmd_mark "$@" ;;
    stop)  shift; cmd_stop ;;
    analyze) cmd_analyze ;;
    *) echo "Usage: $0 {start [--rebuild] | mark <label> | stop | analyze}"; exit 1 ;;
esac
