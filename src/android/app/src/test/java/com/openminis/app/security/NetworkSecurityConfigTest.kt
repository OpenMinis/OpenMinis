package com.openminis.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Validates invariants of [network_security_config.xml]:
 * 1. Base config permits both system and user CAs (for self-hosted HTTPS endpoints - Issue #258).
 * 2. High-risk official provider and auth domains strictly permit only system CAs (preventing MITM key exfiltration).
 * 3. Mutation tests verifying assertions accurately flag unsafe configurations (missing protected domains, user CA leaks into official domains, or missing user CA in base config).
 */
class NetworkSecurityConfigTest {

    private val protectedDomains = setOf(
        "openai.com",
        "anthropic.com",
        "claude.ai",
        "googleapis.com",
        "openrouter.ai",
        "deepseek.com",
        "x.ai"
    )

    private fun parseXml(content: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
        return doc.documentElement
    }

    private fun loadConfigXml(): Element {
        // Resolve network_security_config.xml relative to working directory or project root
        val candidates = listOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml"),
            File("src/android/app/src/main/res/xml/network_security_config.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: fail("Could not find network_security_config.xml in candidates: $candidates") as Nothing

        return parseXml(file.readText())
    }

    private fun assertBaseConfigInvariants(root: Element) {
        val baseConfigNodes = root.getElementsByTagName("base-config")
        assertTrue("Expected exactly one <base-config>", baseConfigNodes.length == 1)

        val baseConfig = baseConfigNodes.item(0) as Element
        val trustAnchors = baseConfig.getElementsByTagName("trust-anchors")
        assertTrue("Expected <trust-anchors> inside <base-config>", trustAnchors.length == 1)

        val certs = (trustAnchors.item(0) as Element).getElementsByTagName("certificates")
        val sources = mutableSetOf<String>()
        for (i in 0 until certs.length) {
            val el = certs.item(i) as Element
            sources.add(el.getAttribute("src"))
        }

        assertTrue("base-config must trust system certificates", sources.contains("system"))
        assertTrue("base-config must trust user certificates for private/self-hosted endpoints (Issue #258)", sources.contains("user"))
    }

    private fun assertOfficialDomainInvariants(root: Element) {
        val domainConfigs = root.getElementsByTagName("domain-config")
        val domainTrustMap = mutableMapOf<String, Pair<Boolean, Set<String>>>()

        for (i in 0 until domainConfigs.length) {
            val dc = domainConfigs.item(i) as Element
            val domainNodes = dc.getElementsByTagName("domain")
            val trustAnchorNodes = dc.getElementsByTagName("trust-anchors")

            val certSources = mutableSetOf<String>()
            if (trustAnchorNodes.length > 0) {
                val certNodes = (trustAnchorNodes.item(0) as Element).getElementsByTagName("certificates")
                for (c in 0 until certNodes.length) {
                    val certEl = certNodes.item(c) as Element
                    certSources.add(certEl.getAttribute("src"))
                }
            }

            for (d in 0 until domainNodes.length) {
                val domainEl = domainNodes.item(d) as Element
                val domainName = domainEl.textContent.trim()
                val includeSubdomains = domainEl.getAttribute("includeSubdomains") == "true"
                domainTrustMap[domainName] = Pair(includeSubdomains, certSources)
            }
        }

        for (domain in protectedDomains) {
            assertTrue("Official domain  must be explicitly declared in domain-config", domainTrustMap.containsKey(domain))
            val (includeSubdomains, certSources) = domainTrustMap[domain]!!
            assertTrue("Official domain  must include subdomains", includeSubdomains)
            assertTrue("Official domain  must trust system certificates", certSources.contains("system"))
            assertFalse("Official domain  must NEVER trust user certificates", certSources.contains("user"))
            assertEquals("Official domain  trust anchors must contain ONLY system certificates", setOf("system"), certSources)
        }
    }

    @Test
    fun `base-config permits system and user certificates`() {
        val root = loadConfigXml()
        assertBaseConfigInvariants(root)
    }

    @Test
    fun `official domains are pinned strictly to system certificates only`() {
        val root = loadConfigXml()
        assertOfficialDomainInvariants(root)
    }

    @Test
    fun `mutation test - fails when base-config lacks user certificates`() {
        val unsafeXml = """
            <network-security-config>
                <base-config cleartextTrafficPermitted="true">
                    <trust-anchors>
                        <certificates src="system" />
                    </trust-anchors>
                </base-config>
            </network-security-config>
        """.trimIndent()

        var caught = false
        try {
            assertBaseConfigInvariants(parseXml(unsafeXml))
        } catch (e: AssertionError) {
            caught = true
        }
        assertTrue("Validator must reject base-config without user certificates", caught)
    }

    @Test
    fun `mutation test - fails when official domain allows user certificates`() {
        val unsafeXml = """
            <network-security-config>
                <domain-config>
                    <domain includeSubdomains="true">openai.com</domain>
                    <trust-anchors>
                        <certificates src="system" />
                        <certificates src="user" />
                    </trust-anchors>
                </domain-config>
            </network-security-config>
        """.trimIndent()

        var caught = false
        try {
            assertOfficialDomainInvariants(parseXml(unsafeXml))
        } catch (e: AssertionError) {
            caught = true
        }
        assertTrue("Validator must reject official domain config trusting user certificates", caught)
    }

    @Test
    fun `mutation test - fails when official domain is missing from domain-config`() {
        val incompleteXml = """
            <network-security-config>
                <domain-config>
                    <domain includeSubdomains="true">openai.com</domain>
                    <trust-anchors>
                        <certificates src="system" />
                    </trust-anchors>
                </domain-config>
            </network-security-config>
        """.trimIndent()

        var caught = false
        try {
            assertOfficialDomainInvariants(parseXml(incompleteXml))
        } catch (e: AssertionError) {
            caught = true
        }
        assertTrue("Validator must reject config missing official domains (e.g. anthropic, deepseek, etc.)", caught)
    }
}
