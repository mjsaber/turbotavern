# BG Hero/Trinket Tier Data-Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Python CLI that scrapes Hearthstone Battlegrounds hero/trinket raw stats (Firestone) + identity (HearthstoneJSON) into an append-only SQLite database, with content-change snapshots, full raw-payload retention, and idempotent re-runs.

**Architecture:** A `data-pipeline/` uv project exposing `init-db`, `sync-entities`, `fetch-stats`. **Key model (from Stage-0 real data):** the *fetch unit is a URL*, the *snapshot unit is a dimension* `(source, entity_type, mmr_bracket, time_period, mode, region)`. Heroes: 1 URL per `(mmr, period)` → 1 dimension. Trinkets: 1 URL per `period` whose entries carry nested `averagePlacementAtMmr` → normalize **expands into 5 bracket dimensions**. `fetch_state` is keyed by URL (HTTP validators only); dedup reads the latest snapshot's `content_hash` per dimension. `trinket_class` (lesser/greater) comes from HearthstoneJSON `spellSchool`, not the stats feed. Concurrency: `BEGIN IMMEDIATE` + global `flock`.

**Tech Stack:** Python 3.12, `uv`, stdlib `sqlite3` + `gzip`, `httpx` (injectable transport for tests), `pyyaml`, `pytest`. Spec: `docs/superpowers/specs/2026-05-31-bg-tier-data-pipeline-design.md` (v4).

**Per-Stage Codex Review:** After each Stage's final commit, run the review at the end of this doc before starting the next Stage. Do not advance until READY.

> **Real Firestone endpoints (Stage 0, 2026-05-31), recorded in `sources.yaml`:**
> - Hero: `https://static.zerotoheroes.com/api/bgs/hero-stats/mmr-{mmr}/{period}/overview-from-hourly.gz.json` — `{heroStats:[{heroCardId, dataPoints, averagePosition, placementDistribution, totalOffered, totalPicked, tribeStats, ...}], ...}`
> - Trinket: `https://static.zerotoheroes.com/api/bgs/trinket-stats/{period}/overview-from-hourly.gz.json` — `{trinketStats:[{trinketCardId, dataPoints, pickRate, averagePlacement, averagePlacementAtMmr:[{mmr,dataPoints,placement}], pickRateAtMmr:[{mmr,dataPoints,pickRate}]}], ...}`
> - `mmr` ∈ {100,50,25,10,1}; `period` ∈ {last-patch, past-three, past-seven}. Despite `.gz.json`, server returns plain JSON with ETag + Last-Modified.

---

## File Structure

```
data-pipeline/
├── pyproject.toml
├── sources.yaml                    # real Firestone URL templates (Stage 0)
├── README.md
├── src/bgtiers/
│   ├── __init__.py
│   ├── models.py                   # FeedKey, NormalizedRow/Feed, FetchTask, FetchResult, Outcome
│   ├── db.py                       # DDL + connect() (isolation_level=None) + init_db()
│   ├── hashing.py                  # content_hash() (§5.6)
│   ├── config.py                   # load_fetch_tasks() / hsjson_cards_url()
│   ├── normalize.py                # firestone hero/trinket -> dict[mmr_bracket, NormalizedFeed]
│   ├── entities.py                 # HSJSON sync (+ trinket_class from spellSchool) + ensure_entity stub
│   ├── fetch.py                    # conditional HTTP GET by URL + retry
│   ├── load.py                     # load_url(): per-dimension dedup-by-latest-snapshot, raw retention, URL validator advance
│   ├── runlock.py                  # flock run-lock
│   └── cli.py                      # argparse wiring
└── tests/
    ├── conftest.py
    ├── fixtures/                    # REAL trimmed responses saved in Stage 0
    │   ├── firestone_heroes.json    #   {heroStats:[8 entries], ...}
    │   ├── firestone_trinkets.json  #   {trinketStats:[8 entries], ...}
    │   └── hsjson_cards.json        #   (created in Stage 5 / from real cards.json subset)
    ├── test_db.py  test_hashing.py  test_normalize.py  test_entities.py
    ├── test_fetch.py  test_load.py  test_config.py  test_runlock.py  test_cli.py
    └── test_integration.py
```

---

## Stage 0: Endpoint Discovery — DONE (2026-05-31)

Already completed before implementation: real URLs above are confirmed and the trimmed real fixtures `data-pipeline/tests/fixtures/firestone_heroes.json` and `firestone_trinkets.json` are saved (8 entries each, real field names/shape). `sources.yaml` is written in Stage 8. **Do not regenerate or overwrite these fixtures** — they are the authoritative recorded shape. The gate PASSED.

---

## Stage 1: Project Scaffold

**Files:** Create `data-pipeline/pyproject.toml`, `src/bgtiers/__init__.py`, `tests/__init__.py`, `tests/test_smoke.py`, `.gitignore`

- [ ] **Step 1: Init uv project + deps**

Run:
```bash
cd /Users/jun/code/bob_assistant/data-pipeline 2>/dev/null || mkdir -p /Users/jun/code/bob_assistant/data-pipeline
cd /Users/jun/code/bob_assistant/data-pipeline
uv init --bare --name bgtiers 2>/dev/null || true
uv add httpx pyyaml
uv add --dev pytest
```

- [ ] **Step 2: pyproject.toml (src layout + console script)**

Ensure `data-pipeline/pyproject.toml` contains:
```toml
[project]
name = "bgtiers"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = ["httpx>=0.27", "pyyaml>=6"]

[project.scripts]
bgtiers = "bgtiers.cli:main"

[dependency-groups]
dev = ["pytest>=8"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.pytest.ini_options]
pythonpath = ["src"]
testpaths = ["tests"]
```
Create empty `src/bgtiers/__init__.py` and `tests/__init__.py`.

- [ ] **Step 3: Smoke test**

Create `data-pipeline/tests/test_smoke.py`:
```python
def test_package_imports():
    import bgtiers
    assert bgtiers is not None
```

- [ ] **Step 4: Run** — `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_smoke.py -q` → Expected: 1 passed.

- [ ] **Step 5: .gitignore + commit**

Create `data-pipeline/.gitignore`:
```
*.db
*.db-wal
*.db-shm
.fetch.lock
__pycache__/
.venv/
```
```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "feat(data-pipeline): scaffold uv project for BG tier pipeline"
```

---

## Stage 2: Database Schema + init-db

**Files:** Create `src/bgtiers/db.py`, `tests/conftest.py`, `tests/test_db.py`

- [ ] **Step 1: conftest + failing test**

Create `data-pipeline/tests/conftest.py`:
```python
import pytest
from bgtiers import db


@pytest.fixture
def conn():
    c = db.connect(":memory:")
    db.init_db(c)
    yield c
    c.close()
```

Create `data-pipeline/tests/test_db.py`:
```python
import sqlite3
from bgtiers import db


def _tables(conn):
    return {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'").fetchall()}


def test_init_db_creates_all_tables(conn):
    assert _tables(conn) >= {"entity", "fetch_state", "snapshot", "entity_stats", "raw_payload"}


def test_init_db_creates_latest_view(conn):
    views = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='view'").fetchall()}
    assert "v_latest_stats" in views


def test_init_db_is_idempotent(conn):
    db.init_db(conn)  # must not raise


def test_foreign_keys_enforced(conn):
    assert conn.execute("PRAGMA foreign_keys").fetchone()[0] == 1


def test_fetch_state_is_keyed_by_url(conn):
    # one row per (source, raw_url); a second insert of same key conflicts
    conn.execute("INSERT INTO fetch_state (source, raw_url, last_checked_at) VALUES ('firestone','http://u','t')")
    try:
        conn.execute("INSERT INTO fetch_state (source, raw_url, last_checked_at) VALUES ('firestone','http://u','t2')")
        dup = True
    except sqlite3.IntegrityError:
        dup = False
    assert dup is False


def test_entity_type_check_constraint(conn):
    conn.execute("INSERT INTO entity (entity_type, card_id, first_seen_at, last_seen_at) VALUES ('hero','H1','t','t')")
    try:
        conn.execute("INSERT INTO entity (entity_type, card_id, first_seen_at, last_seen_at) VALUES ('bogus','H2','t','t')")
        raised = False
    except sqlite3.IntegrityError:
        raised = True
    assert raised
```

