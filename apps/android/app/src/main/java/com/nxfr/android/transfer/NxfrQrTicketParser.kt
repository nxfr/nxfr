package com.nxfr.android.transfer

sealed interface QrScanResult {
    data class ConnectTicket(val deviceId: String, val addr: String) : QrScanResult
    data class DesertTicket(
        val ssid: String,
        val pw: String,
        val ip: String,
        val port: Int
    ) : QrScanResult
    data object WebUploadLink : QrScanResult
    data object Invalid : QrScanResult
}

object NxfrQrTicketParser {
    fun parse(content: String): QrScanResult {
        val trimmed = content.trim()
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return QrScanResult.WebUploadLink
        }
        // Desert Mode ticket: nxfr://desert?ssid=...&pw=...&ip=...&port=...
        if (trimmed.startsWith("nxfr://desert", ignoreCase = true)) {
            try {
                val queryStr = trimmed.substringAfter("?", missingDelimiterValue = "")
                if (queryStr.isNotEmpty()) {
                    val params = mutableMapOf<String, String>()
                    for (pair in queryStr.split("&")) {
                        val kv = pair.split("=", limit = 2)
                        if (kv.size == 2) {
                            params[kv[0]] = java.net.URLDecoder.decode(kv[1], "UTF-8")
                        }
                    }
                    val ssid = params["ssid"]
                    val pw = params["pw"] ?: ""
                    val ip = params["ip"]
                    val port = params["port"]?.toIntOrNull() ?: 17394
                    if (!ssid.isNullOrEmpty() && !ip.isNullOrEmpty() && isPrivateIp(ip)) {
                        return QrScanResult.DesertTicket(ssid, pw, ip, port)
                    }
                }
            } catch (_: Throwable) {}
        }
        // Direct connect ticket: nxfr://connect?did=...&addr=...
        if (trimmed.startsWith("nxfr://connect", ignoreCase = true)) {
            try {
                val queryStr = trimmed.substringAfter("?", missingDelimiterValue = "")
                if (queryStr.isNotEmpty()) {
                    var did: String? = null
                    var addr: String? = null
                    for (pair in queryStr.split("&")) {
                        val kv = pair.split("=", limit = 2)
                        if (kv.size == 2) {
                            if (kv[0] == "did") did = kv[1]
                            if (kv[0] == "addr") addr = kv[1]
                        }
                    }
                    if (!did.isNullOrEmpty() && !addr.isNullOrEmpty()) {
                        return QrScanResult.ConnectTicket(did, addr)
                    }
                }
            } catch (_: Throwable) {}
        }
        return QrScanResult.Invalid
    }

    /** Only accept RFC1918 private and link-local IPv4 addresses. */
    private fun isPrivateIp(ip: String): Boolean {
        val addr = try {
            java.net.InetAddress.getByName(ip)
        } catch (_: Throwable) {
            return false
        }
        if (addr !is java.net.Inet4Address) return false
        val bytes = addr.address
        if (bytes.size != 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == 10 -> true                           // 10.0.0.0/8
            b0 == 172 && b1 in 16..31 -> true           // 172.16.0.0/12
            b0 == 192 && b1 == 168 -> true              // 192.168.0.0/16
            b0 == 169 && b1 == 254 -> true              // 169.254.0.0/16 (link-local)
            else -> false
        }
    }
}
