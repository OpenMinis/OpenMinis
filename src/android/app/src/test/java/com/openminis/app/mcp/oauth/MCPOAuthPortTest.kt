package com.openminis.app.mcp.oauth

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCPOAUTH-1: the fixed 54546 failed whenever a previous listener had not
 * released the port. The helper must move to the next bindable loopback port.
 */
class MCPOAuthPortTest {

    @Test
    fun picksAPortOtherThanTheBusyPreferredOne() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val busy = ServerSocket(0, 1, loopback)
        try {
            val preferred = busy.localPort
            val chosen = MCPOAuthController.findFreeLoopbackPort(preferred, span = 20)
            assertNotEquals(preferred, chosen)
            assertTrue("chosen=$chosen", chosen in preferred until preferred + 20)
        } finally {
            busy.close()
        }
    }

    @Test
    fun returnsThePreferredPortWhenFree() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val probe = ServerSocket(0, 1, loopback)
        val free = probe.localPort
        probe.close()
        assertEquals(free, MCPOAuthController.findFreeLoopbackPort(free, span = 1))
    }
}
