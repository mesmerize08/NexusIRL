// Port of Moblin/Various/Model/ModelObs.swift @97e26d2 and its calls from ModelStream.swift
// (auto start and stop) and Model.swift (the 1 s and 10 s timers).
package io.github.mesmerize08.nexusirl.obs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.mesmerize08.nexusirl.chat.ChatController
import io.github.mesmerize08.nexusirl.core.secrets.MissingSecretException
import io.github.mesmerize08.nexusirl.core.websocket.CleartextPolicy
import io.github.mesmerize08.nexusirl.settings.SettingsMoblinkRelay
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.settings.SettingsStream
import io.github.mesmerize08.nexusirl.stream.StreamController
import io.github.mesmerize08.nexusirl.stream.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI

/**
 * OBS remote control for the selected stream: while it has OBS remote control on, stays
 * connected to OBS (live or not, as upstream), keeps [state] up to date, starts and stops OBS's
 * stream and recording when going live and ending if asked to, and switches between the main
 * and BRB scenes while live.
 *
 * Main thread.
 */
object ObsController {
    private val handler = Handler(Looper.getMainLooper())
    private var store: SettingsStore? = null
    private var scope: CoroutineScope? = null
    private var obs: ObsWebSocket? = null
    private var switcher = ObsSceneSwitcher(SystemClock::elapsedRealtime)
    private var ticks = 0L

    /** Upstream `sourceFetchScreenshot`: the OBS panel is open. */
    private var panelOpen = false
    private var screenshotFetching = false
    private var audioLevelsLatest = ""

    private val mutableState = MutableStateFlow(ObsState())
    val state: StateFlow<ObsState> = mutableState

    private val mutableScreenshot = MutableStateFlow<ByteArray?>(null)

    /** A JPEG of OBS's current scene, about once a second while the OBS panel is open. */
    val screenshot: StateFlow<ByteArray?> = mutableScreenshot

    private data class Config(val configured: Boolean, val url: String, val password: String)

    fun start(context: Context) {
        if (scope != null) {
            return
        }
        val settings = StreamController.settings(context.applicationContext)
        store = settings
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch {
            // References only: the secrets are resolved when connecting.
            settings.database
                .map { database ->
                    val stream = database.selectedStream
                    Config(
                        stream?.isObsRemoteControlConfigured == true,
                        stream?.obsWebSocketUrl.orEmpty(),
                        stream?.obsWebSocketPassword.orEmpty(),
                    )
                }
                .distinctUntilChanged()
                .collect { reload(it) }
        }
    }

    private fun selectedStream(): SettingsStream? = store?.current?.selectedStream

    /** Upstream `reloadObsWebSocket`. */
    private fun reload(config: Config) {
        obs?.stop()
        obs = null
        handler.removeCallbacks(tick)
        mutableScreenshot.value = null
        switcher = ObsSceneSwitcher(SystemClock::elapsedRealtime)
        val settings = store ?: return
        val stream = selectedStream()
        if (!config.configured || stream == null) {
            mutableState.value = ObsState()
            return
        }
        val (url, password) = try {
            settings.obsWebSocketUrl(stream) to settings.obsWebSocketPassword(stream)
        } catch (e: MissingSecretException) {
            mutableState.value =
                ObsState(configured = true, error = "The OBS password is missing. Enter it again in the stream's settings.")
            return
        }
        if (!SettingsMoblinkRelay.isValidWebSocketUrl(url)) {
            mutableState.value = ObsState(configured = true, error = "Enter a ws:// or wss:// URL in the stream's settings")
            return
        }
        // The client would refuse it on every attempt; say why instead (docs/PORT_PLAN.md §9.5).
        val host = runCatching { URI(url).host }.getOrNull()
        if (url.startsWith("ws:", ignoreCase = true) && (host == null || !CleartextPolicy.isAllowed(host, allowLocalNetwork = true))) {
            mutableState.value = ObsState(configured = true, error = "ws:// is only allowed to the local network; use wss://")
            return
        }
        mutableState.value = ObsState(configured = true)
        lateinit var socket: ObsWebSocket
        socket = ObsWebSocket(url, password, Listener { block -> handler.post { if (obs === socket) block() } })
        obs = socket
        socket.start()
        handler.postDelayed(tick, TICK_MILLIS)
    }

