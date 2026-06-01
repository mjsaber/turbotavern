# BG Hero-Select Tier Overlay — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax. **Per-stage gate:** every stage ends with a Codex review
> (`codex exec review --uncommitted` or a focused `codex exec` over the new files); fix until
> no Critical/Should-fix, then proceed.

**Goal:** Show an S/A/B/C tier badge over each hero during BG hero-select, by OCR-ing names
and looking them up in a bundled tier table, inside the existing `overlay-app`.

**Architecture:** Self-contained `com.bobassist.phase0.herotier` module on its own
`HandlerThread`. Pure-logic core (normalize, match, tier table, badge layout, trigger) is
unit-tested with no device. Android-framework edges (MediaProjection grabber, OCR engine,
overlay windows, coordinator) sit behind interfaces with fakes. The OCR engine is chosen by a
Spike-A bake-off (PP-OCRv5 primary vs ML Kit baseline; spec §6.1, Appendix A). A Python exporter
in `data-pipeline` produces the bundled asset. Two device-gated spikes (OCR accuracy; trigger
signal) are user-executed. Spec: `docs/superpowers/specs/2026-06-01-bg-hero-tier-overlay-design.md` (v4).

**Status:** v4 (Codex plan-review round-2 + OCR-engine research addressed; see Appendix A).

**Tech Stack:** Kotlin (JVM 17, minSdk 29, no Compose), JUnit4 + Robolectric 4.13, on-device OCR
engine selected by the Spike-A bake-off (PP-OCRv5 mobile via LiteRT/ncnn primary; ML Kit bundled
text-recognition baseline — spec §6.1, Appendix A), MediaProjection; Python 3.12 + `uv` + pytest.

**Stage order:** Stages 0–6 are device-independent and fully unit-tested — build/review first.
Stages 7–8 are framework wiring (behind interfaces, light Robolectric). Stage 9 is
user-executed device spikes + manual gates that finalize the trigger path and fuzzy
constants. Combat-fingerprint + trigger interface are codeable before Spike B; only the
*select-open predicate* is filled in after it.

---

## Shared types (created in Stage 2, used throughout)
```kotlin
// com/bobassist/phase0/herotier/Types.kt
package com.bobassist.phase0.herotier
import android.graphics.Bitmap
import android.graphics.Rect

enum class Tier { S, A, B, C }
data class HeroTier(val cardId: String, val tier: Tier)
data class OcrLine(val text: String, val box: Rect, val confidence: Float? = null) // box in CAPTURE px
data class HeroBadge(val cardId: String, val tier: Tier, val box: Rect)            // box in CAPTURE px

/** Capture→screen mapping. Capture buffer is sized to the display's CURRENT orientation, so
 *  mapping is pure scale+offset (no in-plane rotation). `rotationDeg` tags the frame so the
 *  coordinator can drop a frame whose orientation no longer matches at render time. */
data class Transform(val scaleX: Float, val scaleY: Float, val offsetX: Int, val offsetY: Int)
data class Frame(val bitmap: Bitmap, val captureW: Int, val captureH: Int,
                 val transform: Transform, val rotationDeg: Int)
```
**Rotation design (addresses plan-review C3):** we do NOT rotate coordinates. The
`VirtualDisplay` is sized to the current `WindowMetrics` bounds and `resize()`d on config
change, so each captured buffer is already in the live display orientation → capture↔screen is
scale+offset only. `Frame.rotationDeg` is the display rotation at capture; the coordinator
**discards** any frame whose `rotationDeg` differs from the current rotation when it is about
to render (stale-rotation guard, tested in Stage 8). This is correct and simpler than rotating
a same-orientation buffer.

---

## Stage 0: Tier exporter (data-pipeline, Python)

**Files:** Create `data-pipeline/src/bgtiers/export_tiers.py`,
`data-pipeline/tests/test_export_tiers.py`.

**Goal:** Spec §10 — read SQLite → emit `herotier_v1.json`; percentile tiers; zhTW→enUS
fallback; single `(mode,region)` assertion.

- [ ] **Step 1: Write failing tests** (real schema: `entity` needs `first_seen_at`/
  `last_seen_at` NOT NULL; `snapshot` needs `content_hash`/`fetched_at`/`raw_url` NOT NULL):
```python
# data-pipeline/tests/test_export_tiers.py
import pytest
from bgtiers import db, export_tiers

def _seed(conn, rows, mode="solo", region="global"):
    """rows: list of (card_id, en, zh|None, avg)."""
    db.init_db(conn)
    conn.execute("BEGIN IMMEDIATE")
    snap = conn.execute(
        "INSERT INTO snapshot (source, entity_type, mmr_bracket, time_period, mode, region,"
        " content_hash, fetched_at, raw_url) VALUES"
        " ('firestone','hero','100','last-patch',?,?,'h','t','u')", (mode, region)).lastrowid
    for cid, en, zh, avg in rows:
        eid = conn.execute(
            "INSERT INTO entity (entity_type, card_id, name, first_seen_at, last_seen_at)"
            " VALUES ('hero',?,?,'t','t')", (cid, en)).lastrowid
        if zh is not None:
            conn.execute("INSERT INTO entity_name (entity_id, locale, name, name_key)"
                         " VALUES (?,?,?,?)", (eid, 'zhTW', zh, zh))
        conn.execute("INSERT INTO entity_stats (snapshot_id, entity_id, avg_placement,"
                     " data_points) VALUES (?,?,?,?)", (snap, eid, avg, 5000))
    conn.execute("COMMIT")

def test_percentile_and_fallback():
    conn = db.connect(":memory:")
    rows = [(f"BG_HERO_{i:03d}", f"En{i}", (f"中{i}" if i != 4 else None), 3.0 + 0.2*i)
            for i in range(10)]
    _seed(conn, rows)
    out = export_tiers.build(conn, generated_at="2026-06-01T00:00:00Z")
    assert out["bracket"] == "100" and out["period"] == "last-patch"
    by = {h["cardId"]: h for h in out["heroes"]}
    assert by["BG_HERO_000"]["tier"] == "S"                      # p=0.05 < 0.12
    assert by["BG_HERO_009"]["tier"] == "C"                      # p=0.95
    assert by["BG_HERO_004"]["names"]["zhTW"] == "En4"           # zhTW fallback to enUS
    assert by["BG_HERO_000"]["names"]["enUS"] == "En0"
    # cut math: p=(i+0.5)/10 → S only for i=0 (0.05); i=1 is 0.15 ≥ 0.12 → A
    assert sum(1 for h in out["heroes"] if h["tier"] == "S") == 1

def test_rejects_multiple_mode_region():
    conn = db.connect(":memory:")
    _seed(conn, [("BG_HERO_001", "En1", "中1", 3.5)])
    _seed(conn, [("BG_HERO_002", "En2", "中2", 3.6)], region="eu")   # 2nd region
    with pytest.raises(ValueError, match="mode/region"):
        export_tiers.build(conn)
```

