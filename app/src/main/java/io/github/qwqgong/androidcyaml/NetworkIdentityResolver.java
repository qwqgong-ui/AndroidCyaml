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
    private static final String REDACTED_BSSID = "02:00:00:00:00:00";

    private final Context context;

    NetworkIdentityResolver(Context context) {
        this.context = context.getApplicationContext();
    }

    String resolve(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return "";
        }
        try {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return wifiIdentity(capabilities);
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return cellularIdentity(capabilities);
            }
        } catch (RuntimeException ignored) {
            // Permission, telephony, and Wi-Fi services may change during a handover.
        }
        return "";
    }

    private String wifiIdentity(NetworkCapabilities capabilities) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return "";
        }
        Object transportInfo = capabilities.getTransportInfo();
        if (!(transportInfo instanceof WifiInfo wifiInfo)) {
            return "";
        }
        String ssid = normalizeSsid(wifiInfo.getSSID());
        String bssid = normalizeBssid(wifiInfo.getBSSID());
        if (ssid.isEmpty() && bssid.isEmpty()) {
            return "";
        }
        return fingerprint(
                "wifi:v1|ssid=" + component(ssid) + "|bssid=" + component(bssid)
        );
    }

    private String cellularIdentity(NetworkCapabilities capabilities) {
        TelephonyManager base = context.getSystemService(TelephonyManager.class);
        if (base == null) {
            return "";
        }
        int subscriptionId = subscriptionId(capabilities);
        TelephonyManager telephony = SubscriptionManager.isValidSubscriptionId(subscriptionId)
                ? base.createForSubscriptionId(subscriptionId)
                : base;
        String operator = safeTelephonyString(telephony::getSimOperator);
        String carrierName = safeTelephonyString(telephony::getSimOperatorName);
        int carrierId = safeCarrierId(telephony);
        int networkType = safeNetworkType(telephony);
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)
                && operator.isEmpty()
                && carrierName.isEmpty()
                && carrierId < 0) {
            return "";
        }
        return fingerprint(
                "cellular:v1|subscription=" + subscriptionId
                        + "|operator=" + component(operator)
                        + "|carrier=" + carrierId
                        + "|name=" + component(carrierName)
                        + "|networkType=" + networkType
        );
    }

    private static int subscriptionId(NetworkCapabilities capabilities) {
        NetworkSpecifier specifier = capabilities.getNetworkSpecifier();
        if (specifier instanceof TelephonyNetworkSpecifier telephonySpecifier) {
            return telephonySpecifier.getSubscriptionId();
        }
        return SubscriptionManager.getDefaultDataSubscriptionId();
    }

    private int safeNetworkType(TelephonyManager telephony) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
        try {
            return telephony.getDataNetworkType();
        } catch (RuntimeException ignored) {
            return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
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
