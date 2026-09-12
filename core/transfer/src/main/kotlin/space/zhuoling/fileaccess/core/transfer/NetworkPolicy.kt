package space.zhuoling.fileaccess.core.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import space.zhuoling.fileaccess.core.model.StorageError
import space.zhuoling.fileaccess.core.model.StorageException

@Singleton
class NetworkPolicy @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager get() = context.getSystemService(ConnectivityManager::class.java)

    /** Both the socket factory and policy checks select this exact network. A VPN is respected. */
    private fun selected(): Pair<Network, NetworkCapabilities>? {
        val active = manager.activeNetwork
        val available = manager.allNetworks.mapNotNull { network ->
            manager.getNetworkCapabilities(network)?.let { network to it }
        }
        val current = available.firstOrNull { it.first == active }
        return current?.takeIf { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            ?: available.firstOrNull { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            ?: current
    }

    fun selectedNetworkHandle(): Long? = selected()?.first?.networkHandle

    /** No INTERNET or VALIDATED check: a NAS can be usable on an offline LAN. */
    fun waitingReason(wifiOnly: Boolean, unmeteredOnly: Boolean, chargingOnly: Boolean): String? {
        val selected = selected()?.second ?: return "等待网络连接"
        if (wifiOnly && !selected.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "等待 Wi-Fi"
        if (unmeteredOnly && !selected.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return "等待非计费网络"
        }
        val battery = context.getSystemService(BatteryManager::class.java)
        if (chargingOnly && !battery.isCharging) return "等待充电"
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (!battery.isCharging && level in 0..15) return "电量较低，等待充电"
        return null
    }

    /** Bind only this connection's sockets and DNS, never the process-wide network. */
    fun socketFactory(): SocketFactory {
        val network = selected()?.first ?: throw StorageException(StorageError.NETWORK, "No usable network")
        return BoundSocketFactory(network)
    }
}

private class BoundSocketFactory(private val network: Network) : SocketFactory() {
    override fun createSocket(): Socket = network.socketFactory.createSocket()
    override fun createSocket(host: String, port: Int): Socket = connect(network.getAllByName(host), port)
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(arrayOf(host), port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connect(network.getAllByName(host), port, InetSocketAddress(localHost, localPort))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connect(arrayOf(address), port, InetSocketAddress(localAddress, localPort))

    private fun connect(addresses: Array<InetAddress>, port: Int, local: InetSocketAddress? = null): Socket {
        var failure: java.io.IOException? = null
        for (address in addresses.take(3)) {
            val socket = createSocket()
            try {
                if (local != null) socket.bind(local)
                socket.connect(InetSocketAddress(address, port), 10_000)
                return socket
            } catch (error: java.io.IOException) {
                runCatching { socket.close() }
                failure = error
            }
        }
        throw failure ?: java.io.IOException("Host has no usable address")
    }
}
