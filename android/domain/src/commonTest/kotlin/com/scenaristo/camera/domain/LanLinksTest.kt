package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.net.LanLink
import com.scenaristo.camera.domain.net.LanLinks
import com.scenaristo.camera.domain.net.LinkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanLinksTest {

    private fun wifi(vararg ipv4: String) = LanLink("wlan0", ipv4.toList(), LinkKind.LOCAL)
    private fun cellular(vararg ipv4: String) = LanLink("rmnet_data0", ipv4.toList(), LinkKind.CELLULAR)
    private fun vpn(vararg ipv4: String) = LanLink("tun0", ipv4.toList(), LinkKind.VPN)

    // ADR-0026 / PRD 6.8: the phone is on Wi-Fi, so that is the address a laptop
    // types.
    @Test
    fun `ADR-0026 - a Wi-Fi address is served`() {
        assertEquals("192.168.1.9", LanLinks.serveAddress(listOf(wifi("192.168.1.9"))))
    }

    // The case the whole rule exists for: some carriers assign RFC 1918
    // addresses, so a cellular interface can look exactly like a LAN one. It is
    // not one, and nobody on it should reach the camera.
    @Test
    fun `ADR-0026 - a carrier-assigned private address is not a local network`() {
        val links = listOf(cellular("10.24.3.7"))
        assertNull(LanLinks.serveAddress(links))
        assertFalse(LanLinks.hasLocalNetwork(links))
    }

    @Test
    fun `ADR-0026 - Wi-Fi wins over a cellular link that is also up`() {
        assertEquals(
            "192.168.1.9",
            LanLinks.serveAddress(listOf(cellular("10.24.3.7"), wifi("192.168.1.9"))),
        )
    }

    // ADR-0006 left a VPN peer reaching the port out of scope; this is what
    // putting it in scope looks like. The tunnel's own address is never served.
    @Test
    fun `ADR-0026 - a VPN tunnel is not a local network`() {
        assertNull(LanLinks.serveAddress(listOf(vpn("10.8.0.2"), cellular("10.24.3.7"))))
    }

    @Test
    fun `ADR-0026 - a VPN over Wi-Fi still serves the Wi-Fi address`() {
        assertEquals(
            "192.168.1.9",
            LanLinks.serveAddress(listOf(vpn("10.8.0.2"), wifi("192.168.1.9"))),
        )
    }

    // PRD 6.8: with no Wi-Fi the phone's own hotspot is the answer. The platform
    // reports no network for it, so it arrives with no opinion attached and must
    // still be served.
    @Test
    fun `PRD 6_8 - the phone's own hotspot is a local network`() {
        val hotspot = LanLink("ap0", listOf("192.168.43.1"), LinkKind.LOCAL)
        assertEquals("192.168.43.1", LanLinks.serveAddress(listOf(hotspot)))
    }

    @Test
    fun `ADR-0026 - loopback alone is not a local network`() {
        assertNull(LanLinks.serveAddress(listOf(LanLink("lo", listOf("127.0.0.1"), LinkKind.LOCAL))))
    }

    // A public address on a local interface is not served either: it would be
    // refused by LanOnly on every request anyway, so advertising it would put a
    // URL on the phone's screen that nothing can open.
    @Test
    fun `ADR-0006 - only addresses the request guard would admit are served`() {
        assertNull(LanLinks.serveAddress(listOf(wifi("93.184.216.34"))))
    }

    // 169.254 means DHCP never answered. Usable on a direct cable, so it is
    // served, but only when nothing better is up.
    @Test
    fun `ADR-0026 - a routable private address beats a link-local one`() {
        assertEquals(
            "192.168.1.9",
            LanLinks.serveAddress(listOf(wifi("169.254.7.7"), wifi("192.168.1.9"))),
        )
        assertEquals("169.254.7.7", LanLinks.serveAddress(listOf(wifi("169.254.7.7"))))
    }

    // An interface with only an IPv6 address is not a candidate: LanOnly admits
    // no IPv6 peer, so a v6-only link would advertise an address the server
    // itself would 403.
    @Test
    fun `ADR-0006 - an IPv6-only link is not served`() {
        assertNull(LanLinks.serveAddress(listOf(LanLink("wlan0", emptyList(), LinkKind.LOCAL))))
    }

    @Test
    fun `ADR-0026 - no interfaces at all is no local network`() {
        assertFalse(LanLinks.hasLocalNetwork(emptyList()))
        assertTrue(LanLinks.hasLocalNetwork(listOf(wifi("192.168.1.9"))))
    }
}
