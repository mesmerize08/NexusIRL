package io.github.mesmerize08.nexusirl.stream

import android.content.Context
import io.github.mesmerize08.nexusirl.media.pipeline.AudioPipeline
import io.github.mesmerize08.nexusirl.media.pipeline.FanOutSink
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import io.github.mesmerize08.nexusirl.media.pipeline.MicrophoneManager

/**
 * One microphone capture and AAC encoder shared by everything that needs audio (the stream and
 * the recording), like upstream's single audio unit feeding all encoders. Android does not
 * reliably allow one app two concurrent microphone captures, so consumers attach here instead.
 *
 * Capture runs only while at least one consumer is attached. Call from the main thread.
 */
class SharedAudio(context: Context) {
    private val fanOut = FanOutSink()
    private val microphones = MicrophoneManager(context.applicationContext)
    private val consumers = mutableSetOf<MediaSink>()
    private var pipeline: AudioPipeline? = null

    /** Selected microphone name, for the status line. */
    @Volatile
    var microphoneName = ""
        private set

    val levelDbfs: Float
        get() = pipeline?.levelDbfs ?: -160f

    fun add(consumer: MediaSink) {
        if (consumers.isEmpty()) {
            start()
        }
        // The current audio format is replayed to the new consumer.
        fanOut.add(consumer)
        consumers += consumer
    }

    fun remove(consumer: MediaSink) {
        fanOut.remove(consumer)
        if (consumers.remove(consumer) && consumers.isEmpty()) {
            stop()
        }
    }

    private fun start() {
        val audio = AudioPipeline(fanOut)
        pipeline = audio
        microphones.start { _, selected ->
            audio.setPreferredDevice(selected?.device)
            microphoneName = selected?.name ?: ""
        }
        audio.start()
    }

    private fun stop() {
        microphones.stop()
        pipeline?.stop()
        pipeline = null
    }
}
