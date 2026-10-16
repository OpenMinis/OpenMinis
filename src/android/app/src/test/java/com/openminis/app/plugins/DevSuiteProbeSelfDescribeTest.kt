package com.openminis.app.plugins

import com.openminis.app.ProductionSources
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-devsuite-probe-selfdescribe] The FAILED status line must carry the probe
 * command. The qemu case looked like a broken install while the probe was
 * simply looking for a name the package does not ship — "安装完成但探针未通过"
 * alone gave the user (and the agent reading the status row) nothing to
 * self-diagnose. The wiring is one getString call, so the guard is a
 * source-grep: if someone reverts to the bare string resource, this fails
 * instead of the message silently regressing.
 */
class DevSuiteProbeSelfDescribeTest {

    @Test
    fun `failed status line passes the probe command to the string resource`() {
        val src = ProductionSources.read("plugins/DevSuiteInstaller.kt")
        assertNotNull("DevSuiteInstaller.kt not found", src)
        assertTrue(
            "the FAILED update must format dev_suite_probe_failed WITH the probe command:\n$src",
            Regex("dev_suite_probe_failed,\\s*probeCommand\\(component\\)").containsMatchIn(src),
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
