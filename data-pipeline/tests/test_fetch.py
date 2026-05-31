import httpx
from bgtiers import fetch


def _client(handler):
    return httpx.Client(transport=httpx.MockTransport(handler))


def test_fetch_200_returns_body_and_validators():
    def handler(req):
        assert "If-None-Match" not in req.headers
        return httpx.Response(200, content=b'{"ok":1}', headers={"ETag": '"e1"', "Last-Modified": "lm1"})
    res = fetch.fetch_url(_client(handler), "http://x", prev_etag=None, prev_last_modified=None)
    assert res.status == 200 and res.body == b'{"ok":1}'
    assert res.etag == '"e1"' and res.last_modified == "lm1"


def test_fetch_sends_conditional_headers_and_handles_304():
    seen = {}
    def handler(req):
        seen["inm"] = req.headers.get("If-None-Match")
        seen["ims"] = req.headers.get("If-Modified-Since")
        return httpx.Response(304)
    res = fetch.fetch_url(_client(handler), "http://x", '"e1"', "lm1")
    assert res.status == 304 and res.body is None
    assert seen["inm"] == '"e1"' and seen["ims"] == "lm1"


def test_fetch_retries_then_succeeds():
    calls = {"n": 0}
    def handler(req):
        calls["n"] += 1
        return httpx.Response(503) if calls["n"] < 2 else httpx.Response(200, content=b'ok', headers={"ETag": '"e"'})
    res = fetch.fetch_url(_client(handler), "http://x", None, None, max_retries=3, backoff=0)
    assert res.status == 200 and calls["n"] == 2
