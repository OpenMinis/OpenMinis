package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-subagent-control-hidden] Telling a control call apart from a
 * delegation.
 *
 * A control call (status / steer / cancel / resume) has no result of its own to
 * show — its effect lands on the card of the run it acted on — so it gets one
 * quiet line instead of a card. Getting this wrong in either direction is
 * user-visible: a missed control call is a duplicate row, and a MISCLASSIFIED
 * delegation is a card that vanishes.
 */
class SubAgentControlCallTest {

    private fun classify(args: String?, result: String?) =
        HelperRunner.classifyCall(args, result)

    // ── Tier 1: the call's own action ───────────────────────────────────────

    @Test
    fun `the action argument classifies the call directly`() {
        assertEquals(HelperRunner.SubAgentCall.STATUS, classify("""{"action":"status"}""", null))
        assertEquals(HelperRunner.SubAgentCall.RESUME, classify("""{"action":"resume"}""", null))
        assertEquals(HelperRunner.SubAgentCall.STEER, classify("""{"action":"steer"}""", null))
        assertEquals(HelperRunner.SubAgentCall.CANCEL, classify("""{"action":"cancel"}""", null))
        assertEquals(HelperRunner.SubAgentCall.DELEGATE, classify("""{"action":"delegate"}""", null))
    }

    @Test
    fun `an absent action means delegate, which is the schema's default`() {
        assertEquals(HelperRunner.SubAgentCall.DELEGATE, classify("""{"task":"do it"}""", null))
    }

    @Test
    fun `the action is matched case-insensitively`() {
        assertEquals(HelperRunner.SubAgentCall.STATUS, classify("""{"action":"STATUS"}""", null))
    }

    // ── Tier 2: inferring from the result's shape ───────────────────────────

    @Test
    fun `a status result is recognised by its agents array`() {
        assertEquals(
            HelperRunner.SubAgentCall.STATUS,
            classify(null, """{"ok":true,"action":"status","count":1,"agents":[{"job_id":"j"}]}"""),
        )
    }

    @Test
    fun `a resume result is recognised by its child_session_ids`() {
        assertEquals(
            HelperRunner.SubAgentCall.RESUME,
            classify(null, """{"ok":true,"resumed":1,"child_session_ids":["c1"]}"""),
        )
    }

    @Test
    fun `a steer result is recognised by queued plus a job id`() {
        assertEquals(
            HelperRunner.SubAgentCall.STEER,
            classify(null, """{"ok":true,"status":"queued","job_id":"j1"}"""),
        )
    }

    @Test
    fun `a steer rejection is still a control call`() {
        for (reason in listOf("already_finished", "child_not_running")) {
            assertEquals(
                HelperRunner.SubAgentCall.STEER,
                classify(null, """{"ok":false,"status":"rejected","reason":"$reason"}"""),
            )
        }
    }

    // ── THE PITFALL: order of the shape tests ───────────────────────────────

    @Test
    fun `a RESUMED delegation keeps its card`() {
        // This is the trap. A resumed delegation's result carries a `resumed`
        // key — the marker saying the run had been interrupted — so keying on
        // that would hide the very card the user just resumed. A delegation
        // ALWAYS carries child_session_id and a control call never does, so
        // that has to be tested FIRST. iOS recorded hitting exactly this.
        val resumedDelegation =
            """{"ok":true,"status":"completed","resumed":true,"child_session_id":"c1","result":"done"}"""
        assertEquals(HelperRunner.SubAgentCall.DELEGATE, classify(null, resumedDelegation))
        assertFalse(HelperRunner.isControlOnly(null, resumedDelegation))
    }

    @Test
    fun `child_session_id wins over every other shape marker`() {
        // Belt and braces: even a payload that also looks like a status result
        // is a delegation if it started a run.
        val hybrid = """{"child_session_id":"c1","agents":[{"job_id":"j"}]}"""
        assertEquals(HelperRunner.SubAgentCall.DELEGATE, classify(null, hybrid))
    }

    @Test
    fun `an empty child_session_id does not count as a delegation`() {
        // The control payloads write "" for unused fields, so presence alone
        // is not enough — it has to actually name a session.
        val control = """{"child_session_id":"","agents":[]}"""
        assertEquals(HelperRunner.SubAgentCall.STATUS, classify(null, control))
    }

    @Test
    fun `an ordinary delegation result is a delegation`() {
        val delegation =
            """{"ok":true,"status":"completed","child_session_id":"c1","job_id":"j1","result":"x"}"""
        assertFalse(HelperRunner.isControlOnly(null, delegation))
    }

