"""HTTP fetch with conditional requests + retry. Knows nothing about the DB."""
from __future__ import annotations
import time
import httpx

from .models import FetchResult


def fetch_url(client: httpx.Client, raw_url: str, prev_etag: str | None,
              prev_last_modified: str | None, max_retries: int = 3,
              backoff: float = 0.5) -> FetchResult:
    headers = {}
    if prev_etag:
        headers["If-None-Match"] = prev_etag
    if prev_last_modified:
        headers["If-Modified-Since"] = prev_last_modified

    last_exc = None
    for attempt in range(max_retries):
        try:
            resp = client.get(raw_url, headers=headers)
            if resp.status_code == 304:
                return FetchResult(raw_url, 304, None, prev_etag, prev_last_modified)
            if resp.status_code >= 500:
                raise httpx.HTTPStatusError("server error", request=resp.request, response=resp)
            resp.raise_for_status()
            return FetchResult(raw_url, 200, resp.content,
                               resp.headers.get("ETag"), resp.headers.get("Last-Modified"))
        except httpx.HTTPError as exc:
            last_exc = exc
            if attempt < max_retries - 1 and backoff:
                time.sleep(backoff * (2 ** attempt))
    raise last_exc
