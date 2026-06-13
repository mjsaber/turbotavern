package com.turbotavern.overlay

/**
 * Pure visual-state machine for the floating overlay button.
 *
 * Inputs: only poll-driven candidate counts and explicit cooldown commands
 * from the host. Taps do NOT live in this state machine — the host service
 * (BobVpnService) decides on each tap whether to attempt a kill, and only
 * calls OverlayPoller.enterCooldown() when a kill actually succeeded. This
 * keeps the state machine side-effect-free and trivially unit-testable.
 *
 * Transitions:
 *   WaitingForBattle --(poll: ≥1 candidate)----> Ready
 *   Ready            --(poll: 0 candidates)----> WaitingForBattle
 *   any              --(host: enterCooldown)---> Cooldown        (in OverlayPoller, not here)
 *   Cooldown         --(timer: 2 s)------------> WaitingForBattle (in OverlayPoller, not here)
 */
sealed class OverlayState {

    /** Drives `OverlayWindow.applyState` to swap the circle drawable. */
    abstract val visual: Visual

    /** Called on each poll tick. Returns next state. Cooldown ignores polls. */
    open fun onPoll(candidateCount: Int): OverlayState = this

    object WaitingForBattle : OverlayState() {
        override val visual = Visual.WAITING
        override fun onPoll(candidateCount: Int) =
            if (candidateCount >= 1) Ready else this
    }

    object Ready : OverlayState() {
        override val visual = Visual.READY
        override fun onPoll(candidateCount: Int) =
            if (candidateCount >= 1) this else WaitingForBattle
    }

    /**
     * Cooldown is an `object` (not a data class) for 1.1 — the 2 s duration
     * is fixed and exit is owned by [OverlayPoller]'s scheduleAfter callback.
     */
    object Cooldown : OverlayState() {
        override val visual = Visual.COOLDOWN
        override fun onPoll(candidateCount: Int) = this
    }

    enum class Visual { WAITING, READY, COOLDOWN }

    companion object {
        const val COOLDOWN_MS = 2_000L
    }
}
