package com.openminis.app.ui.chat

import com.openminis.app.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-programmatic-prompt-keeps-draft] A prompt nobody typed (a
 * scheduled fire, a sub-agent callback, the sessions CLI) must never touch the
 * composer draft (iOS 2a06de66a).
 *
 * Android's headless path already carried its text as an argument, but the
 * pre-send context check's auto-compact branch cleared `_inputText`
 * unconditionally, so a background prompt that tripped the compact threshold
 * erased what the user was typing.
 */
class ProgrammaticPromptKeepsDraftTest {

    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")

    private fun branch(start: String, end: String) = vm.substringAfter(start).substringBefore(end)

    @Test
    fun `the auto-compact branch clears the composer only for the user's own send`() {
        val compactBranch = branch("PreSendContextAction.COMPACT_THEN_SEND -> {", "PreSendContextAction.ASK_USER -> {")
        assertTrue(compactBranch.contains("if (!headless) _inputText.value = \"\""))
        assertFalse(
            "an unconditional clear is back",
            compactBranch.lines().any { it.trim() == "_inputText.value = \"\"" },
        )
        assertTrue(compactBranch.contains("pendingSendHeadless = headless"))
    }

    @Test
    fun `the send after compaction stays headless`() {
        val compactAndSend = branch("fun compactAndSendPending(", "fun sendPendingWithoutCompacting(")
        assertTrue(compactAndSend.contains("sendMessage(text, skipContextCheck = true, headless = headless, prefill = prefill)"))
        val sendWithout = branch("fun sendPendingWithoutCompacting(", "fun cancelCompactBeforeSend(")
        assertTrue(sendWithout.contains("headless = headless"))
    }

    @Test
    fun `cancel never puts a background prompt into the composer`() {
        val cancel = branch("fun cancelCompactBeforeSend(", "private enum class InLoopContextAction")
        assertTrue(cancel.contains("if (!pendingSendHeadless) pendingSendText?.let { _inputText.value = it }"))
    }

    @Test
    fun `a headless prompt still refuses the ask-user dialog before touching anything`() {
        val askBranch = branch("PreSendContextAction.ASK_USER -> {", "_showCompactBeforeSendPrompt.value = true")
        val refuse = askBranch.indexOf("if (headless) return SubmitOutcome.Rejected(\"context_near_capacity\")")
        val clear = askBranch.indexOf("_inputText.value = \"\"")
        assertTrue(refuse >= 0 && clear > refuse)
    }
}
