// Port of the LUT loading in Moblin/VideoEffects/LutEffect.swift @97e26d2 (`makeLutCube`,
// `lutEffectConvertLut`, `interpolate3d`), plus a `.cube` parser in place of the SC3DLut package.
package io.github.mesmerize08.nexusirl.effects

import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** A 3D colour lookup table: [dimension]^3 RGB entries (0..1), red fastest, then green, then blue. */
class LutCube(val dimension: Int, val rgb: FloatArray) {
    init {
        require(dimension >= 2 && rgb.size == dimension * dimension * dimension * 3) { "Bad LUT size" }
    }

    fun entry(red: Int, green: Int, blue: Int, channel: Int): Float = rgb[(((blue * dimension + green) * dimension + red) * 3) + channel]
}

class InvalidLutException(message: String) : Exception(message)

object Luts {
    /** Upstream caps LUTs at 64 entries per side. */
    const val MAX_DIMENSION = 64

    /**
     * Parses an Adobe/Resolve `.cube` file (`LUT_3D_SIZE N` then N^3 lines of "r g b", red
     * fastest). DOMAIN_MIN/DOMAIN_MAX are applied; 1D LUTs are rejected.
     */
    fun parseCube(text: String): LutCube {
        var size = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) {
                continue
            }
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> Unit
                "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: throw InvalidLutException("Bad LUT_3D_SIZE")
                "LUT_1D_SIZE" -> throw InvalidLutException("1D LUTs are not supported")
                "DOMAIN_MIN" -> domainMin = parseTriple(parts)
                "DOMAIN_MAX" -> domainMax = parseTriple(parts)
                "LUT_3D_INPUT_RANGE" -> Unit
                else -> {
                    if (parts.size < 3) {
                        throw InvalidLutException("Bad line: $line")
                    }
                    for (index in 0 until 3) {
                        values += parts[index].toFloatOrNull() ?: throw InvalidLutException("Bad value: $line")
                    }
                }
            }
        }
        if (size < 2 || size > 256) {
            throw InvalidLutException("Missing or unsupported LUT_3D_SIZE")
        }
        if (values.size != size * size * size * 3) {
            throw InvalidLutException("Expected ${size * size * size} entries, got ${values.size / 3}")
        }
        val rgb = FloatArray(values.size) { index ->
            val channel = index % 3
            ((values[index] - domainMin[channel]) / (domainMax[channel] - domainMin[channel])).coerceIn(0f, 1f)
        }
        return LutCube(size, rgb)
    }

    private fun parseTriple(parts: List<String>): FloatArray =
        FloatArray(3) { parts.getOrNull(it + 1)?.toFloatOrNull() ?: throw InvalidLutException("Bad domain") }

    /**
     * Upstream `lutEffectConvertLut`: a square-ish image of N^3 pixels laid out as tiles of N x N
     * (red across, green down), one tile per blue value, left to right then top to bottom.
     * [pixels] are ARGB.
     */
    fun fromImage(width: Int, height: Int, pixels: IntArray): LutCube {
        val dimension = cbrt((width.toDouble() * height)).roundToInt()
        if (dimension < 2 || width % dimension != 0 || height % dimension != 0 || dimension * dimension * dimension != width * height) {
            throw InvalidLutException("LUT image is not a cube")
        }
        val columns = width / dimension
        val rgb = FloatArray(dimension * dimension * dimension * 3)
        var index = 0
        for (blue in 0 until dimension) {
            for (green in 0 until dimension) {
                for (red in 0 until dimension) {
                    val row = blue / columns * dimension + green
                    val column = blue % columns * dimension + red
                    val pixel = pixels[row * width + column]
                    rgb[index++] = ((pixel shr 16) and 0xFF) / 255f
                    rgb[index++] = ((pixel shr 8) and 0xFF) / 255f
                    rgb[index++] = (pixel and 0xFF) / 255f
                }
            }
        }
        return LutCube(dimension, rgb)
    }

    /** Upstream `makeLutCube`: resamples to at most [MAX_DIMENSION] per side (trilinear). */
    fun limit(cube: LutCube): LutCube {
        val dimension = min(cube.dimension, MAX_DIMENSION)
        if (dimension == cube.dimension) {
            return cube
        }
        val rgb = FloatArray(dimension * dimension * dimension * 3)
        var index = 0
        val scale = 1f / (dimension - 1)
        for (blue in 0 until dimension) {
            for (green in 0 until dimension) {
                for (red in 0 until dimension) {
                    for (channel in 0 until 3) {
                        rgb[index++] = interpolate(cube, red * scale, green * scale, blue * scale, channel)
                    }
                }
            }
        }
        return LutCube(dimension, rgb)
    }

    /** Upstream `interpolate3d`. */
    fun interpolate(cube: LutCube, r: Float, g: Float, b: Float, channel: Int): Float {
        val max = (cube.dimension - 1).toFloat()
        val x = (r * max).coerceIn(0f, max)
        val y = (g * max).coerceIn(0f, max)
        val z = (b * max).coerceIn(0f, max)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val x1 = min(x0 + 1, cube.dimension - 1)
        val y1 = min(y0 + 1, cube.dimension - 1)
        val z1 = min(z0 + 1, cube.dimension - 1)
        val xd = x - x0
        val yd = y - y0
        val zd = z - z0
        fun e(xi: Int, yi: Int, zi: Int) = cube.entry(xi, yi, zi, channel)
        val c00 = e(x0, y0, z0) * (1 - xd) + e(x1, y0, z0) * xd
        val c01 = e(x0, y0, z1) * (1 - xd) + e(x1, y0, z1) * xd
        val c10 = e(x0, y1, z0) * (1 - xd) + e(x1, y1, z0) * xd
        val c11 = e(x0, y1, z1) * (1 - xd) + e(x1, y1, z1) * xd
        val c0 = c00 * (1 - yd) + c10 * yd
        val c1 = c01 * (1 - yd) + c11 * yd
        return c0 * (1 - zd) + c1 * zd
    }

    /**
     * The cube as an RGBA texture strip for GLES 2 (no 3D textures): width N*N, height N, blue
     * slice b at x = b*N + red, y = green.
     */
    fun toStrip(cube: LutCube): ByteArray {
        val n = cube.dimension
        val bytes = ByteArray(n * n * n * 4)
        for (blue in 0 until n) {
            for (green in 0 until n) {
                for (red in 0 until n) {
                    val offset = (green * n * n + blue * n + red) * 4
                    for (channel in 0 until 3) {
                        bytes[offset + channel] = (cube.entry(red, green, blue, channel) * 255 + 0.5f).toInt().coerceIn(0, 255).toByte()
                    }
                    bytes[offset + 3] = 255.toByte()
                }
            }
        }
        return bytes
    }
}
