# bgtiers — BG hero/trinket tier data pipeline

Scrapes raw Firestone BG stats + HearthstoneJSON identity into an append-only SQLite DB.
Scope: data acquisition + storage only (spec 2026-05-31, v4).

## Commands
    uv run bgtiers --db bgtiers.db init-db
    uv run bgtiers --db bgtiers.db sync-entities      # HearthstoneJSON identity (+ trinket lesser/greater)
    uv run bgtiers --db bgtiers.db fetch-stats        # Firestone stats (run-lock; heroes per mmr, trinkets expanded)

## Schedule (example, daily; flock prevents overlap)
    0 9 * * * cd /path/to/data-pipeline && uv run bgtiers fetch-stats

## Notes
- Heroes: one URL per (mmr, period). Trinkets: one URL per period, expanded into 5 mmr brackets.
- fetch_state is keyed by URL (HTTP validators); dedup compares each dimension's latest snapshot.
