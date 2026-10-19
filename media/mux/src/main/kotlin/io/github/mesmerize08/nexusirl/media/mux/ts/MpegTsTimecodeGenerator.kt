// Port of Moblin/Media/HaishinKit/Mpeg/MpegTsTimecodeGenerator.swift @97e26d2
package io.github.mesmerize08.nexusirl.media.mux.ts

import io.github.mesmerize08.nexusirl.core.clock.MediaTime
import io.github.mesmerize08.nexusirl.media.mux.nal.SeiTimecode
import java.time.Instant

/** A wall-clock timecode: [clock] in seconds since the Unix epoch, and the frame within its second. */
data class MpegTsTimecode(val clock: Double, val frame: Long) {
    fun toSeiTimecode(): SeiTimecode {
        val seconds = Math.floor(clock)
        val instant = Instant.ofEpochSecond(seconds.toLong(), ((clock - seconds) * 1e9).toLong())
        return SeiTimecode.fromClock(instant, frame)
    }
}

/**
 * Maps presentation timestamps to NTP-synchronized wall-clock timecodes so that several cameras
 * can be aligned in post production.
 */
class MpegTsTimecodeGenerator {
    private var presentationTimeStampBase: Double? = null
    private var previousDecodeTimeStamp: Double? = null
    private var estimatedFrameDuration = 0.033
    private var offsetingFrames = false

    fun reset() {
        presentationTimeStampBase = null
        previousDecodeTimeStamp = null
    }

    fun hasReference(): Boolean = presentationTimeStampBase != null

    /** [now] is NTP time in seconds since the Unix epoch; [presentationTimeStamp] is the host clock now. */
    fun setReference(now: Double, presentationTimeStamp: Double) {
        presentationTimeStampBase = now - presentationTimeStamp
    }

    fun makeTimecode(presentationTimeStamp: MediaTime, decodeTimeStamp: MediaTime?): MpegTsTimecode? {
        val base = presentationTimeStampBase ?: return null
        val presentation = presentationTimeStamp.seconds
        val decode = decodeTimeStamp?.seconds ?: presentation
        previousDecodeTimeStamp?.let { previous ->
            estimatedFrameDuration = maxOf(0.7 * estimatedFrameDuration + 0.3 * (decode - previous), 0.001)
        }
        previousDecodeTimeStamp = decode
        val now = base + presentation + (if (offsetingFrames) estimatedFrameDuration / 2 else 0.0)
        val offsetWithinSecond = now % 1.0
        val frame = offsetWithinSecond / estimatedFrameDuration
        val offsetFromFrame = offsetWithinSecond - Math.floor(frame) * estimatedFrameDuration
        if (offsetFromFrame < estimatedFrameDuration / 6 || offsetFromFrame > estimatedFrameDuration * 5 / 6) {
            offsetingFrames = !offsetingFrames
        }
        return MpegTsTimecode(now, frame.toLong())
    }
}
