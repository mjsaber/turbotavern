# BG 英雄 / 饰品 Tier 数据管线（抓取 + 存储）设计文档

**Date:** 2026-05-31
**Status:** v4（已过 codex review v1-v3 + Stage 0 端点发现，按真实 feed 修订，待 codex re-review）
**Layer:** 新增独立子系统（建议落在 `data-pipeline/` 下），不改现有 macOS / Android 跳过功能任何代码
**Scope:** 只做**数据获取 + 数据存储**。不含数据消费（overlay 检测、展示）、不含对外 API 实现、不含「raw 数值 → 字母 Tier」的转换层 —— 但**存储格式必须为这些未来场景留好接口**。

> **修订记录**
> - v1 → v2：纳入 codex review 全部 should-fix —— 去重改为「对比最新」而非内容唯一约束、新增 `fetch_state`（持久化 HTTP 校验器）、`region`/`source` 进入唯一键与 latest view、新增 `raw_payload` 原始包留存、补 `content_hash` 规范化契约、补 normalize 校验规则与事务、未知 card_id 建 stub、latest 取数加 tie-break。「按 patch 快照」reframe 为「**按内容变化快照 + patch 作 best-effort 标签**」（见 §2 C4）。
> - v2 → v3：纳入 codex review v2 三项 —— (1) HTTP 校验器（etag/last_modified）**只在 normalize+load 成功后才推进**，校验失败保持 stale 以便下次重新 `200` 拉取（修复「坏 feed 被 304 永久跳过」）；(2) snapshot 变化判定的 `content_hash` **纳入 feed 级 provenance**（patch + 原始 schema 指纹），原始包留存声明收窄为「每个 stat/provenance 变化的 snapshot」；(3) load 用 SQLite `BEGIN IMMEDIATE` 串行化，杜绝并发重复 snapshot。
> - v3 → v3.1：纳入 codex review v3 末项 —— 整个 `fetch-stats` run 加**全局 run lock（flock 锁文件）**，保证同一时刻只有一个 run，杜绝两个重叠 run 乱序 commit 把 `fetch_state` 推回旧值（见 §4.3）。
> - v3.1 → v4：**Stage 0 端点发现（2026-05-31）按真实 Firestone feed 修订**。关键事实：①英雄按 MMR 分 URL 文件，饰品**不分**（饰品 MMR 数据嵌在每条目的 `averagePlacementAtMmr` 里）；②**fetch 单元 = URL，snapshot 单元 = 维度** —— 饰品一次 URL fetch 产出 5 个分段 snapshot，故 `fetch_state` 改为**按 URL 主键**（只存 HTTP 校验器），去重改为**查该维度最新 snapshot 的 content_hash**（不再把 hash 存进 fetch_state）；③字段名按真实数据（英雄 `heroStats`/`averagePosition`，饰品 `trinketStats`/`averagePlacement`）；④时间段实际值 `last-patch`/`past-three`/`past-seven`；⑤`trinket_class`（lesser/greater）**stats feed 没有**，改从 HearthstoneJSON 的 `spellSchool`（`LESSER_TRINKET`/`GREATER_TRINKET`）取，落在 `sync-entities`。

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
- **HearthstoneJSON**：`https://api.hearthstonejson.com/v1/latest/enUS/cards.json`（数组，每个元素一张卡）
  - 提供 `dbfId`、`id`(cardId)、`name`、`type`、`set`、`spellSchool`。
  - **BG 英雄筛选**（Stage 0 已确认）：`type == "HERO"` 且 `set` 含 `BATTLEGROUNDS`。
  - **饰品筛选 + lesser/greater**（Stage 0 已确认）：`type == "BATTLEGROUND_TRINKET"`；`spellSchool == "LESSER_TRINKET"` → `trinket_class='lesser'`，`"GREATER_TRINKET"` → `'greater'`。**`trinket_class` 由此身份源提供**（stats feed 里没有），在 `sync-entities` 写入 `entity`。
  - **图片 URL 形态**：`https://art.hearthstonejson.com/v1/256x/{cardId}.jpg`，存进 `entity.image_url`。

