package io.github.mesmerize08.nexusirl.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.media.pipeline.AudioConsumers
import io.github.mesmerize08.nexusirl.media.pipeline.AudioEncoderFormat
import io.github.mesmerize08.nexusirl.media.pipeline.AudioPipeline
import io.github.mesmerize08.nexusirl.media.pipeline.MediaSink
import io.github.mesmerize08.nexusirl.media.pipeline.Microphone

/**
 * One microphone capture shared by everything that needs audio (the stream and the recording),
 * like upstream's single audio unit feeding all encoders. Android does not reliably allow one app
 * two concurrent microphone captures, so consumers attach here instead. Consumers of one codec
 * share its encoder: an Opus stream and a recording (always AAC, as upstream's) get one each.
 *
 * Capture runs only while at least one consumer is attached. Consumers are added and removed on
 * the capture-control thread (stopping capture waits for its threads), and microphone changes
 * are applied there too, so the capture is only touched from that thread.
 */
class SharedAudio(
    context: Context,
    /** Whether to capture in stereo; read when capture starts (upstream `preferStereoMic`). */
    private val stereo: () -> Boolean = { false },
    /** Runs on the capture-control thread. */
    private val control: (() -> Unit) -> Unit,
) {
    init {
        Microphones.start(context)
    }

    private val consumers = AudioConsumers(
        startEncoder = { format, sink ->
            try {
                pipeline?.addEncoder(format, sink)
            } catch (e: Exception) {
                // The encoder failed: stream and record without audio rather than crash.
                android.util.Log.w("SharedAudio", "Audio encoder $format failed: $e")
            }
        },
        stopEncoder = { format -> pipeline?.removeEncoder(format) },
    )

    @Volatile
    private var pipeline: AudioPipeline? = null

    /** [Microphones] is main-thread only. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Selected microphone name, for the status line. */
    @Volatile
    var microphoneName = ""
        private set

    /** Upstream mute quick button: the microphone keeps running but sends silence. */
    @Volatile
    var muted = false
        set(value) {
            field = value
            pipeline?.muted = value
        }

    /** Channels being captured: 1 or 2, and 1 while stopped. */
    val channels: Int
        get() = pipeline?.channels ?: 1

    val levelDbfs: Float
        get() = pipeline?.levelDbfs ?: -160f

    /** Attaches [consumer] to a [format] encoder; recordings use AAC. */
    fun add(consumer: MediaSink, format: AudioEncoderFormat = AudioEncoderFormat.AAC) {
        if (consumers.isEmpty) {
            start()
        }
        consumers.add(consumer, format)
    }

    fun remove(consumer: MediaSink) {
        if (consumers.isEmpty) {
            return
        }
        consumers.remove(consumer)
        if (consumers.isEmpty) {
            stop()
        }
    }

    private fun start() {
        val audio = AudioPipeline(channels = if (stereo()) 2 else 1)
        pipeline = audio
        // After publishing it: a mute set meanwhile on the main thread is not lost.
        audio.muted = muted
        // Route to the selected mic before capture starts, then follow later changes.
        audio.setPreferredDevice(Microphones.selected.value?.device)
        try {
            audio.start()
        } catch (e: Exception) {
            // No capture: stream and record without audio rather than crash.
            android.util.Log.w("SharedAudio", "Audio failed: $e")
            pipeline = null
            microphoneName = NO_MICROPHONE
            return
        }
        follow(Microphones.selected.value)
        mainHandler.post {
            Microphones.addListener(followListener)
            // A change between the line above and the listener would otherwise be missed.
            control { follow(Microphones.selected.value) }
        }
    }

    private companion object {
        /** Shown in the status line; silence is sent instead (no permission, or busy). */
        const val NO_MICROPHONE = "No microphone (sending silence)"
    }

    private val followListener: (Microphone?) -> Unit = { selected -> control { follow(selected) } }

    /** Routes capture to the selected mic. Capture-control thread. */
    private fun follow(selected: Microphone?) {
        val audio = pipeline ?: return
        audio.setPreferredDevice(selected?.device)
        microphoneName = if (audio.microphoneAvailable) selected?.name ?: "" else NO_MICROPHONE
    }

    private fun stop() {
        mainHandler.post { Microphones.removeListener(followListener) }
        pipeline?.stop()
        pipeline = null
    }
}
