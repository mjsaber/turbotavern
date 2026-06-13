package com.turbotavern.herotier

/**
 * Pure edge detector for the hero-select window (spec §8.1). Emits [Transition.Enter] once on the
 * rising edge of [isOpen], [Transition.Exit] once on the falling edge, [Transition.None] otherwise.
 *
 * [isOpen] is pluggable: the production predicate (decided after Spike B) opens on the recorded
 * select signature and treats [CombatFingerprint.present] as a forced close — or, if no select
 * signature exists, the visual-probe path supersedes this in the coordinator (spec §8.2).
 */
class SelectPhaseTrigger(private val isOpen: (String) -> Boolean) {
    private var open = false

    fun update(connectionsJson: String): Transition {
        val now = isOpen(connectionsJson)
        return when {
            now && !open -> { open = true; Transition.Enter }
            !now && open -> { open = false; Transition.Exit }
            else -> Transition.None
        }
    }
}
