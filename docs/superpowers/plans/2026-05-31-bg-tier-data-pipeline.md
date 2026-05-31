# BG Hero/Trinket Tier Data-Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Python CLI that scrapes Hearthstone Battlegrounds hero/trinket raw stats (Firestone) + identity (HearthstoneJSON) into an append-only SQLite database, with content-change snapshots, full raw-payload retention, and idempotent re-runs.

**Architecture:** A `data-pipeline/` uv project exposing three CLI subcommands (`init-db`, `sync-entities`, `fetch-stats`). The fetch pipeline is 4 pure-ish stages — discover → fetch → normalize+validate → load — each in its own module and unit-tested against recorded fixtures (no live network in tests). Storage is a single SQLite file: 5 tables (`entity`, `fetch_state`, `snapshot`, `entity_stats`, `raw_payload`) + a `v_latest_stats` view. Dedup is application-level (compare to `fetch_state.last_content_hash`); history is append-only; concurrency is guarded by `BEGIN IMMEDIATE` + a global `flock` run-lock.

**Tech Stack:** Python 3.12, `uv`, stdlib `sqlite3`, `httpx` (injectable transport for tests), `pytest`. Spec: `docs/superpowers/specs/2026-05-31-bg-tier-data-pipeline-design.md` (v3.1).

**Codex-review checkpoint:** After each Stage's final commit, run `codex exec` review on the diff before starting the next Stage (see "Per-Stage Codex Review" at the end). Do not start Stage N+1 until Stage N's review is clean.

---

## File Structure

```
data-pipeline/
├── pyproject.toml                  # uv project + deps (httpx, pytest)
├── sources.yaml                    # feed URL config — FILLED BY STAGE 0 discovery spike
├── README.md                       # how to run init-db / sync-entities / fetch-stats
├── src/bgtiers/
│   ├── __init__.py
│   ├── models.py                   # FeedKey, NormalizedRow, FetchResult, LoadOutcome
│   ├── db.py                       # DDL constants, connect(), init_db()
│   ├── hashing.py                  # content_hash() canonicalization contract (§5.6)
│   ├── config.py                   # load_feeds() from sources.yaml
│   ├── normalize.py                # firestone hero/trinket normalizers + validation
│   ├── entities.py                 # HearthstoneJSON sync + ensure_entity() stub creation
│   ├── fetch.py                    # conditional HTTP GET, retry
│   ├── load.py                     # BEGIN IMMEDIATE load: dedup, snapshot/stats/raw write, validator advance
│   ├── runlock.py                  # flock global run-lock context manager
│   └── cli.py                      # argparse: init-db, sync-entities, fetch-stats
└── tests/
    ├── conftest.py                 # in-memory/temp db fixtures, fixture loaders
    ├── fixtures/
    │   ├── firestone_heroes.json   # recorded (Stage 0); representative until then
    │   ├── firestone_trinkets.json
    │   └── hsjson_cards.json
    ├── test_db.py
    ├── test_hashing.py
    ├── test_normalize.py
    ├── test_entities.py
    ├── test_fetch.py
    ├── test_load.py
    └── test_integration.py
```

**Module responsibilities (one job each):**
- `models.py` — plain dataclasses shared across stages; no logic.
- `db.py` — owns the schema (single source of DDL truth) + connection setup (foreign keys ON).
- `hashing.py` — the canonicalization contract; pure function, locked by tests.
- `normalize.py` — source JSON → `list[NormalizedRow]` + `schema_fingerprint` + validation. One function per (source, entity_type).
- `entities.py` — identity table sync + stub creation for unknown card_ids.
- `fetch.py` — HTTP only; knows nothing about the DB.
- `load.py` — the transactional core; the only module that writes snapshot/stats/raw/fetch_state.
- `runlock.py` — process-level mutual exclusion.
- `cli.py` — argument parsing + wiring; no business logic.

---

## Stage 0: Endpoint Discovery Spike (GATED — do this first, by hand)

**Goal:** Produce a real `sources.yaml` + recorded fixtures. **This is a pass/fail gate (spec §9-1): if stable Firestone URLs cannot be found, STOP and re-evaluate the data source before writing any pipeline code.**

- [ ] **Step 1: Capture Firestone's stats requests**

Open `https://www.firestoneapp.com/battlegrounds/heroes` (and `/trinkets`) in a browser with DevTools → Network. Filter for `static.zerotoheroes.com` / `static.firestoneapp.com` JSON requests as you toggle the MMR and time-period filters. Record, for heroes and trinkets:
- the exact URL template, noting where `mmrPercentile` (100/50/25/10/1) and `timePeriod` (last-patch/past-3/past-7) appear,
- one full response body per entity type (save raw).

Also fetch identity once:
```bash
curl -s https://api.hearthstonejson.com/v1/latest/enUS/cards.json -o /tmp/hsjson_cards.json
wc -c /tmp/hsjson_cards.json
```

- [ ] **Step 2: GATE decision**

If you found stable, fetchable URLs for BOTH heroes and trinkets → continue. If not (auth-gated, signed tokens, no trinket feed) → STOP, report findings, revisit §3.2 of the spec (HSReplay fallback or revised scope) before proceeding.

- [ ] **Step 3: Save recorded fixtures** (used by all later tests — trim to ~8-15 entities each to keep tests fast, but keep real field names/shape)

Save the trimmed bodies to `data-pipeline/tests/fixtures/firestone_heroes.json`, `firestone_trinkets.json`, `hsjson_cards.json`. Ensure the trinket fixture includes at least one `lesser` and one `greater` trinket.

- [ ] **Step 4: Write the real `sources.yaml`**

Create `data-pipeline/sources.yaml` with the URL templates you discovered (this is the authoritative copy — later stages must NOT overwrite it):
```yaml
firestone:
  hero_url: "<REAL hero stats URL with {mmr} and {period} placeholders>"
  trinket_url: "<REAL trinket stats URL with {mmr} and {period} placeholders>"
  brackets: ["100", "50", "25", "10", "1"]   # spec §9-4 default matrix
  periods: ["last-patch"]
hsjson:
  cards_url: "https://api.hearthstonejson.com/v1/latest/enUS/cards.json"
```

> **CONTRACT FOR LATER STAGES (avoid clobbering Stage-0 evidence):**
> - The fixtures and `sources.yaml` created here are the **authoritative** copies.
> - Stages 4, 5, 8 below show **representative** JSON / YAML **only as a fallback for the case where Stage 0 was skipped**. If the real files already exist (normal path), **do not recreate or overwrite them** — just confirm they exist and move on.
> - If the real feed's field names differ from the representative ones, adjust `normalize._ID_FIELD`, `normalize` trinket-class extraction, and `entities._is_bg_hero` / `_is_bg_trinket` to match, and let the tests (run against the real fixtures) lock the shape.

---

## Stage 1: Project Scaffold

**Files:**
- Create: `data-pipeline/pyproject.toml`, `data-pipeline/src/bgtiers/__init__.py`, `data-pipeline/tests/__init__.py`, `data-pipeline/tests/test_smoke.py`

- [ ] **Step 1: Init the uv project**

Run:
```bash
cd data-pipeline 2>/dev/null || (mkdir -p data-pipeline && cd data-pipeline)
cd /Users/jun/code/bob_assistant/data-pipeline
uv init --bare --name bgtiers 2>/dev/null || true
uv add httpx
uv add --dev pytest
```

- [ ] **Step 2: Create the package layout**

Create `data-pipeline/pyproject.toml` (merge with what `uv init` produced; ensure src layout):
```toml
[project]
name = "bgtiers"
version = "0.1.0"
requires-python = ">=3.12"
dependencies = ["httpx>=0.27"]

[dependency-groups]
dev = ["pytest>=8"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.pytest.ini_options]
pythonpath = ["src"]
testpaths = ["tests"]
```

Create empty `data-pipeline/src/bgtiers/__init__.py` and `data-pipeline/tests/__init__.py`.

- [ ] **Step 3: Write a smoke test**

Create `data-pipeline/tests/test_smoke.py`:
```python
def test_package_imports():
    import bgtiers
    assert bgtiers is not None
```

- [ ] **Step 4: Run it**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest -q`
Expected: 1 passed.

- [ ] **Step 5: Add .gitignore + commit**

Create `data-pipeline/.gitignore`:
```
*.db
*.db-wal
*.db-shm
.fetch.lock
__pycache__/
.venv/
```
Run:
```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "feat(data-pipeline): scaffold uv project for BG tier pipeline"
```

---

## Stage 2: Database Schema + init-db

**Files:**
- Create: `data-pipeline/src/bgtiers/db.py`, `data-pipeline/tests/test_db.py`, `data-pipeline/tests/conftest.py`

- [ ] **Step 1: Write the failing test**

Create `data-pipeline/tests/conftest.py`:
```python
import sqlite3
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
from bgtiers import db


