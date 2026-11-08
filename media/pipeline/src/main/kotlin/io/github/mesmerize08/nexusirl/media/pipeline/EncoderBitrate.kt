package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * The bitrate an encoder should run at: the one asked for (stream setting, bitrate preset,
 * adaptive bitrate), or a [STILL_DIVISOR]th of it, but at least [STILL_MINIMUM], while the picture
 * is still ([StillPicture]: the camera is gone, another app took it). Not upstream: VideoToolbox
 * copes with a repeated picture, MediaCodec CBR does not.
 *
 * Field test 2026-11-08 (S26 Ultra, c2.qti.hevc.encoder, 5 Mbps CBR): with nothing to spend its
 * bits on, CBR rate control drove its quantizer to the floor, made 1 MB keyframes (10x normal), and
 * once the camera was back, P-frames of 0.4-0.8 MB. The [DataRateLimiter] then let through about
 * 2 frames a second, each frame a second apart and so as large again, for 7 seconds. At a tenth
 * the encoder stays near its usual quantizer (EncoderRateControlProbe, -e probe still: keyframes
 * of at most 0.26 MB instead of 0.8 MB, 30 fps after the return; at 1 Mbps, held at the minimum,
 * likewise).
 *
 * The minimum keeps the limiter's allowance above such a keyframe or two: a tenth of 2 Mbps would
 * let a frame through every few seconds while still.
 */
internal class EncoderBitrate(requested: Long) {
    @Volatile
    var requested = requested

    @Volatile
    var still = false

    val target: Long
        get() {
            val requested = requested
            return if (still) maxOf(requested / STILL_DIVISOR, minOf(requested, STILL_MINIMUM)) else requested
        }

    companion object {
        const val STILL_DIVISOR = 10
        const val STILL_MINIMUM = 500_000L
    }
}