- [ ] **Step 2: Run, verify fail** — `cd data-pipeline && uv run pytest tests/test_export_tiers.py -q`.

- [ ] **Step 3: Implement** `export_tiers.py`:
```python
"""Export bundled hero-tier asset (spec §10). Read-only over the SQLite."""
from __future__ import annotations
import datetime as dt
import json

_CUTS = (("S", 0.12), ("A", 0.35), ("B", 0.68), ("C", 1.01))

def _tier(p: float) -> str:
    for name, hi in _CUTS:
        if p < hi:
            return name
    return "C"

def build(conn, *, generated_at: str | None = None) -> dict:
    distinct = [tuple(r) for r in conn.execute(
        "SELECT DISTINCT mode, region FROM v_latest_stats WHERE entity_type='hero'"
        " AND source='firestone' AND mmr_bracket='100' AND time_period='last-patch'").fetchall()]
    if distinct != [("solo", "global")]:
        raise ValueError(f"expected single (solo,global) mode/region, got {distinct}")
    rows = conn.execute(
        "SELECT v.card_id AS card_id, v.name AS en_name, en.name AS zh_name,"
        "       v.avg_placement AS avg"
        " FROM v_latest_stats v"
        " JOIN entity e ON e.card_id=v.card_id AND e.entity_type=v.entity_type"
        " LEFT JOIN entity_name en ON en.entity_id=e.entity_id AND en.locale='zhTW'"
        " WHERE v.entity_type='hero' AND v.source='firestone' AND v.mmr_bracket='100'"
        "   AND v.time_period='last-patch' AND v.mode='solo' AND v.region='global'"
        " ORDER BY v.avg_placement ASC, v.card_id ASC").fetchall()
    n = len(rows)
    if n == 0:
        raise ValueError("no hero rows for bracket=100 last-patch")
    heroes = []
    for i, r in enumerate(rows):
        p = (i + 0.5) / n
        zh = r["zh_name"] if r["zh_name"] is not None else r["en_name"]
        heroes.append({"cardId": r["card_id"], "tier": _tier(p),
                       "names": {"zhTW": zh, "enUS": r["en_name"]}})
    return {"schemaVersion": 1, "bracket": "100", "period": "last-patch",
            "generatedAt": generated_at or dt.datetime.now(dt.timezone.utc)
                .strftime("%Y-%m-%dT%H:%M:%SZ"),
            "heroes": heroes}

def main(argv=None):
    import argparse
    from . import db
    ap = argparse.ArgumentParser(prog="export-tiers")
    ap.add_argument("--db", default="bgtiers.db"); ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    data = build(db.connect(a.db))
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"wrote {len(data['heroes'])} heroes -> {a.out}")

if __name__ == "__main__":
    main()
```
- [ ] **Step 4: Pass.** — `uv run pytest tests/test_export_tiers.py -q`.
- [ ] **Step 5: Commit** — `feat(data-pipeline): hero-tier exporter`.
- [ ] **Step 6: Codex review** `codex exec review --uncommitted`; fix until clean.

---

## Stage 1: NameKey + shared parity vectors

**Files:** Create `.../herotier/NameKey.kt`, `.../test/.../herotier/NameKeyTest.kt`;
canonical `data-pipeline/tests/fixtures/namekey_vectors.json`; mirror
`android/overlay-app/app/src/test/resources/namekey_vectors.json` (byte-identical);
add tests to `data-pipeline/tests/test_localize.py` + a vectors-sync test.

**Parity scope (addresses plan-review S3):** parity is defined by the shared vector set,
which stays within Latin + CJK. Kotlin `lowercase()` equals Python `casefold()` over that set.
**Full Unicode casefold is a documented non-goal** (e.g. `ß`→`ss` in Python, `ß` in Kotlin) —
hero names never contain such codepoints. The **lone-surrogate** case cannot be encoded in a
UTF-8 JSON file, so it is a separate hardcoded test in each suite (Kotlin `"\uD800"`, Python
`"\ud800"`), asserting both normalize it to "".

- [ ] **Step 1: vectors file (canonical + mirror, identical bytes)** — encodable cases only:
```json
[
  {"in": "Sneed", "out": "sneed"},
  {"in": "  Patches the Pirate ", "out": "patches the pirate"},
  {"in": "A.F.Kay", "out": "a.f.kay"},
  {"in": "N'Zoth", "out": "n'zoth"},
  {"in": "斯尼德", "out": "斯尼德"},
  {"in": "『深沉絕望』尤格薩倫", "out": "『深沉絕望』尤格薩倫"},
  {"in": "ＳＮＥＥＤ", "out": "sneed"},
  {"in": "foo\t bar\n baz", "out": "foo bar baz"}
]
```
- [ ] **Step 2: failing Kotlin test**
```kotlin
package com.bobassist.phase0.herotier
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class NameKeyTest {
    @Test fun matchesSharedVectors() {
        val arr = JSONArray(javaClass.classLoader!!.getResource("namekey_vectors.json")!!.readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertEquals("vec $i: ${o.getString("in")}", o.getString("out"), NameKey.of(o.getString("in")))
        }
    }
    @Test fun dropsLoneSurrogate() = assertEquals("", NameKey.of("\uD800"))
    @Test fun keepsValidSupplementary() =                       // 😀 U+1F600 survives NFKC+lowercase
        assertEquals("😀", NameKey.of("😀"))
}
```
- [ ] **Step 3: fail.**
- [ ] **Step 4: implement**
```kotlin
package com.bobassist.phase0.herotier
import java.text.Normalizer

/** Parity with data-pipeline localize.normalize_name_key over the Latin+CJK vector set.
 *  NFKC → lowercase → drop lone surrogates → collapse whitespace → trim. No punctuation strip.
 *  Non-goal: full Unicode casefold (ß etc.) — absent from BG hero names. */
object NameKey {
    fun of(raw: String?): String {
        if (raw == null) return ""
        val n = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        var i = 0
        while (i < n.length) {
            val c = n[i]
            when {
                c.isHighSurrogate() && i + 1 < n.length && n[i + 1].isLowSurrogate() -> {
                    sb.append(c); sb.append(n[i + 1]); i += 2
                }
                c.isHighSurrogate() || c.isLowSurrogate() -> i += 1   // lone surrogate → drop
                else -> { sb.append(c); i += 1 }
            }
        }
        return sb.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
```
- [ ] **Step 5: pass** — `cd android/overlay-app && ./gradlew :app:testDebugUnitTest --tests '*NameKeyTest*'`.
- [ ] **Step 6: Python parity + lone-surrogate tests** in `test_localize.py`:
```python
def test_namekey_shared_vectors():
    import json, pathlib
    from bgtiers.localize import normalize_name_key
    vecs = json.loads((pathlib.Path(__file__).parent / "fixtures" / "namekey_vectors.json").read_text("utf-8"))
    for v in vecs:
        assert normalize_name_key(v["in"]) == v["out"], v["in"]

def test_namekey_lone_surrogate():
    from bgtiers.localize import normalize_name_key
    assert normalize_name_key("\ud800") == ""
```
Run `uv run pytest tests/test_localize.py -q`.
- [ ] **Step 7: vectors-sync test** (in `test_export_tiers.py` or new `test_vectors.py`):
```python
def test_namekey_vectors_mirror_in_sync():
    import pathlib
    root = pathlib.Path(__file__).resolve().parents[2]
    a = (root / "data-pipeline/tests/fixtures/namekey_vectors.json").read_bytes()
    b = (root / "android/overlay-app/app/src/test/resources/namekey_vectors.json").read_bytes()
    assert a == b, "namekey vectors drifted"
```
- [ ] **Step 8: Commit + Codex review.**

