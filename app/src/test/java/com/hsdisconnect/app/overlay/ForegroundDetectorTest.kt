package com.hsdisconnect.app.overlay

import com.hsdisconnect.app.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDetectorTest {

    private fun fixedDetector(samples: List<Boolean>): ForegroundDetector {
        var i = 0
        return ForegroundDetector(
            sampleNow = {
                samples[i++.coerceAtMost(samples.lastIndex)]
            },
            debounceSamples = Constants.FOREGROUND_DEBOUNCE_SAMPLES,
        )
    }

    @Test
    fun `initial state is false`() {
        val d = fixedDetector(listOf(false))
        assertFalse(d.isForeground.value)
    }

    @Test
    fun `one true sample does not flip yet (debounce of 2)`() {
        val d = fixedDetector(listOf(true, false))
        d.tick()
        assertFalse(d.isForeground.value)
    }

    @Test
    fun `two consecutive true samples flip to true`() {
        val d = fixedDetector(listOf(true, true))
        d.tick(); d.tick()
        assertTrue(d.isForeground.value)
    }

    @Test
    fun `two true then one false stays true`() {
        val d = fixedDetector(listOf(true, true, false))
        d.tick(); d.tick(); d.tick()
        assertTrue(d.isForeground.value)
    }

    @Test
    fun `two true then two false flips back to false`() {
        val d = fixedDetector(listOf(true, true, false, false))
        d.tick(); d.tick(); d.tick(); d.tick()
        assertFalse(d.isForeground.value)
    }

    @Test
    fun `state transitions count is 2 over true,true,false,false`() {
        val d = fixedDetector(listOf(true, true, false, false))
        val seen = mutableListOf<Boolean>()
        seen.add(d.isForeground.value)
        repeat(4) {
            d.tick()
            if (seen.last() != d.isForeground.value) seen.add(d.isForeground.value)
        }
        assertEquals(listOf(false, true, false), seen)
    }
}
