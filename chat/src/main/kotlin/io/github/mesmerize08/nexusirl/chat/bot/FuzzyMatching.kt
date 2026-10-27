// Port of FuzzyMatchingSwift 0.11.1 (github.com/seanoshea/FuzzyMatchingSwift @87b14bd,
// FuzzyMatchingSwift/Classes/FuzzyMatching.swift), the fuzzy matcher upstream's chat bot uses.
// Copyright 2016 Sean O'Shea, Apache License 2.0 (see NOTICE). Only `fuzzyMatchPattern` is
// ported, with its quirks kept so typos match the same commands as on iOS: the substring search
// does not lower the threshold, the bitap scan never looks at the text's last character, and a
// better match after the expected location does not restart the scan. Swift counts characters and
// this counts UTF-16 units, which only differs outside the Basic Multilingual Plane.
package io.github.mesmerize08.nexusirl.chat.bot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Like Swift's `<<` on a 64-bit Int, which gives 0 rather than wrapping for large shifts. */
private fun shl(value: Long, count: Int): Long = if (count >= Long.SIZE_BITS) 0 else value shl count

/**
 * Upstream `String.fuzzyMatchPattern`: where [pattern] approximately occurs in this text, or null.
 * [threshold] 0 needs an exact match and 1 matches anything; [distance] is how far from [loc] a
 * match may be.
 */
fun String.fuzzyMatchPattern(pattern: String, loc: Int = 0, threshold: Double = 0.5, distance: Double = 1000.0): Int? {
    if (isEmpty()) {
        return null
    }
    val location = max(0, min(loc, length))
    if (equals(pattern, ignoreCase = true)) {
        return 0
    }
    if (pattern.isEmpty()) {
        return null
    }
    if (pattern.length <= length && pattern.equals(substring(0, pattern.length), ignoreCase = true)) {
        return location
    }
    return matchBitap(pattern, location, threshold, distance)
}

private fun String.matchBitap(pattern: String, loc: Int, threshold: Double, distance: Double): Int? {
    val alphabet = matchAlphabet(pattern)
    // Upstream finds a literal match but keeps the caller's threshold.
    var scoreThreshold = threshold
    var bestLoc: Int? = literalMatch(pattern, loc)
    val matchMask = shl(1, pattern.length - 1)
    var lastRd = LongArray(0)
    for (index in pattern.indices) {
        var binMin = 0
        var binMax = pattern.length + length
        var binMid = binMax
        while (binMin < binMid) {
            if (bitapScore(index, loc + binMid, loc, pattern, distance) <= scoreThreshold) {
                binMin = binMid
            } else {
                binMax = binMid
            }
            binMid = (binMax - binMin) / 2 + binMin
        }
        val start = maxOfConstAndDiff(1, loc, binMid)
        val finish = min(loc + binMid, length) + pattern.length
        val rd = LongArray(finish + 2)
        rd[finish + 1] = shl(1, index) - 1
        // Upstream indexes the previous row as if it were as long as this one.
        fun last(i: Int) = lastRd.getOrElse(i) { 0 }
        var j = finish
        while (j >= start) {
            val charMatch = characterMatch(j, alphabet)
            rd[j] = if (index == 0) {
                (rd[j + 1] shl 1 or 1) and charMatch
            } else {
                val lastMatch = (last(j + 1) or last(j)) shl 1 or 1
                ((rd[j + 1] shl 1 or 1) and charMatch) or lastMatch or last(j + 1)
            }
            if (rd[j] and matchMask != 0L) {
                val score = bitapScore(index, j - 1, loc, pattern, distance)
                if (score <= scoreThreshold) {
                    scoreThreshold = score
                    bestLoc = j - 1
                    if (j - 1 <= loc) {
                        break
                    }
                }
            }
            j -= 1
        }
        if (bitapScore(index + 1, loc, loc, pattern, distance) > scoreThreshold) {
            break
        }
        lastRd = rd
    }
    return bestLoc
}

/** Upstream `speedUpBySearchingForSubstring`, which only contributes a starting best location. */
private fun String.literalMatch(pattern: String, loc: Int): Int? {
    if (indexOf(pattern) < 0) {
        return null
    }
    val backward = substring(0, min(loc + pattern.length, length)).lastIndexOf(pattern)
    return if (backward >= 0) backward else indexOf(pattern)
}

/** Upstream `getCharacterMatch`: the bits of the character before [position], never the last one. */
private fun String.characterMatch(position: Int, alphabet: Map<Char, Long>): Long {
    if (position <= 0 || position >= length) {
        return 0
    }
    return alphabet[this[position - 1]] ?: 0
}

private fun matchAlphabet(pattern: String): Map<Char, Long> {
    val alphabet = mutableMapOf<Char, Long>()
    for ((index, char) in pattern.withIndex()) {
        alphabet[char] = (alphabet[char] ?: 0) or shl(1, pattern.length - index - 1)
    }
    return alphabet
}

private fun bitapScore(errorCount: Int, x: Int, loc: Int, pattern: String, distance: Double): Double {
    val accuracy = errorCount.toDouble() / pattern.length
    if (distance == 0.0) {
        return accuracy
    }
    return accuracy + abs(loc - x) / distance
}

private fun maxOfConstAndDiff(a: Int, b: Int, c: Int): Int = if (b <= c) a else b - c + a
