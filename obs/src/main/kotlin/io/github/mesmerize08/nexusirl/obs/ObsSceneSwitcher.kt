// Port of the OBS scene switcher in Moblin/Various/Model/ModelObs.swift @97e26d2
// (isStreamLikelyBroken and updateObsSceneSwitcherStreamingViaRelay).
package io.github.mesmerize08.nexusirl.obs

/**
 * Switches OBS to the BRB scene while the stream from the phone is likely broken, and back to
 * the main scene once it works again. Only ever switches between those two scenes, so a scene
 * the streamer picked by hand is left alone. [clock] is in milliseconds.
 */
class ObsSceneSwitcher(private val clock: () -> Long) {
    private var brokenSinceMillis: Long? = null

    /**
     * Called once a second while live and connected to OBS. Returns the scene to switch to, or
     * null. [disconnected]: the stream is reconnecting; [droppedPackets]: packets were dropped
     * since the last call.
     */
    fun update(currentScene: String, mainScene: String, brbScene: String, disconnected: Boolean, droppedPackets: Boolean): String? {
        if (currentScene.isEmpty() || mainScene.isEmpty() || brbScene.isEmpty()) {
            return null
        }
        return if (isStreamLikelyBroken(currentScene, brbScene, disconnected, droppedPackets)) {
            brbScene.takeIf { currentScene == mainScene }
        } else {
            mainScene.takeIf { currentScene == brbScene }
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
    }
}
