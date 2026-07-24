package io.github.qwqgong.androidcyaml;

import android.net.IpPrefix;

import java.io.IOException;
import java.net.InetAddress;

final class NetworkAddressParser {
    record AddressPrefix(InetAddress address, int prefixLength) {}

    private NetworkAddressParser() {}

    static InetAddress parseAddress(String value) throws IOException {
        if (value == null || value.isBlank() || !value.matches("[0-9A-Fa-f:.]+")) {
            throw new IOException("invalid numeric IP address: " + value);
        }
        return InetAddress.getByName(value);
    }

    static IpPrefix parsePrefix(String value) throws IOException {
        AddressPrefix prefix = parseAddressPrefix(value);
        return new IpPrefix(prefix.address(), prefix.prefixLength());
    }

    static AddressPrefix parseAddressPrefix(String value) throws IOException {
        int separator = value == null ? -1 : value.lastIndexOf('/');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IOException("invalid IP prefix: " + value);
        }
        try {
            InetAddress address = parseAddress(value.substring(0, separator));
            int length = Integer.parseInt(value.substring(separator + 1));
            int maximumLength = address.getAddress().length * 8;
            if (length < 0 || length > maximumLength) {
                throw new IllegalArgumentException("prefix length out of range");
            }
            // IpPrefix masks host bits by design, so it must not be used as the
            // source for VpnService.Builder.addAddress(). Retain the original
            // interface address and create IpPrefix only for route operations.
            return new AddressPrefix(address, length);
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid IP prefix: " + value, exception);
        }
    }
}
