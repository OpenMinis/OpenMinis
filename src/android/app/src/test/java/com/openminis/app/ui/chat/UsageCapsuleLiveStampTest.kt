package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-usage-capsule-live] The usage capsule must be reachable on a
 * reply that JUST finished streaming, not only after the session is reopened.
 *
 * Reported: tapping blank space in a freshly streamed reply showed nothing;
 * leaving and re-entering the session made the capsule appear. Root cause:
 * `ChatMessage.tokenUsage` / `completedAt` were populated ONLY by the DB-load
 * path (`toChatMessages()`), never on the turn-finish path — so the live bubble
 * had `tokenUsage == null`, `buildFlatChatItems` emitted no AssistantUsage row,
 * and the blank-tap toggle had nothing to reveal. loadSession() parsed the row
 * from the DB on re-entry, which is why it "came back".
 *
 * Two layers are pinned here:
 *  - the flat build's contract (a non-streaming reply WITH usage yields exactly
 *    one AssistantUsage row; without usage, none) — this is the gate the live
 *    path was silently failing, and it documents the failure mode; and
 *  - a source assertion that `persistAssistantTurn` stamps the live bubble the
 *    same way the load path does, from both callers in the agent loop. The VM
 *    needs a Context/DB/provider to construct, so (as with SwitchModelGhostRetry
 *    and AgentHistoryRebuildIdempotence) the wiring is asserted against source.
 */
class UsageCapsuleLiveStampTest {

    private val usage = ChatTokenUsage(
        inputTokens = 120, outputTokens = 30,
        cacheCreationTokens = 0, cacheReadTokens = 0, latestContextTokens = 150,
    )

    private fun user(id: String) = ChatMessage(id = id, role = "user", content = "say ALPHA")

    private fun reply(id: String, streaming: Boolean, withUsage: Boolean) = ChatMessage(
        id = id, role = "assistant", content = "",
        isStreaming = streaming,
        toolBlocks = listOf(AssistantBlock(id = "$id-t", kind = "text", content = "ALPHA")),
        tokenUsage = if (withUsage) usage else null,
        completedAt = if (withUsage) 1_700_000_000_000L else null,
    )

    private fun usageRows(vararg msgs: ChatMessage) =
        buildFlatChatItems(msgs.toList()).filterIsInstance<FlatChatItem.AssistantUsage>()

    // ── the flat build's contract ──────────────────────────────────────────

    @Test
    fun `a finished reply with usage yields exactly one capsule row`() {
        val rows = usageRows(user("u1"), reply("a1", streaming = false, withUsage = true))
        assertEquals(1, rows.size)
        assertEquals("a1", rows[0].messageId)
        assertEquals(usage, rows[0].usage)
    }

    @Test
    fun `a finished reply WITHOUT usage yields no row — the pre-fix live state`() {
        // This is what every freshly streamed reply looked like before the fix:
        // streaming over, but tokenUsage never stamped. Nothing to reveal.
        val rows = usageRows(user("u1"), reply("a1", streaming = false, withUsage = false))
        assertTrue("no usage → no capsule row; this is the bug's failure mode", rows.isEmpty())
    }

    @Test
    fun `usage on a still-streaming reply is held back until the stream ends`() {
        // The tool-turn path stamps usage while the bubble is still streaming.
        // The gate must keep the row hidden then (a finish time for a turn that
        // has not finished would be wrong) and release it once streaming stops.
        assertTrue(usageRows(user("u1"), reply("a1", streaming = true, withUsage = true)).isEmpty())
        assertEquals(1, usageRows(user("u1"), reply("a1", streaming = false, withUsage = true)).size)
    }

    // ── the live path must stamp the bubble ────────────────────────────────

    private val vmSrc by lazy {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("missing ChatViewModel source", f.exists())
        f.readText()
    }

    @Test
    fun `persistAssistantTurn stamps tokenUsage and completedAt onto the live bubble`() {
        val body = vmSrc.substringAfter("private suspend fun persistAssistantTurn(")
            .substringBefore("@Deprecated")
        assertTrue("must accept the UI bubble id", body.contains("uiAssistantId: String?"))
        assertTrue(
            "must derive usage from the persisted entity, exactly like toChatMessages()",
            body.contains("ChatTokenUsage.parse(entity.tokenUsage)"),
        )
        assertTrue(
            "must stamp both fields onto the matching live ChatMessage",
            body.contains("m.copy(tokenUsage = stampedUsage ?: m.tokenUsage, completedAt = entity.createdAt)"),
        )
        assertTrue("the _messages write must happen on Main", body.contains("withContext(Dispatchers.Main)"))
    }

    @Test
    fun `a turn without usage keeps the capsule an earlier turn stamped`() {
        // [T-android-usage-capsule-live-reload] The reload merge keeps
        // `msg.tokenUsage ?: prev.tokenUsage`; the live stamp must not assign a
        // null over an earlier tool turn's usage, or the capsule vanishes live
        // and reappears after a reload.
        val body = vmSrc.substringAfter("private suspend fun persistAssistantTurn(")
            .substringBefore("@Deprecated")
        assertTrue(body.contains("tokenUsage = stampedUsage ?: m.tokenUsage"))
        assertTrue("must not overwrite with a possibly-null value",
            !body.contains("m.copy(tokenUsage = stampedUsage,"))
        assertTrue("the reload merge applies the same rule",
            vmSrc.contains("tokenUsage = msg.tokenUsage ?: prev.tokenUsage,"))
    }

    @Test
    fun `both agent-loop callers hand the bubble id to the persist step`() {
        val calls = Regex("persistAssistantTurn\\(turnParts, lastUsage, turnReasoningContent, blockMeta[^)]*\\)")
            .findAll(vmSrc).map { it.value }.toList()
        assertEquals("expected the final-turn and tool-turn callers", 2, calls.size)
        for (c in calls) {
            assertTrue(
                "caller must pass uiAssistantId = assistantId, or that path's bubble never gets its capsule: $c",
                c.contains("uiAssistantId = assistantId"),
            )
        }
    }

    @Test
    fun `the live stamp mirrors the load path field for field`() {
        // If the load path ever changes how it derives these, the live path
        // must change with it — otherwise a reloaded bubble and a live one
        // would show different numbers for the same turn.
        val load = vmSrc.substringAfter("toChatMessages(): List<ChatMessage>")
        assertTrue(load.contains("ChatTokenUsage.parse(entity.tokenUsage)"))
        assertTrue(load.contains("completedAt = if (entity.role == \"assistant\") entity.createdAt else null"))
    }
}
