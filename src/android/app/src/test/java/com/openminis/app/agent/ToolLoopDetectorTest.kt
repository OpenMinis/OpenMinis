package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for ToolLoopDetector. Each strategy gets its own isolated
 * detector instance so warning-bucket / history state doesn't bleed across
 * test methods.
 *
 * Notes on `expectedRecordWarnings`: the detector emits warnings via
 * `record()` once per `warningThreshold` call (i.e. at counts 10, 20, 30…).
 * Tests assert against that bucketed cadence, not on every call past
 * threshold, to mirror the real LLM-facing behavior.
 */
class ToolLoopDetectorTest {

    // ─── 1. generic_repeat ────────────────────────────────────────────────────

    @Test
    fun `generic_repeat does not warn under threshold`() {
        val detector = ToolLoopDetector()
        repeat(9) { i ->
            val pre = detector.check("file_read", mapOf("path" to "/foo"))
            assertEquals("call ${i + 1} should not be flagged pre-execution", Level.NONE, pre.level)
            val post = detector.record("file_read", mapOf("path" to "/foo"),
                result = "ok-$i", errorMessage = null)
            assertEquals("call ${i + 1} should not warn", Level.NONE, post.level)
        }
    }

    @Test
    fun `generic_repeat warns at threshold and uses bucketed throttle`() {
        val detector = ToolLoopDetector()
        var warnings = 0
        repeat(20) {
            detector.check("file_read", mapOf("path" to "/foo"))
            val post = detector.record("file_read", mapOf("path" to "/foo"),
                result = "ok", errorMessage = null)
            if (post.level == Level.WARNING) warnings++
        }
        // Expect warnings exactly at bucket boundaries: count 10, count 20 → 2.
        assertEquals("should emit exactly two warnings (buckets 10 and 20)", 2, warnings)
    }

