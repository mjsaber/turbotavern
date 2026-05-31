import json
import pathlib
import pytest
from bgtiers import entities
from bgtiers.localize import normalize_name_key

FIX = pathlib.Path(__file__).parent / "fixtures"


def _en():
    return json.loads((FIX / "hsjson_cards.json").read_text())


def _zh():
    return json.loads((FIX / "hsjson_cards_zhTW.json").read_text())


def _sync(conn, cards, locale="enUS", default="enUS", now="t", known=None):
    return entities.sync_entities(conn, cards, locale, default, now, known)


# ---- identity (default locale) — parity with previous behaviour ----

def test_sync_inserts_heroes_and_trinkets(conn):
    n, synced = _sync(conn, _en())
    by = {r["card_id"]: r for r in
          conn.execute("SELECT card_id, entity_type, trinket_class FROM entity").fetchall()}
    assert set(by) == {"BG_HERO_001", "BG_HERO_002", "BG30_MagicItem_902", "BG30_MagicItem_301"}
    assert by["BG_HERO_001"]["entity_type"] == "hero"
    assert by["BG30_MagicItem_902"]["entity_type"] == "trinket"
    assert n == 4                                     # constructed card excluded
    assert set(synced) == {("hero", "BG_HERO_001"), ("hero", "BG_HERO_002"),
                           ("trinket", "BG30_MagicItem_902"), ("trinket", "BG30_MagicItem_301")}


def test_sync_sets_trinket_class_from_spellschool(conn):
    _sync(conn, _en())
    cls = {r["card_id"]: r["trinket_class"] for r in
           conn.execute("SELECT card_id, trinket_class FROM entity").fetchall()}
    assert cls["BG30_MagicItem_902"] == "greater"
    assert cls["BG30_MagicItem_301"] == "lesser"


def test_sync_is_idempotent(conn):
    _sync(conn, _en()); _sync(conn, _en())
    assert conn.execute("SELECT COUNT(*) FROM entity").fetchone()[0] == 4
    assert conn.execute("SELECT COUNT(*) FROM entity_name").fetchone()[0] == 4   # enUS names, no dup


def test_ensure_entity_creates_stub_for_unknown_card(conn):
    eid = entities.ensure_entity(conn, "trinket", "BG30_MagicItem_999", now="t")
    row = conn.execute("SELECT * FROM entity WHERE entity_id=?", (eid,)).fetchone()
    assert row["card_id"] == "BG30_MagicItem_999"
    assert row["name"] is None and row["trinket_class"] is None
    assert row["entity_type"] == "trinket"


def test_ensure_entity_returns_existing_id(conn):
    a = entities.ensure_entity(conn, "hero", "BG_HERO_X", now="t")
    b = entities.ensure_entity(conn, "hero", "BG_HERO_X", now="t")
    assert a == b


def test_sync_backfills_existing_stub(conn):
    entities.ensure_entity(conn, "trinket", "BG30_MagicItem_301", now="t0")
    _sync(conn, _en(), now="t1")
    row = conn.execute("SELECT name, dbf_id, trinket_class FROM entity "
                       "WHERE card_id='BG30_MagicItem_301'").fetchone()
    assert row["name"] == "Lesser One" and row["dbf_id"] == 60301 and row["trinket_class"] == "lesser"


def test_sync_does_not_clobber_known_name_with_null(conn):
    _sync(conn, _en(), now="t0")
    _sync(conn, [{"id": "BG_HERO_001", "type": "HERO", "set": "BATTLEGROUNDS"}], now="t1")
    row = conn.execute("SELECT name, dbf_id FROM entity WHERE card_id='BG_HERO_001'").fetchone()
    assert row["name"] == "Test Hero One" and row["dbf_id"] == 50001


# ---- entity.name stays enUS; entity_name holds all locales ----

def test_entity_name_default_only_sets_entity_name_column(conn):
    _, synced = _sync(conn, _en())
    _sync(conn, _zh(), locale="zhTW", known=synced)
    # entity.name == enUS for a hero
    name = conn.execute("SELECT name FROM entity WHERE card_id='BG_HERO_001'").fetchone()["name"]
    assert name == "Test Hero One"
    # both-locale rows in entity_name
    eid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG_HERO_001'").fetchone()["entity_id"]
    locs = {r["locale"]: r["name"] for r in
            conn.execute("SELECT locale, name FROM entity_name WHERE entity_id=?", (eid,)).fetchall()}
    assert locs == {"enUS": "Test Hero One", "zhTW": "測試英雄一"}


