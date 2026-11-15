package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class IngestAudioDecoderTest {
    private class Recorder : IngestAudioDecoder.Listener {
        val chunks = LinkedBlockingQueue<Pair<ShortArray, Long>>()
        val failures = LinkedBlockingQueue<String>()
        val threads = LinkedBlockingQueue<String>()

        override fun onChunk(pcm: ShortArray, ptsNanos: Long) {
            threads.add(Thread.currentThread().name)
            chunks.add(pcm to ptsNanos)
        }

        override fun onFailed(reason: String) {
            threads.add(Thread.currentThread().name)
            failures.add(reason)
        }
    }

    private val recorder = Recorder()
    private val decoders = ArrayList<IngestAudioDecoder>()

    private fun decoder(outputChannels: Int): IngestAudioDecoder {
        val decoder = IngestAudioDecoder("test", outputChannels, recorder)
        decoders += decoder
        return decoder
    }

    @After
    fun tearDown() {
        decoders.forEach { it.close() }
    }

    /** Decodes [audio] fed at a live-ish pace and returns every chunk, once no more come. */
    private fun decodeAll(audio: EncodedAudio, outputChannels: Int): List<Pair<ShortArray, Long>> {
        val decoder = decoder(outputChannels)
        decoder.configure(audio.audioSpecificConfig)
        for ((frame, pts) in audio.frames) {
            decoder.queue(frame, pts)
            Thread.sleep(5)
        }
        val chunks = ArrayList<Pair<ShortArray, Long>>()
        while (true) {
            chunks += recorder.chunks.poll(1, TimeUnit.SECONDS) ?: break
        }
        assertNull("failed", recorder.failures.peek())
        assertTrue(recorder.threads.all { it == "ingest-audio-test" })
        return chunks
    }

    private fun assertDecodesAndResamples(sampleRate: Int, channels: Int, outputChannels: Int) {
        assumeTrue("no AAC encoder", AacTestEncoder.has())
        val audio = AacTestEncoder.encode(sampleRate, channels)
        val chunks = decodeAll(audio, outputChannels)
        assertTrue("only ${chunks.size} chunks", chunks.size > 20)
        chunks.forEach { assertEquals(1024 * outputChannels, it.first.size) }
        // Nanoseconds are kept: the first chunk starts at a frame's exact pts.
        assertTrue("first pts ${chunks[0].second}", audio.frames.any { it.second == chunks[0].second })
        val chunkNanos = 1024 * 1_000_000_000.0 / 48_000
        for (i in 1 until chunks.size) {
            val step = chunks[i].second - chunks[i - 1].second
            assertTrue("chunk $i step $step ns", abs(step - chunkNanos) <= 1_000_000)
        }
        val duration = chunks.size * 1024 / 48_000.0
        // What was encoded: encoders differ (Android 14's adds no priming frames and drops the
        // partial last frame; later ones add both), so the expected length comes from the frames.
        val encoded = audio.frames.size * 1024.0 / sampleRate
        Log.i("IngestAudioDecoderTest", "$sampleRate Hz × $channels → $outputChannels: ${chunks.size} chunks, $duration s of $encoded s")
        // Without an end of stream the decoder holds its last frames (about 60 ms), and the
        // resampler less than a chunk: no more may be missing, and nothing may be added.
        assertTrue("decoded $duration s of $encoded s", duration in encoded - 0.1..encoded + 0.03)
        // The tone: rising zero crossings of the first channel, away from the encoder's priming.
        val samples = chunks.drop(5).dropLast(5).flatMap { (pcm, _) -> (pcm.indices step outputChannels).map { pcm[it].toInt() } }
        var first = -1.0
        var last = -1.0
        var crossings = 0
        for (i in 1 until samples.size) {
            if (samples[i - 1] < 0 && samples[i] >= 0) {
                val at = i - 1 + samples[i - 1].toDouble() / (samples[i - 1] - samples[i])
                if (first < 0) first = at else crossings++
                last = at
            }
        }
        val frequency = crossings * 48_000.0 / (last - first)
        assertEquals("tone $frequency Hz", 1000.0, frequency, 20.0)
        if (outputChannels == 2) {
            val pcm = chunks[chunks.size / 2].first
            assertTrue((pcm.indices step 2).all { abs(pcm[it] - pcm[it + 1]) <= 2 })
        }
    }

    @Test
    fun decodesAndResamples() {
        assertDecodesAndResamples(44_100, 2, outputChannels = 2)
        recorder.chunks.clear()
        recorder.threads.clear()
        assertDecodesAndResamples(48_000, 1, outputChannels = 2)
        recorder.chunks.clear()
        assertDecodesAndResamples(44_100, 2, outputChannels = 1)
    }

    @Test
    fun badConfigFails() {
        val empty = decoder(2)
        empty.configure(byteArrayOf())
        assertNotNull(recorder.failures.poll(5, TimeUnit.SECONDS))
        empty.queue(ByteArray(100), 0)
        // AAC-LC with the reserved sampling frequency index 13.
        decoder(2).configure(byteArrayOf(0x16, 0x90.toByte()))
        assertNotNull(recorder.failures.poll(5, TimeUnit.SECONDS))
        assertNull(recorder.chunks.poll(200, TimeUnit.MILLISECONDS))
        assertTrue(recorder.threads.all { it == "ingest-audio-test" })
    }
}
