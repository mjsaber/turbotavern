"""Schema definition (single source of DDL truth) + connection setup."""
import sqlite3

SCHEMA = """
CREATE TABLE IF NOT EXISTS entity (
  entity_id     INTEGER PRIMARY KEY,
  entity_type   TEXT NOT NULL CHECK (entity_type IN ('hero','trinket')),
  card_id       TEXT NOT NULL,
  dbf_id        INTEGER,
  name          TEXT,
  image_url     TEXT,
  trinket_class TEXT CHECK (trinket_class IN ('lesser','greater') OR trinket_class IS NULL),
  first_seen_at TEXT NOT NULL,
  last_seen_at  TEXT NOT NULL,
  UNIQUE (entity_type, card_id)
);

-- localized display names + normalized search key, one row per (entity, locale).
-- entity.name stays the default-locale string; this table is the multi-language source.
CREATE TABLE IF NOT EXISTS entity_name (
  entity_id  INTEGER NOT NULL REFERENCES entity(entity_id),
  locale     TEXT NOT NULL,
  name       TEXT NOT NULL,
  name_key   TEXT NOT NULL,
  UNIQUE (entity_id, locale)
);

-- fetch_state is keyed by URL (the fetch unit): a trinket URL feeds 5 dimensions
-- but has one ETag, so validators live per-URL. Dedup does NOT live here.
CREATE TABLE IF NOT EXISTS fetch_state (
  source          TEXT NOT NULL,
  raw_url         TEXT NOT NULL,
  etag            TEXT,
  last_modified   TEXT,
  last_checked_at TEXT NOT NULL,
  PRIMARY KEY (source, raw_url)
);

-- mmr_bracket / time_period CHECKs use spec §5.3 enumerated values (Stage-0 confirmed).
CREATE TABLE IF NOT EXISTS snapshot (
  snapshot_id          INTEGER PRIMARY KEY,
  source               TEXT NOT NULL,
  entity_type          TEXT NOT NULL CHECK (entity_type IN ('hero','trinket')),
  mmr_bracket          TEXT NOT NULL CHECK (mmr_bracket IN ('100','50','25','10','1')),
  time_period          TEXT NOT NULL CHECK (time_period IN ('last-patch','past-three','past-seven')),
  mode                 TEXT NOT NULL DEFAULT 'solo',
  region               TEXT NOT NULL DEFAULT 'global',
  patch                TEXT,
  source_last_modified TEXT,
  content_hash         TEXT NOT NULL,
  fetched_at           TEXT NOT NULL,
  raw_url              TEXT NOT NULL
  -- no UNIQUE on fetched_at: append-only history must allow two changed snapshots
  -- in the same second; duplicate-insert is prevented by per-dimension dedup + run-lock.
);

CREATE TABLE IF NOT EXISTS entity_stats (
  id                     INTEGER PRIMARY KEY,
  snapshot_id            INTEGER NOT NULL REFERENCES snapshot(snapshot_id),
  entity_id              INTEGER NOT NULL REFERENCES entity(entity_id),
  avg_placement          REAL NOT NULL CHECK (avg_placement >= 1.0 AND avg_placement <= 8.0),
  data_points            INTEGER NOT NULL CHECK (data_points >= 0),
  pick_rate              REAL,
  placement_distribution TEXT,
  extra_json             TEXT,
  UNIQUE (snapshot_id, entity_id)
);

CREATE TABLE IF NOT EXISTS raw_payload (
  snapshot_id      INTEGER PRIMARY KEY REFERENCES snapshot(snapshot_id),
  body_gzip        BLOB NOT NULL,
  content_encoding TEXT NOT NULL,
  byte_size        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_entity_name_lookup ON entity_name (locale, name_key);
CREATE INDEX IF NOT EXISTS idx_entity_stats_entity ON entity_stats(entity_id);
CREATE INDEX IF NOT EXISTS idx_snapshot_latest
  ON snapshot(source, entity_type, mmr_bracket, time_period, mode, region, fetched_at);

CREATE VIEW IF NOT EXISTS v_latest_stats AS
WITH ranked AS (
  SELECT snapshot_id,
         ROW_NUMBER() OVER (
           PARTITION BY source, entity_type, mmr_bracket, time_period, mode, region
           ORDER BY fetched_at DESC, snapshot_id DESC
         ) AS rn
  FROM snapshot
)
SELECT
  snap.snapshot_id, snap.source, snap.entity_type, snap.mmr_bracket,
  snap.time_period, snap.mode, snap.region, snap.patch, snap.fetched_at,
  e.card_id, e.name, e.image_url, e.trinket_class, e.dbf_id,
  st.avg_placement, st.data_points, st.pick_rate,
  st.placement_distribution, st.extra_json
FROM ranked r
JOIN snapshot snap   ON snap.snapshot_id = r.snapshot_id AND r.rn = 1
JOIN entity_stats st ON st.snapshot_id = snap.snapshot_id
JOIN entity e        ON e.entity_id = st.entity_id;
"""


def connect(path: str) -> sqlite3.Connection:
    # isolation_level=None -> autocommit, so our explicit BEGIN IMMEDIATE/COMMIT/ROLLBACK
    # in load.py control transactions (default sqlite3 would inject its own BEGIN).
    conn = sqlite3.connect(path, isolation_level=None)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.row_factory = sqlite3.Row
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
