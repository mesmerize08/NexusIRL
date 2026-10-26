// Port of the streamer parts of Moblin/Various/Model/ModelRemoteControl.swift @97e26d2 (v1 subset).
package io.github.mesmerize08.nexusirl.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.core.graphics.scale
import io.github.mesmerize08.nexusirl.BuildConfig
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAssistantStreamerState
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAudioInfo
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAudioLevel
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlFilter
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlResponse
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettings
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettingsBitratePreset
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettingsMic
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettingsScene
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettingsStream
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStartStatusFilter
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStatusGeneral
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStatusGeneralFlame
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStatusItem
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStatusTopLeft
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStatusTopRight
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStreamer
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStreamerDelegate
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlWeb
import io.github.mesmerize08.nexusirl.settings.QuickButtonType
import io.github.mesmerize08.nexusirl.settings.SettingsArchive
import io.github.mesmerize08.nexusirl.settings.SettingsRemoteControl
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.stream.Microphones
import io.github.mesmerize08.nexusirl.stream.StreamController
import io.github.mesmerize08.nexusirl.stream.ThermalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How the streamer's connection to the assistant is doing, for the settings screen. */
enum class RemoteControlConnection { OFF, CONNECTING, CONNECTED, WRONG_PASSWORD }

/**
 * Runs the streamer side of remote control while it is enabled: an iOS Moblin assistant (or any
 * compatible one) can see the status and preview and control scenes, going live, recording,
 * zoom, torch, mute, stealth mode and effects. Settings imports from the assistant are refused.
 *
 * Call from the main thread.
 */
@SuppressLint("StaticFieldLeak") // Holds only the application context.
object RemoteControlController {
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var store: SettingsStore? = null
    private var scope: CoroutineScope? = null
    private var streamer: RemoteControlStreamer? = null
    private var web: RemoteControlWeb? = null

    // What each output asked for: the assistant connection and the web pages.
    private var streamerStatusMillis = 0L
    private var webStatus = false
    private var streamerPreview = false
    private var webPreview = false
    private var previewIntervalMillis = 1000L
    private var ticking = false
    private var previewTicking = false

    /** Increases per connection; callbacks from a replaced connection are ignored. */
    private var generation = 0

    private val mutableConnection = MutableStateFlow(RemoteControlConnection.OFF)
    val connection: StateFlow<RemoteControlConnection> = mutableConnection

    /** What a (re)connect depends on. The password is only read from the secret store here. */
    private data class Config(val enabled: Boolean, val urlRef: String, val passwordRef: String)

    private data class WebConfig(val enabled: Boolean, val port: Int, val tokenRef: String)

    private val mutableWebRunning = MutableStateFlow(false)

    /** Whether the web remote control is listening. */
    val webRunning: StateFlow<Boolean> = mutableWebRunning

    fun start(context: Context) {
        if (scope != null) {
            return
        }
        val appContext = context.applicationContext
        this.context = appContext
        val settings = StreamController.settings(appContext)
        store = settings
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch {
            settings.database
                .map { Config(it.remoteControl.streamer.enabled, it.remoteControl.streamer.url.trim(), it.remoteControl.password) }
                .distinctUntilChanged()
                .collect(::reload)
        }
        newScope.launch {
            settings.database
                .map { WebConfig(it.remoteControl.web.enabled, it.remoteControl.web.androidPort, it.remoteControl.web.token) }
                .distinctUntilChanged()
                .collect(::reloadWeb)
        }
        // Tell the assistant about changes (upstream `remoteControlStreamerSendStateChanged`).
        newScope.launch {
            combine(
                StreamController.status,
                StreamController.recording,
                StreamController.camera,
                StreamController.selectedSceneId,
                settings.database,
            ) { _, _, _, _, _ -> }
                .combine(Microphones.selected) { _, _ -> state() }
                .distinctUntilChanged().collect {
                    streamer?.stateChanged(it)
                    web?.stateChanged(it)
                }
        }
    }

    private fun reload(config: Config) {
        streamer?.stop()
        streamer = null
        streamerStatusMillis = 0
        streamerPreview = false
        val password = store?.remoteControlPassword().orEmpty()
        val url = store?.remoteControlUrl().orEmpty().trim()
        if (!config.enabled || url.isEmpty() || password.isEmpty()) {
            mutableConnection.value = RemoteControlConnection.OFF
            return
        }
        mutableConnection.value = RemoteControlConnection.CONNECTING
        generation += 1
        val owner = generation
        streamer = RemoteControlStreamer(url, password, streamerId(), Delegate { owner == generation }).also { it.start() }
    }

    /** Increases per web server; callbacks from a stopped one are ignored. */
    private var webGeneration = 0

