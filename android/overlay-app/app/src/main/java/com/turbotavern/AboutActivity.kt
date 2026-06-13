package com.turbotavern

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal license / About screen. Renders every text file bundled under assets/licenses/, which is
 * flavor-specific: the full (sideload) build ships GPL-3.0.txt + NOTICE.txt; the clean (Play) build
 * ships EULA.txt. This keeps the GPL-3.0 "the license must accompany the binary" obligation satisfied
 * and visible in-app for the full build.
 */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = buildString {
            val names = runCatching { assets.list("licenses") }.getOrNull()?.sorted().orEmpty()
            if (names.isEmpty()) append("No license files bundled.")
            for (name in names) {
                append("===== ").append(name).append(" =====\n\n")
                append(
                    runCatching { assets.open("licenses/$name").bufferedReader().use { it.readText() } }
                        .getOrElse { "(could not read $name: ${it.message})" }
                )
                append("\n\n\n")
            }
        }
        val tv = TextView(this).apply {
            text = body
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(32, 48, 32, 48)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
