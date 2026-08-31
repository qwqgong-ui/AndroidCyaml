package io.github.qwqgong.androidcyaml

import android.content.pm.PackageManager
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
        if (options.includePackage.isNotEmpty() && options.excludePackage.isNotEmpty()) {
            throw IOException("Android 不能同时应用 include-package 与 exclude-package")
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
        if (options.autoRoute) {
            addRoutes(builder, options.inet4RouteAddress, hasIpv4, false)
            addRoutes(builder, options.inet6RouteAddress, hasIpv6, true)
            addExcludedRoutes(builder, options.inet4RouteExcludeAddress)
            addExcludedRoutes(builder, options.inet6RouteExcludeAddress)
        }
        for (address in options.dnsServerAddress) {
            builder.addDnsServer(parseNumericAddress(address))
        }
        applyPackageRouting(builder, options, context.packageName)

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

        fun applyPackageRouting(
            builder: VpnService.Builder,
            options: TunOptions,
            ownPackage: String,
        ) {
            try {
                if (options.includePackage.isNotEmpty()) {
                    // The embedded core must remain inside VpnService routing. Its
                    // real outbound sockets are excluded individually with protect().
                    builder.addAllowedApplication(ownPackage)
                    var acceptedTargets = 0
                    for (packageName in options.includePackage) {
                        if (ownPackage == packageName) {
                            continue
                        }
                        try {
                            builder.addAllowedApplication(packageName)
                            acceptedTargets++
                        } catch (exception: PackageManager.NameNotFoundException) {
                            Log.w(TAG, "Ignoring unavailable included package $packageName")
                        }
                    }
                    if (acceptedTargets == 0 && options.includePackage.none { it == ownPackage }) {
                        throw IOException("tun.include-package 未匹配任何已安装应用")
                    }
                    return
                }

                for (packageName in options.excludePackage) {
                    if (ownPackage == packageName) {
                        Log.w(TAG, "Ignoring exclusion of the embedded core package")
                        continue
                    }
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (exception: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Ignoring unavailable excluded package $packageName")
                    }
                }
            } catch (exception: PackageManager.NameNotFoundException) {
                throw IOException("AndroidCyaml 自身包无法加入 VPN 白名单", exception)
            } catch (exception: RuntimeException) {
                throw IOException("Android 应用路由配置失败", exception)
            }
        }

        fun addAddresses(builder: VpnService.Builder, prefixes: List<String>): Boolean {
            var added = false
            for (value in prefixes) {
                val prefix = NetworkAddressParser.parseAddressPrefix(value)
                builder.addAddress(prefix.address, prefix.prefixLength)
                added = true
            }
            return added
        }

        fun addRoutes(
            builder: VpnService.Builder,
            prefixes: List<String>,
            familyAvailable: Boolean,
            ipv6: Boolean,
        ) {
            if (!familyAvailable) {
                return
            }
            if (prefixes.isEmpty()) {
                builder.addRoute(if (ipv6) "::" else "0.0.0.0", 0)
                return
            }
            for (value in prefixes) {
                builder.addRoute(NetworkAddressParser.parsePrefix(value))
            }
        }

        fun addExcludedRoutes(builder: VpnService.Builder, prefixes: List<String>) {
            for (value in prefixes) {
                builder.excludeRoute(NetworkAddressParser.parsePrefix(value))
            }
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