---

## Stage 2: Types + TierTable (ambiguity-safe)

**Files:** Create `.../herotier/Types.kt` (block above), `.../herotier/TierTable.kt`,
`.../test/.../herotier/TierTableTest.kt`; test resource `.../test/resources/herotier_test.json`.

**Goal (addresses plan-review C2):** `nameKey → set of cardIds`. `lookup` returns a single
`HeroTier` only when the key maps to exactly **one** cardId; an ambiguous key (two different
heroes sharing a normalized name) returns **null** — never a wrong badge.

- [ ] **Step 1: failing tests**
```kotlin
class TierTableTest {
    private fun load(res: String) =
        TierTable.fromJson(javaClass.classLoader!!.getResource(res)!!.readText())
    @Test fun looksUpBothLocales() {
        val t = load("herotier_test.json")             // Sneed S (zhTW 斯尼德), Mirok B (米羅克)
        assertEquals(Tier.S, t.lookup(NameKey.of("斯尼德"))!!.tier)
        assertEquals("BG_HERO_001", t.lookup(NameKey.of("Sneed"))!!.cardId)
        assertNull(t.lookup(NameKey.of("not a hero")))
    }
    @Test fun ambiguousKeyReturnsNull() {
        // herotier_ambig.json: two different cardIds whose enUS both normalize to "twin"
        val t = load("herotier_ambig.json")
        assertNull(t.lookup(NameKey.of("Twin")))
        assertTrue(t.keys().contains(NameKey.of("Twin")))   // key present, but ambiguous → no match
    }
    @Test fun sameHeroBothLocalesNotAmbiguous() {
        val t = load("herotier_test.json")
        assertEquals(t.lookup(NameKey.of("Sneed"))!!.cardId, t.lookup(NameKey.of("斯尼德"))!!.cardId)
    }
}
```
`herotier_test.json`: heroes Sneed (S, names zhTW 斯尼德/enUS Sneed, BG_HERO_001) and
米羅克/Mirok (B, BG_HERO_002). `herotier_ambig.json`: BG_X tier A names enUS "Twin"; BG_Y
tier C names enUS "Twin".

- [ ] **Step 2: fail. Step 3: implement**
```kotlin
package com.bobassist.phase0.herotier
import org.json.JSONObject

class TierTable private constructor(private val byName: Map<String, HeroTier?>) {
    /** null when the key is ambiguous (multiple cardIds) or absent. */
    fun lookup(nameKey: String): HeroTier? = byName[nameKey]
    fun keys(): Set<String> = byName.keys
    val size get() = byName.size

    companion object {
        fun fromJson(json: String): TierTable {
            val acc = HashMap<String, MutableSet<String>>()        // key -> distinct cardIds
            val tierOf = HashMap<String, HeroTier>()               // cardId -> HeroTier
            val heroes = JSONObject(json).getJSONArray("heroes")
            for (i in 0 until heroes.length()) {
                val h = heroes.getJSONObject(i)
                val cid = h.getString("cardId")
                tierOf[cid] = HeroTier(cid, Tier.valueOf(h.getString("tier")))
                val names = h.getJSONObject("names")
                for (loc in names.keys()) {
                    val k = NameKey.of(names.getString(loc))
                    if (k.isNotEmpty()) acc.getOrPut(k) { HashSet() }.add(cid)
                }
            }
            val resolved = HashMap<String, HeroTier?>()
            for ((k, cids) in acc) resolved[k] = if (cids.size == 1) tierOf[cids.first()] else null
            return TierTable(resolved)
        }
    }
}
```
*(`lookup` returns null for both absent keys and ambiguous keys — `keys()` lets the matcher's
fuzzy scan still see ambiguous keys without ever resolving them.)*
- [ ] **Step 4: pass. Step 5: commit + Codex review.**

---

## Stage 3: HeroMatcher + bounded Levenshtein

**Files:** Create `.../herotier/Levenshtein.kt`, `.../herotier/HeroMatcher.kt`;
test `LevenshteinTest.kt`, `HeroMatcherTest.kt`; test resource `herotier_match.json`.

**Goal:** Spec §7.2 — exact / short-name-exact-only / fuzzy with ambiguity margin / dedup.
`herotier_match.json` heroes: Sneed (S, BG_HERO_001), Patches the Pirate (A, BG_HERO_002),
米羅克 (B, BG_HERO_003), **Brann (B, BG_HERO_005)** and **Brawn (C, BG_HERO_006)** — an
edit-distance-1 twin pair that makes the ambiguity-reject test real: OCR `"Bramn"` (len 5,
cap=1) is distance 1 from *both* `brann` and `brawn` (margin 0 < `ambigMargin` 2) → must reject.

