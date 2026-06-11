"""Export the bundled trinket-tier asset (mirror of export_tiers for entity_type='trinket').

Trinkets come in two classes — 'lesser' and 'greater' — that are offered on SEPARATE turns, so a
single global S/A/B/C across both would be misleading (a strong lesser and a strong greater are not
competing). Tiers are therefore computed by percentile WITHIN each class. The Android side ranks the
2-3 trinkets actually offered against each other; cardId+trinketClass keeps each entry unique because
a lesser and a greater can share a localized base name. Read-only over the SQLite.
"""
from __future__ import annotations
import datetime as dt
import json

# Same percentile cut points as heroes; applied independently per trinket class.
_CUTS = (("S", 0.12), ("A", 0.35), ("B", 0.68), ("C", 1.01))


def _tier(p: float) -> str:
    for name, hi in _CUTS:
        if p < hi:
            return name
    return "C"


def build(conn, *, generated_at: str | None = None) -> dict:
    distinct = [tuple(r) for r in conn.execute(
        "SELECT DISTINCT mode, region FROM v_latest_stats WHERE entity_type='trinket'"
        " AND source='firestone' AND mmr_bracket='100' AND time_period='last-patch'"
        " ORDER BY mode, region").fetchall()]
    if not distinct:
        raise ValueError("no trinket rows for bracket=100 last-patch")
    if distinct != [("solo", "global")]:
        raise ValueError(f"expected single (solo,global) mode/region, got {distinct}")
    rows = conn.execute(
        "SELECT e.entity_id AS eid, v.card_id AS card_id, v.name AS en_name,"
        "       v.trinket_class AS tclass, v.avg_placement AS avg"
        " FROM v_latest_stats v"
        " JOIN entity e ON e.card_id=v.card_id AND e.entity_type=v.entity_type"
        " WHERE v.entity_type='trinket' AND v.source='firestone' AND v.mmr_bracket='100'"
        "   AND v.time_period='last-patch' AND v.mode='solo' AND v.region='global'"
        " ORDER BY v.avg_placement ASC, v.card_id ASC").fetchall()
    if not rows:                     # defensive: empty-result already caught by the distinct guard
        raise ValueError("no trinket rows for bracket=100 last-patch")

    # All localized names per entity: entity_id -> {locale: name}. enUS is the default-locale identity
    # on the stats row; other locales flow through entity_name as-is. No enUS-fallback for a missing
    # locale (there is no text to OCR-match in that locale).
    names_by_eid: dict[int, dict[str, str]] = {}
    for r in conn.execute("SELECT entity_id, locale, name FROM entity_name"):
        if r["name"]:
            names_by_eid.setdefault(r["entity_id"], {})[r["locale"]] = r["name"]

    # Partition by class FIRST, then percentile within each (rows already sorted by avg, card_id).
    by_class: dict[str, list] = {}
    for r in rows:
        cls = r["tclass"]
        if cls not in ("lesser", "greater"):
            raise ValueError(f"trinket {r['card_id']} has invalid trinket_class {cls!r}")
        if r["en_name"] is None:
            raise ValueError(f"trinket {r['card_id']} missing enUS name")
        by_class.setdefault(cls, []).append(r)

    trinkets = []
    for cls in ("lesser", "greater"):
        group = by_class.get(cls, [])
        n = len(group)
        for i, r in enumerate(group):
            p = (i + 0.5) / n
            names = {"enUS": r["en_name"]}
            for loc, name in sorted(names_by_eid.get(r["eid"], {}).items()):
                if loc != "enUS":
                    names[loc] = name
            trinkets.append({"cardId": r["card_id"], "trinketClass": cls, "tier": _tier(p), "names": names})

    return {"schemaVersion": 1, "bracket": "100", "period": "last-patch",
            "generatedAt": generated_at or dt.datetime.now(dt.timezone.utc)
                .strftime("%Y-%m-%dT%H:%M:%SZ"),
            "trinkets": trinkets}


def main(argv=None):
    import argparse
    from . import db
    ap = argparse.ArgumentParser(prog="export-trinkets")
    ap.add_argument("--db", default="bgtiers.db")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    data = build(db.connect(a.db))
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"wrote {len(data['trinkets'])} trinkets -> {a.out}")


if __name__ == "__main__":
    main()