    @Test
    fun `generic_repeat ignores poll tools`() {
        val detector = ToolLoopDetector()
        repeat(15) {
            val post = detector.record("check_agent", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
            // Poll tool should follow the poll path; record() emits at >= warning
            // threshold, but the warning text must reference polling, not generic.
            if (post.level == Level.WARNING) {
                assertTrue("poll-path warning expected, got: ${post.message}",
                    post.message?.contains("Stop polling") == true)
            }
        }
    }

    @Test
    fun `generic_repeat is order-stable across param key reordering`() {
        // Two different Map iteration orders, same logical params → same hash,
        // so 5 of one + 5 of the other should land in the same bucket and warn.
        val a: Map<String, Any?> = linkedMapOf("a" to 1, "b" to 2)
        val b: Map<String, Any?> = linkedMapOf("b" to 2, "a" to 1)
        var warnings = 0
        val detector = ToolLoopDetector()
        repeat(5) {
            val r = detector.record("custom_tool", a, "ok", null)
            if (r.level == Level.WARNING) warnings++
        }
        repeat(5) {
            val r = detector.record("custom_tool", b, "ok", null)
            if (r.level == Level.WARNING) warnings++
        }
        assertEquals("identical-hash params should yield exactly one warning at count 10",
            1, warnings)
    }

    @Test
    fun `argsHash ignores tool_title counter suffix`() {
        // Repro of the DeepSeek V4 Flash live failure: every call carried a
        // `tool_title` like "Read /etc/hostname #1", "#2", … with the model's
        // own counter. Without filtering tool_title, each call's argsHash
        // differed, so 30 logically-identical reads produced zero warnings or
        // blocks. The fix excludes tool_title from argsHashFor.
        val detector = ToolLoopDetector()
        repeat(10) { i ->
            detector.record(
                toolName = "file_read",
                params = mapOf(
                    "path" to "/etc/hostname",
                    "tool_title" to "Read /etc/hostname #${i + 1}",
                ),
                result = "localhost",
                errorMessage = null,
            )
        }
        val pre = detector.check(
            "file_read",
            mapOf(
                "path" to "/etc/hostname",
                "tool_title" to "Read /etc/hostname #11",
            ),
        )
        assertTrue(
            "tool_title variation must NOT defeat repeat detection (got ${pre.level})",
            pre.level != Level.NONE,
        )
    }

    // ─── 2. unknown_tool_repeat ──────────────────────────────────────────────

    @Test
    fun `unknown_tool_repeat blocks at threshold`() {
        val detector = ToolLoopDetector()
        // 9 unknown-tool records: under threshold, the 10th check() must allow.
        repeat(9) {
            detector.record("foobar", emptyMap(),
                result = null, errorMessage = "Error: unknown tool: foobar")
        }
        val preUnder = detector.check("foobar", emptyMap())
        assertEquals("9 unknown records → still allowed", Level.NONE, preUnder.level)
        // Add the 10th unknown-tool record; next check() must block.
        detector.record("foobar", emptyMap(),
            result = null, errorMessage = "Error: unknown tool: foobar")
        val pre = detector.check("foobar", emptyMap())
        assertEquals(Level.CRITICAL, pre.level)
        assertTrue("message should mention foobar",
            pre.message?.contains("foobar") == true)
        assertTrue("message should mention block",
            pre.message?.contains("LOOP BLOCKED") == true)
    }

    @Test
    fun `unknown_tool_repeat resets on different tool name`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("foobar", emptyMap(),
                result = null, errorMessage = "unknown tool: foobar")
        }
        // 10 streaks of foobar, then one of bazquux → unknown-streak for foobar
        // is broken (the new tail entry has a different unknownToolName), so
        // check() must NOT escalate to CRITICAL via unknown_tool_repeat. A
        // generic_repeat warning may still fire from the prior identical args.
        detector.record("bazquux", emptyMap(),
            result = null, errorMessage = "unknown tool: bazquux")
        val preFoobar = detector.check("foobar", emptyMap())
        assertTrue("foobar streak interrupted; must not be CRITICAL",
            preFoobar.level != Level.CRITICAL)
    }

    @Test
    fun `unknown_tool_repeat parses tool not found wording`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("ghost", emptyMap(),
                result = null, errorMessage = "tool 'ghost' not found in registry")
        }
        val pre = detector.check("ghost", emptyMap())
        assertEquals(Level.CRITICAL, pre.level)
    }

    @Test
    fun `unknown_tool_repeat does not match unrelated error`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("file_read", mapOf("path" to "/x"),
                result = null, errorMessage = "Error: file not found: /x")
        }
        val pre = detector.check("file_read", mapOf("path" to "/x"))
        // generic_repeat would have warned, but no CRITICAL for unknown_tool here.
        assertNull("unrelated error must not be parsed as unknown-tool",
            pre.message?.takeIf { it.contains("unavailable tool") })
    }

    // ─── 3. known_poll_no_progress ───────────────────────────────────────────

    @Test
    fun `known_poll_no_progress warns at threshold`() {
        val detector = ToolLoopDetector()
        repeat(9) {
            detector.record("check_agent", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
        }
        val preBefore = detector.check("check_agent", mapOf("id" to "job-1"))
        assertEquals("9 prior records, streak=9, no warn yet",
            Level.NONE, preBefore.level)
        val post = detector.record("check_agent", mapOf("id" to "job-1"),
            result = "running", errorMessage = null)
        assertEquals("10th record triggers WARNING", Level.WARNING, post.level)
        assertTrue(post.message?.contains("Stop polling") == true)
    }

    @Test
    fun `known_poll_no_progress blocks at critical threshold`() {
        val detector = ToolLoopDetector()
        repeat(20) {
            detector.record("check_agent", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
        }
        // After 20 records the streak is 20; next check must block as CRITICAL.
        val pre = detector.check("check_agent", mapOf("id" to "job-1"))
        assertEquals(Level.CRITICAL, pre.level)
        assertTrue(pre.message?.contains("Session execution blocked") == true)
    }

    @Test
    fun `known_poll_no_progress resets when result changes`() {
        val detector = ToolLoopDetector()
        repeat(9) {
            detector.record("check_agent", mapOf("id" to "job-1"),
                result = "running", errorMessage = null)
        }
        // Result changed → progress observed; streak should break.
        detector.record("check_agent", mapOf("id" to "job-1"),
            result = "completed", errorMessage = null)
        // Now even with the same args, no-progress streak = 1 (just the latest).
        val pre = detector.check("check_agent", mapOf("id" to "job-1"))
        assertEquals("progress resets streak", Level.NONE, pre.level)
    }

    @Test
    fun `known_poll_no_progress recognizes check_agent op poll`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("check_agent",
                mapOf("op" to "status", "id" to "p1"),
                result = "still_running", errorMessage = null)
        }
        // Streak now 10 → next check on the same poll args yields a WARNING.
        val pre = detector.check("check_agent", mapOf("op" to "status", "id" to "p1"))
        assertEquals(Level.WARNING, pre.level)
    }

    /**
     * [T-poll-tool-drift] `op=clear` mutates the registry instead of polling it,
     * so it must stay on the generic path. The detector previously keyed on tool
     * names that do not exist here (`command_status`, `process`), which meant NO
     * call ever took the poll branch at all.
     */
    @Test
    fun `check_agent op clear is not treated as a poll`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("check_agent",
                mapOf("op" to "clear"), result = "cleared", errorMessage = null)
        }
        val post = detector.check("check_agent", mapOf("op" to "clear"))
        assertTrue(
            "clear must not use the poll wording: ${post.message}",
            post.message?.contains("Stop polling") != true,
        )
    }

    // ─── 4. global_circuit_breaker ───────────────────────────────────────────

    @Test
    fun `global_circuit_breaker blocks any tool at 30`() {
        val detector = ToolLoopDetector()
        // Use a non-poll tool to make sure the breaker — not the poll path —
        // is the one tripping. With identical args+results it'd otherwise
        // trip generic_repeat warnings only (warnings != block).
        repeat(30) {
            detector.record("custom_widget", mapOf("k" to "v"),
                result = "no-progress", errorMessage = null)
        }
        val pre = detector.check("custom_widget", mapOf("k" to "v"))
        assertEquals(Level.CRITICAL, pre.level)
        assertTrue("breaker message expected, got: ${pre.message}",
            pre.message?.contains("global circuit breaker") == true)
    }

    @Test
    fun `global_circuit_breaker does not trip when results vary`() {
        val detector = ToolLoopDetector()
        repeat(30) { i ->
            detector.record("custom_widget", mapOf("k" to "v"),
                result = "result-$i", errorMessage = null)
        }
        val pre = detector.check("custom_widget", mapOf("k" to "v"))
        // Each result distinct → no-progress streak = 1 → no breaker.
        assertTrue("breaker must not fire on varying results",
            pre.level != Level.CRITICAL ||
                pre.message?.contains("global circuit breaker") != true)
    }

    // ─── config / reset / hygiene ────────────────────────────────────────────

    @Test
    fun `reset clears history and warning buckets`() {
        val detector = ToolLoopDetector()
        repeat(10) {
            detector.record("file_read", mapOf("path" to "/foo"),
                result = "ok", errorMessage = null)
        }
        detector.reset()
        assertEquals(0, detector.historySnapshot().size)
        // After reset, 10 more identical calls should warn anew (buckets cleared).
        var warnings = 0
        repeat(10) {
            val r = detector.record("file_read", mapOf("path" to "/foo"),
                result = "ok", errorMessage = null)
            if (r.level == Level.WARNING) warnings++
        }
        assertEquals("first bucket should warn once after reset", 1, warnings)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config rejects warning gte critical`() {
        ToolLoopConfig(warningThreshold = 10, criticalThreshold = 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config rejects critical gte breaker`() {
        ToolLoopConfig(criticalThreshold = 30, globalCircuitBreakerThreshold = 30)
    }

}