- [ ] **Step 1: Levenshtein contract tests**
```kotlin
class LevenshteinTest {
    @Test fun zeroForEqual() = assertEquals(0, Levenshtein.distance("abc", "abc", 9))
    @Test fun oneEdit() = assertEquals(1, Levenshtein.distance("abc", "abd", 9))
    @Test fun boundedReturnsCapPlusOneWhenExceeded() =
        assertEquals(3, Levenshtein.distance("abcdef", "uvwxyz", 2))   // bound=2 → returns >bound as bound+1
    @Test fun emptyStrings() = assertEquals(3, Levenshtein.distance("", "abc", 9))
}
```
- [ ] **Step 2: HeroMatcher failing tests** (concrete, no placeholders)
```kotlin
class HeroMatcherTest {
    private val table = TierTable.fromJson(
        javaClass.classLoader!!.getResource("herotier_match.json")!!.readText())
    private val m = HeroMatcher(table)
    private fun ln(s: String) = OcrLine(s, Rect(0, 0, 10, 10))

    @Test fun exact() = assertEquals("BG_HERO_001", m.match(listOf(ln("Sneed"))).single().cardId)
    @Test fun fuzzyRecoversOneOff() =                                   // "Patches the Pircte" d=1
        assertEquals("BG_HERO_002", m.match(listOf(ln("Patches the Pircte"))).single().cardId)
    @Test fun shortNameNoFuzzy() =                                     // "米羅" 2-char, not exact
        assertTrue(m.match(listOf(ln("米羅"))).isEmpty())
    @Test fun shortNameExactStillMatches() =
        assertEquals("BG_HERO_003", m.match(listOf(ln("米羅克"))).single().cardId)
    @Test fun ambiguousRejected() =                                   // d=1 to BOTH brann & brawn → reject
        assertTrue(m.match(listOf(ln("Bramn"))).isEmpty())
    @Test fun twinExactStillResolves() =                              // exact wins over fuzzy ambiguity
        assertEquals("BG_HERO_005", m.match(listOf(ln("Brann"))).single().cardId)
    @Test fun nonHeroDropped() = assertTrue(m.match(listOf(ln("Choose Your Hero"))).isEmpty())
    @Test fun dedupKeepsOne() =
        assertEquals(1, m.match(listOf(ln("Sneed"), ln("sneed"))).size)
}
```
*(`Bramn` exercises the `(b2 - b1) >= ambigMargin` reject branch with a genuine tie — not a
bounded-sentinel "no match". `twinExactStillResolves` confirms exact lookup precedes fuzzy.)*

- [ ] **Step 3: fail. Step 4: implement**
```kotlin
// Levenshtein.kt
package com.bobassist.phase0.herotier
object Levenshtein {
    /** Edit distance; if it would exceed [bound], returns bound+1 (early exit). */
    fun distance(a: String, b: String, bound: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > bound) return bound + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > bound) return bound + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
```
```kotlin
// HeroMatcher.kt
package com.bobassist.phase0.herotier
class HeroMatcher(
    private val table: TierTable,
    private val shortLen: Int = 3,
    private val fuzzyCap: Int = 2,
    private val fuzzyRatio: Double = 0.2,
    private val ambigMargin: Int = 2,
) {
    fun match(lines: List<OcrLine>): List<HeroBadge> {
        val out = LinkedHashMap<String, HeroBadge>()
        for (ln in lines) {
            val k = NameKey.of(ln.text)
            if (k.isEmpty()) continue
            val ht = resolve(k) ?: continue
            out.getOrPut(ht.cardId) { HeroBadge(ht.cardId, ht.tier, ln.box) }
        }
        return out.values.toList()
    }
    private fun resolve(k: String): HeroTier? {
        table.lookup(k)?.let { return it }                 // exact (already ambiguity-safe)
        if (k.length <= shortLen) return null              // short → exact-only
        val cap = minOf(fuzzyCap, Math.floor(fuzzyRatio * k.length).toInt())
        if (cap <= 0) return null
        var b1 = Int.MAX_VALUE; var b2 = Int.MAX_VALUE; var best: String? = null
        for (key in table.keys()) {
            val d = Levenshtein.distance(k, key, cap)
            if (d < b1) { b2 = b1; b1 = d; best = key } else if (d < b2) b2 = d
        }
        if (best != null && b1 <= cap && (b2 - b1) >= ambigMargin) return table.lookup(best)
        return null                                        // table.lookup(best) may itself be null if ambiguous
    }
}
```
- [ ] **Step 5: pass. Step 6: commit + Codex review.** Constants are named params; Stage 9
  tunes them under the zero-wrong-badge gate without code edits.

---

## Stage 4: BadgeLayout (scale+offset)

**Files:** Create `.../herotier/BadgeLayout.kt`; test `BadgeLayoutTest.kt`.

**Goal:** Spec §9.3 — the SINGLE capture→screen transform; scale+offset only (rotation handled
by capture orientation + Stage-8 stale guard, see Shared types).

- [ ] **Step 1: failing tests**
```kotlin
class BadgeLayoutTest {
    @Test fun centersAboveNameBox() {
        val t = Transform(scaleX = 2f, scaleY = 2f, offsetX = 0, offsetY = 10)
        val nameBox = Rect(100, 200, 160, 224)                 // capture px (w=60,h=24)
        val r = BadgeLayout.place(nameBox, t, badgePx = 40, gapPx = 6)
        // screen name box: x[200,320], y[410,458]; center x=260; badge 40 wide → [240,280]
        assertEquals(240, r.left); assertEquals(280, r.right)
        // top = screenTop(410) - gap(6) - badge(40) = 364
        assertEquals(364, r.top); assertEquals(404, r.bottom)
    }
    @Test fun unitScaleNoOffset() {
        val r = BadgeLayout.place(Rect(0, 100, 50, 120), Transform(1f,1f,0,0), 20, 4)
        assertEquals(76, r.top)                                 // 100 - 4 - 20
    }
}
```
- [ ] **Step 2: fail. Step 3: implement**
```kotlin
package com.bobassist.phase0.herotier
import android.graphics.Rect
object BadgeLayout {
    fun place(box: Rect, t: Transform, badgePx: Int, gapPx: Int): Rect {
        val left = (box.left * t.scaleX).toInt() + t.offsetX
        val right = (box.right * t.scaleX).toInt() + t.offsetX
        val top = (box.top * t.scaleY).toInt() + t.offsetY
        val cx = (left + right) / 2
        val bTop = top - gapPx - badgePx
        return Rect(cx - badgePx / 2, bTop, cx - badgePx / 2 + badgePx, bTop + badgePx)
    }
}
```
- [ ] **Step 4: pass. Step 5: commit + Codex review.**

