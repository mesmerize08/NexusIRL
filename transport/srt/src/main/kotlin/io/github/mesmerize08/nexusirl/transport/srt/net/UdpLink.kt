package io.github.mesmerize08.nexusirl.transport.srt.net

import io.github.mesmerize08.nexusirl.core.clock.SerialExecutor
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketException

/** Network interface type, mirroring `NWInterface.InterfaceType` as used by SRTLA. */
enum class InterfaceType { CELLULAR, WIFI, WIRED_ETHERNET, OTHER }

/**
 * A network path that sockets can be bound to. On Android this wraps an `android.net.Network`
 * ([bind] calls `network.bindSocket(socket)` and [resolve] uses `network.getAllByName`); on the JVM
 * it is the default route.
 */
interface BondingInterface {
    /** Stable identity, e.g. the Android network handle or interface name. */
    val id: String
    val type: InterfaceType

    /** System interface name (e.g. `rmnet0`), for user-configurable display names. */
    val name: String

    fun bind(socket: DatagramSocket)

    fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

/**
 * One unbound path over the system's default route: for SRTLA where nothing lists the networks
 * (desktop tools). Without any interface the SRTLA client has no path and sends nothing.
 */
object DefaultRoute : BondingInterface {
    override val id: String = "default"
    override val type: InterfaceType = InterfaceType.WIRED_ETHERNET
    override val name: String = "default"

    override fun bind(socket: DatagramSocket) = Unit
}

/** DNS lookup strategy (upstream `SettingsDnsLookupStrategy`). */
enum class DnsLookupStrategy { SYSTEM, IPV4, IPV6, IPV4_AND_IPV6 }

/** A connected UDP "connection" (upstream `NWConnection` with UDP parameters). */
interface UdpLink {
    fun send(packet: ByteArray)

    fun close()
}

fun interface UdpLinkListener {
    fun onEvent(event: UdpLinkEvent)
}

sealed interface UdpLinkEvent {
    data object Ready : UdpLinkEvent

    data class Failed(val reason: String) : UdpLinkEvent

    class Packet(val data: ByteArray) : UdpLinkEvent
}

/** Opens [UdpLink]s. Events are always delivered on the given [SerialExecutor]. */
fun interface UdpLinkFactory {
    fun open(host: String, port: Int, bondingInterface: BondingInterface?, executor: SerialExecutor, listener: UdpLinkListener): UdpLink
}

/** [UdpLinkFactory] on `java.net.DatagramSocket`, used on both the JVM and Android. */
class DatagramUdpLinkFactory(private val dnsLookupStrategy: DnsLookupStrategy = DnsLookupStrategy.SYSTEM) : UdpLinkFactory {
    override fun open(
        host: String,
        port: Int,
        bondingInterface: BondingInterface?,
        executor: SerialExecutor,
        listener: UdpLinkListener,
    ): UdpLink = DatagramUdpLink(host, port, bondingInterface, dnsLookupStrategy, executor, listener)
}

private class DatagramUdpLink(
    host: String,
    port: Int,
    bondingInterface: BondingInterface?,
    dnsLookupStrategy: DnsLookupStrategy,
    private val executor: SerialExecutor,
    private val listener: UdpLinkListener,
) : UdpLink {
    @Volatile
    private var closed = false
    private var socket: DatagramSocket? = null

    init {
        // Resolution and binding can block, so they run on the receive thread.
        Thread({ run(host, port, bondingInterface, dnsLookupStrategy) }, "udp-link-$host:$port").apply {
            isDaemon = true
            start()
        }
    }

    private fun run(host: String, port: Int, bondingInterface: BondingInterface?, strategy: DnsLookupStrategy) {
        val socket = try {
            val addresses = bondingInterface?.resolve(host) ?: InetAddress.getAllByName(host)
            val address = selectAddress(addresses, strategy) ?: throw IOException("No address for $host")
            DatagramSocket().also {
                bondingInterface?.bind(it)
                it.connect(InetSocketAddress(address, port))
            }
        } catch (e: Exception) {
            post(UdpLinkEvent.Failed(e.message ?: e.javaClass.simpleName))
            return
        }
        synchronized(this) {
            if (closed) {
                socket.close()
                return
            }
            this.socket = socket
        }
        post(UdpLinkEvent.Ready)
        val buffer = ByteArray(2048)
        while (!closed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(datagram)
            } catch (e: PortUnreachableException) {
                // ICMP port unreachable (peer not listening yet). NWConnection does not fail on
                // this, and SRT's own timers handle the outcome, so keep the link up.
                continue
            } catch (e: SocketException) {
                if (!closed) {
                    post(UdpLinkEvent.Failed(e.message ?: "Receive failed"))
                }
                return
            } catch (e: IOException) {
                // ICMP port unreachable etc. on connected sockets: keep receiving, like NWConnection.
                continue
            }
            post(UdpLinkEvent.Packet(datagram.data.copyOf(datagram.length)))
        }
    }

    private fun selectAddress(addresses: Array<InetAddress>, strategy: DnsLookupStrategy): InetAddress? = when (strategy) {
        DnsLookupStrategy.IPV4 -> addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        DnsLookupStrategy.IPV6 -> addresses.firstOrNull { it is Inet6Address } ?: addresses.firstOrNull()
        DnsLookupStrategy.SYSTEM, DnsLookupStrategy.IPV4_AND_IPV6 -> addresses.firstOrNull()
    }

    private fun post(event: UdpLinkEvent) {
        executor.execute {
            if (!closed) {
                listener.onEvent(event)
            }
        }
    }

    override fun send(packet: ByteArray) {
        val socket = this.socket ?: return
        try {
            socket.send(DatagramPacket(packet, packet.size))
        } catch (e: IOException) {
            // Dropped like an idempotent NWConnection send; loss is handled by SRT.
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
            socket?.close()
        }
    }
}
