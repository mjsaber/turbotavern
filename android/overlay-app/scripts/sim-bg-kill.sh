#!/usr/bin/env bash
# sim-bg-kill.sh — drive deterministic scenarios against a debug build of
# com.bobassist.phase0 to validate the overlay tap-to-skip path WITHOUT
# needing a real Hearthstone battle.
#
# Usage:
#   ./sim-bg-kill.sh <scenario> [--rebuild]
#
# Scenarios (Phase 1.3 plan §11):
#   cold_start              tap-to-close round trip from a fresh service start
#   rapid_tap               cooldown drops 9/10 rapid taps
#   server_rotate           pick prefers newest candidate by createdAt
#   permission_revoke       fg=false hides the overlay
#   slow_snapshot           snapshot delay shows up in the tap phase table
#   tap_while_snapshot      tap queued behind in-flight snapshot on pollHandler
#   tap_at_poll_offsets     tap-to-close variance across 4 offsets stays < 200ms
#   preexisting_candidate   override set after service start → Ready within 1 tick
#
# Each run writes artifacts to /tmp/sim/<scenario>/<timestamp>/.
# macOS bash 3.2 compatible.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=./sim-lib.sh
. "$SCRIPT_DIR/sim-lib.sh"

# --- args -----------------------------------------------------------------

if [ "$#" -lt 1 ]; then
    echo "usage: $0 <scenario> [--rebuild]" >&2
    exit 2
fi

SCENARIO="$1"; shift || true
REBUILD=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --rebuild) REBUILD=1; shift ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

TS="$(date +%Y%m%d-%H%M%S)"
ART_DIR="/tmp/sim/$SCENARIO/$TS"
mkdir -p "$ART_DIR"

PASS=0
FAIL=0
ok()   { echo "PASS: $*";  PASS=$((PASS+1)); }
bad()  { echo "FAIL: $*";  FAIL=$((FAIL+1)); }
note() { echo "[$SCENARIO] $*"; }

# --- prerequisites --------------------------------------------------------

require_device() {
    local n
    n=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {c++} END {print c+0}')
    if [ "$n" -lt 1 ]; then
        echo "FATAL: no adb device attached" >&2
        exit 1
    fi
}

require_device

# codex code-review round-2 P3: jq prerequisite removed (no jq usage in either script).

# --- bootstrap (shared by all scenarios except preexisting_candidate) -----

bootstrap_service() {
    note "force-stop $BOB_PKG"
    adb shell am force-stop "$BOB_PKG" >/dev/null
    if [ "$REBUILD" -eq 1 ]; then
        note "rebuild + reinstall debug APK"
        # codex code-review round-2 P2: explicitly guard rebuild + install
        # so a build failure or device error aborts the scenario instead of
        # silently validating the previously-installed (stale) APK.
        if ! ( cd "$APP_DIR" && ./gradlew :app:assembleFullDebug -q ) >/dev/null; then
            echo "FATAL: assembleDebug failed" >&2
            return 1
        fi
        if ! adb install -r "$APP_DIR/app/build/outputs/apk/full/debug/app-full-debug.apk" >/dev/null; then
            echo "FATAL: adb install failed" >&2
            return 1
        fi
    fi
    adb logcat -c >/dev/null 2>&1 || true
    note "launch MainActivity with auto_start=true"
    adb shell am start -n "$BOB_PKG/.MainActivity" --ez auto_start true >/dev/null
    # Wait for the canonical breadcrumb (KEEP per plan §codex P2 #10).
    local deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if adb logcat -d -s BobVpnService:I 2>/dev/null \
            | grep -q "overlay + poller started"; then
            note "service is up"
            return 0
        fi
        sleep 0.5
    done
    bad "service did not log 'overlay + poller started' within 30s"
    return 1
}

# --- artifact helpers -----------------------------------------------------

