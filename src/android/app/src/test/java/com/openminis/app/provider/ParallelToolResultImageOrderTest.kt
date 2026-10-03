package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-parallel-toolresult-image-split] The `tool` replies answering one
 * assistant's tool_calls must reach the wire CONTIGUOUSLY, with image carriers
 * appended after the whole run.
 *
 * The reported bug: two parallel `read_image` calls made DeepSeek reject the
 * request with `[400] No tool output found for tool call call_…`. The provider
 * emitted, per result, a `tool` message immediately followed by that result's
 * image-carrier `user` message — so with two calls the carrier landed BETWEEN
 * the two `tool` replies and ended the run, leaving the second call unanswered.
 *
 * With a single image the carrier lands after the only `tool` message and
 * nothing is split, which is exactly why the bug needed TWO parallel calls to
 * appear and why single-image chats always worked.
 *
 * Strictness is the only thing that varied between providers — GLM accepted the
 * same malformed body, DeepSeek did not. The body was wrong in both cases, so
 * this pins the ordering rather than any one provider's tolerance.
 *
 * Mirrors the provider's emission order rather than calling into it: the real
 * loop needs an OkHttp stack and credentials, while the defect is entirely in
 * the sequence the loop produces.
 */
class ParallelToolResultImageOrderTest {

    private fun result(id: String, withImage: Boolean) = AgentContentPart.ToolResult(
        id = id,
        name = "read_image",
        content = "ok",
        imageData = if (withImage) byteArrayOf(1, 2, 3) else null,
        imageMimeType = if (withImage) "image/png" else null,
    )

    /** The FIXED emission order: every `tool`, then every carrier. */
    private fun emit(results: List<AgentContentPart.ToolResult>): List<String> {
        val out = mutableListOf<String>()
        val carriers = mutableListOf<String>()
        for (tr in results) {
            out.add("tool:${tr.id}")
            if (tr.imageData != null) carriers.add("user-image:${tr.id}")
        }
        out.addAll(carriers)
        return out
    }

    /** The ORIGINAL order, kept so the regression is expressible. */
    private fun emitInterleaved(results: List<AgentContentPart.ToolResult>): List<String> {
        val out = mutableListOf<String>()
        for (tr in results) {
            out.add("tool:${tr.id}")
            if (tr.imageData != null) out.add("user-image:${tr.id}")
        }
        return out
    }

    /** True when no non-tool message sits between the first and last `tool`. */
    private fun toolRunIsContiguous(seq: List<String>): Boolean {
        val first = seq.indexOfFirst { it.startsWith("tool:") }
        val last = seq.indexOfLast { it.startsWith("tool:") }
        if (first < 0) return true
        return (first..last).all { seq[it].startsWith("tool:") }
    }

    @Test
    fun `two parallel image results keep the tool run contiguous`() {
        val seq = emit(listOf(result("call_01", true), result("call_02", true)))
        assertEquals(
            listOf("tool:call_01", "tool:call_02", "user-image:call_01", "user-image:call_02"),
            seq,
        )
        assertTrue("both tool replies must be adjacent", toolRunIsContiguous(seq))
    }

    @Test
    fun `the old interleaved order split the run - this is the reported 400`() {
        // Proves the test is not vacuous: the pre-fix order fails the same
        // predicate the fix satisfies, and fails it exactly at two calls.
        val seq = emitInterleaved(listOf(result("call_01", true), result("call_02", true)))
        assertEquals(
            listOf("tool:call_01", "user-image:call_01", "tool:call_02", "user-image:call_02"),
            seq,
        )
        assertTrue(
            "the carrier between the two tool replies is what orphaned call_02",
            !toolRunIsContiguous(seq),
        )
    }

    @Test
    fun `a single image result was never split - why the bug needed parallelism`() {
        // Both orders agree here. This is the case that always worked, and the
        // reason the defect survived: it is invisible until two calls run.
        val one = listOf(result("call_01", true))
        assertEquals(emit(one), emitInterleaved(one))
        assertTrue(toolRunIsContiguous(emit(one)))
    }

    @Test
    fun `results without images are unaffected`() {
        val seq = emit(listOf(result("call_01", false), result("call_02", false)))
        assertEquals(listOf("tool:call_01", "tool:call_02"), seq)
        assertTrue(toolRunIsContiguous(seq))
    }

    @Test
    fun `a mixed batch still emits every tool reply before any carrier`() {
        // The image may be on the FIRST of several calls — the shape most
        // likely to split a run, since every later tool reply follows the
        // carrier under the old order.
        val seq = emit(
            listOf(result("call_01", true), result("call_02", false), result("call_03", true)),
        )
        assertEquals(
            listOf(
                "tool:call_01", "tool:call_02", "tool:call_03",
                "user-image:call_01", "user-image:call_03",
            ),
            seq,
        )
        assertTrue(toolRunIsContiguous(seq))
    }

    @Test
    fun `carriers stay in call order so captions match their images`() {
        // Grouping the carriers is only safe if their order is preserved: the
        // caption names the tool_call_id, and a reordered batch would attach
        // the wrong id to the wrong pixels.
        val seq = emit(listOf(result("call_A", true), result("call_B", true), result("call_C", true)))
        assertEquals(
            listOf("user-image:call_A", "user-image:call_B", "user-image:call_C"),
            seq.filter { it.startsWith("user-image:") },
        )
    }
}
