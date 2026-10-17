package com.openminis.app.plugins

import org.junit.Assert.assertTrue
import org.junit.Test

class DevSuiteInstallErrorTest {
    @Test
    fun exit217KeepsStageAndOutputTail() {
        val error = DevSuiteInstallError(
            componentId = "modern-package-manager",
            stage = DevSuiteInstallError.Stage.POST_INSTALL,
            exitCode = 217,
            command = "sh setup.sh",
            outputTail = "builder: failed to find cmake\nPATH=/opt/bin",
        )
        val text = error.displayText()
        assertTrue(text.contains("post_install"))
        assertTrue(text.contains("exit 217"))
        assertTrue(text.contains("failed to find cmake"))
        assertTrue(text.contains("PATH=/opt/bin"))
    }

    @Test
    fun aptCommandFailsWhenUpdateFails() {
        val cmd = DevSuiteInstaller.aptCommand(
            DevComponent(
                id = "apt",
                name = "apt",
                description = "",
                aptPackages = listOf("curl"),
                checkCommand = "true",
            ),
        )
        assertTrue(cmd.contains("apt-get update &&"))
        assertTrue(!cmd.contains("apt-get update || true"))
    }
}