---

## Stage 5: SelectPhaseTrigger + combat fingerprint

**Files:** Create `.../herotier/SelectPhaseTrigger.kt`, `.../herotier/Transition.kt`,
`.../herotier/CombatFingerprint.kt`; tests `SelectPhaseTriggerTest.kt`,
`CombatFingerprintTest.kt`. Reuse connection-JSON shape from existing `BattleConnection` tests.

**Goal:** Spec §8.1 — pure edge detector with a pluggable open predicate; an **exit-on-combat**
helper matching the real fingerprint `host=="" ∧ network=="tcp" ∧ destinationPort∈{1119,3724}`.

- [ ] **Step 1: failing tests**
The real connection snapshot is a **top-level JSON array of flat objects** (`host`,
`network`, `destinationPort`, `id`, `destinationIp`, `createdAt`) — exactly what
`core/BattleConnection.pickWithCount` parses. To guarantee the fingerprint never drifts,
`CombatFingerprint` **delegates to `BattleConnection.pickWithCount`** rather than
re-implementing the filter.
```kotlin
class CombatFingerprintTest {
    private fun arr(host: String, net: String, port: Int) =
        """[{"host":"$host","network":"$net","destinationPort":$port,"id":"x","createdAt":1}]"""
    @Test fun matchesBattleSocket() = assertTrue(CombatFingerprint.present(arr("", "tcp", 1119)))
    @Test fun matchesPort3724() = assertTrue(CombatFingerprint.present(arr("", "tcp", 3724)))
    @Test fun rejectsResolvedHost() = assertFalse(CombatFingerprint.present(arr("blizzard.com","tcp",1119)))
    @Test fun rejectsUdp() = assertFalse(CombatFingerprint.present(arr("", "udp", 1119)))
    @Test fun rejectsOtherPort() = assertFalse(CombatFingerprint.present(arr("", "tcp", 443)))
    @Test fun emptyArray() = assertFalse(CombatFingerprint.present("[]"))
}
class SelectPhaseTriggerTest {
    @Test fun risingThenFalling() {
        val t = SelectPhaseTrigger(isOpen = { it.contains("OPEN") })
        assertEquals(Transition.None,  t.update("idle"))
        assertEquals(Transition.Enter, t.update("OPEN"))
        assertEquals(Transition.None,  t.update("OPEN"))     // stays open
        assertEquals(Transition.Exit,  t.update("idle"))
        assertEquals(Transition.Enter, t.update("OPEN"))     // re-open
    }
}
```
- [ ] **Step 2: fail. Step 3: implement**
```kotlin
// Transition.kt
package com.bobassist.phase0.herotier
enum class Transition { Enter, Exit, None }
```
```kotlin
// CombatFingerprint.kt — delegate to the existing kill-path filter so it cannot drift.
package com.bobassist.phase0.herotier
import com.bobassist.phase0.core.BattleConnection
object CombatFingerprint {
    fun present(connectionsJson: String): Boolean =
        BattleConnection.pickWithCount(connectionsJson).first != null
}
```
```kotlin
// SelectPhaseTrigger.kt
package com.bobassist.phase0.herotier
class SelectPhaseTrigger(private val isOpen: (String) -> Boolean) {
    private var open = false
    fun update(connectionsJson: String): Transition {
        val now = isOpen(connectionsJson)
        return when {
            now && !open -> { open = true; Transition.Enter }
            !now && open -> { open = false; Transition.Exit }
            else -> Transition.None
        }
    }
}
```
*(The real `isOpen` is wired in Stage 9.4 after Spike B: a connection predicate that opens on
the select signature and treats `CombatFingerprint.present` as a forced close — or, if no
select signature exists, the visual-probe path supersedes this in the coordinator.)*
- [ ] **Step 4: pass. Step 5: commit + Codex review.**

---

## Stage 6: TierOverlay (per-badge touch-through windows)

**Files:** Create `.../herotier/TierOverlay.kt`, `.../herotier/BadgeView.kt`,
`.../herotier/OpacityCap.kt`; test `TierOverlayTest.kt` (Robolectric).

**Goal:** Spec §9.2. **API (single, per Nit 1):** `show(badges: List<HeroBadge>, place:
(HeroBadge)->Rect)` where `place` yields **screen-space** Rects (the coordinator supplies a
`BadgeLayout`-backed lambda; the overlay never transforms). **Injection seam (per S7; no
Mockito — the project has none):** a narrow `WindowHost` interface wraps the three
WindowManager calls we use, so tests record calls with a hand fake.
```kotlin
interface WindowHost {                                          // production delegates to WindowManager
    fun add(view: View, p: WindowManager.LayoutParams)
    fun remove(view: View)
}
```
`TierOverlay(host: WindowHost, context: Context, opacityCap: () -> Float)`.

- [ ] **Step 1: failing tests** (JUnit; no Robolectric needed for the recording test — a fake
  host records LayoutParams; `BadgeView` construction needs a Context, so run under
  `RobolectricTestRunner` only for that):
