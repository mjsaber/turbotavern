# BG 名称本地化（zhTW + enUS）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 BG 英雄 / 饰品的每个实体存多语言名称（繁体中文 `zhTW` + 英文 `enUS`）+ 归一化检索键，支持 name→entity 检索与 id→name 显示。

**Architecture:** 扩展现有 `data-pipeline/` 的 `sync-entities` 链路与 schema：新增 `entity_name` 侧表 + `localize.normalize_name_key` 纯函数；`sync_entities` 改为按 locale 调用并用 default-run seen-map 挂名；CLI 按 locale 顺序去重遍历，每 locale 一个 `BEGIN IMMEDIATE` 事务。不动 `fetch-stats` 统计抓取链路。

**Tech Stack:** Python 3.12+ / uv、stdlib `sqlite3` + `unicodedata`、`httpx`（测试用 `MockTransport`）、`pyyaml`、`pytest`。

**Spec:** `docs/superpowers/specs/2026-05-31-bg-localization-design.md`（v3，codex-reviewed DONE）。

**每步 Codex review：** 每个 Stage 末尾有一个 `codex exec --skip-git-repo-check "<review prompt>"` 检查点。verdict 必须 DONE（或修完再过）才进入下一 Stage。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `data-pipeline/src/bgtiers/localize.py` | **新建** | `normalize_name_key(name) -> str` 纯函数（NFKC+casefold+trim+collapse），唯一归一化契约 |
| `data-pipeline/src/bgtiers/db.py` | 改 | `SCHEMA` 加 `entity_name` 表 + `idx_entity_name_lookup` 索引 |
| `data-pipeline/src/bgtiers/config.py` | 改 | `hsjson_cards_url` → `hsjson_locale_config(path) -> (template, default_locale, locales)` |
| `data-pipeline/sources.yaml` | 改 | `hsjson` 段改为 `cards_url_template` + `default_locale` + `locales` |
| `data-pipeline/src/bgtiers/entities.py` | 改 | `sync_entities(...)` 按 locale + seen-map + `entity_name` upsert/reconcile |
| `data-pipeline/src/bgtiers/cli.py` | 改 | `cmd_sync_entities` 按 locale 顺序去重遍历 + 每 locale 事务 + 失败非零退出；新增 `_ordered_locales` |
| `data-pipeline/tests/fixtures/hsjson_cards_zhTW.json` | **新建** | 与 enUS fixture 同卡 id 的 zhTW 名（含一张缺 zhTW 名的卡） |
| `data-pipeline/tests/test_localize.py` | **新建** | 归一化单测 |
| `data-pipeline/tests/test_entities.py` | 改 | 适配新签名 + 新增 locale / reconcile / 碰撞 / stale-stub 用例 |
| `data-pipeline/tests/test_config.py` | 改 | 替换 `test_hsjson_cards_url` → `test_hsjson_locale_config` |
| `data-pipeline/tests/test_integration.py` | 改 | 3 处 `sync_entities(conn, cards, now=...)` 调用适配新签名 |
| `data-pipeline/tests/test_cli.py` | 改 | 新增 `sync-entities` 多 locale + 部分失败 + 事务回滚 + `_ordered_locales` 用例 |

所有命令在 `data-pipeline/` 目录下用 `uv run` 执行。

---

## Stage 1: `normalize_name_key` 纯函数

**Files:**
- Create: `data-pipeline/src/bgtiers/localize.py`
- Test: `data-pipeline/tests/test_localize.py`

- [ ] **Step 1: 写失败测试**

`data-pipeline/tests/test_localize.py`:

```python
from bgtiers.localize import normalize_name_key


def test_casefold_lowercases_latin():
    assert normalize_name_key("Sneed") == normalize_name_key("SNEED") == "sneed"


def test_trim_and_collapse_whitespace():
    assert normalize_name_key("  Rokara,   Arcane  Warrior ") == "rokara, arcane warrior"


def test_nfkc_fullwidth_to_halfwidth():
    # 全角拉丁/数字 -> 半角，再 casefold
    assert normalize_name_key("Ｓｎｅｅｄ") == "sneed"


def test_chinese_is_preserved():
    assert normalize_name_key("洛卡菈") == "洛卡菈"


def test_blank_inputs_return_empty():
    assert normalize_name_key("") == ""
    assert normalize_name_key("   ") == ""
    assert normalize_name_key("\t\n ") == ""


def test_non_string_returns_empty():
    assert normalize_name_key(None) == ""
    assert normalize_name_key(123) == ""


def test_punctuation_preserved():
    assert normalize_name_key("Al'Akir") == "al'akir"
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd data-pipeline && uv run pytest tests/test_localize.py -q`
Expected: FAIL（`ModuleNotFoundError: bgtiers.localize`）

