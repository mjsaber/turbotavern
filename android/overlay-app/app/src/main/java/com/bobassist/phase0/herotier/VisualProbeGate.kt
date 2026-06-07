package com.bobassist.phase0.herotier

/**
 * Pure §8.2 open/close decision for the hero-select visual probe (spec §4.1). Fed one probe's hero
 * **match count** per tick; emits single-fire [Transition] edges. Owns ONLY the match-count logic —
 * the coordinator handles the orthogonal closes (foreground-lost / MAX_WINDOW / projection-stop) and
 * resets the gate via [forceClose]. No Android types.
 *
 * Open when a probe yields ≥ [openMatches]. Close after [closeK] consecutive 0-match probes; any
 * probe with ≥1 match resets the zero run (so a held window stays open while heroes are visible).
 */
class VisualProbeGate(
    private val openMatches: Int = 2,
    private val closeK: Int = 3,
) {
    private var open = false
    private var consecutiveZero = 0

    fun onProbe(matchCount: Int): Transition = when {
        !open && matchCount >= openMatches -> { open = true; consecutiveZero = 0; Transition.Enter }
        open && matchCount == 0 -> {
            consecutiveZero++
            if (consecutiveZero >= closeK) { open = false; Transition.Exit } else Transition.None
        }
        open -> { consecutiveZero = 0; Transition.None }   // 1..(openMatches-1): keep open, reset zero run
        else -> Transition.None
    }

    /** Coordinator-driven close (foreground-lost / timeout / projection-stop). Idempotent. */
    fun forceClose() { open = false; consecutiveZero = 0 }
}
