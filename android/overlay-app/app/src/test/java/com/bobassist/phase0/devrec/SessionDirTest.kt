package com.bobassist.phase0.devrec

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SessionDirTest {
    private fun tmp() = File.createTempFile("devrec", "").let { it.delete(); it.mkdirs(); it }

    @Test fun frameNameIsNumericStem() =
        assertEquals("1780700000000.json", SessionDir.frameName(1780700000000))

    @Test fun markAndShotNames() {
        assertEquals("MARK-1780700000000-3.txt", SessionDir.markName(1780700000000, 3))
        assertEquals("SHOT-1780700000000-3.png", SessionDir.shotName(1780700000000, 3))
    }

    @Test fun uniqueTsMonotonicAndCollisionBump() {
        val u = SessionDir.UniqueTs()
        assertEquals(1000L, u.next(1000))
        assertEquals(1001L, u.next(1000))   // same ms -> +1
        assertEquals(1002L, u.next(1000))   // same ms again -> +1 again
        assertEquals(2000L, u.next(2000))   // jumps forward to real time
        assertEquals(2001L, u.next(1500))   // never goes backward
    }

    @Test fun rollPreviousMovesOldSession() {
        val root = tmp(); val dir = File(root, "devrec"); dir.mkdirs()
        File(dir, "1.json").writeText("[]")
        val prev = File(root, "devrec-prev")
        SessionDir.rollPrevious(dir, prev)
        assertFalse(File(dir, "1.json").exists())
        assertTrue(File(prev, "1.json").exists())
    }
}