- [ ] **Step 3: 实现**

`data-pipeline/src/bgtiers/localize.py`:

```python
"""名称归一化契约：检索键写入与（未来）检索查询必须共用此函数。"""
from __future__ import annotations
import unicodedata


def normalize_name_key(name) -> str:
    """NFKC -> casefold -> trim -> 内部空白折叠为单空格。
    非字符串 / 空 / 纯空白 -> 空字符串（调用方据此跳过或删除该行）。"""
    if not isinstance(name, str):
        return ""
    text = unicodedata.normalize("NFKC", name).casefold()
    return " ".join(text.split())
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd data-pipeline && uv run pytest tests/test_localize.py -q`
Expected: PASS（7 passed）

- [ ] **Step 5: Commit**

```bash
git add data-pipeline/src/bgtiers/localize.py data-pipeline/tests/test_localize.py
git commit -m "feat(localize): normalize_name_key (NFKC+casefold+trim+collapse)"
```

- [ ] **Step 6: Codex review**

```bash
codex exec --skip-git-repo-check "Review the new file data-pipeline/src/bgtiers/localize.py and its test data-pipeline/tests/test_localize.py against spec section 4 of docs/superpowers/specs/2026-05-31-bg-localization-design.md (name_key = NFKC -> casefold -> trim -> collapse whitespace; blank/non-string -> empty string; punctuation preserved). Check correctness, edge cases (combining chars, fullwidth, surrogate-free), and that write-side and future read-side share one contract. Classify Critical/Should-fix/Nit. End with 'Final verdict: DONE' or 'NEEDS-CHANGES'." 2>&1 | tail -30
```
verdict 必须 DONE 才进入 Stage 2。

---

## Stage 2: `entity_name` schema

**Files:**
- Modify: `data-pipeline/src/bgtiers/db.py`
- Test: `data-pipeline/tests/test_db.py`（已存在；若无则新建）

- [ ] **Step 1: 写失败测试**

追加到 `data-pipeline/tests/test_db.py`（若该文件不存在则新建，含 `from bgtiers import db`）:

```python
def test_entity_name_table_and_index_created(conn):
    cols = {r["name"] for r in conn.execute("PRAGMA table_info(entity_name)").fetchall()}
    assert cols == {"entity_id", "locale", "name", "name_key"}
    idx = {r["name"] for r in conn.execute("PRAGMA index_list(entity_name)").fetchall()}
    assert "idx_entity_name_lookup" in idx


def test_entity_name_unique_entity_locale(conn):
    import sqlite3, pytest
    conn.execute("INSERT INTO entity (entity_type, card_id, first_seen_at, last_seen_at) "
                 "VALUES ('hero','H',?,?)", ("t", "t"))
    eid = conn.execute("SELECT entity_id FROM entity").fetchone()["entity_id"]
    conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key) VALUES (?,?,?,?)",
                 (eid, "enUS", "Foo", "foo"))
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key) VALUES (?,?,?,?)",
                     (eid, "enUS", "Bar", "bar"))
```