    // ── Arguments outrank the result shape ──────────────────────────────────

    @Test
    fun `the action argument is preferred over the result shape`() {
        // A status call whose result happens to carry a child_session_id (it
        // reports one per agent) must still read as a control call.
        val args = """{"action":"status"}"""
        val result = """{"agents":[{"child_session_id":"c1"}]}"""
        assertEquals(HelperRunner.SubAgentCall.STATUS, classify(args, result))
    }

    // ── Robustness ──────────────────────────────────────────────────────────

    @Test
    fun `malformed json never throws and never hides a card`() {
        // Failing safe means DELEGATE: showing an extra row is recoverable,
        // hiding a real delegation is not.
        for (bad in listOf("", "{not json", "[]", "null")) {
            assertEquals(HelperRunner.SubAgentCall.DELEGATE, classify(bad, bad))
        }
        assertEquals(HelperRunner.SubAgentCall.DELEGATE, classify(null, null))
    }

    // ── The count on the summary line ───────────────────────────────────────

    @Test
    fun `the count comes from the array the action produced`() {
        assertEquals(
            3,
            HelperRunner.controlCount(
                HelperRunner.SubAgentCall.STATUS,
                """{"agents":[{},{},{}]}""",
            ),
        )
        assertEquals(
            2,
            HelperRunner.controlCount(
                HelperRunner.SubAgentCall.RESUME,
                """{"child_session_ids":["a","b"]}""",
            ),
        )
    }

    @Test
    fun `a missing count is null so the line omits it rather than guessing`() {
        assertNull(HelperRunner.controlCount(HelperRunner.SubAgentCall.STATUS, """{"ok":true}"""))
        assertNull(HelperRunner.controlCount(HelperRunner.SubAgentCall.STEER, """{"ok":true}"""))
        assertNull(HelperRunner.controlCount(HelperRunner.SubAgentCall.STATUS, null))
    }

    @Test
    fun `isControlOnly agrees with classifyCall`() {
        assertTrue(HelperRunner.isControlOnly("""{"action":"cancel"}""", null))
        assertFalse(HelperRunner.isControlOnly("""{"action":"delegate"}""", null))
    }
}

/**
 * [T-android-no-deliverable] A sub agent that finishes without writing anything.
 *
 * This used to report `completed`, so the card showed a green tick and "Done"
 * while the parent received nothing — more deceptive than an error, because the
 * user reads success and discovers the gap later.
 */
class SubAgentNoDeliverableTest {

    @Test
    fun `a clean finish with no result is reclassified`() {
        assertEquals(
            HelperRunner.NO_DELIVERABLE,
            HelperRunner.resolvedStatus("completed", ""),
        )
    }

    @Test
    fun `whitespace only counts as nothing handed over`() {
        for (blank in listOf(" ", "\n", "  \n\t ")) {
            assertEquals(
                HelperRunner.NO_DELIVERABLE,
                HelperRunner.resolvedStatus("completed", blank),
            )
        }
    }

    @Test
    fun `a real deliverable stays completed`() {
        assertEquals("completed", HelperRunner.resolvedStatus("completed", "the answer"))
        // Even a single character is a deliverable — the rule is "empty", not
        // "short", and second-guessing length would drop real answers.
        assertEquals("completed", HelperRunner.resolvedStatus("completed", "x"))
    }

    @Test
    fun `only a clean finish is reclassified`() {
        // cancelled / timeout / failed already report WHY the run ended.
        // Relabelling them would throw that reason away, so an empty result
        // must not touch them.
        for (s in listOf("cancelled", "timeout", "failed", "rejected", "interrupted")) {
            assertEquals(s, HelperRunner.resolvedStatus(s, ""))
        }
    }

    @Test
    fun `it is not called failed`() {
        // Nothing broke: the transcript is intact and the tools ran. Naming it
        // a failure would send the user hunting for a fault that is not there.
        assertNotEquals("failed", HelperRunner.NO_DELIVERABLE)
        assertEquals("no_deliverable", HelperRunner.NO_DELIVERABLE)
    }

    @Test
    fun `the status is a distinct token, not one the unknown branch swallows`() {
        // helperStatusColor / helperStatusGlyph route unrecognised statuses to
        // a neutral "the app does not know how this ended". Here it knows
        // exactly, so this must be its own recognised token.
        assertTrue(HelperRunner.NO_DELIVERABLE.isNotBlank())
        assertNotEquals("unknown", HelperRunner.NO_DELIVERABLE)
        assertNotEquals("completed", HelperRunner.NO_DELIVERABLE)
    }
}
