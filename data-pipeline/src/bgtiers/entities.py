"""Identity sync (HearthstoneJSON) + stub creation for unknown card_ids."""
from __future__ import annotations
import sqlite3

_IMG_TMPL = "https://art.hearthstonejson.com/v1/256x/{card_id}.jpg"
_SPELLSCHOOL_TO_CLASS = {"LESSER_TRINKET": "lesser", "GREATER_TRINKET": "greater"}


def _is_bg_hero(card: dict) -> bool:
    return card.get("type") == "HERO" and "BATTLEGROUNDS" in str(card.get("set", "")).upper()


def _is_bg_trinket(card: dict) -> bool:
    return card.get("type") == "BATTLEGROUND_TRINKET"


def sync_entities(conn: sqlite3.Connection, cards: list[dict], now: str) -> int:
    """Upsert BG heroes + trinkets from HearthstoneJSON. Trinket lesser/greater comes
    from spellSchool. Returns rows touched."""
    touched = 0
    for card in cards:
        if _is_bg_hero(card):
            etype, tclass = "hero", None
        elif _is_bg_trinket(card):
            etype = "trinket"
            tclass = _SPELLSCHOOL_TO_CLASS.get(card.get("spellSchool"))
        else:
            continue
        cid = card["id"]
        conn.execute(
            """
            INSERT INTO entity (entity_type, card_id, dbf_id, name, image_url,
                                trinket_class, first_seen_at, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (entity_type, card_id) DO UPDATE SET
                dbf_id        = excluded.dbf_id,
                name          = excluded.name,
                image_url     = COALESCE(entity.image_url, excluded.image_url),
                trinket_class = COALESCE(excluded.trinket_class, entity.trinket_class),
                last_seen_at  = excluded.last_seen_at
            """,
            (etype, cid, card.get("dbfId"), card.get("name"),
             _IMG_TMPL.format(card_id=cid), tclass, now, now),
        )
        touched += 1
    return touched


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
