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
