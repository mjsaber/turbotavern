package com.bobassist.phase0.herotier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatFingerprintTest {
    private fun arr(host: String, net: String, port: Int) =
        """[{"host":"$host","network":"$net","destinationPort":$port,"id":"x","createdAt":1}]"""

    @Test fun matchesBattleSocket1119() = assertTrue(CombatFingerprint.present(arr("", "tcp", 1119)))
    @Test fun matchesPort3724() = assertTrue(CombatFingerprint.present(arr("", "tcp", 3724)))
    @Test fun rejectsResolvedHost() = assertFalse(CombatFingerprint.present(arr("blizzard.com", "tcp", 1119)))
    @Test fun rejectsUdp() = assertFalse(CombatFingerprint.present(arr("", "udp", 1119)))
    @Test fun rejectsOtherPort() = assertFalse(CombatFingerprint.present(arr("", "tcp", 443)))
    @Test fun emptyArray() = assertFalse(CombatFingerprint.present("[]"))
    @Test fun malformedJsonIsFalse() = assertFalse(CombatFingerprint.present("not json"))
}