capture_trace() {
    local out="$ART_DIR/bobtrace.log"
    adb logcat -d -s BobTrace:I > "$out" 2>/dev/null
    echo "$out"
}

emit_phase_table() {
    local trace_file
    trace_file="$1"
    local table_file="$ART_DIR/phase-table.txt"
    parse_trace "$trace_file" > "$table_file"
    echo "--- phase table ($table_file) ---"
    cat "$table_file"
    echo "--- end phase table ---"
}

cleanup() {
    note "sim_clear_all"
    sim_clear_all || true
}
trap cleanup EXIT

# --- helpers used by multiple scenarios -----------------------------------

ONE_CAND_JSON='[{"id":"sim-1","host":"","network":"tcp","destinationPort":3724,"createdAt":1000}]'
TWO_CAND_OLD_NEW='[{"id":"sim-A","host":"","network":"tcp","destinationPort":3724,"createdAt":100},{"id":"sim-B","host":"","network":"tcp","destinationPort":3724,"createdAt":900}]'

# count_trace_events <trace_file> <phase> <event>
count_trace_events() {
    local f="$1" phase="$2" event="$3"
    grep -E "phase=$phase event=$event" "$f" 2>/dev/null | wc -l | tr -d ' '
}

# extract a single field from the FIRST matching trace line.
# extract_field <trace_file> <phase> <event> <key>
extract_field() {
    local f="$1" phase="$2" event="$3" key="$4"
    grep -E "phase=$phase event=$event" "$f" 2>/dev/null \
        | head -1 \
        | sed -nE "s/.* $key=([^ ]+).*/\1/p"
}

# wait_for_trace <trace_file_path> <phase> <event> <timeout_s>
# Repeatedly re-dumps logcat to the file and greps. Returns 0 on hit.
wait_for_trace() {
    local out="$1" phase="$2" event="$3" timeout="$4"
    local deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        adb logcat -d -s BobTrace:I > "$out" 2>/dev/null
        if grep -qE "phase=$phase event=$event" "$out"; then
            return 0
        fi
        sleep 0.25
    done
    return 1
}

# tap_to_close_dt_ms <trace_file>
# Returns dt_ms between the first `phase=tap event=entry` and the first
# matching `phase=close event=exit` within the SAME cycle. Prints integer ms.
tap_to_close_dt_ms() {
    local f="$1"
    awk '
        function val(line, key,    m, re) {
            re = "[ ]" key "=[^ ]+"
            if (match(line, re)) {
                m = substr(line, RSTART, RLENGTH)
                sub("^[ ]" key "=", "", m)
                return m
            }
            return ""
        }
        /BobTrace:[[:space:]]+trace[[:space:]]/ {
            cyc = val($0, "cycle"); ph = val($0, "phase"); ev = val($0, "event"); tns = val($0, "t_ns")
            if (cyc == "" || tns == "") next
            if (ph == "tap" && ev == "entry" && !(cyc in tap_t)) tap_t[cyc] = tns
            if (ph == "close" && ev == "exit" && (cyc in tap_t) && !(cyc in close_t)) close_t[cyc] = tns
        }
        END {
            for (c in close_t) {
                printf "%d\n", (close_t[c] - tap_t[c]) / 1000000
                break
            }
        }
    ' "$f"
}

# Phase 1.4 helpers ---------------------------------------------------------

# Count <phase>/<event> trace lines that belong to a cycle containing a `tap entry`.
# Used to assert the tap path no longer takes a `snapshot` (it closes the cache).
# tap_cycle_phase_count <trace_file> <phase> <event>
tap_cycle_phase_count() {
    awk -v wph="$2" -v wev="$3" '
        function val(line, key,   m, re) {
            re = "[ ]" key "=[^ ]+"
            if (match(line, re)) { m = substr(line, RSTART, RLENGTH); sub("^[ ]" key "=", "", m); return m }
            return ""
        }
        /BobTrace:[[:space:]]+trace[[:space:]]/ {
            cyc = val($0, "cycle"); ph = val($0, "phase"); ev = val($0, "event")
            if (cyc == "") next
            rec[NR] = cyc SUBSEP ph SUBSEP ev
            if (ph == "tap" && ev == "entry") tapcyc[cyc] = 1
        }
        END {
            n = 0
            for (i in rec) {
                split(rec[i], a, SUBSEP)
                if ((a[1] in tapcyc) && a[2] == wph && a[3] == wev) n++
            }
            print n
        }
    ' "$1"
}

