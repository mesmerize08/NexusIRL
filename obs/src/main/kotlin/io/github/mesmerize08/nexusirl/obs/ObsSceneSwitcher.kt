// Port of the OBS scene switcher in Moblin/Various/Model/ModelObs.swift @97e26d2
// (isStreamLikelyBroken and updateObsSceneSwitcherStreamingViaRelay).
package io.github.mesmerize08.nexusirl.obs

/** Switch OBS to [scene]; [toast]: tell the streamer (the first attempt for this target). */
data class ObsSceneSwitch(val scene: String, val toast: Boolean)

/**
 * Switches OBS to the BRB scene while the stream from the phone is likely broken, and back to
 * the main scene once it works again. Only ever switches between those two scenes, so a scene
 * the streamer picked by hand is left alone. [clock] is in milliseconds.
 *
 * Unlike upstream, which asks (and toasts) every second until OBS reports the new scene, one
 * request at a time, retried [RETRY_MILLIS] after a failure, with one toast per target.
 */
class ObsSceneSwitcher(private val clock: () -> Long) {
    private var brokenSinceMillis: Long? = null
    private var inFlight: String? = null
    private var failed: String? = null
    private var failedAtMillis = 0L
    private var toasted: String? = null

    /**
     * Called once a second while live and connected to OBS. Returns the scene to switch to, or
     * null. [disconnected]: the stream is reconnecting; [droppedPackets]: packets were dropped
     * since the last call. Report how a returned switch went with [finished].
     */
    fun update(currentScene: String, mainScene: String, brbScene: String, disconnected: Boolean, droppedPackets: Boolean): ObsSceneSwitch? {
        if (currentScene.isEmpty() || mainScene.isEmpty() || brbScene.isEmpty()) {
            return null
        }
        val target = if (isStreamLikelyBroken(currentScene, brbScene, disconnected, droppedPackets)) {
            brbScene.takeIf { currentScene == mainScene }
        } else {
            mainScene.takeIf { currentScene == brbScene }
        }
        if (target == null) {
            toasted = null
            return null
        }
        val now = clock()
        if (target == inFlight || (target == failed && now - failedAtMillis < RETRY_MILLIS)) {
            return null
        }
        inFlight = target
        val toast = target != toasted
        toasted = target
        return ObsSceneSwitch(target, toast)
    }

    /** The request to switch to [scene] (from [update]) succeeded or failed. */
    fun finished(scene: String, success: Boolean) {
        if (inFlight == scene) {
            inFlight = null
        }
        if (success) {
            failed = null
        } else {
            failed = scene
            failedAtMillis = clock()
        }
    }

    private fun isStreamLikelyBroken(currentScene: String, brbScene: String, disconnected: Boolean, droppedPackets: Boolean): Boolean {
        val now = clock()
        if (disconnected) {
            return true
        }
        if (droppedPackets) {
            brokenSinceMillis = now
            return true
        }
        brokenSinceMillis?.let { since ->
            // Stay on BRB a while after packets were dropped, so a shaky link does not flap.
            if (now - since < BROKEN_HOLD_MILLIS || currentScene != brbScene) {
                return true
            }
        }
        brokenSinceMillis = null
        return false
    }

    private companion object {
        const val BROKEN_HOLD_MILLIS = 15_000L
        const val RETRY_MILLIS = 10_000L
    }
}
