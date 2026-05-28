#!/usr/bin/env bash
# Shared helpers for sim-bg-kill.sh — wraps the debug-only `am broadcast`
# commands exposed by TestReceiver (debug build only).
#
# Conventions:
#   - All helpers assume `adb` on PATH and a single attached device.
#   - Logcat is filtered by tag rather than cleared globally so concurrent
#     scenarios can be co-debugged via plain `adb logcat`.
#   - macOS bash 3.2 compatible: no associative arrays, no `mapfile`,
#     no `${var,,}`, no process substitution where avoidable.
#
# Source from a scenario script:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   . "$SCRIPT_DIR/sim-lib.sh"
# shellcheck shell=bash

BOB_PKG=com.bobassist.phase0
BOB_TEST_ACTION="${BOB_PKG}.TEST"

# Single-quote a string for the DEVICE shell (Android sh). Wraps in single
# quotes and escapes any embedded single quote as '\''.
_shq() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# Internal: emit one am broadcast command.
#
# IMPORTANT (device-shell quoting): `adb shell am broadcast --es json <JSON>`
# with the JSON passed as a separate arg gets MANGLED — the device sh eats
# the `{ } "` characters, so a JSON value arrives as ~1 char. We instead build
# the ENTIRE device command as one string, single-quoting every dynamic value
# for the device sh, and hand that single string to `adb shell`. The host
# double-quotes preserve the embedded device single-quotes verbatim.
#
# Args after cmd_name must be `--es key value` (or `--ez key value`) triples.
_bob_broadcast() {
    local cmd_name="$1"; shift
    local devcmd="am broadcast -a $(_shq "$BOB_TEST_ACTION") -p $(_shq "$BOB_PKG") --es cmd $(_shq "$cmd_name")"
    while [ "$#" -gt 0 ]; do
        local flag="$1" key="$2" val="$3"
        shift 3
        devcmd="$devcmd $flag $(_shq "$key") $(_shq "$val")"
    done
    adb shell "$devcmd" >/dev/null
}

# --- sim_* commands -------------------------------------------------------

# sim_set_snapshot '<json-array>'
sim_set_snapshot() {
    local json="$1"
    _bob_broadcast sim_set_snapshot --es json "$json"
}

sim_clear_snapshot() {
    _bob_broadcast sim_clear_snapshot
}

# sim_set_snapshot_delay <ms>
sim_set_snapshot_delay() {
    local ms="$1"
    _bob_broadcast sim_set_snapshot_delay --es ms "$ms"
}

# sim_set_close_delay <ms>
sim_set_close_delay() {
    local ms="$1"
    _bob_broadcast sim_set_close_delay --es ms "$ms"
}

# sim_set_foreground <true|false>
sim_set_foreground() {
    local value="$1"
    _bob_broadcast sim_set_foreground --es value "$value"
}

sim_force_tick() {
    _bob_broadcast sim_force_tick
}

sim_clear_all() {
    _bob_broadcast sim_clear_all
}

# overlay_tap — dispatch a synthetic tap through OverlaySession.handleTap.
overlay_tap() {
    _bob_broadcast overlay_tap
}

# overlay_state — broadcasts the query, then reads back the latest matching
# SpikeC:I logcat line and prints just the `state=<X>` field (e.g. "Ready").
# Returns 0 with state on stdout if found within ~1s, else 1.
overlay_state() {
    # NOTE: deliberately does NOT `adb logcat -c` — clearing the global buffer
    # here would also wipe the BobTrace tap-cycle lines that capture_trace
    # needs (caused flaky "no close exit seen" failures). To avoid reading a
    # STALE overlay_state line (codex round-3 P2 concern), we snapshot the
    # pre-broadcast line count and wait for a NEWER line to appear.
    local before
    before=$(adb logcat -d -s SpikeC:I 2>/dev/null | grep -c 'overlay_state state=')
    _bob_broadcast overlay_state
    local deadline=$(( $(date +%s) + 2 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local all after line
        all=$(adb logcat -d -s SpikeC:I 2>/dev/null | grep 'overlay_state state=')
        after=$(printf '%s\n' "$all" | grep -c 'overlay_state state=')
        if [ "$after" -gt "$before" ]; then
            line=$(printf '%s\n' "$all" | tail -1)
            echo "$line" | sed -E 's/.*overlay_state state=([^ ]+).*/\1/'
            return 0
        fi
        sleep 0.2
    done
    return 1
}

# wait_for_state <expected> <timeout_s>
# Polls overlay_state every 0.5s until the parsed value matches `expected` or
# the timeout elapses. Returns 0 on match, 1 on timeout.
wait_for_state() {
    local expected="$1"
    local timeout_s="$2"
    local deadline=$(( $(date +%s) + timeout_s ))
    local got=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        got=$(overlay_state || true)
        if [ "$got" = "$expected" ]; then
            return 0
        fi
        sleep 0.5
    done
    echo "wait_for_state: timed out waiting for state=$expected (last=$got)" >&2
    return 1
}

# parse_trace <log_file>
# Reads a logcat dump filtered by tag=BobTrace and prints a table grouped by
# cycle. Each row: cycle, phase, event, dt_ms_from_first_in_cycle.
#
# Expected line format (from TraceSink):
#   <prefix> BobTrace: trace session=<s> cycle=<c> phase=<p> event=<e> t_ns=<n> thread=<...> [k=v ...]
#
# Output is plain-text, suitable for piping or printf-ing to stdout.
parse_trace() {
    local log_file="$1"
    if [ ! -f "$log_file" ]; then
        echo "parse_trace: no such file: $log_file" >&2
        return 1
    fi
    awk '
        # Extract a single "key=val" token from the BobTrace line. Vals may
        # contain non-space chars; quoting is not currently emitted by Kotlin.
        function val(line, key,    re, m, rest) {
            re = "[ ]" key "=[^ ]+"
            if (match(line, re)) {
                m = substr(line, RSTART, RLENGTH)
                sub("^[ ]" key "=", "", m)
                return m
            }
            return ""
        }
        /BobTrace:[[:space:]]+trace[[:space:]]/ {
            cyc  = val($0, "cycle")
            ph   = val($0, "phase")
            ev   = val($0, "event")
            tns  = val($0, "t_ns")
            if (cyc == "" || tns == "") next
            # First sighting of this cycle sets the t0 baseline.
            if (!(cyc in t0)) { t0[cyc] = tns; order[++n] = cyc }
            dt_ms = (tns - t0[cyc]) / 1000000
            # Build the row; tail-key-value pairs (delay_ms, picked_id, etc.)
            # are appended verbatim so the consumer can grep them.
            extras = $0
            sub(/.*BobTrace:[[:space:]]+/, "", extras)
            # strip the known leading fields we already split out
            gsub(/(^|[ ])(trace|session=[^ ]+|cycle=[^ ]+|phase=[^ ]+|event=[^ ]+|t_ns=[^ ]+|thread=[^ ]+)/, " ", extras)
            gsub(/^[ ]+|[ ]+$/, "", extras)
            rows[cyc] = rows[cyc] sprintf("  %-14s %-7s  +%6d ms  %s\n", ph, ev, dt_ms, extras)
        }
        END {
            if (n == 0) {
                print "parse_trace: no BobTrace lines in input"
                exit 0
            }
            printf "%-10s %s\n", "CYCLE", "PHASE          EVENT     DT_MS     EXTRA"
            for (i = 1; i <= n; i++) {
                c = order[i]
                printf "cycle=%s\n%s", c, rows[c]
            }
        }
    ' "$log_file"
}
