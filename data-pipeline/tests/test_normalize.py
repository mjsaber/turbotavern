import json
import pathlib
import pytest

from bgtiers import normalize

FIX = pathlib.Path(__file__).parent / "fixtures"


def _load(name):
    return json.loads((FIX / name).read_text())


def test_hero_normalizes_to_single_bracket():
    raw = _load("firestone_heroes.json")
    feeds = normalize.normalize_firestone(raw, entity_type="hero", url_mmr="100")
    assert set(feeds) == {"100"}                      # one dimension = the URL's mmr
    feed = feeds["100"]
    assert len(feed.rows) == len(raw["heroStats"])
    first = raw["heroStats"][0]
    row = {r.card_id: r for r in feed.rows}[first["heroCardId"]]
    assert row.avg_placement == first["averagePosition"]
    assert row.data_points == first["dataPoints"]
    assert "averagePosition" in feed.schema_fingerprint


def test_trinket_expands_into_five_brackets():
    raw = _load("firestone_trinkets.json")
    feeds = normalize.normalize_firestone(raw, entity_type="trinket", url_mmr=None)
    assert set(feeds) == {"100", "50", "25", "10", "1"}
    entry = raw["trinketStats"][0]
    cid = entry["trinketCardId"]
    by_mmr = {m["mmr"]: m for m in entry["averagePlacementAtMmr"]}
    # bracket '10' row's avg_placement == that entry's mmr-10 placement
    row10 = {r.card_id: r for r in feeds["10"].rows}[cid]
    assert row10.avg_placement == by_mmr[10]["placement"]
    assert row10.data_points == by_mmr[10]["dataPoints"]


def test_trinket_empty_bracket_is_dropped_not_emitted():
    # T1 only has mmr-100 data -> only the '100' dimension is produced, not an empty '10'
    raw = {"trinketStats": [
        {"trinketCardId": "T1", "dataPoints": 100, "pickRate": 0.1, "averagePlacement": 4.0,
         "averagePlacementAtMmr": [{"mmr": 100, "dataPoints": 100, "placement": 4.0}],
         "pickRateAtMmr": [{"mmr": 100, "dataPoints": 100, "pickRate": 0.1}]},
    ]}
    feeds = normalize.normalize_firestone(raw, entity_type="trinket", url_mmr=None)
    assert set(feeds) == {"100"}                      # empty brackets dropped
    assert [r.card_id for r in feeds["100"].rows] == ["T1"]


def test_validate_rejects_empty_hero_feed():
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone({"heroStats": []}, entity_type="hero", url_mmr="100")


def test_validate_rejects_empty_trinket_url():
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone({"trinketStats": []}, entity_type="trinket", url_mmr=None)


def test_validate_rejects_out_of_range_placement():
    bad = {"heroStats": [{"heroCardId": "X", "dataPoints": 10, "averagePosition": 9.9}]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero", url_mmr="100")


def test_validate_rejects_duplicate_card_ids():
    bad = {"heroStats": [
        {"heroCardId": "X", "dataPoints": 10, "averagePosition": 4.0},
        {"heroCardId": "X", "dataPoints": 11, "averagePosition": 4.1},
    ]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero", url_mmr="100")


def test_validate_rejects_missing_core_field():
    bad = {"heroStats": [{"heroCardId": "X", "dataPoints": 10}]}  # no averagePosition
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero", url_mmr="100")


def test_validate_rejects_non_numeric_placement():
    bad = {"heroStats": [{"heroCardId": "X", "dataPoints": 10, "averagePosition": "low"}]}
    with pytest.raises(normalize.ValidationError):
        normalize.normalize_firestone(bad, entity_type="hero", url_mmr="100")
