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
 * Tri-locale OCR-reality gate (en/zh-CN/zh-TW). The Layer-1 golden test (T1/T3) feeds the matcher the
 * CANONICAL asset name (or a random single-char deletion of it), so it cannot see a SYSTEMATIC failure:
 * a character that the PP-OCRv5 rec head can NEVER emit because it is absent from ppocrv5_dict.txt.
 *
 * Concretely: 26 of 113 zhTW names use U+2027 (‧ HYPHENATION POINT) as the name separator, and that
 * char is not in the 18,383-entry dict — so the CTC decoder physically cannot output it. The real OCR
 * line is therefore the name with the separator DROPPED, which used to push len-4/5 zhTW names past the
 * fuzzy cap (floor(0.2*len) == 0 at len 4) and yield no badge.
 *
 * This test ties what PP-OCRv5 can actually emit to what the table accepts: it loads the shipped dict as
 * the rec alphabet, projects every (cardId, locale, name) onto that alphabet (dropping undecodable code
 * points), and asserts the matcher still resolves the CORRECT hero. It catches the whole undecodable-char
 * class, not just the heroes named today. Expected RED before the separator-fold alias, GREEN after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class HeroDictDecodabilityTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val json = ctx.assets.open("herotier_v1.json").bufferedReader().use { it.readText() }
    private val table = TierTable.fromJson(json)
    private val matcher = HeroMatcher(table)   // production defaults
    private fun ln(s: String) = OcrLine(s, BoxPx(0, 0, 10, 10))

    /** Single code points the PP-OCRv5 rec head can emit (one entry per dict line). */
    private val decodable: Set<Int> = ctx.assets.open("ppocr/ppocrv5_dict.txt").bufferedReader().useLines { lines ->
        buildSet {
            for (line in lines) {
                val c = line.trimEnd('\n', '\r')
                if (c.isNotEmpty()) add(c.codePointAt(0))
            }
        }
    }

    /** Project a name onto what the rec head can output: keep decodable code points + whitespace, drop the rest. */
    private fun project(name: String): String {
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (decodable.contains(cp) || Character.isWhitespace(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

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

    /** Sanity: confirm the dict really is missing U+2027 (the bug's root cause) so the test stays meaningful. */
    @Test fun dictIsMissingTheZhTwSeparator() {
        assertTrue("expected U+2027 absent from ppocrv5_dict.txt", !decodable.contains(0x2027))
        assertTrue("dict should be the full ~18k table", decodable.size > 15000)
    }

    /** Every name, as PP-OCRv5 can actually emit it (undecodable chars dropped), resolves to its own hero. */
    @Test fun everyDecodableNameResolvesToItsHero() {
        val fail = corpus.mapNotNull { (cid, loc, name) ->
            val emitted = project(name)
            if (emitted.isBlank()) return@mapNotNull null      // wholly-undecodable name is a separate concern
            val got = matcher.match(listOf(ln(emitted))).map { it.cardId }
            if (cid in got) null else "$cid/$loc canonical=\"$name\" ocr-emits=\"$emitted\" -> $got"
        }
        assertTrue(
            "heroes unmatchable from real PP-OCRv5 output (${fail.size}):\n${fail.joinToString("\n")}",
            fail.isEmpty(),
        )
    }

    /** The projection must never resolve to a WRONG hero (folding aliases must stay ambiguity-safe). */
    @Test fun decodableProjectionNeverWrong() {
        val wrong = corpus.flatMap { (cid, _, name) ->
            val emitted = project(name)
            if (emitted.isBlank()) emptyList()
            else matcher.match(listOf(ln(emitted))).filter { it.cardId != cid }
                .map { "\"$name\" ocr=\"$emitted\" -> WRONG ${it.cardId} (src $cid)" }
        }
        assertTrue("decodable projection -> wrong hero (${wrong.size}):\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }
}
