package com.nxfr.android.discovery

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInterfaceHelper {

    /**
     * Checks whether an IPv4 address is in the Carrier-Grade NAT (CGNAT) block
     * 100.64.0.0/10 (100.64.0.0 - 100.127.255.255).
     */
    fun isCgnat(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }

    /**
     * Checks whether an interface name represents a cellular/mobile modem link.
     */
    fun isCellularInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("rmnet") ||
                lower.startsWith("pdp") ||
                lower.startsWith("ccmni") ||
                lower.startsWith("wwan") ||
                lower.startsWith("dummy") ||
                lower.contains("cell")
    }

    /**
     * Checks if interface is local/P2P/hotspot/Wi-Fi/Ethernet.
     */
    fun isPreferredInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("ap") ||
                lower.startsWith("swlan") ||
                lower.startsWith("wlan") ||
                lower.startsWith("p2p") ||
                lower.startsWith("eth") ||
                lower.startsWith("en") ||
                lower.startsWith("bridge") ||
                lower.startsWith("rndis")
    }

    /**
     * Enumerates network interfaces applying 9.22c hierarchy:
     * 1. Active, non-loopback, UP IPv4 addresses.
     * 2. Exclude cellular modems & CGNAT if valid local interfaces exist.
     * 3. Sort preferred (AP/Hotspot > WLAN > Ethernet > Other).
     */
    fun getOrderedLocalIps(context: Context? = null): List<Pair<String, String>> {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            val preferredList = mutableListOf<Pair<String, String>>()
            val fallbackList = mutableListOf<Pair<String, String>>()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val ifName = iface.name
                val isCellular = isCellularInterface(ifName)

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.isEmpty() || host == "0.0.0.0") continue

                        val isCgnatIp = isCgnat(host)
                        val isPreferred = isPreferredInterface(ifName) && !isCellular && !isCgnatIp

                        if (isPreferred) {
                            preferredList.add(ifName to host)
                        } else if (!isCellular && !isCgnatIp) {
                            preferredList.add(ifName to host)
                        } else {
                            fallbackList.add(ifName to host)
                        }
                    }
                }
            }

            // Return preferred local addresses first (AP/Hotspot > WLAN/P2P > Ethernet).
            if (preferredList.isNotEmpty()) {
                return preferredList.sortedWith(
                    compareByDescending<Pair<String, String>> {
                        val n = it.first.lowercase()
                        n.startsWith("ap") || n.startsWith("swlan")
                    }.thenByDescending {
                        val n = it.first.lowercase()
                        n.startsWith("wlan") || n.startsWith("p2p")
                    }.thenBy { it.first }
                )
            }

            return fallbackList
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /**
     * Get the single primary local IP address to advertise in web links and QR codes.
     * Returns null if no valid local interface exists.
     */
    fun getPrimaryLocalIp(context: Context? = null): String? {
        val list = getOrderedLocalIps(context)
        return list.firstOrNull()?.second
    }
}
