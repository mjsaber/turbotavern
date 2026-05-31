import json
import textwrap
import httpx
import pytest

from bgtiers import cli, db, entities


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


# ---- sync-entities multi-locale ----

def _sync_sources(tmp_path):
    p = tmp_path / "sources_loc.yaml"
    p.write_text(textwrap.dedent("""
        firestone: {hero_url: "h", trinket_url: "t", brackets: ["10"], periods: ["last-patch"]}
        hsjson:
          cards_url_template: "http://cards/{locale}.json"
          default_locale: "enUS"
          locales: ["enUS", "zhTW"]
    """))
    return str(p)


_EN_CARDS = [{"id": "BG_HERO_001", "dbfId": 1, "name": "Sneed", "type": "HERO", "set": "BATTLEGROUNDS"}]
_ZH_CARDS = [{"id": "BG_HERO_001", "dbfId": 1, "name": "斯尼德", "type": "HERO", "set": "BATTLEGROUNDS"}]


def _locale_client(monkeypatch, fail_locale=None):
    """Patch cli.httpx.Client with a MockTransport; returns the list of fetched locales
    (so a test can prove a locale was NOT requested)."""
    real_client = httpx.Client
    requested = []

    def handler(req):
        loc = req.url.path.rsplit("/", 1)[-1].replace(".json", "")
        requested.append(loc)
        if loc == fail_locale:
            return httpx.Response(500)
        body = _ZH_CARDS if loc == "zhTW" else _EN_CARDS
        return httpx.Response(200, content=json.dumps(body).encode())

    monkeypatch.setattr(cli.httpx, "Client",
                        lambda *a, **k: real_client(transport=httpx.MockTransport(handler)))
    return requested


def test_ordered_locales_default_first_dedupe():
    assert cli._ordered_locales("enUS", ["zhTW", "enUS", "zhTW"]) == ["enUS", "zhTW"]
    assert cli._ordered_locales("enUS", ["zhTW"]) == ["enUS", "zhTW"]   # default absent from locales
    assert cli._ordered_locales("enUS", []) == ["enUS"]


def test_sync_entities_loads_both_locales(tmp_path, monkeypatch):
    dbp = str(tmp_path / "t.db")
    _locale_client(monkeypatch)
    args = cli.build_parser().parse_args(["--db", dbp, "--sources", _sync_sources(tmp_path), "sync-entities"])
    args.func(args)
    conn = db.connect(dbp)
    rows = {r["locale"]: r["name"] for r in
            conn.execute("SELECT locale, name FROM entity_name").fetchall()}
    assert rows == {"enUS": "Sneed", "zhTW": "斯尼德"}
    assert conn.execute("SELECT name FROM entity WHERE card_id='BG_HERO_001'").fetchone()["name"] == "Sneed"


def test_sync_entities_zhTW_http_failure_keeps_enus_and_exits_nonzero(tmp_path, monkeypatch):
    dbp = str(tmp_path / "t.db")
    _locale_client(monkeypatch, fail_locale="zhTW")
    args = cli.build_parser().parse_args(["--db", dbp, "--sources", _sync_sources(tmp_path), "sync-entities"])
    with pytest.raises(SystemExit) as ei:
        args.func(args)
    assert ei.value.code == 1
    conn = db.connect(dbp)
    locs = {r["locale"] for r in conn.execute("SELECT locale FROM entity_name").fetchall()}
    assert locs == {"enUS"}                            # enUS committed, zhTW never written


def test_sync_entities_rolls_back_locale_on_midtxn_error(tmp_path, monkeypatch):
    # Prove the per-locale BEGIN IMMEDIATE rollback: zhTW writes a row, then raises INSIDE
    # the transaction. That partial write must be rolled back; enUS stays committed.
    dbp = str(tmp_path / "t.db")
    _locale_client(monkeypatch)
    real_sync = entities.sync_entities

    def flaky(conn, cards, locale, default_locale, now, known_ids=None):
        if locale == "zhTW":
            eid = next(iter(known_ids.values()))
            conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key) "
                         "VALUES (?,?,?,?)", (eid, "zhTW", "斯尼德", "斯尼德"))
            raise RuntimeError("boom mid-transaction")
        return real_sync(conn, cards, locale, default_locale, now, known_ids)

    monkeypatch.setattr(cli.entities, "sync_entities", flaky)
    args = cli.build_parser().parse_args(["--db", dbp, "--sources", _sync_sources(tmp_path), "sync-entities"])
    with pytest.raises(SystemExit) as ei:
        args.func(args)
    assert ei.value.code == 1
    conn = db.connect(dbp)
    locs = {r["locale"] for r in conn.execute("SELECT locale FROM entity_name").fetchall()}
    assert locs == {"enUS"}                            # the zhTW row written before boom was rolled back


def test_sync_entities_default_failure_skips_nondefault(tmp_path, monkeypatch):
    dbp = str(tmp_path / "t.db")
    requested = _locale_client(monkeypatch, fail_locale="enUS")
    args = cli.build_parser().parse_args(["--db", dbp, "--sources", _sync_sources(tmp_path), "sync-entities"])
    with pytest.raises(SystemExit) as ei:
        args.func(args)
    assert ei.value.code == 1
    assert requested == ["enUS"]                       # zhTW never fetched (loop broke on default failure)
    conn = db.connect(dbp)
    assert conn.execute("SELECT COUNT(*) FROM entity_name").fetchone()[0] == 0
