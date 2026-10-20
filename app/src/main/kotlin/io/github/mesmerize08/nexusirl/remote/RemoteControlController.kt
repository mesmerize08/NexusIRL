// Port of the streamer parts of Moblin/Various/Model/ModelRemoteControl.swift @97e26d2 (v1 subset).
package io.github.mesmerize08.nexusirl.remote

import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAssistantStreamerState
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAudioInfo
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAudioLevel
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlFilter
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlResponse
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettings
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
import io.github.mesmerize08.nexusirl.settings.QuickButtonType
import io.github.mesmerize08.nexusirl.settings.SettingsRemoteControl
import io.github.mesmerize08.nexusirl.settings.SettingsStore
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
import java.util.UUID

/** How the streamer's connection to the assistant is doing, for the settings screen. */
enum class RemoteControlConnection { OFF, CONNECTING, CONNECTED, WRONG_PASSWORD }

/**
 * Runs the streamer side of remote control while it is enabled: an iOS Moblin assistant (or any
 * compatible one) can see the status and preview and control scenes, going live, recording,
 * zoom, torch, mute, stealth mode and effects. Settings imports from the assistant are refused.
 *
 * Call from the main thread.
 */
object RemoteControlController {
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var store: SettingsStore? = null
    private var scope: CoroutineScope? = null
    private var streamer: RemoteControlStreamer? = null
    private var statusIntervalMillis = 0L
    private var previewing = false
    private var previewIntervalMillis = 1000L

    /** Increases per connection; callbacks from a replaced connection are ignored. */
    private var generation = 0

    private val mutableConnection = MutableStateFlow(RemoteControlConnection.OFF)
    val connection: StateFlow<RemoteControlConnection> = mutableConnection

    /** What a (re)connect depends on. The password is only read from the secret store here. */
    private data class Config(val enabled: Boolean, val url: String, val passwordRef: String)

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
        // Tell the assistant about changes (upstream `remoteControlStreamerSendStateChanged`).
        newScope.launch {
            combine(
                StreamController.status,
                StreamController.recording,
                StreamController.camera,
                StreamController.selectedSceneId,
                settings.database,
            ) { _, _, _, _, _ ->
                state()
            }.distinctUntilChanged().collect { streamer?.stateChanged(it) }
        }
    }

    private fun reload(config: Config) {
        streamer?.stop()
        streamer = null
        stopStatus()
        previewing = false
        val password = store?.remoteControlPassword().orEmpty()
        if (!config.enabled || config.url.isEmpty() || password.isEmpty()) {
            mutableConnection.value = RemoteControlConnection.OFF
            return
        }
        mutableConnection.value = RemoteControlConnection.CONNECTING
        generation += 1
        streamer = RemoteControlStreamer(config.url, password, streamerId(), Delegate(generation)).also { it.start() }
    }

    /** Upstream `getRemoteControlStreamerId`: a per-device id, not part of the settings. */
    private fun streamerId(): String {
        val preferences = context!!.getSharedPreferences("remote_control", Context.MODE_PRIVATE)
        return preferences.getString("streamerId", null) ?: UUID.randomUUID().toString().uppercase().also {
            preferences.edit().putString("streamerId", it).apply()
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
            bitrate = if (status.active) {
                RemoteControlStatusItem(
                    "%.1f Mbps".format(status.videoBitrateKbps / 1000.0),
                    status.live,
                )
            } else {
                null
            },
            uptime = if (status.active) RemoteControlStatusItem(formatUptime(status.uptimeSeconds), status.live) else null,
            recording = StreamController.recording.value?.let { RemoteControlStatusItem("Recording") },
        )
        return RemoteControlResponse.GetStatus(general, topLeft, topRight)
    }

    private fun formatUptime(seconds: Long) = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

    private fun settings(): RemoteControlSettings {
        val database = store?.current
        return RemoteControlSettings(
            streams = database?.streams.orEmpty().map { RemoteControlSettingsStream(it.id, it.name) },
            scenes = database?.scenes.orEmpty().filter { it.enabled }.map { RemoteControlSettingsScene(it.id, it.name) },
        )
    }

    private fun stopStatus() {
        statusIntervalMillis = 0
        handler.removeCallbacks(statusTick)
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (statusIntervalMillis <= 0) {
                return
            }
            val status = status()
            streamer?.sendStatus(status.general, status.topLeft, status.topRight)
            handler.postDelayed(this, statusIntervalMillis)
        }
    }

    private val previewTick = object : Runnable {
        override fun run() {
            if (!previewing) {
                return
            }
            StreamController.currentFrame { frame ->
                frame?.let { streamer?.sendPreview(jpeg(it)) }
            }
            handler.postDelayed(this, previewIntervalMillis)
        }
    }

    /** Upstream `VideoLowFpsImage`: 400 pixels on the long side, JPEG quality 0.3. */
    private fun jpeg(frame: Bitmap): ByteArray {
        val scale = 400f / maxOf(frame.width, frame.height)
        val small = Bitmap.createScaledBitmap(
            frame,
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

    private class Delegate(private val owner: Int) : RemoteControlStreamerDelegate {
        /** Runs [block] on the main thread with the app context, if this connection is still current. */
        private fun main(block: (Context) -> Unit) {
            handler.post {
                if (owner == generation) {
                    context?.let(block)
                }
            }
        }

        override fun connected() = main {
            mutableConnection.value = RemoteControlConnection.CONNECTED
            streamer?.stateChanged(state())
        }

        override fun disconnected() = main {
            mutableConnection.value = RemoteControlConnection.CONNECTING
            RemoteControlController.stopStatus()
            previewing = false
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
            // The controller's, not this delegate's (which would queue another stop).
            RemoteControlController.stopStatus()
            statusIntervalMillis = interval.coerceAtLeast(1) * 1000L
            handler.post(statusTick)
        }

        override fun stopStatus() = main { RemoteControlController.stopStatus() }

        override fun startPreview() = main {
            val fps = store?.current?.remoteControl?.streamer?.previewFps ?: 1f
            if (fps > 0 && !previewing) {
                previewing = true
                // Upstream: an interval of 1/fps, between 0.2 and 1 second.
                previewIntervalMillis = (1000 / fps).toLong().coerceIn(200, 1000)
                handler.post(previewTick)
            }
        }

        override fun stopPreview() = main { previewing = false }
    }

    /** Whether remote control is set up enough to try (upstream `isRemoteControlStreamerConfigured`). */
    fun isConfigured(remoteControl: SettingsRemoteControl) = remoteControl.streamer.enabled && remoteControl.streamer.url.isNotBlank()
}
