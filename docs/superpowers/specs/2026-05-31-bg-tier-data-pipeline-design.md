# BG 英雄 / 饰品 Tier 数据管线（抓取 + 存储）设计文档

**Date:** 2026-05-31
**Status:** v3.1（已过 codex review v1+v2+v3，待用户 review）
**Layer:** 新增独立子系统（建议落在 `data-pipeline/` 下），不改现有 macOS / Android 跳过功能任何代码
**Scope:** 只做**数据获取 + 数据存储**。不含数据消费（overlay 检测、展示）、不含对外 API 实现、不含「raw 数值 → 字母 Tier」的转换层 —— 但**存储格式必须为这些未来场景留好接口**。

> **修订记录**
> - v1 → v2：纳入 codex review 全部 should-fix —— 去重改为「对比最新」而非内容唯一约束、新增 `fetch_state`（持久化 HTTP 校验器）、`region`/`source` 进入唯一键与 latest view、新增 `raw_payload` 原始包留存、补 `content_hash` 规范化契约、补 normalize 校验规则与事务、未知 card_id 建 stub、latest 取数加 tie-break。「按 patch 快照」reframe 为「**按内容变化快照 + patch 作 best-effort 标签**」（见 §2 C4）。
> - v2 → v3：纳入 codex review v2 三项 —— (1) HTTP 校验器（etag/last_modified）**只在 normalize+load 成功后才推进**，校验失败保持 stale 以便下次重新 `200` 拉取（修复「坏 feed 被 304 永久跳过」）；(2) snapshot 变化判定的 `content_hash` **纳入 feed 级 provenance**（patch + 原始 schema 指纹），原始包留存声明收窄为「每个 stat/provenance 变化的 snapshot」；(3) load 用 SQLite `BEGIN IMMEDIATE` 串行化，杜绝并发重复 snapshot。
> - v3 → v3.1：纳入 codex review v3 末项 —— 整个 `fetch-stats` run 加**全局 run lock（flock 锁文件）**，保证同一时刻只有一个 run，杜绝两个重叠 run 乱序 commit 把 `fetch_state` 推回旧值（见 §4.3）。

---

## 1. Why（动机 + 目标场景）

未来 Bob 助手要在游戏里**感知到英雄 / 饰品被 offer 时，在 overlay 显示对应 tier 辅助选择**。这需要一份自有的、可持续更新的 BG 英雄 / 饰品强弱数据。

本 spec 只负责**把原始数据搞进一个数据库**，并保证存储格式对得起未来的使用方式：

- 未来 app 会**联网通过 API** 从这个库取数据，更新进自己的 App。
- 未来会**另加一层**把连续数值（如平均名次）转成字母 Tier（S/A/B/C/D）。

> 明确的非目标（Out of Scope，见 §8）：API 实现、Tier 转换层、overlay 集成。本 spec 交付的是「抓取脚本 + SQLite 库 + schema」。

---

## 2. 关键约束（已与用户确认）

| # | 决策 | 取舍 |
|---|---|---|
| C1 | 实体范围 = BG **英雄 + 饰品（trinkets）** | 不含 minions / quests / anomalies（schema 用 `entity_type` 留扩展） |
| C2 | **只存 raw 连续数值**，Tier 转换是未来另一层 | schema 围绕 raw metric 设计，不存字母 tier |
| C3 | Tier 维度 = **按 MMR 分段细分** | mode（Solo/Duos）预留维度，当前默认 `solo` |
| C4 | 时间维度 = **按内容变化打快照 + 保留历史**，只追加 | 原「按 patch 快照」reframe：feed 内容一变就存一份新 snapshot（含 patch 内调整都能抓到）；`patch` 作 best-effort 标签，可空。查询取最新 snapshot |
| C5 | 存储 = **SQLite 单文件** | schema 保持可移植（不用 SQLite 专属特性），未来可迁移到联网 DB |
| C6 | 核心强弱指标 = **`avg_placement`（平均名次，越低越强）** | `data_points` 作置信度闸门；`pick_rate` 是人气非强弱 |

---

## 3. 数据源

核心思路：**把「身份」与「数值」拆成两个源** —— 统计源都以 cardId 为主键，overlay 检测到的也是 cardId，所以需要一张稳定的身份表把名字 / 图片对应上。

