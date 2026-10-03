package com.openminis.app.agent.jobs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-p1-delegate-task] Argument parsing / clamping and result JSON shape, aligned with iOS HelperRunner. */
class HelperRunnerArgsTest {

    @Test
    fun `defaults match iOS — primary tier, 10 minutes, wait=false (background)`() {
        val a = HelperRunner.parseArgs("""{"tool_title":"T","task":"do it"}""")
        assertEquals("T", a.title); assertEquals("do it", a.task)
        assertEquals(HelperModelTier.PRIMARY, a.tier)
        assertEquals("primary", a.tierRequested)
        assertEquals(HelperRunner.DEFAULT_MINUTES, a.minutes)
        assertFalse("background is the default (T-p2-background-default)", a.wait)
        assertEquals("none", a.progressLevel)
    }

    @Test
    fun `wait=true opts into blocking and progress levels are validated`() {
        assertTrue(HelperRunner.parseArgs("""{"task":"x","wait":true}""").wait)
        assertEquals("frequent", HelperRunner.parseArgs("""{"task":"x","progress_report":"frequent"}""").progressLevel)
        assertEquals("moderate", HelperRunner.parseArgs("""{"task":"x","progress_report":"MODERATE"}""").progressLevel)
        assertEquals("none", HelperRunner.parseArgs("""{"task":"x","progress_report":"hourly"}""").progressLevel)
    }

    @Test
    fun `background start payload and running detection`() {
        val start = HelperRunner.backgroundStartJson("J", "C", "gpt", HelperModelTier.PRIMARY, 10, converted = false)
        assertTrue(HelperRunner.isRunningPayload(start))
        assertEquals("C", HelperRunner.childSessionIdFrom(start))
        val o = JSONObject(start)
        assertEquals("running", o.getString("status")); assertFalse(o.getBoolean("converted_from_wait"))
        assertTrue(JSONObject(HelperRunner.backgroundStartJson("J", "C", "gpt", HelperModelTier.SUB, 5, converted = true)).getString("note").contains("moved to the background"))
        assertFalse(HelperRunner.isRunningPayload(HelperRunner.resultJson("completed", "r", "m", HelperModelTier.PRIMARY, "primary", 1, 0, "C", "J")))
        assertTrue(HelperRunner.isRunningPayload(HelperRunner.progressJson("C", "t", "shell_execute", "ls", 1000)))
    }

    @Test
    fun `result payload carries summary and delivered_as and an empty result becomes the status note`() {
        val done = JSONObject(HelperRunner.resultJson("completed", "", "m", HelperModelTier.PRIMARY, "primary", 2, 3000, "C", "J", summary = "Summary: tools none yet · turns 2 · tokens in 0 / out 0", deliveredAs = "new turn in this conversation"))
        assertTrue(done.getString("result").startsWith("(the agent ended with status completed"))
        assertEquals("new turn in this conversation", done.getString("delivered_as"))
        assertTrue(done.getString("summary").startsWith("Summary:"))
        assertEquals("timeout", HelperRunner.statusWord(AgentJobState.TIMEOUT))
        assertEquals("completed", HelperRunner.statusWord(AgentJobState.DONE))
        assertEquals("cancelled", HelperRunner.statusWord(AgentJobState.CANCELLED))
    }

    @Test
    fun `wrap-up prompt and run summary line`() {
        assertTrue(HelperRunner.wrapUpPrompt(HelperWrapUpReason.TURNS).startsWith("[You have used all of your tool rounds."))
        assertTrue(HelperRunner.wrapUpPrompt(HelperWrapUpReason.BUDGET).startsWith("[Your time budget is up."))
        assertEquals("Summary: tools shell_execute×2, file_read×1 · turns 3 · tokens in 12.3k / out 900 (cache read 1.0k)",
            HelperRunner.runSummaryLine(listOf("shell_execute", "file_read", "shell_execute"), 3, 12_300, 900, 1_000))
        assertEquals("Summary: tools none yet · turns 0 · tokens in 0 / out 0", HelperRunner.runSummaryLine(emptyList(), 0, 0, 0, 0))
    }

    @Test
    fun `minutes clamp to 1-60 and bad values fall back to default`() {
        assertEquals(60, HelperRunner.parseArgs("""{"task":"x","max_minutes":99}""").minutes)
        assertEquals(1, HelperRunner.parseArgs("""{"task":"x","max_minutes":0}""").minutes)
        assertEquals(HelperRunner.DEFAULT_MINUTES, HelperRunner.parseArgs("""{"task":"x","max_minutes":"lots"}""").minutes)
    }

    @Test
    fun `tier parse — sub, unknown degrades to primary, tierRequested keeps raw`() {
        assertEquals(HelperModelTier.SUB, HelperRunner.parseArgs("""{"task":"x","model_tier":"sub"}""").tier)
        val weird = HelperRunner.parseArgs("""{"task":"x","model_tier":"auto"}""")
        assertEquals(HelperModelTier.PRIMARY, weird.tier)
        assertEquals("auto", weird.tierRequested)
    }

    @Test
    fun `wait=false parses false so the runner can reject it`() {
        assertFalse(HelperRunner.parseArgs("""{"task":"x","wait":false}""").wait)
    }

    @Test
    fun `context is appended to the child prompt`() {
        val p = HelperRunner.childPrompt(HelperRunner.parseArgs("""{"task":"A","context":"B"}"""))
        assertTrue(p.startsWith("A")); assertTrue(p.contains("B"))
        assertEquals("A", HelperRunner.childPrompt(HelperRunner.parseArgs("""{"task":"A"}""")))
    }

    @Test
    fun `rejection and result JSON carry the iOS field set`() {
        val rej = JSONObject(HelperRunner.rejectionJson("depth_limit", "nope"))
        assertFalse(rej.getBoolean("ok")); assertEquals("depth_limit", rej.getString("reason"))
        val res = JSONObject(HelperRunner.resultJson("completed", "42", "gpt", HelperModelTier.SUB, "sub", 3, 65_000, "C", "J"))
        assertTrue(res.getBoolean("ok"))
        for (k in listOf("status", "result", "model_used", "tier_used", "tier_requested", "turns", "elapsed_s", "child_session_id", "job_id")) {
            assertTrue("missing $k", res.has(k))
        }
        assertEquals("C", res.getString("child_session_id"))
        assertEquals(65, res.getInt("elapsed_s"))
        val timeout = JSONObject(HelperRunner.resultJson("timeout", "", "gpt", HelperModelTier.PRIMARY, "primary", 1, 0, "C", "J"))
        assertFalse(timeout.getBoolean("ok"))
    }

    @Test
    fun `childSessionIdFrom reads progress and result blocks, null otherwise`() {
        assertEquals("C1", HelperRunner.childSessionIdFrom(HelperRunner.progressJson("C1", "t", "shell", "ls", 0)))
        assertEquals("C2", HelperRunner.childSessionIdFrom(HelperRunner.resultJson("completed", "", "m", HelperModelTier.PRIMARY, "primary", 1, 0, "C2", "J")))
        assertNull(HelperRunner.childSessionIdFrom("not json"))
        assertNull(HelperRunner.childSessionIdFrom(HelperRunner.rejectionJson("empty_task", "x")))
    }
}
