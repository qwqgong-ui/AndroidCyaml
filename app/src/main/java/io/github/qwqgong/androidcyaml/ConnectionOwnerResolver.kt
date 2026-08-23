package io.github.qwqgong.androidcyaml

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ConnectionOwnerResolver(context: Context) {
    private data class CacheEntry(val packageName: String, val cachedAtNanos: Long)

    private val context: Context = context.applicationContext
    private val connectivityManager: ConnectivityManager? =
        this.context.getSystemService(ConnectivityManager::class.java)
    private val packageNames = ConcurrentHashMap<Int, CacheEntry>()

    fun resolveEncoded(
        protocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): String {
        val manager = connectivityManager ?: throw IOException("ConnectivityManager 不可用")
        val source = endpoint(sourceAddress, sourcePort)
        val destination = endpoint(destinationAddress, destinationPort)
        val uid = manager.getConnectionOwnerUid(protocol, source, destination)
        if (uid == Process.INVALID_UID) {
            throw IOException("未找到连接所属进程")
        }
        return uid.toString() + "\n" + packageNameFor(uid)
    }

    private fun packageNameFor(uid: Int): String {
        val now = System.nanoTime()
        val cached = packageNames[uid]
        if (cached != null && now - cached.cachedAtNanos < CACHE_TTL_NANOS) {
            return cached.packageName
        }
        val resolved = packageNameForUid(uid)
        packageNames[uid] = CacheEntry(resolved, now)
        return resolved
    }

    private fun packageNameForUid(uid: Int): String {
        val packageManager = context.packageManager
        val packages = packageManager.getPackagesForUid(uid)
        if (packages != null && packages.isNotEmpty()) {
            packages.sort()
            return packages[0]
        }
        val name = packageManager.getNameForUid(uid)
        return if (name.isNullOrBlank()) "uid:$uid" else name
    }

    private companion object {
        // Android can reassign an app's uid to a different package after an
        // uninstall/reinstall cycle. An unbounded cache would keep matching
        // routing rules against the old package forever; bound the staleness
        // window instead of caching for the life of the process.
        val CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10)

        fun endpoint(address: String?, port: Int): InetSocketAddress {
            if (port < 0 || port > 65_535) {
                throw IOException("无效的连接端口")
            }
            return InetSocketAddress(NetworkAddressParser.parseAddress(address), port)
        }
    }
}
