package com.hsdisconnect.app.core

import android.content.Context
import android.content.SharedPreferences

class Prefs(private val sp: SharedPreferences) {
    companion object {
        private const val FILE = "hs_disconnect_prefs"
        private const val KEY_DURATION = "duration_ms"
        private const val KEY_X = "button_x"
        private const val KEY_Y = "button_y"

        fun from(context: Context): Prefs =
            Prefs(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }

    var durationMs: Long
        get() = sp.getLong(KEY_DURATION, Constants.DEFAULT_DURATION_MS)
        set(value) = sp.edit().putLong(KEY_DURATION, value).apply()

    var buttonPosition: Pair<Int, Int>
        get() = sp.getInt(KEY_X, -1) to sp.getInt(KEY_Y, -1)
        set(value) = sp.edit()
            .putInt(KEY_X, value.first)
            .putInt(KEY_Y, value.second)
            .apply()
}