    /** The browser remote control (docs/PORT_PLAN.md §9.6: token, local interfaces only). */
    private fun reloadWeb(config: WebConfig) {
        web?.stop()
        web = null
        webStatus = false
        webPreview = false
        webGeneration += 1
        mutableWebRunning.value = false
        val context = context ?: return
        val token = store?.webControlToken().orEmpty()
        if (!config.enabled || token.length < 32) {
            return
        }
        val owner = webGeneration
        val server = RemoteControlWeb(
            port = config.port,
            token = token,
            delegate = Delegate(web = true) { owner == webGeneration },
            asset = { path -> runCatching { context.assets.open("web/$path").use { it.readBytes() } }.getOrNull() },
            acceptLocalAddress = RemoteControlAssistantController::isAllowedLocalAddress,
            snapshot = ::snapshotJpeg,
            onPreviewWanted = { wanted ->
                handler.post {
                    if (owner == webGeneration) {
                        webPreview = wanted
                        updateLoops()
                    }
                }
            },
        )
        try {
            server.start()
            web = server
            mutableWebRunning.value = true
        } catch (e: Exception) {
            // The port is in use: shown as not running.
            server.stop()
        }
    }

    /** The current frame as a full-size JPEG for the web `/snapshot` route. Blocking. */
    private fun snapshotJpeg(): ByteArray? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: ByteArray? = null
        handler.post {
            StreamController.currentFrame { frame ->
                result = frame?.let {
                    val output = ByteArrayOutputStream()
                    it.compress(Bitmap.CompressFormat.JPEG, 90, output)
                    it.recycle()
                    output.toByteArray()
                }
                latch.countDown()
            }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    /** Starts or stops the status and preview loops to match what the outputs asked for. */
    private fun updateLoops() {
        if ((streamerStatusMillis > 0 || webStatus) && !ticking) {
            ticking = true
            handler.post(statusTick)
        }
        if ((streamerPreview || webPreview) && !previewTicking) {
            previewTicking = true
            handler.post(previewTick)
        }
    }

    /** Upstream `getRemoteControlStreamerId`: a per-device id, not part of the settings. */
    private fun streamerId(): String {
        val preferences = context!!.getSharedPreferences("remote_control", Context.MODE_PRIVATE)
        return preferences.getString("streamerId", null) ?: UUID.randomUUID().toString().uppercase().also {
            preferences.edit { putString("streamerId", it) }
        }
    }

    private fun isOn(type: QuickButtonType) = context?.let { StreamController.isQuickButtonOn(it, type) } == true

    private val filterButtons = mapOf(
        RemoteControlFilter.GRAY_SCALE to QuickButtonType.GRAY_SCALE,
        RemoteControlFilter.SEPIA to QuickButtonType.SEPIA,
        RemoteControlFilter.PIXELLATE to QuickButtonType.PIXELLATE,
        RemoteControlFilter.FOUR_THREE to QuickButtonType.FOUR_THREE,
        RemoteControlFilter.CRT to QuickButtonType.CRT,
        RemoteControlFilter.PINCH to QuickButtonType.PINCH,
        RemoteControlFilter.WHIRLPOOL to QuickButtonType.WHIRLPOOL,
    )

    /** Upstream `createRemoteControlStateChanged`. */
    private fun state(): RemoteControlAssistantStreamerState {
        val camera = StreamController.camera.value
        return RemoteControlAssistantStreamerState(
            scene = StreamController.selectedSceneId.value,
            mic = Microphones.selected.value?.id,
            // Upstream: the preset matching the stream's bitrate, if any.
            bitrate = store?.current?.let { database ->
                database.bitratePresets.firstOrNull { it.bitrate == database.selectedStream?.bitrate }?.id
            },
            zoom = camera.zoom,
            streaming = StreamController.status.value.active,
            recording = StreamController.recording.value != null,
            muted = isOn(QuickButtonType.MUTE),
            stealthMode = isOn(QuickButtonType.BLACK_SCREEN),
            torchOn = camera.torch,
            batteryCharging = battery()?.isCharging,
            filters = filterButtons.mapValues { (_, button) -> isOn(button) },
        )
    }

    private fun battery(): BatteryManager? = context?.getSystemService(BatteryManager::class.java)

    /** Upstream `remoteControlStreamerGetStatus`. */
    private fun status(): RemoteControlResponse.GetStatus {
        val status = StreamController.status.value
        val database = store?.current
        val stream = database?.selectedStream
        val camera = StreamController.camera.value
        val muted = isOn(QuickButtonType.MUTE)
        val general = RemoteControlStatusGeneral(
            batteryCharging = battery()?.isCharging,
            batteryLevel = battery()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            flame = when (status.thermal) {
                ThermalState.NORMAL, ThermalState.FAIR -> RemoteControlStatusGeneralFlame.WHITE
                ThermalState.SERIOUS -> RemoteControlStatusGeneralFlame.YELLOW
                ThermalState.CRITICAL -> RemoteControlStatusGeneralFlame.RED
            },
            isLive = status.active,
            isRecording = StreamController.recording.value != null,
            isMuted = muted,
        )
        val topLeft = RemoteControlStatusTopLeft(
            // The stream name only: never the URL or key (docs/PORT_PLAN.md §9.4).
            stream = stream?.let { RemoteControlStatusItem(it.name) },
            camera = camera.current?.let { RemoteControlStatusItem(it.label) },
            mic = status.microphone.takeIf { it.isNotEmpty() }?.let { RemoteControlStatusItem(it) },
            zoom = RemoteControlStatusItem("%.1f".format(camera.zoom)),
        )
        val audioLevel = when {
            muted -> RemoteControlAudioLevel.Muted
            status.audioLevelDbfs <= -160f -> RemoteControlAudioLevel.Unknown
            else -> RemoteControlAudioLevel.Value(status.audioLevelDbfs)
        }
        val topRight = RemoteControlStatusTopRight(
            audioInfo = RemoteControlAudioInfo(audioLevel, 1),
            // Upstream's formats ("5.0 Mbps (12.3 MB)", "1m 23s"): assistants and Moblin's test
            // harness read them.
            bitrate = if (status.active) {
                RemoteControlStatusItem(
                    "${StatusFormat.bitsPerSecond(status.sentBitsPerSecond)} (${StatusFormat.bytes(status.sentBytes)})",
                    status.live,
                )
            } else {
                null
            },
            uptime = if (status.active) RemoteControlStatusItem(StatusFormat.uptime(status.uptimeSeconds), status.live) else null,
            recording = StreamController.recording.value?.let { RemoteControlStatusItem("Recording") },
        )
        return RemoteControlResponse.GetStatus(general, topLeft, topRight)
    }

    private fun settings(): RemoteControlSettings {
        val database = store?.current
        return RemoteControlSettings(
            streams = database?.streams.orEmpty().map { RemoteControlSettingsStream(it.id, it.name) },
            scenes = database?.scenes.orEmpty().filter { it.enabled }.map { RemoteControlSettingsScene(it.id, it.name) },
            bitratePresets = database?.bitratePresets.orEmpty().map { RemoteControlSettingsBitratePreset(it.id, it.bitrate) },
            mics = Microphones.mics.value.map { RemoteControlSettingsMic(it.id, it.name) },
        )
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (streamerStatusMillis <= 0 && !webStatus) {
                ticking = false
                return
            }
            val status = status()
            if (streamerStatusMillis > 0) {
                streamer?.sendStatus(status.general, status.topLeft, status.topRight)
            }
            if (webStatus) {
                web?.sendStatus(status.general, status.topLeft, status.topRight)
            }
            handler.postDelayed(this, if (streamerStatusMillis > 0) streamerStatusMillis else 1000)
        }
    }