> `conn` fixture 来自 `tests/conftest.py`（in-memory + `init_db`）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd data-pipeline && uv run pytest tests/test_db.py -q`
Expected: FAIL —— `test_entity_name_table_and_index_created` 因 `PRAGMA table_info` 返回空集触发**断言失败**（cols == set()），`test_entity_name_unique_entity_locale` 因 `no such table: entity_name` 报错。

- [ ] **Step 3: 实现**

在 `data-pipeline/src/bgtiers/db.py` 的 `SCHEMA` 字符串里，`entity` 表之后、`fetch_state` 之前插入：

```sql
CREATE TABLE IF NOT EXISTS entity_name (
  entity_id  INTEGER NOT NULL REFERENCES entity(entity_id),
  locale     TEXT NOT NULL,
  name       TEXT NOT NULL,
  name_key   TEXT NOT NULL,
  UNIQUE (entity_id, locale)
);
```

并在 `SCHEMA` 末尾（其它 `CREATE INDEX` 旁）加：

```sql
CREATE INDEX IF NOT EXISTS idx_entity_name_lookup ON entity_name (locale, name_key);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd data-pipeline && uv run pytest tests/test_db.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add data-pipeline/src/bgtiers/db.py data-pipeline/tests/test_db.py
git commit -m "feat(db): entity_name side table + (locale,name_key) lookup index"
```

- [ ] **Step 6: Codex review**

```bash
codex exec --skip-git-repo-check "Review the entity_name table + idx_entity_name_lookup added to data-pipeline/src/bgtiers/db.py against spec section 5.1 of docs/superpowers/specs/2026-05-31-bg-localization-design.md. Confirm: UNIQUE(entity_id,locale) for idempotent upsert; non-unique (locale,name_key) lookup index; FK to entity; CREATE ... IF NOT EXISTS keeps init_db idempotent on existing DBs; parent v_latest_stats untouched. Classify Critical/Should-fix/Nit. End with 'Final verdict: DONE' or 'NEEDS-CHANGES'." 2>&1 | tail -30
```

---

## Stage 3: config `hsjson_locale_config` + sources.yaml

**Files:**
- Modify: `data-pipeline/src/bgtiers/config.py`, `data-pipeline/sources.yaml`
- Test: `data-pipeline/tests/test_config.py`

- [ ] **Step 1: 改测试（替换旧的 `test_hsjson_cards_url`）**

在 `data-pipeline/tests/test_config.py` 删除 `test_hsjson_cards_url`，新增：

```python
def test_hsjson_locale_config(tmp_path):
    path = _write(tmp_path, """
        firestone: {hero_url: "h", trinket_url: "t", brackets: ["100"], periods: ["last-patch"]}
        hsjson:
          cards_url_template: "http://cards/{locale}.json"
          default_locale: "enUS"
          locales: ["enUS", "zhTW"]
    """)
    template, default_locale, locales = config.hsjson_locale_config(path)
    assert template == "http://cards/{locale}.json"
    assert default_locale == "enUS"
    assert locales == ["enUS", "zhTW"]
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd data-pipeline && uv run pytest tests/test_config.py -q`
Expected: FAIL（`AttributeError: ... hsjson_locale_config`）

- [ ] **Step 3: 实现**

`data-pipeline/src/bgtiers/config.py`：新增 `hsjson_locale_config`，并把 `hsjson_cards_url` **改成 over 新配置的临时 shim**（Stage 4 cli 改造后删除）——这样本 stage 结束代码仍可运行（旧 `cmd_sync_entities` 经 shim 拿 default-locale URL，无悬空调用）：

```python
def hsjson_locale_config(path: str) -> tuple[str, str, list[str]]:
    h = _read(path)["hsjson"]
    return h["cards_url_template"], h["default_locale"], list(h["locales"])


def hsjson_cards_url(path: str) -> str:
    # 临时 shim：旧 cmd_sync_entities 仍调用它；Stage 4 改造 cli 后删除。
    template, default_locale, _ = hsjson_locale_config(path)
    return template.format(locale=default_locale)
```

`data-pipeline/sources.yaml`：把 `hsjson` 段从

```yaml
hsjson:
  cards_url: "https://api.hearthstonejson.com/v1/latest/enUS/cards.json"
```

改为

```yaml
hsjson:
  cards_url_template: "https://api.hearthstonejson.com/v1/latest/{locale}/cards.json"
  default_locale: "enUS"
  locales: ["enUS", "zhTW"]
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd data-pipeline && uv run pytest tests/test_config.py -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add data-pipeline/src/bgtiers/config.py data-pipeline/sources.yaml data-pipeline/tests/test_config.py
git commit -m "feat(config): hsjson_locale_config (template+default_locale+locales)"
```

- [ ] **Step 6: Codex review**

```bash
codex exec --skip-git-repo-check "Review config.hsjson_locale_config + sources.yaml hsjson change in data-pipeline against spec section 3/6.2 of docs/superpowers/specs/2026-05-31-bg-localization-design.md. Confirm: template/default_locale/locales returned correctly; hsjson_cards_url is now a thin SHIM over hsjson_locale_config (intentional — still called by the unchanged cmd_sync_entities so the codebase stays runnable this stage; it will be removed in Stage 4 when cli is rewritten) and the shim returns the default-locale URL against the NEW sources.yaml format (no stale cards_url key); load_fetch_tasks (firestone) untouched. Classify Critical/Should-fix/Nit. End with 'Final verdict: DONE' or 'NEEDS-CHANGES'." 2>&1 | tail -30
```

---

## Stage 4: `sync_entities` 按 locale + seen-map + entity_name

**Files:**
- Modify: `data-pipeline/src/bgtiers/entities.py`
- Create: `data-pipeline/tests/fixtures/hsjson_cards_zhTW.json`
- Test: `data-pipeline/tests/test_entities.py`

- [ ] **Step 1: 建 zhTW fixture**

`data-pipeline/tests/fixtures/hsjson_cards_zhTW.json`（同卡 id；`BG30_MagicItem_301` **故意无 name** 测缺翻译）:

```json
[
  {"id": "BG_HERO_001", "dbfId": 50001, "name": "測試英雄一", "type": "HERO", "set": "BATTLEGROUNDS"},
  {"id": "BG_HERO_002", "dbfId": 50002, "name": "測試英雄二", "type": "HERO", "set": "BATTLEGROUNDS"},
  {"id": "BG30_MagicItem_902", "dbfId": 60902, "name": "神聖之槌", "type": "BATTLEGROUND_TRINKET", "set": "BATTLEGROUNDS", "spellSchool": "GREATER_TRINKET"},
  {"id": "BG30_MagicItem_301", "dbfId": 60301, "type": "BATTLEGROUND_TRINKET", "set": "BATTLEGROUNDS", "spellSchool": "LESSER_TRINKET"},
  {"id": "AT_001", "dbfId": 100, "name": "構築卡", "type": "MINION", "set": "TGT"}
]
```

- [ ] **Step 2: 改既有测试适配新签名 + 加新用例**

把 `data-pipeline/tests/test_entities.py` 整体替换为：

```python
import json
import pathlib
import pytest
from bgtiers import entities
from bgtiers.localize import normalize_name_key