- [ ] **Step 2: Run to verify it fails** — `uv run pytest tests/test_db.py -q` → FAIL (`bgtiers.db` missing).

- [ ] **Step 3: Implement `db.py`**

Create `data-pipeline/src/bgtiers/db.py`:
```python
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
```

- [ ] **Step 4: Run** — `uv run pytest tests/test_db.py -q` → Expected: 6 passed.

- [ ] **Step 5: Commit**
```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "feat(data-pipeline): SQLite schema (5 tables, URL-keyed fetch_state, latest view)"
```

---

## Stage 3: Models + content_hash

**Files:** Create `src/bgtiers/models.py`, `src/bgtiers/hashing.py`, `tests/test_hashing.py`

- [ ] **Step 1: models.py**

Create `data-pipeline/src/bgtiers/models.py`:
```python
"""Plain dataclasses shared across pipeline stages. No logic."""
from __future__ import annotations
from dataclasses import dataclass, field
import enum


@dataclass(frozen=True)
class FeedKey:
    source: str
    entity_type: str          # 'hero' | 'trinket'
    mmr_bracket: str          # '100'|'50'|'25'|'10'|'1'
    time_period: str          # 'last-patch'|'past-three'|'past-seven'
    mode: str = "solo"
    region: str = "global"


@dataclass(frozen=True)
class FetchTask:
    """One HTTP fetch. url_mmr is set for heroes (mmr is in the URL); None for
    trinkets (mmr comes from each entry's averagePlacementAtMmr)."""
    source: str
    entity_type: str
    raw_url: str
    time_period: str
    url_mmr: str | None


@dataclass
class NormalizedRow:
    card_id: str
    avg_placement: float
    data_points: int
    pick_rate: float | None = None
    placement_distribution: list | None = None
    extra_json: dict = field(default_factory=dict)


@dataclass
class NormalizedFeed:
    rows: list[NormalizedRow]
    patch: str | None
    schema_fingerprint: list[str]


@dataclass
class FetchResult:
    raw_url: str
    status: int               # 200 | 304
    body: bytes | None
    etag: str | None
    last_modified: str | None


class Outcome(enum.Enum):
    INSERTED = "inserted"
    UNCHANGED = "unchanged"
    NOT_MODIFIED = "not_modified"   # HTTP 304 for the whole URL
```

- [ ] **Step 2: failing hashing test**

Create `data-pipeline/tests/test_hashing.py`:
```python
from bgtiers.hashing import content_hash
from bgtiers.models import NormalizedRow, NormalizedFeed


def _feed(rows, patch="27.0", fp=None):
    return NormalizedFeed(rows=rows, patch=patch, schema_fingerprint=fp or ["avg", "card_id"])


def test_hash_is_stable_regardless_of_row_order():
    a = _feed([NormalizedRow("A", 4.1, 100), NormalizedRow("B", 3.9, 200)])
    b = _feed([NormalizedRow("B", 3.9, 200), NormalizedRow("A", 4.1, 100)])
    assert content_hash(a) == content_hash(b)


def test_hash_changes_when_stat_changes():
    assert content_hash(_feed([NormalizedRow("A", 4.1, 100)])) != \
           content_hash(_feed([NormalizedRow("A", 4.2, 100)]))


def test_hash_changes_when_patch_changes():
    assert content_hash(_feed([NormalizedRow("A", 4.1, 100)], patch="27.0")) != \
           content_hash(_feed([NormalizedRow("A", 4.1, 100)], patch="27.2"))


def test_hash_changes_when_schema_fingerprint_changes():
    assert content_hash(_feed([NormalizedRow("A", 4.1, 100)], fp=["a", "b"])) != \
           content_hash(_feed([NormalizedRow("A", 4.1, 100)], fp=["a", "b", "c"]))


def test_float_rounding_ignores_sub_micro_noise():
    assert content_hash(_feed([NormalizedRow("A", 4.1000001, 100)])) == \
           content_hash(_feed([NormalizedRow("A", 4.1000002, 100)]))
```

- [ ] **Step 3: Run to verify it fails** — `uv run pytest tests/test_hashing.py -q` → FAIL.

- [ ] **Step 4: Implement `hashing.py`**

Create `data-pipeline/src/bgtiers/hashing.py`:
```python
"""content_hash canonicalization contract — spec §5.6. Locked by tests."""
from __future__ import annotations
import hashlib
import json

from .models import NormalizedFeed

_FLOAT_NDIGITS = 6


def _canon(obj):
    """Floats -> fixed-decimal STRINGS so the hash is stable across implementations
    (4.1 -> '4.100000'), independent of float repr. ints/None untouched."""
    if isinstance(obj, bool):
        return obj
    if isinstance(obj, float):
        return f"{obj:.{_FLOAT_NDIGITS}f}"
    if isinstance(obj, dict):
        return {k: _canon(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_canon(v) for v in obj]
    return obj


def content_hash(feed: NormalizedFeed) -> str:
    stats = sorted(
        ({
            "card_id": r.card_id,
            "avg_placement": r.avg_placement,
            "data_points": r.data_points,
            "pick_rate": r.pick_rate,
            "placement_distribution": r.placement_distribution,
            "extra_json": r.extra_json,
        } for r in feed.rows),
        key=lambda r: r["card_id"],
    )
    payload = {
        "stats": _canon(stats),
        "patch": feed.patch,
        "schema_fingerprint": sorted(feed.schema_fingerprint),
    }
    serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()
```

- [ ] **Step 5: Run** — `uv run pytest tests/test_hashing.py -q` → Expected: 5 passed.

- [ ] **Step 6: Commit**
```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "feat(data-pipeline): shared models + content_hash with provenance"
```

---

## Stage 4: Normalize (real fields + trinket MMR expansion)

**Files:** Create `src/bgtiers/normalize.py`, `tests/test_normalize.py`. (Fixtures already exist from Stage 0.)