### 3.2 统计源（raw 数值 → 未来转 tier）— Stage 0 已确认的真实端点
- **主源：Firestone 公开统计 JSON**（`static.zerotoheroes.com` 的 S3，App 开源 `Zero-to-Heroes/firestone`）。`.gz.json` 文件名但**服务器返回纯 JSON**（HTTP 层处理压缩），带 **ETag + Last-Modified**（条件请求可用）。遵守其 ToS（个人非商用）。
- **英雄**（按 MMR 分 URL 文件）：
  - `https://static.zerotoheroes.com/api/bgs/hero-stats/mmr-{mmr}/{period}/overview-from-hourly.gz.json`
  - `mmr` ∈ {100,50,25,10,1}（top N%）；`period` ∈ {last-patch, past-three, past-seven}
  - 顶层 `{heroStats:[...], lastUpdateDate, dataPoints, mmrPercentiles}`；条目：`heroCardId`、`dataPoints`、`averagePosition`、`placementDistribution`、`tribeStats`、`totalOffered`、`totalPicked` 等。**一个文件 = 一个 (hero, mmr, period)**。
- **饰品**（URL **不**分 MMR，MMR 数据嵌在条目内）：
  - `https://static.zerotoheroes.com/api/bgs/trinket-stats/{period}/overview-from-hourly.gz.json`
  - 顶层 `{trinketStats:[...], lastUpdateDate, dataPoints, timePeriod}`；条目：`trinketCardId`、`dataPoints`、`pickRate`、`averagePlacement`（注意不是 `averagePosition`）、`averagePlacementAtMmr:[{mmr,dataPoints,placement}]`、`pickRateAtMmr:[{mmr,dataPoints,pickRate}]`。
  - **建模决策（已与用户确认）**：normalize 把 `averagePlacementAtMmr` **展开成每个 mmr 分段一行** → 一次 trinket 文件 fetch 产出 5 个 (trinket, mmr, period) snapshot，schema 与英雄统一，饰品也拿到 MMR 分段。
- **次源 / 未来交叉校验：HSReplay.net** —— 内部接口带签名参数（`hgd` token），更脆。**本 spec 不实现**，仅 schema 用 `source` 列预留多源。

### 3.3 已知数据口径（重要，影响判读，非阻塞实现）
- **更新频率**：官方未文档化。`overview-from-hourly` 暗示小时级聚合；实测 `Last-Modified` 为当天（见上）。→ 不依赖猜测频率，靠**条件请求 + 内容去重**（§4）。
- **服务器区域**：**不按暴雪区服切分**，是 Firestone 全球用户聚合（NA/EU 为主）。分桶维度只有 MMR + 时间段，无 region。→ schema 留 `region` 列，恒为 `global`。任何公开源都拿不到单一区服 tier。

---

## 4. 抓取机制

**形态：一个独立的 Python CLI，按需 / 定时跑一次，写入本地 SQLite。无常驻服务。**

### 4.1 Pipeline

**关键概念：fetch 单元 = URL，snapshot 单元 = 维度。** 英雄一个 URL = 一个维度 (hero,mmr,period)；饰品一个 URL = 一个 period 文件，normalize 展开成 5 个维度 (trinket,{100,50,25,10,1},period)。

```
1. discover  → 按配置拼出 fetch 任务列表（每个任务 = 一个 URL + 它能产出的维度）
2. fetch     → HTTP GET URL，带条件请求（If-None-Match/If-Modified-Since）
3. normalize → 解析 + 校验，产出该 URL 对应的「维度 → NormalizedFeed」（英雄 1 个，饰品 5 个）
4. load      → 事务写入：每个维度对比其最新 snapshot 的 content_hash，变了才追加
```

- **discover**：从 `sources.yaml` 读 URL 模板 + 分段/时间段矩阵，拼出 fetch 任务：
  - 英雄：`{mmr}×{period}` 个 URL，每个 URL 产出 1 个维度（mmr 来自 URL）。
  - 饰品：`{period}` 个 URL（不含 mmr），每个 URL 产出 5 个维度（mmr 来自条目内 `averagePlacementAtMmr`）。
  - URL 不写死在代码里，放 `sources.yaml`（Stage 0 已填真实模板）。
- **fetch**：从 `fetch_state` 按 **URL** 读上次**成功处理**的 `etag`/`last_modified`，带进条件请求。
  - `304 Not Modified` → 跳过 normalize/load，仅更新该 URL 的 `fetch_state.last_checked_at`。
  - `200` → 进 normalize；**校验器此刻先不落库**，要等 normalize+load 全部成功后才推进（见 load 第 4 步）。
  - 失败指数退避重试（≤3 次）；**单个 URL 失败不拖垮其余**；整体有失败则进程非 0 退出。
  - 把真实 `Last-Modified` 打日志（反推刷新节奏）。
