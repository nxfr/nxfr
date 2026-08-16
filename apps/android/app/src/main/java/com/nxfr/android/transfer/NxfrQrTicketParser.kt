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
                    if (!ssid.isNullOrEmpty() && !ip.isNullOrEmpty()) {
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
}
