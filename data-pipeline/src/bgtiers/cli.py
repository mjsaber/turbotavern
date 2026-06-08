"""CLI entrypoint: init-db | sync-entities | fetch-stats. Wiring only."""
from __future__ import annotations
import argparse
import datetime as dt
import json
import sys

import httpx

from . import db, config, fetch, normalize, entities, load, card_renders
from .runlock import run_lock, AlreadyRunning


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def cmd_init_db(args):
    db.init_db(db.connect(args.db))
    print(f"initialized {args.db}")


def _ordered_locales(default_locale, locales):
    """default 永远第一个，整体保序去重。"""
    seen, out = set(), []
    for loc in [default_locale, *locales]:
        if loc not in seen:
            seen.add(loc)
            out.append(loc)
    return out


def cmd_sync_entities(args):
    conn = db.connect(args.db)
    db.init_db(conn)
    template, default_locale, locales = config.hsjson_locale_config(args.sources)
    failures = 0
    known_ids = None
    total = 0
    with httpx.Client(timeout=60) as client:
        for locale in _ordered_locales(default_locale, locales):
            try:
                cards = client.get(template.format(locale=locale)).raise_for_status().json()
                conn.execute("BEGIN IMMEDIATE")
                try:
                    n, synced = entities.sync_entities(conn, cards, locale, default_locale,
                                                        _now(), known_ids)
                    conn.execute("COMMIT")
                except Exception:
                    conn.execute("ROLLBACK")
                    raise
                total += n
                if locale == default_locale:
                    known_ids = synced                 # 提交后固化，供非 default 用
                print(f"synced {locale}: {n} names")
            except Exception as exc:
                failures += 1
                print(f"sync {locale}: FAILED {exc}", file=sys.stderr)
                if locale == default_locale:
                    break                              # 身份未建立，非 default 无法继续
    print(f"synced {total} entity-names total")
    if failures:
        sys.exit(1)


def cmd_fetch_stats(args):
    try:
        with run_lock(args.lock):
            _do_fetch_stats(args)
    except AlreadyRunning as exc:
        print(f"skipped: {exc}", file=sys.stderr)
        sys.exit(2)


def _do_fetch_stats(args):
    conn = db.connect(args.db)
    db.init_db(conn)
    failures = 0
    with httpx.Client(timeout=60) as client:
        for task in config.load_fetch_tasks(args.sources):
            fs = conn.execute(
                "SELECT etag, last_modified FROM fetch_state WHERE source=? AND raw_url=?",
                (task.source, task.raw_url),
            ).fetchone()
            prev_etag = fs["etag"] if fs else None
            prev_lm = fs["last_modified"] if fs else None
            try:
                res = fetch.fetch_url(client, task.raw_url, prev_etag, prev_lm)
                if res.status == 304:
                    load.load_url(conn, source=task.source, entity_type=task.entity_type,
                                  raw_url=task.raw_url, time_period=task.time_period, status=304,
                                  body=None, etag=res.etag, last_modified=res.last_modified,
                                  feeds_by_bracket=None, now=_now())
                    print(f"{task.entity_type} {task.raw_url}: 304")
                    continue
                print(f"{task.entity_type} {task.raw_url}: 200 last-modified={res.last_modified}")
                raw = json.loads(res.body)
                feeds = normalize.normalize_firestone(raw, task.entity_type, task.url_mmr)  # may raise
                out = load.load_url(conn, source=task.source, entity_type=task.entity_type,
                                    raw_url=task.raw_url, time_period=task.time_period, status=200,
                                    body=res.body, etag=res.etag, last_modified=res.last_modified,
                                    feeds_by_bracket=feeds, now=_now())
                print(f"  -> {[f'{b}:{o.value}' for b, o in out.items()]}")
            except Exception as exc:
                failures += 1
                print(f"{task.entity_type} {task.raw_url}: FAILED {exc}", file=sys.stderr)
    if failures:
        sys.exit(1)


def cmd_fetch_card_renders(args):
    """Layer-2 OCR corpus: download {cardId}__{locale}.png renders for the shipped heroes."""
    art = config.hsjson_art_template(args.sources)
    _, default_locale, locales = config.hsjson_locale_config(args.sources)
    card_ids = card_renders.read_card_ids(args.asset)
    with httpx.Client(timeout=30, follow_redirects=True) as client:
        res = card_renders.fetch_card_renders(
            client, art, card_ids, _ordered_locales(default_locale, locales), args.out)
    print(f"renders: downloaded={len(res['downloaded'])} skipped={len(res['skipped'])} "
          f"missing={len(res['missing'])} -> {args.out}")


def build_parser():
    p = argparse.ArgumentParser(prog="bgtiers")
    p.add_argument("--db", default="bgtiers.db")
    p.add_argument("--sources", default="sources.yaml")
    sub = p.add_subparsers(required=True)
    sub.add_parser("init-db").set_defaults(func=cmd_init_db)
    sub.add_parser("sync-entities").set_defaults(func=cmd_sync_entities)
    fp = sub.add_parser("fetch-stats")
    fp.add_argument("--lock", default=".fetch.lock")
    fp.set_defaults(func=cmd_fetch_stats)
    rp = sub.add_parser("fetch-card-renders")
    rp.add_argument("--asset", default="../android/overlay-app/app/src/main/assets/herotier_v1.json")
    rp.add_argument("--out", default="build/card-renders")
    rp.set_defaults(func=cmd_fetch_card_renders)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
