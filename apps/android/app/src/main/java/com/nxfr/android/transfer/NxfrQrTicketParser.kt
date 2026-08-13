package com.nxfr.android.transfer

sealed interface QrScanResult {
    data class ConnectTicket(val deviceId: String, val addr: String) : QrScanResult
    data object WebUploadLink : QrScanResult
    data object Invalid : QrScanResult
}

object NxfrQrTicketParser {
    fun parse(content: String): QrScanResult {
        val trimmed = content.trim()
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return QrScanResult.WebUploadLink
        }
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
