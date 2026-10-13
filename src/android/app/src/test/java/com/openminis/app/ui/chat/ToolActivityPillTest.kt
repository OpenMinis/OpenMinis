package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [P0-4] The floating activity pill pins exactly one block — the latest tool
 * of the latest assistant reply — and only while a turn streams in fold-OFF
 * mode. These cover the pure projection the mount gate and the data
 * pipeline share (floatingOverlayBlocks) and the elapsed-text formatter.
 */
class ToolActivityPillTest {
    private fun tool(
        id: String,
        status: ToolBlockStatus? = ToolBlockStatus.RUNNING,
        startTimeMs: Long = 0L,
        durationMs: Long = 0L,
        name: String = "shell_execute",
    ) = AssistantBlock(
        id = id,
        kind = "tool_use",
        toolName = name,
        toolTitle = name,
        toolStatus = status,
        startTimeMs = startTimeMs,
        durationMs = durationMs,
    )

    private fun assistant(id: String, vararg blocks: AssistantBlock) = ChatMessage(
        id = id,
        role = "assistant",
        content = "",
        toolBlocks = blocks.toList(),
    )

    @Test
    fun foldOnYieldsNoOverlay() {
        val overlay = floatingOverlayBlocks(
            assistant("m", tool("t1")),
            foldAiProcess = true,
            showSubAgentBar = false,
            activeSubAgentToolIds = emptySet(),
        )
        assertEquals(emptyList<AssistantBlock>(), overlay)
    }

    @Test
    fun foldOffKeepsEveryToolOfTheLatestReply() {
        val overlay = floatingOverlayBlocks(
            assistant("m", tool("t1", ToolBlockStatus.SUCCESS), tool("t2")),
            foldAiProcess = false,
            showSubAgentBar = false,
            activeSubAgentToolIds = emptySet(),
        )
        assertEquals(listOf("t1", "t2"), overlay.map { it.id })
    }

    @Test
    fun liveSubAgentCardIsExcludedWhileTheBarOwnsIt() {
        val overlay = floatingOverlayBlocks(
            assistant("m", tool("t1", name = "spawn_agent"), tool("t2")),
            foldAiProcess = false,
            showSubAgentBar = true,
            activeSubAgentToolIds = setOf("t1"),
        )
        assertEquals(listOf("t2"), overlay.map { it.id })
    }

    @Test
    fun completedSubAgentCardStaysInTheOverlay() {
        val overlay = floatingOverlayBlocks(
            assistant("m", tool("t1", ToolBlockStatus.SUCCESS, name = "spawn_agent")),
            foldAiProcess = false,
            showSubAgentBar = true,
            activeSubAgentToolIds = emptySet(),
        )
        assertEquals(listOf("t1"), overlay.map { it.id })
    }

    @Test
    fun batchSubCardMatchesByParentToolId() {
        val overlay = floatingOverlayBlocks(
            assistant("m", tool("t9#sub-2", name = "shell_execute")),
            foldAiProcess = false,
            showSubAgentBar = true,
            activeSubAgentToolIds = setOf("t9"),
        )
        assertEquals(emptyList<AssistantBlock>(), overlay)
    }

    @Test
    fun noReplyMeansNoOverlay() {
        assertEquals(
            emptyList<AssistantBlock>(),
            floatingOverlayBlocks(null, false, false, emptySet()),
        )
    }

    @Test
    fun elapsedTicksWhileInFlightAndFreezesOnceSettled() {
        val running = tool("t", ToolBlockStatus.RUNNING, startTimeMs = 1_000L)
        assertEquals("3s", toolActivityElapsedText(running, nowMs = 3_500L))
        val done = tool("t", ToolBlockStatus.SUCCESS, durationMs = 12_000L)
        assertEquals("12s", toolActivityElapsedText(done, nowMs = 99_000L))
        val noClock = tool("t", ToolBlockStatus.RUNNING)
        assertNull(toolActivityElapsedText(noClock, nowMs = 5_000L))
    }
}
