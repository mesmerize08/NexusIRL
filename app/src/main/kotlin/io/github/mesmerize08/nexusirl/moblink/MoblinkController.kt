// Port of Moblin/Various/Model/ModelMoblink.swift (relay parts) and Moblin/Moblink/MoblinkScanner.swift
// @97e26d2.
package io.github.mesmerize08.nexusirl.moblink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.mesmerize08.nexusirl.core.websocket.ReconnectingWebSocket
import io.github.mesmerize08.nexusirl.net.AndroidBondingInterface
import io.github.mesmerize08.nexusirl.net.MultiNetworkManager
import io.github.mesmerize08.nexusirl.settings.SettingsMoblinkRelay
import io.github.mesmerize08.nexusirl.settings.SettingsStore
import io.github.mesmerize08.nexusirl.stream.StreamController
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID

/** A streamer found on the Wi-Fi (upstream `MoblinkScannerStreamer`). */
data class MoblinkStreamer(val name: String, val url: String)

/**
 * Runs the Moblink relay while it is enabled: finds Moblin streamers on the Wi-Fi (mDNS
 * `_moblink._tcp`) or uses a manual URL, and lends them this phone's mobile data (and Ethernet).
 * The control connection goes over Wi-Fi; the tunnels go over mobile data. Keeps mobile data up
 * while Wi-Fi is connected (MultiNetworkManager), and the foreground service keeps it running with
 * the screen off.
 *
 * Call from the main thread.
 */
object MoblinkController {
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var store: SettingsStore? = null
    private var scope: CoroutineScope? = null
    private var networks: MultiNetworkManager? = null
    private var interfaces: List<BondingInterface> = emptyList()
    private val relays = LinkedHashMap<String, MoblinkRelay>()
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var config: Config? = null

    private val mutableState = MutableStateFlow<MoblinkRelayState?>(null)

    /** The relay state, or null while the relay is off. */
    val state: StateFlow<MoblinkRelayState?> = mutableState

    private val mutableStreamers = MutableStateFlow<List<MoblinkStreamer>>(emptyList())

    /** Streamers found on the Wi-Fi. */
    val streamers: StateFlow<List<MoblinkStreamer>> = mutableStreamers

    private val relayStates = HashMap<String, MoblinkRelayState>()

    val isRunning: Boolean
        get() = config != null

