#!/usr/bin/env bash
# Headless emulator smoke test for DevRecorder: boots an arm64 AVD, installs, grants overlay,
# drives Start->mark*3->stop entirely over adb (marks via TestReceiver broadcast, no taps),
# then pull+analyze and assert files landed. Catches integration regressions with NO phone / NO Hearthstone.
#
# One-time: downloads system image (~1GB) + creates the AVD. Re-runs reuse both.
# Usage: scripts/emu-smoke.sh
set -uo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
# homebrew's cmdline-tools default to their OWN sdk root; force every tool onto the root the emulator uses.
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"; [[ -x "$SDKM" ]] || SDKM="$(command -v sdkmanager)"
AVDM="$SDK/cmdline-tools/latest/bin/avdmanager"; [[ -x "$AVDM" ]] || AVDM="$(command -v avdmanager)"
EMUBIN="$SDK/emulator/emulator"
BOB=com.bobassist.phase0
IMG="system-images;android-34;google_atd;arm64-v8a"
IMGDIR="$SDK/system-images/android-34/google_atd/arm64-v8a"
AVD=devrec-smoke
EMU=emulator-5554
MARKS=3
fail() { echo "SMOKE FAIL: $*" >&2; cleanup; exit 1; }
EMU_PID=""
cleanup() { [[ -n "$EMU_PID" ]] && kill "$EMU_PID" 2>/dev/null; }
trap cleanup EXIT

# --- 1. ensure system image + AVD (idempotent) ---
if [[ ! -d "$IMGDIR" ]]; then
  echo "[emu] installing $IMG (one-time ~1GB)..."
  set +o pipefail; yes | "$SDKM" --sdk_root="$SDK" "$IMG" >/dev/null; set -o pipefail   # `yes` dies on SIGPIPE; judge by result, not exit
  [[ -d "$IMGDIR" ]] || fail "sdkmanager install (image dir absent)"
fi
if ! "$EMUBIN" -list-avds 2>/dev/null | grep -qx "$AVD"; then
  echo "[emu] creating AVD $AVD..."; echo no | "$AVDM" create avd -n "$AVD" -k "$IMG" --device pixel_5 >/dev/null || fail "create avd"
fi

# --- 2. boot headless ---
echo "[emu] booting $AVD headless..."
"$EMUBIN" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect >/tmp/emu-smoke-boot.log 2>&1 &
EMU_PID=$!
adb -s "$EMU" wait-for-device || fail "device never appeared"
for i in $(seq 120); do
  [[ "$(adb -s "$EMU" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 2; [[ $i -eq 120 ]] && fail "boot timeout"
done
adb -s "$EMU" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
echo "[emu] booted."

# --- 3. install + grant overlay ---
./gradlew :app:assembleFullDebug -q || fail "assembleDebug"
adb -s "$EMU" install -r app/build/outputs/apk/full/debug/app-full-debug.apk >/dev/null || fail "install"
adb -s "$EMU" shell appops set "$BOB" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1   # floating panel, no manual grant
adb -s "$EMU" shell appops set "$BOB" GET_USAGE_STATS allow >/dev/null 2>&1        # foreground query (debug log)

# --- 4. launch + drive Start Recording + accept MediaProjection consent (uiautomator) ---
ui_tap_text() { # tap the center of the first node whose text= matches $1 (case-insensitive)
  adb -s "$EMU" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$(adb -s "$EMU" shell cat /sdcard/ui.xml 2>/dev/null | tr '>' '\n' \
    | grep -iE "text=\"$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
  [[ -z "$b" ]] && return 1
  local n; n=$(echo "$b" | grep -oE '[0-9]+'); set -- $n
  adb -s "$EMU" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); return 0
}
try_tap() { for t in "$@"; do ui_tap_text "$t" && { echo "[ui] tapped '$t'"; return 0; }; done; return 1; }

adb -s "$EMU" shell am start -n "$BOB/.devrec.DevRecorderActivity" >/dev/null || fail "am start"
sleep 2
try_tap "Start Recording" "START RECORDING" "Start" || fail "no 'Start Recording' button"
sleep 2                                                    # system consent dialog
try_tap "Start now" "START NOW" "Start" "Allow" || echo "[ui] WARN: consent button not found (may be pre-granted)"
# wait for the recorder service to come up
for i in $(seq 20); do adb -s "$EMU" shell pidof "$BOB" >/dev/null 2>&1 && grep -q "recording up" <(adb -s "$EMU" shell run-as "$BOB" cat files/bob-breadcrumbs.log 2>/dev/null) && break; sleep 1; done

# --- 5. fire marks + stop entirely over adb (no taps) ---
for n in $(seq "$MARKS"); do
  adb -s "$EMU" shell am broadcast -a "$BOB.TEST" -p "$BOB" --es cmd devrec_mark >/dev/null; sleep 1
done
adb -s "$EMU" shell am broadcast -a "$BOB.TEST" -p "$BOB" --es cmd devrec_stop >/dev/null; sleep 2

# --- 6. pull + analyze + assert ---
SERIAL="$EMU" scripts/dev-record.sh pull || fail "pull"
OUT=/tmp/devrec
frames=$(ls "$OUT"/*.json 2>/dev/null | grep -cE '/[0-9]+\.json' || true)
marks=$(ls "$OUT"/MARK-*.txt 2>/dev/null | wc -l | tr -d ' ')
shots=$(ls "$OUT"/SHOT-*.png 2>/dev/null | wc -l | tr -d ' ')
echo "[smoke] frames=$frames marks=$marks shots=$shots (expected marks=$MARKS)"
[[ "$marks" -eq "$MARKS" ]] || fail "marks=$marks != $MARKS"
[[ "$shots" -ge 1 ]] || fail "no SHOT captured (MediaProjection path broken)"
SERIAL="$EMU" scripts/dev-record.sh analyze || fail "analyze crashed"
echo "SMOKE PASS: marks=$marks shots=$shots frames=$frames"
