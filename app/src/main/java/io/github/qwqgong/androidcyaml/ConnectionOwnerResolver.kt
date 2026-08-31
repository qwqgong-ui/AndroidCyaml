package io.github.qwqgong.androidcyaml

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import io.github.qwqgong.androidcyaml.network.NetworkAddressParser
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

    // Misses only. A hit must never be cached: the four-tuple is unique per live
    // connection, so a positive entry could only ever be read back after the
    // kernel reused the port for a different app, and it would answer with the
    // wrong one. A miss is different -- the socket was already gone when we
    // asked, and asking again about the same dead four-tuple cannot start
    // returning an owner.
    private val recentMisses = ConcurrentHashMap<String, Long>()

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
        val flow = "$protocol|$source|$destination"
        val now = System.nanoTime()
        if (isRecentMiss(flow, now)) {
            throw IOException("未找到连接所属进程")
        }
        // The Binder round trip into ConnectivityService. It is the whole cost of
        // process matching and there is no batch form of it, so the only wins
        // available are not making the call twice for the same dead flow and not
        // making it at all when no rule needs a process (mihomo's strict mode).
        val uid = manager.getConnectionOwnerUid(protocol, source, destination)
        if (uid == Process.INVALID_UID) {
            rememberMiss(flow, now)
            throw IOException("未找到连接所属进程")
        }
        return uid.toString() + "\n" + packageNameFor(uid)
    }

    private fun isRecentMiss(flow: String, now: Long): Boolean {
        val missedAt = recentMisses[flow] ?: return false
        if (now - missedAt < MISS_TTL_NANOS) {
            return true
        }
        recentMisses.remove(flow, missedAt)
        return false
    }

    private fun rememberMiss(flow: String, now: Long) {
        // A reconnect storm is exactly when this map grows and exactly when the
        // process must not be paying for it. Sweeping on the overflowing insert
        // keeps it bounded without a timer or a background thread.
        if (recentMisses.size >= MAX_TRACKED_MISSES) {
            recentMisses.entries.removeIf { now - it.value >= MISS_TTL_NANOS }
            if (recentMisses.size >= MAX_TRACKED_MISSES) {
                recentMisses.clear()
            }
        }
        recentMisses[flow] = now
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

        // Short, because a miss is only worth remembering for as long as the
        // retries that follow it. mihomo re-dials a failed connection within
        // seconds; past that the four-tuple may legitimately belong to a new
        // socket and must be asked about again.
        val MISS_TTL_NANOS = TimeUnit.SECONDS.toNanos(5)

        const val MAX_TRACKED_MISSES = 512

        fun endpoint(address: String?, port: Int): InetSocketAddress {
            if (port < 0 || port > 65_535) {
                throw IOException("无效的连接端口")
            }
            return InetSocketAddress(NetworkAddressParser.parseAddress(address), port)
        }
    }
}