# Max snapshot_ms across ALL `poll_snapshot exit` rows (codex r2-plan #1: startup
# polls before sim_set_snapshot_delay produce small values; take the max).
# max_poll_snapshot_ms <trace_file>
max_poll_snapshot_ms() {
    awk '
        function val(line, key,   m, re) {
            re = "[ ]" key "=[^ ]+"
            if (match(line, re)) { m = substr(line, RSTART, RLENGTH); sub("^[ ]" key "=", "", m); return m }
            return ""
        }
        /phase=poll_snapshot event=exit/ {
            v = val($0, "snapshot_ms"); if (v != "" && v + 0 > max) max = v + 0
        }
        END { print max + 0 }
    ' "$1"
}

# conn_id from the `close entry` of a tap cycle (proves WHICH cached candidate was closed).
# tap_cycle_close_conn_id <trace_file>
tap_cycle_close_conn_id() {
    awk '
        function val(line, key,   m, re) {
            re = "[ ]" key "=[^ ]+"
            if (match(line, re)) { m = substr(line, RSTART, RLENGTH); sub("^[ ]" key "=", "", m); return m }
            return ""
        }
        /BobTrace:[[:space:]]+trace[[:space:]]/ {
            cyc = val($0, "cycle"); ph = val($0, "phase"); ev = val($0, "event")
            if (cyc == "") next
            if (ph == "tap" && ev == "entry") tapcyc[cyc] = 1
            if (ph == "close" && ev == "entry" && (cyc in tapcyc) && cid == "") cid = val($0, "conn_id")
        }
        END { print cid }
    ' "$1"
}

# --- scenario runners -----------------------------------------------------

run_cold_start() {
    bootstrap_service || return 1
    sim_set_foreground true
    sleep 3   # detector tick (codex P1 #7 — pre-Task 9 path)
    sim_set_snapshot "$ONE_CAND_JSON"
    sim_force_tick
    if ! wait_for_state Ready 5; then
        bad "state never reached Ready"
        local f; f=$(capture_trace); emit_phase_table "$f"
        return 1
    fi
    ok "state reached Ready"
    overlay_tap
    local trace_file="$ART_DIR/bobtrace.log"
    if ! wait_for_trace "$trace_file" close exit 5; then
        bad "no 'phase=close event=exit' seen within 5s"
        emit_phase_table "$trace_file"
        return 1
    fi
    local dt_ms; dt_ms=$(tap_to_close_dt_ms "$trace_file")
    note "tap → close exit dt_ms = $dt_ms"
    if [ -n "$dt_ms" ] && [ "$dt_ms" -lt 50 ]; then
        ok "tap-to-close < 50ms (got ${dt_ms}ms)"
    else
        bad "tap-to-close NOT < 50ms (got ${dt_ms}ms) — finding for Task 12"
    fi
    emit_phase_table "$trace_file"
}

run_rapid_tap() {
    bootstrap_service || return 1
    sim_set_foreground true
    sleep 3
    sim_set_snapshot "$ONE_CAND_JSON"
    sim_force_tick
    wait_for_state Ready 5 || { bad "state never reached Ready"; return 1; }
    note "firing 10 taps in ~200ms"
    local i=0
    while [ "$i" -lt 10 ]; do
        overlay_tap
        i=$((i+1))
        sleep 0.02
    done
    sleep 2
    local trace_file; trace_file=$(capture_trace)
    local close_entries; close_entries=$(count_trace_events "$trace_file" close entry)
    note "close entry count = $close_entries"
    if [ "$close_entries" = "1" ]; then
        ok "exactly 1 close entry (cooldown dropped the rest)"
    else
        bad "expected 1 close entry, got $close_entries — finding for Task 12"
    fi
    emit_phase_table "$trace_file"
}

