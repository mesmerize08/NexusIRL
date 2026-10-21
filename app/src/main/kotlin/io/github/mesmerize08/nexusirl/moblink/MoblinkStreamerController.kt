// Port of the streamer parts of Moblin/Various/Model/ModelMoblink.swift @97e26d2.
package io.github.mesmerize08.nexusirl.moblink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.mesmerize08.nexusirl.remote.RemoteControlAssistantController
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
import java.util.UUID

/**
 * Runs this device as a Moblink streamer while enabled: other phones on the Wi-Fi running Moblin's
 * or NexusIRL's relay find it (mDNS `_moblink._tcp`) and lend their mobile data as extra SRTLA
 * paths while live. Only LAN, hotspot and Tailscale connections are accepted, never mobile data.
 *
 * Call from the main thread, except [startTunnels] and [stopTunnels] (any thread).
 */
object MoblinkStreamerController {
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var store: SettingsStore? = null
    private var scope: CoroutineScope? = null

    @Volatile
    private var streamer: MoblinkStreamer? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    private val mutableRelays = MutableStateFlow<List<MoblinkRelayStatus>?>(null)

    /** Connected relays, or null while the streamer is off. */
    val relays: StateFlow<List<MoblinkRelayStatus>?> = mutableRelays

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
                .map { Config(it.moblink.streamer.enabled, it.moblink.streamer.port, it.moblink.password) }
                .distinctUntilChanged()
                .collect(::reload)
        }
    }

    private fun reload(config: Config) {
        stopAll()
        val context = context ?: return
        val password = store?.moblinkPassword().orEmpty()
        if (!config.enabled || password.isEmpty() || config.port !in 1024..65535) {
            mutableRelays.value = null
            return
        }
        val newStreamer = MoblinkStreamer(
            config.port,
            password,
            object : MoblinkStreamerListener {
                override fun tunnelAdded(tunnel: MoblinkTunnel) {
                    StreamController.session?.addMoblink(tunnel)
                }

                override fun tunnelRemoved(tunnel: MoblinkTunnel) {
                    StreamController.session?.removeMoblink(tunnel)
                }

                override fun relaysChanged() {
                    handler.post { mutableRelays.value = streamer?.statuses() }
                }
            },
            acceptLocalAddress = RemoteControlAssistantController::isAllowedLocalAddress,
        )
        try {
            newStreamer.start()
        } catch (e: Exception) {
            mutableRelays.value = null
            return
        }
        streamer = newStreamer
        mutableRelays.value = emptyList()
        advertise(context, config.port)
        handler.postDelayed(statusTick, STATUS_MILLIS)
        // Already live with SRTLA: the session reports the destination again on its next connect.
    }

    private fun stopAll() {
        handler.removeCallbacks(statusTick)
        registration?.let { listener ->
            try {
                nsd?.unregisterService(listener)
            } catch (e: IllegalArgumentException) {
                // Not registered.
            }
        }
        registration = null
        streamer?.stop()
        streamer = null
    }

    /** Upstream: the relays' battery and thermal state, refreshed every 10 seconds. */
    private val statusTick = object : Runnable {
        override fun run() {
            val current = streamer ?: return
            Thread({ current.updateStatus() }, "moblink-status").apply { isDaemon = true }.start()
            handler.postDelayed(this, STATUS_MILLIS)
        }
    }

    /** Upstream `NWListener.Service`: name is a per-device id, TXT `name` is shown on relays. */
    private fun advertise(context: Context, port: Int) {
        val manager = context.getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = serviceId(context)
            serviceType = MOBLINK_SERVICE_TYPE
            this.port = port
            setAttribute("name", deviceName(context))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Upstream `moblinkServerId`: kept outside the settings. */
    private fun serviceId(context: Context): String {
        val preferences = context.getSharedPreferences("moblink", Context.MODE_PRIVATE)
        return preferences.getString("serverId", null) ?: UUID.randomUUID().toString().uppercase().also {
            preferences.edit().putString("serverId", it).apply()
        }
    }

    private fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Upstream `startTunnels`: an SRTLA stream knows its server. Any thread. */
    fun startTunnels(host: String, port: Int) {
        streamer?.let { current -> Thread({ current.startTunnels(host, port) }, "moblink-tunnels").apply { isDaemon = true }.start() }
    }

    /** Upstream `stopTunnels`: the stream ended. Any thread. */
    fun stopTunnels() {
        streamer?.stopTunnels()
    }

    private const val STATUS_MILLIS = 10_000L
}
