package com.openminis.app.sandbox

import com.openminis.app.ProductionSources
import com.openminis.app.sandbox.ContainerDns.DnsMode
import com.openminis.app.sandbox.ContainerDns.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-container-network-dns] (OpenMinis#396) Custom sandbox DNS: what counts as
 * a server, what resolv.conf gets, and when the system DNS still wins.
 */
class RootfsCustomDnsTest {

    // ── IPv4 ────────────────────────────────────────────────────────────────

    @Test
    fun `valid IPv4 addresses`() {
        for (s in listOf("223.5.5.5", "8.8.8.8", "0.0.0.0", "255.255.255.255", "10.0.0.1", "100.100.100.100")) {
            assertTrue(s, ContainerDns.isValidIpv4(s))
        }
    }

    @Test
    fun `invalid IPv4 addresses`() {
        for (s in listOf("256.1.1.1", "1.1.1", "1.1.1.1.1", "01.1.1.1", "1.1.1.-1", "a.b.c.d", "1..1.1", "", " 1.1.1.1", "1.1.1.1 ")) {
            assertFalse(s, ContainerDns.isValidIpv4(s))
        }
    }

    // ── IPv6 ────────────────────────────────────────────────────────────────

    @Test
    fun `valid IPv6 addresses`() {
        for (s in listOf(
            "2400:3200::1", "2400:3200:baba::1", "2001:4860:4860::8888", "::1", "::", "fe80::1",
            "2001:0db8:0000:0000:0000:ff00:0042:8329", "1:2:3:4:5:6:7::", "::ffff:192.0.2.1",
            "64:ff9b::1.1.1.1", "fe80::1%wlan0", "FE80::ABCD",
        )) {
            assertTrue(s, ContainerDns.isValidIpv6(s))
        }
    }

    @Test
    fun `invalid IPv6 addresses`() {
        for (s in listOf(
            "2400:3200", "1:2:3:4:5:6:7:8:9", "1::2::3", ":::1", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:",
            "12345::1", "g::1", "1:2:3:4:5:6:7:8::", "::1.1.1", "fe80::1%", "fe80::1%wl an0", "dns.google", "",
        )) {
            assertFalse(s, ContainerDns.isValidIpv6(s))
        }
    }

    // ── parsing a user's list ───────────────────────────────────────────────

    @Test
    fun `newlines, commas, semicolons and spaces all separate entries`() {
        val r = ContainerDns.parse("223.5.5.5\n2400:3200::1, 8.8.8.8;1.1.1.1   9.9.9.9\r\n")
        assertTrue(r.isValid)
        assertEquals(listOf("223.5.5.5", "2400:3200::1", "8.8.8.8", "1.1.1.1", "9.9.9.9"), r.servers)
    }

    @Test
    fun `duplicates collapse, order is kept, pasted resolv lines and brackets work`() {
        val r = ContainerDns.parse("nameserver 1.1.1.1\nnameserver [2606:4700::1111]\n1.1.1.1")
        assertEquals(listOf("1.1.1.1", "2606:4700::1111"), r.servers)
        assertTrue(r.isValid)
    }

    @Test
    fun `invalid entries are reported verbatim, and a hostname is not an address`() {
        val r = ContainerDns.parse("1.1.1.1, dns.google, 999.1.1.1")
        assertFalse(r.isValid)
        assertEquals(listOf("dns.google", "999.1.1.1"), r.invalid)
        assertEquals(listOf("1.1.1.1"), r.servers)
    }

    @Test
    fun `blank input is valid and empty`() {
        val r = ContainerDns.parse("  \n , ")
        assertTrue(r.isValid)
        assertTrue(r.servers.isEmpty())
    }

    // ── auto / custom decision ──────────────────────────────────────────────

    @Test
    fun `auto follows the system whatever servers are stored`() {
        assertEquals(Plan.System, ContainerDns.plan(DnsMode.AUTO, "1.1.1.1"))
        assertEquals(Plan.System, ContainerDns.plan(DnsMode.AUTO, null))
    }

    @Test
    fun `custom writes exactly the stored servers`() {
        assertEquals(Plan.Custom(listOf("223.5.5.5", "2400:3200::1")), ContainerDns.plan(DnsMode.CUSTOM, "223.5.5.5\n2400:3200::1"))
    }

    @Test
    fun `custom with no usable server falls back to the system instead of breaking lookups`() {
        assertEquals(Plan.System, ContainerDns.plan(DnsMode.CUSTOM, ""))
        assertEquals(Plan.System, ContainerDns.plan(DnsMode.CUSTOM, null))
        assertEquals(Plan.System, ContainerDns.plan(DnsMode.CUSTOM, "not-an-ip"))
    }

    @Test
    fun `unknown or missing mode means auto`() {
        assertEquals(DnsMode.AUTO, DnsMode.fromWire(null))
        assertEquals(DnsMode.AUTO, DnsMode.fromWire("bogus"))
        assertEquals(DnsMode.CUSTOM, DnsMode.fromWire(" Custom "))
    }

    // ── resolv.conf ─────────────────────────────────────────────────────────

    @Test
    fun `custom resolv conf lists the servers and nothing from the network`() {
        val conf = ContainerDns.customResolvConf(listOf("223.5.5.5", "2400:3200::1"))
        val lines = conf.lines().filter { it.isNotBlank() }
        assertTrue(lines.first().startsWith("#"))
        assertEquals(listOf("nameserver 223.5.5.5", "nameserver 2400:3200::1"), lines.drop(1))
        assertFalse("no search line in custom mode", conf.contains("search"))
    }

    @Test
    fun `stored form is one address per line`() {
        assertEquals("1.1.1.1\n2400:3200::1", ContainerDns.normalize(" 1.1.1.1 , 2400:3200::1 , 1.1.1.1"))
    }

    // ── RootfsManager wiring ────────────────────────────────────────────────

    @Test
    fun `refreshDns takes the custom branch before reading the system, and auto is unchanged`() {
        val src = ProductionSources.read("sandbox/RootfsManager.kt")
        val body = src.substringAfter("fun refreshDns() {").substringBefore("\n    private fun writeResolvConf(")
        val planAt = body.indexOf("val plan = ContainerDns.plan(context)")
        val systemAt = body.indexOf("cm?.activeNetwork")
        assertTrue(planAt in 0 until systemAt)
        assertTrue(body.contains("writeResolvConf(ContainerDns.customResolvConf(plan.servers))"))
        // The auto path still reads LinkProperties and keeps its public fallback.
        assertTrue(body.contains("linkProps.dnsServers"))
        assertTrue(body.contains("nameserver 8.8.8.8"))
        // Every write goes through the one helper.
        assertEquals(1, Regex("""File\(rootfsDir, "etc/resolv\.conf"\)""").findAll(src).count())
    }

    @Test
    fun `install, boot and network changes all go through refreshDns`() {
        assertTrue(ProductionSources.read("sandbox/RootfsManager.kt").contains("            refreshDns()"))
        assertTrue(ProductionSources.read("sandbox/PRootKernel.kt").contains("rootfsManager.refreshDns()"))
        assertTrue(ProductionSources.read("network/NetworkMonitor.kt").contains("RootfsManager.getInstance(ctx).refreshDns()"))
    }
}
