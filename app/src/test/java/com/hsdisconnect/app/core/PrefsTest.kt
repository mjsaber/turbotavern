package com.hsdisconnect.app.core

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PrefsTest {
    private lateinit var sp: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        sp = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { sp.edit() } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        prefs = Prefs(sp)
    }

    @Test
    fun `default duration falls back to Constants when unset`() {
        every { sp.getLong("duration_ms", Constants.DEFAULT_DURATION_MS) } returns Constants.DEFAULT_DURATION_MS
        assertEquals(Constants.DEFAULT_DURATION_MS, prefs.durationMs)
    }

    @Test
    fun `setting duration writes to prefs`() {
        prefs.durationMs = 8_000L
        verify { editor.putLong("duration_ms", 8_000L) }
        verify { editor.apply() }
    }

    @Test
    fun `button position defaults to -1, -1 when unset`() {
        every { sp.getInt("button_x", -1) } returns -1
        every { sp.getInt("button_y", -1) } returns -1
        assertEquals(-1 to -1, prefs.buttonPosition)
    }

    @Test
    fun `setting button position writes both x and y`() {
        prefs.buttonPosition = 120 to 340
        verify { editor.putInt("button_x", 120) }
        verify { editor.putInt("button_y", 340) }
    }
}
