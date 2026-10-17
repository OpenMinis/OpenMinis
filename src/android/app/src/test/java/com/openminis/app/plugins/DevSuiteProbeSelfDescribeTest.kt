package com.openminis.app.plugins

import com.openminis.app.ProductionSources
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-devsuite-probe-selfdescribe] A probe failure must retain its stage,
 * command and bounded output tail. The qemu case looked like a broken install
 * while the probe was simply looking for a name the package does not ship;
 * the structured error keeps that distinction visible to the user.
 */
class DevSuiteProbeSelfDescribeTest {

    @Test
    fun `failed status line uses the structured probe error`() {
        val src = ProductionSources.read("plugins/DevSuiteInstaller.kt")
        assertNotNull("DevSuiteInstaller.kt not found", src)
        assertTrue(
            "the FAILED update must record the probe stage:\n$src",
            "DevSuiteInstallError.Stage.PROBE" in src,
        )
        assertTrue(
            "the probe failure must retain its command and output tail:\n$src",
            "probeCommand(component)" in src && "probe.outputTail" in src,
        )
    }

    @Test
    fun `qemu description warns about the static-bare naming duality`() {
        // The description feeds the agent-facing prompt: the model must not
        // assume /usr/bin/qemu-x86_64 exists on Ubuntu 24.04 arm64 (the
        // package ships only the -static name there).
        val src = ProductionSources.read("plugins/DevSuite.kt")
        assertNotNull("DevSuite.kt not found", src)
        val qemu = src.substringAfter("qemu-x86-64-compat").substringBefore("llama-cpp")
        assertTrue(
            "qemu component description must mention the -static naming and probing first",
            qemu.contains("qemu-x86_64-static") && qemu.contains("command -v"),
        )
    }
}
