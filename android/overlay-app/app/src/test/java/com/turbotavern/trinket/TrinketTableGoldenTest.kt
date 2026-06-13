package com.turbotavern.trinket

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.turbotavern.herotier.BoxPx
import com.turbotavern.herotier.OcrLine
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden test over the SHIPPED trinket asset for ALL trinkets × {enUS,zhCN,zhTW}, mirroring the hero
 * Layer-1 + dict-decodability tests. Locks tri-locale safety on real data: every name self-matches with
 * its class hint, OCR noise never resolves to a WRONG trinket, and every name as PP-OCRv5 can actually
 * emit it (undecodable code points dropped) still resolves. Uses production [TrinketMatcher] defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TrinketTableGoldenTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val json = ctx.assets.open("trinkettier_v1.json").bufferedReader().use { it.readText() }
    private val table = TrinketTable.fromJson(json)
    private val matcher = TrinketMatcher(table)
    private fun ln(s: String) = OcrLine(s, BoxPx(0, 0, 10, 10))

    private val decodable: Set<Int> = ctx.assets.open("ppocr/ppocrv5_dict.txt").bufferedReader().useLines { lines ->
        buildSet { for (line in lines) { val c = line.trimEnd('\n', '\r'); if (c.isNotEmpty()) add(c.codePointAt(0)) } }
    }
    private fun project(name: String): String {
        val sb = StringBuilder(name.length); var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (decodable.contains(cp) || Character.isWhitespace(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    // (cardId, class, locale, name)
    private val corpus: List<Quad> = run {
        val arr = JSONObject(json).getJSONArray("trinkets")
        buildList {
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val cid = t.getString("cardId")
                val cls = TrinketClass.parse(t.getString("trinketClass"))
                val names = t.getJSONObject("names")
                for (loc in names.keys()) {
                    val nm = names.getString(loc)
                    if (nm.isNotBlank()) add(Quad(cid, cls, loc, nm))
                }
            }
        }
    }
    private data class Quad(val cid: String, val cls: TrinketClass, val loc: String, val name: String)

    @Test fun corpusIsThreeLocalesAndReasonablySized() {
        val locales = corpus.map { it.loc }.toSet()
        val cards = corpus.map { it.cid }.toSet()
        assertTrue("expected {enUS,zhCN,zhTW}, got $locales", locales == setOf("enUS", "zhCN", "zhTW"))
        assertTrue("expected 100+ trinkets, got ${cards.size}", cards.size >= 100)
    }

    @Test fun everyCanonicalNameSelfMatchesWithItsClassHint() {
        val fail = corpus.mapNotNull { (cid, cls, loc, name) ->
            val got = matcher.match(listOf(ln(name)), cls).map { it.entry.cardId }
            if (cid in got) null else "$cid/$cls/$loc \"$name\" -> $got"
        }
        assertTrue("trinket self-match failures (${fail.size}):\n${fail.joinToString("\n")}", fail.isEmpty())
    }

    @Test fun everyDecodableNameResolvesWithItsClassHint() {
        val fail = corpus.mapNotNull { (cid, cls, loc, name) ->
            val emitted = project(name)
            if (emitted.isBlank()) return@mapNotNull null
            val got = matcher.match(listOf(ln(emitted)), cls).map { it.entry.cardId }
            if (cid in got) null else "$cid/$cls/$loc canonical=\"$name\" ocr=\"$emitted\" -> $got"
        }
        assertTrue("trinkets unmatchable from real PP-OCRv5 output (${fail.size}):\n${fail.joinToString("\n")}", fail.isEmpty())
    }

    @Test fun noiseNeverResolvesWrongTrinket() {
        val wrong = ArrayList<String>()
        for ((cid, cls, _, name) in corpus) {
            val variants = buildList {
                add("|$name"); add("$name|")
                if (com.turbotavern.herotier.NameKey.of(name).length > 4)
                    for (i in name.indices) add(name.removeRange(i, i + 1))
            }
            for (p in variants) for (m in matcher.match(listOf(ln(p)), cls)) {
                if (m.entry.cardId != cid) wrong.add("\"$name\"+\"$p\" -> WRONG ${m.entry.cardId} (src $cid)")
            }
        }
        assertTrue("noise -> wrong trinket (${wrong.size}):\n${wrong.take(40).joinToString("\n")}", wrong.isEmpty())
    }
}
