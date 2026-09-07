package com.scenaristo.camera.domain.net

/**
 * What the platform says one interface is attached to (ADR-0026).
 *
 * Three kinds and not more, because the only question asked of this is whether
 * an address is on a network a laptop in the same room could be on.
 */
enum class LinkKind {
    /** Wi-Fi, Ethernet, or the phone's own hotspot: somewhere a laptop can be. */
    LOCAL,

    /** A carrier's mobile network, whoever else is on it. */
    CELLULAR,

    /** A tunnel. Its peers are not in the room, whatever their addresses look like. */
    VPN,
}

/** One of the phone's interfaces, reduced to what the serve decision needs. */
data class LanLink(
    val name: String,
    val ipv4: List<String>,
    val kind: LinkKind,
)

/**
 * Whether there is a local network to serve on, and at which address (ADR-0026).
 *
 * [LanOnly] answers this for one *request*, after the port is already open.
 * This answers it for the *port*, and the two are not the same question: a
 * carrier that hands its subscribers RFC 1918 addresses — several do — puts the
 * phone on a 10.0.0.0/8 network shared with strangers, and every one of them
 * passes [LanOnly.isPrivateAddress]. Address shape is not evidence of a local
 * network; the transport the address sits on is.
 *
 * So the rule is transport first, address second: an address counts only when
 * the platform attributes its interface to something local. A VPN tunnel is
 * excluded for the same reason a carrier is — ADR-0006 left "a VPN peer reaching
 * the port" as out of scope for v1, and this is what putting it in scope costs:
 * one enum case.
 *
 * The hotspot is the case that shapes the rest. When the phone *is* the access
 * point there is no platform network object to ask about, so an interface the
 * platform has no opinion on is [LinkKind.LOCAL] — the hotspot is the reason
 * this is a list of interfaces rather than a list of networks.
 *
 * Platform-free, in `:domain`, because the iOS server in Phase 4 makes the same
 * decision from the same three facts (ADR-0013).
 */
object LanLinks {

    /**
     * The address to serve on, or null when there is no local network.
     *
     * Routable private addresses win over link-local ones: 169.254 means DHCP
     * never answered, so it is a working address only in the direct-cable case
     * and a symptom of a half-joined Wi-Fi network otherwise. Within a tier the
     * first interface wins, so the answer is stable for a stable interface list.
     */
    fun serveAddress(links: List<LanLink>): String? {
        val candidates = links.filter { it.kind == LinkKind.LOCAL }
            .flatMap { it.ipv4 }
            .filter { LanOnly.isPrivateAddress(it) && !isLoopback(it) }
        return candidates.firstOrNull { !isLinkLocal(it) } ?: candidates.firstOrNull()
    }

    /** Whether the server should be listening at all (ADR-0026). */
    fun hasLocalNetwork(links: List<LanLink>): Boolean = serveAddress(links) != null

    private fun isLoopback(ip: String): Boolean = ip.startsWith("127.")

    private fun isLinkLocal(ip: String): Boolean = ip.startsWith("169.254.")
}
