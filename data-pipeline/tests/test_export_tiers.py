import pytest
from bgtiers import db, export_tiers


def _seed(conn, rows, mode="solo", region="global"):
    """rows: list of (card_id, en, zh|None, avg)."""
    db.init_db(conn)
    conn.execute("BEGIN IMMEDIATE")
    snap = conn.execute(
        "INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,"
        " content_hash, fetched_at, raw_url) VALUES"
        " ('firestone','hero','100','last-patch',?,?,'h','t','u')", (mode, region)).lastrowid
    for cid, en, zh, avg in rows:
        eid = conn.execute(
            "INSERT INTO entity (entity_type, card_id, name, first_seen_at, last_seen_at)"
            " VALUES ('hero',?,?,'t','t')", (cid, en)).lastrowid
        if zh is not None:
            conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key)"
                         " VALUES (?,?,?,?)", (eid, 'zhTW', zh, zh))
        conn.execute("INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement,"
                     " data_points) VALUES (?,?,?,?)", (snap, eid, avg, 5000))
    conn.execute("COMMIT")


def test_percentile_and_fallback():
    conn = db.connect(":memory:")
    rows = [(f"BG_HERO_{i:03d}", f"En{i}", (f"中{i}" if i != 4 else None), 3.0 + 0.2 * i)
            for i in range(10)]
    _seed(conn, rows)
    out = export_tiers.build(conn, generated_at="2026-06-01T00:00:00Z")
    assert out["bracket"] == "100" and out["period"] == "last-patch"
    by = {h["cardId"]: h for h in out["heroes"]}
    assert by["BG_HERO_000"]["tier"] == "S"                      # p=0.05 < 0.12
    assert by["BG_HERO_009"]["tier"] == "C"                      # p=0.95
    assert by["BG_HERO_004"]["names"]["zhTW"] == "En4"           # zhTW fallback to enUS
    assert by["BG_HERO_000"]["names"]["enUS"] == "En0"
    # cut math: p=(i+0.5)/10 -> S only for i=0 (0.05); i=1 is 0.15 >= 0.12 -> A
    assert sum(1 for h in out["heroes"] if h["tier"] == "S") == 1


def test_rejects_multiple_mode_region():
    conn = db.connect(":memory:")
    _seed(conn, [("BG_HERO_001", "En1", "中1", 3.5)])
    _seed(conn, [("BG_HERO_002", "En2", "中2", 3.6)], region="eu")   # 2nd region
    with pytest.raises(ValueError, match="mode/region"):
        export_tiers.build(conn)
