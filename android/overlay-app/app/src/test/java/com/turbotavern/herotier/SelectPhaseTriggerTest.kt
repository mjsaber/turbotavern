package com.turbotavern.herotier

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectPhaseTriggerTest {
    @Test fun risingThenFallingSingleFire() {
        val t = SelectPhaseTrigger(isOpen = { it.contains("OPEN") })
        assertEquals(Transition.None, t.update("idle"))
        assertEquals(Transition.Enter, t.update("OPEN"))
        assertEquals(Transition.None, t.update("OPEN"))          // stays open -> None
        assertEquals(Transition.Exit, t.update("idle"))
        assertEquals(Transition.None, t.update("idle"))          // stays closed -> None
        assertEquals(Transition.Enter, t.update("OPEN"))         // re-open
    }
}
