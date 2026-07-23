package io.github.qwqgong.androidcyaml;

import android.net.IpPrefix;

import java.io.IOException;
import java.net.InetAddress;

final class NetworkAddressParser {
    private NetworkAddressParser() {}

    static InetAddress parseAddress(String value) throws IOException {
        if (value == null || value.isBlank() || !value.matches("[0-9A-Fa-f:.]+")) {
            throw new IOException("invalid numeric IP address: " + value);
        }
        return InetAddress.getByName(value);
    }

    static IpPrefix parsePrefix(String value) throws IOException {
        int separator = value == null ? -1 : value.lastIndexOf('/');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IOException("invalid IP prefix: " + value);
        }
        try {
            InetAddress address = parseAddress(value.substring(0, separator));
            int length = Integer.parseInt(value.substring(separator + 1));
            return new IpPrefix(address, length);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid IP prefix: " + value, exception);
        }
    }
}