def _tables(conn):
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    ).fetchall()
    return {r[0] for r in rows}


def test_init_db_creates_all_tables(conn):
    assert _tables(conn) >= {
        "entity", "fetch_state", "snapshot", "entity_stats", "raw_payload"
    }


def test_init_db_creates_latest_view(conn):
    views = conn.execute(
        "SELECT name FROM sqlite_master WHERE type='view'"
    ).fetchall()
    assert ("v_latest_stats",) in views


def test_init_db_is_idempotent(conn):
    # running again must not raise
    db.init_db(conn)


def test_foreign_keys_enforced(conn):
    assert conn.execute("PRAGMA foreign_keys").fetchone()[0] == 1


def test_entity_type_check_constraint(conn):
    import sqlite3
    conn.execute(
        "INSERT INTO entity (entity_type, card_id, first_seen_at, last_seen_at) "
        "VALUES ('hero','H1','t','t')"
    )
    try:
        conn.execute(
            "INSERT INTO entity (entity_type, card_id, first_seen_at, last_seen_at) "
            "VALUES ('bogus','H2','t','t')"
        )
        raised = False
    except sqlite3.IntegrityError:
        raised = True
    assert raised
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_db.py -q`
Expected: FAIL (`ModuleNotFoundError: bgtiers.db` / `connect` undefined).

- [ ] **Step 3: Implement `db.py`**

Create `data-pipeline/src/bgtiers/db.py`:
```python
"""Schema definition (single source of DDL truth) + connection setup."""
import sqlite3

SCHEMA = """
CREATE TABLE IF NOT EXISTS entity (
  entity_id     INTEGER PRIMARY KEY AUTOINCREMENT,
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

-- NOTE: mmr_bracket / time_period CHECKs use the spec §5.3 enumerated values.
-- If Stage 0 discovery reveals Firestone encodes brackets/periods differently,
-- adjust these CHECKs AND sources.yaml together (and the tests will catch drift).
CREATE TABLE IF NOT EXISTS fetch_state (
  source            TEXT NOT NULL,
  entity_type       TEXT NOT NULL CHECK (entity_type IN ('hero','trinket')),
  mmr_bracket       TEXT NOT NULL CHECK (mmr_bracket IN ('100','50','25','10','1')),
  time_period       TEXT NOT NULL CHECK (time_period IN ('last-patch','past-3','past-7')),
  mode              TEXT NOT NULL DEFAULT 'solo',
  region            TEXT NOT NULL DEFAULT 'global',
  raw_url           TEXT NOT NULL,
  etag              TEXT,
  last_modified     TEXT,
  last_content_hash TEXT,
  last_snapshot_id  INTEGER REFERENCES snapshot(snapshot_id),
  last_checked_at   TEXT NOT NULL,
  PRIMARY KEY (source, entity_type, mmr_bracket, time_period, mode, region)
);

CREATE TABLE IF NOT EXISTS snapshot (
  snapshot_id          INTEGER PRIMARY KEY AUTOINCREMENT,
  source               TEXT NOT NULL,
  entity_type          TEXT NOT NULL CHECK (entity_type IN ('hero','trinket')),
  mmr_bracket          TEXT NOT NULL CHECK (mmr_bracket IN ('100','50','25','10','1')),
  time_period          TEXT NOT NULL CHECK (time_period IN ('last-patch','past-3','past-7')),
  mode                 TEXT NOT NULL DEFAULT 'solo',
  region               TEXT NOT NULL DEFAULT 'global',
  patch                TEXT,
  source_last_modified TEXT,
  content_hash         TEXT NOT NULL,
  fetched_at           TEXT NOT NULL,
  raw_url              TEXT NOT NULL
  -- NOTE: no UNIQUE on fetched_at — append-only history must allow two changed
  -- snapshots within the same timestamp second. Duplicate-insert is prevented by
  -- the application-level dedup (fetch_state.last_content_hash) + run-lock, not a constraint.
);

CREATE TABLE IF NOT EXISTS entity_stats (
  id                     INTEGER PRIMARY KEY AUTOINCREMENT,
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
JOIN snapshot snap     ON snap.snapshot_id = r.snapshot_id AND r.rn = 1
JOIN entity_stats st   ON st.snapshot_id = snap.snapshot_id
JOIN entity e          ON e.entity_id = st.entity_id;
"""


def connect(path: str) -> sqlite3.Connection:
    # isolation_level=None -> autocommit mode, so our explicit BEGIN IMMEDIATE /
    # COMMIT / ROLLBACK in load.py control transactions (the default Python sqlite3
    # transaction machinery would otherwise inject its own BEGIN and fight ours).
    conn = sqlite3.connect(path, isolation_level=None)
    conn.execute("PRAGMA foreign_keys = ON")
    conn.row_factory = sqlite3.Row
    return conn


def init_db(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)
    conn.commit()
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_db.py -q`
Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "feat(data-pipeline): SQLite schema + init_db (5 tables + latest view)"
```

---

## Stage 3: content_hash Canonicalization (§5.6)

**Files:**
- Create: `data-pipeline/src/bgtiers/models.py`, `data-pipeline/src/bgtiers/hashing.py`, `data-pipeline/tests/test_hashing.py`

- [ ] **Step 1: Define shared models**

Create `data-pipeline/src/bgtiers/models.py`:
```python
"""Plain dataclasses shared across pipeline stages. No logic here."""
from __future__ import annotations
from dataclasses import dataclass, field


@dataclass(frozen=True)
class FeedKey:
    source: str
    entity_type: str          # 'hero' | 'trinket'
    mmr_bracket: str          # '100'|'50'|'25'|'10'|'1'
    time_period: str          # 'last-patch'|'past-3'|'past-7'
    mode: str = "solo"
    region: str = "global"


@dataclass
class NormalizedRow:
    card_id: str
    avg_placement: float
    data_points: int
    pick_rate: float | None = None
    placement_distribution: list | None = None
    trinket_class: str | None = None      # 'lesser'|'greater' for trinkets, None for heroes
    extra_json: dict = field(default_factory=dict)


@dataclass
class NormalizedFeed:
    rows: list[NormalizedRow]
    patch: str | None
    schema_fingerprint: list[str]   # sorted field-name set, for provenance


@dataclass
class FetchResult:
    feed_key: FeedKey
    raw_url: str
    status: int                     # 200 | 304
    body: bytes | None
    etag: str | None
    last_modified: str | None
```

- [ ] **Step 2: Write the failing test**

Create `data-pipeline/tests/test_hashing.py`:
```python
from bgtiers.hashing import content_hash
from bgtiers.models import NormalizedRow, NormalizedFeed


def _feed(rows, patch="27.0", fp=None):
    return NormalizedFeed(rows=rows, patch=patch,
                          schema_fingerprint=fp or ["avg", "card_id"])


def test_hash_is_stable_regardless_of_row_order():
    a = _feed([NormalizedRow("A", 4.1, 100), NormalizedRow("B", 3.9, 200)])
    b = _feed([NormalizedRow("B", 3.9, 200), NormalizedRow("A", 4.1, 100)])
    assert content_hash(a) == content_hash(b)


def test_hash_changes_when_stat_changes():
    a = _feed([NormalizedRow("A", 4.1, 100)])
    b = _feed([NormalizedRow("A", 4.2, 100)])
    assert content_hash(a) != content_hash(b)


def test_hash_changes_when_patch_changes():
    a = _feed([NormalizedRow("A", 4.1, 100)], patch="27.0")
    b = _feed([NormalizedRow("A", 4.1, 100)], patch="27.2")
    assert content_hash(a) != content_hash(b)


def test_hash_changes_when_schema_fingerprint_changes():
    a = _feed([NormalizedRow("A", 4.1, 100)], fp=["avg", "card_id"])
    b = _feed([NormalizedRow("A", 4.1, 100)], fp=["avg", "card_id", "newField"])
    assert content_hash(a) != content_hash(b)


def test_float_rounding_ignores_sub_micro_noise():
    a = _feed([NormalizedRow("A", 4.1000001, 100)])
    b = _feed([NormalizedRow("A", 4.1000002, 100)])
    assert content_hash(a) == content_hash(b)
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_hashing.py -q`
Expected: FAIL (`ModuleNotFoundError: bgtiers.hashing`).