    /** Forwards OBS's callbacks to the main thread, for the current connection only. */
    private class Listener(private val post: (() -> Unit) -> Unit) : ObsWebSocketListener {
        override fun connected() = post {
            mutableState.update { it.copy(connected = true, error = "") }
            updateStatus()
            if (panelOpen) {
                openPanel()
            }
        }

        override fun disconnected() = post {
            mutableState.update { it.copy(connected = false, error = obs?.connectionErrorMessage.orEmpty()) }
        }

        override fun sceneChanged(sceneName: String) = post {
            mutableState.update { it.copy(currentScene = sceneName) }
            updateAudioInputs(sceneName)
            updateMediaInputs(sceneName)
        }

        override fun inputMuteStateChanged(inputName: String, muted: Boolean) = post {
            mutableState.update { state ->
                state.copy(sceneInputs = state.sceneInputs.map { if (it.name == inputName) it.copy(muted = muted) else it })
            }
        }

        override fun streamStatusChanged(active: Boolean, state: ObsOutputState?) = post { updateStreamStatus(active, state) }

        override fun recordStatusChanged(active: Boolean, state: ObsOutputState?) = post { updateRecordStatus(active, state) }

        override fun audioVolume(volumes: List<ObsAudioInputVolume>) = post {
            val sourceName = selectedStream()?.obsSourceName.orEmpty()
            val volume = volumes.firstOrNull { it.name == sourceName }
            audioLevelsLatest = volume?.let { obsAudioLevelsText(it.volumes) } ?: "Source $sourceName not found"
        }
    }

    private fun updateStreamStatus(active: Boolean, state: ObsOutputState?) =
        mutableState.update { it.copy(streaming = active, streamingState = outputState(active, state)) }

    private fun updateRecordStatus(active: Boolean, state: ObsOutputState?) =
        mutableState.update { it.copy(recording = active, recordingState = outputState(active, state)) }

