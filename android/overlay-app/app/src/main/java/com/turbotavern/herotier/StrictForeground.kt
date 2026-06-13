package com.turbotavern.herotier

/** Strict foreground state for the capture gate. UNKNOWN (no usage data) must NOT capture. */
enum class Foreground { TRUE, FALSE, UNKNOWN }

/**
 * Maps a foreground-package query to a strict gate. Unlike the kill-button's optimistic
 * `ForegroundDetector` (null -> stays foreground), a null query here means UNKNOWN, so hero-tier
 * never captures the screen without positive evidence Hearthstone is foreground.
 */
object StrictForeground {
    fun of(query: String?, target: String): Foreground = when (query) {
        null -> Foreground.UNKNOWN
        target -> Foreground.TRUE
        else -> Foreground.FALSE
    }
}
