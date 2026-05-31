# BG 英雄 / 饰品 名称本地化（多语言名称 抓取 + 存储）设计文档

**Date:** 2026-05-31
**Status:** v3（已纳入 codex review v1 + v2 全部 should-fix + nit，待 re-review）

> **修订记录**
> - v1 → v2：纳入 codex review v1 —— (1) 缺翻译 / 名变空时 **DELETE 旧 `entity_name` 行** reconcile，杜绝 upsert-only 留下的 stale 翻译（§4/§6.1/§7）；(2) 同步加**事务 / 失败边界** —— 每 locale 一个 `BEGIN IMMEDIATE` 原子提交，失败回滚该 locale、其余继续、命令非零退出，default locale 失败则中止（§6.2/§7）；(3) locale 顺序改为**保序去重**，修复重复非 default locale 跑两次（§6.2）；(4) 澄清 `card_id → name` 需经 `entity` 按 `(entity_type, card_id)` join（§1）。
> - v2 → v3：纳入 codex review v2 —— (1) 非 default locale 取 `entity_id` 改用 **default run 的 seen-map（`known_ids`）** 而非裸 `SELECT`，杜绝把本地化名错挂到本次未建立的旧 stub / 历史行；map 在 default 事务提交后固化再传给非 default（§6.1/§6.2）；(2) 精确定义「空名」= **归一化后空字符串**（含纯空白），并把「enUS 行总在」收窄为「enUS 名非空时总在」，单张空名卡不视为 default 失败（§4/§7/§8）。
**Layer:** 扩展现有 `data-pipeline/` 子系统，**不改统计抓取（fetch-stats）链路**，只扩展身份同步（sync-entities）与 schema
**Scope:** 只做**本地化名称的获取 + 存储**。先支持**繁体中文（zhTW）+ 英文（enUS）**，schema 设计为加语言零改动。存储格式须同时支持两种用法：**①name→entity 检索**（给定某语言名称定位到实体及其 tier 数据）、**②id→name 显示**（给定实体渲染指定语言名称）。**不含**消费层（overlay、对外 API、检索/显示的具体实现代码）。

> 这是 [BG tier 数据管线 spec](2026-05-31-bg-tier-data-pipeline-design.md) 的后续扩展，独立走 spec → plan → 实现 周期。父 spec 的 `entity` 主表只存单一 `name`（来自 HearthstoneJSON enUS）；本 spec 在其上叠加多语言名称。

---

## 1. Why（动机 + 目标场景）

未来 overlay 在游戏里感知到英雄 / 饰品时，要按玩家客户端语言显示名称；并且检测手段（OCR / 游戏日志）拿到的是**本地化后的名称字符串**，需要据此反查到实体及其 tier 数据。

因此本 spec 让数据管线**为每个实体存多语言名称 + 归一化检索键**，使两个方向都成立：

- **id → name（显示）**：已知 `entity_id` 取其在指定 `locale` 的显示名。若起点是 `card_id`，先经 `entity` 表按 `(entity_type, card_id)`（父 spec 的唯一键）join 出 `entity_id` 再取名。
- **name → entity（检索）**：给定某语言的名称字符串，归一化后定位到实体。

本 spec 只负责**把多语言名称搞进库并建好检索键**，不实现检索 / 显示的查询 API（那是未来消费层）。

---

## 2. 关键约束（已与用户确认）

| # | 决策 | 取舍 |
|---|---|---|
| L1 | 首批语言 = **繁体中文 `zhTW` + 英文 `enUS`**，default = `enUS` | 简体是 `zhCN`（不同 locale）；扩展语言 = `locales` 列表加一行，无代码 / schema 改动 |
| L2 | 数据源 = **同一个 HearthstoneJSON 端点**的 per-locale `cards.json` | 不引入新数据源；只有 `name` 随 locale 变，`id`/`dbfId`/`type`/`spellSchool` 跨 locale 一致 |
| L3 | 存储 = **新增 `entity_name` 侧表**（`entity_id, locale, name, name_key`） | 不在 `entity` 上加列、不存 JSON blob；侧表对「加语言」零 schema 改动且检索可走普通索引 |
| L4 | 检索匹配 = **归一化后精确匹配**（非子串 / 非模糊） | 输入预期是规范的完整本地化名；`name_key` 存归一化键，查 = 等值匹配 |
| L5 | `entity.name` **保留** = `default_locale`（enUS）字符串 | 父 spec 的 `v_latest_stats` 与既有测试 / 消费者不受影响；`entity_name` 存**全部** locale（含 enUS）作为检索单一来源 |
| L6 | 同步方式 = **每次 run 无条件 upsert**（与现 `sync-entities` 一致） | 身份 / 名称同步不引入 `fetch_state` 条件请求；幂等靠 upsert |

