package com.turbotavern.herotier

import org.junit.Assert.assertEquals
import org.junit.Test

class StrictForegroundTest {
    private val hs = "com.blizzard.wtcg.hearthstone"

    @Test fun nullIsUnknown() = assertEquals(Foreground.UNKNOWN, StrictForeground.of(null, hs))
    @Test fun targetIsTrue() = assertEquals(Foreground.TRUE, StrictForeground.of(hs, hs))
    @Test fun otherIsFalse() = assertEquals(Foreground.FALSE, StrictForeground.of("com.other", hs))
}
