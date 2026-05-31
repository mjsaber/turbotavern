"""content_hash canonicalization contract — spec §5.6. Locked by tests."""
from __future__ import annotations
import hashlib
import json

from .models import NormalizedFeed

_FLOAT_NDIGITS = 6


def _canon(obj):
    """Floats -> fixed-decimal STRINGS so the hash is stable across implementations
    (4.1 -> '4.100000'), independent of float repr. ints/None untouched."""
    if isinstance(obj, bool):
        return obj
    if isinstance(obj, float):
        return f"{obj:.{_FLOAT_NDIGITS}f}"
    if isinstance(obj, dict):
        return {k: _canon(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_canon(v) for v in obj]
    return obj


def content_hash(feed: NormalizedFeed) -> str:
    stats = sorted(
        ({
            "card_id": r.card_id,
            "avg_placement": r.avg_placement,
            "data_points": r.data_points,
            "pick_rate": r.pick_rate,
            "placement_distribution": r.placement_distribution,
            "extra_json": r.extra_json,
        } for r in feed.rows),
        key=lambda r: r["card_id"],
    )
    payload = {
        "stats": _canon(stats),
        "patch": feed.patch,
        "schema_fingerprint": sorted(feed.schema_fingerprint),
    }
    serialized = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()
