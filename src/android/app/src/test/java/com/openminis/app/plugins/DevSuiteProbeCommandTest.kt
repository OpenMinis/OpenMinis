package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-devsuite-qemu-probe] Pure checks for the probe binary-name expansion.
 * A package may ship the bare tool name or the -static one — Ubuntu 24.04
 * arm64 qemu-user-static ships ONLY /usr/bin/qemu-x86_64-static (no bare
 * qemu-x86_64), while older Debian ships the bare name — so the accepted
 * set must be derived from the tool name instead of one hardcoded probe.
 * No Robolectric: everything here is pure string logic.
 */
class DevSuiteProbeCommandTest {

    @Test
    fun bareToolNameAcceptsBareAndStaticVariants() {
        assertEquals(
            listOf("qemu-x86_64", "qemu-x86_64-static"),
            DevSuiteCatalog.probeBinaryCandidates("qemu-x86_64"),
        )
    }

    @Test
    fun staticToolNameAcceptsStaticFirstThenBare() {
        assertEquals(
            listOf("qemu-x86_64-static", "qemu-x86_64"),
            DevSuiteCatalog.probeBinaryCandidates("qemu-x86_64-static"),
        )
    }

    @Test
    fun plainNameKeepsExactNameFirst() {
        assertEquals(
            listOf("go", "go-static"),
            DevSuiteCatalog.probeBinaryCandidates("go"),
        )
    }

    @Test
    fun catalogQemuProbeExpandsToBothNames() {
        val qemu = DevSuiteCatalog.onlineToolById("qemu-x86-64-compat")!!
        assertEquals(
            "command -v 'qemu-x86_64' || command -v 'qemu-x86_64-static'",
            DevSuiteInstaller.probeCommand(qemu),
        )
    }

    @Test
    fun compoundCommandVProbesExpandEveryClause() {
        val kit = DevSuiteCatalog.bundleById("code-search-suite")!!.component("code-search-toolkit")!!
        assertEquals(
            "command -v 'rg' && command -v 'fzf' && command -v 'batcat'",
            DevSuiteInstaller.probeCommand(kit),
        )
    }

    @Test
    fun nonCommandVProbePassesThroughVerbatim() {
        val core = DevSuiteCatalog.bundleById("android-suite")!!.component("android-core")!!
        assertEquals(
            "test -x /opt/android-sdk/build-tools/35.0.2/aapt2 && " +
                "test -x /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager",
            DevSuiteInstaller.probeCommand(core),
        )
    }

    @Test
    fun fileGlobProbePassesThroughVerbatim() {
        val ai = DevSuiteCatalog.bundleById("python-suite")!!.component("python-ai-dev")!!
        assertEquals(
            "test -f /usr/include/Python.h || ls /usr/include/python3*/Python.h >/dev/null 2>&1",
            DevSuiteInstaller.probeCommand(ai),
        )
    }

    @Test
    fun installTargetByIdWrapsOnlineToolsAndResolvesBundles() {
        assertEquals(
            "qemu-x86-64-compat",
            DevSuiteCatalog.installTargetById("qemu-x86-64-compat")?.id,
        )
        assertEquals(
            "android-suite",
            DevSuiteCatalog.installTargetById("android-suite")?.id,
        )
    }
}
