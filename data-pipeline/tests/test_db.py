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


def test_entity_name_table_and_index_created(conn):
    cols = {r["name"] for r in conn.execute("PRAGMA table_info(entity_name)").fetchall()}
    assert cols == {"entity_id", "locale", "name", "name_key"}
    idx = {r["name"] for r in conn.execute("PRAGMA index_list(entity_name)").fetchall()}
    assert "idx_entity_name_lookup" in idx


def test_entity_name_unique_entity_locale(conn):
    import pytest
    conn.execute("INSERT INTO entity (entity_type, card_id, first_seen_at, last_seen_at) "
                 "VALUES ('hero','H',?,?)", ("t", "t"))
    eid = conn.execute("SELECT entity_id FROM entity").fetchone()["entity_id"]
    conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key) VALUES (?,?,?,?)",
                 (eid, "enUS", "Foo", "foo"))
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key) VALUES (?,?,?,?)",
                     (eid, "enUS", "Bar", "bar"))