---

## 3. 数据源

**HearthstoneJSON per-locale 身份文件**（与父 spec §3.1 同一来源，只是按 locale 取）：

```
https://api.hearthstonejson.com/v1/latest/{locale}/cards.json
```

- 已验证 `enUS` / `zhTW` / `zhCN` 端点均 `HTTP 200`。
- 已验证 `zhTW` 返回**真繁体**，与简体 `zhCN` 区分明确：

  | card_id | enUS | zhTW（繁） | zhCN（简） |
  |---|---|---|---|
  | `BG20_HERO_100` | Rokara | 洛卡**菈** | 洛卡拉 |
  | `BG30_MagicItem_301` | Eternal Portrait | 永**恆**頭像 | 永恒肖像 |
  | `BG20_HERO_100_SKIN_A4` | Rokara, Arcane Warrior | 秘法**戰士**洛卡菈 | 奥术战士洛卡拉 |

- 跨 locale **只有 `name` 不同**；身份字段（`id`/`dbfId`/`type`/`set`/`spellSchool`）一致 → 实体识别（`_is_bg_hero`/`_is_bg_trinket`/`trinket_class`）逻辑跨 locale 复用，**只从 default locale 派生身份**即可（见 §5）。

`sources.yaml` 由单 URL 改为模板 + locale 列表：

```yaml
hsjson:
  cards_url_template: "https://api.hearthstonejson.com/v1/latest/{locale}/cards.json"
  default_locale: "enUS"
  locales: ["enUS", "zhTW"]
```

> **不兼容变更**：删除旧的 `hsjson.cards_url`，改为 `cards_url_template` + `default_locale` + `locales`。`config.hsjson_cards_url()` 相应替换（见 §6）。这是本地配置文件，无外部消费者。

---

## 4. 名称归一化（`name_key`）

检索键对所有 locale 用**同一套规则**，在 Python 侧计算后写入 `name_key`：

1. **Unicode NFKC**：统一全角 / 半角、兼容性字形（如全角英数 → 半角）。
2. **casefold()**：Latin 转小写（对中文是 no-op）。
3. **trim**：去首尾空白。
4. **collapse**：内部连续空白折叠为单个空格。

**保留标点 / 撇号**（如 `Al'Akir`、`Rokara, Arcane Warrior` 的逗号）——输入预期是规范本地化名，保留可避免 `Al'Akir`/`AlAkir` 之类碰撞与歧义。

> `name_key` 是普通 SQLite `TEXT` 列，查询走二进制（区分大小写）比较。让 `Sneed`/`SNEED` 命中同一键的是 **Python 侧的 casefold**，不是 SQLite collation。检索方与写入方必须用**同一个归一化函数**，故该函数是本 spec 的公开契约（见 §5.3）。

**「空」的判定 = name 经上述归一化后为空字符串**（涵盖缺失 / 非字符串 / `""` / 纯空白）。空名 → **不计算 key、不落 `entity_name` 行**；并且若该 `(entity_id, locale)` 行先前已存在（上次 run 有翻译、这次变空），**删除它**以保持库与源一致（见 §6.1 step 3、§7）。

此规则对所有 locale 一致，**含 default locale**：故「enUS 行总在」准确表述为「**enUS 名非空（即所有真实 BG 卡）时 enUS 行总在**」——理论上不存在 enUS 名为空的 BG 卡，但单张空名卡只是少一行，不视为 default locale 失败、不中止整批（见 §6.2 失败语义只针对 fetch / parse）。

---

## 5. 存储 Schema 变更

### 5.1 新增 `entity_name` 侧表

```sql
CREATE TABLE IF NOT EXISTS entity_name (
  entity_id  INTEGER NOT NULL REFERENCES entity(entity_id),
  locale     TEXT NOT NULL,              -- 'enUS' | 'zhTW' | …
  name       TEXT NOT NULL,              -- 来自 HSJSON 的原始显示名（不归一化）
  name_key   TEXT NOT NULL,              -- §4 归一化后的检索键
  UNIQUE (entity_id, locale)
);

CREATE INDEX IF NOT EXISTS idx_entity_name_lookup
  ON entity_name (locale, name_key);     -- name → entity 检索路径
```

