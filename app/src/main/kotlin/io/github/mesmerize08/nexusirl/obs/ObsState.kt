// Port of QuickButtonObs and statusObsText in Moblin/Various/Model/ModelObs.swift @97e26d2.
package io.github.mesmerize08.nexusirl.obs

import java.util.Locale

/** Upstream `ObsSceneInput`: an audio input of the current scene; [muted] null while unknown. */
data class ObsSceneInput(val name: String, val muted: Boolean?)

/** Upstream `ObsSceneMediaSource`: a media source of the current scene playing a URL. */
data class ObsSceneMediaSource(val name: String, val input: String)

/** What the app knows about OBS (upstream `QuickButtonObs`). */
data class ObsState(
    /** The selected stream has OBS remote control on, with a URL. */
    val configured: Boolean = false,
    val connected: Boolean = false,
    /** Why it is not connected, such as "Authentication failed.". */
    val error: String = "",
    val currentScene: String = "",
    val scenes: List<String> = emptyList(),
    val streaming: Boolean = false,
    val streamingState: ObsOutputState = ObsOutputState.STOPPED,
    val recording: Boolean = false,
    val recordingState: ObsOutputState = ObsOutputState.STOPPED,
    val sceneInputs: List<ObsSceneInput> = emptyList(),
    val sceneMediaSources: List<ObsSceneMediaSource> = emptyList(),
    /** The source's audio levels while the OBS panel is open, or empty. */
    val audioLevels: String = "",
    val audioDelayMs: Int = 0,
    val fixing: Boolean = false,
) {
    /** Upstream `statusObsText`, also sent to remote control assistants. */
    val statusText: String
        get() = when {
            !configured -> "Not configured"
            connected -> when {
                streaming && recording -> "$currentScene (Streaming, Recording)"
                streaming -> "$currentScene (Streaming)"
                recording -> "$currentScene (Recording)"
                else -> currentScene
            }
            else -> error.ifEmpty { "Connecting" }
        }
}

/** Upstream `obsWebsocketAudioVolume`: each channel in dB, "Muted" when silent. */
fun obsAudioLevelsText(volumes: List<Float>): String =
    volumes.joinToString(", ") { if (it.isInfinite()) "Muted" else String.format(Locale.US, "%.1f dB", it) }

/** Upstream `obsWebsocketStreamStatusChanged`: the state, or one derived from [active] when not known. */
fun outputState(active: Boolean, state: ObsOutputState?): ObsOutputState =
    state ?: if (active) ObsOutputState.STARTED else ObsOutputState.STOPPED
