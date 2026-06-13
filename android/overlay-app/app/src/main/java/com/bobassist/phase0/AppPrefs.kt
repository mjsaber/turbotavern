package com.bobassist.phase0

import android.content.Context

/**
 * User settings (SharedPreferences). Written by [SettingsActivity], read by the overlay
 * ([OverlayService] -> SelectCoordinator) to gate the hero / trinket overlays. Defaults: both on.
 */
object AppPrefs {
    private const val FILE = "turbo_tavern_prefs"
    private const val K_HERO = "hero_overlay_enabled"
    private const val K_TRINKET = "trinket_overlay_enabled"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun heroEnabled(c: Context) = p(c).getBoolean(K_HERO, true)
    fun trinketEnabled(c: Context) = p(c).getBoolean(K_TRINKET, true)
    fun setHeroEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean(K_HERO, v).apply()
    fun setTrinketEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean(K_TRINKET, v).apply()
}
