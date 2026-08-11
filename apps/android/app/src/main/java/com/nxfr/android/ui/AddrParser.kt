package com.nxfr.android.ui

/**
 * Parses an "ip:port" string into a validated (host, port) pair.
 *
 * Accepted format: `^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d{1,5}$`
 * Each octet must be 0–255, port must be 1–65535.
 *
 * @return (host, port) or null if invalid.
 */
fun parseAddr(input: String): Pair<String, Int>? {
    val trimmed = input.trim()
    val regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3}):(\d{1,5})$""")
    val match = regex.matchEntire(trimmed) ?: return null
    val (o1, o2, o3, o4, portStr) = match.destructured
    val octets = listOf(o1, o2, o3, o4).map { it.toIntOrNull() ?: return null }
    if (octets.any { it !in 0..255 }) return null
    val port = portStr.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return "${octets[0]}.${octets[1]}.${octets[2]}.${octets[3]}" to port
}
