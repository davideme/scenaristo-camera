package com.scenaristo.camera.server

import com.scenaristo.camera.domain.net.LanOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUrlTest {

    // PRD 6.8: the phone shows the URL and a QR code. ADR-0006 requires the
    // scheme and port to be explicit in both.
    @Test
    fun `PRD 6_8 - the connection URL always carries scheme and port`() {
        assertEquals("http://192.168.1.9:8080", ConnectionUrl.format("192.168.1.9"))
        assertEquals("http://10.0.0.4:9000", ConnectionUrl.format("10.0.0.4", 9000))
    }

    // ADR-0006: the phone's own URL is always an IP literal, which is what makes
    // the Host-header check a usable DNS-rebinding defence. If the URL we hand
    // out were ever a hostname, the server would reject its own clients.
    @Test
    fun `ADR-0006 - the advertised host is always accepted by the LAN guard`() {
        val ip = "192.168.1.9"
        val host = ConnectionUrl.format(ip).removePrefix("http://")
        assertTrue(LanOnly.allows(remoteAddress = "192.168.1.50", hostHeader = host))
    }
}
