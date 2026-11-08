package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * The bitrate an encoder is given: the one asked for (stream setting, adaptive bitrate), or a
 * [STILL_DIVISOR]th of it while the picture is still because the camera is gone (another app took
 * it). Not upstream: VideoToolbox copes with a repeated picture, MediaCodec CBR does not.
 *
 * Field test 2026-11-08 (S26 Ultra, c2.qti.hevc.encoder, 5 Mbps CBR): with nothing to spend its
 * bits on, CBR rate control drove its quantizer to the floor, made 1 MB keyframes (10x normal), and
 * once the camera was back, P-frames of 0.4-0.8 MB. The [DataRateLimiter] then let through about
 * 2 frames a second, each frame a second apart and so as large again, for 7 seconds. At a tenth
 * the encoder stays near its usual quantizer (EncoderRateControlProbe, -e probe still: keyframes
 * 150 kB instead of 0.8 MB, no frames held back after the return).
 */
internal class EncoderBitrate(private var requested: Long) {
    private var still = false

    var applied = requested
        private set

    /** Sets the bitrate asked for; returns the bitrate to apply, or null when it is unchanged. */
    fun request(bitrate: Long): Long? {
        requested = bitrate
        return update()
    }

    /** Whether the picture is still; returns the bitrate to apply, or null when it is unchanged. */
    fun setStill(still: Boolean): Long? {
        this.still = still
        return update()
    }

    private fun update(): Long? {
        val bitrate = if (still) requested / STILL_DIVISOR else requested
        if (bitrate == applied) {
            return null
        }
        applied = bitrate
        return bitrate
    }

    companion object {
        const val STILL_DIVISOR = 10
    }
}
