package com.turbotavern.herotier

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build

/**
 * Maximum window alpha that still lets touches pass through an overlay on Android 12+ (the
 * obscuring-opacity rule for `FLAG_NOT_TOUCHABLE`). Pre-31 has no such limit, so a full 1f is fine.
 * Falls back to the platform default (0.8) if the value is unavailable or degenerate.
 */
object OpacityCap {
    private const val DEFAULT_CAP = 0.8f

    fun of(context: Context): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
        val v = runCatching {
            (context.getSystemService(Context.INPUT_SERVICE) as InputManager)
                .maximumObscuringOpacityForTouch
        }.getOrDefault(DEFAULT_CAP)
        return if (v > 0f && v <= 1f) v else DEFAULT_CAP
    }
}
