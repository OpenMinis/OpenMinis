package com.openminis.app.config

import com.openminis.app.ProductionSources
import com.openminis.app.config.fields.ContainerDnsFields
import com.openminis.app.sandbox.ContainerDns
import com.openminis.app.sandbox.ContainerDns.DnsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-container-network-dns] (OpenMinis#396) `minis-config` binding for the
 * sandbox DNS: `get` / `set network.dnsMode` and `network.dnsCustomServers`,
 * driven through a real [ConfigRegistry] and the real fields, with the prefs
 * swapped for an in-memory store.
 */
class ContainerDnsConfigTest {

    private class MemStore : ContainerDns.Store {
        var m = DnsMode.AUTO
        var raw = ""
        override fun mode() = m
        override fun rawServers() = raw
        override fun save(mode: DnsMode, rawServers: String) {
            m = mode
            raw = ContainerDns.normalize(rawServers)
        }
    }

    private val store = MemStore()
    private var applied = 0
    private val registry = ConfigRegistry().also { r ->
        ContainerDnsFields.build(store) { applied++ }.forEach { r.register(it) }
    }

    private fun field(path: String) = registry.resolveField(path) ?: error("unregistered: $path")

    /** What `minis-config set <path> <value-json>` does after the gate. */
    private fun set(path: String, valueJson: String) {
        val v = ConfigValue.decode(valueJson) ?: throw ConfigError.InvalidValue("unparseable: $valueJson")
        field(path).write(v)
    }

    private fun get(path: String) = (field(path).read() as ConfigValue.Str).value

    private fun assertRejected(path: String, valueJson: String, contains: String) {
        val before = store.m to store.raw
        val appliedBefore = applied
        try {
            set(path, valueJson)
            fail("expected $valueJson to be rejected for $path")
        } catch (e: ConfigError) {
            assertTrue("message: ${e.message}", e.message.orEmpty().contains(contains))
        }
        assertEquals("a rejected write changes nothing", before, store.m to store.raw)
        assertEquals("and applies nothing", appliedBefore, applied)
    }

    @Test
    fun `both paths are registered, readable and writable`() {
        for (p in listOf("network.dnsMode", "network.dnsCustomServers")) {
            val f = field(p)
            assertEquals(ConfigAccess.READWRITE, f.access)
            assertTrue(f.revertable)
            assertTrue(f.description.isNotBlank())
        }
        assertTrue("topic shows up in list-topics", registry.topics().contains("network"))
    }

    @Test
    fun `get dnsMode defaults to auto`() {
        assertEquals("auto", get("network.dnsMode"))
        assertEquals("", get("network.dnsCustomServers"))
    }

    @Test
    fun `set dnsMode custom (unquoted, as typed in a shell) and back to auto`() {
        set("network.dnsMode", "custom")
        assertEquals(DnsMode.CUSTOM, store.m)
        assertEquals("custom", get("network.dnsMode"))
        set("network.dnsMode", "\"auto\"")
        assertEquals(DnsMode.AUTO, store.m)
        assertEquals("each write re-applies resolv.conf", 2, applied)
    }

    @Test
    fun `set dnsMode rejects anything but auto or custom`() {
        assertRejected("network.dnsMode", "manual", contains = "")
        assertRejected("network.dnsMode", "1", contains = "")
    }

    @Test
    fun `set dnsCustomServers as a quoted list, mixed IPv4 and IPv6`() {
        // JSON escape for a newline, as typed on the CLI.
        set("network.dnsCustomServers", "\"223.5.5.5, 2400:3200::1\\n8.8.8.8\"")
        assertEquals("223.5.5.5\n2400:3200::1\n8.8.8.8", get("network.dnsCustomServers"))
        assertEquals(1, applied)
        // Setting servers does not switch the mode by itself.
        assertEquals(DnsMode.AUTO, store.m)
    }

    @Test
    fun `set dnsCustomServers as a JSON array`() {
        set("network.dnsCustomServers", """["1.1.1.1","2606:4700::1111"]""")
        assertEquals("1.1.1.1\n2606:4700::1111", store.raw)
    }

    @Test
    fun `a single unquoted IPv4 address works`() {
        set("network.dnsCustomServers", "223.5.5.5")
        assertEquals("223.5.5.5", store.raw)
    }

    @Test
    fun `invalid servers are rejected with the bad entries named`() {
        assertRejected("network.dnsCustomServers", "\"1.1.1.1, dns.google\"", contains = "dns.google")
        assertRejected("network.dnsCustomServers", "\"999.1.1.1\"", contains = "999.1.1.1")
        assertRejected("network.dnsCustomServers", "[1, 2]", contains = "strings")
        assertRejected("network.dnsCustomServers", "true", contains = "expected a string")
    }

    @Test
    fun `an empty list clears the servers`() {
        set("network.dnsCustomServers", "\"8.8.8.8\"")
        set("network.dnsCustomServers", "\"\"")
        assertEquals("", store.raw)
    }

    @Test
    fun `mode and servers together give the custom plan`() {
        set("network.dnsCustomServers", "\"223.5.5.5\"")
        set("network.dnsMode", "custom")
        assertEquals(ContainerDns.Plan.Custom(listOf("223.5.5.5")), ContainerDns.plan(store.m, store.raw))
    }

    // ── value decoding on the CLI ───────────────────────────────────────────

    @Test
    fun `an unquoted value with leftover text is an error, not a silent truncation`() {
        // org.json stops an unquoted value at the first , : ; etc. These used
        // to store "223.5.5.5", "2400" and "https" and report success.
        assertNull(ConfigValue.decode("223.5.5.5,8.8.8.8"))
        assertNull(ConfigValue.decode("2400:3200::1"))
        assertNull(ConfigValue.decode("https://example.com"))
        assertNull(ConfigValue.decode("\"a\" trailing"))
        // Everything that was valid still is.
        assertEquals(ConfigValue.Str("custom"), ConfigValue.decode("custom"))
        assertEquals(ConfigValue.Str("223.5.5.5"), ConfigValue.decode("223.5.5.5"))
        assertEquals(ConfigValue.Str("2400:3200::1"), ConfigValue.decode("\"2400:3200::1\""))
        assertEquals(ConfigValue.Int(3), ConfigValue.decode(" 3 "))
        assertEquals(ConfigValue.Bool(true), ConfigValue.decode("true"))
        assertEquals(ConfigValue.Null, ConfigValue.decode("null"))
        assertTrue(ConfigValue.decode("""{"a":[1,2]}""") is ConfigValue.Obj)
    }

    @Test
    fun `the builtins register these fields against the real prefs and refresh DNS`() {
        val builtins = ProductionSources.read("config/ConfigBuiltins.kt")
        assertTrue(builtins.contains("registerNetwork(r, context)"))
        assertTrue(builtins.contains("ContainerDnsFields.build(ContainerDns.PrefsStore(context))"))
        assertTrue(builtins.contains("RootfsManager.getInstance(context).refreshDns()"))
        // The UI writes the same keys.
        val dns = ProductionSources.read("sandbox/ContainerDns.kt")
        assertTrue(dns.contains("const val KEY_MODE = \"network.dnsMode\""))
        assertTrue(dns.contains("const val KEY_SERVERS = \"network.dnsCustomServers\""))
    }
}
