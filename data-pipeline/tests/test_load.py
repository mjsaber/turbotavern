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
