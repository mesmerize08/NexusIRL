package io.github.mesmerize08.nexusirl.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.mesmerize08.nexusirl.transport.srt.net.BondingInterface
import io.github.mesmerize08.nexusirl.transport.srt.net.InterfaceType
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * A bonding path on one Android [Network]: sockets are bound with [Network.bindSocket] and names
 * resolved on that network, so cellular traffic really leaves over cellular even while Wi-Fi is
 * the default (upstream: `NWParameters.requiredInterface`).
 */
class AndroidBondingInterface(val network: Network, override val type: InterfaceType, override val name: String) : BondingInterface {
    override val id: String = network.networkHandle.toString()

    override fun bind(socket: DatagramSocket) {
        network.bindSocket(socket)
    }

    override fun resolve(host: String): Array<InetAddress> = network.getAllByName(host)

    override fun toString(): String = "$type($name)"
}

/**
 * Keeps cellular, Wi-Fi and Ethernet networks available at the same time and reports them as
 * SRTLA bonding paths (replaces upstream's `NWPathMonitor`).
 *
 * Requesting each transport (rather than only listening) is what keeps mobile data up while
 * Wi-Fi is connected; without it Android tears cellular down. Callbacks are kept registered for
 * the whole stream (docs/PORT_PLAN.md §3).
 */
class MultiNetworkManager(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callbacks = ArrayList<ConnectivityManager.NetworkCallback>()
    private val interfaces = LinkedHashMap<Network, AndroidBondingInterface>()
    private var listener: ((List<BondingInterface>) -> Unit)? = null

    fun start(onChange: (List<BondingInterface>) -> Unit) {
        listener = onChange
        for ((transport, type) in TRANSPORTS) {
            val request = NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val name = connectivity.getLinkProperties(network)?.interfaceName ?: type.name.lowercase()
                    update { interfaces[network] = AndroidBondingInterface(network, type, name) }
                }

                override fun onLost(network: Network) {
                    update { interfaces.remove(network) }
                }
            }
            connectivity.requestNetwork(request, callback)
            callbacks.add(callback)
        }
    }

    fun stop() {
        for (callback in callbacks) {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        callbacks.clear()
        synchronized(interfaces) { interfaces.clear() }
        listener = null
    }

    fun current(): List<BondingInterface> = synchronized(interfaces) { interfaces.values.toList() }

    private fun update(change: () -> Unit) {
        val snapshot = synchronized(interfaces) {
            change()
            interfaces.values.toList()
        }
        listener?.invoke(snapshot)
    }

    private companion object {
        val TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR to InterfaceType.CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI to InterfaceType.WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET to InterfaceType.WIRED_ETHERNET,
        )
    }
}
