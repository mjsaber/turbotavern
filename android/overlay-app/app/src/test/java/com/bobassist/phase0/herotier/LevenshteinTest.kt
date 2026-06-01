package com.bobassist.phase0.herotier

import org.junit.Assert.assertEquals
import org.junit.Test

class LevenshteinTest {
    @Test fun zeroForEqual() = assertEquals(0, Levenshtein.distance("abc", "abc", 9))
    @Test fun oneEdit() = assertEquals(1, Levenshtein.distance("abc", "abd", 9))
    @Test fun boundedReturnsBoundPlusOneWhenExceeded() =
        assertEquals(3, Levenshtein.distance("abcdef", "uvwxyz", 2))   // far apart, bound 2 -> 3
    @Test fun emptyToNonEmpty() = assertEquals(3, Levenshtein.distance("", "abc", 9))
    @Test fun lengthGapExceedsBound() = assertEquals(3, Levenshtein.distance("a", "abcd", 2))
}
