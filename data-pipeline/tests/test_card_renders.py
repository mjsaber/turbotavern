import json

import httpx

from bgtiers import card_renders


def _client(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


ART = "https://art/{locale}/{cardId}.png"


def test_downloads_writes_files_and_records_404s(tmp_path):
    seen = []

    def handler(req):
        seen.append(str(req.url))
        return httpx.Response(404) if "HERO_X" in str(req.url) else httpx.Response(200, content=b"PNGDATA")

    res = card_renders.fetch_card_renders(
        _client(handler), ART, ["HERO_A", "HERO_X"], ["enUS", "zhTW"], str(tmp_path), sleep=0)

    assert "https://art/enUS/HERO_A.png" in seen and "https://art/zhTW/HERO_X.png" in seen
    assert (tmp_path / "HERO_A__enUS.png").read_bytes() == b"PNGDATA"
    assert (tmp_path / "HERO_A__zhTW.png").exists()
    assert not (tmp_path / "HERO_X__enUS.png").exists()
    assert len(res["downloaded"]) == 2 and len(res["missing"]) == 2

    manifest = json.loads((tmp_path / "missing.json").read_text(encoding="utf-8"))
    assert {m["cardId"] for m in manifest} == {"HERO_X"}
    assert all(m["status"] == 404 for m in manifest)


def test_idempotent_skips_existing(tmp_path):
    (tmp_path / "HERO_A__enUS.png").write_bytes(b"old")
    calls = {"n": 0}

    def handler(req):
        calls["n"] += 1
        return httpx.Response(200, content=b"new")

    res = card_renders.fetch_card_renders(
        _client(handler), ART, ["HERO_A"], ["enUS"], str(tmp_path), sleep=0)

    assert calls["n"] == 0  # existing file -> not re-fetched
    assert res["skipped"] == ["HERO_A__enUS.png"]
    assert (tmp_path / "HERO_A__enUS.png").read_bytes() == b"old"


def test_read_card_ids(tmp_path):
    asset = tmp_path / "a.json"
    asset.write_text(json.dumps({"heroes": [{"cardId": "X"}, {"cardId": "Y"}]}), encoding="utf-8")
    assert card_renders.read_card_ids(str(asset)) == ["X", "Y"]
