package com.bobassist.phase0.trinket

import com.bobassist.phase0.herotier.Transition
import com.bobassist.phase0.herotier.VisualProbeGate

/** Which select-screen overlay should be shown right now. Exactly one (or none) is ever active. */
enum class SelectWindow { NONE, HERO, TRINKET }

/**
 * Mode guard over two [VisualProbeGate]s (hero + trinket). The hero-select and trinket-select screens
 * are distinct, so at most ONE overlay may be open at a time; this owns that mutual exclusion so the
 * trinket overlay never opens on a hero-select frame and vice-versa.
 *
 * Per probe it is fed BOTH dictionaries' match counts. While a window is open only that window's gate
 * runs (the other is held shut), so a stray cross-dictionary hit cannot flip windows mid-phase. From
 * NONE, hero wins a (rare, shouldn't-happen) tie since the two dictionaries are disjoint. Pure logic;
 * no Android types. The coordinator diffs [active] across calls for enter/exit and handles orthogonal
 * closes (foreground-lost / timeout / projection-stop) via [forceClose].
 */
class SelectWindowArbiter(
    private val heroGate: VisualProbeGate = VisualProbeGate(),
    private val trinketGate: VisualProbeGate = VisualProbeGate(),
) {
    var active: SelectWindow = SelectWindow.NONE
        private set

    /** @return the window active AFTER this probe. */
    fun onProbe(heroMatches: Int, trinketMatches: Int): SelectWindow {
        when (active) {
            SelectWindow.HERO -> {
                if (heroGate.onProbe(heroMatches) == Transition.Exit) active = SelectWindow.NONE
            }
            SelectWindow.TRINKET -> {
                if (trinketGate.onProbe(trinketMatches) == Transition.Exit) active = SelectWindow.NONE
            }
            SelectWindow.NONE -> {
                // Hero has priority on the disjoint-dictionary tie that should never actually occur.
                if (heroGate.onProbe(heroMatches) == Transition.Enter) {
                    active = SelectWindow.HERO
                    trinketGate.forceClose()
                } else if (trinketGate.onProbe(trinketMatches) == Transition.Enter) {
                    active = SelectWindow.TRINKET
                    heroGate.forceClose()
                }
            }
        }
        return active
    }

    /** Coordinator-driven close of whichever window is open (foreground-lost / timeout / projection-stop). */
    fun forceClose() {
        heroGate.forceClose()
        trinketGate.forceClose()
        active = SelectWindow.NONE
    }
}