- **id → name（显示）**：`SELECT name FROM entity_name WHERE entity_id=? AND locale=?`。
- **name → entity（检索）**：`SELECT entity_id FROM entity_name WHERE locale=? AND name_key=?`。
- `UNIQUE(entity_id, locale)` 保证「一个实体每语言至多一名」，使 upsert 幂等。
- `idx_entity_name_lookup` **非唯一**：不同实体可能归一化到同一 `name_key`（见 §7 碰撞），检索可返回多行，由消费方按 `entity_type`/`trinket_class` 消歧。

### 5.2 `entity` 主表

**不变。** `entity.name` 仍 = `default_locale`（enUS）显示名，保父 spec 的 `v_latest_stats` 与既有测试不动。`entity_name` 含全部 locale（含 enUS），是检索 / 多语言显示的单一来源。

### 5.3 归一化函数契约

`normalize_name_key(name: str) -> str`（新建于一个独立小模块，如 `localize.py`），实现 §4 四步。写入与未来检索都必须经它。纯函数、无依赖、可单测。

---

## 6. 同步机制变更

### 6.1 `sync_entities` 改为按 locale 调用

```
sync_entities(conn, cards, locale, default_locale, now, known_ids=None)
    -> (touched, synced_ids)        # synced_ids: {(entity_type, card_id): entity_id}
```

`entity_id` **不靠裸 `SELECT`**（裸 SELECT 会命中本次 enUS run 未建立的旧 stub / 历史行，把 zhTW 名错挂上去）。改用「本次 default run 的 seen-map」：

- **default locale**（`known_ids` 忽略）：对每张 BG 英雄 / 饰品卡，沿用现有 `entity` upsert（dbf_id / image_url / trinket_class 走既有 COALESCE；`entity.name` 设为 enUS 名），把它 upsert 出的 `entity_id` 收进 `synced_ids` 并返回。
- **非 default locale**（必须传入 default run 返回的 `synced_ids` 作 `known_ids`）：**不写 `entity` 身份**；仅处理 `(entity_type, card_id) ∈ known_ids` 的卡，`entity_id` 直接取自该 map。不在 map 中的卡 → 跳过 + 计一次告警（身份跨 locale 应一致，出现即异常）。

随后对取到 `entity_id` 的卡：

3. **名称 upsert / reconcile**：
   - `name` 非空 → `INSERT ... ON CONFLICT(entity_id, locale) DO UPDATE SET name=excluded.name, name_key=excluded.name_key`。
   - `name` 空 / 缺失 → `DELETE FROM entity_name WHERE entity_id=? AND locale=?`（清掉可能存在的旧翻译，使库与源一致；无旧行则 no-op）。
   - 因每次 run 拉的是该 locale 的**完整** cards.json，「卡仍在但名变空」由上面的 DELETE 覆盖；「卡整张从 feed 消失」极罕见（HSJSON 不会删历史卡），不主动清理，留待该 entity 整体淘汰策略（不在本 spec）。

> 顺序保证：default locale（enUS）**必须先同步**，以建立 / 更新身份与 `entity_id`。CLI 按 `default_locale` 优先、其余按 `locales` 顺序遍历（见 §6.2）。

### 6.2 `cmd_sync_entities` 遍历 locale

**locale 顺序 = default 先行 + 保序去重**（修复重复 locale 跑两次）：

```
def _ordered_locales(default_locale, locales):
    seen, out = set(), []
    for loc in [default_locale, *locales]:     # default 永远第一个
        if loc not in seen:
            seen.add(loc); out.append(loc)
    return out
```

**每个 locale 在一个 `BEGIN IMMEDIATE` 事务里原子处理**（与父 spec load 一致），失败只回滚该 locale：

```
failures = 0
known_ids = None        # default run 的 seen-map；default 先行（_ordered_locales 保证）所以非 default 用时已就绪
for locale in _ordered_locales(default_locale, cfg.locales):
    try:
        cards = httpx.get(cards_url_template.format(locale=locale), timeout=60).json()
        conn.execute("BEGIN IMMEDIATE")
        try:
            n, synced = sync_entities(conn, cards, locale, default_locale, now, known_ids)
            conn.execute("COMMIT")
            touched += n
            if locale == default_locale:
                known_ids = synced          # 提交后再固化，供后续非 default locale 用
        except Exception:
            conn.execute("ROLLBACK"); raise
    except Exception as exc:
        failures += 1
        print(f"sync {locale}: FAILED {exc}", file=sys.stderr)
        if locale == default_locale:
            break          # default 失败 → known_ids 未建立，非 default 无法继续
if failures:
    sys.exit(1)
```

