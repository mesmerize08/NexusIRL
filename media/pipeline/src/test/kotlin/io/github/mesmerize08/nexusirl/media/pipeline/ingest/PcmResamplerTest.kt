package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmResamplerTest {
    private class Out(val samples: ShortArray, val pts: Long)

    private fun run(resampler: PcmResampler, pcm: ShortArray, frames: Int, pts: Long, out: MutableList<Out>) =
        resampler.process(pcm, frames, pts) { chunk, p -> out += Out(chunk.copyOf(), p) }

    private fun sine(rate: Int, frames: Int, hz: Int = 1000) =
        ShortArray(frames) { (10_000 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    @Test
    fun sameRatePassesThrough() {
        val resampler = PcmResampler(48_000, 1, 1)
        val input = ShortArray(4096) { (it * 7 % 20_000).toShort() }
        val out = mutableListOf<Out>()
        run(resampler, input, 4096, 1_000_000_000L, out)
        assertEquals(4, out.size)
        out.forEachIndexed { i, o ->
            assertContentEquals(input.copyOfRange(i * 1024, (i + 1) * 1024), o.samples)
            assertEquals(1_000_000_000L + i * 1024L * 1_000_000_000L / 48_000, o.pts)
        }
        assertEquals(21_333_333L, out[1].pts - out[0].pts)
    }

    @Test
    fun upsample44100() {
        val frames = 44_100
        val out = mutableListOf<Out>()
        val resampler = PcmResampler(44_100, 1, 1)
        val input = sine(44_100, frames)
        var offset = 0
        while (offset < frames) {
            val n = minOf(1000, frames - offset)
            run(resampler, input.copyOfRange(offset, offset + n), n, offset * 1_000_000_000L / 44_100, out)
            offset += n
        }
        assertEquals((frames * 48_000.0 / 44_100 / 1024).toInt(), out.size)
        val all = out.flatMap { it.samples.toList() }
        var crossings = 0
        for (i in 1 until all.size) if (all[i - 1] < 0 && all[i] >= 0) crossings++
        val seconds = all.size / 48_000.0
        assertTrue(abs(crossings / seconds - 1000) < 10, "freq ${crossings / seconds}")
    }

    @Test
    fun stereoToMono() {
        val input = ShortArray(2048) { if (it % 2 == 0) 1000 else 3000 }
        val out = mutableListOf<Out>()
        run(PcmResampler(48_000, 2, 1), input, 1024, 0, out)
        assertEquals(1, out.size)
        assertTrue(out[0].samples.all { it == 2000.toShort() })
    }

    @Test
    fun monoToStereo() {
        val input = ShortArray(1024) { (it + 1).toShort() }
        val out = mutableListOf<Out>()
        run(PcmResampler(48_000, 1, 2), input, 1024, 0, out)
        assertEquals(1, out.size)
        assertEquals(2048, out[0].samples.size)
        for (i in 0 until 1024) {
            assertEquals((i + 1).toShort(), out[0].samples[2 * i])
            assertEquals((i + 1).toShort(), out[0].samples[2 * i + 1])
        }
    }

    @Test
    fun ptsDoesNotDrift() {
        val resampler = PcmResampler(44_100, 1, 1)
        val input = ShortArray(1024)
        var last = -1L
        var inFrames = 0L
        val reads = 600L * 44_100 / 1024
        for (r in 0 until reads) {
            resampler.process(input, 1024, inFrames * 1_000_000_000L / 44_100) { _, p -> last = p }
            inFrames += 1024
        }
        val chunkIndex = (last * 48_000.0 / 1e9 / 1024).roundToLong()
        val exact = chunkIndex * 1024L * 1_000_000_000L / 48_000
        assertTrue(abs(last - exact) < 1_000_000, "last=$last exact=$exact")
        assertTrue(last > 599_000_000_000L)
    }

    @Test
    fun gapResets() {
        val resampler = PcmResampler(48_000, 1, 1)
        val out = mutableListOf<Out>()
        val input = ShortArray(1024)
        run(resampler, input, 1024, 0, out)
        run(resampler, input, 1024, 500_000_000L + 21_333_333L, out)
        assertEquals(2, out.size)
        assertEquals(521_333_333L, out[1].pts)
    }
}
