package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-subagent-card-resume] An interrupted sub agent's card offers a
 * Resume button (iOS HelperBlockView: `isInterrupted` → arrow.clockwise, same
 * slot as Stop), wired to the restart the model's `resume` call uses.
 *
 * "Interrupted" is not a status anything writes: it is a persisted `running`
 * payload whose block is no longer live, i.e. the app stopped mid-run. These
 * pin which cards get the button, and that the button goes through the same
 * slot/queue decision as the model rather than starting past the cap.
 */
class HelperCardResumeTest {

    private fun block(status: String, toolStatus: ToolBlockStatus) = AssistantBlock(
        id = "tool1",
        kind = "tool_use",
        content = """{"status":"$status","child_session_id":"child-1","title":"Research"}""",
        toolName = "subagent_task",
        toolTitle = "Research",
        toolStatus = toolStatus,
    )

    /** The card's condition for the Resume button, from HelperToolBlock. */
    private fun offersResume(b: AssistantBlock) = parseHelperBlock(b).finished?.status == "interrupted"

    @Test
    fun `a run the app lost mid-way is interrupted and offers Resume`() {
        val lost = block("running", ToolBlockStatus.SUCCESS)
        assertEquals("interrupted", parseHelperBlock(lost).finished?.status)
        assertTrue(offersResume(lost))
        assertEquals("child-1", parseHelperBlock(lost).childSessionId)
    }

    @Test
    fun `a live run offers Stop, not Resume`() {
        val live = block("running", ToolBlockStatus.RUNNING)
        assertTrue(parseHelperBlock(live).isRunning)
        assertTrue(!offersResume(live))
    }

    @Test
    fun `finished runs offer nothing to resume`() {
        for (s in listOf("completed", "failed", "cancelled", "timeout")) {
            assertNotEquals(s, true, offersResume(block(s, ToolBlockStatus.SUCCESS)))
        }
    }

    // ── Wiring ────────────────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `the card shows Resume only for an interrupted run, in Stop's slot`() {
        val ui = src("src/main/java/com/openminis/app/ui/chat/HelperUi.kt")
        val resume = ui.indexOf("info.finished?.status == \"interrupted\" && onResume != null")
        val stop = ui.indexOf("info.isRunning && onStop != null")
        assertTrue(resume in 0 until stop)
        assertTrue("disabled while a resume is pending", ui.contains(".clickable(enabled = !resuming"))
    }

    @Test
    fun `the chat wires the button to the view model and reports queued or refused`() {
        val cs = src("src/main/java/com/openminis/app/ui/chat/ChatScreen.kt")
        assertTrue(cs.contains("viewModel.resumeInterruptedFromCard(item.block)"))
        assertTrue(cs.contains("R.string.helper_resume_queued") && cs.contains("R.string.helper_resume_failed"))
    }

    @Test
    fun `the button takes the same slot or queue decision as the model's resume`() {
        val vm = src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        val body = vm.substringAfter("fun resumeInterruptedFromCard(").substringBefore("fun startQueuedDelegation(")
        val queuedCheck = body.indexOf("isQueuedLive(resumeQueueKey(childId))")
        val interruptedCheck = body.indexOf("interruptedChildIds(activeSessionId)")
        val cap = body.indexOf("canStartChildJob")
        val start = body.indexOf("resumeInterruptedChild(childId)")
        val queue = body.indexOf("enqueueResume(childId)")
        assertTrue("already-queued and not-interrupted are checked first", queuedCheck in 0 until interruptedCheck)
        assertTrue("the cap is checked before starting", interruptedCheck < cap && cap < start)
        assertTrue("a full set of slots queues instead", queue > start)
    }
}