def test_id_to_name_zhTW(conn):
    _, synced = _sync(conn, _en()); _sync(conn, _zh(), locale="zhTW", known=synced)
    eid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG_HERO_002'").fetchone()["entity_id"]
    row = conn.execute("SELECT name FROM entity_name WHERE entity_id=? AND locale='zhTW'", (eid,)).fetchone()
    assert row["name"] == "測試英雄二"


def test_name_to_entity_both_languages(conn):
    _, synced = _sync(conn, _en()); _sync(conn, _zh(), locale="zhTW", known=synced)
    # zhTW name -> entity
    key = normalize_name_key("測試英雄一")
    eid = conn.execute("SELECT entity_id FROM entity_name WHERE locale='zhTW' AND name_key=?",
                       (key,)).fetchone()["entity_id"]
    assert conn.execute("SELECT card_id FROM entity WHERE entity_id=?", (eid,)).fetchone()["card_id"] == "BG_HERO_001"
    # enUS case/fullwidth variant still hits
    eid2 = conn.execute("SELECT entity_id FROM entity_name WHERE locale='enUS' AND name_key=?",
                        (normalize_name_key("ＴＥＳＴ  hero  ONE"),)).fetchone()["entity_id"]
    assert eid2 == eid


# ---- missing translation + reconcile ----

def test_missing_zhTW_name_no_row_but_enUS_present(conn):
    _, synced = _sync(conn, _en()); _sync(conn, _zh(), locale="zhTW", known=synced)
    eid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG30_MagicItem_301'").fetchone()["entity_id"]
    locs = {r["locale"] for r in
            conn.execute("SELECT locale FROM entity_name WHERE entity_id=?", (eid,)).fetchall()}
    assert locs == {"enUS"}                            # no zhTW row (301 has no zhTW name)


def test_reconcile_deletes_stale_translation_when_blank(conn):
    _, synced = _sync(conn, _en())
    _sync(conn, _zh(), locale="zhTW", known=synced)   # 902 has zhTW name
    eid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG30_MagicItem_902'").fetchone()["entity_id"]
    assert conn.execute("SELECT COUNT(*) FROM entity_name WHERE entity_id=? AND locale='zhTW'",
                        (eid,)).fetchone()[0] == 1
    # re-sync zhTW where 902 name is now blank -> row deleted
    zh2 = [dict(c, name="  ") if c["id"] == "BG30_MagicItem_902" else c for c in _zh()]
    _sync(conn, zh2, locale="zhTW", known=synced)
    assert conn.execute("SELECT COUNT(*) FROM entity_name WHERE entity_id=? AND locale='zhTW'",
                        (eid,)).fetchone()[0] == 0


# ---- collision + stale-stub guard ----

def test_name_key_collision_returns_two_entities(conn):
    cards = [{"id": "H_A", "type": "HERO", "set": "BATTLEGROUNDS", "name": "Foo"},
             {"id": "H_B", "type": "HERO", "set": "BATTLEGROUNDS", "name": "foo"}]
    _sync(conn, cards)
    rows = conn.execute("SELECT entity_id FROM entity_name WHERE locale='enUS' AND name_key=?",
                        (normalize_name_key("Foo"),)).fetchall()
    assert len(rows) == 2


def test_non_default_card_not_in_seen_map_is_skipped(conn):
    # pre-existing stub NOT in this run's enUS cards
    entities.ensure_entity(conn, "hero", "BG_HERO_GHOST", now="t0")
    _, synced = _sync(conn, _en())                    # enUS does NOT include GHOST
    ghost_zh = _zh() + [{"id": "BG_HERO_GHOST", "type": "HERO", "set": "BATTLEGROUNDS", "name": "幽靈"}]
    _sync(conn, ghost_zh, locale="zhTW", known=synced)
    gid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG_HERO_GHOST'").fetchone()["entity_id"]
    assert conn.execute("SELECT COUNT(*) FROM entity_name WHERE entity_id=?", (gid,)).fetchone()[0] == 0
