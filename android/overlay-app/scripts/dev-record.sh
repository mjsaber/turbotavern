#!/usr/bin/env bash
# DevRecorder host harness (debug builds). On-screen MARK button records the marks;
# this script just launches/pulls/analyzes. Set SERIAL=<adb serial> for a specific device.
#
#   SERIAL=192.168.x.x:port ./dev-record.sh start [--rebuild]   # launch + grant capture, then play
#   ./dev-record.sh mark        # fallback marker (when the on-screen button can't be used)
#   ./dev-record.sh stop        # stop recording
#   ./dev-record.sh pull        # pull the session to /tmp/devrec
#   ./dev-record.sh analyze     # run analyze-recording.py over /tmp/devrec
set -uo pipefail
BOB=com.turbotavern.full
BOB_NS=com.turbotavern
DEV="${SERIAL:+-s $SERIAL}"
HOST_OUT=/tmp/devrec
cd "$(dirname "$0")/.."

case "${1:-}" in
  start)
    if [[ "${2:-}" == "--rebuild" ]]; then
        ./gradlew :app:assembleFullDebug -q && adb $DEV install -r app/build/outputs/apk/full/debug/app-full-debug.apk
    fi
    adb $DEV shell am start -n "$BOB/com.turbotavern.devrec.DevRecorderActivity"
    echo "Tap 'Start Recording' + grant full-screen capture. Then play; tap MARK on screen (or: $0 mark)."
    ;;
  mark) adb $DEV shell am broadcast -a com.turbotavern.TEST -p "$BOB" --es cmd devrec_mark >/dev/null && echo "marked (fallback)";;
  stop) adb $DEV shell am broadcast -a com.turbotavern.TEST -p "$BOB" --es cmd devrec_stop >/dev/null && echo "stopped";;
  pull)
    rm -rf "$HOST_OUT"; mkdir -p "$HOST_OUT"
    adb $DEV shell "run-as $BOB sh -c 'tar cf - files/devrec 2>/dev/null'" > "$HOST_OUT.tar" 2>/dev/null
    tar xf "$HOST_OUT.tar" -C "$HOST_OUT" --strip-components=2 2>/dev/null || true
    frames=$(ls "$HOST_OUT"/*.json 2>/dev/null | grep -cE '/[0-9]+\.json$')
    shots=$(ls "$HOST_OUT"/SHOT-*.png 2>/dev/null | wc -l | tr -d ' ')
    marks=$(ls "$HOST_OUT"/MARK-*.txt 2>/dev/null | wc -l | tr -d ' ')
    echo "pulled -> $HOST_OUT  (frames=$frames shots=$shots marks=$marks)"
    ;;
  analyze) python3 scripts/analyze-recording.py "$HOST_OUT";;
  *) echo "Usage: SERIAL=<serial> $0 {start [--rebuild]|mark|stop|pull|analyze}"; exit 1;;
esac
