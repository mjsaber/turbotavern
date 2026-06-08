"""Download HearthstoneJSON card renders per locale for the Layer-2 OCR corpus.

Plain client.get + status checks (404 is expected for the ~15 newer BG heroes with no collectible
render — recorded in the manifest, not an error). Knows nothing about the DB; testable with
httpx.MockTransport.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

import httpx


def read_card_ids(asset_path: str) -> list[str]:
    """Hero cardIds from the shipped herotier_v1.json (the same heroes the app matches)."""
    data = json.loads(Path(asset_path).read_text(encoding="utf-8"))
    return [h["cardId"] for h in data["heroes"]]


def fetch_card_renders(
    client: httpx.Client,
    art_template: str,
    card_ids: list[str],
    locales: list[str],
    out_dir: str,
    sleep: float = 0.05,
) -> dict:
    """Download {cardId}__{locale}.png into out_dir. Idempotent (skips existing). 404/other ->
    missing.json manifest. Returns {downloaded, skipped, missing}."""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    downloaded: list[str] = []
    skipped: list[str] = []
    missing: list[dict] = []
    for cid in card_ids:
        for loc in locales:
            dest = out / f"{cid}__{loc}.png"
            if dest.exists():
                skipped.append(dest.name)
                continue
            url = art_template.format(locale=loc, cardId=cid)
            try:
                resp = client.get(url)
            except httpx.HTTPError as exc:
                missing.append({"cardId": cid, "locale": loc, "error": str(exc)})
                continue
            if resp.status_code == 200:
                dest.write_bytes(resp.content)
                downloaded.append(dest.name)
            else:
                missing.append({"cardId": cid, "locale": loc, "status": resp.status_code})
            if sleep:
                time.sleep(sleep)
    (out / "missing.json").write_text(
        json.dumps(missing, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"downloaded": downloaded, "skipped": skipped, "missing": missing}
