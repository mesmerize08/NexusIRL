// Port of the assistant parts of Moblin/Various/Model/ModelRemoteControl.swift @97e26d2 (v1 subset).
package io.github.mesmerize08.nexusirl.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import io.github.mesmerize08.nexusirl.core.websocket.CleartextPolicy
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAssistant
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAssistantDelegate
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlAssistantStreamerState
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlRequest
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlResponse
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlSettings
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStartStatusFilter
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStatusGeneral
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStatusTopLeft
import io.github.mesmerize08.nexusirl.remotecontrol.RemoteControlStatusTopRight
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.stream.StreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** What the assistant knows about the connected streamer. */
data class RemoteStreamerView(
    val connected: Boolean = false,
    val settings: RemoteControlSettings? = null,
    val state: RemoteControlAssistantStreamerState = RemoteControlAssistantStreamerState(),
    val general: RemoteControlStatusGeneral? = null,
    val topLeft: RemoteControlStatusTopLeft? = null,
    val topRight: RemoteControlStatusTopRight? = null,
    val preview: Bitmap? = null,
)

/**
 * Runs this device as a remote control assistant while enabled: listens on the configured port
 * (LAN, hotspot and Tailscale only, never mobile data) for one streamer, shows its status and
 * preview, and sends it commands. Call from the main thread.
 */
object RemoteControlAssistantController {
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var store: SettingsStore? = null
    private var scope: CoroutineScope? = null
    private var assistant: RemoteControlAssistant? = null

    private val mutableView = MutableStateFlow<RemoteStreamerView?>(null)

    /** The streamer as seen from here; null while the assistant is off. */
    val view: StateFlow<RemoteStreamerView?> = mutableView

    private data class Config(val enabled: Boolean, val port: Int, val passwordRef: String)

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
                .map { Config(it.remoteControl.assistant.enabled, it.remoteControl.assistant.port, it.remoteControl.password) }
                .distinctUntilChanged()
                .collect(::reload)
        }
    }

    private fun reload(config: Config) {
        assistant?.stop()
        assistant = null
        val password = store?.remoteControlPassword().orEmpty()
        if (!config.enabled || password.isEmpty() || config.port !in 1024..65535) {
            mutableView.value = null
            return
        }
        mutableView.value = RemoteStreamerView()
        assistant = RemoteControlAssistant(config.port, password, Delegate(), acceptLocalAddress = ::isAllowedLocalAddress)
            .also { it.start() }
    }

    private fun update(transform: (RemoteStreamerView) -> RemoteStreamerView) {
        handler.post { mutableView.value?.let { mutableView.value = transform(it) } }
    }

    /**
     * docs/PORT_PLAN.md §9.6: never on mobile data. The address must be loopback, LAN, hotspot or
     * Tailscale, and not on a cellular interface (carrier-grade NAT shares Tailscale's range).
     */
    fun isAllowedLocalAddress(address: InetAddress): Boolean {
        val host = address.hostAddress.orEmpty()
        if (!CleartextPolicy.isLoopback(host) && !CleartextPolicy.isLocalNetwork(host)) {
            return false
        }
        val name = try {
            NetworkInterface.getByInetAddress(address)?.name
        } catch (e: Exception) {
            null
        } ?: return true
        return name !in cellularInterfaces()
    }

    private fun cellularInterfaces(): Set<String> {
        val connectivity = context?.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.filter {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }.mapNotNull { connectivity.getLinkProperties(it)?.interfaceName }.toSet()
    }

    /** This device's LAN addresses, to show which URL the streamer should use. */
    fun localUrls(port: Int): List<String> = localAddresses().map { "ws://$it:$port" }

    /** This device's LAN, hotspot and Tailscale IPv4 addresses (not mobile data). */
    fun localAddresses(): List<String> {
        val cellular = cellularInterfaces()
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.name !in cellular }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter { CleartextPolicy.isLocalNetwork(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Sends [request] to the streamer (upstream assistant buttons). */
    fun send(request: RemoteControlRequest) {
        assistant?.request(request)
    }

    private class Delegate : RemoteControlAssistantDelegate {
        override fun connected() {
            update { it.copy(connected = true) }
            val current = assistant ?: return
            current.request(RemoteControlRequest.GetSettings) { _, data ->
                (data as? RemoteControlResponse.GetSettings)?.let { settings -> update { it.copy(settings = settings.data) } }
            }
            current.request(RemoteControlRequest.StartStatus(1, RemoteControlStartStatusFilter()))
            current.request(RemoteControlRequest.StartPreview)
        }

        override fun disconnected() = update { RemoteStreamerView() }

        override fun stateChanged(state: RemoteControlAssistantStreamerState) = update { view ->
            // The streamer sends only what changed: merge (upstream remoteControlAssistantStateChanged).
            val old = view.state
            view.copy(
                state = old.copy(
                    scene = state.scene ?: old.scene,
                    zoom = state.zoom ?: old.zoom,
                    streaming = state.streaming ?: old.streaming,
                    recording = state.recording ?: old.recording,
                    muted = state.muted ?: old.muted,
                    stealthMode = state.stealthMode ?: old.stealthMode,
                    torchOn = state.torchOn ?: old.torchOn,
                    batteryCharging = state.batteryCharging ?: old.batteryCharging,
                    filters = state.filters ?: old.filters,
                ),
            )
        }

        override fun status(
            general: RemoteControlStatusGeneral?,
            topLeft: RemoteControlStatusTopLeft?,
            topRight: RemoteControlStatusTopRight?,
        ) = update { it.copy(general = general ?: it.general, topLeft = topLeft ?: it.topLeft, topRight = topRight ?: it.topRight) }

        override fun preview(jpeg: ByteArray) {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            update { it.copy(preview = bitmap) }
        }
    }
}
