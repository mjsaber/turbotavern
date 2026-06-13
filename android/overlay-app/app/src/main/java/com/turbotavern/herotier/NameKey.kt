package com.turbotavern.herotier

import java.text.Normalizer

/**
 * Parity with data-pipeline `localize.normalize_name_key` over the Latin+CJK vector set
 * (`namekey_vectors.json`). NFKC -> lowercase -> drop lone surrogates -> collapse whitespace ->
 * trim. **No punctuation stripping** (decorative marks like `『』`, commas, apostrophes survive on
 * both ends so keys align). Non-goal: full Unicode casefold (e.g. ß -> ss in Python) — absent
 * from BG hero names, so `lowercase()` suffices for the shared vectors.
 */
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
                c.isHighSurrogate() || c.isLowSurrogate() -> i += 1   // lone surrogate -> drop
                else -> { sb.append(c); i += 1 }
            }
        }
        return sb.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
