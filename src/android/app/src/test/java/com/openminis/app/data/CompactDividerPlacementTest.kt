package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-inloop-compact-divider-order / GH#235] Pins the message ORDER
 * around an in-loop auto-compaction.
 *
 * The bug: `runAgentLoop` appends its assistant placeholder ONCE before the
 * turn loop and reuses it across iterations. In-loop compaction tail-appends
 * the "N messages compacted" divider, which therefore landed AFTER that live
 * bubble — and the loop then `continue`d and kept appending thinking blocks,
 * tool calls and final text into it. Everything produced after the compaction
 * rendered ABOVE the divider, reading as though fresh output had been filed
 * into already-compacted history.
 *
 * The fix (plan B, matching iOS e65540b69): seal the bubble the run was
 * writing into, drop it if it never produced anything, and continue in a FRESH
 * bubble appended after the divider.
 *
 * Following CompactToolPairingTest / InLoopContextPolicyTest: the production
 * code lives on ChatViewModel, which needs a Context, a DB and a provider to
 * construct, so this mirrors the ORDERING/SEALING RULES rather than the
 * coroutine plumbing. [compactedContinuation] is kept behaviourally equivalent
 * to the COMPACTED branch of runAgentLoop; if one changes without the other,
 * `post-compaction output lands below the divider` is the test that fails.
 */
class CompactDividerPlacementTest {

    /** Minimal stand-in for ChatMessage — only the fields ordering reads. */
    private data class Row(
        val id: String,
        val role: String,
        val content: String = "",
        val blocks: List<String> = emptyList(),
        val isStreaming: Boolean = false,
    )

    /**
     * Mirrors the COMPACTED branch: seal the active bubble (flush + stop
     * streaming), drop it when empty, append the divider, then append a fresh
     * placeholder that subsequent writes target.
     */
    private fun compactedContinuation(
        current: List<Row>,
        sealedId: String,
        accumulatedText: String,
        accumulatedBlocks: List<String>,
        freshId: String,
    ): List<Row> {
        // Seal: flush what the bubble accumulated, clear streaming.
        var out = current.map { row ->
            if (row.id != sealedId) row
            else row.copy(
                content = accumulatedText,
                blocks = accumulatedBlocks,
                isStreaming = false,
            )
        }
        // Drop the sealed bubble if it never produced anything.
        val sealed = out.firstOrNull { it.id == sealedId }
        if (sealed != null && sealed.content.isEmpty() && sealed.blocks.isEmpty()) {
            out = out.filterNot { it.id == sealedId }
        }
        // compactAll() tail-appends the divider at this point.
        out = out + Row(id = "sysinfo_1", role = "system")
        // Fresh bubble for the continuation.
        return out + Row(id = freshId, role = "assistant", isStreaming = true)
    }

    /**
     * The reported scenario: compaction fires mid-turn after the bubble has
     * already produced output. That output stays ABOVE the divider (it really
     * is pre-compaction history) and the continuation goes BELOW it.
     */
    @Test
    fun `post-compaction output lands below the divider`() {
        val before = listOf(
            Row("u1", "user"),
            Row("a1", "assistant", content = "older turn"),
            Row("u2", "user"),
            Row("live", "assistant", isStreaming = true),
        )

        val after = compactedContinuation(
            current = before,
            sealedId = "live",
            accumulatedText = "thinking so far",
            accumulatedBlocks = listOf("tool:shell_execute"),
            freshId = "fresh",
        )

        val dividerIdx = after.indexOfFirst { it.role == "system" }
        val sealedIdx = after.indexOfFirst { it.id == "live" }
        val freshIdx = after.indexOfFirst { it.id == "fresh" }

        assertTrue("sealed bubble stays above the divider", sealedIdx < dividerIdx)
        assertTrue("continuation bubble sits below the divider", freshIdx > dividerIdx)
        // The fresh bubble must be last so everything the loop appends next
        // renders below the line.
        assertEquals("fresh", after.last().id)
    }

    /** The sealed bubble must stop streaming, or it keeps absorbing writes. */
    @Test
    fun `sealed bubble stops streaming and keeps its content`() {
        val before = listOf(Row("live", "assistant", isStreaming = true))

        val after = compactedContinuation(
            current = before,
            sealedId = "live",
            accumulatedText = "partial answer",
            accumulatedBlocks = emptyList(),
            freshId = "fresh",
        )

        val sealed = after.first { it.id == "live" }
        assertFalse("sealed bubble must not still be streaming", sealed.isStreaming)
        assertEquals("partial answer", sealed.content)
        assertTrue("only the fresh bubble streams", after.first { it.id == "fresh" }.isStreaming)
    }

    /**
     * When compaction fires before the bubble produced anything, keeping it
     * would strand an empty grey row above the divider.
     */
    @Test
    fun `an empty sealed bubble is dropped`() {
        val before = listOf(
            Row("u1", "user"),
            Row("live", "assistant", isStreaming = true),
        )

        val after = compactedContinuation(
            current = before,
            sealedId = "live",
            accumulatedText = "",
            accumulatedBlocks = emptyList(),
            freshId = "fresh",
        )

        assertFalse("empty sealed bubble must be removed", after.any { it.id == "live" })
        assertEquals(listOf("u1", "sysinfo_1", "fresh"), after.map { it.id })
    }

    /** A bubble that produced only tool blocks (no text) is NOT empty. */
    @Test
    fun `a tool-only sealed bubble is kept`() {
        val before = listOf(Row("live", "assistant", isStreaming = true))

        val after = compactedContinuation(
            current = before,
            sealedId = "live",
            accumulatedText = "",
            accumulatedBlocks = listOf("tool:file_read"),
            freshId = "fresh",
        )

        assertTrue("tool-only bubble must survive", after.any { it.id == "live" })
        val dividerIdx = after.indexOfFirst { it.role == "system" }
        assertTrue(after.indexOfFirst { it.id == "live" } < dividerIdx)
    }

    /**
     * Two compactions in one run must not interleave: each seals its own
     * bubble and the ordering stays strictly chronological.
     */
    @Test
    fun `back-to-back compactions keep chronological order`() {
        var rows = listOf(
            Row("u1", "user"),
            Row("live1", "assistant", isStreaming = true),
        )

        rows = compactedContinuation(rows, "live1", "first chunk", emptyList(), "live2")
        rows = compactedContinuation(rows, "live2", "second chunk", emptyList(), "live3")

        assertEquals(
            listOf("u1", "live1", "sysinfo_1", "live2", "sysinfo_1", "live3"),
            rows.map { it.id },
        )
        assertEquals("live3", rows.last().id)
        assertTrue("only the newest bubble streams", rows.first { it.id == "live3" }.isStreaming)
        assertFalse(rows.first { it.id == "live2" }.isStreaming)
    }
}