    private val previewTick = object : Runnable {
        override fun run() {
            if (!streamerPreview && !webPreview) {
                previewTicking = false
                return
            }
            val toStreamer = streamerPreview
            val toWeb = webPreview
            StreamController.currentFrame(PREVIEW_LONG_SIDE) { frame ->
                frame?.let { jpeg(it) }?.let {
                    if (toStreamer) streamer?.sendPreview(it)
                    if (toWeb) web?.sendPreview(it)
                }
            }
            handler.postDelayed(this, previewIntervalMillis)
        }
    }

    private const val PREVIEW_LONG_SIDE = 400

    private const val REQUEST_TIMEOUT_SECONDS = 3L

    /** See [Delegate.importSettings]. */
    const val TEST_IMPORT_MARKER = "accept-remote-settings-import-for-tests"

    /** Upstream `VideoLowFpsImage`: 400 pixels on the long side, JPEG quality 0.3. */
    private fun jpeg(frame: Bitmap): ByteArray {
        val scale = PREVIEW_LONG_SIDE.toFloat() / maxOf(frame.width, frame.height)
        val small = frame.scale(
            (frame.width * scale).toInt().coerceAtLeast(1),
            (frame.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        val output = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 30, output)
        if (small !== frame) {
            small.recycle()
        }
        frame.recycle()
        return output.toByteArray()
    }

    /** Requests from the assistant connection or (with [web]) from web pages. */
    private class Delegate(private val web: Boolean = false, private val isCurrent: () -> Boolean) : RemoteControlStreamerDelegate {
        /**
         * Runs [block] on the main thread with the app context, if this connection is still
         * current, and waits for it (briefly): like upstream, a request has taken effect when it is
         * answered, so a status read right after "go live" sees the stream starting.
         */
        private fun main(block: (Context) -> Unit) {
            val done = CountDownLatch(1)
            handler.post {
                try {
                    if (isCurrent()) {
                        context?.let(block)
                    }
                } finally {
                    done.countDown()
                }
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                done.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }

        override fun connected() = main {
            if (web) {
                this@RemoteControlController.web?.stateChanged(state())
                return@main
            }
            mutableConnection.value = RemoteControlConnection.CONNECTED
            streamer?.stateChanged(state())
        }

        /**
         * Refused, as the class says, except for automated tests: a debug build with the marker
         * file [TEST_IMPORT_MARKER] (created over `adb run-as`, which only works on debug builds)
         * accepts imports so Moblin's system-test harness can configure the phone. Never live.
         */
        override fun importSettings(data: ByteArray): Boolean {
            val context = context ?: return false
            if (web || !BuildConfig.DEBUG || !File(context.filesDir, TEST_IMPORT_MARKER).exists()) {
                return false
            }
            val json = try {
                SettingsArchive.readSettingsJson(data)
            } catch (e: Exception) {
                return false
            }
            val done = CountDownLatch(1)
            var imported = false
            handler.post {
                if (isCurrent() && !StreamController.status.value.active) {
                    imported = try {
                        StreamController.settings(context).import(json)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                done.countDown()
            }
            done.await(10, TimeUnit.SECONDS)
            return imported
        }

        override fun disconnected() = main {
            mutableConnection.value = RemoteControlConnection.CONNECTING
            streamerStatusMillis = 0
            streamerPreview = false
        }

        override fun wrongPassword() = main { mutableConnection.value = RemoteControlConnection.WRONG_PASSWORD }

        override fun getStatus(): RemoteControlResponse.GetStatus = status()

        override fun getSettings(): RemoteControlSettings = settings()

        override fun setStream(id: UUID) = main { context ->
            // Like upstream, not while live.
            if (!StreamController.status.value.active) {
                StreamController.settings(context).update { it.selectStream(id) }
            }
        }

        override fun setScene(id: UUID) = main { StreamController.selectScene(it, id) }

        override fun setMic(id: String) = main { Microphones.select(id) }

        override fun setBitratePreset(id: UUID) = main { context ->
            StreamController.settings(context).current.bitratePresets.firstOrNull { it.id == id }?.let {
                StreamController.setBitrate(context, it.bitrate)
            }
        }

        override fun setRecord(on: Boolean) = main { context ->
            if (on) StreamController.startRecording(context) else StreamController.stopRecording(context)
        }

        override fun setLive(on: Boolean) = main { context ->
            if (on) StreamController.goLive(context) else StreamController.stop(context)
        }

        override fun setZoom(x: Float) = main { StreamController.setZoom(x) }

        override fun setMute(on: Boolean) = main { StreamController.setQuickButton(it, QuickButtonType.MUTE, on) }

        override fun setStealthMode(on: Boolean) = main { StreamController.setQuickButton(it, QuickButtonType.BLACK_SCREEN, on) }

        override fun setTorch(on: Boolean) = main { StreamController.setQuickButton(it, QuickButtonType.TORCH, on) }

        override fun setFilter(filter: RemoteControlFilter, on: Boolean) = main { context ->
            filterButtons[filter]?.let { StreamController.setQuickButton(context, it, on) }
        }

        override fun startStatus(interval: Int, filter: RemoteControlStartStatusFilter) = main {
            if (web) {
                webStatus = true
            } else {
                streamerStatusMillis = interval.coerceAtLeast(1) * 1000L
            }
            updateLoops()
        }

        override fun stopStatus() = main {
            if (web) webStatus = false else streamerStatusMillis = 0
        }

        // Web pages ask for previews per page (RemoteControlWeb.onPreviewWanted).
        override fun startPreview() = main {
            val fps = store?.current?.remoteControl?.streamer?.previewFps ?: 1f
            if (!web && fps > 0) {
                streamerPreview = true
                // Upstream: an interval of 1/fps, between 0.2 and 1 second.
                previewIntervalMillis = (1000 / fps).toLong().coerceIn(200, 1000)
                updateLoops()
            }
        }

        override fun stopPreview() = main {
            if (!web) streamerPreview = false
        }
    }

    /** Whether remote control is set up enough to try (upstream `isRemoteControlStreamerConfigured`). */
    fun isConfigured(remoteControl: SettingsRemoteControl) = remoteControl.streamer.enabled && remoteControl.streamer.url.isNotBlank()
}
