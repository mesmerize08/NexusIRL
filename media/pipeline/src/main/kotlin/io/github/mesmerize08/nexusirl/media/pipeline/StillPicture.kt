package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Whether the frame timer is repeating one camera image: still after [thresholdTicks] frame times
 * without a new image (the camera taken by another app, stalled, switching, or not started yet),
 * moving again on the next image. Decided from the frames themselves rather than camera events,
 * which can arrive out of order with the images (an image in flight when the camera disconnects
 * arrives after the loss).
 */
internal class StillPicture(private val thresholdTicks: Int) {
    private var ticksWithoutImage = 0
    private var still = false

    /** For an encoder attached while the picture is still. */
    val isStill: Boolean
        get() = still

    /** A frame time passed; returns true when the picture just became still, else null. */
    fun tick(): Boolean? {
        ticksWithoutImage++
        if (still || ticksWithoutImage < thresholdTicks) {
            return null
        }
        still = true
        return true
    }

    /** A new camera image arrived; returns false when the picture was still, else null. */
    fun cameraImage(): Boolean? {
        ticksWithoutImage = 0
        if (!still) {
            return null
        }
        still = false
        return false
    }
}