FIX = pathlib.Path(__file__).parent / "fixtures"


def _en():
    return json.loads((FIX / "hsjson_cards.json").read_text())


def _zh():
    return json.loads((FIX / "hsjson_cards_zhTW.json").read_text())


def _sync(conn, cards, locale="enUS", default="enUS", now="t", known=None):
    return entities.sync_entities(conn, cards, locale, default, now, known)


# ---- identity (default locale) — parity with previous behaviour ----

def test_sync_inserts_heroes_and_trinkets(conn):
    n, synced = _sync(conn, _en())
    by = {r["card_id"]: r for r in
          conn.execute("SELECT card_id, entity_type, trinket_class FROM entity").fetchall()}
    assert set(by) == {"BG_HERO_001", "BG_HERO_002", "BG30_MagicItem_902", "BG30_MagicItem_301"}
    assert by["BG_HERO_001"]["entity_type"] == "hero"
    assert by["BG30_MagicItem_902"]["entity_type"] == "trinket"
    assert n == 4                                     # constructed card excluded
    assert set(synced) == {("hero", "BG_HERO_001"), ("hero", "BG_HERO_002"),
                           ("trinket", "BG30_MagicItem_902"), ("trinket", "BG30_MagicItem_301")}


def test_sync_sets_trinket_class_from_spellschool(conn):
    _sync(conn, _en())
    cls = {r["card_id"]: r["trinket_class"] for r in
           conn.execute("SELECT card_id, trinket_class FROM entity").fetchall()}
    assert cls["BG30_MagicItem_902"] == "greater"
    assert cls["BG30_MagicItem_301"] == "lesser"


def test_sync_is_idempotent(conn):
    _sync(conn, _en()); _sync(conn, _en())
    assert conn.execute("SELECT COUNT(*) FROM entity").fetchone()[0] == 4
    assert conn.execute("SELECT COUNT(*) FROM entity_name").fetchone()[0] == 4   # enUS names, no dup


def test_ensure_entity_creates_stub_for_unknown_card(conn):
    eid = entities.ensure_entity(conn, "trinket", "BG30_MagicItem_999", now="t")
    row = conn.execute("SELECT * FROM entity WHERE entity_id=?", (eid,)).fetchone()
    assert row["card_id"] == "BG30_MagicItem_999"
    assert row["name"] is None and row["trinket_class"] is None
    assert row["entity_type"] == "trinket"


def test_ensure_entity_returns_existing_id(conn):
    a = entities.ensure_entity(conn, "hero", "BG_HERO_X", now="t")
    b = entities.ensure_entity(conn, "hero", "BG_HERO_X", now="t")
    assert a == b


def test_sync_backfills_existing_stub(conn):
    entities.ensure_entity(conn, "trinket", "BG30_MagicItem_301", now="t0")
    _sync(conn, _en(), now="t1")
    row = conn.execute("SELECT name, dbf_id, trinket_class FROM entity "
                       "WHERE card_id='BG30_MagicItem_301'").fetchone()
    assert row["name"] == "Lesser One" and row["dbf_id"] == 60301 and row["trinket_class"] == "lesser"


def test_sync_does_not_clobber_known_name_with_null(conn):
    _sync(conn, _en(), now="t0")
    _sync(conn, [{"id": "BG_HERO_001", "type": "HERO", "set": "BATTLEGROUNDS"}], now="t1")
    row = conn.execute("SELECT name, dbf_id FROM entity WHERE card_id='BG_HERO_001'").fetchone()
    assert row["name"] == "Test Hero One" and row["dbf_id"] == 50001


# ---- entity.name stays enUS; entity_name holds all locales ----