- [ ] **Step 4: Implement `hashing.py`**

Create `data-pipeline/src/bgtiers/hashing.py`:
```python
"""content_hash canonicalization contract — spec §5.6.

Input = stat content + feed-level provenance (patch + schema fingerprint).
Excludes all of our own volatile metadata (fetched_at, etag, url).
Locked by tests; do not change without updating test_hashing.py.
"""
from __future__ import annotations
import hashlib
import json

from .models import NormalizedFeed

_FLOAT_NDIGITS = 6


def _canon(obj):
    """Canonicalize for hashing. Floats become fixed-decimal STRINGS so the hash is
    stable across implementations/JSON encoders (e.g. 4.1 -> '4.100000'), not subject
    to float repr differences. ints stay ints; None stays None."""
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
        (
            {
                "card_id": r.card_id,
                "avg_placement": r.avg_placement,
                "data_points": r.data_points,
                "pick_rate": r.pick_rate,
                "placement_distribution": r.placement_distribution,
                "extra_json": r.extra_json,
            }
            for r in feed.rows
        ),
        key=lambda r: r["card_id"],
    )
    payload = {
        "stats": _canon(stats),
        "patch": feed.patch,
        "schema_fingerprint": sorted(feed.schema_fingerprint),
    }
    serialized = json.dumps(
        payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    )
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_hashing.py -q`
Expected: 5 passed.

- [ ] **Step 6: Commit**

```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "feat(data-pipeline): content_hash canonicalization with provenance"
```

---

## Stage 4: Normalize + Validation

**Files:**
- Create: `data-pipeline/src/bgtiers/normalize.py`, `data-pipeline/tests/test_normalize.py`, `data-pipeline/tests/fixtures/firestone_heroes.json`, `data-pipeline/tests/fixtures/firestone_trinkets.json`

> The fixtures below are **representative** of the documented Firestone fields (heroCardId, dataPoints, averagePosition, placementDistribution, mmrPercentile, timePeriod). **If Stage 0 already saved real fixtures, skip this step — do not overwrite them.** Only create these if Stage 0 was skipped.

- [ ] **Step 1: Add representative fixtures (only if not already present from Stage 0)**

Create `data-pipeline/tests/fixtures/firestone_heroes.json`:
```json
{
  "lastUpdated": "2026-05-30T00:00:00Z",
  "mmrPercentile": 10,
  "timePeriod": "last-patch",
  "stats": [
    {"heroCardId": "BG_HERO_001", "dataPoints": 5200, "averagePosition": 3.92,
     "placementDistribution": [220,210,180,150,120,90,60,40], "tribeStats": []},
    {"heroCardId": "BG_HERO_002", "dataPoints": 4800, "averagePosition": 4.55,
     "placementDistribution": [120,150,170,160,140,120,90,50], "tribeStats": []}
  ]
}
```

Create `data-pipeline/tests/fixtures/firestone_trinkets.json`:
```json
{
  "lastUpdated": "2026-05-30T00:00:00Z",
  "mmrPercentile": 10,
  "timePeriod": "last-patch",
  "stats": [
    {"trinketCardId": "BG_TRINKET_01", "trinketClass": "lesser", "dataPoints": 3100,
     "averagePosition": 4.05, "placementDistribution": [180,170,160,140,120,100,80,50]},
    {"trinketCardId": "BG_TRINKET_02", "trinketClass": "greater", "dataPoints": 2900,
     "averagePosition": 3.80, "placementDistribution": [210,190,170,150,120,90,60,40]}
  ]
}
```

- [ ] **Step 2: Write the failing test**

Create `data-pipeline/tests/test_normalize.py`:
```python
import json
import pathlib
import pytest

from bgtiers import normalize

FIX = pathlib.Path(__file__).parent / "fixtures"


def _load(name):
    return json.loads((FIX / name).read_text())


def test_normalize_heroes_extracts_rows():
    feed = normalize.normalize_firestone(_load("firestone_heroes.json"), entity_type="hero")
    cards = {r.card_id: r for r in feed.rows}
    assert cards["BG_HERO_001"].avg_placement == 3.92
    assert cards["BG_HERO_001"].data_points == 5200
    assert cards["BG_HERO_001"].placement_distribution == [220,210,180,150,120,90,60,40]
    assert feed.patch is not None or feed.patch is None  # patch best-effort
    assert "averagePosition" in feed.schema_fingerprint


def test_normalize_trinkets_promotes_class_to_first_class_field():
    feed = normalize.normalize_firestone(_load("firestone_trinkets.json"), entity_type="trinket")
    row = {r.card_id: r for r in feed.rows}["BG_TRINKET_02"]
    assert row.avg_placement == 3.80
    assert row.trinket_class == "greater"            # promoted, not in extra_json
    assert "trinketClass" not in row.extra_json


def test_normalize_rejects_bad_trinket_class():
    bad = {"stats": [{"trinketCardId": "T", "trinketClass": "bogus",
                      "dataPoints": 10, "averagePosition": 4.0}]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="trinket")


def test_normalize_rejects_non_numeric_placement():
    bad = {"stats": [{"heroCardId": "X", "dataPoints": 10, "averagePosition": "low"}]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero")


def test_validate_rejects_empty_feed():
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone({"stats": []}, entity_type="hero")


def test_validate_rejects_out_of_range_placement():
    bad = {"stats": [{"heroCardId": "X", "dataPoints": 10, "averagePosition": 9.9}]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero")


def test_validate_rejects_duplicate_card_ids():
    bad = {"stats": [
        {"heroCardId": "X", "dataPoints": 10, "averagePosition": 4.0},
        {"heroCardId": "X", "dataPoints": 11, "averagePosition": 4.1},
    ]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero")


def test_validate_rejects_missing_core_field():
    bad = {"stats": [{"heroCardId": "X", "dataPoints": 10}]}  # no averagePosition
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero")
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_normalize.py -q`
Expected: FAIL (`ModuleNotFoundError: bgtiers.normalize`).

- [ ] **Step 4: Implement `normalize.py`**

Create `data-pipeline/src/bgtiers/normalize.py`:
```python
"""Source JSON -> NormalizedFeed + validation (spec §4.1 normalize)."""
from __future__ import annotations

from .models import NormalizedRow, NormalizedFeed

# id field + trinket-class field per entity type (adjust to real feed after Stage 0)
_ID_FIELD = {"hero": "heroCardId", "trinket": "trinketCardId"}
_TRINKET_CLASS_FIELD = "trinketClass"        # adjust to real feed after Stage 0
_VALID_TRINKET_CLASS = {"lesser", "greater"}
_MAX_ROWS = 2000                              # sanity upper bound (BG has ~hundreds)

# fields promoted to first-class columns; everything else -> extra_json
_PROMOTED = ("averagePosition", "dataPoints", "placementDistribution", "pickRate",
             _TRINKET_CLASS_FIELD)


class ValidationError(Exception):
    pass


def _schema_fingerprint(raw: dict) -> list[str]:
    keys = set(raw.keys())
    for row in raw.get("stats", []):
        keys.update(row.keys())
    return sorted(keys)


def _require_number(value, card_id, field_name):
    # bool is a subclass of int — reject it explicitly
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError(f"{field_name} not numeric for {card_id}: {value!r}")
    return value


def normalize_firestone(raw: dict, entity_type: str) -> NormalizedFeed:
    id_field = _ID_FIELD[entity_type]
    stats = raw.get("stats", [])
    if not stats:
        raise ValidationError("empty feed: no stats rows")
    if len(stats) > _MAX_ROWS:
        raise ValidationError(f"implausible row count: {len(stats)} > {_MAX_ROWS}")

    rows: list[NormalizedRow] = []
    seen: set[str] = set()
    for item in stats:
        card_id = item.get(id_field)
        if not card_id:
            raise ValidationError(f"row missing {id_field}: {item!r}")
        if "averagePosition" not in item or "dataPoints" not in item:
            raise ValidationError(f"row missing core field: {card_id}")
        if card_id in seen:
            raise ValidationError(f"duplicate card_id in feed: {card_id}")
        seen.add(card_id)

        avg = float(_require_number(item["averagePosition"], card_id, "averagePosition"))
        if not (1.0 <= avg <= 8.0):
            raise ValidationError(f"avg_placement out of range for {card_id}: {avg}")
        dp_raw = _require_number(item["dataPoints"], card_id, "dataPoints")
        if isinstance(dp_raw, float) and not dp_raw.is_integer():
            raise ValidationError(f"data_points not integral for {card_id}: {dp_raw}")
        dp = int(dp_raw)
        if dp < 0:
            raise ValidationError(f"negative data_points for {card_id}: {dp}")

        dist = item.get("placementDistribution")
        if dist is not None:
            if not isinstance(dist, list) or len(dist) != 8:
                raise ValidationError(f"bad placement distribution for {card_id}")
            for el in dist:
                _require_number(el, card_id, "placementDistribution element")

        trinket_class = None
        if entity_type == "trinket":
            trinket_class = item.get(_TRINKET_CLASS_FIELD)
            if trinket_class is not None and trinket_class not in _VALID_TRINKET_CLASS:
                raise ValidationError(f"bad trinket_class for {card_id}: {trinket_class!r}")

        extra = {k: v for k, v in item.items() if k not in (id_field, *_PROMOTED)}
        rows.append(NormalizedRow(
            card_id=card_id,
            avg_placement=avg,
            data_points=dp,
            pick_rate=(float(item["pickRate"]) if item.get("pickRate") is not None else None),
            placement_distribution=dist,
            trinket_class=trinket_class,
            extra_json=extra,
        ))

    return NormalizedFeed(
        rows=rows,
        patch=raw.get("patch"),  # best-effort; often absent
        schema_fingerprint=_schema_fingerprint(raw),
    )
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_normalize.py -q`
Expected: 8 passed.

