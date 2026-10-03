package com.openminis.app.agent

import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-concurrent-tools] ToolLoopDetector under concurrent callers.
 *
 * Regression test for a device failure, not a hypothetical: a sub agent on a
 * Pixel 6 died with "Attempt to invoke virtual method 'String
 * ToolCallRecord.getToolName()' on a null object reference" and the whole run
 * was lost. The detector's class doc said "serialize calls through the agent
 * loop's existing single-threaded dispatch" — and concurrent tool dispatch had
 * just removed that guarantee. Its ArrayDeque grew on one thread while another
 * scanned it, exposing a transiently-null slot.
 */
class ToolLoopDetectorConcurrencyTest {

    @Test
    fun `concurrent check and record never expose a null record`() = runBlocking {
        repeat(12) {
            val d = ToolLoopDetector()
            coroutineScope {
                (0 until 24).map { i ->
                    async(Dispatchers.Default) {
                        repeat(40) { n ->
                            val params = mapOf("q" to "v$i-$n")
                            d.check("shell_execute", params)
                            d.record("shell_execute", params, result = "ok$n", toolCallId = "c$i-$n")
                            d.historySnapshot().forEach { r ->
                                assertTrue("a record must never be null-ish", r.toolName.isNotEmpty())
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