def test_entity_name_default_only_sets_entity_name_column(conn):
    _, synced = _sync(conn, _en())
    _sync(conn, _zh(), locale="zhTW", known=synced)
    # entity.name == enUS for a hero
    name = conn.execute("SELECT name FROM entity WHERE card_id='BG_HERO_001'").fetchone()["name"]
    assert name == "Test Hero One"
    # both-locale rows in entity_name
    eid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG_HERO_001'").fetchone()["entity_id"]
    locs = {r["locale"]: r["name"] for r in
            conn.execute("SELECT locale, name FROM entity_name WHERE entity_id=?", (eid,)).fetchall()}
    assert locs == {"enUS": "Test Hero One", "zhTW": "測試英雄一"}


def test_id_to_name_zhTW(conn):
    _, synced = _sync(conn, _en()); _sync(conn, _zh(), locale="zhTW", known=synced)
    eid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG_HERO_002'").fetchone()["entity_id"]
    row = conn.execute("SELECT name FROM entity_name WHERE entity_id=? AND locale='zhTW'", (eid,)).fetchone()
    assert row["name"] == "測試英雄二"


def test_name_to_entity_both_languages(conn):
    _, synced = _sync(conn, _en()); _sync(conn, _zh(), locale="zhTW", known=synced)
    # zhTW name -> entity
    key = normalize_name_key("測試英雄一")
    eid = conn.execute("SELECT entity_id FROM entity_name WHERE locale='zhTW' AND name_key=?",
                       (key,)).fetchone()["entity_id"]
    assert conn.execute("SELECT card_id FROM entity WHERE entity_id=?", (eid,)).fetchone()["card_id"] == "BG_HERO_001"
    # enUS case/fullwidth variant still hits
    eid2 = conn.execute("SELECT entity_id FROM entity_name WHERE locale='enUS' AND name_key=?",
                        (normalize_name_key("ＴＥＳＴ  hero  ONE"),)).fetchone()["entity_id"]
    assert eid2 == eid


# ---- missing translation + reconcile ----

def test_missing_zhTW_name_no_row_but_enUS_present(conn):
    _, synced = _sync(conn, _en()); _sync(conn, _zh(), locale="zhTW", known=synced)
    eid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG30_MagicItem_301'").fetchone()["entity_id"]
    locs = {r["locale"] for r in
            conn.execute("SELECT locale FROM entity_name WHERE entity_id=?", (eid,)).fetchall()}
    assert locs == {"enUS"}                            # no zhTW row (301 has no zhTW name)


def test_reconcile_deletes_stale_translation_when_blank(conn):
    _, synced = _sync(conn, _en())
    _sync(conn, _zh(), locale="zhTW", known=synced)   # 902 has zhTW name
    eid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG30_MagicItem_902'").fetchone()["entity_id"]
    assert conn.execute("SELECT COUNT(*) FROM entity_name WHERE entity_id=? AND locale='zhTW'",
                        (eid,)).fetchone()[0] == 1
    # re-sync zhTW where 902 name is now blank -> row deleted
    zh2 = [dict(c, name="  ") if c["id"] == "BG30_MagicItem_902" else c for c in _zh()]
    _sync(conn, zh2, locale="zhTW", known=synced)
    assert conn.execute("SELECT COUNT(*) FROM entity_name WHERE entity_id=? AND locale='zhTW'",
                        (eid,)).fetchone()[0] == 0


# ---- collision + stale-stub guard ----

def test_name_key_collision_returns_two_entities(conn):
    cards = [{"id": "H_A", "type": "HERO", "set": "BATTLEGROUNDS", "name": "Foo"},
             {"id": "H_B", "type": "HERO", "set": "BATTLEGROUNDS", "name": "foo"}]
    _sync(conn, cards)
    rows = conn.execute("SELECT entity_id FROM entity_name WHERE locale='enUS' AND name_key=?",
                        (normalize_name_key("Foo"),)).fetchall()
    assert len(rows) == 2