- [ ] **Step 6: Commit**

```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "feat(data-pipeline): firestone hero/trinket normalize + validation"
```

---

## Stage 5: Entity Sync + Stub Creation

**Files:**
- Create: `data-pipeline/src/bgtiers/entities.py`, `data-pipeline/tests/test_entities.py`, `data-pipeline/tests/fixtures/hsjson_cards.json`

- [ ] **Step 1: Add representative HearthstoneJSON fixture (only if not already present from Stage 0)**

**If Stage 0 already saved a real `hsjson_cards.json`, skip this step — do not overwrite it.** Otherwise create `data-pipeline/tests/fixtures/hsjson_cards.json`:
```json
[
  {"id": "BG_HERO_001", "dbfId": 50001, "name": "Test Hero One", "type": "HERO", "set": "BATTLEGROUNDS"},
  {"id": "BG_HERO_002", "dbfId": 50002, "name": "Test Hero Two", "type": "HERO", "set": "BATTLEGROUNDS"},
  {"id": "BG_TRINKET_02", "dbfId": 60002, "name": "Test Trinket Two", "type": "BATTLEGROUND_TRINKET", "set": "BATTLEGROUNDS"},
  {"id": "AT_001", "dbfId": 100, "name": "Some Constructed Card", "type": "MINION", "set": "TGT"}
]
```

- [ ] **Step 2: Write the failing test**

Create `data-pipeline/tests/test_entities.py`:
```python
import json
import pathlib
from bgtiers import entities

FIX = pathlib.Path(__file__).parent / "fixtures"


def _cards():
    return json.loads((FIX / "hsjson_cards.json").read_text())


def test_sync_inserts_bg_heroes_and_trinkets(conn):
    n = entities.sync_entities(conn, _cards(), now="2026-05-31T00:00:00Z")
    rows = conn.execute("SELECT card_id, entity_type FROM entity ORDER BY card_id").fetchall()
    by_card = {r["card_id"]: r["entity_type"] for r in rows}
    assert set(by_card) == {"BG_HERO_001", "BG_HERO_002", "BG_TRINKET_02"}  # constructed excluded
    assert by_card["BG_HERO_001"] == "hero"
    assert by_card["BG_TRINKET_02"] == "trinket"
    assert n == 3


def test_sync_is_idempotent(conn):
    entities.sync_entities(conn, _cards(), now="2026-05-31T00:00:00Z")
    entities.sync_entities(conn, _cards(), now="2026-05-31T00:00:00Z")
    count = conn.execute("SELECT COUNT(*) FROM entity").fetchone()[0]
    assert count == 3


def test_ensure_entity_creates_stub_for_unknown_card(conn):
    eid = entities.ensure_entity(conn, entity_type="trinket",
                                 card_id="BG_TRINKET_99", now="2026-05-31T00:00:00Z")
    row = conn.execute("SELECT * FROM entity WHERE entity_id=?", (eid,)).fetchone()
    assert row["card_id"] == "BG_TRINKET_99"
    assert row["name"] is None            # stub: name unknown
    assert row["entity_type"] == "trinket"


def test_ensure_entity_returns_existing_id(conn):
    a = entities.ensure_entity(conn, "hero", "BG_HERO_X", now="2026-05-31T00:00:00Z")
    b = entities.ensure_entity(conn, "hero", "BG_HERO_X", now="2026-05-31T00:00:00Z")
    assert a == b


def test_ensure_entity_sets_trinket_class_on_stub(conn):
    eid = entities.ensure_entity(conn, "trinket", "BG_TRINKET_02",
                                 now="2026-05-31T00:00:00Z", trinket_class="greater")
    row = conn.execute("SELECT trinket_class FROM entity WHERE entity_id=?", (eid,)).fetchone()
    assert row["trinket_class"] == "greater"


def test_ensure_entity_backfills_trinket_class_on_existing(conn):
    eid = entities.ensure_entity(conn, "trinket", "BG_TRINKET_02", now="t0")  # class unknown
    entities.ensure_entity(conn, "trinket", "BG_TRINKET_02", now="t1", trinket_class="lesser")
    row = conn.execute("SELECT trinket_class FROM entity WHERE entity_id=?", (eid,)).fetchone()
    assert row["trinket_class"] == "lesser"


def test_sync_backfills_existing_stub(conn):
    entities.ensure_entity(conn, "hero", "BG_HERO_001", now="2026-05-31T00:00:00Z")
    entities.sync_entities(conn, _cards(), now="2026-05-31T00:00:00Z")
    row = conn.execute("SELECT name, dbf_id FROM entity WHERE card_id='BG_HERO_001'").fetchone()
    assert row["name"] == "Test Hero One"
    assert row["dbf_id"] == 50001
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_entities.py -q`
Expected: FAIL (`ModuleNotFoundError: bgtiers.entities`).

- [ ] **Step 4: Implement `entities.py`**

Create `data-pipeline/src/bgtiers/entities.py`:
```python
"""Identity table sync (HearthstoneJSON) + stub creation for unknown card_ids."""
from __future__ import annotations
import sqlite3

_IMG_TMPL = "https://art.hearthstonejson.com/v1/256x/{card_id}.jpg"


def _is_bg_hero(card: dict) -> bool:
    # adjust to real filter after Stage 0 (e.g. battlegroundsHero flag)
    return card.get("type") == "HERO" and "BATTLEGROUNDS" in str(card.get("set", "")).upper()


def _is_bg_trinket(card: dict) -> bool:
    # Best-effort: BG trinkets may or may not be cleanly identifiable in cards.json.
    # Adjust after Stage 0; if trinkets are NOT in cards.json, this stays False and
    # trinket identity comes entirely from stat-feed stubs (ensure_entity).
    return "TRINKET" in str(card.get("type", "")).upper() \
        or "TRINKET" in str(card.get("set", "")).upper()


def _classify(card: dict) -> str | None:
    if _is_bg_hero(card):
        return "hero"
    if _is_bg_trinket(card):
        return "trinket"
    return None


def sync_entities(conn: sqlite3.Connection, cards: list[dict], now: str) -> int:
    """Upsert BG heroes + identifiable trinkets from HearthstoneJSON.
    Returns number of rows touched. (Trinket class comes from the stat feed, not here,
    so we never clobber a known trinket_class with NULL.)"""
    touched = 0
    for card in cards:
        etype = _classify(card)
        if etype is None:
            continue
        card_id = card["id"]
        conn.execute(
            """
            INSERT INTO entity (entity_type, card_id, dbf_id, name, image_url,
                                first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (entity_type, card_id) DO UPDATE SET
                dbf_id       = excluded.dbf_id,
                name         = excluded.name,
                image_url    = COALESCE(entity.image_url, excluded.image_url),
                last_seen_at = excluded.last_seen_at
            """,
            (etype, card_id, card.get("dbfId"), card.get("name"),
             _IMG_TMPL.format(card_id=card_id), now, now),
        )
        touched += 1
    conn.commit()
    return touched


def ensure_entity(conn: sqlite3.Connection, entity_type: str, card_id: str, now: str,
                  trinket_class: str | None = None) -> int:
    """Return entity_id for (entity_type, card_id); create a stub if unknown.
    If trinket_class is given (from the stat feed), set/refresh it — the feed is the
    authority for lesser/greater."""
    row = conn.execute(
        "SELECT entity_id, trinket_class FROM entity WHERE entity_type=? AND card_id=?",
        (entity_type, card_id),
    ).fetchone()
    if row:
        if trinket_class is not None:
            conn.execute(
                "UPDATE entity SET last_seen_at=?, trinket_class=? WHERE entity_id=?",
                (now, trinket_class, row["entity_id"]),
            )
        else:
            conn.execute(
                "UPDATE entity SET last_seen_at=? WHERE entity_id=?",
                (now, row["entity_id"]),
            )
        return row["entity_id"]
    cur = conn.execute(
        "INSERT INTO entity (entity_type, card_id, trinket_class, first_seen_at, last_seen_at) "
        "VALUES (?, ?, ?, ?, ?)",
        (entity_type, card_id, trinket_class, now, now),
    )
    return cur.lastrowid
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_entities.py -q`
Expected: 7 passed.

