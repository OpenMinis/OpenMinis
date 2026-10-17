package com.openminis.app.mcp.client

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JSON-RPC 2.0 permits string ids. The old stdio reader used `optInt` and
 * dropped every string id, so a spec-compliant server's reply never matched
 * the pending call and the tool waited out its timeout.
 */
class McpJsonRpcIdTest {

    @Test
    fun idKeyNormalizesNumberAndStringToTheSameWireKey() {
        assertEquals("5", McpJsonRpc.idKey(5))
        assertEquals("5", McpJsonRpc.idKey("5"))
        assertNull(McpJsonRpc.idKey(null))
        assertNull(McpJsonRpc.idKey(JSONObject.NULL))
    }

    @Test
    fun stringReplyIdMatchesTheNumericRequestId() {
        val body = """{"jsonrpc":"2.0","id":"7","result":{"ok":true}}"""
        assertNotNull(McpJsonRpc.extractResponse(body, "application/json", 7))
    }

    @Test
    fun numericReplyIdStillMatches() {
        val body = """{"jsonrpc":"2.0","id":7,"result":{"ok":true}}"""
        assertNotNull(McpJsonRpc.extractResponse(body, "application/json", 7))
    }

    @Test
    fun mismatchedIdIsNotAccepted() {
        val body = """{"jsonrpc":"2.0","id":"8","result":{}}"""
        assertNull(McpJsonRpc.extractResponse(body, "application/json", 7))
    }
}
