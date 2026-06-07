package com.bobassist.phase0.herotier

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

    // T3 — fuzzy recall on single-char deletions stays above a conservative floor (regression guard).
    @Test fun fuzzyRecallAboveFloor() {
        var total = 0; var ok = 0
        val per = HashMap<String, IntArray>()
        for ((cid, loc, name) in corpus) {
            if (NameKey.of(name).length <= SHORT_LEN + 1) continue
            for (i in name.indices) {
                val recovered = matcher.match(listOf(ln(name.removeRange(i, i + 1)))).any { it.cardId == cid }
                total++; if (recovered) ok++
                val a = per.getOrPut(loc) { intArrayOf(0, 0) }; a[1]++; if (recovered) a[0]++
            }
        }
        per.forEach { (loc, a) -> println("recall[$loc] = ${a[0]}/${a[1]} = ${"%.3f".format(a[0].toDouble() / a[1])}") }
        val recall = ok.toDouble() / total
        println("recall overall = ${"%.3f".format(recall)} ($ok/$total)")
        assertTrue("fuzzy recall $recall below floor $RECALL_FLOOR", recall >= RECALL_FLOOR)
    }

    // Ambiguity audit — no two DISTINCT heroes' names are within the fuzzy cap in the same locale.
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

    /** Edge frame-stroke (both ends) + each single-char deletion when the key stays past shortLen. */
    private fun perturbations(name: String): List<String> = buildList {
        add("|$name"); add("$name|")
        if (NameKey.of(name).length > SHORT_LEN + 1) for (i in name.indices) add(name.removeRange(i, i + 1))
    }

    private companion object {
        // Mirror HeroMatcher production defaults (the audit replicates its cap; match() uses the real ones).
        const val SHORT_LEN = 3; const val FUZZY_CAP = 2; const val FUZZY_RATIO = 0.2; const val RECALL_FLOOR = 0.60
    }
}