def test_non_default_card_not_in_seen_map_is_skipped(conn):
    # pre-existing stub NOT in this run's enUS cards
    entities.ensure_entity(conn, "hero", "BG_HERO_GHOST", now="t0")
    _, synced = _sync(conn, _en())                    # enUS does NOT include GHOST
    ghost_zh = _zh() + [{"id": "BG_HERO_GHOST", "type": "HERO", "set": "BATTLEGROUNDS", "name": "幽靈"}]
    _sync(conn, ghost_zh, locale="zhTW", known=synced)
    gid = conn.execute("SELECT entity_id FROM entity WHERE card_id='BG_HERO_GHOST'").fetchone()["entity_id"]
    assert conn.execute("SELECT COUNT(*) FROM entity_name WHERE entity_id=?", (gid,)).fetchone()[0] == 0
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd data-pipeline && uv run pytest tests/test_entities.py -q`
Expected: FAIL（旧 `sync_entities` 签名 / 无 `entity_name` 写入）

- [ ] **Step 4: 实现**

把 `data-pipeline/src/bgtiers/entities.py` 替换为：

```python
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
```

- [ ] **Step 4b: interim cli 更新 + 删除 shim（避免悬空调用）**

改 `sync_entities` 签名会破坏 `cli.cmd_sync_entities` 的旧调用，故本 stage 同步做**最小化** cli 更新（单 locale 临时版，多 locale 循环留到 Stage 5），并删除 Stage 3 的 `hsjson_cards_url` shim（此后无调用者）。

`data-pipeline/src/bgtiers/cli.py`：把 `cmd_sync_entities` 临时改为：

```python
def cmd_sync_entities(args):
    conn = db.connect(args.db)
    db.init_db(conn)
    template, default_locale, _ = config.hsjson_locale_config(args.sources)
    cards = httpx.get(template.format(locale=default_locale), timeout=60).json()
    n, _ = entities.sync_entities(conn, cards, default_locale, default_locale, _now())
    print(f"synced {n} entity-names (interim: default locale {default_locale} only)")
```

`data-pipeline/src/bgtiers/config.py`：删除 Stage 3 加的 `hsjson_cards_url` shim（现已无调用者）。

> 验证无悬空引用：`cd data-pipeline && grep -rn "hsjson_cards_url" src tests` 应只剩**无**结果（或仅历史注释）。`import` 检查：`uv run python -c "from bgtiers import cli"` 应成功。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd data-pipeline && uv run pytest tests/test_entities.py -q`
Expected: PASS（全部）

- [ ] **Step 5b: 更新 `test_integration.py` 适配新签名**

`test_integration.py` 有 3 处旧调用 `entities.sync_entities(conn, _load("hsjson_cards.json"), now="t0")`（约在第 19/41/63 行）。把每处改为新签名（返回值忽略）：

```python
entities.sync_entities(conn, _load("hsjson_cards.json"), "enUS", "enUS", "t0")
```

Run: `cd data-pipeline && uv run pytest tests/test_integration.py -q`
Expected: PASS（3 个 e2e 用例，新签名下不回归）

- [ ] **Step 6: Commit**

```bash
git add data-pipeline/src/bgtiers/entities.py data-pipeline/src/bgtiers/cli.py data-pipeline/src/bgtiers/config.py data-pipeline/tests/test_entities.py data-pipeline/tests/test_integration.py data-pipeline/tests/fixtures/hsjson_cards_zhTW.json
git commit -m "feat(entities): per-locale sync with entity_name upsert/reconcile + seen-map

Includes interim single-locale cmd_sync_entities update (new sync_entities
signature) and removal of the temporary hsjson_cards_url shim; full
multi-locale cmd_sync_entities lands in the next stage."
```

- [ ] **Step 7: Codex review**

```bash
codex exec --skip-git-repo-check "Review data-pipeline/src/bgtiers/entities.py + tests/test_entities.py against spec sections 6.1/4/7/8 of docs/superpowers/specs/2026-05-31-bg-localization-design.md. Verify: default locale upserts identity + returns seen-map; non-default attaches names ONLY to (entity_type,card_id) in known_ids (no raw SELECT path to stale stubs); blank name (normalized empty) DELETEs the (entity_id,locale) row; entity.name stays default-locale; UNIQUE(entity_id,locale) keeps upsert idempotent; collision yields 2 rows. Check the stale-stub guard test actually proves the guard. Classify Critical/Should-fix/Nit. End with 'Final verdict: DONE' or 'NEEDS-CHANGES'." 2>&1 | tail -40
```

---

## Stage 5: CLI `cmd_sync_entities` 多 locale 遍历

**Files:**
- Modify: `data-pipeline/src/bgtiers/cli.py`
- Test: `data-pipeline/tests/test_cli.py`

- [ ] **Step 1: 写失败测试（追加到 test_cli.py）**

> 先确保 `test_cli.py` 顶部 import 含 `entities`：`from bgtiers import cli, db, entities`。

