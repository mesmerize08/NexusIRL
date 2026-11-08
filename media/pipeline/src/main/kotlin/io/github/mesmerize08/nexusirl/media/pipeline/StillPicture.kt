package io.github.mesmerize08.nexusirl.media.pipeline

/**
 * Whether the composited picture is effectively static: still after [thresholdTicks] frame times
 * without a new camera image (the camera taken by another app, or stalled), unless widgets are
 * animating (at least [animatingChanges] changes in the last [windowTicks] frame times) or a camera
 * switch is under way (its transition animates, and the next camera comes soon). Moving again on
 * the next camera image, when widgets start animating, or when a switch starts.
 *
 * Decided from the frames themselves rather than camera events, which can arrive out of order with
 * the images (an image in flight when the camera disconnects arrives after the loss).
 */
internal class StillPicture(private val thresholdTicks: Int, windowTicks: Int, private val animatingChanges: Int) {
    private var ticksWithoutImage = 0
    private var still = false

    /** Whether the content changed in each of the last frame times (a ring). */
    private val changes = BooleanArray(windowTicks)
    private var changeIndex = 0
    private var changeCount = 0

    /** For an encoder attached while the picture is still. */
    val isStill: Boolean
        get() = still

    /**
     * A frame time passed; [contentChanged] when widgets drew something new for it, [switching]
     * while a camera switch is under way. Returns the new state when it changed, else null.
     */
    fun tick(contentChanged: Boolean, switching: Boolean): Boolean? {
        if (changes[changeIndex]) {
            changeCount--
        }
        changes[changeIndex] = contentChanged
        if (contentChanged) {
            changeCount++
        }
        changeIndex = (changeIndex + 1) % changes.size
        if (switching) {
            ticksWithoutImage = 0
            return set(false)
        }
        ticksWithoutImage++
        return set(ticksWithoutImage >= thresholdTicks && changeCount < animatingChanges)
    }

    /** A new camera image arrived; returns false when the picture was still, else null. */
    fun cameraImage(): Boolean? {
        ticksWithoutImage = 0
        return set(false)
    }

    private fun set(still: Boolean): Boolean? {
        if (still == this.still) {
            return null
        }
        this.still = still
        return still
    }
}
