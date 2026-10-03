package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-agent-inner-tool-front] The parent's floating preview must show
 * the tool a SUB AGENT is running, not just its name.
 *
 * Before this, the delegate branch went unconditionally to HelperThumbContent,
 * which renders `HelperPhase.Running.tool` — a `String?` carrying the tool NAME
 * only, because HelperRunner.progressJson mirrors just the name. So a child
 * running shell_execute showed the text "Agent · Shell" while iOS showed the
 * child's actual terminal output (ToolLiveSheet.swift:2720).
 *
 * The Compose rendering itself needs an instrumented test; what is checkable
 * here is the wiring that decides WHAT gets rendered, plus the two predicates
 * that were easy to get wrong.
 */
class AgentInnerToolFrontTest {

    private val src by lazy {
        File("src/main/java/com/openminis/app/ui/chat/ChatComposerWidgets.kt").readText()
    }

    @Test
    fun `the delegate branch substitutes the child's live tool block`() {
        val branch = src.substringAfter("HelperRunner.TOOL_NAME -> {")
            .substringBefore("else -> {")
        assertTrue(
            "must look up the child's live tool",
            branch.contains("rememberChildLiveToolBlock(childId)"),
        )
        assertTrue(
            "must recurse into the generic renderer so every tool type is covered",
            branch.contains("ToolPreviewThumbnail("),
        )
        assertTrue(
            "must fall back to the agent card when the child is between tools",
            branch.contains("HelperThumbContent(block)"),
        )
    }

    @Test
    fun `the recursion is depth-capped at one level`() {
        // A sub agent can itself delegate. iOS is bounded structurally
        // (liveChildToolBlock resolves only the DIRECT child); Android recurses
        // through the same composable, so the cap has to be explicit.
        val branch = src.substringAfter("HelperRunner.TOOL_NAME -> {")
            .substringBefore("else -> {")
        assertTrue("must gate on depth", branch.contains("punchThroughDepth < 1"))
        assertTrue("must increment on recurse", branch.contains("punchThroughDepth = punchThroughDepth + 1"))
        assertTrue("the parameter must default to 0", src.contains("punchThroughDepth: Int = 0"))
    }

    @Test
    fun `the glow survives the substitution`() {
        // The trap: after substitution `block` is the CHILD's shell_execute,
        // whose toolName is not the agent tool — the original predicate would
        // go false exactly when the ring is most warranted.
        val decl = src.substringAfter("val subAgentLive =").substringBefore("val subAgentGlow")
        assertTrue(
            "a punched-through tile must count as a sub agent tile",
            decl.contains("punchThroughDepth > 0"),
        )
        assertTrue(
            "the original agent-card case must still qualify",
            decl.contains("HelperRunner.TOOL_NAME"),
        )
    }

    @Test
    fun `the observer never constructs a view model`() {
        // Constructing here would race the child's own construction and could
        // resurrect a finished session.
        val fn = src.substringAfter("fun rememberChildLiveToolBlock")
            .substringBefore("@Composable\nprivate fun ToolPreviewThumbnail")
        assertTrue("must use the non-constructing lookup", fn.contains("ChatViewModelStore.existing("))
        assertFalse("must not build one", fn.contains("ViewModelProvider"))
        assertFalse("must not use ownerFor", fn.contains("ownerFor"))
    }

    @Test
    fun `the observer merges the streaming side-channel`() {
        // While the child's turn is in flight its tool blocks live ONLY in
        // streamingById; reading `messages` alone shows nothing until the turn
        // ends. Same trap documented on ChatViewModel.childLiveMessages.
        val fn = src.substringAfter("fun rememberChildLiveToolBlock")
            .substringBefore("@Composable\nprivate fun ToolPreviewThumbnail")
        assertTrue("must observe the stream", fn.contains("streamingById"))
        // [T-android-stream-overlay-subset] The overlay is now applied by
        // streamingOverlaySubset — same merge, but it copies ONLY the
        // streaming messages instead of rebuilding the child's whole history
        // on every token. The invariant under test is that the side-channel is
        // still overlaid, not which helper does it.
        assertTrue(
            "must overlay the side-channel onto the messages",
            fn.contains("streamingOverlaySubset(msgs, streaming)") ||
                fn.contains("mergeStreamingOverlay(msgs, streaming)"),
        )
    }

    @Test
    fun `only an ACTIVE child tool is surfaced`() {
        // A finished tool must release the tile back to the agent card, or the
        // preview strands on work that is over.
        val fn = src.substringAfter("fun rememberChildLiveToolBlock")
            .substringBefore("@Composable\nprivate fun ToolPreviewThumbnail")
        for (st in listOf("RUNNING", "STREAMING", "PENDING")) {
            assertTrue("must accept $st", fn.contains("ToolBlockStatus.$st"))
        }
        assertTrue("must filter to tool_use blocks", fn.contains("kind == \"tool_use\""))
    }
}
