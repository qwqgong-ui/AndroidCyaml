package io.github.qwqgong.androidcyaml

import android.net.IpPrefix
import java.io.IOException
import java.net.InetAddress

object NetworkAddressParser {
    data class AddressPrefix(
        val address: InetAddress,
        val prefixLength: Int,
    )

    fun parseAddress(value: String?): InetAddress {
        if (value == null || value.isBlank() || !value.matches(Regex("[0-9A-Fa-f:.]+"))) {
            throw IOException("invalid numeric IP address: $value")
        }
        return InetAddress.getByName(value)
    }

    fun parsePrefix(value: String?): IpPrefix {
        val prefix = parseAddressPrefix(value)
        return IpPrefix(prefix.address, prefix.prefixLength)
    }

    fun parseAddressPrefix(value: String?): AddressPrefix {
        if (value == null) {
            throw IOException("invalid IP prefix: null")
        }
        val separator = value.lastIndexOf('/')
        if (separator <= 0 || separator == value.length - 1) {
            throw IOException("invalid IP prefix: $value")
        }
        try {
            val address = parseAddress(value.substring(0, separator))
            val length = value.substring(separator + 1).toInt()
            val maximumLength = address.address.size * 8
            if (length < 0 || length > maximumLength) {
                throw IllegalArgumentException("prefix length out of range")
            }
            // IpPrefix masks host bits by design, so it must not be used as the
            // source for VpnService.Builder.addAddress(). Retain the original
            // interface address and create IpPrefix only for route operations.
            return AddressPrefix(address, length)
        } catch (exception: IllegalArgumentException) {
            throw IOException("invalid IP prefix: $value", exception)
        }
    }
}
