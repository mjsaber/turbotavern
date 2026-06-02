import pytest
from bgtiers import db, export_tiers


def _seed(conn, rows, mode="solo", region="global"):
    """rows: list of (card_id, en, locnames, avg). locnames: dict {locale: name} (extra locales)."""
    db.init_db(conn)
    conn.execute("BEGIN IMMEDIATE")
    snap = conn.execute(
        "INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,"
        " content_hash, fetched_at, raw_url) VALUES"
        " ('firestone','hero','100','last-patch',?,?,'h','t','u')", (mode, region)).lastrowid
    for cid, en, locnames, avg in rows:
        eid = conn.execute(
            "INSERT INTO entity (entity_type, card_id, name, first_seen_at, last_seen_at)"
            " VALUES ('hero',?,?,'t','t')", (cid, en)).lastrowid
        for loc, name in (locnames or {}).items():
            conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key)"
                         " VALUES (?,?,?,?)", (eid, loc, name, name))
        conn.execute("INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement,"
                     " data_points) VALUES (?,?,?,?)", (snap, eid, avg, 5000))
    conn.execute("COMMIT")


def test_percentile_and_multilocale_names():
    conn = db.connect(":memory:")
    rows = [(f"BG_HERO_{i:03d}", f"En{i}",
             ({"zhTW": f"繁{i}", "zhCN": f"简{i}"} if i != 4 else {}),   # hero 4 has no Chinese names
             3.0 + 0.2 * i) for i in range(10)]
    _seed(conn, rows)
    out = export_tiers.build(conn, generated_at="2026-06-01T00:00:00Z")
    assert out["bracket"] == "100" and out["period"] == "last-patch"
    by = {h["cardId"]: h for h in out["heroes"]}
    assert by["BG_HERO_000"]["tier"] == "S"                      # p=0.05 < 0.12
    assert by["BG_HERO_009"]["tier"] == "C"                      # p=0.95
    # all three locales present when available
    assert by["BG_HERO_000"]["names"] == {"enUS": "En0", "zhTW": "繁0", "zhCN": "简0"}
    # missing Chinese names -> only enUS (no fallback duplication)
    assert by["BG_HERO_004"]["names"] == {"enUS": "En4"}
    assert sum(1 for h in out["heroes"] if h["tier"] == "S") == 1


def test_rejects_multiple_region():
    conn = db.connect(":memory:")
    _seed(conn, [("BG_HERO_001", "En1", {"zhTW": "繁1"}, 3.5)])
    _seed(conn, [("BG_HERO_002", "En2", {"zhTW": "繁2"}, 3.6)], region="eu")   # 2nd region
    with pytest.raises(ValueError, match="mode/region"):
        export_tiers.build(conn)


def test_rejects_multiple_mode():
    conn = db.connect(":memory:")
    _seed(conn, [("BG_HERO_001", "En1", {"zhTW": "繁1"}, 3.5)])
    _seed(conn, [("BG_HERO_002", "En2", {"zhTW": "繁2"}, 3.6)], mode="duos")   # 2nd mode
    with pytest.raises(ValueError, match="mode/region"):
        export_tiers.build(conn)


def test_empty_view_reports_no_rows_not_mode_region():
    conn = db.connect(":memory:")
    db.init_db(conn)
    with pytest.raises(ValueError, match="no hero rows"):
        export_tiers.build(conn)


def test_card_id_tiebreak_on_equal_avg():
    conn = db.connect(":memory:")
    _seed(conn, [("BG_HERO_009", "En9", {}, 4.0),
                 ("BG_HERO_002", "En2", {}, 4.0),
                 ("BG_HERO_005", "En5", {}, 4.0)])
    out = export_tiers.build(conn)
    assert [h["cardId"] for h in out["heroes"]] == ["BG_HERO_002", "BG_HERO_005", "BG_HERO_009"]


def test_missing_enus_name_fails_loudly():
    conn = db.connect(":memory:")
    db.init_db(conn)
    conn.execute("BEGIN IMMEDIATE")
    snap = conn.execute(
        "INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,"
        " content_hash, fetched_at, raw_url) VALUES"
        " ('firestone','hero','100','last-patch','solo','global','h','t','u')").lastrowid
    eid = conn.execute(
        "INSERT INTO entity (entity_type, card_id, name, first_seen_at, last_seen_at)"
        " VALUES ('hero','BG_HERO_777',NULL,'t','t')").lastrowid
    conn.execute("INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement, data_points)"
                 " VALUES (?,?,?,?)", (snap, eid, 3.5, 5000))
    conn.execute("COMMIT")
    with pytest.raises(ValueError, match="missing enUS name"):
        export_tiers.build(conn)
