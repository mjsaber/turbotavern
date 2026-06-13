package com.turbotavern.herotier

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class NameKeyTest {
    @Test fun matchesSharedVectors() {
        val arr = JSONArray(javaClass.classLoader!!.getResource("namekey_vectors.json")!!.readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertEquals("vec $i: ${o.getString("in")}", o.getString("out"), NameKey.of(o.getString("in")))
        }
    }

    @Test fun dropsLoneSurrogate() = assertEquals("", NameKey.of("\uD800"))

    @Test fun dropsEmbeddedLoneSurrogate() =                    // parity with Python "a\ud800b" -> "ab"
        assertEquals("ab", NameKey.of("a\uD800b"))

    @Test fun keepsValidSupplementary() =                       // U+1F600 survives NFKC+lowercase
        assertEquals("😀", NameKey.of("😀"))
}