- [ ] **Step 6: Commit**

```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "feat(data-pipeline): HearthstoneJSON entity sync + stub creation"
```

---

## Stage 6: Load (transactional core)

**Files:**
- Create: `data-pipeline/src/bgtiers/load.py`, `data-pipeline/tests/test_load.py`

This is the heart of the pipeline: dedup-by-latest-hash, append-only snapshot/stats/raw write, validator advance only on success, `BEGIN IMMEDIATE`.

- [ ] **Step 1: Write the failing test**

Create `data-pipeline/tests/test_load.py`:
```python
import gzip
import pytest

from bgtiers import load, entities
from bgtiers.models import FeedKey, NormalizedRow, NormalizedFeed

KEY = FeedKey(source="firestone", entity_type="hero", mmr_bracket="10", time_period="last-patch")


def _feed(avg=3.9, patch="27.0"):
    return NormalizedFeed(
        rows=[NormalizedRow("BG_HERO_001", avg, 5200,
                            placement_distribution=[1, 1, 1, 1, 1, 1, 1, 1])],
        patch=patch, schema_fingerprint=["averagePosition", "heroCardId"])


def test_first_load_inserts_snapshot_and_stats_and_raw(conn):
    outcome = load.load_feed(conn, KEY, raw_url="http://x", status=200,
                             body=b'{"raw":1}', etag='"e1"', last_modified="lm1",
                             feed=_feed(), now="2026-05-31T00:00:00Z")
    assert outcome == load.Outcome.INSERTED
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 1
    assert conn.execute("SELECT COUNT(*) FROM entity_stats").fetchone()[0] == 1
    raw = conn.execute("SELECT body_gzip FROM raw_payload").fetchone()[0]
    assert gzip.decompress(raw) == b'{"raw":1}'


def test_unchanged_content_does_not_insert_but_advances_validators(conn):
    load.load_feed(conn, KEY, "http://x", 200, b'{"raw":1}', '"e1"', "lm1", _feed(), "t1")
    outcome = load.load_feed(conn, KEY, "http://x", 200, b'{"raw":1}', '"e2"', "lm2", _feed(), "t2")
    assert outcome == load.Outcome.UNCHANGED
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 1
    fs = conn.execute("SELECT etag, last_modified FROM fetch_state").fetchone()
    assert fs["etag"] == '"e2"' and fs["last_modified"] == "lm2"   # advanced


def test_changed_content_appends_new_snapshot(conn):
    load.load_feed(conn, KEY, "http://x", 200, b'{"raw":1}', '"e1"', "lm1", _feed(avg=3.9), "t1")
    load.load_feed(conn, KEY, "http://x", 200, b'{"raw":2}', '"e2"', "lm2", _feed(avg=4.4), "t2")
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 2


def test_a_b_a_reappearance_is_recorded(conn):
    load.load_feed(conn, KEY, "http://x", 200, b'A', '"e1"', "lm1", _feed(avg=3.9), "t1")
    load.load_feed(conn, KEY, "http://x", 200, b'B', '"e2"', "lm2", _feed(avg=4.4), "t2")
    load.load_feed(conn, KEY, "http://x", 200, b'A', '"e3"', "lm3", _feed(avg=3.9), "t3")
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 3


def test_304_only_updates_last_checked(conn):
    load.load_feed(conn, KEY, "http://x", 200, b'A', '"e1"', "lm1", _feed(), "t1")
    outcome = load.load_feed(conn, KEY, "http://x", 304, None, '"e1"', "lm1", None, "t2")
    assert outcome == load.Outcome.NOT_MODIFIED
    fs = conn.execute("SELECT last_checked_at, etag FROM fetch_state").fetchone()
    assert fs["last_checked_at"] == "t2" and fs["etag"] == '"e1"'


def test_unknown_card_id_gets_stub(conn):
    feed = NormalizedFeed(
        rows=[NormalizedRow("BG_HERO_NEW", 4.0, 10)],
        patch="27.0", schema_fingerprint=["heroCardId"])
    load.load_feed(conn, KEY, "http://x", 200, b'A', '"e1"', "lm1", feed, "t1")
    row = conn.execute("SELECT name FROM entity WHERE card_id='BG_HERO_NEW'").fetchone()
    assert row is not None and row["name"] is None


def test_200_without_etag_clears_stale_validator(conn):
    # first 200 sets etag; a later changed 200 that omits ETag must NOT keep the old one
    load.load_feed(conn, KEY, "http://x", 200, b'A', '"e1"', "lm1", _feed(avg=3.9), "t1")
    load.load_feed(conn, KEY, "http://x", 200, b'B', None, None, _feed(avg=4.4), "t2")
    fs = conn.execute("SELECT etag, last_modified FROM fetch_state").fetchone()
    assert fs["etag"] is None and fs["last_modified"] is None


def test_load_trinket_sets_entity_class(conn):
    tkey = FeedKey("firestone", "trinket", "10", "last-patch")
    feed = NormalizedFeed(
        rows=[NormalizedRow("BG_TRINKET_02", 3.8, 2900, trinket_class="greater")],
        patch="27.0", schema_fingerprint=["trinketCardId"])
    load.load_feed(conn, tkey, "http://x", 200, b'A', '"e1"', "lm1", feed, "t1")
    row = conn.execute("SELECT trinket_class FROM entity WHERE card_id='BG_TRINKET_02'").fetchone()
    assert row["trinket_class"] == "greater"


def test_rollback_on_mid_transaction_failure_leaves_db_clean(conn):
    # avg_placement=9.0 passes normalize-less construction but violates the DB CHECK,
    # so the entity_stats INSERT raises mid-transaction -> whole feed rolls back.
    import sqlite3
    bad = NormalizedFeed(
        rows=[NormalizedRow("BG_HERO_001", 9.0, 100)],  # 9.0 > CHECK max 8.0
        patch="27.0", schema_fingerprint=["heroCardId"])
    with pytest.raises(sqlite3.IntegrityError):
        load.load_feed(conn, KEY, "http://x", 200, b'A', '"e1"', "lm1", bad, "t1")
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM fetch_state").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM raw_payload").fetchone()[0] == 0
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_load.py -q`
Expected: FAIL (`ModuleNotFoundError: bgtiers.load`).

- [ ] **Step 3: Implement `load.py`**

