package io.github.mesmerize08.nexusirl.media.pipeline.ingest

import android.util.Log
import android.view.Surface
import io.github.mesmerize08.nexusirl.core.clock.HostClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One ingested (RTMP) stream as a video and an audio source for the pipeline, with Moblin's
 * buffered timing (spec §5).
 *
 * Timestamps are host nanoseconds as the RTMP server stamps them: publisher time plus a host base
 * plus the latency, so a frame is due when its PTS (plus the drift) is reached. [latencyNanos] is
 * the target fill level of the [DriftTracker] shared by video and audio.
 *
 * Video: compressed units wait in an [IngestJitterBuffer]. Each [updateVideo] feeds the decoder the
 * units whose `dts + drift ≤ output + decodeAhead` ([IngestDecodeSchedule], 150–500 ms) while it
 * takes input, fewer than six frames are held decoded, and fewer than 16 units queued to it have
 * not come out yet. Decoded frames wait in a [BufferedVideo], which picks the one to show; that
 * one is released to the surface, the others are dropped. A frame shown more than a frame interval
 * after its time makes the schedule decode further ahead. A decoder fed for 5 s without a picture
 * ends the source. When the decoder starts a new codec on its own (the software fallback) the
 * source drops what it buffered for the old one and waits for the next key frame, as it does on a
 * new config.
 *
 * Audio: AAC goes through [IngestAudioDecoder] (48 kHz, [outputChannels], 1024-frame chunks) into
 * a [BufferedAudio]; [audioChunk] plays the chunk for the output time and silence in gaps.
 *
 * At most [MAX_DECODING] sources in the process decode video: [setVideoConfig] of one more ends it
 * with "Too many video sources". Any decoder failure ends the source.
 *
 * [onEnded] is called at most once, never after [close] returns (close waits for a call in
 * progress, unless made from it), and without any of the source's state locks held; it must not
 * wait for a thread that is calling [close]. After it the source is closed: every call is a no-op.
 *
 * Threading: the config and add calls come from the RTMP server's thread, [attachSurface] and
 * [updateVideo] from the pipeline's GL thread, [audioChunk] from the audio output thread, and the
 * decoders call back on their own threads. Video state and audio state each have a lock; a
 * decoder's close() is never called with one held.
 */
