package io.github.qwqgong.androidcyaml

import android.content.Context
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicLong

/**
 * Network-side half of the diagnostics log.
 *
 * Two shapes of signal, because they have very different frequencies. Rare
 * transitions -- a network appearing, being lost, or becoming unusable -- get a
 * line each, since those are the moments a connectivity complaint has to be
 * lined up against. Everything that can repeat quickly is a counter instead,
 * read once per sample: a flapping mobile network can fire capability callbacks
 * many times a minute, and one line per callback would both burn the log budget
 * and bury the transitions worth reading.
 *
 * Capability changes are logged only when a flag that matters actually flips,
 * so "validated dropped" is visible while a signal-strength refresh is not.
 */
object NetworkDiagnostics {
    private val availableEvents = AtomicLong()
    private val lostEvents = AtomicLong()
    private val unusableEvents = AtomicLong()
    private val capabilityEvents = AtomicLong()
    private val linkEvents = AtomicLong()
    private val handoverEvents = AtomicLong()
    private val refreshFailures = AtomicLong()

    private val lock = Any()
    private val loggedFlags = HashMap<Long, String>()

    @Volatile
    private var underlying = "netHandle=0 netKind=- netIpv6=false netDnsCount=0"

    fun onAvailable(context: Context, handle: Long) {
        availableEvents.incrementAndGet()
        DiagnosticsLog.append(context, "net.available", "handle=$handle")
    }

    fun onLost(context: Context, handle: Long) {
        lostEvents.incrementAndGet()
        synchronized(lock) { loggedFlags.remove(handle) }
        DiagnosticsLog.append(context, "net.lost", "handle=$handle")
    }

    /** A network that is still present but no longer usable as an underlying. */
    fun onUnusable(context: Context, handle: Long, capabilities: NetworkCapabilities?) {
        unusableEvents.incrementAndGet()
        val flags = describe(capabilities)
        val changed = synchronized(lock) { loggedFlags.put(handle, UNUSABLE_MARKER) != UNUSABLE_MARKER }
        if (changed) {
            DiagnosticsLog.append(context, "net.unusable", "handle=$handle $flags")
        }
    }

    fun onCapabilitiesChanged(
        context: Context,
        handle: Long,
        capabilities: NetworkCapabilities?,
    ) {
        capabilityEvents.incrementAndGet()
        val flags = describe(capabilities)
        val changed = synchronized(lock) { loggedFlags.put(handle, flags) != flags }
        if (changed) {
            DiagnosticsLog.append(context, "net.capabilities", "handle=$handle $flags")
        }
    }

    fun onLinkPropertiesChanged() {
        linkEvents.incrementAndGet()
    }

    fun onHandover() {
        handoverEvents.incrementAndGet()
    }

    /** Counter only; the caller writes the line through its own diagnostics host. */
    fun onRefreshFailed() {
        refreshFailures.incrementAndGet()
    }

    /** Records the underlying network each sample line should carry. */
    fun setUnderlying(handle: Long, kind: String, ipv6Usable: Boolean, dnsCount: Int) {
        underlying = "netHandle=" + handle +
            " netKind=" + DiagnosticsLog.oneLine(kind.ifBlank { "-" }) +
            " netIpv6=" + ipv6Usable +
            " netDnsCount=" + dnsCount
    }

    fun sample(): String = underlying +
        " netAvailable=" + availableEvents.get() +
        " netLost=" + lostEvents.get() +
        " netUnusable=" + unusableEvents.get() +
        " netCapabilityEvents=" + capabilityEvents.get() +
        " netLinkEvents=" + linkEvents.get() +
        " netHandovers=" + handoverEvents.get() +
        " netRefreshFailures=" + refreshFailures.get()

    private fun describe(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) {
            return "transport=- validated=? notSuspended=? notMetered=? notRoaming=?"
        }
        return "transport=" + transportName(capabilities) +
            " validated=" + capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            ) +
            " notSuspended=" + capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED,
            ) +
            " notMetered=" + capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            ) +
            " notRoaming=" + capabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
            )
    }

    private fun transportName(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "usb"
        else -> "other"
    }

    private const val UNUSABLE_MARKER = "unusable"
}
