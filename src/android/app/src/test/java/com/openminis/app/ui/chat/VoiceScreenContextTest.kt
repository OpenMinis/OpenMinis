package com.openminis.app.ui.chat

import com.openminis.app.speech.correction.ScreenContextBuilder.Kind
import com.openminis.app.speech.correction.VoiceCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-voice-viewport-context] The chat side of the on-screen correction
 * context: what each rendered row contributes, and which reply counts as the
 * latest one.
 */
class VoiceScreenContextTest {

    private fun block(id: String, kind: String, content: String = "", toolName: String = "", toolTitle: String = "") =
        AssistantBlock(id = id, kind = kind, content = content, toolName = toolName, toolTitle = toolTitle)

    @Test
    fun `a tool pill contributes its title only, never its arguments or output`() {
        val tool = block("b1", "tool_use", content = "total 48\ndrwxr-xr-x ...", toolName = "shell_execute", toolTitle = "列出主目录文件")
        val seg = FlatChatItem.AssistantToolUse("m1", tool, listOf(tool)).toScreenSegment()
        assertEquals(Kind.TOOL, seg.kind)
        assertEquals("列出主目录文件", seg.text)
        assertEquals("tool:m1:b1", seg.key)
    }

    @Test
    fun `a tool pill without a title falls back to the tool name, as the pill does`() {
        val tool = block("b1", "tool_use", toolName = "shell_execute")
        assertEquals("shell_execute", FlatChatItem.AssistantToolUse("m1", tool, listOf(tool)).toScreenSegment().text)
    }

    @Test
    fun `user and assistant rows carry their text, headers carry none`() {
        val user = FlatChatItem.UserBubble(ChatMessage(id = "u1", role = "user", content = "打开设置")).toScreenSegment()
        assertEquals(Kind.USER, user.kind); assertEquals("打开设置", user.text); assertEquals("u1", user.messageId)
        val md = FlatChatItem.AssistantMarkdownBlock("a1", "b1", "第一段", 0, false, false, "第一段").toScreenSegment()
        assertEquals(Kind.ASSISTANT, md.kind); assertEquals("第一段", md.text)
        val header = FlatChatItem.AssistantHeader("a1").toScreenSegment()
        assertNull(header.kind); assertEquals("", header.text)
    }

    @Test
    fun `the latest reply is the newest assistant turn with text, read from its text blocks`() {
        val messages = listOf(
            ChatMessage(id = "a1", role = "assistant", content = "旧回复"),
            ChatMessage(
                id = "a2", role = "assistant", content = "标题片段",
                toolBlocks = listOf(block("t", "text", content = "新回复正文"), block("x", "tool_use", toolName = "shell_execute")),
            ),
            ChatMessage(id = "u3", role = "user", content = "用户后来又说了一句"),
            // A tool-only turn in progress has no text yet: it is not the reply.
            ChatMessage(id = "a4", role = "assistant", content = "", toolBlocks = listOf(block("y", "tool_use", toolName = "file_read"))),
        )
        val latest = VoiceCorrection.latestReply(messages)!!
        assertEquals("a2", latest.messageId)
        assertEquals("新回复正文", latest.text)
    }

    @Test
    fun `no assistant text means no latest reply`() {
        assertNull(VoiceCorrection.latestReply(listOf(ChatMessage(id = "u", role = "user", content = "你好"))))
    }
}
