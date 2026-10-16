package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-history-paging-stream-gate] Source-drift guard: history paging must
 * stay available while a turn is streaming.
 *
 * The user-visible bug: during a long autonomous run (the only time anyone
 * scrolls up to re-read history) the "load older" pill was visible and
 * tappable but every tap silently no-op'd — `loadOlderMessages` /
 * `loadOlderPage` opened with `if (_isStreaming.value) return`. The gate was
 * defensive code from the pagination rewrite with no documented race behind
 * it, and it guarded nothing: the old edge this page prepends to never
 * overlaps the live turn's new edge, the fetch+splice holds the ledger's
 * mutationMutex, and every stream-side `_messages` publish runs on the main
 * dispatcher while this splice re-reads `_messages` after its last
 * suspension with no suspension between that read and the publish — so
 * main-thread serialization closes the lost-update window.
 *
 * If these assertions fail, someone reintroduced a streaming gate (or
 * dropped the single-flight / ledger serialization the gate wrongly stood
 * in for). Fix the code, not the test.
 */
class ChatHistoryPagingStreamGateTest {

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("signature not found: $signature", start >= 0)
        val braceOpen = source.indexOf('{', start)
        assertTrue("function body not found after: $signature", braceOpen > start)
        var depth = 0
        var i = braceOpen
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(braceOpen, i + 1)
                }
            }
            i++
        }
        assertTrue("unbalanced braces after: $signature", false)
        return ""
    }

    @Test
    fun `loadOlderMessages does not gate on streaming`() {
        val source = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val body = functionBody(source, "fun loadOlderMessages()")

        assertFalse(
            "loadOlderMessages must not refuse to run while a turn streams — " +
                "the pill is visible mid-run and a silent no-op reads as a dead button",
            "_isStreaming" in body,
        )
        assertTrue(
            "single-flight claim must stay: concurrent taps are collapsed, " +
                "not refused outright",
            "claimOlderLoad()" in body,
        )
    }

    @Test
    fun `loadOlderPage does not gate on streaming`() {
        val source = ProductionSources.read("ui/chat/ChatViewModel.kt")
        val body = functionBody(source, "fun loadOlderPage(): Boolean")

        assertFalse(
            "loadOlderPage must not return early while a turn streams — " +
                "the up-button walk and jump-to-message fallback page through it",
            "_isStreaming" in body,
        )
        assertTrue(
            "single-flight claim must stay: a call while a page is loading is a no-op",
            "claimOlderLoad()" in body,
        )
        assertTrue(
            "the fetch+splice must keep the ledger's mutation mutex so a " +
                "concurrent tail-attach drain cannot interleave its own splice",
            "mutationMutex.withLock" in body,
        )
    }
}
