"""Firestone JSON -> dict[mmr_bracket, NormalizedFeed] + validation (spec §4.1)."""
from __future__ import annotations

from .models import NormalizedRow, NormalizedFeed

BRACKETS = ("100", "50", "25", "10", "1")
_MAX_ROWS = 5000


class ValidationError(Exception):
    pass


def _schema_fingerprint(raw: dict, array_field: str) -> list[str]:
    keys = set(raw.keys())
    for row in raw.get(array_field, []):
        keys.update(row.keys())
    return sorted(keys)


def _num(value, card_id, field_name):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError(f"{field_name} not numeric for {card_id}: {value!r}")
    return value


def _mk_row(card_id, avg, dp, pick_rate, dist, extra) -> NormalizedRow:
    avg = float(_num(avg, card_id, "avg"))
    if not (1.0 <= avg <= 8.0):
        raise ValidationError(f"avg_placement out of range for {card_id}: {avg}")
    dp_n = _num(dp, card_id, "data_points")
    if isinstance(dp_n, float) and not dp_n.is_integer():
        raise ValidationError(f"data_points not integral for {card_id}: {dp_n}")
    dp_i = int(dp_n)
    if dp_i < 0:
        raise ValidationError(f"negative data_points for {card_id}: {dp_i}")
    if dist is not None:
        # Real feed stores 8 objects ({rank, percentage, totalMatches}), not bare numbers.
        # Validate it's a length-8 list (BG has 8 ranks); store the objects verbatim.
        if not isinstance(dist, list) or len(dist) != 8:
            raise ValidationError(f"bad placement distribution for {card_id}")
    return NormalizedRow(card_id, avg, dp_i, pick_rate, dist, extra)


def _build_feed(rows: list[NormalizedRow], raw: dict, fingerprint: list[str]) -> NormalizedFeed:
    seen = set()
    for r in rows:
        if r.card_id in seen:
            raise ValidationError(f"duplicate card_id in dimension: {r.card_id}")
        seen.add(r.card_id)
    return NormalizedFeed(rows=rows, patch=raw.get("patch"), schema_fingerprint=fingerprint)


def _normalize_hero(raw: dict, url_mmr: str) -> dict[str, NormalizedFeed]:
    stats = raw.get("heroStats", [])
    if not stats:
        raise ValidationError("empty hero feed")
    if len(stats) > _MAX_ROWS:
        raise ValidationError(f"implausible row count: {len(stats)}")
    fp = _schema_fingerprint(raw, "heroStats")
    rows = []
    for it in stats:
        cid = it.get("heroCardId")
        if not cid:
            raise ValidationError(f"hero row missing heroCardId: {it!r}")
        if "averagePosition" not in it or "dataPoints" not in it:
            raise ValidationError(f"hero row missing core field: {cid}")
        offered, picked = it.get("totalOffered"), it.get("totalPicked")
        pick_rate = (picked / offered) if (offered and picked is not None) else None
        extra = {k: v for k, v in it.items()
                 if k not in ("heroCardId", "averagePosition", "dataPoints",
                              "placementDistribution", "totalOffered", "totalPicked")}
        rows.append(_mk_row(cid, it["averagePosition"], it["dataPoints"], pick_rate,
                            it.get("placementDistribution"), extra))
    return {url_mmr: _build_feed(rows, raw, fp)}


def _normalize_trinket(raw: dict) -> dict[str, NormalizedFeed]:
    stats = raw.get("trinketStats", [])
    if not stats:
        raise ValidationError("empty trinket feed")
    if len(stats) > _MAX_ROWS:
        raise ValidationError(f"implausible row count: {len(stats)}")
    fp = _schema_fingerprint(raw, "trinketStats")
    per_bracket: dict[str, list[NormalizedRow]] = {b: [] for b in BRACKETS}
    for it in stats:
        cid = it.get("trinketCardId")
        if not cid:
            raise ValidationError(f"trinket row missing trinketCardId: {it!r}")
        pr_by_mmr = {p["mmr"]: p.get("pickRate") for p in it.get("pickRateAtMmr", [])}
        extra = {k: v for k, v in it.items()
                 if k not in ("trinketCardId", "averagePlacement", "dataPoints", "pickRate",
                              "averagePlacementAtMmr", "pickRateAtMmr")}
        for ap in it.get("averagePlacementAtMmr", []):
            bracket = str(ap.get("mmr"))
            if bracket not in per_bracket:
                continue
            if "placement" not in ap or "dataPoints" not in ap:
                raise ValidationError(f"trinket {cid} mmr {bracket} missing core field")
            per_bracket[bracket].append(
                _mk_row(cid, ap["placement"], ap["dataPoints"], pr_by_mmr.get(ap.get("mmr")),
                        None, dict(extra)))
    # URL-level feed (trinketStats) is validated non-empty above. A per-bracket expansion
    # may legitimately be empty (e.g. low-sample top-1% bracket) -> drop it, don't emit an
    # empty dimension (load would otherwise create a snapshot with zero stat rows).
    return {b: _build_feed(rows, raw, fp) for b, rows in per_bracket.items() if rows}


def normalize_firestone(raw: dict, entity_type: str, url_mmr: str | None) -> dict[str, NormalizedFeed]:
    if entity_type == "hero":
        assert url_mmr is not None, "hero normalize needs the URL's mmr bracket"
        return _normalize_hero(raw, url_mmr)
    if entity_type == "trinket":
        return _normalize_trinket(raw)
    raise ValidationError(f"unknown entity_type: {entity_type}")
