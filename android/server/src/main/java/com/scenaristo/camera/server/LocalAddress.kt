package com.scenaristo.camera.server

import com.scenaristo.camera.domain.net.LanOnly
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The address to put in front of the user (PRD 6.8, ADR-0006).
 *
 * ADR-0006 dropped mDNS — `NsdManager` on API 34 cannot register a hostname —
 * so the URL a creator types into their laptop is an IP literal, and the phone
 * has to find its own.
 *
 * Interfaces are enumerated rather than asked of `WifiManager` because the phone
 * may be the hotspot rather than a client, and the answer must be the same in
 * both cases. Only private IPv4 addresses qualify, using the same rule the
 * server admits requests with, so the address shown is always one the server
 * would actually accept a connection from.
 */
object LocalAddress {

    fun find(): String? = NetworkInterface.getNetworkInterfaces()
        ?.asSequence()
        ?.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        ?.flatMap { it.inetAddresses.asSequence() }
        ?.filterIsInstance<Inet4Address>()
        ?.mapNotNull { it.hostAddress }
        ?.firstOrNull { LanOnly.isPrivateAddress(it) && !it.startsWith("127.") }

    /** The full URL, or null when there is no network to serve on. */
    fun url(port: Int = ConnectionUrl.DEFAULT_PORT): String? =
        find()?.let { ConnectionUrl.format(it, port) }
}