### 3.1 身份主表源（identity + 显示资源）
- **HearthstoneJSON**：`https://api.hearthstonejson.com/v1/latest/enUS/cards.json`
  - 提供 `dbfId`、`id`(cardId)、`name`、`type`、`set`。
  - **BG 英雄筛选**：`type == "HERO"` 且带 BG 专属字段（如 `battlegroundsHero` / 出现在 BG hero set）。实现「端点发现」时锁定确切判据并写进 normalizer。
  - **饰品筛选**：饰品较新；若 cards.json 筛不干净，则以统计源 feed 带回的 trinket cardId 为准反查补齐（见 §4.4 未知 card_id 处理）。
  - **图片 URL 形态**：`https://art.hearthstonejson.com/v1/256x/{cardId}.jpg`（具体尺寸路径实现时确认；存进 `entity.image_url`）。

### 3.2 统计源（raw 数值 → 未来转 tier）
- **主源：Firestone 公开统计 JSON**（托管在 `static.zerotoheroes.com` 的 S3，App 开源 `Zero-to-Heroes/firestone`）
  - 一个体系覆盖**英雄 + 饰品**，带 `mmrPercentile` 分段桶、时间段、`averagePosition` + `dataPoints` + 名次分布。最契合「先存 raw」。
  - 字段（来自 Firestone wiki）：`heroCardId`、`dataPoints`、`averagePosition`、`placementDistribution`、`tribeStats`、`mmrPercentile`、`timePeriod`。
  - ⚠️ 确切 URL 官方未文档化 —— 见 §4.1「端点发现」。使用遵守其 ToS（个人非商用）。
- **次源 / 未来交叉校验：HSReplay.net**（BG 英雄 + 饰品 greater/lesser 页面，带 rank bracket）
  - 内部接口带签名参数（`hgd` token），更脆。**本 spec 不实现**，仅在 schema 用 `source` 列预留多源。

### 3.3 已知数据口径（重要，影响判读，非阻塞实现）
- **更新频率**：官方未文档化。实测量级为「**天级**」（聚合结果定期重建上传 S3，不是分钟级实时）。→ 不依赖猜测的频率，靠**条件请求 + 内容去重**（§4）。
- **服务器区域**：**不按暴雪区服（美/欧/亚）切分**，是 Firestone 全体用户对局的**全球聚合**（NA/EU 为主，亚服占比偏低）。分桶维度只有 MMR + 时间段，没有 region。→ schema 留 `region` 列，当前恒为 `global`。任何公开源都拿不到「单一国家服务器」的 tier，这是客观限制。

---

## 4. 抓取机制

**形态：一个独立的 Python CLI，按需 / 定时跑一次，写入本地 SQLite。无常驻服务。**

### 4.1 Pipeline（4 步，每步职责单一、可单独测试）

```
1. discover  → 按配置拼出 Firestone 当前有效的 stats feed URL（英雄 + 饰品 × 各分段/时间段）
2. fetch     → HTTP GET，带条件请求（If-None-Match / If-Modified-Since），更新 fetch_state
3. normalize → 把 Firestone JSON 摊平成统一 raw-metric 行 + 校验（适配层）
4. load      → 事务写入 SQLite：对比最新 snapshot 的 content_hash，变了才追加
```

- **discover（端点发现）**：Firestone stats URL 未官方文档化。
  - 实现第一步（**独立 spike，带 pass/fail gate**，见 §9-1）：抓 Firestone App / `firestoneapp.com/battlegrounds` 的网络请求，得到 URL pattern（含 `mmrPercentile` / `timePeriod` 占位），写进 `sources.yaml`。**URL 不写死在代码里**，放配置；Firestone 改路径只改配置。spike 失败（拿不到稳定 URL）则在动后续实现前先暴露。
  - discover 步按配置 + 要抓的 (entity_type, mmr_bracket, time_period) 组合拼出 URL 列表，每个 URL 对应一个「feed 维度键」`(source, entity_type, mmr_bracket, time_period, mode, region)`。
- **fetch**：`httpx`/`requests`，从 `fetch_state` 读该 feed 维度键上次**成功处理**的 `etag` / `last_modified`，带进条件请求。
  - `304 Not Modified` → 该 feed 跳过 normalize/load，仅更新 `fetch_state.last_checked_at`。
  - `200` → 进 normalize；**校验器（`etag`/`last_modified`）此刻先不落库**，要等 normalize+load 成功（或确认无变化）后才推进（见 load）。
  - **关键（修复坏 feed 被永久 304 跳过）**：`200` 拿到的响应若 normalize **校验失败**，则**不推进** `etag`/`last_modified` —— 保持上次成功的值（或为空）。这样下次条件请求用的是旧校验器，服务器会再回 `200` 让我们重试，而不是 `304` 把这份从未成功入库的数据永久跳过。
  - 失败指数退避重试（≤3 次）；失败不写库、该 feed 标记失败并继续其他 feed（**单个 bracket 失败不拖垮其余**），整体有失败时进程非 0 退出（方便定时器告警）。
  - 首次实现把真实 `Last-Modified` 打日志，反推实际刷新节奏。
