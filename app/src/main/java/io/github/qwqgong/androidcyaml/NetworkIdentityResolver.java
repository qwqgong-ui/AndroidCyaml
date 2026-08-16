package io.github.qwqgong.androidcyaml;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.NetworkCapabilities;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Builds privacy-preserving identities for validated physical networks. */
final class NetworkIdentityResolver {
    record Profile(String identity, String kind, String label) {
        Profile {
            identity = identity == null ? "" : identity;
            kind = kind == null ? "" : kind;
            label = label == null ? "" : label;
        }

        boolean available() {
            return !identity.isBlank();
        }
    }

    private static final String REDACTED_BSSID = "02:00:00:00:00:00";

    private final Context context;

    NetworkIdentityResolver(Context context) {
        this.context = context.getApplicationContext();
    }

    String resolve(NetworkCapabilities capabilities) {
        return profile(capabilities).identity();
    }

    Profile profile(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return new Profile("", "", "");
        }
        try {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return wifiProfile(capabilities);
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return cellularProfile(capabilities);
            }
        } catch (RuntimeException ignored) {
            // Permission, telephony, and Wi-Fi services may change during a handover.
        }
        return new Profile("", "", "");
    }

    private Profile wifiProfile(NetworkCapabilities capabilities) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return new Profile("", "wifi", "Wi-Fi");
        }
        Object transportInfo = capabilities.getTransportInfo();
        if (!(transportInfo instanceof WifiInfo wifiInfo)) {
            return new Profile("", "wifi", "Wi-Fi");
        }
        String ssid = normalizeSsid(wifiInfo.getSSID());
        String bssid = normalizeBssid(wifiInfo.getBSSID());
        if (ssid.isEmpty() && bssid.isEmpty()) {
            return new Profile("", "wifi", "Wi-Fi");
        }
        String name = !ssid.isEmpty() ? ssid : bssid;
        return new Profile(wifiFingerprint(ssid, bssid), "wifi", "Wi-Fi · " + name);
    }

    private Profile cellularProfile(NetworkCapabilities capabilities) {
        TelephonyManager base = context.getSystemService(TelephonyManager.class);
        if (base == null) {
            return new Profile("", "cellular", "移动数据");
        }
        int subscriptionId = subscriptionId(capabilities);
        TelephonyManager telephony = SubscriptionManager.isValidSubscriptionId(subscriptionId)
                ? base.createForSubscriptionId(subscriptionId)
                : base;
        String operator = safeTelephonyString(telephony::getSimOperator);
        String carrierName = safeTelephonyString(telephony::getSimOperatorName);
        int carrierId = safeCarrierId(telephony);
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)
                && operator.isEmpty()
                && carrierName.isEmpty()
                && carrierId < 0) {
            return new Profile("", "cellular", "移动数据");
        }
        String name = !carrierName.isEmpty() ? carrierName : operator;
        if (name.isEmpty()) {
            name = SubscriptionManager.isValidSubscriptionId(subscriptionId)
                    ? "SIM " + subscriptionId
                    : "当前 SIM";
        }
        return new Profile(
                cellularFingerprint(subscriptionId, operator, carrierId, carrierName),
                "cellular",
                "移动数据 · " + name
        );
    }

    private static int subscriptionId(NetworkCapabilities capabilities) {
        NetworkSpecifier specifier = capabilities.getNetworkSpecifier();
        if (specifier instanceof TelephonyNetworkSpecifier telephonySpecifier) {
            return telephonySpecifier.getSubscriptionId();
        }
        return SubscriptionManager.getDefaultDataSubscriptionId();
    }

    private static int safeCarrierId(TelephonyManager telephony) {
        try {
            return telephony.getSimSpecificCarrierId();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String safeTelephonyString(StringSupplier supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String normalizeSsid(String ssid) {
        if (ssid == null || ssid.isBlank() || WifiManager.UNKNOWN_SSID.equals(ssid)) {
            return "";
        }
        String value = ssid.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String normalizeBssid(String bssid) {
        if (bssid == null || bssid.isBlank() || REDACTED_BSSID.equalsIgnoreCase(bssid)) {
            return "";
        }
        return bssid.trim().toLowerCase(Locale.ROOT);
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }

    static String wifiFingerprint(String ssid, String bssid) {
        String stableSsid = ssid == null ? "" : ssid.trim();
        String fallbackBssid = bssid == null ? "" : bssid.trim().toLowerCase(Locale.ROOT);
        // BSSID identifies an access point, not a Wi-Fi environment. Keying a
        // remembered choice by it breaks on mesh roaming and multi-AP networks.
        // It remains useful only when Android redacts the SSID.
        String canonical = stableSsid.isEmpty()
                ? "wifi:v2|bssid=" + component(fallbackBssid)
                : "wifi:v2|ssid=" + component(stableSsid);
        return fingerprint(canonical);
    }

    static String cellularFingerprint(
            int subscriptionId,
            String operator,
            int carrierId,
            String carrierName
    ) {
        String stableOperator = operator == null ? "" : operator.trim();
        String fallbackName = carrierName == null ? "" : carrierName.trim();
        // Radio technology (for example 4G versus 5G) changes in place and must
        // not create a new environment. Carrier name is only a fallback because
        // it may be localized by Android.
        String carrierComponent = !stableOperator.isEmpty() || carrierId >= 0
                ? "operator=" + component(stableOperator) + "|carrier=" + carrierId
                : "name=" + component(fallbackName);
        return fingerprint(
                "cellular:v2|subscription=" + subscriptionId + "|" + carrierComponent
        );
    }

    private static String fingerprint(String canonicalIdentity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonicalIdentity.getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                encoded.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                encoded.append(Character.forDigit(value & 0x0f, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 unavailable", impossible);
        }
    }

    @FunctionalInterface
    private interface StringSupplier {
        String get();
    }
}
