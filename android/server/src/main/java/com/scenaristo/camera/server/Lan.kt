package com.scenaristo.camera.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.scenaristo.camera.domain.net.LanLink
import com.scenaristo.camera.domain.net.LanLinks
import com.scenaristo.camera.domain.net.LinkKind
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Whether there is a local network to serve on, and at which address
 * (ADR-0026, PRD 6.8).
 *
 * Two sources, because neither alone answers the question:
 *
 *  - **`NetworkInterface`** says what exists. It is the only one of the two that
 *    sees the phone's own hotspot, which is not a platform `Network` at all —
 *    the reason the address has always been read from the interface list, and
 *    the reason it still is.
 *  - **`ConnectivityManager`** says what each interface *is*. This is the half
 *    that was missing: a carrier that assigns RFC 1918 addresses makes a
 *    cellular interface indistinguishable from a Wi-Fi one by address alone, and
 *    the address is what the old code decided on.
 *
 * The decision itself is [LanLinks] in `:domain`, so iOS reaches the same answer
 * in Phase 4 (ADR-0013) and so it is testable on the host, which nothing here
 * is.
 *
 * Read on demand rather than kept live behind a `NetworkCallback`: a callback
 * never fires for the hotspot coming up, so the caller has to poll regardless,
 * and one mechanism that is always right beats two that are each right about
 * half of it (ADR-0026).
 */
class Lan(private val context: Context) {

    /** The address to serve on, or null when there is no local network. */
    fun address(): String? = LanLinks.serveAddress(links())

    /** The URL to put in front of the user, or null when there is nothing to serve. */
    fun url(port: Int = ConnectionUrl.DEFAULT_PORT): String? =
        address()?.let { ConnectionUrl.format(it, port) }

    /**
     * The interface list as one line, for the log that explains a decision.
     *
     * A phone on a tripod reports through `logcat` and nothing else, and "the
     * remote is off" without the reason is a bug report nobody can act on -- the
     * same argument ADR-0025 makes for logging why the camera is awake.
     */
    fun describe(): String = links().joinToString(" ") { link ->
        "${link.name}=${link.kind}${link.ipv4}"
    }

    private fun links(): List<LanLink> {
        val kinds = kindsByInterface()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        return interfaces?.asSequence().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .map { candidate ->
                LanLink(
                    name = candidate.name,
                    ipv4 = candidate.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                        .toList(),
                    // An interface the platform has no opinion about is local.
                    // That is the hotspot, and it is why the default is this way
                    // round (ADR-0026).
                    kind = kinds[candidate.name] ?: LinkKind.LOCAL,
                )
            }
            .toList()
    }

    /**
     * What the platform attributes each interface to.
     *
     * `allNetworks` is deprecated in favour of `registerNetworkCallback`, and is
     * used anyway: the callback answers "has anything changed", which is not the
     * question here — this needs a complete answer at a single instant, before
     * the port opens, and a callback that has not delivered yet is
     * indistinguishable from an interface the platform has no opinion on. Being
     * wrong that way round means serving on cellular, which is the one outcome
     * ADR-0026 exists to prevent.
     */
    @Suppress("DEPRECATION")
    private fun kindsByInterface(): Map<String, LinkKind> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyMap()
        val kinds = mutableMapOf<String, LinkKind>()
        for (network in runCatching { manager.allNetworks }.getOrDefault(emptyArray())) {
            val name = manager.getLinkProperties(network)?.interfaceName ?: continue
            val kind = manager.getNetworkCapabilities(network)?.let(::kindOf) ?: continue
            // Two networks can name the same interface — an IMS network beside
            // the default cellular one does. The first non-local answer sticks,
            // so a second opinion can only take an interface out of service,
            // never put one back into it.
            val known = kinds[name]
            if (known == null || known == LinkKind.LOCAL) kinds[name] = kind
        }
        return kinds
    }

    private fun kindOf(capabilities: NetworkCapabilities): LinkKind = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> LinkKind.VPN
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LinkKind.CELLULAR
        else -> LinkKind.LOCAL
    }
}