run_server_rotate() {
    bootstrap_service || return 1
    sim_set_foreground true
    sleep 3
    # First snapshot: only A
    sim_set_snapshot '[{"id":"sim-A","host":"","network":"tcp","destinationPort":3724,"createdAt":100}]'
    sim_force_tick
    wait_for_state Ready 5 || { bad "state never reached Ready"; return 1; }
    # Second snapshot: A + B (B newer)
    sim_set_snapshot "$TWO_CAND_OLD_NEW"
    sim_force_tick
    sleep 0.5
    overlay_tap
    local trace_file="$ART_DIR/bobtrace.log"
    # Phase 1.4: tap no longer picks; the poll loop cached sim-B (newest-by-createdAt).
    # Assert the tap closed the cached newest candidate via its `close entry conn_id`.
    if ! wait_for_trace "$trace_file" close entry 5; then
        bad "no close entry observed"
        emit_phase_table "$trace_file"
        return 1
    fi
    local closed; closed=$(tap_cycle_close_conn_id "$trace_file")
    note "tap closed conn_id = $closed"
    if [ "$closed" = "sim-B" ]; then
        ok "closed cached newest candidate (sim-B)"
    else
        bad "expected closed conn_id=sim-B, got $closed"
    fi
    emit_phase_table "$trace_file"
}

run_permission_revoke() {
    bootstrap_service || return 1
    sim_set_foreground true
    sleep 3
    sim_set_snapshot "$ONE_CAND_JSON"
    sim_force_tick
    wait_for_state Ready 5 || { bad "state never reached Ready"; return 1; }
    # Now go background.
    sim_set_foreground false
    sleep 3   # detector tick
    local trace_file; trace_file=$(capture_trace)
    # NB: t_ns=/thread= fields sit between event= and visible=, so allow .* between them.
    if grep -qE 'phase=setVisible event=entry .*visible=false' "$trace_file"; then
        ok "setVisible(false) observed in trace"
    else
        bad "no setVisible(false) — finding for Task 12"
    fi
    emit_phase_table "$trace_file"
}

run_slow_snapshot() {
    bootstrap_service || return 1
    sim_set_foreground true
    sleep 3
    sim_set_snapshot_delay 1000
    sim_set_snapshot "$ONE_CAND_JSON"
    sim_force_tick
    # Give the (slow) tick time to land + state to flip.
    wait_for_state Ready 10 || { bad "state never reached Ready"; return 1; }
    overlay_tap
    local trace_file="$ART_DIR/bobtrace.log"
    if ! wait_for_trace "$trace_file" close exit 10; then
        bad "no close exit within 10s"
        emit_phase_table "$trace_file"
        return 1
    fi
    # Phase 1.4: the slow snapshot now lives on the POLL path, not the tap path.
    # (a) the tap cycle must take NO `snapshot`; (b) the poll snapshot_ms reflects the delay.
    local tap_snap; tap_snap=$(tap_cycle_phase_count "$trace_file" snapshot exit)
    local poll_ms;  poll_ms=$(max_poll_snapshot_ms "$trace_file")
    local total_dt; total_dt=$(tap_to_close_dt_ms "$trace_file")
    note "tap-cycle snapshot phases = $tap_snap; max poll_snapshot snapshot_ms = $poll_ms; tap→close dt_ms = $total_dt"
    if [ "$tap_snap" = "0" ]; then
        ok "tap path took no snapshot (cached close)"
    else
        bad "tap path still took $tap_snap snapshot(s) — connectionsJson on tap path"
    fi
    if [ -n "$poll_ms" ] && [ "$poll_ms" -ge 1000 ]; then
        ok "poll snapshot_ms ≥ 1000 (got ${poll_ms}ms) — slow snapshot now on poll path"
    else
        bad "expected poll snapshot_ms ≥ 1000, got '$poll_ms'"
    fi
    # tap→close is no longer bound to the snapshot delay; record only (may be fast or
    # queued behind an in-flight poll snapshot — that residual S#1 is P2's concern).
    emit_phase_table "$trace_file"
}