- **normalize + 校验**：解析该 URL 的 JSON → `{mmr_bracket: NormalizedFeed}`。**适配层**，每个 (source, entity_type) 一个 normalizer：
  - 英雄：数组 `heroStats`，id `heroCardId`，`avg_placement←averagePosition`，`data_points←dataPoints`，`placement_distribution←placementDistribution`，pick_rate←`totalPicked/totalOffered`（可空）；产出 `{该URL的mmr: feed}`。
  - 饰品：数组 `trinketStats`，id `trinketCardId`；对每条目的 `averagePlacementAtMmr` 逐 mmr 展开：`avg_placement←placement`、`data_points←该mmr的dataPoints`、`pick_rate←pickRateAtMmr[同mmr]`；产出 `{100:feed,50:feed,25:feed,10:feed,1:feed}`。
  - 未建模字段（`tribeStats`、`standardDeviation` 等）原样进 `extra_json`；整份原始响应体存 `raw_payload`（§5.5）。
  - **校验（任一不过 → 拒绝整个 URL 的全部维度，不发布、不推进校验器）**：**URL 级数组非空**（`heroStats`/`trinketStats`）、行数 ≤ 上限；每行 `card_id` + `avg_placement` + `data_points` 必填、类型合法、`1 ≤ avg_placement ≤ 8`、`data_points ≥ 0`；`placement_distribution` 若存在须为长度 8 的列表（真实 feed 是 8 个 `{rank,percentage,totalMatches}` 对象，**原样存储**，不约束元素类型）；同维度内 `card_id` 不重复。
  - **无样本行跳过（Stage 0 实测）**：真实 feed 里 `dataPoints == 0`（且非 bool）的行其 `placement`/`averagePosition` 是 `0` 哨兵（非合法名次 1–8）。**这类行直接跳过、不计入**（不报错、不触发越界校验）。`dataPoints` 为 `False`/非数值仍走类型校验被拒。例如某稀有饰品在 top-1% 分段 `dataPoints==0` → 该饰品在 `mmr_bracket='1'` 维度缺席。
  - **空维度丢弃**：URL 级数组非空已校验；某维度跳过无样本行后可能为空（英雄整份全 0 样本，或饰品某 mmr 分段全 0 样本）→ **该维度不产出**；`load` 也对空 feed 防御性跳过 —— **绝不创建零 stat 行的空 snapshot**。
- **load（事务，`BEGIN IMMEDIATE`）**：一个 URL 的全部维度 + 该 URL 的 `fetch_state` 更新放进**一个** `BEGIN IMMEDIATE` 事务（SQLite 立即取写锁，串行化）：
  1. 对每个维度算 `content_hash`（含 provenance，契约见 §5.6）。
  2. 查该维度**最新 snapshot 的 `content_hash`**（`SELECT … ORDER BY fetched_at DESC, snapshot_id DESC LIMIT 1`）；**相同 → 跳过**该维度；**不同 → 追加** 一个 `snapshot` + 一批 `entity_stats` + 一条 `raw_payload`。
  3. 未知 card_id 即时建 stub `entity`（§4.4）。
  4. **全部维度处理成功后**，推进该 URL 的 `etag`/`last_modified` 到 `fetch_state`（无论是否有新 snapshot）。任何异常 → 回滚整个事务，校验器保持 stale（下次重新 `200` 重试）。
  - **只追加，从不 update/delete `snapshot`/`entity_stats`**。A→B→A 也作为新 snapshot 记录（去重只比「该维度最新」，不比全历史，也无内容唯一约束）。
  - 去重对象 = snapshot 表本身（单一事实来源），不在 `fetch_state` 里另存 hash。

### 4.2 子命令
- `init-db`：建表 + view + 索引（§5）。
- `sync-entities`：从 HearthstoneJSON 拉 `cards.json`，upsert `entity` 表（英雄 + 饰品），**饰品的 `trinket_class` 从 `spellSchool` 写入**。低频（每版本跑一次），与 stats 抓取解耦。
- `fetch-stats`：跑上面 4 步 pipeline。