    private val tick = object : Runnable {
        override fun run() {
            ticks += 1
            updateSceneSwitcher()
            if (panelOpen) {
                updateScreenshot()
                if (mutableState.value.audioLevels != audioLevelsLatest) {
                    mutableState.update { it.copy(audioLevels = audioLevelsLatest) }
                }
            }
            if (ticks % 10 == 0L) {
                updateStatus()
            }
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    /** Upstream `updateObsStatus`, every 10 s and on connect. */
    private fun updateStatus() {
        val obs = obs?.takeIf { it.isConnected } ?: return
        obs.getStreamStatus { result -> handler.post { updateStreamStatus(result.getOrDefault(false), null) } }
        obs.getRecordStatus { result -> handler.post { updateRecordStatus(result.getOrDefault(false), null) } }
        listScenes()
    }

    /** Upstream `listObsScenes`. */
    private fun listScenes(updateAudioInputs: Boolean = false) {
        obs?.getSceneList { result ->
            handler.post {
                val list = result.getOrNull() ?: return@post
                mutableState.update { it.copy(currentScene = list.current, scenes = list.scenes) }
                if (updateAudioInputs) {
                    updateAudioInputs(list.current)
                }
                updateMediaInputs(list.current)
            }
        }
    }

    /** Upstream `updateObsMediaInputs`: the scene's media sources that play a URL, not a file. */
    private fun updateMediaInputs(sceneName: String) {
        val obs = obs ?: return
        obs.getSceneItemList(sceneName) { items ->
            val names = items.getOrNull()?.filter { it.inputKind == "ffmpeg_source" }?.map { it.sourceName }
            if (names == null) {
                handler.post { mutableState.update { it.copy(sceneMediaSources = emptyList()) } }
                return@getSceneItemList
            }
            if (names.isEmpty()) {
                return@getSceneItemList
            }
            obs.getMediaSourcesSettingsBatch(names) { result ->
                val sources = result.getOrNull()?.let { settings ->
                    names.zip(settings).mapNotNull { (name, setting) ->
                        setting?.takeIf { !it.isLocalFile }?.let { ObsSceneMediaSource(name, it.input) }
                    }
                }.orEmpty()
                handler.post { mutableState.update { it.copy(sceneMediaSources = sources) } }
            }
        }
    }

    /** Upstream `updateObsAudioInputs`: OBS's global mics, and the scene's enabled inputs, with their mute state. */
    private fun updateAudioInputs(sceneName: String) {
        val obs = obs ?: return
        fun publish(sceneInputs: List<ObsSceneInput>) {
            handler.post { mutableState.update { it.copy(sceneInputs = sceneInputs) } }
        }
        obs.getInputList { inputsResult ->
            val inputs = inputsResult.getOrNull()
            if (inputs == null) {
                publish(emptyList())
                return@getInputList
            }
            obs.getSpecialInputs { specialResult ->
                val mics = specialResult.getOrNull()?.mics
                if (mics == null) {
                    publish(emptyList())
                    return@getSpecialInputs
                }
                obs.getSceneItemList(sceneName) { itemsResult ->
                    val items = itemsResult.getOrNull()
                    if (items.isNullOrEmpty()) {
                        publish(emptyList())
                        return@getSceneItemList
                    }
                    val names = inputs.filter { input ->
                        input in mics || items.firstOrNull { it.sourceName == input }?.sceneItemEnabled == true
                    }
                    obs.getInputMuteBatch(names) { mutedResult ->
                        val muted = mutedResult.getOrNull()?.takeIf { it.size == names.size }
                        publish(muted?.let { names.zip(it).map { (name, isMuted) -> ObsSceneInput(name, isMuted) } }.orEmpty())
                    }
                }
            }
        }
    }

    /** Upstream `updateObsSourceScreenshot`: one request at a time. */
    private fun updateScreenshot() {
        val scene = mutableState.value.currentScene
        val obs = obs?.takeIf { it.isConnected } ?: return
        if (screenshotFetching || scene.isEmpty()) {
            return
        }
        screenshotFetching = true
        obs.getSourceScreenshot(scene) { result ->
            handler.post {
                screenshotFetching = false
                if (panelOpen) {
                    mutableScreenshot.value = result.getOrNull()
                }
            }
        }
    }

    /** Upstream `updateObsSceneSwitcher` (streaming via a relay or server; not "streaming directly to OBS"). */
    private fun updateSceneSwitcher() {
        val stream = selectedStream() ?: return
        val state = mutableState.value
        if (!StreamController.isLive || !state.connected) {
            return
        }
        val target = switcher.update(
            state.currentScene,
            stream.obsMainScene,
            stream.obsBrbScene,
            disconnected = StreamController.status.value.state == StreamState.RECONNECTING,
            // Android's transports do not count dropped packets.
            droppedPackets = false,
        ) ?: return
        if (target == stream.obsBrbScene) {
            ChatController.showToast("😠 Stream likely broken 😠 Trying to switch OBS scene to $target")
        } else {
            ChatController.showToast("🥳 Stream likely working 🥳 Trying to switch OBS scene to $target")
        }
        setScene(target)
    }

    /** The OBS panel opened: upstream's onAppear. */
    fun openPanel() {
        panelOpen = true
        mutableScreenshot.value = null
        screenshotFetching = false
        audioLevelsLatest = ""
        mutableState.update { it.copy(audioLevels = "") }
        listScenes(updateAudioInputs = true)
        obs?.startAudioVolume()
        updateAudioDelay()
    }

    fun closePanel() {
        panelOpen = false
        mutableScreenshot.value = null
        obs?.stopAudioVolume()
    }

    /** Upstream `setObsScene`. */
    fun setScene(name: String) {
        updateMediaInputs(name)
        obs?.setCurrentProgramScene(name) { result ->
            handler.post {
                result.onSuccess {
                    mutableState.update { it.copy(currentScene = name) }
                    updateAudioInputs(name)
                }.onFailure { ChatController.showToast("Failed to set OBS scene to $name: ${it.message}") }
            }
        }
    }

    private fun toastOnFailure(title: String): (Result<Unit>) -> Unit = { result ->
        result.onFailure { error -> handler.post { ChatController.showToast("$title: ${error.message}") } }
    }

    fun startStream() {
        obs?.startStream(toastOnFailure("Failed to start OBS stream"))
    }

    fun stopStream() {
        obs?.stopStream(toastOnFailure("Failed to stop OBS stream"))
    }

    fun startRecording() {
        obs?.startRecord(toastOnFailure("Failed to start OBS recording"))
    }

    fun stopRecording() {
        obs?.stopRecord(toastOnFailure("Failed to stop OBS recording"))
    }

    /** Upstream `obsMuteAudio`; the change shows once OBS reports it. */
    fun setMuted(inputName: String, muted: Boolean) {
        obs?.setInputMute(inputName, muted)
    }

    /**
     * Upstream `obsFixStream`: restarts the stream's source in OBS. Returns false when OBS remote
     * control is not set up.
     */
    fun fixSource(): Boolean {
        val obs = obs ?: return false
        val sourceName = selectedStream()?.obsSourceName.orEmpty()
        mutableState.update { it.copy(fixing = true) }
        obs.restartInput(sourceName) { result ->
            handler.post {
                mutableState.update { it.copy(fixing = false) }
                result.onFailure { ChatController.showToast("Failed to fix OBS input: ${it.message}") }
            }
        }
        return true
    }

    /** Upstream `setObsAudioDelay`, clamped to OBS's range. */
    fun setAudioDelay(offsetMs: Int) {
        val sourceName = selectedStream()?.obsSourceName.orEmpty()
        if (sourceName.isEmpty()) {
            return
        }
        obs?.setInputAudioSyncOffset(sourceName, offsetMs.coerceIn(OBS_MINIMUM_AUDIO_DELAY, OBS_MAXIMUM_AUDIO_DELAY)) {
            handler.post { updateAudioDelay() }
        }
    }

    private fun updateAudioDelay() {
        val sourceName = selectedStream()?.obsSourceName.orEmpty()
        if (sourceName.isEmpty()) {
            return
        }
        obs?.getInputAudioSyncOffset(sourceName) { result ->
            result.onSuccess { offset -> handler.post { mutableState.update { it.copy(audioDelayMs = offset) } } }
        }
    }

    /** Upstream `setObsMediaSourceSettings`: what a scene's media source plays. */
    fun setMediaSourceInput(name: String, input: String) {
        obs?.setMediaSourceSettings(name, input) { handler.post { updateMediaInputs(mutableState.value.currentScene) } }
    }

    /** Upstream's OBS part of `startStream`: called when going live with [stream]. */
    fun onGoLive(stream: SettingsStream) {
        if (stream.obsAutoStartStream) {
            startStream()
        }
        if (stream.obsAutoStartRecording) {
            startRecording()
        }
    }

    /** Upstream's OBS part of `stopStream`: called when ending a stream with [stream]. */
    fun onStop(stream: SettingsStream) {
        if (stream.obsAutoStopStream) {
            stopStream()
        }
        if (stream.obsAutoStopRecording) {
            stopRecording()
        }
    }

    private const val TICK_MILLIS = 1000L
}
