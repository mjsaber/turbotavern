package com.bobassist.phase0.core

import org.json.JSONArray

/**
 * Spike D-validated filter for the live HS BG battle socket.
 *
 * Fingerprint (verified on OnePlus 10T + Android 15 + international HS):
 *   - metadata.host == ""               // unresolved/direct-IP connect
 *   - metadata.network == "tcp"
 *   - metadata.destinationPort == 3724  // Blizzard BG game server
 *
 * Under Phase 0 build tag `cmfa` + find-process-mode:off, only HS is in
 * addAllowedApplication, so the connection table contains only HS sockets.
 * Therefore process-name disambiguation is unnecessary.
 *
 * The socket is long-lived (multiple combat rounds) — see PINNED-VERSIONS.md
 * §Spike D. Server rotates it on occasion; we always pick the currently-live
 * one. Spike E v1 picks the first match; future versions may prefer the
 * newest by createdAt if multiple ever co-exist.
 */
object BattleConnection {

    data class Candidate(
        val id: String,
        val destinationIp: String,
        val destinationPort: Int,
        val createdAt: Long,
    )

    fun pick(connectionsJson: String): Candidate? {
        val arr = runCatching { JSONArray(connectionsJson) }.getOrElse { return null }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("host") != "") continue
            if (o.optString("network") != "tcp") continue
            if (o.optInt("destinationPort") != 3724) continue
            return Candidate(
                id = o.optString("id"),
                destinationIp = o.optString("destinationIp"),
                destinationPort = o.optInt("destinationPort"),
                createdAt = o.optLong("createdAt"),
            )
        }
        return null
    }
}
