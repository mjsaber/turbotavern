"""Export bundled hero-tier asset (spec §10). Read-only over the SQLite."""
from __future__ import annotations
import datetime as dt
import json

_CUTS = (("S", 0.12), ("A", 0.35), ("B", 0.68), ("C", 1.01))


def _tier(p: float) -> str:
    for name, hi in _CUTS:
        if p < hi:
            return name
    return "C"


def build(conn, *, generated_at: str | None = None) -> dict:
    distinct = [tuple(r) for r in conn.execute(
        "SELECT DISTINCT mode, region FROM v_latest_stats WHERE entity_type='hero'"
        " AND source='firestone' AND mmr_bracket='100' AND time_period='last-patch'").fetchall()]
    if distinct != [("solo", "global")]:
        raise ValueError(f"expected single (solo,global) mode/region, got {distinct}")
    rows = conn.execute(
        "SELECT v.card_id AS card_id, v.name AS en_name, en.name AS zh_name,"
        "       v.avg_placement AS avg"
        " FROM v_latest_stats v"
        " JOIN entity e ON e.card_id=v.card_id AND e.entity_type=v.entity_type"
        " LEFT JOIN entity_name en ON en.entity_id=e.entity_id AND en.locale='zhTW'"
        " WHERE v.entity_type='hero' AND v.source='firestone' AND v.mmr_bracket='100'"
        "   AND v.time_period='last-patch' AND v.mode='solo' AND v.region='global'"
        " ORDER BY v.avg_placement ASC, v.card_id ASC").fetchall()
    n = len(rows)
    if n == 0:
        raise ValueError("no hero rows for bracket=100 last-patch")
    heroes = []
    for i, r in enumerate(rows):
        p = (i + 0.5) / n
        zh = r["zh_name"] if r["zh_name"] is not None else r["en_name"]
        heroes.append({"cardId": r["card_id"], "tier": _tier(p),
                       "names": {"zhTW": zh, "enUS": r["en_name"]}})
    return {"schemaVersion": 1, "bracket": "100", "period": "last-patch",
            "generatedAt": generated_at or dt.datetime.now(dt.timezone.utc)
                .strftime("%Y-%m-%dT%H:%M:%SZ"),
            "heroes": heroes}


def main(argv=None):
    import argparse
    from . import db
    ap = argparse.ArgumentParser(prog="export-tiers")
    ap.add_argument("--db", default="bgtiers.db")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    data = build(db.connect(a.db))
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"wrote {len(data['heroes'])} heroes -> {a.out}")


if __name__ == "__main__":
    main()
