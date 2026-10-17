package com.openminis.app.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentShellPathTest {
    @Test
    fun pathRefreshReloadsPathAndCommandHash() {
        val command = PersistentShellEnvironment.pathRefreshCommand("/opt/bin:/usr/bin")
        assertTrue(command.contains("export PATH='/opt/bin:/usr/bin'"))
        assertTrue(command.contains("hash -r"))
        assertTrue(command.endsWith("\n"))
    }
}