```kotlin
class FakeWindowHost : WindowHost {
    val added = mutableListOf<WindowManager.LayoutParams>()
    var removeCount = 0
    override fun add(view: View, p: WindowManager.LayoutParams) { added += p }
    override fun remove(view: View) { removeCount++ }
}

@RunWith(RobolectricTestRunner::class)
class TierOverlayTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun overlay(host: WindowHost, cap: Float) = TierOverlay(host, ctx, opacityCap = { cap })
    private fun badge(c: String) = HeroBadge("BG_$c", Tier.A, Rect(0,0,10,10))

    @Test fun showAddsOnePerBadge() {
        val h = FakeWindowHost(); overlay(h, 0.5f).show(listOf(badge("A"), badge("B"))) { Rect(0,0,40,40) }
        assertEquals(2, h.added.size)
    }
    @Test fun layoutParamsTouchThroughAndAlphaCapped() {
        val h = FakeWindowHost(); overlay(h, 0.5f).show(listOf(badge("A"))) { Rect(0,0,40,40) }
        val p = h.added.single()
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(p.alpha <= 0.5f)
    }
    @Test fun clearRemovesAllIdempotent() {
        val h = FakeWindowHost(); val o = overlay(h, 0.5f); o.show(listOf(badge("A"))) { Rect(0,0,40,40) }
        o.clear(); o.clear(); assertEquals(1, h.removeCount)
    }
    @Test fun showReplacesPreviousSet() {
        val h = FakeWindowHost(); val o = overlay(h, 0.5f)
        o.show(listOf(badge("A"))) { Rect(0,0,40,40) }
        o.show(listOf(badge("B"), badge("C"))) { Rect(0,0,40,40) }
        assertEquals(1, h.removeCount); assertEquals(3, h.added.size)   // old removed, 1+2 added
    }
}
```
- [ ] **Step 2: fail. Step 3: implement** `TierOverlay` holding `MutableList<View>`; `show`
  clears then adds one `BadgeView` per badge at `place(badge)` with LayoutParams
  (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE`,
  `alpha = minOf(1f, opacityCap())`, gravity TOP|START, x/y/w/h from the screen Rect).
  Production `WindowHost` delegates to the real `WindowManager`. `OpacityCap` helper:
  `InputManager.getMaximumObscuringOpacityForTouch()` on API ≥ 31, else `1f`.
  `BadgeView` draws the colored rounded letter (spec §9.4 colors).
- [ ] **Step 4: API-level test** — `@Config(sdk=[30])` asserts `OpacityCap.of(ctx)` returns 1f
  pre-31; `@Config(sdk=[33])` reads the InputManager value (≤ 1f). **Step 5: pass.
  Step 6: commit + Codex review.**

---

## Stage 7: Framework edges — ScreenGrabber, HeroOcr, manifest/gradle, consent

**Files:** Modify `app/build.gradle.kts`, `AndroidManifest.xml`, `BobVpnService.kt`,
`MainActivity.kt`; create `.../herotier/ScreenGrabber.kt` (+`MediaProjectionGrabber`),
`.../herotier/HeroOcr.kt` (interface + baseline `MlKitHeroOcr`).

**Goal:** Spec §5/§6 wiring + a **baseline** OCR impl so the pipeline runs end-to-end on the
device early (needed to capture Spike-A frames). **No automated tests** (framework + models);
covered by Stage 9 gates + the build compiling. All correctness lives in Stages 0–6/8.
**Engine note (spec §6.1):** PP-OCRv5 is the *primary* engine but its native runtime is heavier
to wire, so Stage 7 ships the trivial **ML Kit baseline** first; the **`PaddleHeroOcr`
(PP-OCRv5)** impl is built in Stage 9.1a and the two are A/B'd in 9.2, which selects the
shipped engine. Both satisfy the same `HeroOcr` interface, so nothing else changes.

- [ ] **Step 1: gradle (baseline engine)** — add
  `implementation("com.google.mlkit:text-recognition:<pin>")` and
  `implementation("com.google.mlkit:text-recognition-chinese:<pin>")` (pin current bundled
  versions at impl time). Sync. (PP-OCRv5 runtime deps — LiteRT `org.tensorflow:tensorflow-lite`
  + `tensorflow-lite-gpu`, or a prebuilt ncnn `.aar`/`.so` — are added **for the bake-off** in
  Stage 9.1a, and **removed in 9.2 if PP-OCRv5 loses**.)
- [ ] **Step 2: manifest** — add `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission; service
  `android:foregroundServiceType="specialUse|mediaProjection"`.
- [ ] **Step 3: consent (MainActivity)** — feature toggle launches
  `MediaProjectionManager.createScreenCaptureIntent()`; on `RESULT_OK` start the host service
  with extras `(resultCode, data)`.
- [ ] **Step 4: service** — on consent extras: re-`startForeground(NOTIF_ID, notif,
  FOREGROUND_SERVICE_TYPE_SPECIAL_USE or FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` (guard
  API≥29; the OR-type form needs API≥34 — on 29..33 set type via the manifest + the single
  specialUse arg, and document the minimum tested SDK as the device's Android 15). Build
  `MediaProjection`; register `MediaProjection.Callback.onStop` → teardown + `coordinator.stop()`
  + drop projection type. Create ONE `VirtualDisplay` at `WindowMetrics` size + `ImageReader`.
- [ ] **Step 5: ScreenGrabber** — `interface ScreenGrabber { fun capture(): Frame? }`;
  `MediaProjectionGrabber.capture()` pulls the latest `ImageReader` image → `Bitmap` →
  `Frame(bitmap, captureW, captureH, Transform(displayW/captureW, displayH/captureH, 0,0),
  rotationDeg = display.rotation)`. On config change: `virtualDisplay.resize(newW,newH,dpi)`
  (no second display).
- [ ] **Step 6: HeroOcr interface + baseline `MlKitHeroOcr`** — `interface HeroOcr { fun
  recognize(frame: Frame): List<OcrLine> }`. `MlKitHeroOcr` runs the Latin + Chinese
  recognizers and emits, per block: one `OcrLine` per `Text.Line` (text + boundingBox), **and**
  — for any line whose element count > 1 — one `OcrLine` per `Text.Element` as fallback
  candidates. Boxes returned in **capture pixels** (ML Kit already reports them in input-bitmap
  px = capture px, so no extra mapping). `confidence` left null. Downstream `HeroMatcher`
  dedups overlapping line/element matches by cardId. Init failure → empty + log (degrade).
- [ ] **Step 7: build** — `./gradlew :app:assembleDebug`. **Step 8: commit + Codex review**
  (focus: FGS-type timing across API 29..35, callback teardown, no leaked VirtualDisplay,
  line+element output, the `HeroOcr` seam being engine-swappable).

---

## Stage 8: HeroTierCoordinator + host wiring

**Files:** Create `.../herotier/HeroTierCoordinator.kt`; modify `BobVpnService.kt`; test
`HeroTierCoordinatorTest.kt` (Robolectric + fakes), `IntegrationFactory`-style helper.

**Goal:** Spec §4/§6/§9 — own `HandlerThread("herotier")`; poll `connectionsJson()` → trigger;
on Enter run bounded capture loop; strict foreground gate; render on main handler; close on
Exit/fg-lost/timeout/projection-stop; stale-rotation guard.

- [ ] **Step 1: failing Robolectric tests** — mirror existing `OverlaySessionTapTest` harness:
  `@RunWith(RobolectricTestRunner::class)`, `@LooperMode(LooperMode.Mode.PAUSED)`, drain with
  `shadowOf(htThread.looper).idleFor(ms, MILLISECONDS)` + `shadowOf(Looper.getMainLooper()).idle()`.
  Fakes: `FakeGrabber(frames)`, `FakeOcr(linesPerCall)`, `FakeOverlay(recording show/clear)`,
  injectable `isOpen`, `connectionsJson`, and a **strict foreground supplier**
  `foreground: () -> Foreground` where `Foreground = TRUE | FALSE | UNKNOWN`. Tests:
  - Enter + foreground TRUE + OCR→Sneed ⇒ overlay.show with 1 badge.
  - Empty OCR for k rounds then a hit ⇒ retries then shows (assert ≤ MAX_ATTEMPTS).
  - Exit ⇒ overlay.clear, loop stopped (no further capture calls).
  - **foreground UNKNOWN (usage access missing) at Enter ⇒ never captures, no show.**
  - **foreground FALSE at Enter ⇒ never captures, no show.**
  - foreground TRUE→FALSE mid-window ⇒ clear + stop.
  - MAX_WINDOW_MS elapsed ⇒ clear.
  - projection-stop callback ⇒ stop + clear.
  - **stale-rotation: frame.rotationDeg ≠ current rotation at render ⇒ frame dropped, no badge.**
  - `stop()` ⇒ `removeCallbacksAndMessages(null)` on htHandler; subsequent ticks are no-ops;
    kill-path handler untouched.
- [ ] **Step 2: fail. Step 3: implement** mirroring `OverlaySession` patterns: `@Volatile
  started`; first line of every posted runnable `if (!started) return`; own handler cleared in
  `stop()`. Strict foreground = capture only when supplier returns `TRUE`. Render posts to
  `mainHandler` and re-checks rotation. **Step 4: pass.**
- [ ] **Step 5: StrictForeground mapper (pure, addresses S8)** — the kill-path
  `ForegroundDetector` treats a `null` usage query optimistically (foreground=true); the
  capture gate must NOT. Add a pure mapper + JUnit test (no Robolectric):
```kotlin
enum class Foreground { TRUE, FALSE, UNKNOWN }
object StrictForeground {
    /** null query (usage access missing / no recent events) → UNKNOWN → never capture. */
    fun of(query: String?, target: String): Foreground = when (query) {
        null   -> Foreground.UNKNOWN
        target -> Foreground.TRUE
        else   -> Foreground.FALSE
    }
}
```
```kotlin
class StrictForegroundTest {
    private val hs = "com.blizzard.wtcg.hearthstone"
    @Test fun nullIsUnknown()  = assertEquals(Foreground.UNKNOWN, StrictForeground.of(null, hs))
    @Test fun targetIsTrue()   = assertEquals(Foreground.TRUE,    StrictForeground.of(hs, hs))
    @Test fun otherIsFalse()   = assertEquals(Foreground.FALSE,   StrictForeground.of("com.other", hs))
}
```
  The coordinator captures **only** when the supplier returns `TRUE`; the Stage-8 coordinator
  tests already cover UNKNOWN-at-Enter and FALSE-at-Enter ⇒ no capture.
- [ ] **Step 6: host wiring** — construct in `BobVpnService` when feature enabled + consent;
  feed it `connectionsJson` (same `MihomoCore` source as the kill path) on its own poll; the
  strict foreground supplier is `{ StrictForeground.of(queryForegroundPackage(), HS_PKG) }`;
  stop in service teardown.
- [ ] **Step 7: full test run** `./gradlew :app:testDebugUnitTest`. **Step 8: commit + Codex review.**

---

## Stage 9: Device spikes + manual gates (USER-EXECUTED) — finalizes trigger & constants

**Require the OnePlus 10T + live Hearthstone. I build the harnesses; the user runs them.**

- [ ] **9.1 Spike A harness (I build)** — a debug-only activity
  `com.bobassist.phase0.herotier.debug.OcrProbeActivity` (in `app/src/debug/`). Reads PNGs from
  `/sdcard/Android/data/com.bobassist.phase0/files/probe/*.png`, runs **each registered
  `HeroOcr` engine** + `HeroMatcher` + the bundled `TierTable`, and `Log.i("OcrProbe", ...)` one
  JSON line per (image, engine): `{"file":..,"engine":"mlkit|paddle","ms":..,"lines":[{"text",
  "box":[l,t,r,b],"conf"}],"matches":[{"cardId","tier"}]}`. It **also renders a sanity overlay**
  (spec §6.1): the input frame with each `OcrLine.box` drawn on it (saved as
  `probe/out/<file>.<engine>.png`), so box↔text alignment per engine is visually verifiable —
  this is how we confirm PP-OCRv5's input-space→capture-px box mapping is correct. Launch:
  `adb shell am start -n com.bobassist.phase0/.herotier.debug.OcrProbeActivity`; read:
  `adb logcat -s OcrProbe` and pull `probe/out/`.
- [ ] **9.1a `PaddleHeroOcr` (PP-OCRv5) impl (I build)** — add the PP-OCRv5 mobile pipeline as a
  second `HeroOcr` (spec §6.1). Primary references (both Android PP-OCRv5 ports): LiteRT
  `iFleey/PPOCRv5-Android` (Apache-2.0; FP16 `ocr_det`/`ocr_rec` `.tflite` + `keys_v5.txt`) and
  ncnn `nihui/ncnn-android-ppocrv5`. (`RapidAI/RapidOcrAndroidOnnx` is a mature ONNX-Runtime
  Android OCR reference but **not confirmed as a PP-OCRv5 port** — only use it after verifying
  PP-OCRv5 ONNX model compatibility against `HF PaddlePaddle/PP-OCRv5_mobile_rec`.) Steps:
  - Vendor model files under `app/src/main/assets/ocr/` (det + rec + dict; the **single rec
    model covers Traditional Chinese + English**, so no per-locale model). **Record each file's
    size, source URL, sha256, and license/NOTICE** in `app/src/main/assets/ocr/MODELS.md`.
  - Add the runtime dep/native lib (LiteRT, or a prebuilt ncnn `.so`), **arm64-v8a only**
    (matches the project's `abiFilters`); confirm the `.so` ABI packaging.
  - Pipeline: detect (DBNet) → for each detected line, run rec → `OcrLine(text, box, conf)`.
    **Map detector boxes from the model's preprocessed input space (e.g. letterboxed 640×640)
    back to capture-bitmap pixels** before returning (internal to the impl; spec §6.1 box note).
  - Measure **det→rec p95 latency** on the device; compare against `CAPTURE_INTERVAL_MS` (note:
    PP-OCRv5 is slower than v4 due to its larger dictionary).
  - Init/model-load failure → empty + log (degrade); never crash.
  - This is framework/native code → **no Robolectric test**; correctness is the Spike-A bake-off.
- [ ] **9.2 Spike A bake-off & selection (user runs, I analyze)** — push ≥8 frames/locale to the
  probe dir; run the harness over **both** engines. Compute per engine, **per locale**:
  **recall** (offered heroes resolved to correct cardId), **wrong-badge count**, and median +
  p95 **latency**. **Eligibility:** an engine must meet spec §3 (recall ≥80%, **zero wrong
  badges**) on **both** locales (enUS *and* zhTW) — never select on Traditional Chinese alone.
  Among eligible engines, prefer the one stronger on Traditional Chinese, then lower latency /
  smaller APK. Wire the winner as the production `HeroOcr` in `BobVpnService`; drop the loser's
  deps. **Tune** `HeroMatcher` ctor params + any preprocessing until the gate holds; commit
  tuned defaults + a short results table in the spike note. (Research expectation, to be
  confirmed: PP-OCRv5 wins Traditional Chinese; ML Kit may win English on latency/size.)
- [ ] **9.3 Spike B recording (user)** — record the mihomo connection table across
  queue→hero-select→first-combat with boundary timestamps, using the recorder from commit
  `4ba6c1d` (I confirm/adapt the exact command in `android/overlay-app/scripts/`).
- [ ] **9.4 Decide & wire trigger (I implement from the recording)** — if a clean select
  signature exists, implement `isOpen` for `SelectPhaseTrigger` (+ fixture-JSON unit test, and
  `CombatFingerprint.present` as forced-close). If not, wire the **visual-probe fallback**
  (spec §8.2) in the coordinator with constants `PROBE_MS=2000`, open at `≥2` matches, close
  after `CLOSE_K=3` zero-match probes; add Robolectric probe open/close tests. Codex-review.
- [ ] **9.5 Generate & bundle the real asset (user/online)** — `cd data-pipeline && uv run
  python -m bgtiers.export_tiers --db <live.db> --out
  ../android/overlay-app/app/src/main/assets/herotier_v1.json` against a DB populated by
  `sync-entities` + `fetch-stats`; commit the asset.
- [ ] **9.6 Manual device gates (user)** — spec §11 checklist: overlay permission + projection
  consent; **tap-through (pick a hero with badges shown)**; projection revoke → badges clear,
  inert, no crash; rotation mid-window; the **selected** OCR engine's models load + run for both
  languages. Record pass/fail; fix.
- [ ] **9.7 Final Codex review** of the branch (`codex exec review --uncommitted`); fix until
  clean; mark spec/plan Done.

---

## Self-review
- **Spec coverage:** §5→Stage7; §6→Stage8 (strict gate) + existing detector; §7→Stages1+3;
  §8→Stage5 + 9.4; §9→Stages4+6+8; §10→Stage0 + 9.5; §11→per-stage + 9.6; §3→Stage9.
- **Type consistency:** boxes are capture-px until `BadgeLayout`; `Tier`/`HeroTier` shared;
  `Transition` shared; `TierTable.lookup` returns null on ambiguity (no wrong badge);
  `Frame.rotationDeg` drives the Stage-8 stale guard.
- **Intentional deferrals (not placeholders):** `HeroMatcher` thresholds,
  `SelectPhaseTrigger.isOpen`, and the **OCR engine** (ML Kit vs PP-OCRv5) are named injection
  points finalized in Stage 9 by device spikes; every other step is fully specified with
  runnable code/tests. The `HeroOcr` interface isolates the engine choice from all other stages.

---

## Appendix A: On-device OCR engine research (2026-06)

**Question:** SOTA low-cost on-device OCR for **Traditional Chinese + English** on Android
(offline, low latency, small). Use one model if possible, separate models acceptable.

**Finding — one recognition model suffices.** **PP-OCRv5 mobile (PaddleOCR)** ships a *single
multilingual recognition model* (SVTRv2 head, ~18,383-char dict) covering **Traditional Chinese,
English, Simplified, Japanese, Pinyin** — so both in-game languages are handled by one rec
model, no per-locale split. (The full pipeline still needs detection + recognition + dict.)
Pipeline: DBNet detection (yields name boxes) → rec per line. Published mobile accuracy ≈
**0.72 Traditional Chinese / 0.88 English** on hard scene text. **Hypothesis (Spike-A tests it):**
hero-name UI text (large, high-contrast) is easier than benchmark scene text, and the closed
110-name dictionary + fuzzy match (§7) recovers residual errors. Models are Apache-2.0.

**Android deployment paths (real repos):**
| Repo | Runtime | License | Notes |
|---|---|---|---|
| `iFleey/PPOCRv5-Android` | LiteRT/TFLite | Apache-2.0 | PP-OCRv5 port; FP16 `ocr_det`/`ocr_rec` `.tflite` + `keys_v5.txt`; GPU(OpenCL)+XNNPACK; single rec covers Trad+EN; demo app (liftable) |
| `nihui/ncnn-android-ppocrv5` | ncnn (Tencent) | ncnn = BSD-3 (verify repo) | PP-OCRv5 port; C++/JNI arm64; by the ncnn author. "Smallest/fastest" is a hypothesis to measure, not a cited fact |
| `RapidAI/RapidOcrAndroidOnnx` | ONNX Runtime | Apache-2.0 | mature ONNX Android OCR reference — **not confirmed a PP-OCRv5 port**; verify PP-OCRv5 ONNX model compat before use |

**ML Kit (baseline only).** Bundled Latin + Chinese recognizers; trivial integration, free,
offline. ML Kit lists Chinese (incl. `zh-Hant`) as supported, **but Traditional accuracy on
stylized game text is unproven** and community issues report Chinese recognition errors /
traditional↔simplified confusion (googlesamples/mlkit #421; #808 is a separate crash/focus
issue). A *risk to validate in Spike A*, not a known-bad baseline. Ships only if it clears the
zero-wrong-badge gate on **both** locales.

**Decision:** primary = PP-OCRv5 mobile (LiteRT or ncnn, chosen in Spike A by latency/size);
baseline = ML Kit. Both behind `HeroOcr`; the Stage-9.2 bake-off on real frames selects the
shipped engine — eligibility requires zero wrong badges on **both** locales, with Traditional
Chinese as the tie-breaker.

**Sources:** PaddleOCR PP-OCRv5 docs (paddleocr.ai); HF `PaddlePaddle/PP-OCRv5_mobile_rec`;
`iFleey/PPOCRv5-Android`; `nihui/ncnn-android-ppocrv5`; `RapidAI/RapidOcrAndroidOnnx`; ML Kit
text-recognition supported-languages; googlesamples/mlkit issues #421 / #808.