```python
def _sync_sources(tmp_path):
    p = tmp_path / "sources_loc.yaml"
    p.write_text(textwrap.dedent("""
        firestone: {hero_url: "h", trinket_url: "t", brackets: ["10"], periods: ["last-patch"]}
        hsjson:
          cards_url_template: "http://cards/{locale}.json"
          default_locale: "enUS"
          locales: ["enUS", "zhTW"]
    """))
    return str(p)


_EN_CARDS = [{"id": "BG_HERO_001", "dbfId": 1, "name": "Sneed", "type": "HERO", "set": "BATTLEGROUNDS"}]
_ZH_CARDS = [{"id": "BG_HERO_001", "dbfId": 1, "name": "斯尼德", "type": "HERO", "set": "BATTLEGROUNDS"}]


def _locale_client(monkeypatch, fail_locale=None):
    """Patch cli.httpx.Client with a MockTransport; returns the list of fetched locales
    (so a test can prove a locale was NOT requested)."""
    real_client = httpx.Client
    requested = []

    def handler(req):
        loc = req.url.path.rsplit("/", 1)[-1].replace(".json", "")
        requested.append(loc)
        if loc == fail_locale:
            return httpx.Response(500)
        body = _ZH_CARDS if loc == "zhTW" else _EN_CARDS
        return httpx.Response(200, content=json.dumps(body).encode())

    monkeypatch.setattr(cli.httpx, "Client",
                        lambda *a, **k: real_client(transport=httpx.MockTransport(handler)))
    return requested


def test_ordered_locales_default_first_dedupe():
    assert cli._ordered_locales("enUS", ["zhTW", "enUS", "zhTW"]) == ["enUS", "zhTW"]
    assert cli._ordered_locales("enUS", ["zhTW"]) == ["enUS", "zhTW"]   # default absent from locales
    assert cli._ordered_locales("enUS", []) == ["enUS"]


def test_sync_entities_loads_both_locales(tmp_path, monkeypatch):
    dbp = str(tmp_path / "t.db")
    _locale_client(monkeypatch)
    args = cli.build_parser().parse_args(["--db", dbp, "--sources", _sync_sources(tmp_path), "sync-entities"])
    args.func(args)
    conn = db.connect(dbp)
    rows = {r["locale"]: r["name"] for r in
            conn.execute("SELECT locale, name FROM entity_name").fetchall()}
    assert rows == {"enUS": "Sneed", "zhTW": "斯尼德"}
    assert conn.execute("SELECT name FROM entity WHERE card_id='BG_HERO_001'").fetchone()["name"] == "Sneed"


def test_sync_entities_zhTW_http_failure_keeps_enus_and_exits_nonzero(tmp_path, monkeypatch):
    dbp = str(tmp_path / "t.db")
    _locale_client(monkeypatch, fail_locale="zhTW")
    args = cli.build_parser().parse_args(["--db", dbp, "--sources", _sync_sources(tmp_path), "sync-entities"])
    with pytest.raises(SystemExit) as ei:
        args.func(args)
    assert ei.value.code == 1
    conn = db.connect(dbp)
    locs = {r["locale"] for r in conn.execute("SELECT locale FROM entity_name").fetchall()}
    assert locs == {"enUS"}                            # enUS committed, zhTW never written


def test_sync_entities_rolls_back_locale_on_midtxn_error(tmp_path, monkeypatch):
    # Prove the per-locale BEGIN IMMEDIATE rollback: zhTW writes a row, then raises INSIDE
    # the transaction. That partial write must be rolled back; enUS stays committed.
    dbp = str(tmp_path / "t.db")
    _locale_client(monkeypatch)
    real_sync = entities.sync_entities

    def flaky(conn, cards, locale, default_locale, now, known_ids=None):
        if locale == "zhTW":
            eid = next(iter(known_ids.values()))
            conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key) "
                         "VALUES (?,?,?,?)", (eid, "zhTW", "斯尼德", "斯尼德"))
            raise RuntimeError("boom mid-transaction")
        return real_sync(conn, cards, locale, default_locale, now, known_ids)

    monkeypatch.setattr(cli.entities, "sync_entities", flaky)
    args = cli.build_parser().parse_args(["--db", dbp, "--sources", _sync_sources(tmp_path), "sync-entities"])
    with pytest.raises(SystemExit) as ei:
        args.func(args)
    assert ei.value.code == 1
    conn = db.connect(dbp)
    locs = {r["locale"] for r in conn.execute("SELECT locale FROM entity_name").fetchall()}
    assert locs == {"enUS"}                            # the zhTW row written before boom was rolled back


def test_sync_entities_default_failure_skips_nondefault(tmp_path, monkeypatch):
    dbp = str(tmp_path / "t.db")
    requested = _locale_client(monkeypatch, fail_locale="enUS")
    args = cli.build_parser().parse_args(["--db", dbp, "--sources", _sync_sources(tmp_path), "sync-entities"])
    with pytest.raises(SystemExit) as ei:
        args.func(args)
    assert ei.value.code == 1
    assert requested == ["enUS"]                       # zhTW never fetched (loop broke on default failure)
    conn = db.connect(dbp)
    assert conn.execute("SELECT COUNT(*) FROM entity_name").fetchone()[0] == 0
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd data-pipeline && uv run pytest tests/test_cli.py -q`
Expected: FAIL（旧 `cmd_sync_entities` 用 `httpx.get` + `hsjson_cards_url`）

