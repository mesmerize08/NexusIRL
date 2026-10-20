package io.github.mesmerize08.nexusirl.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LutTest {
    private fun identityCube(n: Int): String = buildString {
        appendLine("TITLE \"identity\"")
        appendLine("# comment")
        appendLine("LUT_3D_SIZE $n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            appendLine("${r / (n - 1.0)} ${g / (n - 1.0)} ${b / (n - 1.0)}")
        }
    }

    @Test
    fun parsesCubeFiles() {
        val cube = Luts.parseCube(identityCube(3))
        assertEquals(3, cube.dimension)
        assertEquals(1f, cube.entry(2, 0, 0, 0))
        assertEquals(0.5f, cube.entry(0, 1, 0, 1))
        assertEquals(1f, cube.entry(0, 0, 2, 2))
        assertEquals(0.25f, Luts.interpolate(cube, 0.25f, 0.6f, 0.9f, 0), 1e-5f)
        assertFailsWith<InvalidLutException> { Luts.parseCube("LUT_1D_SIZE 4\n0 0 0") }
        assertFailsWith<InvalidLutException> { Luts.parseCube("LUT_3D_SIZE 2\n0 0 0") }
    }

    @Test
    fun domainIsNormalised() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n" + (0 until 8).joinToString("\n") { "2 1 0" }
        val cube = Luts.parseCube(text)
        assertEquals(1f, cube.entry(0, 0, 0, 0))
        assertEquals(0.5f, cube.entry(0, 0, 0, 1))
    }

    @Test
    fun readsUpstreamImageLayout() {
        // A 4^3 identity LUT as a 8x8 image: 2 tile columns, blue tiles left to right then down.
        val n = 4
        val width = 8
        val height = 8
        val pixels = IntArray(width * height)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val row = b / 2 * n + g
            val column = b % 2 * n + r
            pixels[row * width + column] = (0xFF shl 24) or ((r * 85) shl 16) or ((g * 85) shl 8) or (b * 85)
        }
        val cube = Luts.fromImage(width, height, pixels)
        assertEquals(n, cube.dimension)
        assertEquals(1f, cube.entry(3, 1, 2, 0))
        assertEquals(1f / 3, cube.entry(3, 1, 2, 1), 1e-5f)
        assertEquals(2f / 3, cube.entry(3, 1, 2, 2), 1e-5f)
        assertFailsWith<InvalidLutException> { Luts.fromImage(10, 10, IntArray(100)) }
    }

    @Test
    fun limitAndStrip() {
        val big = Luts.parseCube(identityCube(65))
        val limited = Luts.limit(big)
        assertEquals(64, limited.dimension)
        assertEquals(1f, limited.entry(63, 0, 0, 0), 1e-5f)
        val strip = Luts.toStrip(Luts.parseCube(identityCube(2)))
        // Width 4 (2 blue slices of 2), height 2: pixel (x=3, y=1) is red 1, green 1, blue 1.
        val offset = (1 * 4 + 3) * 4
        assertEquals(listOf(255, 255, 255, 255), (0 until 4).map { strip[offset + it].toInt() and 0xFF })
        assertEquals(listOf(0, 0, 0), (0 until 3).map { strip[it].toInt() and 0xFF })
    }
}
