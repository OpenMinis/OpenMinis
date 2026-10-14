package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-dev-suite] Dependency ordering for bundle assembly. Pure JVM — the
 * installer shells out, but the ORDER is where a wrong answer silently
 * installs pnpm before node or NDK before the SDK it probes.
 */
class DevSuiteOrderTest {

    private fun bundle(vararg components: DevComponent) =
        DevBundle(id = "test", name = "test", summary = "", components = components.toList())

    private fun comp(id: String, vararg deps: String, required: Boolean = false) =
        DevComponent(
            id = id, name = id, description = "",
            isRequired = required, dependsOn = deps.toList(),
            checkCommand = "true",
        )

    @Test
    fun `required components always included`() {
        val b = bundle(comp("core", required = true), comp("optional"))
        val ordered = DevSuiteInstaller.orderComponents(b, emptySet())
        assertEquals(listOf("core"), ordered.map { it.id })
    }

    @Test
    fun `selected optional components included`() {
        val b = bundle(comp("core", required = true), comp("extra"))
        val ordered = DevSuiteInstaller.orderComponents(b, setOf("extra"))
        assertEquals(setOf("core", "extra"), ordered.map { it.id }.toSet())
    }

    @Test
    fun `dependency installs before dependent`() {
        val b = bundle(
            comp("node", required = true),
            comp("pnpm", "node"),
        )
        val ordered = DevSuiteInstaller.orderComponents(b, setOf("pnpm"))
        assertEquals(listOf("node", "pnpm"), ordered.map { it.id })
    }

    @Test
    fun `transitive chain orders fully`() {
        // android-suite shape: ndk depends on core; user selects only ndk.
        val b = bundle(
            comp("android-core", required = true),
            comp("android-ndk", "android-core"),
            comp("rust-dev"),
        )
        val ordered = DevSuiteInstaller.orderComponents(b, setOf("android-ndk"))
        assertEquals(listOf("android-core", "android-ndk"), ordered.map { it.id })
    }

    @Test
    fun `real android suite orders core before ndk`() {
        val suite = DevSuiteCatalog.bundleById("android-suite")!!
        val ordered = DevSuiteInstaller.orderComponents(suite, setOf("android-ndk", "flutter"))
        val ids = ordered.map { it.id }
        assertEquals(0, ids.indexOf("android-core"))
        assertTrue(ids.indexOf("android-core") < ids.indexOf("android-ndk"))
    }
}
