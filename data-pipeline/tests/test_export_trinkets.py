import pytest
from bgtiers import db, export_trinkets


def _seed(conn, rows, mode="solo", region="global"):
    """rows: list of (card_id, trinket_class, en, locnames, avg). locnames: {locale: name} extras."""
    db.init_db(conn)
    conn.execute("BEGIN IMMEDIATE")
    snap = conn.execute(
        "INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,"
        " content_hash, fetched_at, raw_url) VALUES"
        " ('firestone','trinket','100','last-patch',?,?,'h','t','u')", (mode, region)).lastrowid
    for cid, tclass, en, locnames, avg in rows:
        eid = conn.execute(
            "INSERT INTO entity (entity_type, card_id, name, trinket_class, first_seen_at, last_seen_at)"
            " VALUES ('trinket',?,?,?,'t','t')", (cid, en, tclass)).lastrowid
        for loc, name in (locnames or {}).items():
            conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key)"
                         " VALUES (?,?,?,?)", (eid, loc, name, name))
        conn.execute("INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement,"
                     " data_points) VALUES (?,?,?,?)", (snap, eid, avg, 5000))
    conn.execute("COMMIT")


def test_per_class_percentile_tiers_are_independent():
    conn = db.connect(":memory:")
    # 5 lesser (avg 3.0..4.6) + 5 greater (avg 5.0..6.6). If cuts were GLOBAL, no greater could be 'S';
    # per-class, the best greater IS 'S' even though its avg is worse than every lesser.
    rows = [(f"L{i}", "lesser", f"Lesser{i}", {"zhTW": f"小繁{i}", "zhCN": f"小简{i}"}, 3.0 + 0.4 * i) for i in range(5)]
    rows += [(f"G{i}", "greater", f"Greater{i}", {"zhTW": f"大繁{i}", "zhCN": f"大简{i}"}, 5.0 + 0.4 * i) for i in range(5)]
    _seed(conn, rows)
    out = export_trinkets.build(conn, generated_at="2026-06-01T00:00:00Z")
    by = {t["cardId"]: t for t in out["trinkets"]}
    # best in EACH class is S despite very different absolute placements
    assert by["L0"]["tier"] == "S" and by["L0"]["trinketClass"] == "lesser"
    assert by["G0"]["tier"] == "S" and by["G0"]["trinketClass"] == "greater"
    assert by["L4"]["tier"] == "C" and by["G4"]["tier"] == "C"
    # exactly one S per class
    assert sum(1 for t in out["trinkets"] if t["trinketClass"] == "lesser" and t["tier"] == "S") == 1
    assert sum(1 for t in out["trinkets"] if t["trinketClass"] == "greater" and t["tier"] == "S") == 1


def test_all_three_locales_and_avg_placement_present_per_trinket():
    conn = db.connect(":memory:")
    _seed(conn, [("L0", "lesser", "Welcome Inn", {"zhTW": "歡迎客棧", "zhCN": "欢迎客栈"}, 3.5)])
    out = export_trinkets.build(conn)
    t = out["trinkets"][0]
    assert t["names"] == {"enUS": "Welcome Inn", "zhTW": "歡迎客棧", "zhCN": "欢迎客栈"}
    assert t["avgPlacement"] == 3.5   # kept for ranking the offered set
    assert out["schemaVersion"] == 1 and out["bracket"] == "100" and out["period"] == "last-patch"


def test_missing_chinese_names_yield_enus_only_no_fallback():
    conn = db.connect(":memory:")
    _seed(conn, [("L0", "lesser", "OnlyEnglish", {}, 3.5)])
    out = export_trinkets.build(conn)
    assert out["trinkets"][0]["names"] == {"enUS": "OnlyEnglish"}


def test_colliding_base_name_across_classes_stays_distinct():
    conn = db.connect(":memory:")
    # a lesser and a greater that share the SAME localized base name -> distinct cardId+class entries
    _seed(conn, [("L_SHARED", "lesser", "Mystic Trinket", {"zhTW": "神秘飾品"}, 3.4),
                 ("G_SHARED", "greater", "Mystic Trinket", {"zhTW": "神秘飾品"}, 5.4)])
    out = export_trinkets.build(conn)
    keyed = {(t["cardId"], t["trinketClass"]) for t in out["trinkets"]}
    assert ("L_SHARED", "lesser") in keyed and ("G_SHARED", "greater") in keyed
    assert len(out["trinkets"]) == 2