Create `data-pipeline/src/bgtiers/load.py`:
```python
"""Transactional load — the only module that writes snapshot/stats/raw/fetch_state.

Concurrency: BEGIN IMMEDIATE serializes the read-hash-then-write per connection.
A process-level flock (runlock.py) prevents overlapping runs (spec §4.3).
Validator advance: etag/last_modified are persisted only on successful handling
(304, unchanged, or inserted) — never when normalization/validation failed upstream.
"""
from __future__ import annotations
import enum
import gzip
import json
import sqlite3

from .models import FeedKey, NormalizedFeed
from .hashing import content_hash
from . import entities


class Outcome(enum.Enum):
    INSERTED = "inserted"
    UNCHANGED = "unchanged"
    NOT_MODIFIED = "not_modified"   # HTTP 304


def _upsert_fetch_state(conn, key: FeedKey, raw_url, *, etag, last_modified,
                        last_content_hash, last_snapshot_id, now, preserve_validators):
    # preserve_validators=True (HTTP 304): keep the prior etag/last_modified (the body
    #   we already hold is still current) -> COALESCE.
    # preserve_validators=False (a successful 200, unchanged OR inserted): set the
    #   validators to EXACTLY this response's headers, even if NULL, so a 200 that omits
    #   a validator does not leave a stale one behind (codex review v-plan S4).
    if preserve_validators:
        validator_set = ("etag = COALESCE(excluded.etag, fetch_state.etag), "
                         "last_modified = COALESCE(excluded.last_modified, fetch_state.last_modified)")
    else:
        validator_set = ("etag = excluded.etag, "
                         "last_modified = excluded.last_modified")
    conn.execute(
        f"""
        INSERT INTO fetch_state (source, entity_type, mmr_bracket, time_period, mode,
                                 region, raw_url, etag, last_modified, last_content_hash,
                                 last_snapshot_id, last_checked_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT (source, entity_type, mmr_bracket, time_period, mode, region)
        DO UPDATE SET
            raw_url = excluded.raw_url,
            {validator_set},
            last_content_hash = COALESCE(excluded.last_content_hash, fetch_state.last_content_hash),
            last_snapshot_id = COALESCE(excluded.last_snapshot_id, fetch_state.last_snapshot_id),
            last_checked_at = excluded.last_checked_at
        """,
        (key.source, key.entity_type, key.mmr_bracket, key.time_period, key.mode,
         key.region, raw_url, etag, last_modified, last_content_hash,
         last_snapshot_id, now),
    )


def _get_last_hash(conn, key: FeedKey):
    row = conn.execute(
        "SELECT last_content_hash FROM fetch_state WHERE source=? AND entity_type=? "
        "AND mmr_bracket=? AND time_period=? AND mode=? AND region=?",
        (key.source, key.entity_type, key.mmr_bracket, key.time_period, key.mode, key.region),
    ).fetchone()
    return row["last_content_hash"] if row else None


def load_feed(conn: sqlite3.Connection, key: FeedKey, raw_url: str, status: int,
              body: bytes | None, etag, last_modified,
              feed: NormalizedFeed | None, now: str) -> Outcome:
    conn.execute("BEGIN IMMEDIATE")
    try:
        if status == 304:
            _upsert_fetch_state(conn, key, raw_url, etag=etag, last_modified=last_modified,
                                last_content_hash=None, last_snapshot_id=None, now=now,
                                preserve_validators=True)
            conn.execute("COMMIT")
            return Outcome.NOT_MODIFIED

        assert feed is not None and body is not None
        new_hash = content_hash(feed)
        if _get_last_hash(conn, key) == new_hash:
            _upsert_fetch_state(conn, key, raw_url, etag=etag, last_modified=last_modified,
                                last_content_hash=new_hash, last_snapshot_id=None, now=now,
                                preserve_validators=False)
            conn.execute("COMMIT")
            return Outcome.UNCHANGED

        cur = conn.execute(
            """INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode,
                   region, patch, source_last_modified, content_hash, fetched_at, raw_url)
               VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            (key.source, key.entity_type, key.mmr_bracket, key.time_period, key.mode,
             key.region, feed.patch, last_modified, new_hash, now, raw_url),
        )
        snapshot_id = cur.lastrowid

        for r in feed.rows:
            entity_id = entities.ensure_entity(conn, key.entity_type, r.card_id, now,
                                               trinket_class=r.trinket_class)
            conn.execute(
                """INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement,
                       data_points, pick_rate, placement_distribution, extra_json)
                   VALUES (?,?,?,?,?,?,?)""",
                (snapshot_id, entity_id, r.avg_placement, r.data_points, r.pick_rate,
                 json.dumps(r.placement_distribution) if r.placement_distribution is not None else None,
                 json.dumps(r.extra_json, sort_keys=True) if r.extra_json else None),
            )

        gz = gzip.compress(body)
        conn.execute(
            "INSERT INTO raw_payload (snapshot_id, body_gzip, content_encoding, byte_size) "
            "VALUES (?,?,?,?)",
            (snapshot_id, gz, "gzip", len(body)),
        )

        _upsert_fetch_state(conn, key, raw_url, etag=etag, last_modified=last_modified,
                            last_content_hash=new_hash, last_snapshot_id=snapshot_id, now=now,
                            preserve_validators=False)
        conn.execute("COMMIT")
        return Outcome.INSERTED
    except Exception:
        conn.execute("ROLLBACK")
        raise
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_load.py -q`
Expected: 9 passed.

> NOTE on COALESCE in `_upsert_fetch_state`: on a 304 we pass `last_content_hash=None` so COALESCE keeps the prior hash. On unchanged/inserted we pass the real hash. This is intentional — never overwrite a known hash with NULL.

- [ ] **Step 5: Commit**

```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "feat(data-pipeline): transactional load with dedup, raw retention, validator advance"
```

---

## Stage 7: Fetch (conditional HTTP)

**Files:**
- Create: `data-pipeline/src/bgtiers/fetch.py`, `data-pipeline/tests/test_fetch.py`

- [ ] **Step 1: Write the failing test** (uses `httpx.MockTransport` — no network)

Create `data-pipeline/tests/test_fetch.py`:
```python
import httpx
from bgtiers import fetch
from bgtiers.models import FeedKey

KEY = FeedKey("firestone", "hero", "10", "last-patch")


def _client(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_fetch_200_returns_body_and_validators():
    def handler(req):
        assert "If-None-Match" not in req.headers
        return httpx.Response(200, content=b'{"ok":1}',
                              headers={"ETag": '"e1"', "Last-Modified": "lm1"})
    res = fetch.fetch_feed(_client(handler), KEY, "http://x", prev_etag=None, prev_last_modified=None)
    assert res.status == 200 and res.body == b'{"ok":1}'
    assert res.etag == '"e1"' and res.last_modified == "lm1"


def test_fetch_sends_conditional_headers():
    seen = {}
    def handler(req):
        seen["inm"] = req.headers.get("If-None-Match")
        seen["ims"] = req.headers.get("If-Modified-Since")
        return httpx.Response(304)
    res = fetch.fetch_feed(_client(handler), KEY, "http://x", prev_etag='"e1"', prev_last_modified="lm1")
    assert res.status == 304 and res.body is None
    assert seen["inm"] == '"e1"' and seen["ims"] == "lm1"


def test_fetch_retries_then_succeeds():
    calls = {"n": 0}
    def handler(req):
        calls["n"] += 1
        if calls["n"] < 2:
            return httpx.Response(503)
        return httpx.Response(200, content=b'ok', headers={"ETag": '"e"'})
    res = fetch.fetch_feed(_client(handler), KEY, "http://x", None, None, max_retries=3, backoff=0)
    assert res.status == 200 and calls["n"] == 2
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_fetch.py -q`
Expected: FAIL (`ModuleNotFoundError: bgtiers.fetch`).

- [ ] **Step 3: Implement `fetch.py`**

Create `data-pipeline/src/bgtiers/fetch.py`:
```python
"""HTTP fetch with conditional requests + retry. Knows nothing about the DB."""
from __future__ import annotations
import time
import httpx

from .models import FeedKey, FetchResult


def fetch_feed(client: httpx.Client, key: FeedKey, raw_url: str,
               prev_etag: str | None, prev_last_modified: str | None,
               max_retries: int = 3, backoff: float = 0.5) -> FetchResult:
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
                return FetchResult(key, raw_url, 304, None, prev_etag, prev_last_modified)
            if resp.status_code >= 500:
                raise httpx.HTTPStatusError("server error", request=resp.request, response=resp)
            resp.raise_for_status()
            return FetchResult(key, raw_url, 200, resp.content,
                               resp.headers.get("ETag"), resp.headers.get("Last-Modified"))
        except httpx.HTTPError as exc:
            last_exc = exc
            if attempt < max_retries - 1 and backoff:
                time.sleep(backoff * (2 ** attempt))
    raise last_exc
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_fetch.py -q`
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "feat(data-pipeline): conditional HTTP fetch with retry"
```

---

## Stage 8: Run-Lock + Config + CLI Wiring

**Files:**
- Create: `data-pipeline/src/bgtiers/runlock.py`, `data-pipeline/src/bgtiers/config.py`, `data-pipeline/src/bgtiers/cli.py`, `data-pipeline/sources.yaml`, `data-pipeline/tests/test_runlock.py`

- [ ] **Step 1: Write the failing run-lock test**

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

- [ ] **Step 2: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_runlock.py -q`
Expected: FAIL (`ModuleNotFoundError: bgtiers.runlock`).

- [ ] **Step 3: Implement `runlock.py`**

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

- [ ] **Step 4: Run to verify run-lock passes**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_runlock.py -q`
Expected: 2 passed.

- [ ] **Step 5: Add PyYAML + ensure sources.yaml exists**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv add pyyaml`