- **normalize + 校验**：把源 JSON 摊成统一行（见 §5.4）。**这是适配层** —— 未来接 HSReplay 只是再写一个 normalizer，输出同样行格式。英雄、饰品各一个 normalizer，落到同一张 `entity_stats`（`entity_type` 区分）。源里暂不建模的字段（`tribeStats`、可能的 trinket delta/impact）**原样塞进 `extra_json`**；整份原始响应体另存 `raw_payload`（见 §5.5）。
  - **校验规则（任一不过 → 拒绝该 feed 的 snapshot，不发布）**：feed 非空；行数在合理范围；每行 `card_id` + `avg_placement` + `data_points` 必填且类型/区间合法（`1 ≤ avg_placement ≤ 8`、`data_points ≥ 0`）；`placement_distribution` 若存在须为长度 8 的数值数组；同一 feed 内 `card_id` 不重复；字段集相对上次无重大 schema 漂移（缺核心字段则告警并拒绝）。
- **load（事务，`BEGIN IMMEDIATE`）**：整段读-比-写在一个 `BEGIN IMMEDIATE` 事务内完成（SQLite 立即取写锁 → 同一 feed 的并发 run 被串行化，杜绝两个 run 同时读到旧 hash 各插一份重复 snapshot）：
  1. 算本 feed 规范化后的 `content_hash`（**含 feed 级 provenance**，契约见 §5.6）。
  2. 读 `fetch_state.last_content_hash`；**相同 → 跳过插入**（视为「无变化」）；**不同 → 追加** 一个新 `snapshot` + 一批 `entity_stats` + 一条 `raw_payload`，并把 `last_content_hash` / `last_snapshot_id` 写回 `fetch_state`。
  3. **成功结束事务后**，才把本次的 `etag` / `last_modified` 推进写入 `fetch_state`（无论有无新 snapshot —— 「无变化」也是一次成功处理）。校验失败/异常 → 回滚事务，校验器保持 stale。
  - **只追加，从不 update/delete `snapshot`/`entity_stats`**（历史保留）。内容回到旧值（A→B→A）也会作为新 snapshot 记录（去重只对比「最新」，不对比全历史，故不会被唯一约束误拒）。

### 4.2 子命令
- `init-db`：建表 + view + 索引（§5）。
- `sync-entities`：从 HearthstoneJSON 拉 `cards.json`，upsert `entity` 表（英雄 + 能识别的饰品）。低频（每个新版本跑一次），与 stats 抓取解耦。
- `fetch-stats`：跑上面 4 步 pipeline。

### 4.3 触发 / 调度
- 脚本本身只管「跑一次」，**幂等**（重复跑同一份数据不产生重复 snapshot）。
- **全局 run lock**：整个 `fetch-stats` run 启动时取一把 `flock` 锁文件（如 `data-pipeline/.fetch.lock`），拿不到锁直接退出（上一个 run 还没跑完）。保证同一时刻只有一个 run —— 避免两个重叠 run 各自 fetch 到不同版本后**乱序 commit**，把 `fetch_state.last_content_hash` / 校验器推回旧值、进而下次重复插入。`BEGIN IMMEDIATE`（§4.1 load）保的是进程内/事务级原子；run lock 保的是 run 之间不重叠，二者互补。
  - 注：本设计假设**单机定时单 run**。若未来要分布式 / 并发抓取，run lock 不够，需改用「事务内单调守卫」（存 `last_processed_fetched_at`，事务内拒绝比当前 feed head 更旧的响应）—— 记在 §9，当前 out of scope。
- 定时交给外部：macOS `launchd` / `cron` / GitHub Actions 定时 workflow，每天 1–2 次。
- 默认抓 `time_period = last-patch`（最贴近当前 meta）；schema 不锁死，可同时抓 past-3 / past-7。

### 4.4 未知 card_id 处理（身份与统计解耦）
统计 feed 可能带回 `entity` 表里还没有的 card_id（尤其新饰品，HearthstoneJSON 未必同步）。
- **策略 = 永不丢统计**：遇未知 card_id，**即时建一条 stub `entity`**（`entity_type` 来自当前 normalizer、`card_id` 已知、`name`/`dbf_id`/`image_url` 留空），再写其 stats。
- 下次 `sync-entities` 跑到时回填 stub 的 `name`/`dbf_id`/`image_url`（upsert by `(entity_type, card_id)`）。

