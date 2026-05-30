#!/usr/bin/env python3
"""Analyze a spike-d recording (timestamped connectionsJson frames).

Usage: analyze-recording.py <recording_dir>   # dir containing <epoch_ms>.json + MARK-*.txt

Reusable offline analysis: builds a per-connection lifespan table and a
change-log (when connections appear/disappear), so we can locate the battle
socket by correlating socket lifetimes with narrated combat windows — without
re-playing the game. Identity = host|ip:port (ids rotate; host/ip/port is stable).
"""
import sys, os, json, glob

def load(d):
    frames = []
    for f in sorted(glob.glob(os.path.join(d, "*.json")), key=lambda p: int(os.path.basename(p)[:-5])):
        ts = int(os.path.basename(f)[:-5])
        try:
            conns = json.load(open(f))
        except Exception:
            conns = []
        frames.append((ts, conns))
    marks = []
    for f in glob.glob(os.path.join(d, "MARK-*.txt")):
        parts = os.path.basename(f)[5:-4].split("-", 1)
        marks.append((int(parts[0]), parts[1] if len(parts) > 1 else ""))
    return frames, sorted(marks)

def key(c):
    return f'{c.get("host","")}|{c.get("destinationIp","")}:{c.get("destinationPort","")}'

def main():
    d = sys.argv[1]
    frames, marks = load(d)
    if not frames:
        print("no frames"); return
    t0 = frames[0][0]
    rel = lambda ts: (ts - t0) / 1000.0
    print(f"frames={len(frames)} span={rel(frames[-1][0]):.1f}s  (t0={t0})")
    for mts, lbl in marks:
        print(f"  MARK +{rel(mts):7.1f}s  {lbl}")

    # Per-connection lifespan
    life = {}  # key -> [first_ts, last_ts, sample_conn]
    for ts, conns in frames:
        for c in conns:
            k = key(c)
            if k not in life:
                life[k] = [ts, ts, c]
            life[k][1] = ts
    print("\n=== connection lifespans (sorted by first-seen) ===")
    print(f'{"+start":>8} {"+end":>8} {"dur":>6}  host_empty?  host|ip:port')
    for k, (a, b, c) in sorted(life.items(), key=lambda kv: kv[1][0]):
        he = "HOST_EMPTY" if c.get("host", "") == "" else "          "
        print(f'{rel(a):8.1f} {rel(b):8.1f} {(b-a)/1000.0:6.1f}  {he}   {k}')

    # Fingerprint back-test: replay candidate fingerprints over the recording.
    # Mirrors BattleConnection.pick: host=="" && network=="tcp" && port matches.
    def fp_match(c, ports):
        return (c.get("host", "") == "" and c.get("network", "") == "tcp"
                and c.get("destinationPort", 0) in ports)
    for name, ports in (("OLD {3724}", {3724}), ("NEW {1119,3724}", {1119, 3724})):
        first_ready = None
        ready_frames = 0
        last_cand = None
        for ts, conns in frames:
            cands = [c for c in conns if fp_match(c, ports)]
            if cands:
                ready_frames += 1
                if first_ready is None:
                    first_ready = ts
                last_cand = max(cands, key=lambda c: c.get("createdAt", 0))
        pct = 100.0 * ready_frames / len(frames)
        fr = f"+{rel(first_ready):.1f}s" if first_ready else "never"
        ex = f'{last_cand.get("destinationIp")}:{last_cand.get("destinationPort")}' if last_cand else "-"
        print(f"\n=== fingerprint {name}: Ready in {ready_frames}/{len(frames)} frames ({pct:.0f}%), first Ready {fr}, e.g. {ex} ===")

    # Change-log: only frames where the connection SET changes
    print("\n=== change-log (Δ connection set) ===")
    prev = set()
    for ts, conns in frames:
        cur = {key(c) for c in conns}
        added, removed = cur - prev, prev - cur
        if added or removed:
            for k in sorted(added):
                tag = "  <-- HOST_EMPTY" if k.startswith("|") else ""
                print(f'+{rel(ts):8.1f}s  ADD  {k}{tag}')
            for k in sorted(removed):
                tag = "  <-- HOST_EMPTY" if k.startswith("|") else ""
                print(f'+{rel(ts):8.1f}s  DEL  {k}{tag}')
        prev = cur

if __name__ == "__main__":
    main()
