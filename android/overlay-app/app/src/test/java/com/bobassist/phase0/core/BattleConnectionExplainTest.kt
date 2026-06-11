package com.bobassist.phase0.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BattleConnection.explain] — the diagnostic per-connection verdict
 * line used by the post-kill table dumps (debt #9: post-kill reconnect
 * fingerprint was never observed).
 *
 * The verdict must mirror [BattleConnection.pickWithCount]'s rejection order
 * exactly (host, then network, then port) so the dump explains what production
 * actually did.
 */
class BattleConnectionExplainTest {

    @Test
    fun `accepted battle socket is marked OK`() {
        val line = BattleConnection.explain(
            """[{"id":"abc-12345","host":"","network":"tcp","destinationIp":"66.40.189.71","destinationPort":3724,"createdAt":1000}]"""
        )
        assertTrue(line, line.startsWith("conns=1"))
        assertTrue(line, line.contains("OK tcp 66.40.189.71:3724"))
        assertTrue(line, line.contains("id=abc-1234"))   // id truncated to 8 chars
        assertTrue(line, line.contains("created=1000"))
    }

    @Test
    fun `resolved host is rejected with HOST reason carrying the hostname`() {
        val line = BattleConnection.explain(
            """[{"id":"x","host":"us.actual.battle.net","network":"tcp","destinationIp":"34.125.38.117","destinationPort":1119,"createdAt":2000}]"""
        )
        assertTrue(line, line.contains("HOST(us.actual.battle.net) tcp 34.125.38.117:1119"))
    }

    @Test
    fun `non-tcp is rejected with NET reason`() {
        val line = BattleConnection.explain(
            """[{"id":"x","host":"","network":"udp","destinationIp":"1.2.3.4","destinationPort":3724,"createdAt":1}]"""
        )
        assertTrue(line, line.contains("NET(udp)"))
    }

    @Test
    fun `port outside battle set is rejected with PORT reason`() {
        val line = BattleConnection.explain(
            """[{"id":"x","host":"","network":"tcp","destinationIp":"1.2.3.4","destinationPort":443,"createdAt":1}]"""
        )
        assertTrue(line, line.contains("PORT tcp 1.2.3.4:443"))
    }

    @Test
    fun `rejection order matches production - host checked before network and port`() {
        // host set AND udp AND bad port: production rejects on host first.
        val line = BattleConnection.explain(
            """[{"id":"x","host":"cdn.example","network":"udp","destinationIp":"1.2.3.4","destinationPort":443,"createdAt":1}]"""
        )
        assertTrue(line, line.contains("HOST(cdn.example)"))
    }

    @Test
    fun `empty table reports conns=0`() {
        assertEquals("conns=0", BattleConnection.explain("[]"))
    }

    @Test
    fun `malformed json reports parse_error`() {
        assertEquals("conns=? parse_error", BattleConnection.explain("not json"))
    }

    @Test
    fun `multiple connections keep table order and total count`() {
        val line = BattleConnection.explain(
            """[{"id":"lobby","host":"us.actual.battle.net","network":"tcp","destinationIp":"34.125.38.117","destinationPort":1119,"createdAt":1},""" +
            """{"id":"game","host":"","network":"tcp","destinationIp":"66.40.189.71","destinationPort":3724,"createdAt":2}]"""
        )
        assertTrue(line, line.startsWith("conns=2"))
        val hostIdx = line.indexOf("HOST(us.actual.battle.net)")
        val okIdx = line.indexOf("OK tcp 66.40.189.71:3724")
        assertTrue(line, hostIdx in 0 until okIdx)
    }
}