---

## 5. 存储 Schema（SQLite，4 表 + 1 view）

保持可移植：不用 SQLite 专属特性；JSON 字段用 TEXT 存，原始包用 BLOB。所有枚举/必填列加 `NOT NULL`，外键加 FK，关键区间加 `CHECK`（在 SQLite 可移植范围内）。

### 5.1 `entity` — 身份主表
| 列 | 类型 | 说明 |
|---|---|---|
| `entity_id` | INTEGER PK | 内部自增 |
| `entity_type` | TEXT NOT NULL | `'hero'` \| `'trinket'`（CHECK 限定） |
| `card_id` | TEXT NOT NULL | 稳定 join key（overlay 检测到的也是它） |
| `dbf_id` | INTEGER | HearthstoneJSON 数字 ID，可空 |
| `name` | TEXT | 显示名，可空（stub 时空） |
| `image_url` | TEXT | overlay 用图，可空 |
| `trinket_class` | TEXT | 饰品的 `lesser`/`greater`；英雄为 NULL |
| `first_seen_at` | TEXT NOT NULL | ISO，首次入库 |
| `last_seen_at` | TEXT NOT NULL | ISO，最近一次出现在源中 |

约束：`UNIQUE(entity_type, card_id)`。

### 5.2 `fetch_state` — 每个 feed 维度键的可变抓取状态（**非历史，可覆盖**）
| 列 | 类型 | 说明 |
|---|---|---|
| `source` | TEXT NOT NULL | `'firestone'` |
| `entity_type` | TEXT NOT NULL | `'hero'` \| `'trinket'` |
| `mmr_bracket` | TEXT NOT NULL | 见 §5.3 |
| `time_period` | TEXT NOT NULL | |
| `mode` | TEXT NOT NULL | 默认 `'solo'` |
| `region` | TEXT NOT NULL | 默认 `'global'` |
| `raw_url` | TEXT NOT NULL | 实际拉取 URL |
| `etag` | TEXT | 上次**成功处理**响应的 ETag（校验失败不推进） |
| `last_modified` | TEXT | 上次**成功处理**响应的 Last-Modified |
| `last_content_hash` | TEXT | 上次**已入库**内容 hash（去重对比对象，含 provenance，见 §5.6） |
| `last_snapshot_id` | INTEGER | 指向最近一次入库 snapshot |
| `last_checked_at` | TEXT NOT NULL | 最近一次 fetch 时间（含 304） |

约束：`PRIMARY KEY(source, entity_type, mmr_bracket, time_period, mode, region)`。
> 作用：HTTP 校验器与「上次内容 hash」与 snapshot 解耦 —— 即使本次 `200` 但内容没变（不插 snapshot），只要处理成功 ETag 仍被刷新，避免反复全量下载。
> **校验器只在成功处理后推进**（见 §4.1 load 第 3 步）：坏 feed 校验失败时校验器保持 stale，下次重新 `200` 重试，不会被 `304` 永久跳过。

### 5.3 `snapshot` — 一次**内容变化**的入库 = 一个数据版本（append-only）
| 列 | 类型 | 说明 |
|---|---|---|
| `snapshot_id` | INTEGER PK | |
| `source` | TEXT NOT NULL | `'firestone'`（留多源） |
| `entity_type` | TEXT NOT NULL | `'hero'` \| `'trinket'` |
| `mmr_bracket` | TEXT NOT NULL | MMR 百分位桶：`'100'`(全部)/`'50'`/`'25'`/`'10'`/`'1'`，数字=top N% |
| `time_period` | TEXT NOT NULL | `'last-patch'`/`'past-3'`/`'past-7'` |
| `mode` | TEXT NOT NULL | 预留，默认 `'solo'` |
| `region` | TEXT NOT NULL | 预留，默认 `'global'` |
| `patch` | TEXT | 游戏版本号，best-effort，可空 |
| `source_last_modified` | TEXT | 入库时的 HTTP Last-Modified，溯源 |
| `content_hash` | TEXT NOT NULL | normalize 后内容 hash（§5.6） |
| `fetched_at` | TEXT NOT NULL | 我方抓取时间（ISO） |
| `raw_url` | TEXT NOT NULL | 实际拉取 URL，溯源 |

