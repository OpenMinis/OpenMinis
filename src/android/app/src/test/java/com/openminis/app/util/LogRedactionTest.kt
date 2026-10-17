package com.openminis.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactionTest {

    @Test
    fun commandLineNeverReachesTheLog() {
        val secret = "curl -H 'Authorization: Bearer sk-live-123456' https://api.example.com"
        val summary = LogRedaction.command(secret)
        assertFalse(summary.contains("sk-live-123456"))
        assertFalse(summary.contains("Authorization"))
        assertTrue(summary.startsWith("cmd=curl "))
        assertTrue(summary.contains("len=${secret.length}"))
        assertTrue(summary.contains("sha="))
    }

    @Test
    fun envAssignmentAsTheFirstTokenIsNotLeaked() {
        val summary = LogRedaction.command("MYSQL_PWD=hunter2 mysql -u root")
        assertFalse(summary.contains("hunter2"))
        assertTrue(summary.startsWith("cmd=<env>"))
    }

    @Test
    fun deterministicForTheSameCommandAndDifferentAcrossCommands() {
        assertEquals(LogRedaction.command("ls -la"), LogRedaction.command("ls -la"))
        assertNotEquals(LogRedaction.command("ls -la"), LogRedaction.command("ls -l"))
    }

    @Test
    fun emptyCommandIsHandled() {
        assertEquals("cmd=<empty>", LogRedaction.command("   "))
    }
}
