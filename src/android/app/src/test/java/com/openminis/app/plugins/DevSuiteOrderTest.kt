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
        DevBundle(
            id = "test",
            // Order test never resolves resources; real keys keep the stub
            // constructing the same shape as production call sites.
            nameRes = com.openminis.app.R.string.dev_suite_online_header,
            summaryRes = com.openminis.app.R.string.dev_suite_online_footer,
            components = components.toList(),
        )

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

    // [T-devsuite-cascade] The old assemble loop did `if (ok) ready.add(id) else
    // break`: one failure aborted every LATER selection, so a failed optional
    // Flutter download killed rust-dev and android-re even though neither
    // depends on Flutter. Only dependents may be skipped.

    @Test
    fun `an optional failure does not abort independent selections`() {
        val suite = DevSuiteCatalog.bundleById("android-suite")!!
        val ordered = DevSuiteInstaller.orderComponents(
            suite,
            setOf("flutter", "rust-dev", "android-re", "android-ndk"),
        )
        val blocked = DevSuiteInstaller.blockedByFailures(ordered, setOf("flutter"))
        assertEquals(emptySet<String>(), blocked.keys)
    }

    @Test
    fun `a failed dependency blocks only its own dependents`() {
        val suite = DevSuiteCatalog.bundleById("android-suite")!!
        val ordered = DevSuiteInstaller.orderComponents(
            suite,
            setOf("flutter", "rust-dev", "android-re", "android-ndk"),
        )
        val blocked = DevSuiteInstaller.blockedByFailures(ordered, setOf("android-core"))
        // android-ndk depends on android-core; everything else keeps going.
        assertEquals(setOf("android-ndk"), blocked.keys)
        assertEquals("android-core", blocked["android-ndk"])
    }

    @Test
    fun `blocking propagates transitively down a chain`() {
        val b = bundle(
            comp("core", required = true),
            comp("mid", "core"),
            comp("leaf", "mid"),
            comp("independent"),
        )
        val ordered = DevSuiteInstaller.orderComponents(b, setOf("mid", "leaf", "independent"))
        val blocked = DevSuiteInstaller.blockedByFailures(ordered, setOf("core"))
        assertEquals(setOf("mid", "leaf"), blocked.keys)
        assertTrue("independent" !in blocked)
    }

    @Test
    fun `a ready component is not blocked by a failed sibling`() {
        val suite = DevSuiteCatalog.bundleById("android-suite")!!
        val ordered = DevSuiteInstaller.orderComponents(suite, setOf("android-ndk"))
        assertTrue(DevSuiteInstaller.blockedByFailures(ordered, emptySet()).isEmpty())
    }

    // [T-devsuite-apt-lock] The installer used to hand the coordinator a bare
    // `apt-get install …` line: it contended the dpkg lock with the very guest
    // scripts the same assembly runs (spurious exit 100) and never refreshed
    // the package lists, so a fresh rootfs failed resolution. These are the
    // strings the guest needs to see; the subshell is what makes the EXIT trap
    // release the lock when the COMMAND ends rather than when the persistent
    // shell dies.

    @Test
    fun `apt step takes the shared guest lock, refreshes lists and releases`() {
        val c = DevComponent(
            id = "x", name = "x", description = "",
            aptPackages = listOf("ripgrep", "fd-find"),
            checkCommand = "true",
        )
        val cmd = DevSuiteInstaller.aptCommand(c)
        assertTrue(cmd, cmd.contains(". /usr/local/lib/minis/apt-lock.sh"))
        assertTrue(cmd, cmd.contains("minis_acquire_apt_lock 1800"))
        assertTrue(cmd, cmd.contains("trap minis_release_apt_lock EXIT"))
        assertTrue(cmd, cmd.contains("apt-get update"))
        assertTrue(cmd, cmd.contains("apt-get install -y --no-install-recommends ripgrep fd-find"))
        val acquireAt = cmd.indexOf("minis_acquire_apt_lock")
        val updateAt = cmd.indexOf("apt-get update")
        val installAt = cmd.indexOf("apt-get install")
        assertTrue("lock must precede update: $cmd", acquireAt in 0 until updateAt)
        assertTrue("update must precede install: $cmd", updateAt < installAt)
        assertTrue("must run in a subshell so the trap fires per command", cmd.startsWith("(") && cmd.endsWith(")"))
    }

    @Test
    fun `apt step keeps the command parseable as an install for the sandbox clocks`() {
        // BudgetClassifier splits on ; & | and inspects each segment's command:
        // the wrapped line must still expose a plain `apt-get install` segment,
        // otherwise the install loses the wall clock it needs.
        val c = DevComponent(id = "x", name = "x", description = "", aptPackages = listOf("curl"), checkCommand = "true")
        val cmd = DevSuiteInstaller.aptCommand(c)
        val segments = cmd.replace(Regex(""""[^"]*"""), " ").split(Regex("""&&|\|\||[;&|]"""))
        assertTrue(
            "no bare apt-get segment in: $cmd",
            segments.any { it.trim().startsWith("apt-get install") },
        )
    }
}