`sources.yaml` is created by **Stage 0** with the real URLs. **Do not overwrite it.** Only if Stage 0 was skipped, create a fallback `data-pipeline/sources.yaml` (note the full bracket matrix per spec §9-4):
```yaml
# Authoritative copy is written in Stage 0. {mmr} and {period} are substituted per feed.
firestone:
  hero_url: "https://static.zerotoheroes.com/REPLACE/bgs-hero-stats-{mmr}-{period}.json"
  trinket_url: "https://static.zerotoheroes.com/REPLACE/bgs-trinket-stats-{mmr}-{period}.json"
  brackets: ["100", "50", "25", "10", "1"]
  periods: ["last-patch"]
hsjson:
  cards_url: "https://api.hearthstonejson.com/v1/latest/enUS/cards.json"
```

- [ ] **Step 6: Write the failing config test**

Create `data-pipeline/tests/test_config.py`:
```python
import textwrap
from bgtiers import config


def _write(tmp_path, body):
    p = tmp_path / "sources.yaml"
    p.write_text(textwrap.dedent(body))
    return str(p)


def test_load_feeds_expands_full_matrix(tmp_path):
    path = _write(tmp_path, """
        firestone:
          hero_url: "http://h/{mmr}/{period}.json"
          trinket_url: "http://t/{mmr}/{period}.json"
          brackets: ["100", "10", "1"]
          periods: ["last-patch"]
        hsjson:
          cards_url: "http://cards"
    """)
    feeds = config.load_feeds(path)
    # 2 entity types x 3 brackets x 1 period = 6
    assert len(feeds) == 6
    urls = {u for _, u in feeds}
    assert "http://h/10/last-patch.json" in urls
    assert "http://t/1/last-patch.json" in urls
    types = {k.entity_type for k, _ in feeds}
    assert types == {"hero", "trinket"}


def test_hsjson_cards_url(tmp_path):
    path = _write(tmp_path, """
        firestone: {hero_url: "h", trinket_url: "t", brackets: ["100"], periods: ["last-patch"]}
        hsjson: {cards_url: "http://cards"}
    """)
    assert config.hsjson_cards_url(path) == "http://cards"
```

- [ ] **Step 7: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_config.py -q`
Expected: FAIL (`ModuleNotFoundError: bgtiers.config`).

- [ ] **Step 8: Implement `config.py`**

Create `data-pipeline/src/bgtiers/config.py`:
```python
"""Load sources.yaml -> list of (FeedKey, raw_url)."""
from __future__ import annotations
import yaml

from .models import FeedKey


def _read(path: str) -> dict:
    with open(path) as fh:
        return yaml.safe_load(fh)


def load_feeds(path: str) -> list[tuple[FeedKey, str]]:
    fs = _read(path)["firestone"]
    feeds: list[tuple[FeedKey, str]] = []
    for entity_type, url_key in (("hero", "hero_url"), ("trinket", "trinket_url")):
        for mmr in fs["brackets"]:
            for period in fs["periods"]:
                url = fs[url_key].format(mmr=mmr, period=period)
                feeds.append((FeedKey("firestone", entity_type, mmr, period), url))
    return feeds


def hsjson_cards_url(path: str) -> str:
    return _read(path)["hsjson"]["cards_url"]
```

- [ ] **Step 9: Run to verify it passes**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_config.py -q`
Expected: 2 passed.

