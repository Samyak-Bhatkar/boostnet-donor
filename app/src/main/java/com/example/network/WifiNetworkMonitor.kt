package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

class WifiNetworkMonitor(
    private val context: Context,
    private val onWifiIpChanged: (InetAddress?) -> Unit
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var lastReportedIp: InetAddress? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkIpChange()
        }

        override fun onLost(network: Network) {
            checkIpChange()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            checkIpChange()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // fallback
        }
        checkIpChange()
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // ignore
        }
    }

    @Synchronized
    private fun checkIpChange() {
        val currentIp = getWifiIpAddress(context)
        if (currentIp != lastReportedIp) {
            lastReportedIp = currentIp
            onWifiIpChanged(currentIp)
        }
    }

    companion object {
        /**
         * Resolves the device's local Wi-Fi IP address.
         * Dual-Stack: prioritizes IPv4, falls back to non-loopback IPv6 if in an IPv6-only hotspot.
         */
        fun getWifiIpAddress(context: Context): InetAddress? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Try active Wi-Fi network via ConnectivityManager first
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    val addresses = linkProps.linkAddresses.map { it.address }
                    val ipv4 = addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    if (ipv4 != null) return ipv4
                    val ipv6 = addresses.firstOrNull { it is Inet6Address && !it.isLoopbackAddress && !it.isMulticastAddress }
                    if (ipv6 != null) return ipv6
                }
            }

            // Fallback: scan NetworkInterfaces (e.g. wlan0, ap0)
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                var fallbackIpv6: InetAddress? = null
                for (nif in interfaces) {
                    val name = nif.name.lowercase()
                    if (name.contains("wlan") || name.contains("ap") || name.contains("wifi") || name.contains("swlan")) {
                        for (addr in nif.inetAddresses) {
                            if (!addr.isLoopbackAddress) {
                                if (addr is Inet4Address) return addr
                                if (fallbackIpv6 == null && addr is Inet6Address && !addr.isMulticastAddress) {
                                    fallbackIpv6 = addr
                                }
                            }
                        }
                    }
                }
                if (fallbackIpv6 != null) return fallbackIpv6
            } catch (e: Exception) {
                // ignore
            }

            return null
        }

        /**
         * Calculates directed subnet broadcast IP (e.g. 192.168.43.255) rather than 255.255.255.255.
         */
        fun calculateBroadcastAddress(context: Context): InetAddress {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val linkProps = cm.getLinkProperties(network) ?: continue
                    for (linkAddr in linkProps.linkAddresses) {
                        val addr = linkAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val prefix = linkAddr.prefixLength
                            val ipInt = inet4ToInt(addr)
                            val maskInt = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                            val bcastInt = ipInt or maskInt.inv()
                            return intToInet4(bcastInt)
                        }
                    }
                }
            }

            // Fallback via WifiManager DhcpInfo
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val dhcp = wm?.dhcpInfo
                if (dhcp != null && dhcp.ipAddress != 0) {
                    val broadcast = (dhcp.ipAddress and 0xFFFFFF) or 0xFF000000.toInt()
                    val bytes = byteArrayOf(
                        (broadcast and 0xFF).toByte(),
                        ((broadcast ushr 8) and 0xFF).toByte(),
                        ((broadcast ushr 16) and 0xFF).toByte(),
                        ((broadcast ushr 24) and 0xFF).toByte()
                    )
                    return InetAddress.getByAddress(bytes)
                }
            } catch (e: Exception) {
                // ignore
            }

            return InetAddress.getByName("255.255.255.255")
        }

        private fun inet4ToInt(addr: Inet4Address): Int {
            val b = addr.address
            return ((b[0].toInt() and 0xFF) shl 24) or
                    ((b[1].toInt() and 0xFF) shl 16) or
                    ((b[2].toInt() and 0xFF) shl 8) or
                    (b[3].toInt() and 0xFF)
        }

        private fun intToInet4(ip: Int): InetAddress {
            val bytes = byteArrayOf(
                ((ip ushr 24) and 0xFF).toByte(),
                ((ip ushr 16) and 0xFF).toByte(),
                ((ip ushr 8) and 0xFF).toByte(),
                (ip and 0xFF).toByte()
            )
            return InetAddress.getByAddress(bytes)
        }
    }
}
