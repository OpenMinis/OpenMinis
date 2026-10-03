package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases of a model switch that lands while a turn is running.
 *
 * Guards: a43ad4134 (T-android-switch-model-ghost-retry), 8aa6b373b
 * (T-android-switch-model-hang), aa3aab6bb (T-android-switch-model-seam-cleanup)
 * and ef4a06e4d (T-android-switch-model-let-stream-finish). Extends
 * [SwitchModelGhostRetryTest], which pins the seam's teardown scope and the
 * selector wiring; this file covers what that one does not:
 *
 *  1. The "bound to the abandoned model" predicate reads the retry-ladder
 *     counters, but `_autoRetryAttempt` stays non-zero for the WHOLE retried
 *     attempt — it is only cleared when that stream completes. A turn that
 *     needed one retry and is now streaming normally therefore still reads as
 *     a ghost retry, and a model switch cancels it into "tap Resume": the
 *     ef4a06e4d regression, on every turn that retried once. iOS clears the
 *     counter the moment the stream opens (streamWithAutoRetry).   (BUG today)
 *  2. `selectEntry` swaps the class-level `currentModel` immediately, while the
 *     running turn keeps its turn-local provider. The turn's thinking gate
 *     reads the class-level model, so the rest of the turn sends the NEW
 *     model's reasoning decision to the OLD model.                 (BUG today)
 *  3. Documented parity decision: Android applies a mid-turn switch at the
 *     next SEND (the turn-local provider is declared outside the turn loop);
 *     iOS (86a045284) applies it at the next REQUEST. Pinned so a port of the
 *     iOS behaviour updates this test deliberately.
 */
class SwitchModelMidTurnEdgeTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: $path", f.exists())
        return f.readText()
    }

    private val vmSrc by lazy { src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }

    private val agentLoop by lazy {
        vmSrc.substringAfter("private suspend fun runAgentLoop(")
            .substringBefore("\n    private suspend fun ")
            .substringBefore("\n    private fun ")
            .substringBefore("\n    fun ")
    }

    private fun stripComments(s: String): String =
        s.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    // ---- 1. ladder counter vs. a healthy retried stream --------------------

    /** Timeline model of the ladder counter. `resetOnFirstChunk` = the fix. */
    private fun counterWhileRetriedStreamIsHealthy(resetOnFirstChunk: Boolean): Int {
        var attempt = 0
        attempt = 1                     // transient error → ladder step 1
        /* countdown ... then the retried stream opens and a chunk arrives */
        if (resetOnFirstChunk) attempt = 0
        return attempt                  // the value the seam sees mid-stream
    }

    @Test
    fun `model - a healthy retried stream must not look like a ghost retry`() {
        assertEquals(
            "shipping: counter still 1 while the retried attempt streams → a switch cancels it",
            1, counterWhileRetriedStreamIsHealthy(resetOnFirstChunk = false),
        )
        assertEquals(0, counterWhileRetriedStreamIsHealthy(resetOnFirstChunk = true))
    }

    @Test
    fun `the ladder counter is cleared once the retried attempt starts producing output`() {
        assertTrue("runAgentLoop not found", agentLoop.length > 1000)
        val firstChunkBlock = agentLoop
            .substringAfter("if (firstChunkSeen.compareAndSet(false, true)) {", missingDelimiterValue = "")
            .substringBefore("when (chunk) {")
        assertTrue("first-chunk hook not found", firstChunkBlock.isNotEmpty())
        assertTrue(
            "isBoundToAbandonedModel() reads _autoRetryAttempt, which today is only reset when the " +
                "retried stream COMPLETES. A model switch during a healthy retried stream is then " +
                "treated as a ghost retry and cancels the turn. Reset _autoRetryAttempt / " +
                "_autoRetryCountdown on the first chunk (iOS resets on stream open).",
            stripComments(firstChunkBlock).contains("_autoRetryAttempt.value = 0"),
        )
    }

    // ---- 2. thinking gate follows the provider serving the turn ------------

    @Test
    fun `the in-flight turn gates thinking on its own provider, not the freshly picked model`() {
        // The argument line right after the tools argument of the turn's stream call.
        val call = agentLoop
            .substringAfter("tools = if (helperWrapUpInjected) emptyList() else agentTools,", "")
            .substringBefore(".collect { chunk ->")
        assertTrue("stream call not found", call.contains("thinkingLevel ="))
        assertFalse(
            "runAgentLoop's thinking gate reads the class-level currentModel " +
                "(currentModelSupportsReasoning), which selectEntry swaps immediately, while the request " +
                "goes to the turn-local provider. Gate on currentProvider.model.supportsReasoning instead.",
            call.contains("currentModelSupportsReasoning"),
        )
    }

    // ---- 3. documented parity decision -------------------------------------

    @Test
    fun `a mid-turn switch applies at the next send (turn-local provider outlives every round)`() {
        val code = stripComments(agentLoop)
        val localProvider = code.indexOf("var currentProvider = provider")
        val turnLoop = code.indexOf("for (turn in 0 until turnCap)")
        assertTrue("turn-local provider not found", localProvider >= 0)
        assertTrue("turn loop not found", turnLoop >= 0)
        assertTrue(
            "Android applies a user switch at the next SEND (ef4a06e4d). iOS applies it at the next " +
                "REQUEST (86a045284, pendingModelSwitch). If you are porting that, update this test.",
            localProvider < turnLoop,
        )
    }
}
