package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-dev-suite] Catalog integrity contract. The recipes are data, so the
 * failure modes that matter are structural: duplicate ids, dangling
 * dependencies, missing probes, unsafe purge paths. A bad recipe would
 * otherwise only fail inside the guest at install time, far from the
 * change that introduced it.
 */
class DevSuiteCatalogTest {

    @Test
    fun `component ids are unique across bundles and online tools`() {
        val ids = DevSuiteCatalog.allComponents().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `bundle ids are unique`() {
        val ids = DevSuiteCatalog.bundles.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every bundle has a required component`() {
        DevSuiteCatalog.bundles.forEach { bundle ->
            assertTrue(
                "bundle ${bundle.id} has no required component",
                bundle.components.any { it.isRequired },
            )
        }
    }

    @Test
    fun `dependencies resolve inside the same bundle`() {
        DevSuiteCatalog.bundles.forEach { bundle ->
            bundle.components.forEach { c ->
                c.dependsOn.forEach { dep ->
                    assertNotNull(
                        "component ${c.id} depends on unknown $dep (bundle ${bundle.id})",
                        bundle.component(dep),
                    )
                }
            }
        }
    }

    @Test
    fun `every component has a non-blank check command`() {
        DevSuiteCatalog.allComponents().forEach { c ->
            assertTrue("component ${c.id} has no checkCommand", c.checkCommand.isNotBlank())
        }
    }

    @Test
    fun `purge paths are absolute and traversal-free`() {
        DevSuiteCatalog.allComponents().forEach { c ->
            c.purgePaths.forEach { p ->
                assertTrue("component ${c.id} purge path not absolute: $p", p.startsWith("/"))
                assertFalse("component ${c.id} purge path has ..: $p", p.contains(".."))
            }
            c.purgeLinks.forEach { l ->
                assertFalse("component ${c.id} purge link is a path: $l", l.contains('/'))
            }
        }
    }

    @Test
    fun `android core recipe downloads on demand`() {
        // The whole point of the [T-apk-size] change: android-core must NOT
        // reference a bundled asset — its postInstall drives the setup script
        // that fetches the tools with a mirror ladder.
        val core = DevSuiteCatalog.bundleById("android-suite")!!.component("android-core")!!
        assertTrue(core.postInstallSteps.any { it.contains("minis-android-sdk-setup") })
        assertTrue(core.aptPackages.isNotEmpty())
    }

    @Test
    fun `android core skips the ndk leg and android ndk runs it`() {
        // [T-devsuite-ndk-split] minis-android-sdk-setup installs the ~1 GB NDK
        // unless MINIS_SKIP_NDK=1 and exits 1 when the NDK tail check fails, so
        // an unflagged core step both pulled the NDK for a "core only" assembly
        // and marked core FAILED on an NDK hiccup. The NDK belongs to the
        // optional android-ndk component.
        val suite = DevSuiteCatalog.bundleById("android-suite")!!
        val core = suite.component("android-core")!!
        val ndk = suite.component("android-ndk")!!
        assertTrue(
            "android-core must skip the NDK: ${core.postInstallSteps}",
            core.postInstallSteps.all {
                it.contains("minis-android-sdk-setup") && it.contains("MINIS_SKIP_NDK=1")
            },
        )
        assertTrue(
            "android-ndk must run the NDK leg: ${ndk.postInstallSteps}",
            ndk.postInstallSteps.any {
                it.contains("minis-android-sdk-setup") && !it.contains("MINIS_SKIP_NDK=1")
            },
        )
        // ...and the core probe checks exactly what the core step installs.
        assertTrue(core.checkCommand.contains("aapt2"))
        assertFalse(core.checkCommand.contains("ndk"))
    }

    @Test
    fun `every downloaded payload reserves disk`() {
        // [T-devsuite-disk-gate] Components whose recipe curls a payload must
        // carry an estimate, otherwise `needed <= 0` skips the gate entirely;
        // the apt-heavy ones are not free either (dpkg keeps the .deb AND the
        // unpacked tree).
        DevSuiteCatalog.allComponents().forEach { c ->
            val downloads = c.postInstallSteps.isNotEmpty() || c.aptPackages.isNotEmpty()
            if (downloads) {
                assertTrue(
                    "component ${c.id} installs something but reserves no space",
                    c.estimatedBytes > 0L,
                )
            }
        }
    }

    @Test
    fun `deep link ids resolve to a bundle or an online tool`() {
        // [T-devsuite-tool-link] installTargetById is what the market screen
        // uses for minis://devsuite/install/<id>. Resolving only bundles made
        // an online-tool link a silent no-op: the link was consumed, the
        // market opened, nothing installed, no error.
        assertNotNull(DevSuiteCatalog.installTargetById("android-suite"))
        val tool = DevSuiteCatalog.installTargetById("llama-cpp")
        assertNotNull(tool)
        assertEquals(listOf("llama-cpp"), tool!!.components.map { it.id })
        assertTrue(DevSuiteCatalog.isOnlineToolId("llama-cpp"))
        assertFalse(DevSuiteCatalog.isOnlineToolId("android-suite"))
        assertNull(DevSuiteCatalog.installTargetById("no-such-thing"))
        // Every id the link path can produce must be a valid link target.
        (DevSuiteCatalog.bundles.map { it.id } + DevSuiteCatalog.onlineTools.map { it.id })
            .forEach { id ->
                assertNotNull("no install target for $id", DevSuiteCatalog.installTargetById(id))
                assertEquals(DevSuiteCatalog.installLink(id), "minis://devsuite/install/$id")
            }
    }

    @Test
    fun `install links use the devsuite deep-link scheme`() {
        assertEquals("minis://devsuite/install/android-suite", DevSuiteCatalog.installLink("android-suite"))
        // Every bundle id must be deep-link safe (lowercase, no slashes).
        DevSuiteCatalog.bundles.forEach { b ->
            assertTrue(b.id.matches(Regex("[a-z0-9-]+")))
        }
    }

    @Test
    fun `expected catalog surface is present`() {
        val bundleIds = DevSuiteCatalog.bundles.map { it.id }.toSet()
        assertEquals(
            setOf(
                "android-suite", "code-search-suite", "python-suite",
                "nodejs-suite", "go-suite",
            ),
            bundleIds,
        )
        val onlineIds = DevSuiteCatalog.onlineTools.map { it.id }.toSet()
        assertEquals(setOf("qemu-x86-64-compat", "llama-cpp"), onlineIds)
    }
}