run_tap_while_snapshot() {
    bootstrap_service || return 1
    sim_set_foreground true
    sleep 3
    sim_set_snapshot_delay 2000
    sim_set_snapshot "$ONE_CAND_JSON"
    sim_force_tick                  # kicks off long snapshot
    sleep 0.2                       # 200ms into the snapshot
    overlay_tap
    local trace_file="$ART_DIR/bobtrace.log"
    # tap_post entry includes delay_ms.
    if ! wait_for_trace "$trace_file" tap_post entry 10; then
        bad "no tap_post entry observed"
        emit_phase_table "$trace_file"
        return 1
    fi
    local delay_ms; delay_ms=$(extract_field "$trace_file" tap_post entry delay_ms)
    note "tap_post delay_ms = $delay_ms"
    # Residual S#1: the tap still queues behind the in-flight POLL snapshot (P2's concern).
    if [ -n "$delay_ms" ] && [ "$delay_ms" -ge 1500 ]; then
        ok "tap_post queued ≥ 1500ms (got ${delay_ms}ms) — residual in-flight poll wait (S#1)"
    else
        note "tap_post delay_ms = '$delay_ms' (in-flight poll wait; non-deterministic)"
    fi
    # Phase 1.4 core assertion: the tap path itself takes NO snapshot.
    if ! wait_for_trace "$trace_file" close exit 10; then
        bad "no close exit observed"
        emit_phase_table "$trace_file"
        return 1
    fi
    local tap_snap; tap_snap=$(tap_cycle_phase_count "$trace_file" snapshot exit)
    if [ "$tap_snap" = "0" ]; then
        ok "tap path took no snapshot (cached close) — connectionsJson off the tap path"
    else
        bad "tap path still took $tap_snap snapshot(s)"
    fi
    emit_phase_table "$trace_file"
}

run_tap_at_poll_offsets() {
    bootstrap_service || return 1
    sim_set_foreground true
    sleep 3
    local offsets="0 200 400 600"
    local results=""
    local off
    for off in $offsets; do
        note "--- offset ${off}ms ---"
        sim_set_snapshot "$ONE_CAND_JSON"
        sim_force_tick
        wait_for_state Ready 5 || { bad "state never reached Ready (offset=$off)"; continue; }
        # Convert ms to seconds for sleep.
        if [ "$off" -gt 0 ]; then
            local frac
            frac=$(awk -v ms="$off" 'BEGIN{printf "%.3f", ms/1000}')
            sleep "$frac"
        fi
        adb logcat -c >/dev/null 2>&1 || true
        overlay_tap
        local trace_file="$ART_DIR/bobtrace-${off}.log"
        if ! wait_for_trace "$trace_file" close exit 5; then
            bad "no close exit at offset=$off"
            continue
        fi
        local dt; dt=$(tap_to_close_dt_ms "$trace_file")
        results="$results $off:$dt"
        note "offset=$off tap→close dt_ms=$dt"
        # Wait for cooldown to expire so next iteration starts clean.
        # codex code-review round-4 P2: TestReceiver.stateLabel emits "Waiting",
        # not "WaitingForBattle" — match the emitted label so the cooldown
        # exit is actually observed (instead of always timing out via || true).
        wait_for_state Waiting 5 || true
        # codex code-review round-5 P2: sim_clear_all also nulls the foreground
        # override; on the next iteration the detector tick can pause the
        # poller (Usage Access granted + HS not actually foreground). Clear
        # only snapshot/close overrides, leave foreground=true active.
        sim_clear_snapshot
        sim_set_close_delay 0
        sleep 0.5
    done
    note "results (offset:dt_ms): $results"
    # Compute min/max.
    local min="" max=""
    for kv in $results; do
        local v="${kv##*:}"
        if [ -z "$v" ]; then continue; fi
        if [ -z "$min" ] || [ "$v" -lt "$min" ]; then min="$v"; fi
        if [ -z "$max" ] || [ "$v" -gt "$max" ]; then max="$v"; fi
    done
    if [ -z "$min" ] || [ -z "$max" ]; then
        bad "no usable samples collected"
        return 1
    fi
    local spread=$(( max - min ))
    note "spread = ${spread}ms (min=$min max=$max)"
    if [ "$spread" -lt 200 ]; then
        ok "spread < 200ms"
    else
        bad "spread ≥ 200ms (${spread}ms) — finding for Task 12"
    fi
}

