package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#323] Old screenshots must leave the prompt as a path, not pixels.
 * iOS already keeps only the last 20; this locks the Android port.
 */
class ContextImageTrimTest {

    @Test
    fun underKeepCountIsUnchanged() {
        val history = (1..5).map { imageMsg("t$it", ByteArray(10)) }
        val result = ContextImageTrim.trim(history, keep = 20) { _, _, _ -> error("no snapshot") }
        assertEquals(0, result.evicted)
        assertTrue(result.history === history)
    }

    @Test
    fun evictsOldestAndKeepsMostRecent() {
        val history = (1..5).map { imageMsg("t$it", ByteArray(it * 10)) }
        var snaps = 0
        val result = ContextImageTrim.trim(history, keep = 2) { id, _, _ ->
            snaps++
            "/var/minis/offloads/tools/$id.png"
        }
        assertEquals(3, result.evicted)
        assertEquals(3, snaps)
        assertNull(toolImage(result.history[0]))
        assertNull(toolImage(result.history[1]))
        assertNull(toolImage(result.history[2]))
        assertEquals(40, toolImage(result.history[3])?.size)
        assertEquals(50, toolImage(result.history[4])?.size)
        assertTrue(result.history[0].contentParts.filterIsInstance<AgentContentPart.ToolResult>()
            .first().content.contains("use read_image"))
    }

    @Test
    fun persistentPathSkipsSnapshot() {
        val history = listOf(
            imageMsg(
                "t1",
                ByteArray(8),
                linuxPath = "/var/minis/workspace/shot.png",
            ),
            imageMsg("t2", ByteArray(8)),
            imageMsg("t3", ByteArray(8)),
        )
        var snaps = 0
        val result = ContextImageTrim.trim(history, keep = 1) { _, _, _ ->
            snaps++
            "/var/minis/offloads/tools/x.png"
        }
        assertEquals(2, result.evicted)
        assertEquals(1, snaps) // only the volatile middle image
        val first = result.history[0].contentParts.filterIsInstance<AgentContentPart.ToolResult>().first()
        assertTrue(first.content.contains("/var/minis/workspace/shot.png"))
        assertFalse(first.content.contains("snapshot:"))
    }

    @Test
    fun standaloneImageDataBecomesTextPlaceholder() {
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ImageData(ByteArray(32), "image/png"),
                ),
            ),
            imageMsg("keep", ByteArray(4)),
        )
        val result = ContextImageTrim.trim(history, keep = 1) { id, _, _ ->
            "/var/minis/offloads/tools/$id.png"
        }
        assertEquals(1, result.evicted)
        val part = result.history[0].contentParts.single()
        assertTrue(part is AgentContentPart.Text)
        assertTrue((part as AgentContentPart.Text).text.contains("saved to /var/minis/offloads"))
        assertEquals(4, toolImage(result.history[1])?.size)
    }

    @Test
    fun readImageToolUseSuppliesOriginalPath() {
        val history = listOf(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolUse(
                        "call1",
                        "read_image",
                        JSONObject().put("path", "/tmp/volatile.png"),
                    ),
                ),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(
                        id = "call1",
                        name = "",
                        content = "ok",
                        imageData = ByteArray(16),
                        imageMimeType = "image/png",
                    ),
                ),
            ),
            imageMsg("keep", ByteArray(4)),
        )
        val result = ContextImageTrim.trim(history, keep = 1) { _, _, _ ->
            "/var/minis/offloads/tools/snap.png"
        }
        val content = result.history[1].contentParts.filterIsInstance<AgentContentPart.ToolResult>()
            .first().content
        assertTrue(content.contains("/tmp/volatile.png"))
        assertTrue(content.contains("snapshot: /var/minis/offloads/tools/snap.png"))
    }

    @Test
    fun isPersistentMinisPathCoversLinuxAndUrlViews() {
        assertTrue(ContextImageTrim.isPersistentMinisPath("/var/minis/attachments/a.png"))
        assertTrue(ContextImageTrim.isPersistentMinisPath("minis://offloads/tools/x.png"))
        assertFalse(ContextImageTrim.isPersistentMinisPath("/tmp/x.png"))
        assertFalse(ContextImageTrim.isPersistentMinisPath("https://example.com/a.png"))
    }

    private fun imageMsg(
        id: String,
        bytes: ByteArray,
        linuxPath: String? = null,
    ): LLMMessage = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolResult(
                id = id,
                name = "browser_use",
                content = "shot",
                imageData = bytes,
                imageMimeType = "image/png",
                imageLinuxPath = linuxPath,
            ),
        ),
    )

    private fun toolImage(msg: LLMMessage): ByteArray? =
        msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>().firstOrNull()?.imageData
}
