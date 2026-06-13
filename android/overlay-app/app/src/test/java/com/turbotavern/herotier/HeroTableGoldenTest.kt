package com.turbotavern.herotier

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layer-1 golden test (spec 2026-06-07-hero-match-golden-test-design): exhaustively exercise hero-name
 * matching over the SHIPPED asset for ALL heroes × {enUS,zhCN,zhTW}. It is the regression net the zhTW
 * miss post-mortem demanded — expected GREEN (it locks current safety; it is not a red-bug-reproducer).
 *
 * Uses the production [HeroMatcher] with NO param overrides so it tracks whatever ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class HeroTableGoldenTest {

    private val json: String = ApplicationProvider.getApplicationContext<Context>()
        .assets.open("herotier_v1.json").bufferedReader().use { it.readText() }
    private val table = TierTable.fromJson(json)
    private val matcher = HeroMatcher(table)                 // production defaults
    private fun ln(s: String) = OcrLine(s, BoxPx(0, 0, 10, 10))

    // (cardId, locale, canonicalName) over all non-blank names in the shipped asset.
    private val corpus: List<Triple<String, String, String>> = run {
        val arr = JSONObject(json).getJSONArray("heroes")
        buildList {
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                val cid = h.getString("cardId")
                val names = h.getJSONObject("names")
                for (loc in names.keys()) {
                    val nm = names.getString(loc)
                    if (nm.isNotBlank()) add(Triple(cid, loc, nm))
                }
            }
        }
    }

    @Test fun corpusIsThreeLocalesAcrossAllHeroes() {
        val heroes = corpus.map { it.first }.toSet()
        val locales = corpus.map { it.second }.toSet()
        assertTrue("expected 113 heroes, got ${heroes.size}", heroes.size == 113)
        assertTrue("expected {enUS,zhCN,zhTW}, got $locales", locales == setOf("enUS", "zhCN", "zhTW"))
    }

    // T1 — every canonical name resolves to its own hero.
    @Test fun everyCanonicalNameSelfMatches() {
        val fail = corpus.mapNotNull { (cid, loc, name) ->
            val got = matcher.match(listOf(ln(name))).map { it.cardId }
            if (cid in got) null else "$cid/$loc \"$name\" -> $got"
        }
        assertTrue("self-match failures (${fail.size}):\n${fail.joinToString("\n")}", fail.isEmpty())
    }

    // T2 — OCR noise (edge frame-stroke + single-char deletion) never resolves to a WRONG hero.
    @Test fun noiseNeverResolvesWrongHero() {
        val wrong = ArrayList<String>()
        for ((cid, _, name) in corpus) {
            for (p in perturbations(name)) {
                for (b in matcher.match(listOf(ln(p)))) {
                    if (b.cardId != cid) wrong.add("\"$name\" + \"$p\" -> WRONG ${b.cardId} (src $cid)")
                }
            }
        }
        assertTrue("noise -> wrong-hero matches (${wrong.size}):\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    // T3 — fuzzy recall on single-char deletions stays above a conservative floor, asserted PER LOCALE.
    // Per-locale (not overall) is deliberate: enUS (~.99, half the trials) would otherwise mask a
    // CJK-only collapse — which is exactly the zhTW-only blind spot this whole effort came from.
    @Test fun fuzzyRecallAboveFloorPerLocale() {
        val per = HashMap<String, IntArray>()
        for ((cid, loc, name) in corpus) {
            if (NameKey.of(name).length <= SHORT_LEN + 1) continue
            for (i in name.indices) {
                val recovered = matcher.match(listOf(ln(name.removeRange(i, i + 1)))).any { it.cardId == cid }
                val a = per.getOrPut(loc) { intArrayOf(0, 0) }; a[1]++; if (recovered) a[0]++
            }
        }
        per.toSortedMap().forEach { (loc, a) -> println("recall[$loc] = ${a[0]}/${a[1]} = ${"%.3f".format(a[0].toDouble() / a[1])}") }
        val below = per.filterValues { it[0].toDouble() / it[1] < RECALL_FLOOR }
            .map { (loc, a) -> "$loc=${"%.3f".format(a[0].toDouble() / a[1])}" }
        assertTrue("per-locale fuzzy recall below floor $RECALL_FLOOR: $below", below.isEmpty())
    }

    // Data-drift canary (NOT a matcher-safety proof — T2 is that): flags two DISTINCT heroes whose
    // names sit within the fuzzy cap in one locale, so a data update that introduces such a collision
    // is noticed. It approximates resolve()'s cap and ignores the ambiguity guard, so it is neither
    // sound nor complete vs the matcher — its job is just to surface new same-locale near-duplicates.
    @Test fun noConfusableDistinctHeroPairs() {
        val confus = ArrayList<String>()
        for ((loc, items) in corpus.groupBy { it.second }) {
            for (i in items.indices) for (j in i + 1 until items.size) {
                val (ci, _, ni) = items[i]; val (cj, _, nj) = items[j]
                if (ci == cj) continue
                val ki = NameKey.of(ni); val kj = NameKey.of(nj)
                if (ki.length <= SHORT_LEN || kj.length <= SHORT_LEN) continue
                val cap = minOf(FUZZY_CAP, Math.floor(FUZZY_RATIO * maxOf(ki.length, kj.length)).toInt())
                if (cap <= 0) continue
                if (Levenshtein.distance(ki, kj, cap) <= cap) confus.add("$loc: $ci\"$ni\" ~ $cj\"$nj\"")
            }
        }
        confus.forEach { println("confusable: $it") }
        assertTrue("confusable distinct-hero pairs (${confus.size}):\n${confus.joinToString("\n")}", confus.isEmpty())
    }

    // Negative corpus — select-screen UI chrome must never produce a badge (false-positive guard).
    @Test fun nonHeroUiYieldsNoBadge() {
        val ui = listOf(
            "選擇一名英雄", "重骰", "確定", "元素，惡魔，機械，海盜，野獸",
            "选择一个英雄", "重掷", "确定", "元素，恶魔，机械，海盗，野兽",
            "Choose Your Hero", "Refresh", "Confirm",
        )
        val fp = ui.mapNotNull { s ->
            val got = matcher.match(listOf(ln(s))).map { it.cardId }
            if (got.isEmpty()) null else "\"$s\" -> $got"
        }
        assertTrue("non-hero UI false positives:\n${fp.joinToString("\n")}", fp.isEmpty())
    }

    // T-middot (live emulator 2026-06-13, the Cariel 凱瑞爾‧羅姆 miss): zhTW names separate with ‧
    // (U+2027), ABSENT from ppocrv5_dict — so OCR emits the interchangeable middot · (U+00B7, which IS
    // in the dict) or drops the mark. T1 only feeds the canonical name (with ‧), an input OCR can NEVER
    // produce — exactly how the miss hid. Assert every separator-bearing hero resolves from BOTH the
    // middot-substituted and the mark-dropped OCR-realistic forms (covers all such heroes, not just one).
    @Test fun separatorBearingNamesResolveFromOcrRealisticForms() {
        val sep = setOf('‧', '・', '•')     // ‧ ・ • — separators OCR substitutes/drops
        val midDot = '·'                             // · — the substitution target (present in the dict)
        var covered = 0
        val fail = ArrayList<String>()
        for ((cid, loc, name) in corpus) {
            val k = NameKey.of(name)
            if (k.none { it in sep }) continue
            covered++
            val asMiddot = buildString { for (c in k) append(if (c in sep) midDot else c) }
            val dropped = k.filterNot { it in sep }
            for (v in listOf(asMiddot, dropped)) {
                val got = matcher.match(listOf(ln(v))).map { it.cardId }
                if (cid !in got) fail.add("$cid/$loc \"$name\" via \"$v\" -> $got")
            }
        }
        assertTrue("expected separator-bearing names in the shipped asset, found none", covered > 0)
        assertTrue("OCR-realistic separator variants failed ($fail of $covered):\n${fail.joinToString("\n")}", fail.isEmpty())
    }

    // Data invariant: NO two DISTINCT heroes' names fold to the same key. The matcher's fold-collision
    // paths (exact-markless, fuzzy, vertical-merge) are only PARTIALLY defined against collisions —
    // codex flagged increasingly-narrow hypothetical merge interactions. Rather than harden the matcher
    // against states that cannot occur, assert they cannot: this holds for the shipped asset, so those
    // edge cases are UNREACHABLE. A future data update that introduces a collision fails loudly HERE,
    // which is the signal to revisit matcher collision-safety — not to pre-handle the impossible.
    @Test fun noFoldCollisionsInShippedAsset() {
        val byFold = HashMap<String, MutableSet<String>>()
        for ((cid, _, name) in corpus) {
            val folded = TierTable.foldSeparators(NameKey.of(name))
            if (folded.isNotEmpty()) byFold.getOrPut(folded) { HashSet() }.add(cid)
        }
        val collisions = byFold.filterValues { it.size > 1 }.map { "${it.key} -> ${it.value}" }
        assertTrue("fold collisions in shipped hero asset (matcher assumes none):\n${collisions.joinToString("\n")}", collisions.isEmpty())
    }

    /** Edge frame-stroke (both ends) + each single-char deletion when the key stays past shortLen. */
    private fun perturbations(name: String): List<String> = buildList {
        add("|$name"); add("$name|")
        if (NameKey.of(name).length > SHORT_LEN + 1) for (i in name.indices) add(name.removeRange(i, i + 1))
    }

    private companion object {
        // The matcher under test uses its real (private) defaults; these mirror them ONLY for the
        // data-drift canary's cap approximation. DRIFT HAZARD: if HeroMatcher's defaults change, update
        // these too (T1/T2/T3 still track production since they call match() with no overrides).
        const val SHORT_LEN = 3; const val FUZZY_CAP = 2; const val FUZZY_RATIO = 0.2; const val RECALL_FLOOR = 0.60
    }
}