run_preexisting_candidate() {
    # Special flow per plan §11: do NOT use the generic bootstrap because we
    # want to inject the snapshot AS CLOSE to service start as possible. The
    # service still must be alive before sim_set_snapshot is meaningful, so
    # we (1) auto-start; (2) wait for breadcrumb; (3) fg=true + 3s; (4) inject.
    bootstrap_service || return 1
    sim_set_foreground true
    sleep 3
    note "injecting snapshot + force_tick (measuring time-to-Ready)"
    # codex code-review P2: macOS `date +%s%N` produces non-numeric output
    # ("…N" suffix); use python for portable millisecond timestamps.
    local t_start; t_start=$(python3 -c 'import time; print(int(time.time()*1000))')
    sim_set_snapshot "$ONE_CAND_JSON"
    sim_force_tick
    if ! wait_for_state Ready 5; then
        bad "state never reached Ready"
        local f; f=$(capture_trace); emit_phase_table "$f"
        return 1
    fi
    local t_end; t_end=$(python3 -c 'import time; print(int(time.time()*1000))')
    local dt_ms=$(( t_end - t_start ))
    note "set_snapshot → state=Ready dt_ms = $dt_ms"
    if [ "$dt_ms" -lt 800 ]; then
        ok "Ready within 1 poll tick (~800ms): ${dt_ms}ms"
    else
        bad "Ready took ${dt_ms}ms (≥ 800ms) — finding for Task 12"
    fi
    local f; f=$(capture_trace); emit_phase_table "$f"
}

# --- dispatch -------------------------------------------------------------

case "$SCENARIO" in
    cold_start)             run_cold_start ;;
    rapid_tap)              run_rapid_tap ;;
    server_rotate)          run_server_rotate ;;
    permission_revoke)      run_permission_revoke ;;
    slow_snapshot)          run_slow_snapshot ;;
    tap_while_snapshot)     run_tap_while_snapshot ;;
    tap_at_poll_offsets)    run_tap_at_poll_offsets ;;
    preexisting_candidate)  run_preexisting_candidate ;;
    *) echo "unknown scenario: $SCENARIO" >&2; exit 2 ;;
esac
# codex code-review round-3 P2: capture runner exit so an early return
# (bootstrap_service failure on --rebuild, etc.) is recorded as a fail
# rather than reported as success because no `bad`/FAIL was emitted.
RUNNER_STATUS=$?
if [ "$RUNNER_STATUS" -ne 0 ] && [ "$FAIL" -eq 0 ]; then
    FAIL=$((FAIL + 1))
    echo "FAIL: scenario runner returned status=$RUNNER_STATUS (no explicit bad/FAIL emitted)"
fi

echo
echo "=== $SCENARIO summary: pass=$PASS fail=$FAIL ==="
echo "artifacts: $ART_DIR"
exit $(( FAIL > 0 ? 1 : 0 ))
