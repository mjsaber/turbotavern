"""Load sources.yaml -> list[FetchTask]."""
from __future__ import annotations
import yaml

from .models import FetchTask


def _read(path: str) -> dict:
    with open(path) as fh:
        return yaml.safe_load(fh)


def load_fetch_tasks(path: str) -> list[FetchTask]:
    fs = _read(path)["firestone"]
    tasks: list[FetchTask] = []
    for period in fs["periods"]:
        # heroes: one URL per (mmr, period)
        for mmr in fs["brackets"]:
            tasks.append(FetchTask("firestone", "hero",
                                   fs["hero_url"].format(mmr=mmr, period=period), period, mmr))
        # trinkets: one URL per period (mmr expanded at normalize time)
        tasks.append(FetchTask("firestone", "trinket",
                               fs["trinket_url"].format(period=period), period, None))
    return tasks


def hsjson_locale_config(path: str) -> tuple[str, str, list[str]]:
    h = _read(path)["hsjson"]
    return h["cards_url_template"], h["default_locale"], list(h["locales"])


def hsjson_cards_url(path: str) -> str:
    # 临时 shim：旧 cmd_sync_entities 仍调用它；Stage 4 改造 cli 后删除。
    template, default_locale, _ = hsjson_locale_config(path)
    return template.format(locale=default_locale)
