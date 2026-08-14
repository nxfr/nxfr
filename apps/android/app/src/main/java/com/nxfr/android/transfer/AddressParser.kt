package com.nxfr.android.transfer

data class ParsedAddress(
    val host: String,
    val port: Int
) {
    val formatted: String
        get() = if (host.contains(":") && !host.startsWith("[")) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }
}

object AddressParser {
    const val DEFAULT_PORT = 17394

    fun parse(rawInput: String?, defaultPort: Int = DEFAULT_PORT): ParsedAddress? {
        if (rawInput.isNullOrBlank()) return null

        var input = rawInput.trim()

        // Strip known URI schemes if the user pasted a full URL
        if (input.startsWith("http://", ignoreCase = true)) {
            input = input.substring(7)
        } else if (input.startsWith("https://", ignoreCase = true)) {
            input = input.substring(8)
        } else if (input.startsWith("nxfr://", ignoreCase = true)) {
            input = input.substring(7)
        }

        // Strip trailing paths / queries / slashes
        val slashIndex = input.indexOf('/')
        if (slashIndex != -1) {
            input = input.substring(0, slashIndex)
        }
        val queryIndex = input.indexOf('?')
        if (queryIndex != -1) {
            input = input.substring(0, queryIndex)
        }

        input = input.trim()
        if (input.isEmpty()) return null

        // 1. Bracketed IPv6: [fe80::1] or [fe80::1]:port
        if (input.startsWith("[")) {
            val closeBracketIndex = input.indexOf(']')
            if (closeBracketIndex == -1) return null // Unclosed bracket

            val hostPart = input.substring(1, closeBracketIndex).trim()
            if (hostPart.isEmpty() || !isValidIpv6(hostPart)) return null

            val remaining = input.substring(closeBracketIndex + 1).trim()
            if (remaining.isEmpty()) {
                return ParsedAddress(hostPart, defaultPort)
            } else if (remaining.startsWith(":")) {
                val portStr = remaining.substring(1).trim()
                val port = portStr.toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                return ParsedAddress(hostPart, port)
            } else {
                return null // Garbage after bracket
            }
        }

        // 2. Unbracketed input: either IPv4 / Hostname / bare IPv6
        val colonCount = input.count { it == ':' }

        if (colonCount > 1) {
            // Multiple colons without brackets -> Bare IPv6 address (no port permitted without brackets)
            if (isValidIpv6(input)) {
                return ParsedAddress(input, defaultPort)
            }
            return null
        } else if (colonCount == 1) {
            // Exactly one colon: host:port (IPv4 or hostname)
            val parts = input.split(':')
            val hostPart = parts[0].trim()
            val portStr = parts[1].trim()

            if (hostPart.isEmpty() || !isValidHostOrIpv4(hostPart)) return null
            val port = portStr.toIntOrNull() ?: return null
            if (port !in 1..65535) return null

            return ParsedAddress(hostPart, port)
        } else {
            // No colon: IPv4 or hostname
            if (!isValidHostOrIpv4(input)) return null
            return ParsedAddress(input, defaultPort)
        }
    }

    private fun isValidHostOrIpv4(host: String): Boolean {
        if (host.isEmpty() || host.length > 255) return false

        // Check IPv4 pattern (digits and dots)
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == 4) {
            val allValidBytes = ipv4Parts.all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255 && (part == "0" || !part.startsWith("0"))
            }
            if (allValidBytes) return true
        }

        // Hostname / domain pattern (alphanumeric, dots, dashes, underscores)
        val hostnameRegex = Regex("^[a-zA-Z0-9_]([a-zA-Z0-9._-]*[a-zA-Z0-9_])?$")
        return hostnameRegex.matches(host)
    }

    private fun isValidIpv6(ip: String): Boolean {
        if (ip.isEmpty()) return false
        val clean = ip.trim()
        val parts = clean.split("::")
        if (parts.size > 2) return false // At most one '::'

        fun validHextets(s: String): Boolean {
            if (s.isEmpty()) return true
            val hextets = s.split(':')
            return hextets.all { hex ->
                hex.length in 1..4 && hex.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }
            }
        }

        return parts.all { validHextets(it) }
    }
}
