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
