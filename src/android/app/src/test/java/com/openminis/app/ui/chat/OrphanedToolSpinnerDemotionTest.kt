package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-orphaned-running-tool-spin] The display-side demotion of orphaned
 * tool spinners.
 *
 * `OrphanedRunningToolGateTest` covers the policy itself; this covers the piece
 * that actually runs against a restored message list. The failure it guards is
 * expensive and invisible in a normal test run: a block persisted as RUNNING
 * whose run no longer exists shimmers forever, which on a 144-message session
 * measured 85-130% of a CPU core while the app sat idle.
 */
class OrphanedToolSpinnerDemotionTest {

    private fun block(id: String, status: ToolBlockStatus?) =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = status)

    private fun messages(vararg blocks: AssistantBlock) = listOf(
        ChatMessage(id = "m1", role = "assistant", content = "", toolBlocks = blocks.toList()),
    )

    @Test
    fun coldOpenDemotesInFlightStatusesToTimeout() {
        val restored = messages(
            block("a", ToolBlockStatus.RUNNING),
            block("b", ToolBlockStatus.STREAMING),
            block("c", ToolBlockStatus.PENDING),
        ).demoteOrphanedToolSpinners(isLiveRun = false)

        assertEquals(
            listOf(ToolBlockStatus.TIMEOUT, ToolBlockStatus.TIMEOUT, ToolBlockStatus.TIMEOUT),
            restored.single().toolBlocks.map { it.toolStatus },
        )
    }

    @Test
    fun liveRunKeepsSpinners() {
        val restored = messages(block("a", ToolBlockStatus.RUNNING))
            .demoteOrphanedToolSpinners(isLiveRun = true)
        assertEquals(ToolBlockStatus.RUNNING, restored.single().toolBlocks.single().toolStatus)
    }

    @Test
    fun liveJobKeepsItsSpinnerEvenWhenTheSessionLooksIdle() {
        val restored = messages(block("job-1", ToolBlockStatus.RUNNING))
            .demoteOrphanedToolSpinners(isLiveRun = false) { it == "job-1" }
        assertEquals(ToolBlockStatus.RUNNING, restored.single().toolBlocks.single().toolStatus)
    }

    @Test
    fun terminalStatusesAreLeftAlone() {
        val restored = messages(
            block("a", ToolBlockStatus.SUCCESS),
            block("b", ToolBlockStatus.FAILED),
            block("c", ToolBlockStatus.CANCELLED),
            block("d", null),
        ).demoteOrphanedToolSpinners(isLiveRun = false)

        assertEquals(
            listOf(
                ToolBlockStatus.SUCCESS,
                ToolBlockStatus.FAILED,
                ToolBlockStatus.CANCELLED,
                null,
            ),
            restored.single().toolBlocks.map { it.toolStatus },
        )
    }

    @Test
    fun messagesWithoutToolBlocksArePassedThrough() {
        val plain = listOf(ChatMessage(id = "m", role = "user", content = "hi"))
        val restored = plain.demoteOrphanedToolSpinners(isLiveRun = false)
        assertEquals(plain, restored)
    }
}
