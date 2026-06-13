package com.turbotavern.herotier

object Levenshtein {
    /** Edit distance between [a] and [b]; if it would exceed [bound], returns `bound + 1`. */
    fun distance(a: String, b: String, bound: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > bound) return bound + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > bound) return bound + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
