package com.scenaristo.camera.server

/**
 * The URL shown on the phone and encoded in the QR code (PRD 6.8).
 *
 * ADR-0006 requires the explicit `http://` scheme and the port to always be
 * present: the page is served over plain HTTP from a LAN IP, and a bare
 * `192.168.1.9:8080` is not something a browser reliably treats as a URL.
 */
object ConnectionUrl {
    const val DEFAULT_PORT: Int = 8080

    fun format(ip: String, port: Int = DEFAULT_PORT): String = "http://$ip:$port"
}
