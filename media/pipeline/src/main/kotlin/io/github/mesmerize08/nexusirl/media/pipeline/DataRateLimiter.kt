// Port of upstream `createDataRateLimits` (Moblin/Media/HaishinKit/Codec/Video/VideoEncoderSettings.swift
// @97e26d2), enforced outside the encoder.
package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Keeps the encoder's output within [FACTOR] times its bitrate (upstream: 1.2x per second).
 * VideoToolbox enforces that itself; MediaCodec has no such limit, and some hardware encoders send
 * far more than asked (field test 2026-11-01: the Snapdragon HEVC encoder sent 7-14 Mbps for
 * 0.25-5 Mbps targets while driving, congesting the link). So frames are not handed to the encoder
 * while it has used up its allowance.
 *
 * The allowance is a token bucket: it fills at [FACTOR] times the current bitrate and holds at most
 * one second of that. When the bitrate drops, the bucket is only capped, so output that was fine
 * at the old bitrate is not held against the new one (which would starve the encoder of frames
 * just as it adapts).
 */
internal class DataRateLimiter {
    private var bytes = Double.NaN
    private var updatedNanos = 0L

    /** Records [size] bytes of encoder output at [nowNanos], made at [bitrate]. */
    @Synchronized
    fun add(nowNanos: Long, bitrate: Long, size: Int) {
        refill(nowNanos, bitrate)
        bytes -= size
    }

    /** Whether the next frame may be encoded at [nowNanos] for an encoder running at [bitrate]. */
    @Synchronized
    fun allows(nowNanos: Long, bitrate: Long): Boolean {
        refill(nowNanos, bitrate)
        return bytes > 0
    }

    private fun refill(nowNanos: Long, bitrate: Long) {
        val bytesPerSecond = bitrate * FACTOR / 8
        bytes = if (bytes.isNaN()) {
            bytesPerSecond
        } else {
            minOf(bytesPerSecond, bytes + bytesPerSecond * (nowNanos - updatedNanos).coerceAtLeast(0) / 1e9)
        }
        updatedNanos = nowNanos
    }

    companion object {
        /** Upstream `videoEncoderDataRateLimitFactor` (1.2, also below 1 Mbps). */
        const val FACTOR = 1.2
    }
}
