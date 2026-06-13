package com.bobassist.phase0

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Settings: toggle the hero / trinket overlays. Persisted via [AppPrefs]; read live by the overlay. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.action_settings)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        col.addView(
            toggleRow(
                getString(R.string.settings_hero), getString(R.string.settings_hero_sum),
                AppPrefs.heroEnabled(this),
            ) { AppPrefs.setHeroEnabled(this, it) }
        )
        col.addView(
            toggleRow(
                getString(R.string.settings_trinket), getString(R.string.settings_trinket_sum),
                AppPrefs.trinketEnabled(this),
            ) { AppPrefs.setTrinketEnabled(this, it) }
        )
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toggleRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        @Suppress("DEPRECATION") // framework Switch is fine for a non-AppCompat Activity (minSdk 29)
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SettingsActivity).apply {
                text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = summary; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(texts)
            addView(sw)
        }
    }
}
