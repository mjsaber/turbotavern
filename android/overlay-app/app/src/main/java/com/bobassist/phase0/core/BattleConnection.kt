package com.bobassist.phase0.core

import org.json.JSONArray

/**
 * Spike D-validated filter for the live HS BG battle socket.
 *
 * Fingerprint (verified on OnePlus 10T + Android 15 + international HS):
 *   - metadata.host == ""                       // unresolved/direct-IP connect
 *   - metadata.network == "tcp"
 *   - metadata.destinationPort in [1119, 3724]  // Blizzard game-server port
 *
 * Port evidence: Spike D (2026-05) saw the battle/game socket on 3724. A
 * 2026-05-29 live recording (recordings/2026-05-29-session1) showed the same
 * direct-IP game connection (host=="", 66.40.x block) on **1119** with NO 3724
 * present — so the old port-3724-only fingerprint matched 0/834 frames. Both
 * are Blizzard game ports; we accept either. host=="" (direct-IP, no DNS)
 * uniquely separates the game-server socket from resolved CDN/service traffic.
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

    /** Blizzard game-server ports seen carrying the live battle session. */
    private val BATTLE_PORTS = setOf(1119, 3724)

    data class Candidate(
        val id: String,
        val destinationIp: String,
        val destinationPort: Int,
        val createdAt: Long,
    )

    fun pick(connectionsJson: String): Candidate? = pickWithCount(connectionsJson).first

    /**
     * Returns the chosen Candidate and the total number of candidates matched.
     * Phase 0 data showed only one matching socket at a time, but during the
     * brief windows where the server rotates the socket two candidates could
     * coexist. We prefer the newest-by-createdAt because the newer one is
     * what the HS client is actually transmitting battle data over.
     */
    fun pickWithCount(connectionsJson: String): Pair<Candidate?, Int> {
        val arr = runCatching { JSONArray(connectionsJson) }.getOrElse { return null to 0 }
        val candidates = mutableListOf<Candidate>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("host") != "") continue
            if (o.optString("network") != "tcp") continue
            if (o.optInt("destinationPort") !in BATTLE_PORTS) continue
            candidates += Candidate(
                id = o.optString("id"),
                destinationIp = o.optString("destinationIp"),
                destinationPort = o.optInt("destinationPort"),
                createdAt = o.optLong("createdAt"),
            )
        }
        return candidates.maxByOrNull { it.createdAt } to candidates.size
    }

    /**
     * Diagnostic verdict line for a raw connections snapshot: one entry per
     * connection with the FIRST reason production would reject it (mirrors
     * [pickWithCount]'s check order: host, network, port) or OK if it matches.
     *
     * Used by the post-kill table dumps (debt #9 — the post-kill reconnect
     * fingerprint has never been observed) and the zero-candidate near-miss
     * trace. Debug-trace only; never on the production decision path.
     */
    fun explain(connectionsJson: String): String {
        val arr = runCatching { JSONArray(connectionsJson) }.getOrElse { return "conns=? parse_error" }
        val sb = StringBuilder("conns=").append(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val host = o.optString("host")
            val network = o.optString("network")
            val port = o.optInt("destinationPort")
            val verdict = when {
                host != "" -> "HOST($host)"
                network != "tcp" -> "NET($network)"
                port !in BATTLE_PORTS -> "PORT"
                else -> "OK"
            }
            sb.append(" | ").append(verdict)
                .append(' ').append(network)
                .append(' ').append(o.optString("destinationIp")).append(':').append(port)
                .append(" id=").append(o.optString("id").take(8))
                .append(" created=").append(o.optLong("createdAt"))
        }
        return sb.toString()
    }
}
