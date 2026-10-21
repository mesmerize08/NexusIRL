package io.github.mesmerize08.nexusirl.stream

import android.content.Context
import io.github.mesmerize08.nexusirl.media.pipeline.AudioPipeline
import io.github.mesmerize08.nexusirl.media.pipeline.FanOutSink
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import io.github.mesmerize08.nexusirl.media.pipeline.Microphone

/**
 * One microphone capture and AAC encoder shared by everything that needs audio (the stream and
 * the recording), like upstream's single audio unit feeding all encoders. Android does not
 * reliably allow one app two concurrent microphone captures, so consumers attach here instead.
 *
 * Capture runs only while at least one consumer is attached. Call from the main thread.
 */
class SharedAudio(context: Context) {
    init {
        Microphones.start(context)
    }

    private val fanOut = FanOutSink()
    private val consumers = mutableSetOf<MediaSink>()
    private var pipeline: AudioPipeline? = null

    /** Selected microphone name, for the status line. */
    @Volatile
    var microphoneName = ""
        private set

    /** Upstream mute quick button: the microphone keeps running but sends silence. */
    var muted = false
        set(value) {
            field = value
            pipeline?.muted = value
        }

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
        audio.muted = muted
        pipeline = audio
        // Route to the selected mic before capture starts, then follow later changes.
        audio.setPreferredDevice(Microphones.selected.value?.device)
        try {
            audio.start()
        } catch (e: Exception) {
            // The AAC encoder failed: stream and record without audio rather than crash.
            android.util.Log.w("SharedAudio", "Audio failed: $e")
            pipeline = null
            microphoneName = NO_MICROPHONE
            return
        }
        follow(Microphones.selected.value)
        Microphones.addListener(followListener)
    }

    private companion object {
        /** Shown in the status line; silence is sent instead (no permission, or busy). */
        const val NO_MICROPHONE = "No microphone (sending silence)"
    }

    private val followListener: (Microphone?) -> Unit = ::follow

    /** Routes capture to the selected mic. */
    private fun follow(selected: Microphone?) {
        val audio = pipeline ?: return
        audio.setPreferredDevice(selected?.device)
        microphoneName = if (audio.microphoneAvailable) selected?.name ?: "" else NO_MICROPHONE
    }

    private fun stop() {
        Microphones.removeListener(followListener)
        pipeline?.stop()
        pipeline = null
    }
}
