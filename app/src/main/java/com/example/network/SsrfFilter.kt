package com.example.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

object SsrfFilter {

    /**
     * Returns true if the address is a safe, routable public IP.
     * Returns false if the address is a bogon, private, loopback, link-local, multicast,
     * CGNAT, documentation, or reserved address (including NAT64/IPv4-mapped private IPs).
     */
    fun isSafePublicAddress(address: InetAddress): Boolean {
        // Handle IPv6 and potential embedded IPv4
        if (address is Inet6Address) {
            val raw = address.address

            // 1. Check NAT64 Well-Known Prefix: 64:ff9b::/96 (first 12 bytes = 00 64 ff 9b 00 00 00 00 00 00 00 00)
            val isNat64 = raw[0] == 0x00.toByte() && raw[1] == 0x64.toByte() &&
                    raw[2] == 0xff.toByte() && raw[3] == 0x9b.toByte() &&
                    raw[4] == 0x00.toByte() && raw[5] == 0x00.toByte() &&
                    raw[6] == 0x00.toByte() && raw[7] == 0x00.toByte() &&
                    raw[8] == 0x00.toByte() && raw[9] == 0x00.toByte() &&
                    raw[10] == 0x00.toByte() && raw[11] == 0x00.toByte()

            if (isNat64) {
                val extractedIpv4 = byteArrayOf(raw[12], raw[13], raw[14], raw[15])
                return isSafeIpv4Bytes(extractedIpv4)
            }

            // 2. Check IPv4-Mapped IPv6: ::ffff:0:0/96 (first 10 bytes = 0, next 2 = 0xff)
            var isMapped = true
            for (i in 0..9) {
                if (raw[i] != 0.toByte()) {
                    isMapped = false
                    break
                }
            }
            if (isMapped && raw[10] == 0xff.toByte() && raw[11] == 0xff.toByte()) {
                val extractedIpv4 = byteArrayOf(raw[12], raw[13], raw[14], raw[15])
                return isSafeIpv4Bytes(extractedIpv4)
            }

            // Standard IPv6 checks
            if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
                return false
            }

            // Unique Local Address: fc00::/7 (first byte has top 7 bits matching 1111110 -> 0xfc or 0xfd)
            val firstByte = raw[0].toInt() and 0xFF
            if ((firstByte and 0xFE) == 0xFC) {
                return false
            }

            // Link-Local: fe80::/10 (first byte 0xfe, second byte top 2 bits 10 -> 0x80 to 0xbf)
            val secondByte = raw[1].toInt() and 0xFF
            if (firstByte == 0xFE && (secondByte and 0xC0) == 0x80) {
                return false
            }

            // Deprecated Site-Local: fec0::/10
            if (firstByte == 0xFE && (secondByte and 0xC0) == 0xC0) {
                return false
            }

            // Multicast: ff00::/8
            if (firstByte == 0xFF) {
                return false
            }

            // Documentation: 2001:db8::/32
            if (raw[0] == 0x20.toByte() && raw[1] == 0x01.toByte() && raw[2] == 0x0d.toByte() && raw[3] == 0xb8.toByte()) {
                return false
            }

            return true
        }

        if (address is Inet4Address) {
            return isSafeIpv4Bytes(address.address)
        }

        return false
    }

    private fun isSafeIpv4Bytes(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF

        // 0.0.0.0/8 (Current network / wildcard)
        if (b0 == 0) return false

        // 10.0.0.0/8 (RFC 1918 Private)
        if (b0 == 10) return false

        // 100.64.0.0/10 (Carrier-Grade NAT / CGNAT: 100.64.0.0 to 100.127.255.255)
        if (b0 == 100 && (b1 in 64..127)) return false

        // 127.0.0.0/8 (Loopback)
        if (b0 == 127) return false

        // 169.254.0.0/16 (Link-Local)
        if (b0 == 169 && b1 == 254) return false

        // 172.16.0.0/12 (RFC 1918 Private: 172.16.0.0 to 172.31.255.255)
        if (b0 == 172 && (b1 in 16..31)) return false

        // 192.0.2.0/24 (TEST-NET-1 Documentation)
        if (b0 == 192 && b1 == 0 && b2 == 2) return false

        // 192.168.0.0/16 (RFC 1918 Private)
        if (b0 == 192 && b1 == 168) return false

        // 198.18.0.0/15 (Benchmarking: 198.18.0.0 to 198.19.255.255)
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return false

        // 198.51.100.0/24 (TEST-NET-2 Documentation)
        if (b0 == 198 && b1 == 51 && b2 == 100) return false

        // 203.0.113.0/24 (TEST-NET-3 Documentation)
        if (b0 == 203 && b1 == 0 && b2 == 113) return false

        // 224.0.0.0/4 (Multicast: 224.0.0.0 to 239.255.255.255)
        if (b0 in 224..239) return false

        // 240.0.0.0/4 (Reserved: 240.0.0.0 to 255.255.255.254)
        if (b0 in 240..255) return false

        // 255.255.255.255 (Limited Broadcast)
        if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) return false

        return true
    }
}