def test_card_id_tiebreak_on_equal_avg_within_class():
    conn = db.connect(":memory:")
    _seed(conn, [("L9", "lesser", "E9", {}, 4.0),
                 ("L2", "lesser", "E2", {}, 4.0),
                 ("L5", "lesser", "E5", {}, 4.0)])
    out = export_trinkets.build(conn)
    assert [t["cardId"] for t in out["trinkets"]] == ["L2", "L5", "L9"]


def test_low_data_point_trinkets_are_excluded():
    conn = db.connect(":memory:")
    db.init_db(conn)
    conn.execute("BEGIN IMMEDIATE")
    snap = conn.execute(
        "INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,"
        " content_hash, fetched_at, raw_url) VALUES"
        " ('firestone','trinket','100','last-patch','solo','global','h','t','u')").lastrowid

    def ins(cid, avg, dp):
        eid = conn.execute(
            "INSERT INTO entity (entity_type, card_id, name, trinket_class, first_seen_at, last_seen_at)"
            " VALUES ('trinket',?,?,'lesser','t','t')", (cid, cid)).lastrowid
        conn.execute("INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement, data_points)"
                     " VALUES (?,?,?,?)", (snap, eid, avg, dp))

    ins("REAL", 4.2, 5000)      # plenty of games
    ins("NOISE", 1.0, 1)        # one game -> avg 1.0 would be a bogus 'S' if not filtered
    ins("LOW", 3.5, 50)         # below the 100 floor
    conn.execute("COMMIT")

    out = export_trinkets.build(conn)
    cids = {t["cardId"] for t in out["trinkets"]}
    assert cids == {"REAL"}     # only the well-sampled trinket survives


def test_rejects_multiple_region():
    conn = db.connect(":memory:")
    _seed(conn, [("L0", "lesser", "E0", {}, 3.5)])
    _seed(conn, [("L1", "lesser", "E1", {}, 3.6)], region="eu")
    with pytest.raises(ValueError, match="mode/region"):
        export_trinkets.build(conn)


def test_empty_view_reports_no_rows():
    conn = db.connect(":memory:")
    db.init_db(conn)
    with pytest.raises(ValueError, match="no trinket rows"):
        export_trinkets.build(conn)


def test_invalid_trinket_class_fails_loudly():
    conn = db.connect(":memory:")
    db.init_db(conn)
    conn.execute("BEGIN IMMEDIATE")
    snap = conn.execute(
        "INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,"
        " content_hash, fetched_at, raw_url) VALUES"
        " ('firestone','trinket','100','last-patch','solo','global','h','t','u')").lastrowid
    # trinket_class NULL is schema-legal but a data bug for an offered trinket -> must fail loudly
    eid = conn.execute(
        "INSERT INTO entity (entity_type, card_id, name, trinket_class, first_seen_at, last_seen_at)"
        " VALUES ('trinket','L_NULL','NoClass',NULL,'t','t')").lastrowid
    conn.execute("INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement, data_points)"
                 " VALUES (?,?,?,?)", (snap, eid, 3.5, 5000))
    conn.execute("COMMIT")
    with pytest.raises(ValueError, match="invalid trinket_class"):
        export_trinkets.build(conn)


def test_missing_enus_name_fails_loudly():
    conn = db.connect(":memory:")
    db.init_db(conn)
    conn.execute("BEGIN IMMEDIATE")
    snap = conn.execute(
        "INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,"
        " content_hash, fetched_at, raw_url) VALUES"
        " ('firestone','trinket','100','last-patch','solo','global','h','t','u')").lastrowid
    eid = conn.execute(
        "INSERT INTO entity (entity_type, card_id, name, trinket_class, first_seen_at, last_seen_at)"
        " VALUES ('trinket','L_NONAME',NULL,'lesser','t','t')").lastrowid
    conn.execute("INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement, data_points)"
                 " VALUES (?,?,?,?)", (snap, eid, 3.5, 5000))
    conn.execute("COMMIT")
    with pytest.raises(ValueError, match="missing enUS name"):
        export_trinkets.build(conn)