`config` 新增 `hsjson_locale_config(path) -> (template, default_locale, locales)`，替换 `hsjson_cards_url`。

### 6.3 `db.SCHEMA` 加入 `entity_name`

`entity_name` 表 + 索引并入 `db.SCHEMA`（`init_db` 用 `executescript`，`CREATE TABLE IF NOT EXISTS` 对既有库幂等，等价轻量迁移）。

---

## 7. 边界情况

| 情况 | 处理 |
|---|---|
| 某 locale 该卡无翻译 / `name` 空（归一化后空） | 不落该 `entity_name` 行；**若旧 run 曾有该行则 DELETE**（§6.1 step 3）保持库与源一致。显示侧未来按 default locale 回退（消费层职责；enUS 名非空时 enUS 行即在，见 §4） |
| 某 locale fetch / parse 失败 | 该 locale 事务回滚（其改动全无），其余 locale 继续；命令以非零退出（`sys.exit(1)`）。**default locale 失败则中止**（身份未建立，非 default 无 `entity_id` 可挂） |
| `name_key` 碰撞（两实体归一化到同键，如某 lesser 与 greater 饰品同基名） | 索引非唯一 → 检索返回多行，消费方按 `entity_type`/`trinket_class` 消歧；存储如实记录，不强制唯一 |
| stub 实体（父 spec `ensure_entity` 为未知 card_id 建桩） | 暂无 `entity_name` 行，直到某次 sync 覆盖它；不报错 |
| 非 default locale 出现 default 里没有的 card_id | 跳过 + 计一次告警（身份跨 locale 应一致，出现即数据异常） |
| 重复 run | `UNIQUE(entity_id, locale)` + upsert → 幂等，名称更新到最新 |

---

## 8. 成功标准（本 spec 实现完算「done」的验证点）

1. `init-db` 后库含 `entity_name` 表与 `idx_entity_name_lookup` 索引。
2. `sync-entities` 跑 enUS + zhTW 两 locale 后：每个 BG 英雄 / 饰品在 `entity_name` 有 enUS 与 zhTW 各一行（有翻译时）；`entity.name` 仍只等于 enUS 名。
3. **id → name**：按 `(entity_id, 'zhTW')` 能取到繁体名。
4. **name → entity**：用某英雄繁体名归一化后按 `(locale='zhTW', name_key)` 能查回正确 `entity_id`；用其 enUS 名（大小写 / 全半角变体）同样命中。
5. 归一化单测覆盖 NFKC（全角→半角）、casefold（大小写）、空白折叠。
6. 缺翻译的卡（zhTW 空）：该 locale 无 `entity_name` 行，但其 enUS 行在（enUS 名非空）。归一化后纯空白名同样不落行。
7. 碰撞用例：两实体同 `name_key` → 检索返回两行。
8. 重复 `sync-entities` 幂等（行数 / 内容不翻倍、不漂移）。
9. **Reconcile**：某卡上次有 zhTW 名、这次该 locale 名变空 → 重跑后其 `(entity_id,'zhTW')` 行被删除。
10. **原子 / 失败**：某 locale fetch 失败时其改动全回滚、其余 locale 仍提交、命令非零退出；default locale 失败则非 default 不执行。
11. **stale-stub 守卫**：库里预存一个 enUS run 未覆盖的 stub / 历史 `entity`（其 `card_id` 不在本次 enUS cards 中），跑 zhTW 后**不会**给它挂上 `entity_name` 行（只计告警）。
12. 全部测试用**录制 fixture**（新增 `hsjson_cards_zhTW.json`），无 live network。

---

## 9. Out of Scope（明确不做）

- 检索 / 显示的查询 API、overlay 集成、OCR / 客户端语言探测。
- 简体 `zhCN` 及其他语言（加 `locales` 一行即可，不在本批）。
- 模糊 / 子串 / 拼写容错匹配（本批只做归一化精确匹配）。
- 名称之外的本地化资源（flavor text、技能描述、图片等）。
- 改动 `fetch-stats` 统计抓取链路。

---

## 10. 待实现时确认的开放点（非阻塞，记录在案）

- `name_key` 是否需在 DB 层加 `NOCASE`/自定义 collation：当前结论**否**，归一化在 Python 侧完成，DB 只存已归一化键、走二进制等值。
- 非 default locale 缺 `card_id` 的告警是否需要单独退出码：当前结论**否**，与父 spec 一致仅打印告警，不计入失败退出码（数据异常非抓取失败）。