约束：**无内容唯一约束**（A→B→A 须可重现）。去重在应用层用 `fetch_state.last_content_hash` 完成。可选防呆 `UNIQUE(source, entity_type, mmr_bracket, time_period, mode, region, fetched_at)` 防同一时刻重复写。

### 5.4 `entity_stats` — raw 数值，每个 entity × snapshot 一行（append-only）
| 列 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER PK | |
| `snapshot_id` | INTEGER NOT NULL | FK → `snapshot` |
| `entity_id` | INTEGER NOT NULL | FK → `entity` |
| `avg_placement` | REAL NOT NULL | `averagePosition`，**核心强弱指标，越低越强**（CHECK 1–8） |
| `data_points` | INTEGER NOT NULL | 样本量，**置信度闸门**（CHECK ≥ 0） |
| `pick_rate` | REAL | 可空，人气（非强弱） |
| `placement_distribution` | TEXT(JSON) | `[p1..p8]` 名次分布 |
| `extra_json` | TEXT(JSON) | 源里暂不建模字段原样留底（`tribeStats`、可能的 trinket delta 等） |

约束：`UNIQUE(snapshot_id, entity_id)`。

### 5.5 `raw_payload` — 每个 snapshot 的原始响应体（1:1，全量留底）
| 列 | 类型 | 说明 |
|---|---|---|
| `snapshot_id` | INTEGER PK | FK → `snapshot`（1:1） |
| `body_gzip` | BLOB NOT NULL | gzip 压缩的原始 feed JSON 字节 |
| `content_encoding` | TEXT NOT NULL | 如 `'gzip'` |
| `byte_size` | INTEGER NOT NULL | 原始未压缩字节数 |

> 理由：feed 无官方文档，normalize 可能漏建模 source-level 字段 / 算错。留全量原始包 → 任何字段日后都可回溯重算，**不必重抓**（契合「先只存原始数据」原则）。压缩存，单文件不至于膨胀过快。
> **留存边界（诚实声明）**：`raw_payload` 与 snapshot 1:1，即「每个 **stat 或 provenance 变化** 的 snapshot 都留一份全量原始包」。若某次 `200` 的统计内容与 feed provenance 都与上次相同（仅服务器无关元数据抖动），则不新增 snapshot/原始包 —— 上一份原始包仍在，不构成有意义的数据丢失。由于变化判定已纳入 patch + schema 指纹（见 §5.6），patch 切换、字段集漂移等**有意义**的 feed 级变化都会触发新 snapshot 并留包。

### 5.6 `content_hash` 规范化契约（保证跨实现幂等一致）
hash 的输入 = **统计内容 + feed 级 provenance**，**不含我方易变元数据**（不含 `fetched_at` / etag / url / 我方落库时间）。步骤固定：
1. **统计部分**：取 normalize 后的行集合，按 `card_id` 升序排序；每行只取参与内容的字段（`card_id`、`avg_placement`、`data_points`、`pick_rate`、`placement_distribution`、`extra_json`）；数值规范化（浮点统一保留固定小数位，如 6 位；`null` 显式化）。
2. **provenance 部分**：附上 `patch`（若 feed 提供）+ **原始响应 schema 指纹**（源 JSON 顶层 + 行级字段名的有序集合），使 patch 切换 / 字段集漂移能触发新 snapshot。
3. 把 1+2 合成一个对象，以 `sort_keys=True`、无空白的紧凑 JSON 序列化；`sha256` 十六进制。
> 该契约写进代码注释与测试，避免不同实现/重构导致 hash 漂移。注意：源里**无关的易变元数据**（如服务器时间戳）应在 normalize 时剔除，不进 hash，否则会制造无意义 snapshot。

### 5.7 索引
- `entity_stats(entity_id)`
- `snapshot(source, entity_type, mmr_bracket, time_period, mode, region, fetched_at)` —— 支撑「取最新 snapshot」

### 5.8 `v_latest_stats` — 便捷 view（给未来 API / Tier 层）
对每个 `(source, entity_type, mmr_bracket, time_period, mode, region)` 取最新 snapshot（排序 **`fetched_at DESC, snapshot_id DESC`** 取首，`snapshot_id` 做确定性 tie-break），join 出 `entity` + `entity_stats`。未来 API 直接 `SELECT`，不必关心历史表结构。

---

## 6. 核心指标与未来 Tier 层的衔接（仅设计说明，不在本 spec 实现）

