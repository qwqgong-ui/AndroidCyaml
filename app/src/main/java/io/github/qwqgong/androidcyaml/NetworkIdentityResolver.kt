package io.github.qwqgong.androidcyaml

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale

/** Builds privacy-preserving identities for validated physical networks. */
class NetworkIdentityResolver(context: Context) {
    data class Profile(
        val identity: String,
        val kind: String,
        val label: String,
    ) {
        fun available(): Boolean = identity.isNotBlank()
    }

    private val context: Context = context.applicationContext

    fun resolve(capabilities: NetworkCapabilities?): String = profile(capabilities).identity

    fun profile(capabilities: NetworkCapabilities?): Profile {
        if (capabilities == null) {
            return Profile("", "", "")
        }
        try {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return wifiProfile(capabilities)
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return cellularProfile(capabilities)
            }
        } catch (ignored: RuntimeException) {
            // Permission, telephony, and Wi-Fi services may change during a handover.
        }
        return Profile("", "", "")
    }

    private fun wifiProfile(capabilities: NetworkCapabilities): Profile {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Profile("", "wifi", "Wi-Fi")
        }
        val wifiInfo = capabilities.transportInfo as? WifiInfo
            ?: return Profile("", "wifi", "Wi-Fi")
        val ssid = normalizeSsid(wifiInfo.ssid)
        val bssid = normalizeBssid(wifiInfo.bssid)
        if (ssid.isEmpty() && bssid.isEmpty()) {
            return Profile("", "wifi", "Wi-Fi")
        }
        val name = ssid.ifEmpty { bssid }
        return Profile(wifiFingerprint(ssid, bssid), "wifi", "Wi-Fi · $name")
    }

    private fun cellularProfile(capabilities: NetworkCapabilities): Profile {
        val base = context.getSystemService(TelephonyManager::class.java)
            ?: return Profile("", "cellular", "移动数据")
        val subscriptionId = subscriptionId(capabilities)
        val telephony = if (SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            base.createForSubscriptionId(subscriptionId)
        } else {
            base
        }
        val operator = safeTelephonyString { telephony.simOperator }
        val carrierName = safeTelephonyString { telephony.simOperatorName }
        val carrierId = safeCarrierId(telephony)
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId) &&
            operator.isEmpty() &&
            carrierName.isEmpty() &&
            carrierId < 0
        ) {
            return Profile("", "cellular", "移动数据")
        }
        var name = carrierName.ifEmpty { operator }
        if (name.isEmpty()) {
            name = if (SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
                "SIM $subscriptionId"
            } else {
                "当前 SIM"
            }
        }
        return Profile(
            cellularFingerprint(subscriptionId, operator, carrierId, carrierName),
            "cellular",
            "移动数据 · $name",
        )
    }

    companion object {
        private const val REDACTED_BSSID = "02:00:00:00:00:00"

        private fun subscriptionId(capabilities: NetworkCapabilities): Int {
            val specifier = capabilities.networkSpecifier
            if (specifier is TelephonyNetworkSpecifier) {
                return specifier.subscriptionId
            }
            return SubscriptionManager.getDefaultDataSubscriptionId()
        }

        private fun safeCarrierId(telephony: TelephonyManager): Int = try {
            telephony.simSpecificCarrierId
        } catch (ignored: RuntimeException) {
            -1
        }

        private fun safeTelephonyString(supplier: () -> String?): String = try {
            supplier()?.trim() ?: ""
        } catch (ignored: RuntimeException) {
            ""
        }

        private fun normalizeSsid(ssid: String?): String {
            if (ssid.isNullOrBlank() || WifiManager.UNKNOWN_SSID == ssid) {
                return ""
            }
            var value = ssid.trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            return value
        }

        private fun normalizeBssid(bssid: String?): String {
            if (bssid.isNullOrBlank() || REDACTED_BSSID.equals(bssid, ignoreCase = true)) {
                return ""
            }
            return bssid.trim().lowercase(Locale.ROOT)
        }

        private fun component(value: String): String = value.length.toString() + ":" + value

        fun wifiFingerprint(ssid: String?, bssid: String?): String {
            val stableSsid = ssid?.trim() ?: ""
            val fallbackBssid = bssid?.trim()?.lowercase(Locale.ROOT) ?: ""
            // BSSID identifies an access point, not a Wi-Fi environment. Keying a
            // remembered choice by it breaks on mesh roaming and multi-AP networks.
            // It remains useful only when Android redacts the SSID.
            val canonical = if (stableSsid.isEmpty()) {
                "wifi:v2|bssid=" + component(fallbackBssid)
            } else {
                "wifi:v2|ssid=" + component(stableSsid)
            }
            return fingerprint(canonical)
        }

        fun cellularFingerprint(
            subscriptionId: Int,
            operator: String?,
            carrierId: Int,
            carrierName: String?,
        ): String {
            val stableOperator = operator?.trim() ?: ""
            val fallbackName = carrierName?.trim() ?: ""
            // Radio technology (for example 4G versus 5G) changes in place and must
            // not create a new environment. Carrier name is only a fallback because
            // it may be localized by Android.
            val carrierComponent = if (stableOperator.isNotEmpty() || carrierId >= 0) {
                "operator=" + component(stableOperator) + "|carrier=" + carrierId
            } else {
                "name=" + component(fallbackName)
            }
            return fingerprint("cellular:v2|subscription=$subscriptionId|$carrierComponent")
        }

        fun pathFingerprint(kind: String?, linkSignature: String?): String {
            val stableKind = kind?.trim()?.ifEmpty { "physical" } ?: "physical"
            val stablePath = linkSignature?.trim() ?: ""
            if (stablePath.isEmpty()) {
                return ""
            }
            return fingerprint("cache:v1|kind=" + component(stableKind) + "|path=" + component(stablePath))
        }

        private fun fingerprint(canonicalIdentity: String): String {
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest(
                    canonicalIdentity.toByteArray(StandardCharsets.UTF_8),
                )
                val encoded = StringBuilder(digest.size * 2)
                for (value in digest) {
                    encoded.append(Character.forDigit(value.toInt() ushr 4 and 0x0f, 16))
                    encoded.append(Character.forDigit(value.toInt() and 0x0f, 16))
                }
                return encoded.toString()
            } catch (impossible: NoSuchAlgorithmException) {
                throw AssertionError("SHA-256 unavailable", impossible)
            }
        }
    }
}