`normalize_firestone` returns `dict[mmr_bracket, NormalizedFeed]`: heroes → one entry (the URL's mmr); trinkets → five entries (expanded from `averagePlacementAtMmr`).

- [ ] **Step 1: Failing tests** (assert against the REAL fixtures; derive expectations from the data, don't hardcode private numbers)

Create `data-pipeline/tests/test_normalize.py`:
```python
import json
import pathlib
import pytest

from bgtiers import normalize

FIX = pathlib.Path(__file__).parent / "fixtures"


def _load(name):
    return json.loads((FIX / name).read_text())


def test_hero_normalizes_to_single_bracket():
    raw = _load("firestone_heroes.json")
    feeds = normalize.normalize_firestone(raw, entity_type="hero", url_mmr="100")
    assert set(feeds) == {"100"}                      # one dimension = the URL's mmr
    feed = feeds["100"]
    assert len(feed.rows) == len(raw["heroStats"])
    first = raw["heroStats"][0]
    row = {r.card_id: r for r in feed.rows}[first["heroCardId"]]
    assert row.avg_placement == first["averagePosition"]
    assert row.data_points == first["dataPoints"]
    assert "averagePosition" in feed.schema_fingerprint


def test_trinket_expands_into_five_brackets():
    raw = _load("firestone_trinkets.json")
    feeds = normalize.normalize_firestone(raw, entity_type="trinket", url_mmr=None)
    assert set(feeds) == {"100", "50", "25", "10", "1"}
    entry = raw["trinketStats"][0]
    cid = entry["trinketCardId"]
    by_mmr = {m["mmr"]: m for m in entry["averagePlacementAtMmr"]}
    # bracket '10' row's avg_placement == that entry's mmr-10 placement
    row10 = {r.card_id: r for r in feeds["10"].rows}[cid]
    assert row10.avg_placement == by_mmr[10]["placement"]
    assert row10.data_points == by_mmr[10]["dataPoints"]


def test_trinket_empty_bracket_is_dropped_not_emitted():
    # T1 only has mmr-100 data -> only the '100' dimension is produced, not an empty '10'
    raw = {"trinketStats": [
        {"trinketCardId": "T1", "dataPoints": 100, "pickRate": 0.1, "averagePlacement": 4.0,
         "averagePlacementAtMmr": [{"mmr": 100, "dataPoints": 100, "placement": 4.0}],
         "pickRateAtMmr": [{"mmr": 100, "dataPoints": 100, "pickRate": 0.1}]},
    ]}
    feeds = normalize.normalize_firestone(raw, entity_type="trinket", url_mmr=None)
    assert set(feeds) == {"100"}                      # empty brackets dropped
    assert [r.card_id for r in feeds["100"].rows] == ["T1"]


def test_validate_rejects_empty_hero_feed():
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone({"heroStats": []}, entity_type="hero", url_mmr="100")


def test_validate_rejects_empty_trinket_url():
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone({"trinketStats": []}, entity_type="trinket", url_mmr=None)


def test_validate_rejects_trinket_entry_missing_mmr_data():
    bad = {"trinketStats": [{"trinketCardId": "T", "dataPoints": 10, "averagePlacement": 4.0}]}  # no averagePlacementAtMmr
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="trinket", url_mmr=None)


def test_validate_rejects_out_of_range_placement():
    bad = {"heroStats": [{"heroCardId": "X", "dataPoints": 10, "averagePosition": 9.9}]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero", url_mmr="100")


def test_validate_rejects_duplicate_card_ids():
    bad = {"heroStats": [
        {"heroCardId": "X", "dataPoints": 10, "averagePosition": 4.0},
        {"heroCardId": "X", "dataPoints": 11, "averagePosition": 4.1},
    ]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero", url_mmr="100")


def test_validate_rejects_missing_core_field():
    bad = {"heroStats": [{"heroCardId": "X", "dataPoints": 10}]}  # no averagePosition
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero", url_mmr="100")


def test_validate_rejects_non_numeric_placement():
    bad = {"heroStats": [{"heroCardId": "X", "dataPoints": 10, "averagePosition": "low"}]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero", url_mmr="100")
```

- [ ] **Step 2: Run to verify it fails** — `uv run pytest tests/test_normalize.py -q` → FAIL.

- [ ] **Step 3: Implement `normalize.py`**

Create `data-pipeline/src/bgtiers/normalize.py`:
```python
"""Firestone JSON -> dict[mmr_bracket, NormalizedFeed] + validation (spec §4.1)."""
from __future__ import annotations

from .models import NormalizedRow, NormalizedFeed

BRACKETS = ("100", "50", "25", "10", "1")
_MAX_ROWS = 5000


class ValidationError(Exception):
    pass


def _schema_fingerprint(raw: dict, array_field: str) -> list[str]:
    keys = set(raw.keys())
    for row in raw.get(array_field, []):
        keys.update(row.keys())
    return sorted(keys)


def _num(value, card_id, field_name):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError(f"{field_name} not numeric for {card_id}: {value!r}")
    return value


def _mk_row(card_id, avg, dp, pick_rate, dist, extra) -> NormalizedRow:
    avg = float(_num(avg, card_id, "avg"))
    if not (1.0 <= avg <= 8.0):
        raise ValidationError(f"avg_placement out of range for {card_id}: {avg}")
    dp_n = _num(dp, card_id, "data_points")
    if isinstance(dp_n, float) and not dp_n.is_integer():
        raise ValidationError(f"data_points not integral for {card_id}: {dp_n}")
    dp_i = int(dp_n)
    if dp_i < 0:
        raise ValidationError(f"negative data_points for {card_id}: {dp_i}")
    if dist is not None:
        # Real feed stores 8 objects ({rank, percentage, totalMatches}), not bare numbers.
        # Validate it's a length-8 list (BG has 8 ranks); store the objects verbatim.
        if not isinstance(dist, list) or len(dist) != 8:
            raise ValidationError(f"bad placement distribution for {card_id}")
    return NormalizedRow(card_id, avg, dp_i, pick_rate, dist, extra)


def _build_feed(rows: list[NormalizedRow], raw: dict, fingerprint: list[str]) -> NormalizedFeed:
    seen = set()
    for r in rows:
        if r.card_id in seen:
            raise ValidationError(f"duplicate card_id in dimension: {r.card_id}")
        seen.add(r.card_id)
    return NormalizedFeed(rows=rows, patch=raw.get("patch"), schema_fingerprint=fingerprint)


def _normalize_hero(raw: dict, url_mmr: str) -> dict[str, NormalizedFeed]:
    stats = raw.get("heroStats", [])
    if not stats:
        raise ValidationError("empty hero feed")
    if len(stats) > _MAX_ROWS:
        raise ValidationError(f"implausible row count: {len(stats)}")
    fp = _schema_fingerprint(raw, "heroStats")
    rows = []
    for it in stats:
        cid = it.get("heroCardId")
        if not cid:
            raise ValidationError(f"hero row missing heroCardId: {it!r}")
        if "averagePosition" not in it or "dataPoints" not in it:
            raise ValidationError(f"hero row missing core field: {cid}")
        offered, picked = it.get("totalOffered"), it.get("totalPicked")
        pick_rate = (picked / offered) if (offered and picked is not None) else None
        extra = {k: v for k, v in it.items()
                 if k not in ("heroCardId", "averagePosition", "dataPoints",
                              "placementDistribution", "totalOffered", "totalPicked")}
        rows.append(_mk_row(cid, it["averagePosition"], it["dataPoints"], pick_rate,
                            it.get("placementDistribution"), extra))
    return {url_mmr: _build_feed(rows, raw, fp)}


def _normalize_trinket(raw: dict) -> dict[str, NormalizedFeed]:
    stats = raw.get("trinketStats", [])
    if not stats:
        raise ValidationError("empty trinket feed")
    if len(stats) > _MAX_ROWS:
        raise ValidationError(f"implausible row count: {len(stats)}")
    fp = _schema_fingerprint(raw, "trinketStats")
    per_bracket: dict[str, list[NormalizedRow]] = {b: [] for b in BRACKETS}
    for it in stats:
        cid = it.get("trinketCardId")
        if not cid:
            raise ValidationError(f"trinket row missing trinketCardId: {it!r}")
        ap_list = it.get("averagePlacementAtMmr")
        if not ap_list:   # missing or empty -> core trinket data absent -> reject the URL
            raise ValidationError(f"trinket {cid} missing averagePlacementAtMmr")
        pr_by_mmr = {p["mmr"]: p.get("pickRate") for p in it.get("pickRateAtMmr", [])}
        extra = {k: v for k, v in it.items()
                 if k not in ("trinketCardId", "averagePlacement", "dataPoints", "pickRate",
                              "averagePlacementAtMmr", "pickRateAtMmr")}
        for ap in ap_list:
            bracket = str(ap.get("mmr"))
            if bracket not in per_bracket:
                continue
            if "placement" not in ap or "dataPoints" not in ap:
                raise ValidationError(f"trinket {cid} mmr {bracket} missing core field")
            per_bracket[bracket].append(
                _mk_row(cid, ap["placement"], ap["dataPoints"], pr_by_mmr.get(ap.get("mmr")),
                        None, dict(extra)))
    # URL-level feed (trinketStats) is validated non-empty above. A per-bracket expansion
    # may legitimately be empty (e.g. low-sample top-1% bracket) -> drop it, don't emit an
    # empty dimension (load would otherwise create a snapshot with zero stat rows).
    return {b: _build_feed(rows, raw, fp) for b, rows in per_bracket.items() if rows}


def normalize_firestone(raw: dict, entity_type: str, url_mmr: str | None) -> dict[str, NormalizedFeed]:
    if entity_type == "hero":
        assert url_mmr is not None, "hero normalize needs the URL's mmr bracket"
        return _normalize_hero(raw, url_mmr)
    if entity_type == "trinket":
        return _normalize_trinket(raw)
    raise ValidationError(f"unknown entity_type: {entity_type}")
```

- [ ] **Step 4: Run** — `uv run pytest tests/test_normalize.py -q` → Expected: 10 passed.

- [ ] **Step 5: Commit**
```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "feat(data-pipeline): firestone normalize with trinket MMR expansion + validation"
```

---

## Stage 5: Entity Sync + Stub

**Files:** Create `src/bgtiers/entities.py`, `tests/test_entities.py`, `tests/fixtures/hsjson_cards.json`

- [ ] **Step 1: HSJSON fixture** (real-shaped subset; trinket carries `spellSchool`)

Create `data-pipeline/tests/fixtures/hsjson_cards.json`:
```json
[
  {"id": "BG_HERO_001", "dbfId": 50001, "name": "Test Hero One", "type": "HERO", "set": "BATTLEGROUNDS"},
  {"id": "BG_HERO_002", "dbfId": 50002, "name": "Test Hero Two", "type": "HERO", "set": "BATTLEGROUNDS"},
  {"id": "BG30_MagicItem_902", "dbfId": 60902, "name": "Holy Mallet", "type": "BATTLEGROUND_TRINKET", "set": "BATTLEGROUNDS", "spellSchool": "GREATER_TRINKET"},
  {"id": "BG30_MagicItem_301", "dbfId": 60301, "name": "Lesser One", "type": "BATTLEGROUND_TRINKET", "set": "BATTLEGROUNDS", "spellSchool": "LESSER_TRINKET"},
  {"id": "AT_001", "dbfId": 100, "name": "Constructed Card", "type": "MINION", "set": "TGT"}
]
```

- [ ] **Step 2: Failing tests**

Create `data-pipeline/tests/test_entities.py`:
```python
import json
import pathlib
from bgtiers import entities

FIX = pathlib.Path(__file__).parent / "fixtures"


def _cards():
    return json.loads((FIX / "hsjson_cards.json").read_text())


def test_sync_inserts_heroes_and_trinkets(conn):
    n = entities.sync_entities(conn, _cards(), now="2026-05-31T00:00:00Z")
    rows = conn.execute("SELECT card_id, entity_type, trinket_class FROM entity").fetchall()
    by = {r["card_id"]: r for r in rows}
    assert set(by) == {"BG_HERO_001", "BG_HERO_002", "BG30_MagicItem_902", "BG30_MagicItem_301"}
    assert by["BG_HERO_001"]["entity_type"] == "hero"
    assert by["BG30_MagicItem_902"]["entity_type"] == "trinket"
    assert n == 4                                     # constructed card excluded


def test_sync_sets_trinket_class_from_spellschool(conn):
    entities.sync_entities(conn, _cards(), now="t")
    cls = {r["card_id"]: r["trinket_class"] for r in
           conn.execute("SELECT card_id, trinket_class FROM entity").fetchall()}
    assert cls["BG30_MagicItem_902"] == "greater"
    assert cls["BG30_MagicItem_301"] == "lesser"


def test_sync_is_idempotent(conn):
    entities.sync_entities(conn, _cards(), now="t")
    entities.sync_entities(conn, _cards(), now="t")
    assert conn.execute("SELECT COUNT(*) FROM entity").fetchone()[0] == 4


def test_ensure_entity_creates_stub_for_unknown_card(conn):
    eid = entities.ensure_entity(conn, "trinket", "BG30_MagicItem_999", now="t")
    row = conn.execute("SELECT * FROM entity WHERE entity_id=?", (eid,)).fetchone()
    assert row["card_id"] == "BG30_MagicItem_999"
    assert row["name"] is None and row["trinket_class"] is None  # stub
    assert row["entity_type"] == "trinket"


def test_ensure_entity_returns_existing_id(conn):
    a = entities.ensure_entity(conn, "hero", "BG_HERO_X", now="t")
    b = entities.ensure_entity(conn, "hero", "BG_HERO_X", now="t")
    assert a == b


def test_sync_backfills_existing_stub(conn):
    entities.ensure_entity(conn, "trinket", "BG30_MagicItem_301", now="t0")  # stub, class null
    entities.sync_entities(conn, _cards(), now="t1")
    row = conn.execute("SELECT name, dbf_id, trinket_class FROM entity WHERE card_id='BG30_MagicItem_301'").fetchone()
    assert row["name"] == "Lesser One" and row["dbf_id"] == 60301 and row["trinket_class"] == "lesser"


def test_sync_does_not_clobber_known_name_with_null(conn):
    entities.sync_entities(conn, _cards(), now="t0")                       # name set
    entities.sync_entities(conn, [{"id": "BG_HERO_001", "type": "HERO", "set": "BATTLEGROUNDS"}], now="t1")  # no name/dbfId
    row = conn.execute("SELECT name, dbf_id FROM entity WHERE card_id='BG_HERO_001'").fetchone()
    assert row["name"] == "Test Hero One" and row["dbf_id"] == 50001       # preserved
```

- [ ] **Step 3: Run to verify it fails** — `uv run pytest tests/test_entities.py -q` → FAIL.

- [ ] **Step 4: Implement `entities.py`**

Create `data-pipeline/src/bgtiers/entities.py`:
```python
"""Identity sync (HearthstoneJSON) + stub creation for unknown card_ids."""
from __future__ import annotations
import sqlite3

_IMG_TMPL = "https://art.hearthstonejson.com/v1/256x/{card_id}.jpg"
_SPELLSCHOOL_TO_CLASS = {"LESSER_TRINKET": "lesser", "GREATER_TRINKET": "greater"}


def _is_bg_hero(card: dict) -> bool:
    return card.get("type") == "HERO" and "BATTLEGROUNDS" in str(card.get("set", "")).upper()


def _is_bg_trinket(card: dict) -> bool:
    return card.get("type") == "BATTLEGROUND_TRINKET"


def sync_entities(conn: sqlite3.Connection, cards: list[dict], now: str) -> int:
    """Upsert BG heroes + trinkets from HearthstoneJSON. Trinket lesser/greater comes
    from spellSchool. Returns rows touched."""
    touched = 0
    for card in cards:
        if _is_bg_hero(card):
            etype, tclass = "hero", None
        elif _is_bg_trinket(card):
            etype = "trinket"
            tclass = _SPELLSCHOOL_TO_CLASS.get(card.get("spellSchool"))
        else:
            continue
        cid = card["id"]
        conn.execute(
            """
            INSERT INTO entity (entity_type, card_id, dbf_id, name, image_url,
                                trinket_class, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (entity_type, card_id) DO UPDATE SET
                dbf_id        = COALESCE(excluded.dbf_id, entity.dbf_id),
                name          = COALESCE(excluded.name, entity.name),
                image_url     = COALESCE(entity.image_url, excluded.image_url),
                trinket_class = COALESCE(excluded.trinket_class, entity.trinket_class),
                last_seen_at  = excluded.last_seen_at
            """,
            (etype, cid, card.get("dbfId"), card.get("name"),
             _IMG_TMPL.format(card_id=cid), tclass, now, now),
        )
        touched += 1
    return touched


def ensure_entity(conn: sqlite3.Connection, entity_type: str, card_id: str, now: str) -> int:
    """Return entity_id for (entity_type, card_id); create a stub if unknown.
    trinket_class is NOT set here (it comes from sync_entities/HSJSON)."""
    row = conn.execute(
        "SELECT entity_id FROM entity WHERE entity_type=? AND card_id=?",
        (entity_type, card_id),
    ).fetchone()
    if row:
        conn.execute("UPDATE entity SET last_seen_at=? WHERE entity_id=?", (now, row["entity_id"]))
        return row["entity_id"]
    cur = conn.execute(
        "INSERT INTO entity (entity_type, card_id, first_seen_at, last_seen_at) VALUES (?,?,?,?)",
        (entity_type, card_id, now, now),
    )
    return cur.lastrowid
```

- [ ] **Step 5: Run** — `uv run pytest tests/test_entities.py -q` → Expected: 7 passed.

- [ ] **Step 6: Commit**
```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "feat(data-pipeline): HSJSON entity sync (trinket_class from spellSchool) + stub"
```

---

## Stage 6: Load (per-dimension dedup, URL validators)

**Files:** Create `src/bgtiers/load.py`, `tests/test_load.py`

`load_url` processes one URL's feeds (1 for heroes, 5 for trinkets) in one `BEGIN IMMEDIATE` transaction: per-dimension dedup vs latest snapshot, append on change, then advance the URL's validators.

- [ ] **Step 1: Failing tests**

Create `data-pipeline/tests/test_load.py`:
```python
import gzip
import pytest

from bgtiers import load, db
from bgtiers.models import NormalizedRow, NormalizedFeed, Outcome


def _feed(avg=3.9, card="BG_HERO_001"):
    return NormalizedFeed([NormalizedRow(card, avg, 5200, None, [1]*8)], "27.0", ["averagePosition"])


def _load_hero(conn, body, etag, lm, feed, now, mmr="100"):
    return load.load_url(conn, source="firestone", entity_type="hero",
                         raw_url="http://h", time_period="last-patch", status=200,
                         body=body, etag=etag, last_modified=lm,
                         feeds_by_bracket={mmr: feed}, now=now)


def test_first_load_inserts_snapshot_stats_raw(conn):
    out = _load_hero(conn, b'{"raw":1}', '"e1"', "lm1", _feed(), "t1")
    assert out["100"] == Outcome.INSERTED
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 1
    assert conn.execute("SELECT COUNT(*) FROM entity_stats").fetchone()[0] == 1
    assert gzip.decompress(conn.execute("SELECT body_gzip FROM raw_payload").fetchone()[0]) == b'{"raw":1}'
    fs = conn.execute("SELECT etag, last_modified FROM fetch_state WHERE raw_url='http://h'").fetchone()
    assert fs["etag"] == '"e1"' and fs["last_modified"] == "lm1"


def test_unchanged_skips_insert_but_advances_url_validators(conn):
    _load_hero(conn, b'A', '"e1"', "lm1", _feed(), "t1")
    out = _load_hero(conn, b'A', '"e2"', "lm2", _feed(), "t2")
    assert out["100"] == Outcome.UNCHANGED
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 1
    fs = conn.execute("SELECT etag, last_modified FROM fetch_state").fetchone()
    assert fs["etag"] == '"e2"' and fs["last_modified"] == "lm2"


def test_changed_content_appends(conn):
    _load_hero(conn, b'A', '"e1"', "lm1", _feed(avg=3.9), "t1")
    _load_hero(conn, b'B', '"e2"', "lm2", _feed(avg=4.4), "t2")
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 2


def test_a_b_a_reappearance_recorded(conn):
    _load_hero(conn, b'A', '"e1"', "lm1", _feed(avg=3.9), "t1")
    _load_hero(conn, b'B', '"e2"', "lm2", _feed(avg=4.4), "t2")
    _load_hero(conn, b'A', '"e3"', "lm3", _feed(avg=3.9), "t3")
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 3


def test_304_only_updates_last_checked(conn):
    _load_hero(conn, b'A', '"e1"', "lm1", _feed(), "t1")
    out = load.load_url(conn, source="firestone", entity_type="hero", raw_url="http://h",
                        time_period="last-patch", status=304, body=None, etag=None,
                        last_modified=None, feeds_by_bracket=None, now="t2")
    assert out == Outcome.NOT_MODIFIED
    fs = conn.execute("SELECT etag, last_checked_at FROM fetch_state").fetchone()
    assert fs["etag"] == '"e1"' and fs["last_checked_at"] == "t2"   # validators preserved


def test_200_without_etag_clears_stale_validator(conn):
    _load_hero(conn, b'A', '"e1"', "lm1", _feed(avg=3.9), "t1")
    _load_hero(conn, b'B', None, None, _feed(avg=4.4), "t2")
    fs = conn.execute("SELECT etag, last_modified FROM fetch_state").fetchone()
    assert fs["etag"] is None and fs["last_modified"] is None


def test_trinket_one_url_five_brackets(conn):
    feeds = {b: NormalizedFeed([NormalizedRow("BG30_MagicItem_902", 4.0 + i*0.1, 100, None, None)],
                               "27.0", ["trinketCardId"]) for i, b in enumerate(("100","50","25","10","1"))}
    out = load.load_url(conn, source="firestone", entity_type="trinket", raw_url="http://t",
                        time_period="last-patch", status=200, body=b'BODY', etag='"e"',
                        last_modified="lm", feeds_by_bracket=feeds, now="t1")
    assert all(v == Outcome.INSERTED for v in out.values())
    assert conn.execute("SELECT COUNT(*) FROM snapshot WHERE entity_type='trinket'").fetchone()[0] == 5
    assert conn.execute("SELECT COUNT(DISTINCT mmr_bracket) FROM snapshot").fetchone()[0] == 5
    # one fetch_state row for the URL (not five)
    assert conn.execute("SELECT COUNT(*) FROM fetch_state WHERE raw_url='http://t'").fetchone()[0] == 1


def test_unknown_card_id_gets_stub(conn):
    feed = NormalizedFeed([NormalizedRow("BG_HERO_NEW", 4.0, 10)], "27.0", ["heroCardId"])
    _load_hero(conn, b'A', '"e1"', "lm1", feed, "t1")
    assert conn.execute("SELECT name FROM entity WHERE card_id='BG_HERO_NEW'").fetchone()["name"] is None


def test_rollback_on_failure_leaves_db_clean(conn):
    import sqlite3
    bad = NormalizedFeed([NormalizedRow("BG_HERO_001", 9.0, 100)], "27.0", ["heroCardId"])  # 9.0 > CHECK
    with pytest.raises(sqlite3.IntegrityError):
        _load_hero(conn, b'A', '"e1"', "lm1", bad, "t1")
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM fetch_state").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM raw_payload").fetchone()[0] == 0
```

- [ ] **Step 2: Run to verify it fails** — `uv run pytest tests/test_load.py -q` → FAIL.

- [ ] **Step 3: Implement `load.py`**

Create `data-pipeline/src/bgtiers/load.py`:
```python
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
```

- [ ] **Step 4: Run** — `uv run pytest tests/test_load.py -q` → Expected: 9 passed.

- [ ] **Step 5: Commit**
```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "feat(data-pipeline): load_url with per-dimension dedup + trinket 5-bracket fan-out"
```

---

## Stage 7: Fetch (conditional HTTP by URL)

**Files:** Create `src/bgtiers/fetch.py`, `tests/test_fetch.py`

- [ ] **Step 1: Failing tests** (httpx MockTransport — no network)

Create `data-pipeline/tests/test_fetch.py`:
```python
import httpx
from bgtiers import fetch


def _client(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_fetch_200_returns_body_and_validators():
    def handler(req):
        assert "If-None-Match" not in req.headers
        return httpx.Response(200, content=b'{"ok":1}', headers={"ETag": '"e1"', "Last-Modified": "lm1"})
    res = fetch.fetch_url(_client(handler), "http://x", prev_etag=None, prev_last_modified=None)
    assert res.status == 200 and res.body == b'{"ok":1}'
    assert res.etag == '"e1"' and res.last_modified == "lm1"


def test_fetch_sends_conditional_headers_and_handles_304():
    seen = {}
    def handler(req):
        seen["inm"] = req.headers.get("If-None-Match")
        seen["ims"] = req.headers.get("If-Modified-Since")
        return httpx.Response(304)
    res = fetch.fetch_url(_client(handler), "http://x", '"e1"', "lm1")
    assert res.status == 304 and res.body is None
    assert seen["inm"] == '"e1"' and seen["ims"] == "lm1"


def test_fetch_retries_then_succeeds():
    calls = {"n": 0}
    def handler(req):
        calls["n"] += 1
        return httpx.Response(503) if calls["n"] < 2 else httpx.Response(200, content=b'ok', headers={"ETag": '"e"'})
    res = fetch.fetch_url(_client(handler), "http://x", None, None, max_retries=3, backoff=0)
    assert res.status == 200 and calls["n"] == 2
```

- [ ] **Step 2: Run to verify it fails** — `uv run pytest tests/test_fetch.py -q` → FAIL.

- [ ] **Step 3: Implement `fetch.py`**

Create `data-pipeline/src/bgtiers/fetch.py`:
```python
"""HTTP fetch with conditional requests + retry. Knows nothing about the DB."""
from __future__ import annotations
import time
import httpx

from .models import FetchResult


def fetch_url(client: httpx.Client, raw_url: str, prev_etag: str | None,
              prev_last_modified: str | None, max_retries: int = 3,
              backoff: float = 0.5) -> FetchResult:
    headers = {}
    if prev_etag:
        headers["If-None-Match"] = prev_etag
    if prev_last_modified:
        headers["If-Modified-Since"] = prev_last_modified

    last_exc = None
    for attempt in range(max_retries):
        try:
            resp = client.get(raw_url, headers=headers)
            if resp.status_code == 304:
                return FetchResult(raw_url, 304, None, prev_etag, prev_last_modified)
            if resp.status_code >= 500:
                raise httpx.HTTPStatusError("server error", request=resp.request, response=resp)
            resp.raise_for_status()
            return FetchResult(raw_url, 200, resp.content,
                               resp.headers.get("ETag"), resp.headers.get("Last-Modified"))
        except httpx.HTTPError as exc:
            last_exc = exc
            if attempt < max_retries - 1 and backoff:
                time.sleep(backoff * (2 ** attempt))
    raise last_exc
```

- [ ] **Step 4: Run** — `uv run pytest tests/test_fetch.py -q` → Expected: 3 passed.

- [ ] **Step 5: Commit**
```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "feat(data-pipeline): conditional HTTP fetch by URL with retry"
```

---

## Stage 8: Config + Run-Lock + CLI

**Files:** Create `src/bgtiers/config.py`, `src/bgtiers/runlock.py`, `src/bgtiers/cli.py`, `data-pipeline/sources.yaml`, `tests/test_config.py`, `tests/test_runlock.py`, `tests/test_cli.py`

- [ ] **Step 1: Write the REAL sources.yaml**

Create `data-pipeline/sources.yaml` (Stage-0 confirmed templates):
```yaml
firestone:
  hero_url: "https://static.zerotoheroes.com/api/bgs/hero-stats/mmr-{mmr}/{period}/overview-from-hourly.gz.json"
  trinket_url: "https://static.zerotoheroes.com/api/bgs/trinket-stats/{period}/overview-from-hourly.gz.json"
  brackets: ["100", "50", "25", "10", "1"]
  periods: ["last-patch"]
hsjson:
  cards_url: "https://api.hearthstonejson.com/v1/latest/enUS/cards.json"
```

- [ ] **Step 2: Failing config test**

Create `data-pipeline/tests/test_config.py`:
```python
import textwrap
from bgtiers import config


def _write(tmp_path, body):
    p = tmp_path / "sources.yaml"
    p.write_text(textwrap.dedent(body))
    return str(p)


def test_load_fetch_tasks_hero_per_mmr_trinket_per_period(tmp_path):
    path = _write(tmp_path, """
        firestone:
          hero_url: "http://h/{mmr}/{period}.json"
          trinket_url: "http://t/{period}.json"
          brackets: ["100", "10"]
          periods: ["last-patch"]
        hsjson:
          cards_url: "http://cards"
    """)
    tasks = config.load_fetch_tasks(path)
    heroes = [t for t in tasks if t.entity_type == "hero"]
    trinkets = [t for t in tasks if t.entity_type == "trinket"]
    assert len(heroes) == 2                            # 2 brackets x 1 period
    assert len(trinkets) == 1                          # 1 period (no mmr in URL)
    assert {t.url_mmr for t in heroes} == {"100", "10"}
    assert trinkets[0].url_mmr is None
    assert trinkets[0].raw_url == "http://t/last-patch.json"
    assert {t.raw_url for t in heroes} == {"http://h/100/last-patch.json", "http://h/10/last-patch.json"}


def test_hsjson_cards_url(tmp_path):
    path = _write(tmp_path, """
        firestone: {hero_url: "h", trinket_url: "t", brackets: ["100"], periods: ["last-patch"]}
        hsjson: {cards_url: "http://cards"}
    """)
    assert config.hsjson_cards_url(path) == "http://cards"
```

- [ ] **Step 3: Run to verify it fails** — `uv run pytest tests/test_config.py -q` → FAIL.

- [ ] **Step 4: Implement `config.py`**

Create `data-pipeline/src/bgtiers/config.py`:
```python
"""Load sources.yaml -> list[FetchTask]."""
from __future__ import annotations
import yaml

from .models import FetchTask


def _read(path: str) -> dict:
    with open(path) as fh:
        return yaml.safe_load(fh)


def load_fetch_tasks(path: str) -> list[FetchTask]:
    fs = _read(path)["firestone"]
    tasks: list[FetchTask] = []
    for period in fs["periods"]:
        # heroes: one URL per (mmr, period)
        for mmr in fs["brackets"]:
            tasks.append(FetchTask("firestone", "hero",
                                   fs["hero_url"].format(mmr=mmr, period=period), period, mmr))
        # trinkets: one URL per period (mmr expanded at normalize time)
        tasks.append(FetchTask("firestone", "trinket",
                               fs["trinket_url"].format(period=period), period, None))
    return tasks


def hsjson_cards_url(path: str) -> str:
    return _read(path)["hsjson"]["cards_url"]
```

- [ ] **Step 5: Run** — `uv run pytest tests/test_config.py -q` → Expected: 2 passed.

- [ ] **Step 6: Failing run-lock test**

Create `data-pipeline/tests/test_runlock.py`:
```python
import pytest
from bgtiers import runlock


def test_lock_acquired_and_released(tmp_path):
    p = tmp_path / ".fetch.lock"
    with runlock.run_lock(str(p)):
        assert p.exists()


def test_second_lock_fails_while_held(tmp_path):
    p = tmp_path / ".fetch.lock"
    with runlock.run_lock(str(p)):
        with pytest.raises(runlock.AlreadyRunning):
            with runlock.run_lock(str(p)):
                pass
```

- [ ] **Step 7: Run to verify it fails** — `uv run pytest tests/test_runlock.py -q` → FAIL.

- [ ] **Step 8: Implement `runlock.py`**

Create `data-pipeline/src/bgtiers/runlock.py`:
```python
"""Global process run-lock via flock (spec §4.3). Non-blocking: fail fast if held."""
from __future__ import annotations
import contextlib
import fcntl
import os


class AlreadyRunning(Exception):
    pass


@contextlib.contextmanager
def run_lock(path: str):
    fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o644)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise AlreadyRunning(f"another fetch-stats run holds {path}") from exc
        try:
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)
```

- [ ] **Step 9: Run** — `uv run pytest tests/test_runlock.py -q` → Expected: 2 passed.

- [ ] **Step 10: Failing CLI test**

Create `data-pipeline/tests/test_cli.py`:
```python
import json
import textwrap
import httpx
import pytest

from bgtiers import cli, db


def _sources(tmp_path):
    p = tmp_path / "sources.yaml"
    p.write_text(textwrap.dedent("""
        firestone:
          hero_url: "http://h/{mmr}/{period}.json"
          trinket_url: "http://t/{period}.json"
          brackets: ["10"]
          periods: ["last-patch"]
        hsjson:
          cards_url: "http://cards"
    """))
    return str(p)


def _hero_body():
    return json.dumps({"heroStats": [
        {"heroCardId": "BG_HERO_001", "dataPoints": 100, "averagePosition": 4.0}
    ]}).encode()


def test_fetch_stats_partial_failure_exits_nonzero_but_loads_good_url(tmp_path, monkeypatch):
    dbp = str(tmp_path / "t.db")
    db.init_db(db.connect(dbp))

    def handler(req):
        if req.url.host == "h":                       # hero URL ok
            return httpx.Response(200, content=_hero_body(), headers={"ETag": '"e"'})
        return httpx.Response(500)                     # trinket URL fails

    real_client = httpx.Client                        # capture BEFORE patch (avoid recursion)
    monkeypatch.setattr(cli.httpx, "Client",
                        lambda *a, **k: real_client(transport=httpx.MockTransport(handler)))

    args = cli.build_parser().parse_args(
        ["--db", dbp, "--sources", _sources(tmp_path), "fetch-stats", "--lock", str(tmp_path / ".lock")])
    with pytest.raises(SystemExit) as ei:
        args.func(args)
    assert ei.value.code == 1
    conn = db.connect(dbp)
    assert conn.execute("SELECT COUNT(*) FROM snapshot WHERE entity_type='hero'").fetchone()[0] == 1
```

- [ ] **Step 11: Run to verify it fails** — `uv run pytest tests/test_cli.py -q` → FAIL.

- [ ] **Step 12: Implement `cli.py`**

Create `data-pipeline/src/bgtiers/cli.py`:
```python
"""CLI entrypoint: init-db | sync-entities | fetch-stats. Wiring only."""
from __future__ import annotations
import argparse
import datetime as dt
import json
import sys

import httpx

from . import db, config, fetch, normalize, entities, load
from .runlock import run_lock, AlreadyRunning


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def cmd_init_db(args):
    db.init_db(db.connect(args.db))
    print(f"initialized {args.db}")


def cmd_sync_entities(args):
    conn = db.connect(args.db)
    db.init_db(conn)
    cards = httpx.get(config.hsjson_cards_url(args.sources), timeout=60).json()
    print(f"synced {entities.sync_entities(conn, cards, now=_now())} entities")


def cmd_fetch_stats(args):
    try:
        with run_lock(args.lock):
            _do_fetch_stats(args)
    except AlreadyRunning as exc:
        print(f"skipped: {exc}", file=sys.stderr)
        sys.exit(2)


def _do_fetch_stats(args):
    conn = db.connect(args.db)
    db.init_db(conn)
    failures = 0
    with httpx.Client(timeout=60) as client:
        for task in config.load_fetch_tasks(args.sources):
            fs = conn.execute(
                "SELECT etag, last_modified FROM fetch_state WHERE source=? AND raw_url=?",
                (task.source, task.raw_url),
            ).fetchone()
            prev_etag = fs["etag"] if fs else None
            prev_lm = fs["last_modified"] if fs else None
            try:
                res = fetch.fetch_url(client, task.raw_url, prev_etag, prev_lm)
                if res.status == 304:
                    load.load_url(conn, source=task.source, entity_type=task.entity_type,
                                  raw_url=task.raw_url, time_period=task.time_period, status=304,
                                  body=None, etag=res.etag, last_modified=res.last_modified,
                                  feeds_by_bracket=None, now=_now())
                    print(f"{task.entity_type} {task.raw_url}: 304")
                    continue
                print(f"{task.entity_type} {task.raw_url}: 200 last-modified={res.last_modified}")
                raw = json.loads(res.body)
                feeds = normalize.normalize_firestone(raw, task.entity_type, task.url_mmr)  # may raise
                out = load.load_url(conn, source=task.source, entity_type=task.entity_type,
                                    raw_url=task.raw_url, time_period=task.time_period, status=200,
                                    body=res.body, etag=res.etag, last_modified=res.last_modified,
                                    feeds_by_bracket=feeds, now=_now())
                print(f"  -> {[f'{b}:{o.value}' for b, o in out.items()]}")
            except Exception as exc:
                failures += 1
                print(f"{task.entity_type} {task.raw_url}: FAILED {exc}", file=sys.stderr)
    if failures:
        sys.exit(1)


def build_parser():
    p = argparse.ArgumentParser(prog="bgtiers")
    p.add_argument("--db", default="bgtiers.db")
    p.add_argument("--sources", default="sources.yaml")
    sub = p.add_subparsers(required=True)
    sub.add_parser("init-db").set_defaults(func=cmd_init_db)
    sub.add_parser("sync-entities").set_defaults(func=cmd_sync_entities)
    fp = sub.add_parser("fetch-stats")
    fp.add_argument("--lock", default=".fetch.lock")
    fp.set_defaults(func=cmd_fetch_stats)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
```

- [ ] **Step 13: Run CLI test** — `uv run pytest tests/test_cli.py -q` → Expected: 1 passed.

- [ ] **Step 14: Verify init-db smoke**
```bash
cd /Users/jun/code/bob_assistant/data-pipeline
uv run bgtiers --db /tmp/bgtiers_test.db init-db
uv run python -c "import sqlite3;print(sorted(r[0] for r in sqlite3.connect('/tmp/bgtiers_test.db').execute(\"select name from sqlite_master where type='table'\")))"
rm -f /tmp/bgtiers_test.db
```
Expected: `['entity', 'entity_stats', 'fetch_state', 'raw_payload', 'snapshot']`.

- [ ] **Step 15: Commit**
```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "feat(data-pipeline): config (hero/trinket URL shapes), run-lock, CLI"
```

---

## Stage 9: End-to-End Integration (real fixtures, no network)

**Files:** Create `tests/test_integration.py`

- [ ] **Step 1: Integration test**

Create `data-pipeline/tests/test_integration.py`:
```python
"""E2E on the REAL Stage-0 fixtures: sync entities -> load hero + trinket -> query view.
No network: feeds loaded directly via load.load_url with normalized fixtures."""
import json
import pathlib

from bgtiers import db, entities, normalize, load
from bgtiers.models import Outcome

FIX = pathlib.Path(__file__).parent / "fixtures"


def _load(name):
    return json.loads((FIX / name).read_text())


def test_hero_e2e_populates_latest_view():
    conn = db.connect(":memory:")
    db.init_db(conn)
    entities.sync_entities(conn, _load("hsjson_cards.json"), now="t0")

    raw = _load("firestone_heroes.json")
    body = (FIX / "firestone_heroes.json").read_bytes()
    feeds = normalize.normalize_firestone(raw, "hero", url_mmr="100")
    out = load.load_url(conn, source="firestone", entity_type="hero", raw_url="http://h",
                        time_period="last-patch", status=200, body=body, etag='"e1"',
                        last_modified="lm1", feeds_by_bracket=feeds, now="t1")
    assert out["100"] == Outcome.INSERTED

    n = conn.execute("SELECT COUNT(*) FROM v_latest_stats WHERE entity_type='hero'").fetchone()[0]
    assert n == len(raw["heroStats"])
    # idempotent re-run: no new snapshot
    load.load_url(conn, source="firestone", entity_type="hero", raw_url="http://h",
                  time_period="last-patch", status=200, body=body, etag='"e1"',
                  last_modified="lm1", feeds_by_bracket=feeds, now="t2")
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 1


def test_trinket_e2e_five_brackets_and_class_join():
    conn = db.connect(":memory:")
    db.init_db(conn)
    entities.sync_entities(conn, _load("hsjson_cards.json"), now="t0")

    raw = _load("firestone_trinkets.json")
    body = (FIX / "firestone_trinkets.json").read_bytes()
    feeds = normalize.normalize_firestone(raw, "trinket", url_mmr=None)
    load.load_url(conn, source="firestone", entity_type="trinket", raw_url="http://t",
                  time_period="last-patch", status=200, body=body, etag='"e1"',
                  last_modified="lm1", feeds_by_bracket=feeds, now="t1")

    brackets = {r[0] for r in conn.execute(
        "SELECT DISTINCT mmr_bracket FROM snapshot WHERE entity_type='trinket'").fetchall()}
    assert brackets == {"100", "50", "25", "10", "1"}
    # a trinket whose cardId is in the HSJSON fixture shows its class in the view
    row = conn.execute(
        "SELECT trinket_class FROM v_latest_stats WHERE card_id='BG30_MagicItem_902' LIMIT 1").fetchone()
    if row is not None:                               # present only if that card is in the trimmed fixture
        assert row["trinket_class"] == "greater"


def test_latest_view_returns_newest_snapshot_per_dimension():
    conn = db.connect(":memory:")
    db.init_db(conn)
    entities.sync_entities(conn, _load("hsjson_cards.json"), now="t0")
    raw = _load("firestone_heroes.json")
    feed_v1 = normalize.normalize_firestone(raw, "hero", url_mmr="100")
    cid = raw["heroStats"][0]["heroCardId"]
    load.load_url(conn, source="firestone", entity_type="hero", raw_url="http://h",
                  time_period="last-patch", status=200, body=b"v1", etag='"e1"',
                  last_modified="lm1", feeds_by_bracket=feed_v1, now="t1")
    raw2 = json.loads(json.dumps(raw))
    raw2["heroStats"][0]["averagePosition"] = 1.50
    feed_v2 = normalize.normalize_firestone(raw2, "hero", url_mmr="100")
    load.load_url(conn, source="firestone", entity_type="hero", raw_url="http://h",
                  time_period="last-patch", status=200, body=b"v2", etag='"e2"',
                  last_modified="lm2", feeds_by_bracket=feed_v2, now="t2")
    val = conn.execute("SELECT avg_placement FROM v_latest_stats WHERE card_id=?", (cid,)).fetchone()["avg_placement"]
    assert val == 1.50
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 2  # history kept
```

- [ ] **Step 2: Run full suite** — `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest -q` → Expected: all tests pass.

- [ ] **Step 3: README + commit**

Create `data-pipeline/README.md`:
```markdown
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
```

```bash
cd /Users/jun/code/bob_assistant && git add data-pipeline && git commit -m "test(data-pipeline): end-to-end integration on real fixtures + README"
```

---

## Per-Stage Codex Review (required between stages)

After each Stage's final commit, before starting the next:
```bash
cd /Users/jun/code/bob_assistant
codex exec --skip-git-repo-check "Review the latest commit's diff in data-pipeline/ against \
docs/superpowers/specs/2026-05-31-bg-tier-data-pipeline-design.md (v4). Check correctness, \
spec-conformance, test quality, and edge cases. Do NOT modify files; list findings by severity \
(blocker/should-fix/nice-to-have) and give a READY/NOT-READY verdict."
```
Address blocker + should-fix before the next Stage. Re-run until READY.

---

## Self-Review: Spec Coverage Map

| Spec section | Covered by |
|---|---|
| §3.1 HSJSON identity + trinket_class from spellSchool | Stage 5 (`sync_entities`, `_SPELLSCHOOL_TO_CLASS`) |
| §3.2 Firestone hero (per mmr) + trinket (per period, nested mmr) | Stage 4 normalize + Stage 8 config |
| §4.1 discover (URL = fetch unit) | Stage 8 `config.load_fetch_tasks` |
| §4.1 fetch (conditional, URL-keyed validators) | Stage 7 + Stage 8 wiring |
| §4.1 normalize (+ trinket 5-bracket expansion) + validation | Stage 4 |
| §4.1 load (BEGIN IMMEDIATE, per-dim dedup-by-latest-snapshot, validator-on-success) | Stage 6 |
| §4.2 subcommands | Stage 8 |
| §4.3 run-lock + scheduling | Stage 8 `runlock` + Stage 9 README |
| §4.4 unknown card_id -> stub | Stage 5 `ensure_entity` + Stage 6 test |
| §5.1-5.5 schema (URL-keyed fetch_state, raw gzip) | Stage 2 + Stage 6 |
| §5.6 content_hash contract | Stage 3 |
| §5.7 indexes / §5.8 v_latest_stats (+tie-break) | Stage 2 + Stage 9 |
| §7 success criteria 1-8 | Stages 2,5,4/6,6,6,9,5/6,8 (tests throughout) |

**No deferred logic gaps:** real Firestone URLs/fields/time-periods are confirmed (Stage 0) and baked into `sources.yaml` + normalizers; `entity.trinket_class` is populated end-to-end from HSJSON `spellSchool`; trinket MMR brackets are produced by expanding `averagePlacementAtMmr`. Remaining open items (§9 of spec) are future-layer concerns (tier thresholds, extra time periods), explicitly out of scope.
```