- [ ] **Step 3: 实现**

`data-pipeline/src/bgtiers/cli.py`：替换 `cmd_sync_entities`，并加 `_ordered_locales`：

```python
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
```

> `cli.py` 顶部已 `import sys` / `import httpx` / `from . import ... entities, config`（无需新增 import；确认 `entities` 在 import 列表中——已在）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd data-pipeline && uv run pytest tests/test_cli.py -q`
Expected: PASS

- [ ] **Step 5: 跑全量测试**

Run: `cd data-pipeline && uv run pytest -q`
Expected: PASS（全部；含父 spec 既有用例不回归）

- [ ] **Step 6: Commit**

```bash
git add data-pipeline/src/bgtiers/cli.py data-pipeline/tests/test_cli.py
git commit -m "feat(cli): sync-entities loops locales (ordered dedupe, per-locale txn, seen-map)"
```

- [ ] **Step 7: Codex review（最终）**

```bash
codex exec --skip-git-repo-check "Final review of data-pipeline/src/bgtiers/cli.py cmd_sync_entities + _ordered_locales + tests/test_cli.py against spec section 6.2/7 of docs/superpowers/specs/2026-05-31-bg-localization-design.md. Verify: order-preserving dedupe with default first; each locale in its own BEGIN IMMEDIATE committed before known_ids is captured; non-default failure rolls back only that locale, others commit, exit code 1; default-locale failure breaks before non-default; httpx.Client used so MockTransport works. Also confirm no regression to fetch-stats. Classify Critical/Should-fix/Nit. End with 'Final verdict: DONE' or 'NEEDS-CHANGES'." 2>&1 | tail -40
```

---

## Self-Review（写完计划的回查）

**Spec coverage：** L1 locale 集合→Stage 3；L2 数据源→Stage 3 sources.yaml；L3 `entity_name` 侧表→Stage 2；L4 归一化精确匹配→Stage 1 + Stage 4 name→entity 测试；L5 `entity.name` 保留→Stage 4 `test_entity_name_default_only_...`；L6 无条件 upsert→Stage 4/5。§4 blank→Stage 1 + Stage 4 reconcile。§6.1 seen-map→Stage 4。§6.2 事务/失败/去重→Stage 5：`_ordered_locales` 直测（default 先行 + 保序去重 + default 不在 locales）、HTTP 失败回滚、**事务内 mid-txn 抛错回滚**（`test_sync_entities_rolls_back_locale_on_midtxn_error`）、default 失败**证明非 default 未被 fetch**（断言 `requested == ["enUS"]`）。§7 边界（缺翻译/失败/stub/碰撞）→Stage 4/5 各有用例。§8 成功标准 1-12 全部有对应测试。

**回归保护：** 改 `sync_entities` 签名波及 `test_integration.py` 的 3 处旧调用 → Stage 4 Step 5b 显式更新；Stage 5 Step 5 跑全量套件确认父 spec 用例（fetch-stats / load / normalize）不回归。

**Codex review v1（计划层）已纳入：** Critical（漏改 `test_integration.py`）→ Step 5b；Should-fix（CLI 失败测试未证明事务回滚 / 未证明跳过非 default / 缺 `_ordered_locales` 直测）→ Stage 5 三个新测试；Nit（Stage 2 red 原因措辞）→ 已修正。

**Codex review v2（计划层）已纳入：** Should-fix（Stage 3 删 `hsjson_cards_url` 但 `cli.py` 到 Stage 5 才停用 → 中间态悬空调用）→ 改为 **Stage 3 留 shim + Stage 4 Step 4b 做最小化 cli 更新并删 shim**，保证每个 commit 可运行、无悬空引用（含 `grep` 与 `import` 验证）。cli.py 因此在 Stage 4（interim 单 locale）与 Stage 5（多 locale 全量）各动一次，均最小化。

**Placeholder scan：** 无 TBD / TODO；每个改代码的 step 都给了完整代码。

**Type consistency：** `sync_entities(...)` 返回 `(touched, synced)` 在 Stage 4 定义、Stage 5 CLI 按 `n, synced` 解包一致；`hsjson_locale_config` 返回三元组在 Stage 3 定义、Stage 5 解包一致；`normalize_name_key` 在 Stage 1 定义、Stage 4 测试复用同一函数。

**已知良性副作用：** `test_sync_does_not_clobber_known_name_with_null` 第二次 sync 的 stripped 卡无 name → `_upsert_name` 会 DELETE 该卡 enUS `entity_name` 行（符合 §4 reconcile），但 `entity.name` 经 COALESCE 保留；该测试只断言 `entity.name`，仍通过。属预期行为，非缺陷。
