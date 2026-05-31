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