### 4.3 触发 / 调度
- 脚本本身只管「跑一次」，**幂等**（重复跑同一份数据不产生重复 snapshot）。
- **全局 run lock**：整个 `fetch-stats` run 启动时取一把 `flock` 锁文件（如 `data-pipeline/.fetch.lock`），拿不到锁直接退出（上一个 run 还没跑完）。保证同一时刻只有一个 run —— 避免两个重叠 run 各自 fetch 到不同版本后**乱序 commit**，把某维度 snapshot / URL 校验器推回旧值、进而下次重复插入。`BEGIN IMMEDIATE`（§4.1 load）保的是进程内/事务级原子；run lock 保的是 run 之间不重叠，二者互补。
  - 注：本设计假设**单机定时单 run**。若未来要分布式 / 并发抓取，run lock 不够，需改用「事务内单调守卫」—— 记在 §9，当前 out of scope。
- 定时交给外部：macOS `launchd` / `cron` / GitHub Actions 定时 workflow，每天 1–2 次。
- 默认抓 `time_period = last-patch`（最贴近当前 meta）；schema 不锁死，可同时抓 past-three / past-seven。

### 4.4 未知 card_id 处理（身份与统计解耦）
统计 feed 可能带回 `entity` 表里还没有的 card_id（尤其新饰品，HearthstoneJSON 未必同步）。
- **策略 = 永不丢统计**：遇未知 card_id，**即时建一条 stub `entity`**（`entity_type` 来自当前 normalizer、`card_id` 已知、`name`/`dbf_id`/`image_url`/`trinket_class` 留空），再写其 stats。
- 下次 `sync-entities` 跑到时回填 stub 的 `name`/`dbf_id`/`image_url`，并从 `spellSchool` 回填饰品 `trinket_class`（upsert by `(entity_type, card_id)`）。

---

## 5. 存储 Schema（SQLite，5 表 + 1 view）

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

### 5.2 `fetch_state` — 每个 **URL** 的 HTTP 校验器缓存（**非历史，可覆盖**）
| 列 | 类型 | 说明 |
|---|---|---|
| `source` | TEXT NOT NULL | `'firestone'` |
| `raw_url` | TEXT NOT NULL | 实际拉取 URL（fetch 单元） |
| `etag` | TEXT | 上次**成功处理**响应的 ETag（校验失败不推进） |
| `last_modified` | TEXT | 上次**成功处理**响应的 Last-Modified |
| `last_checked_at` | TEXT NOT NULL | 最近一次 fetch 时间（含 304） |

约束：`PRIMARY KEY(source, raw_url)`。
> **为什么按 URL 而非维度**：饰品一个 URL 产出 5 个维度 snapshot，但条件请求的 ETag 是 per-URL 的。按 URL 存校验器最自然（一个 URL 一行），避免把同一 ETag 复制到 5 个维度行。
> **去重不在这里**：内容去重对比的是「该维度最新 snapshot 的 `content_hash`」（snapshot 表是单一事实来源），不在 fetch_state 存 hash。
> **校验器只在成功处理后推进**（§4.1 load 第 4 步）：坏响应校验失败时保持 stale，下次重新 `200` 重试，不会被 `304` 永久跳过。

### 5.3 `snapshot` — 一次**内容变化**的入库 = 一个数据版本（append-only）
| 列 | 类型 | 说明 |
|---|---|---|
| `snapshot_id` | INTEGER PK | |
| `source` | TEXT NOT NULL | `'firestone'`（留多源） |
| `entity_type` | TEXT NOT NULL | `'hero'` \| `'trinket'` |
| `mmr_bracket` | TEXT NOT NULL | MMR 百分位桶：`'100'`(全部)/`'50'`/`'25'`/`'10'`/`'1'`，数字=top N%（饰品由 `averagePlacementAtMmr` 展开得到同样的桶） |
| `time_period` | TEXT NOT NULL | `'last-patch'`/`'past-three'`/`'past-seven'` |
| `mode` | TEXT NOT NULL | 预留，默认 `'solo'` |
| `region` | TEXT NOT NULL | 预留，默认 `'global'` |
| `patch` | TEXT | 游戏版本号，best-effort，可空 |
| `source_last_modified` | TEXT | 入库时的 HTTP Last-Modified，溯源 |
| `content_hash` | TEXT NOT NULL | normalize 后内容 hash（§5.6） |
| `fetched_at` | TEXT NOT NULL | 我方抓取时间（ISO） |
| `raw_url` | TEXT NOT NULL | 实际拉取 URL，溯源 |

