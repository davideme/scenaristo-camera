package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.net.LanOnly
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanOnlyTest {

    // PRD 6.8 (security) / ADR-0006: reject any request whose remote address is
    // not a private or link-local address.
    @Test
    fun `PRD 6_8 - private and link-local peers are admitted`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.255.254", "169.254.1.1", "127.0.0.1")
            .forEach { assertTrue(LanOnly.isPrivateAddress(it), "expected $it to be private") }
    }

    @Test
    fun `PRD 6_8 - public peers are refused`() {
        // 172.15 and 172.32 sit just outside the 172.16/12 block.
        listOf("8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "193.168.1.1", "11.0.0.1")
            .forEach { assertFalse(LanOnly.isPrivateAddress(it), "expected $it to be public") }
    }

    // PRD 6.8 / ADR-0006: the Host header must be an IPv4 literal. The phone's
    // own URLs always are; a DNS-rebinding attack arrives with a hostname.
    @Test
    fun `PRD 6_8 - a hostname in Host is refused even from a LAN peer`() {
        assertTrue(LanOnly.allows("192.168.1.20", "192.168.1.9:8080"))
        assertFalse(LanOnly.allows("192.168.1.20", "attacker.example.com:8080"))
        assertFalse(LanOnly.allows("192.168.1.20", "scenaristo.local"))
    }

    @Test
    fun `PRD 6_8 - both checks must pass, not either`() {
        // LAN peer, literal Host: admitted.
        assertTrue(LanOnly.allows("10.1.2.3", "10.1.2.9:8080"))
        // Public peer, literal Host: refused.
        assertFalse(LanOnly.allows("8.8.8.8", "10.1.2.9:8080"))
    }

    @Test
    fun `PRD 6_8 - malformed addresses are refused rather than parsed loosely`() {
        listOf("192.168.1", "192.168.1.1.1", "192.168.1.256", "192.168.1.", "", "1e2.0.0.1", "0x10.0.0.1")
            .forEach { assertFalse(LanOnly.isIpv4Literal(it), "expected $it to be rejected") }
    }
}
