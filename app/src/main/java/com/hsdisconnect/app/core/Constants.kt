package com.hsdisconnect.app.core

object Constants {
    const val HEARTHSTONE_PACKAGE = "com.blizzard.wtcg.hearthstone"

    val ALLOWED_DURATIONS_MS = listOf(3_000L, 5_000L, 8_000L, 10_000L)
    const val DEFAULT_DURATION_MS = 5_000L

    const val FOREGROUND_POLL_INTERVAL_MS = 1_500L
    const val FOREGROUND_DEBOUNCE_SAMPLES = 2

    const val NOTIF_CHANNEL_OVERLAY = "overlay_service"
    const val NOTIF_CHANNEL_VPN = "vpn_service"
    const val NOTIF_ID_OVERLAY = 1001
    const val NOTIF_ID_VPN = 1002

    const val VPN_TUN_ADDRESS = "10.42.42.1"
    const val VPN_TUN_PREFIX = 32
}