约束：**无内容唯一约束**（A→B→A 须可重现）。去重在应用层用「该维度最新 snapshot 的 `content_hash`」完成。

### 5.4 `entity_stats` — raw 数值，每个 entity × snapshot 一行（append-only）
| 列 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER PK | |
| `snapshot_id` | INTEGER NOT NULL | FK → `snapshot` |
| `entity_id` | INTEGER NOT NULL | FK → `entity` |
| `avg_placement` | REAL NOT NULL | 英雄 `averagePosition` / 饰品 `averagePlacement`（或某 mmr 的 `placement`），**核心强弱指标，越低越强**（CHECK 1–8） |
| `data_points` | INTEGER NOT NULL | 样本量，**置信度闸门**（CHECK ≥ 0） |
| `pick_rate` | REAL | 可空，人气（非强弱） |
| `placement_distribution` | TEXT(JSON) | `[p1..p8]` 名次分布（英雄有；饰品分段行可空） |
| `extra_json` | TEXT(JSON) | 源里暂不建模字段原样留底（`tribeStats`、`standardDeviation` 等） |

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
2. **provenance 部分**：附上 `patch`（若 feed 提供，当前 Firestone feed 不直接给，多为 NULL）+ **原始响应 schema 指纹**（源 JSON 顶层 + 行级字段名的有序集合），使字段集漂移能触发新 snapshot。
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
- **饰品选择偏差**：`avg_placement` 混入「谁在选」的信息（高分玩家更会选）。Firestone 饰品 feed 不单独给 delta/impact；若未来需要，原始包全留可回溯重算。
- **分段选择**：英雄 / 饰品强弱在高分段（top10%/top1%）更有参考价值；Tier 层默认取较高分段那份。**饰品分段来自 `averagePlacementAtMmr` 展开**，与英雄同维度可比。

> 本节落库依据：schema 已能支撑上述全部演进 —— raw 全保留（含 `raw_payload` 全量原始包）、`extra_json` 行级兜底、`v_latest_stats` 提供稳定查询面。

---

## 7. 成功标准（本 spec 实现完算「done」的验证点）

1. `init-db` 后，5 表（entity / fetch_state / snapshot / entity_stats / raw_payload）+ view + 索引按 §5 建出，schema 可移植（纯标准 SQL）。
2. `sync-entities` 能从 HearthstoneJSON 拉到英雄 + 可识别饰品并 upsert，重复跑不产生重复行。
3. `fetch-stats` 能从配置 URL 拉到英雄（每 mmr×period 一文件）+ 饰品（每 period 一文件），normalize + 校验通过后写入 snapshot + stats + raw_payload；**饰品一个文件展开成 5 个分段 snapshot**。
4. **幂等**：同一份未变数据再跑一次，不新增 snapshot。两条路径：(a) `304` → 仅更新该 URL `fetch_state.last_checked_at`，校验器不变；(b) `200` 但该维度最新 snapshot 的 `content_hash` 相同 → 不插 snapshot，但**成功处理后推进** URL 的 `etag`/`last_modified`。
5. 数据变化后再跑，**追加**新 snapshot，旧 snapshot 保留；内容回退（A→B→A）也能追加记录。
6. `v_latest_stats` 对每个 (source, entity_type, mmr_bracket, time_period, mode, region) 返回唯一最新行（有确定性 tie-break）。
7. 未知 card_id 时建 stub entity 且统计不丢；后续 `sync-entities` 能回填 stub（含饰品 `trinket_class`）。
8. 单个 URL 失败不影响其他 URL 入库；坏响应（空/缺核心字段/非法值）被拒绝、不推进校验器、不污染 latest。
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

1. ✅ **Firestone URL / 字段 / lesser-greater**（原 1/2/6）：Stage 0（2026-05-31）已确认，见 §3.1/§3.2，真实模板写进 `sources.yaml`。
2. **`data_points` 闸门阈值 N**：属未来 Tier 层，本 spec 不定。
3. **抓取分段 / 时间段组合清单**：默认 `last-patch` × {100,50,25,10,1}（英雄按 URL，饰品按展开）；可加 past-three/past-seven。
4. **子系统目录名 / 包管理**：`data-pipeline/`，用 `uv`。
5. **并发模型升级**：当前用单机全局 run lock（§4.3）。若将来改分布式/并发抓取，需换成「事务内单调守卫」——当前 out of scope。
