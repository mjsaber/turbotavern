"""Transactional load — the only module that writes snapshot/stats/raw/fetch_state.

One URL's feeds (hero: 1 bracket; trinket: 5) are processed in ONE BEGIN IMMEDIATE
transaction. Per-dimension dedup compares against that dimension's latest snapshot
content_hash (snapshot table is the single source of truth). The URL's HTTP
validators are advanced only after the whole URL processes successfully.
"""
from __future__ import annotations
import gzip
import json
import sqlite3

from .models import NormalizedFeed, Outcome
from .hashing import content_hash
from . import entities

_MODE, _REGION = "solo", "global"


def _latest_hash(conn, source, entity_type, bracket, period) -> str | None:
    row = conn.execute(
        "SELECT content_hash FROM snapshot WHERE source=? AND entity_type=? AND mmr_bracket=? "
        "AND time_period=? AND mode=? AND region=? ORDER BY fetched_at DESC, snapshot_id DESC LIMIT 1",
        (source, entity_type, bracket, period, _MODE, _REGION),
    ).fetchone()
    return row["content_hash"] if row else None


def _upsert_url_state(conn, source, raw_url, *, etag, last_modified, now, preserve):
    # preserve=True (304): keep prior validators. preserve=False (processed 200): set
    # them to exactly this response's headers (even NULL) so a 200 missing a validator
    # does not leave a stale one.
    val = ("etag=COALESCE(excluded.etag,fetch_state.etag), "
           "last_modified=COALESCE(excluded.last_modified,fetch_state.last_modified)") if preserve \
        else "etag=excluded.etag, last_modified=excluded.last_modified"
    conn.execute(
        f"""INSERT INTO fetch_state (source, raw_url, etag, last_modified, last_checked_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT (source, raw_url) DO UPDATE SET {val}, last_checked_at=excluded.last_checked_at""",
        (source, raw_url, etag, last_modified, now),
    )


def _insert_snapshot(conn, source, entity_type, bracket, period, feed, body, last_modified, now, raw_url):
    cur = conn.execute(
        """INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,
               patch, source_last_modified, content_hash, fetched_at, raw_url)
           VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
        (source, entity_type, bracket, period, _MODE, _REGION, feed.patch, last_modified,
         content_hash(feed), now, raw_url),
    )
    snap_id = cur.lastrowid
    for r in feed.rows:
        eid = entities.ensure_entity(conn, entity_type, r.card_id, now)
        conn.execute(
            """INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement, data_points,
                   pick_rate, placement_distribution, extra_json) VALUES (?,?,?,?,?,?,?)""",
            (snap_id, eid, r.avg_placement, r.data_points, r.pick_rate,
             json.dumps(r.placement_distribution) if r.placement_distribution is not None else None,
             json.dumps(r.extra_json, sort_keys=True) if r.extra_json else None),
        )
    gz = gzip.compress(body)
    conn.execute(
        "INSERT INTO raw_payload (snapshot_id, body_gzip, content_encoding, byte_size) VALUES (?,?,?,?)",
        (snap_id, gz, "gzip", len(body)),
    )


def load_url(conn: sqlite3.Connection, *, source, entity_type, raw_url, time_period,
             status, body, etag, last_modified, feeds_by_bracket, now):
    """Returns Outcome.NOT_MODIFIED (304) or dict[bracket -> Outcome] (200)."""
    conn.execute("BEGIN IMMEDIATE")
    try:
        if status == 304:
            _upsert_url_state(conn, source, raw_url, etag=etag, last_modified=last_modified,
                              now=now, preserve=True)
            conn.execute("COMMIT")
            return Outcome.NOT_MODIFIED

        assert feeds_by_bracket is not None and body is not None
        outcomes: dict[str, Outcome] = {}
        for bracket, feed in feeds_by_bracket.items():
            if _latest_hash(conn, source, entity_type, bracket, time_period) == content_hash(feed):
                outcomes[bracket] = Outcome.UNCHANGED
            else:
                _insert_snapshot(conn, source, entity_type, bracket, time_period, feed, body,
                                 last_modified, now, raw_url)
                outcomes[bracket] = Outcome.INSERTED
        _upsert_url_state(conn, source, raw_url, etag=etag, last_modified=last_modified,
                          now=now, preserve=False)
        conn.execute("COMMIT")
        return outcomes
    except Exception:
        conn.execute("ROLLBACK")
        raise