    private data class Config(val name: String, val manual: Boolean, val url: String, val passwordRef: String)

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
                .map { database ->
                    val relay = database.moblink.relay
                    if (relay.enabled && database.moblink.password.isNotEmpty()) {
                        Config(relay.name.trim(), relay.manual, relay.validUrl, database.moblink.password)
                    } else {
                        null
                    }
                }
                .distinctUntilChanged()
                .collect(::reload)
        }
    }

    /** Upstream `reloadMoblinkRelay`. */
    private fun reload(newConfig: Config?) {
        stopAll()
        config = newConfig
        val context = context ?: return
        StreamController.updateForegroundService(context)
        if (newConfig == null) {
            mutableState.value = null
            return
        }
        mutableState.value = MoblinkRelayState.WAITING_FOR_STREAMERS
        networks = MultiNetworkManager(context).also { manager ->
            manager.start { list -> handler.post { networksChanged(list) } }
        }
        if (newConfig.manual) {
            if (newConfig.url.isNotEmpty()) {
                addRelay(newConfig.url)
            }
        } else {
            startDiscovery(context)
        }
    }

    private fun stopAll() {
        relays.values.forEach { it.stop() }
        relays.clear()
        relayStates.clear()
        stopDiscovery()
        networks?.stop()
        networks = null
        interfaces = emptyList()
        mutableStreamers.value = emptyList()
    }

    private fun networksChanged(list: List<BondingInterface>) {
        interfaces = list
        relays.values.forEach { it.setInterfaces(list) }
    }

    /** The control connection goes over Wi-Fi when there is one (upstream: never over cellular). */
    private fun controlClient(): OkHttpClient {
        val wifi = interfaces.firstOrNull { it.type == InterfaceType.WIFI } as? AndroidBondingInterface
        val base = ReconnectingWebSocket.LOCAL_NETWORK_CLIENT
        return wifi?.let { base.newBuilder().socketFactory(it.network.socketFactory).build() } ?: base
    }

    /** Upstream `addMoblinkRelay`: one relay per streamer, never to this device itself. */
    private fun addRelay(url: String) {
        val config = config ?: return
        val context = context ?: return
        if (url in relays || isThisDevice(url)) {
            return
        }
        val password = store?.moblinkPassword().orEmpty()
        val relay = MoblinkRelay(relayId(context), config.name, url, password, ::status, controlClient()) { state ->
            handler.post { relayStateChanged(url, state) }
        }
        relays[url] = relay
        relay.setInterfaces(interfaces)
    }

    private fun relayStateChanged(url: String, state: MoblinkRelayState) {
        if (url !in relays) {
            return
        }
        relayStates[url] = state
        // Worst first, like the relay list in upstream's status.
        val order = listOf(
            MoblinkRelayState.WRONG_PASSWORD,
            MoblinkRelayState.UNKNOWN_ERROR,
            MoblinkRelayState.NO_INTERFACE,
            MoblinkRelayState.CONNECTING,
            MoblinkRelayState.CONNECTED,
        )
        mutableState.value = order.firstOrNull { it in relayStates.values } ?: MoblinkRelayState.WAITING_FOR_STREAMERS
    }

    /** Upstream `isMoblinkRelayOnThisDevice`: the streamer's host is one of our own addresses. */
    private fun isThisDevice(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBeforeLast(':').trim('[', ']')
        return try {
            val ownAddresses = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            ownAddresses.any { it.hostAddress?.substringBefore('%') == host }
        } catch (e: Exception) {
            false
        }
    }

    /** Upstream `getMoblinkRelayId`: a per-device id, kept outside the settings. */
    private fun relayId(context: Context): UUID {
        val preferences = context.getSharedPreferences("moblink", Context.MODE_PRIVATE)
        val stored = preferences.getString("relayId", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return stored ?: UUID.randomUUID().also { preferences.edit().putString("relayId", it.toString().uppercase()).apply() }
    }

    /** Upstream `moblinkRelayGetStatus`: battery and thermal state. Any thread. */
    private fun status(): MoblinkResponse.Status {
        val context = context ?: return MoblinkResponse.Status()
        val battery = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val thermal = when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> MoblinkThermalState.WHITE
            PowerManager.THERMAL_STATUS_MODERATE -> MoblinkThermalState.YELLOW
            else -> MoblinkThermalState.RED
        }
        return MoblinkResponse.Status(battery?.takeIf { it in 0..100 }, thermal)
    }

    // Upstream MoblinkScanner: mDNS discovery of `_moblink._tcp` with a TXT record `name`.

    private fun startDiscovery(context: Context) {
        val manager = context.getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        // Some devices drop multicast (mDNS) without this lock.
        multicastLock = context.getSystemService(WifiManager::class.java)?.createMulticastLock("nexusirl-moblink")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = resolve(manager, service)

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        manager.discoverServices(MOBLINK_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        discovery?.let { listener ->
            try {
                nsd?.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                // Not running.
            }
        }
        discovery = null
        nsd = null
        multicastLock?.release()
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, service: NsdServiceInfo) {
        val onResolved = { resolved: NsdServiceInfo ->
            val name = resolved.attributes["name"]?.toString(Charsets.UTF_8)
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                resolved.hostAddresses
            } else {
                listOfNotNull(
                    resolved.host,
                )
            }
            val url = addresses.firstNotNullOfOrNull { websocketUrl(it, resolved.port) }
            if (name != null && url != null) {
                handler.post { streamerFound(MoblinkStreamer(name, url)) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.registerServiceInfoCallback(
                service,
                { it.run() },
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        onResolved(serviceInfo)
                        try {
                            manager.unregisterServiceInfoCallback(this)
                        } catch (e: IllegalArgumentException) {
                            // Already unregistered.
                        }
                    }

                    override fun onServiceLost() = Unit

                    override fun onServiceInfoCallbackUnregistered() = Unit
                },
            )
        } else {
            manager.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = onResolved(serviceInfo)
                },
            )
        }
    }

    /** Upstream `formatWebsocketUrl`: IPv4, or global IPv6 in brackets; no link-local or loopback. */
    private fun websocketUrl(address: InetAddress, port: Int): String? = when {
        address.isLoopbackAddress || address.isLinkLocalAddress -> null
        address is Inet4Address -> "ws://${address.hostAddress}:$port"
        address is Inet6Address -> "ws://[${address.hostAddress?.substringBefore('%')}]:$port"
        else -> null
    }

    /** Upstream `moblinkScannerDiscoveredStreamers` and `startMoblinkRelayAutomatic`. */
    private fun streamerFound(streamer: MoblinkStreamer) {
        if (mutableStreamers.value.none { it.url == streamer.url }) {
            mutableStreamers.value = mutableStreamers.value + streamer
        }
        if (config?.manual == false) {
            addRelay(streamer.url)
        }
    }

    /** Whether the settings ask for a relay (for the settings screen). */
    fun isConfigured(relay: SettingsMoblinkRelay, password: String) = relay.enabled && password.isNotEmpty()
}