class IngestSource(
    val name: String,
    latencyNanos: Long,
    softwareDecoding: Boolean,
    private val outputChannels: Int,
    private val clock: HostClock = HostClock.system,
    private val onEnded: (reason: String) -> Unit,
) {
    private val latency = latencyNanos / 1e9
    private val driftTracker = DriftTracker(name) { Log.i(TAG, it) }

    private val closed = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)

    /** Held while [onEnded] runs, so [close] can wait for it. */
    private val endLock = Any()
    private val place = IngestDecodingPlace(registry)

    // Video state, guarded by videoLock.
    private val videoLock = Any()
    private val videoDecoder = IngestVideoDecoder(name, softwareDecoding, VideoListener())
    private val jitter = IngestJitterBuffer()
    private val schedule = IngestDecodeSchedule()
    private val watchdog = IngestNoPictureWatchdog()
    private val inFlight = IngestDecoderInFlight()
    private val video = BufferedVideo<IngestVideoDecoder.DecodedFrame>(
        name = name,
        update = true,
        latency = latency,
        driftTracker = driftTracker,
        presentationTimeStamp = { it.ptsNanos / 1e9 },
        onRelease = { videoDecoder.release(it, render = false) },
        log = { Log.i(TAG, it) },
        newestPresentationTimeStamp = { newestVideoPtsNanos()?.let { it / 1e9 } },
    )
    private var videoCodec: IngestVideoCodec? = null
    private var videoConfig: ByteArray? = null
    private var surface: Surface? = null
    private var decoderConfigured = false

    /** The decoder generation whose start the video state already accounts for. */
    private var handledGeneration = -1
    private var newestDecodedPtsNanos: Long? = null
    private var lastDecodedPtsNanos: Long? = null
    private var frameIntervalNanos = DEFAULT_FRAME_INTERVAL_NANOS
    private var lastAddNanos: Long? = null

    // Audio state, guarded by audioLock.
    private val audioLock = Any()
    private val audioDecoder = IngestAudioDecoder(name, outputChannels, AudioListener())
    private val audio = BufferedAudio<AudioChunk>(
        name = name,
        latency = latency,
        manualOutput = true,
        driftTracker = driftTracker,
        presentationTimeStamp = { it.ptsNanos / 1e9 },
        log = { Log.i(TAG, it) },
    )

    private class AudioChunk(val pcm: ShortArray, val ptsNanos: Long)

    /** A frame has been rendered at least once. */
    @Volatile
    var hasVideo = false
        private set

    /** The PTS of the frame [updateVideo] rendered last (for tests). */
    @Volatile
    internal var lastRenderedPtsNanos = 0L
        private set

    /** The PTS of the chunk [audioChunk] played last, not counting silence (for tests). */
    @Volatile
    internal var lastAudioPtsNanos = 0L
        private set

    /** Decoded frames not yet released (for tests). */
    internal val heldFrames: Int get() = videoDecoder.heldFrames

    /** Compressed units waiting in the jitter buffer (for tests). */
    internal val queuedUnits: Int get() = synchronized(videoLock) { jitter.size }

    private val isActive get() = !closed.get() && !ended.get()

    /** The avcC/hvcC record; a new one restarts decoding at the next key frame. */
    fun setVideoConfig(codec: IngestVideoCodec, config: ByteArray) {
        if (!isActive) return
        if (!place.isHeld) {
            if (!place.take()) {
                end("Too many video sources")
                return
            }
            // A close or end racing the take may give the place back too: give() counts once.
            if (!isActive) {
                place.give()
                return
            }
        }
        synchronized(videoLock) {
            if (!isActive) return
            if (codec == videoCodec && config.contentEquals(videoConfig)) return
            videoCodec = codec
            videoConfig = config.copyOf()
            restartVideo()
            surface?.let { configureDecoder(codec, config, it) }
        }
    }

    /** One access unit of length-prefixed NAL units. */
    fun addVideo(isKeyFrame: Boolean, ptsNanos: Long, dtsNanos: Long, lengthPrefixed: ByteArray) {
        if (!isActive) return
        val now = clock.nanoTime()
        synchronized(videoLock) {
            // Checked under the lock, so nothing is added after shutdown() cleared the buffer.
            if (!isActive || !place.isHeld) return
            // Data again after a pause: whatever the decoder held back is not a dead decoder.
            lastAddNanos?.let { if (now - it > RESTART_GAP_NANOS) watchdog.onRestart() }
            lastAddNanos = now
            jitter.add(IngestVideoAccessUnit(isKeyFrame, ptsNanos, dtsNanos, lengthPrefixed))
        }
    }

    /** The AudioSpecificConfig. */
    fun setAudioConfig(audioSpecificConfig: ByteArray) {
        if (!isActive) return
        audioDecoder.configure(audioSpecificConfig)
    }

    /** One raw AAC frame; [aac] must not change afterwards. */
    fun addAudio(ptsNanos: Long, aac: ByteArray) {
        if (!isActive) return
        audioDecoder.queue(aac, ptsNanos)
    }

    /** The surface to decode into: the first one, or a new one after the pipeline was re-created. */
    fun attachSurface(surface: Surface) {
        if (!isActive) return
        synchronized(videoLock) {
            if (!isActive) return
            this.surface = surface
            if (decoderConfigured) {
                videoDecoder.setSurface(surface)
            } else {
                val codec = videoCodec ?: return
                configureDecoder(codec, checkNotNull(videoConfig), surface)
            }
        }
    }

    /**
     * Feeds the decoder, then selects the frame for [outputNanos] and renders it to the surface.
     * True when a new frame was rendered.
     */
    fun updateVideo(outputNanos: Long): Boolean {
        if (!isActive) return false
        var noPicture = false
        val rendered = synchronized(videoLock) {
            if (!decoderConfigured) return false
            val now = clock.nanoTime()
            schedule.onTick(now)
            val driftNanos = (driftTracker.getDrift() * 1e9).toLong()
            feed(outputNanos + schedule.aheadNanos - driftNanos, now)
            if (watchdog.expired(now)) {
                noPicture = true
                return@synchronized false
            }
            val frame = video.updateFrame(outputNanos / 1e9) ?: return@synchronized false
            // A frame of a codec that is gone (recreated since) is not rendered.
            if (!videoDecoder.release(frame, render = true)) return@synchronized false
            if (outputNanos - (frame.ptsNanos + driftNanos) > frameIntervalNanos) schedule.onLate()
            lastRenderedPtsNanos = frame.ptsNanos
            hasVideo = true
            true
        }
        if (noPicture) end("Video decoder produced no picture")
        return rendered
    }

    /** 1024 frames at 48 kHz for [outputNanos], zeros in gaps; null before the first chunk arrived. */
    fun audioChunk(outputNanos: Long): ShortArray? {
        if (!isActive) return null
        val output = synchronized(audioLock) { audio.getFrame(outputNanos / 1e9) } ?: return null
        if (output.silent) return ShortArray(CHUNK_FRAMES * outputChannels)
        lastAudioPtsNanos = output.frame.ptsNanos
        return output.frame.pcm
    }

    /** Stops decoding and frees everything. No [onEnded] comes after this returns. */
    fun close() {
        closed.set(true)
        // Waits for an onEnded in progress (re-entrant when close is called from it).
        synchronized(endLock) {}
        shutdown()
    }

    // Video helpers: called with videoLock held.

    private fun configureDecoder(codec: IngestVideoCodec, config: ByteArray, surface: Surface) {
        videoDecoder.configure(codec, config, surface)
        decoderConfigured = true
        handledGeneration = videoDecoder.currentGeneration
    }

    /** A new config: drops what was buffered; the decoder starts again at a key frame. */
    private fun restartVideo() {
        jitter.clear()
        video.clear()
        inFlight.clear()
        watchdog.onRestart()
        newestDecodedPtsNanos = null
        lastDecodedPtsNanos = null
    }

    /** Queues due units while the decoder takes them and the held and in-flight bounds allow. */
    private fun feed(limitDtsNanos: Long, now: Long) {
        while (videoDecoder.canQueue() && videoDecoder.heldFrames < MAX_HELD_FRAMES && inFlight.size < MAX_IN_FLIGHT) {
            val unit = jitter.takeDue(limitDtsNanos) ?: return
            inFlight.add(unit.ptsNanos)
            watchdog.onQueued(now)
            videoDecoder.queue(unit)
        }
    }

    private fun newestVideoPtsNanos(): Long? {
        val queued = jitter.newestPtsNanos()
        val decoded = newestDecodedPtsNanos
        return if (queued == null || decoded == null) queued ?: decoded else maxOf(queued, decoded)
    }

    private fun onDecodedFrame(frame: IngestVideoDecoder.DecodedFrame) {
        val pts = frame.ptsNanos
        watchdog.onPicture()
        inFlight.onPicture(pts)
        // A jump back of more than a second is a new timeline, not a reordered frame.
        val newest = newestDecodedPtsNanos
        if (newest == null || pts > newest || newest - pts > TIMELINE_JUMP_NANOS) newestDecodedPtsNanos = pts
        lastDecodedPtsNanos?.let { last ->
            val interval = pts - last
            if (interval in MIN_FRAME_INTERVAL_NANOS..MAX_FRAME_INTERVAL_NANOS) frameIntervalNanos = interval
        }
        lastDecodedPtsNanos = pts
        video.appendFrame(frame)
    }

    // Lifecycle.

    /** Ends the source with [reason]: [onEnded] once, then closed. Called without state locks. */
    private fun end(reason: String) {
        if (!ended.compareAndSet(false, true)) return
        Log.w(TAG, "$name: ended: $reason")
        synchronized(endLock) {
            if (!closed.get()) {
                try {
                    onEnded(reason)
                } catch (e: Throwable) {
                    Log.e(TAG, "$name: onEnded threw", e)
                }
            }
        }
        shutdown()
    }

    /** Closes the decoders (no lock held) and drops all buffered media. Idempotent. */
    private fun shutdown() {
        videoDecoder.close()
        audioDecoder.close()
        synchronized(videoLock) {
            jitter.clear()
            video.clear()
            inFlight.clear()
        }
        synchronized(audioLock) { audio.clear() }
        place.give()
    }

    private inner class VideoListener : IngestVideoDecoder.Listener {
        override fun onFrame(frame: IngestVideoDecoder.DecodedFrame) {
            synchronized(videoLock) {
                if (isActive) onDecodedFrame(frame) else videoDecoder.release(frame, render = false)
            }
        }

        override fun onRestarted() {
            synchronized(videoLock) {
                if (!isActive) return
                // A start that a new config caused was handled when it was configured; units
                // added since are for the new codec and stay.
                val generation = videoDecoder.currentGeneration
                if (generation == handledGeneration) return
                handledGeneration = generation
                restartVideo()
            }
        }

        override fun onFormat(width: Int, height: Int) {
            Log.i(TAG, "$name: video $width×$height")
        }

        override fun onFailed(reason: String) = end(reason)
    }

    private inner class AudioListener : IngestAudioDecoder.Listener {
        override fun onChunk(pcm: ShortArray, ptsNanos: Long) {
            synchronized(audioLock) {
                if (isActive) audio.appendFrame(AudioChunk(pcm, ptsNanos))
            }
        }

        override fun onFailed(reason: String) = end(reason)
    }

    companion object {
        /** Sources decoding video at once in the process (spec decision 7). */
        const val MAX_DECODING = 2

        private const val TAG = "IngestSource"
        private const val CHUNK_FRAMES = 1024
        private const val MAX_HELD_FRAMES = 6
        private const val MAX_IN_FLIGHT = 16
        private const val RESTART_GAP_NANOS = 1_000_000_000L
        private const val TIMELINE_JUMP_NANOS = 1_000_000_000L
        private const val DEFAULT_FRAME_INTERVAL_NANOS = 33_333_333L
        private const val MIN_FRAME_INTERVAL_NANOS = 1_000_000L
        private const val MAX_FRAME_INTERVAL_NANOS = 1_000_000_000L

        private val registry = IngestDecodingRegistry(MAX_DECODING)

        /** Sources that hold one of the [MAX_DECODING] places. */
        internal val decodingSources: Int get() = registry.count
    }
}
