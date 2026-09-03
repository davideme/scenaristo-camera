package com.scenaristo.camera.domain.net

/**
 * The LAN-only admission rule from ADR-0006.
 *
 * The server binds once to `0.0.0.0` (Ktor connectors are fixed when
 * `embeddedServer` is built, so per-interface binding would mean restarting the
 * server, and dropping every WebSocket, on each Wi-Fi to hotspot transition).
 * LAN-only is therefore enforced per request instead, by two independent checks:
 *
 *  1. the peer address is private or link-local, and
 *  2. the `Host` header is an IPv4 literal.
 *
 * The second check is what defeats DNS rebinding: the phone's own URLs are
 * always IP literals, while a rebinding attack necessarily arrives with a
 * hostname in `Host`. It works without the server knowing its own interfaces.
 *
 * This lives in `:domain` because it is platform-free and the iOS server must
 * apply the identical rule in Phase 4 (ADR-0013).
 */
object LanOnly {

    /** RFC 1918 private ranges, plus link-local and loopback. */
    fun isPrivateAddress(ip: String): Boolean {
        val octets = parseIpv4(ip) ?: return false
        val (a, b) = octets
        return when {
            a == 10 -> true                          // 10.0.0.0/8
            a == 172 && b in 16..31 -> true          // 172.16.0.0/12
            a == 192 && b == 168 -> true             // 192.168.0.0/16
            a == 169 && b == 254 -> true             // 169.254.0.0/16 link-local
            a == 127 -> true                         // 127.0.0.0/8 loopback
            else -> false
        }
    }

    /** True when [host] is four dot-separated decimal octets and nothing else. */
    fun isIpv4Literal(host: String): Boolean = parseIpv4(host) != null

    /**
     * Whether a request is admitted. [hostHeader] may carry a `:port` suffix,
     * which is stripped before the literal check.
     */
    fun allows(remoteAddress: String, hostHeader: String): Boolean =
        isPrivateAddress(remoteAddress) && isIpv4Literal(hostHeader.substringBefore(':'))

    private fun parseIpv4(value: String): List<Int>? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val n = part.toInt()
            if (n !in 0..255) return null
            n
        }
    }
}
