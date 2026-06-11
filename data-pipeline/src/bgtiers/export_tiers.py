"""Export bundled hero-tier asset (spec §10). Read-only over the SQLite."""
from __future__ import annotations
import datetime as dt
import json

_CUTS = (("S", 0.12), ("A", 0.35), ("B", 0.68), ("C", 1.01))

# Drop entities with too few games to tier honestly (a brand-new or rarely-seen hero would otherwise
# get an avg-placement-of-a-handful-of-games tier). Real heroes have thousands of games.
_MIN_DATA_POINTS = 100


def _tier(p: float) -> str:
    for name, hi in _CUTS:
        if p < hi:
            return name
    return "C"


def build(conn, *, generated_at: str | None = None) -> dict:
    distinct = [tuple(r) for r in conn.execute(
        "SELECT DISTINCT mode, region FROM v_latest_stats WHERE entity_type='hero'"
        " AND source='firestone' AND mmr_bracket='100' AND time_period='last-patch'"
        " ORDER BY mode, region").fetchall()]
    if not distinct:
        raise ValueError("no hero rows for bracket=100 last-patch")
    if distinct != [("solo", "global")]:
        raise ValueError(f"expected single (solo,global) mode/region, got {distinct}")
    rows = conn.execute(
        "SELECT e.entity_id AS eid, v.card_id AS card_id, v.name AS en_name,"
        "       v.avg_placement AS avg"
        " FROM v_latest_stats v"
        " JOIN entity e ON e.card_id=v.card_id AND e.entity_type=v.entity_type"
        " WHERE v.entity_type='hero' AND v.source='firestone' AND v.mmr_bracket='100'"
        "   AND v.time_period='last-patch' AND v.mode='solo' AND v.region='global'"
        "   AND v.data_points >= ?"
        " ORDER BY v.avg_placement ASC, v.card_id ASC", (_MIN_DATA_POINTS,)).fetchall()
    n = len(rows)
    if n == 0:                       # defensive: empty-result already caught by the distinct guard
        raise ValueError("no hero rows for bracket=100 last-patch")
    # All localized names per entity: entity_id -> {locale: name}. enUS comes from entity.name
    # (the default-locale identity); other locales (zhTW, zhCN, ...) flow through as-is. A locale
    # with no name is simply absent (no enUS-fallback — there is no text to match in that locale).
    names_by_eid: dict[int, dict[str, str]] = {}
    for r in conn.execute("SELECT entity_id, locale, name FROM entity_name"):
        if r["name"]:
            names_by_eid.setdefault(r["entity_id"], {})[r["locale"]] = r["name"]
    heroes = []
    for i, r in enumerate(rows):
        if r["en_name"] is None:     # entity.name is nullable; a hero with no enUS name is a data bug
            raise ValueError(f"hero {r['card_id']} missing enUS name")
        p = (i + 0.5) / n
        names = {"enUS": r["en_name"]}
        for loc, name in sorted(names_by_eid.get(r["eid"], {}).items()):
            if loc != "enUS":
                names[loc] = name
        heroes.append({"cardId": r["card_id"], "tier": _tier(p), "names": names})
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