- **`avg_placement` 是转 Tier 的主轴**（英雄 / 饰品同理）。Tier 层在它上面切阈值，例如（数值待真实数据校准）：S `<4.0` / A `4.0–4.3` / B `4.3–4.6` / C `4.6–4.9` / D `>4.9`。
- **`data_points` 作置信度闸门**：样本太少（如 `< N` 场）的实体不参与排名 / 标灰，避免冷门实体名次抖动。
- **`pick_rate` 不参与 Tier**（人气 ≠ 强弱），顶多展示。
- **饰品选择偏差**：`avg_placement` 混入「谁在选」的信息。更干净的信号是 delta/impact（带 vs 不带的名次差）；Firestone 对英雄有（`tribeStats`），饰品 feed 不一定单独给。→ 若 feed 里有，先落 `extra_json` / `raw_payload`；未来 Tier 层若确认其存在且更准，再提升为正式列（如 `placement_delta`）。**因 raw 全留，改阈值 / 改指标都不必重抓。**
- **分段选择**：饰品强弱在高分段（top10%/top1%）更有参考价值；Tier 层默认取较高分段那份。

> 本节落库依据：schema 已能支撑上述全部演进 —— raw 全保留（含 `raw_payload` 全量原始包）、`extra_json` 行级兜底、`v_latest_stats` 提供稳定查询面。

---

## 7. 成功标准（本 spec 实现完算「done」的验证点）

1. `init-db` 后，4 表 + view + 索引按 §5 建出，schema 可移植（纯标准 SQL）。
2. `sync-entities` 能从 HearthstoneJSON 拉到英雄 + 可识别饰品并 upsert，重复跑不产生重复行。
3. `fetch-stats` 能从配置的 Firestone URL 拉到英雄 + 饰品统计，normalize + 校验通过后写入一个 snapshot + stats 行 + raw_payload。
4. **幂等**：同一份未变数据再跑一次，不新增 snapshot（命中 `304` 或 `last_content_hash` 相同），但 `fetch_state` 元数据被刷新。
5. 数据变化后再跑，**追加**新 snapshot，旧 snapshot 保留；内容回退（A→B→A）也能追加记录。
6. `v_latest_stats` 对每个 (source, entity_type, mmr_bracket, time_period, mode, region) 返回唯一最新行（有确定性 tie-break）。
7. 未知 card_id 时建 stub entity 且统计不丢；后续 `sync-entities` 能回填 stub。
8. 单个 feed（bracket）失败不影响其他 feed 入库；坏 feed（空/缺核心字段/非法值）被拒绝、不污染 latest。
9. normalize / load / content_hash / 去重 / stub / 校验 均有单元测试（喂固定 JSON fixture 断言）；网络层用录制 fixture，不依赖真实网络跑测试。

---

## 8. Out of Scope（明确不做）

- ❌ 对外 API / HTTP 服务实现（未来 app 取数那层）。
- ❌ raw 数值 → 字母 Tier 的转换层（未来另加；本 spec 只保证 schema 支撑）。
- ❌ overlay 检测 / 展示 / 游戏内集成。
- ❌ HSReplay 抓取实现（仅 schema 预留多源）。
- ❌ Duos / 分区服数据（仅 schema 预留 `mode` / `region` 维度）。
- ❌ 联网数据库迁移（SQLite 起步，schema 保持可移植）。

---

## 9. 待实现时确认的开放点（非阻塞，记录在案）

1. **Firestone 确切 URL pattern** —— 实现第一步「端点发现」作为**独立 spike，带 pass/fail gate**：拿不到稳定 URL 就先停下来重评数据源，再动后续实现。产出写进 `sources.yaml`。
2. **饰品 feed 字段形态**：trinkets 是否单独 endpoint、是否带 delta/impact、`lesser`/`greater` 如何标识 —— 端点发现时一并确认。
3. **`data_points` 闸门阈值 N**：属未来 Tier 层，本 spec 不定。
4. **抓取分段 / 时间段组合清单**：默认 `last-patch` × {100,50,25,10,1}；最终清单实现时按 feed 实际可用项定。
5. **子系统目录名 / 包管理**：建议 `data-pipeline/`，用 `uv`；实现时与现有仓库结构对齐后定。
6. **HearthstoneJSON BG 英雄 / 饰品确切筛选判据 + 图片 URL 尺寸路径**：端点发现/实现时锁定。
7. **并发模型升级**：当前用单机全局 run lock（§4.3）。若将来改分布式/并发抓取，需换成「事务内单调守卫」（`last_processed_fetched_at` 拒绝乱序旧响应）——当前 out of scope。
