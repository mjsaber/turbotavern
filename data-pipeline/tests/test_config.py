import textwrap
from bgtiers import config


def _write(tmp_path, body):
    p = tmp_path / "sources.yaml"
    p.write_text(textwrap.dedent(body))
    return str(p)


def test_load_fetch_tasks_hero_per_mmr_trinket_per_period(tmp_path):
    path = _write(tmp_path, """
        firestone:
          hero_url: "http://h/{mmr}/{period}.json"
          trinket_url: "http://t/{period}.json"
          brackets: ["100", "10"]
          periods: ["last-patch"]
        hsjson:
          cards_url: "http://cards"
    """)
    tasks = config.load_fetch_tasks(path)
    heroes = [t for t in tasks if t.entity_type == "hero"]
    trinkets = [t for t in tasks if t.entity_type == "trinket"]
    assert len(heroes) == 2                            # 2 brackets x 1 period
    assert len(trinkets) == 1                          # 1 period (no mmr in URL)
    assert {t.url_mmr for t in heroes} == {"100", "10"}
    assert trinkets[0].url_mmr is None
    assert trinkets[0].raw_url == "http://t/last-patch.json"
    assert {t.raw_url for t in heroes} == {"http://h/100/last-patch.json", "http://h/10/last-patch.json"}


def test_hsjson_cards_url(tmp_path):
    path = _write(tmp_path, """
        firestone: {hero_url: "h", trinket_url: "t", brackets: ["100"], periods: ["last-patch"]}
        hsjson: {cards_url: "http://cards"}
    """)
    assert config.hsjson_cards_url(path) == "http://cards"
