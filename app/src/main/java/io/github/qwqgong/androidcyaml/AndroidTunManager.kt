package io.github.qwqgong.androidcyaml

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.qwqgong.androidcyaml.network.NetworkAddressParser
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress

class AndroidTunManager(private val host: VpnPlatformHost) : Closeable {
    private val lock = Any()
    private var tunnel: ParcelFileDescriptor? = null
    private var activeOptions: TunOptions? = null

    fun open(options: TunOptions): ParcelFileDescriptor {
        synchronized(lock) {
            val current = tunnel
            if (options == activeOptions && current != null && current.fileDescriptor.valid()) {
                return current
            }
            val candidate = establish(options)
            val previous = tunnel
            tunnel = candidate
            activeOptions = options
            closeQuietly(previous)
            return candidate
        }
    }

    fun hasUsableTunnel(): Boolean = synchronized(lock) {
        tunnel?.fileDescriptor?.valid() == true
    }

    private fun establish(options: TunOptions): ParcelFileDescriptor {
        if (options.inet4Address.isEmpty() && options.inet6Address.isEmpty()) {
            throw IOException("mihomo 未提供 TUN 接口地址")
        }
        val context = host.platformContext()
        val builder = host.newPlatformBuilder()
            .setSession(context.getString(R.string.app_name))
            .setMtu(options.mtu)
            .setBlocking(false)
            .setMetered(false)
            .setUnderlyingNetworks(null)
            .setConfigureIntent(host.openAppPendingIntent())

        val hasIpv4 = addAddresses(builder, options.inet4Address)
        val hasIpv6 = addAddresses(builder, options.inet6Address)
        // AndroidCyaml owns the whole device route. User TUN route/package filters
        // would send matching app traffic outside the VPN before mihomo sees it.
        if (hasIpv4) builder.addRoute("0.0.0.0", 0)
        if (hasIpv6) builder.addRoute("::", 0)
        for (address in options.dnsServerAddress) {
            builder.addDnsServer(parseNumericAddress(address))
        }
        val established = builder.establish()
            ?: throw IOException("Android 未建立 VpnService TUN 接口")
        Log.i(TAG, "Established embedded mihomo TUN: " + options.summary())
        return established
    }

    override fun close() {
        synchronized(lock) {
            closeQuietly(tunnel)
            tunnel = null
            activeOptions = null
        }
    }

    private companion object {
        const val TAG = "AndroidCyaml/TUN"

        fun addAddresses(builder: VpnService.Builder, prefixes: List<String>): Boolean {
            var added = false
            for (value in prefixes) {
                val prefix = NetworkAddressParser.parseAddressPrefix(value)
                builder.addAddress(prefix.address, prefix.prefixLength)
                added = true
            }
            return added
        }

        fun parseNumericAddress(value: String): InetAddress =
            NetworkAddressParser.parseAddress(value)

        fun closeQuietly(descriptor: ParcelFileDescriptor?) {
            if (descriptor == null) {
                return
            }
            try {
                descriptor.close()
            } catch (exception: IOException) {
                // Descriptor cleanup is best effort during service teardown.
            }
        }
    }
}
