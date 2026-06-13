package com.turbotavern.herotier

import com.turbotavern.core.BattleConnection

/**
 * True when the connection snapshot contains the live BG combat socket
 * (host=="" ∧ network=="tcp" ∧ destinationPort∈{1119,3724}). Delegates to the kill-path
 * filter [BattleConnection.pickWithCount] so this can never drift from the real fingerprint.
 */
object CombatFingerprint {
    fun present(connectionsJson: String): Boolean =
        BattleConnection.pickWithCount(connectionsJson).first != null
}
