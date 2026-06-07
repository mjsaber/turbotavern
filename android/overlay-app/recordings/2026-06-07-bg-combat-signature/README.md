# Recording — 2026-06-07 BG combat-socket signature

Full DevRecorder session from a **real Battlegrounds match** on OnePlus 10T (CPH2451, Android 15),
captured to confirm the combat-socket signature for the kill path and to answer Spike-B (is there a
hero-select connection signature?).

## What's here
- `<epochMs>.json` — 858 connection snapshots (mihomo `connectionsJson`, one per ~500 ms sample).
  Shape: `{id, process, host, destinationIp, destinationPort, network, createdAt}`.
- `MARK-<ts>-<seq>.txt` / `SHOT-<ts>-<seq>.png` — 8 manual marks + their full-screen 2412×1080 PNGs.
- `events.jsonl` — sample/mark event log (foreground pkg, rotation per sample).
- `meta.json` — `started_at_ms` / `stopped_at_ms` / `mark_count`, app + device.
- `ANALYSIS.txt` — saved output of `analyze-recording.py` on this session.

## Key findings (see [[bg-combat-socket-signature]] / spec §2)
- **Combat/game socket = `host=="" ∧ tcp ∧ :3724`** — appeared at **+280.1 s** (after the select
  marks), persisted to end (156 s); IP varies (66.40.189.71 this match). Fingerprint `{3724}` and
  `{1119,3724}` Ready in 308/858 frames (36%). Production `BattleConnection.pickWithCount` selects it.
- A resolved `us.actual.battle.net:1119` (host≠"") is present and **correctly ignored** — positive
  validation of the `host==""` discriminator.
- **No select-phase connection signature** → tier auto-trigger must use the §8.2 visual probe
  (see `docs/superpowers/specs/2026-06-07-bg-visual-probe-trigger-design.md`).

## Re-analyze anytime
```bash
cd android/overlay-app
python3 scripts/analyze-recording.py recordings/2026-06-07-bg-combat-signature
```
