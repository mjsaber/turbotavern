"""Identity sync (HearthstoneJSON, per-locale) + stub creation for unknown card_ids."""
from __future__ import annotations
import sqlite3
import sys

from .localize import normalize_name_key

_IMG_TMPL = "https://art.hearthstonejson.com/v1/256x/{card_id}.jpg"
_SPELLSCHOOL_TO_CLASS = {"LESSER_TRINKET": "lesser", "GREATER_TRINKET": "greater"}


def _is_bg_hero(card: dict) -> bool:
    return card.get("type") == "HERO" and "BATTLEGROUNDS" in str(card.get("set", "")).upper()


def _is_bg_trinket(card: dict) -> bool:
    return card.get("type") == "BATTLEGROUND_TRINKET"


def _classify(card: dict):
    if _is_bg_hero(card):
        return "hero", None
    if _is_bg_trinket(card):
        return "trinket", _SPELLSCHOOL_TO_CLASS.get(card.get("spellSchool"))
    return None, None


def _upsert_identity(conn, etype, tclass, card, now) -> int:
    cid = card["id"]
    conn.execute(
        """
        INSERT INTO entity (entity_type, card_id, dbf_id, name, image_url,
                            trinket_class, first_seen_at, last_seen_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (entity_type, card_id) DO UPDATE SET
            dbf_id        = COALESCE(excluded.dbf_id, entity.dbf_id),
            name          = COALESCE(excluded.name, entity.name),
            image_url     = COALESCE(entity.image_url, excluded.image_url),
            trinket_class = COALESCE(excluded.trinket_class, entity.trinket_class),
            last_seen_at  = excluded.last_seen_at
        """,
        (etype, cid, card.get("dbfId"), card.get("name"),
         _IMG_TMPL.format(card_id=cid), tclass, now, now),
    )
    return conn.execute("SELECT entity_id FROM entity WHERE entity_type=? AND card_id=?",
                        (etype, cid)).fetchone()["entity_id"]


def _upsert_name(conn, entity_id: int, locale: str, raw_name) -> None:
    key = normalize_name_key(raw_name)
    if not key:                                        # blank after normalization -> reconcile
        conn.execute("DELETE FROM entity_name WHERE entity_id=? AND locale=?", (entity_id, locale))
        return
    conn.execute(
        """INSERT INTO entity_name (entity_id, locale, name, name_key) VALUES (?,?,?,?)
           ON CONFLICT (entity_id, locale) DO UPDATE SET
               name=excluded.name, name_key=excluded.name_key""",
        (entity_id, locale, raw_name, key),
    )


def sync_entities(conn: sqlite3.Connection, cards: list[dict], locale: str,
                  default_locale: str, now: str, known_ids: dict | None = None):
    """Upsert BG hero/trinket localized names for one locale.

    default locale: also upserts entity identity (entity.name = default name) and
    returns synced_ids {(entity_type, card_id): entity_id}.
    non-default locale: attaches names ONLY to entities in known_ids (the default
    run's seen-map) — never to stale/stub rows the current default run did not touch.
    Returns (touched, synced_ids)."""
    is_default = (locale == default_locale)
    synced: dict[tuple[str, str], int] = {}
    touched = 0
    for card in cards:
        etype, tclass = _classify(card)
        if etype is None:
            continue
        cid = card["id"]
        if is_default:
            eid = _upsert_identity(conn, etype, tclass, card, now)
            synced[(etype, cid)] = eid
        else:
            eid = (known_ids or {}).get((etype, cid))
            if eid is None:
                print(f"sync {locale}: skip {etype} {cid} (not in default-locale run)",
                      file=sys.stderr)
                continue
        _upsert_name(conn, eid, locale, card.get("name"))
        touched += 1
    return touched, synced


def ensure_entity(conn: sqlite3.Connection, entity_type: str, card_id: str, now: str) -> int:
    """Return entity_id for (entity_type, card_id); create a stub if unknown.
    trinket_class is NOT set here (it comes from sync_entities/HSJSON)."""
    row = conn.execute(
        "SELECT entity_id FROM entity WHERE entity_type=? AND card_id=?",
        (entity_type, card_id),
    ).fetchone()
    if row:
        conn.execute("UPDATE entity SET last_seen_at=? WHERE entity_id=?", (now, row["entity_id"]))
        return row["entity_id"]
    cur = conn.execute(
        "INSERT INTO entity (entity_type, card_id, first_seen_at, last_seen_at) VALUES (?,?,?,?)",
        (entity_type, card_id, now, now),
    )
    return cur.lastrowid