- [ ] **Step 10: Write the failing CLI test** (drives fetch-stats with a mock transport; asserts partial-failure exit + run-lock + that good feeds still load)

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
          trinket_url: "http://t/{mmr}/{period}.json"
          brackets: ["10"]
          periods: ["last-patch"]
        hsjson:
          cards_url: "http://cards"
    """))
    return str(p)


def _good_hero_body():
    return json.dumps({"stats": [
        {"heroCardId": "BG_HERO_001", "dataPoints": 100, "averagePosition": 4.0}
    ]}).encode()


def test_fetch_stats_partial_failure_exits_nonzero(tmp_path, monkeypatch):
    dbp = str(tmp_path / "t.db")
    db.init_db(db.connect(dbp))

    def handler(req):
        if req.url.host == "h":                       # hero feed OK
            return httpx.Response(200, content=_good_hero_body(), headers={"ETag": '"e"'})
        return httpx.Response(500)                     # trinket feed fails

    real_client = httpx.Client                        # capture BEFORE patching to avoid recursion
    monkeypatch.setattr(cli.httpx, "Client",
                        lambda *a, **k: real_client(transport=httpx.MockTransport(handler)))

    args = cli.build_parser().parse_args(
        ["--db", dbp, "--sources", _sources(tmp_path), "fetch-stats",
         "--lock", str(tmp_path / ".lock")])
    with pytest.raises(SystemExit) as ei:
        args.func(args)
    assert ei.value.code == 1                          # partial failure -> nonzero

    conn = db.connect(dbp)
    # the good hero feed still landed despite the trinket failure
    assert conn.execute("SELECT COUNT(*) FROM snapshot WHERE entity_type='hero'").fetchone()[0] == 1
```

- [ ] **Step 11: Run to verify it fails**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_cli.py -q`
Expected: FAIL (`AttributeError: module 'bgtiers' has no attribute 'cli'` / missing).

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
    conn = db.connect(args.db)
    db.init_db(conn)
    print(f"initialized {args.db}")


def cmd_sync_entities(args):
    conn = db.connect(args.db)
    db.init_db(conn)
    url = config.hsjson_cards_url(args.sources)
    cards = httpx.get(url, timeout=60).json()
    n = entities.sync_entities(conn, cards, now=_now())
    print(f"synced {n} entities")


def cmd_fetch_stats(args):
    # Acquire the run-lock FIRST so the whole run (incl. init/config) is mutually
    # exclusive (spec §4.3). Exit cleanly (code 2) if another run holds it.
    try:
        with run_lock(args.lock):
            return _do_fetch_stats(args)
    except AlreadyRunning as exc:
        print(f"skipped: {exc}", file=sys.stderr)
        sys.exit(2)


def _do_fetch_stats(args):
    conn = db.connect(args.db)
    db.init_db(conn)
    feeds = config.load_feeds(args.sources)
    failures = 0
    with httpx.Client(timeout=60) as client:
        for key, url in feeds:
            fs = conn.execute(
                "SELECT etag, last_modified FROM fetch_state WHERE source=? AND "
                "entity_type=? AND mmr_bracket=? AND time_period=? AND mode=? AND region=?",
                (key.source, key.entity_type, key.mmr_bracket, key.time_period,
                 key.mode, key.region),
            ).fetchone()
            prev_etag = fs["etag"] if fs else None
            prev_lm = fs["last_modified"] if fs else None
            try:
                res = fetch.fetch_feed(client, key, url, prev_etag, prev_lm)
                if res.status == 304:
                    load.load_feed(conn, key, url, 304, None, res.etag,
                                   res.last_modified, None, _now())
                    print(f"{key.entity_type}/{key.mmr_bracket}: 304 not modified")
                    continue
                # spec §4.1: log real Last-Modified to learn the feed's refresh cadence
                print(f"{key.entity_type}/{key.mmr_bracket}: 200 last-modified={res.last_modified}")
                raw = json.loads(res.body)
                feed = normalize.normalize_firestone(raw, key.entity_type)  # may raise
                outcome = load.load_feed(conn, key, url, 200, res.body, res.etag,
                                         res.last_modified, feed, _now())
                print(f"{key.entity_type}/{key.mmr_bracket}: {outcome.value}")
            except Exception as exc:  # one feed failing must not kill the rest
                failures += 1
                print(f"{key.entity_type}/{key.mmr_bracket}: FAILED {exc}", file=sys.stderr)
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

Add the console script to `pyproject.toml` under `[project]`:
```toml
[project.scripts]
bgtiers = "bgtiers.cli:main"
```

- [ ] **Step 13: Run the CLI test**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest tests/test_cli.py -q`
Expected: 1 passed.

- [ ] **Step 14: Verify CLI wiring (init-db smoke)**

Run:
```bash
cd /Users/jun/code/bob_assistant/data-pipeline
uv run bgtiers --db /tmp/bgtiers_test.db init-db
uv run python -c "import sqlite3;print(sorted(r[0] for r in sqlite3.connect('/tmp/bgtiers_test.db').execute(\"select name from sqlite_master where type='table'\")))"
rm -f /tmp/bgtiers_test.db
```
Expected: prints `['entity', 'entity_stats', 'fetch_state', 'raw_payload', 'snapshot', 'sqlite_sequence']`.

- [ ] **Step 15: Commit**

```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "feat(data-pipeline): run-lock, sources config, and CLI (init-db/sync-entities/fetch-stats)"
```

---

## Stage 9: End-to-End Integration (fixtures, no network)

**Files:**
- Create: `data-pipeline/tests/test_integration.py`

- [ ] **Step 1: Write the integration test**

Create `data-pipeline/tests/test_integration.py`:
```python
"""End-to-end on fixtures: sync entities -> load hero + trinket feeds -> query
v_latest_stats. No network: feeds are loaded directly via load.load_feed with
normalized fixtures."""
import json
import pathlib

from bgtiers import db, entities, normalize, load
from bgtiers.models import FeedKey

FIX = pathlib.Path(__file__).parent / "fixtures"
HERO_KEY = FeedKey("firestone", "hero", "10", "last-patch")
TRINKET_KEY = FeedKey("firestone", "trinket", "10", "last-patch")


def _load(name):
    return json.loads((FIX / name).read_text())


def test_full_pipeline_populates_latest_view():
    conn = db.connect(":memory:")
    db.init_db(conn)

    entities.sync_entities(conn, _load("hsjson_cards.json"), now="2026-05-31T00:00:00Z")

    body = (FIX / "firestone_heroes.json").read_bytes()
    feed = normalize.normalize_firestone(_load("firestone_heroes.json"), "hero")
    out = load.load_feed(conn, HERO_KEY, "http://x", 200, body, '"e1"', "lm1", feed,
                         "2026-05-31T00:01:00Z")
    assert out == load.Outcome.INSERTED

    rows = conn.execute(
        "SELECT card_id, name, avg_placement, data_points FROM v_latest_stats "
        "WHERE entity_type='hero' ORDER BY avg_placement"
    ).fetchall()
    assert rows[0]["card_id"] == "BG_HERO_001"
    assert rows[0]["name"] == "Test Hero One"      # joined from entity sync
    assert rows[0]["avg_placement"] == 3.92

    # idempotent re-run -> still one snapshot, latest view unchanged
    load.load_feed(conn, HERO_KEY, "http://x", 200, body, '"e1"', "lm1", feed,
                   "2026-05-31T00:02:00Z")
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 1
    assert len(conn.execute("SELECT * FROM v_latest_stats").fetchall()) == 2


def test_trinket_feed_populates_class_and_latest_view():
    conn = db.connect(":memory:")
    db.init_db(conn)

    body = (FIX / "firestone_trinkets.json").read_bytes()
    feed = normalize.normalize_firestone(_load("firestone_trinkets.json"), "trinket")
    out = load.load_feed(conn, TRINKET_KEY, "http://t", 200, body, '"e1"', "lm1", feed,
                         "2026-05-31T00:01:00Z")
    assert out == load.Outcome.INSERTED

    rows = conn.execute(
        "SELECT card_id, trinket_class, avg_placement FROM v_latest_stats "
        "WHERE entity_type='trinket' ORDER BY avg_placement"
    ).fetchall()
    # greater trinket BG_TRINKET_02 has the best (lowest) avg_placement in the fixture
    assert rows[0]["card_id"] == "BG_TRINKET_02"
    assert rows[0]["trinket_class"] == "greater"     # promoted to entity, joined in view
    classes = {r["card_id"]: r["trinket_class"] for r in rows}
    assert classes["BG_TRINKET_01"] == "lesser"


def test_latest_view_returns_only_newest_snapshot():
    conn = db.connect(":memory:")
    db.init_db(conn)
    entities.sync_entities(conn, _load("hsjson_cards.json"), now="t0")

    raw = _load("firestone_heroes.json")
    feed_v1 = normalize.normalize_firestone(raw, "hero")
    load.load_feed(conn, HERO_KEY, "http://x", 200, b"v1", '"e1"', "lm1", feed_v1, "t1")

    raw2 = json.loads(json.dumps(raw))
    raw2["stats"][0]["averagePosition"] = 3.10
    feed_v2 = normalize.normalize_firestone(raw2, "hero")
    load.load_feed(conn, HERO_KEY, "http://x", 200, b"v2", '"e2"', "lm2", feed_v2, "t2")

    val = conn.execute(
        "SELECT avg_placement FROM v_latest_stats WHERE card_id='BG_HERO_001'"
    ).fetchone()["avg_placement"]
    assert val == 3.10   # newest wins
    assert conn.execute("SELECT COUNT(*) FROM snapshot").fetchone()[0] == 2  # history kept
```

- [ ] **Step 2: Run the full suite**

Run: `cd /Users/jun/code/bob_assistant/data-pipeline && uv run pytest -q`
Expected: all tests pass (smoke + db + hashing + normalize + entities + load + fetch + runlock + integration).

- [ ] **Step 3: Write README + commit**

Create `data-pipeline/README.md`:
```markdown
# bgtiers — BG hero/trinket tier data pipeline

Scrapes raw Firestone BG stats + HearthstoneJSON identity into an append-only SQLite DB.
Scope: data acquisition + storage only (see spec 2026-05-31).

## Commands
    uv run bgtiers --db bgtiers.db init-db
    uv run bgtiers --db bgtiers.db sync-entities          # HearthstoneJSON identity
    uv run bgtiers --db bgtiers.db fetch-stats            # Firestone stats (uses run-lock)

## Schedule (example, daily)
    # crontab: run once a day; flock prevents overlap
    0 9 * * * cd /path/to/data-pipeline && uv run bgtiers fetch-stats

## Config
`sources.yaml` holds the Firestone URL templates (filled by endpoint-discovery spike)
and the bracket/period matrix to fetch.
```

Run:
```bash
cd /Users/jun/code/bob_assistant
git add data-pipeline
git commit -m "test(data-pipeline): end-to-end integration + README"
```

---

## Per-Stage Codex Review (required between stages)

After each Stage's final commit, before starting the next Stage:

```bash
cd /Users/jun/code/bob_assistant
codex exec --skip-git-repo-check "Review the latest commit's diff in data-pipeline/ against \
docs/superpowers/specs/2026-05-31-bg-tier-data-pipeline-design.md. Check correctness, \
spec-conformance, test quality, and edge cases. Do NOT modify files; list findings by \
severity (blocker/should-fix/nice-to-have) and give a READY/NOT-READY verdict for proceeding."
```

Address blockers + should-fix before the next Stage. Re-run until READY.

---

## Self-Review: Spec Coverage Map

| Spec section | Covered by |
|---|---|
| §3.1 HearthstoneJSON identity | Stage 5 (`entities.sync_entities`, BG filter, image URL) |
| §3.2 Firestone stats source | Stage 0 discovery + Stage 4 normalize + Stage 7 fetch |
| §4.1 discover | Stage 0 + Stage 8 `config.load_feeds` |
| §4.1 fetch (conditional, validators) | Stage 7 + Stage 8 wiring (prev etag/lm from fetch_state) |
| §4.1 normalize + validation | Stage 4 |
| §4.1 load (BEGIN IMMEDIATE, dedup, validator-on-success) | Stage 6 |
| §4.2 subcommands (init-db/sync-entities/fetch-stats) | Stage 8 |
| §4.3 run-lock + scheduling | Stage 8 (`runlock`) + Stage 9 README |
| §4.4 unknown card_id -> stub | Stage 5 (`ensure_entity`) + Stage 6 test |
| §5.1-5.5 schema (5 tables + raw gzip) | Stage 2 + Stage 6 (raw_payload write) |
| §5.6 content_hash contract | Stage 3 |
| §5.7 indexes | Stage 2 |
| §5.8 v_latest_stats (+tie-break) | Stage 2 (ROW_NUMBER order) + Stage 9 test |
| §7 success criteria 1-9 | Stages 2,5,4/6,6,6,9,6,8,4-9 (tests throughout) |

**Deferred to execution (depends on Stage 0 real data, not logic gaps):** exact Firestone URL templates in `sources.yaml`; the real source field names behind `normalize._ID_FIELD` / `_TRINKET_CLASS_FIELD` and `entities._is_bg_hero` / `_is_bg_trinket` (the code reads these via named constants so adjustment is a one-line change, locked by tests run against the real fixtures). `entity.trinket_class` IS populated end-to-end (normalize promotes it → `ensure_entity` writes it → `v_latest_stats` joins it; covered by `test_load_trinket_sets_entity_class` and `test_trinket_feed_populates_class_and_latest_view`). These are data-shape unknowns, not placeholders.
```
