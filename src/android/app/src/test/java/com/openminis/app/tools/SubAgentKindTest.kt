package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentKindTest {

    @Test
    fun normalizeKinds() {
        assertEquals(SubAgentKind.EXPLORE, SubAgentKind.normalize("read-only"))
        assertEquals(SubAgentKind.EXPLORE, SubAgentKind.normalize("recon"))
        assertEquals(SubAgentKind.PLAN, SubAgentKind.normalize("planner"))
        assertEquals(SubAgentKind.GENERAL, SubAgentKind.normalize("general-purpose"))
        assertEquals(SubAgentKind.GENERAL, SubAgentKind.normalize("gp"))
        assertEquals(SubAgentKind.WORKER, SubAgentKind.normalize(null))
    }

    @Test
    fun exploreDropsWriteToolsKeepsReadWhitelist() {
        val tools = listOf(
            AgentToolDefinition("file_read", "r", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("file_write", "w", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("shell_execute", "s", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("web_search", "q", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
        )
        val names = SubAgentKind.filterTools(SubAgentKind.EXPLORE, tools).map { it.name }
        assertEquals(listOf("file_read", "shell_execute", "web_search"), names)
        assertTrue(SubAgentKind.blocks(SubAgentKind.PLAN, "file_edit"))
        assertFalse(SubAgentKind.blocks(SubAgentKind.WORKER, "file_write"))
        assertFalse(SubAgentKind.blocks(SubAgentKind.GENERAL, "file_write"))
    }

    @Test
    fun clampAndInferTurns() {
        assertEquals(10, SubAgentKind.inferTurns(SubAgentKind.EXPLORE, "look at Foo.kt"))
        assertEquals(40, SubAgentKind.inferTurns(SubAgentKind.WORKER, "implement the login flow"))
        assertEquals(10, SubAgentKind.clampTurns(SubAgentKind.EXPLORE, null, 60, "look at Foo.kt"))
        assertEquals(99, SubAgentKind.clampTurns(SubAgentKind.EXPLORE, 99))
        assertEquals(30, SubAgentKind.clampTurns(SubAgentKind.WORKER, null, 60, "do the slice"))
        assertEquals(30, SubAgentKind.clampTurns(SubAgentKind.WORKER, 99, 30))
        assertEquals(8, SubAgentKind.clampTurns(SubAgentKind.PLAN, 8, 30))
        assertEquals(10, SubAgentKind.clampTurns(SubAgentKind.WORKER, 0, 60, "find the crash"))
    }

    /**
     * [T-subagent-complex-hints] Repair verbs are complex-tier hints. Before
     * the keyword expansion a short "fix the login bug" prompt sized as plain
     * medium and ran dry at 20 turns — the exact user complaint.
     */
    @Test
    fun repairVerbsSizeComplex() {
        assertEquals(40, SubAgentKind.inferTurns(SubAgentKind.WORKER, "fix the login bug"))
        assertEquals(40, SubAgentKind.inferTurns(SubAgentKind.WORKER, "修复登录崩溃"))
        assertEquals(40, SubAgentKind.inferTurns(SubAgentKind.WORKER, "debug why the probe fails"))
        assertEquals(40, SubAgentKind.inferTurns(SubAgentKind.WORKER, "排查探针失败原因"))
        // Investigation verbs also lift explore out of the 10-turn recon band.
        assertEquals(30, SubAgentKind.inferTurns(SubAgentKind.EXPLORE, "investigate the flaky test"))
        assertEquals(30, SubAgentKind.inferTurns(SubAgentKind.EXPLORE, "排查轮次上限来源"))
        // Plain recon stays cheap.
        assertEquals(10, SubAgentKind.inferTurns(SubAgentKind.EXPLORE, "look at Foo.kt"))
    }

    @Test
    fun nestedSpawnAlwaysDropped() {
        val tools = listOf(
            AgentToolDefinition("spawn_agent", "d", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("run_subagent", "d", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("file_read", "r", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
            AgentToolDefinition("cronjob", "c", mapOf("x" to AgentToolParam("string", "x")), listOf("x")),
        )
        val names = SubAgentKind.filterTools(SubAgentKind.WORKER, tools).map { it.name }
        assertTrue(names.none { SubAgentKind.isSpawnTool(it) })
        assertFalse(names.contains("cronjob"))
        assertTrue(SubAgentKind.blocks(SubAgentKind.EXPLORE, CronJobTool.NAME))
        assertTrue(SubAgentKind.blocks(SubAgentKind.WORKER, "run_subagent"))
        assertTrue(SubAgentKind.blocks(SubAgentKind.PLAN, "spawn_agent"))
        assertTrue(SubAgentKind.blocks(SubAgentKind.GENERAL, "spawn_agent"))
        assertTrue(SubAgentKind.isSpawnTool("spawn_agent"))
        assertTrue(SubAgentKind.isSpawnTool("run_subagent"))
        assertTrue(SubAgentKind.isSpawnTool("dispatch_agents"))
        assertTrue(SubAgentKind.isSpawnTool("wolfpack_run"))
        assertTrue(SubAgentKind.blocks(SubAgentKind.WORKER, "dispatch_agents"))
    }

    @Test
    fun parallelWorkersRequireWritePaths() {
        assertTrue(SubAgentKind.requiresWritePaths(SubAgentKind.WORKER, 2))
        assertFalse(SubAgentKind.requiresWritePaths(SubAgentKind.WORKER, 1))
        assertFalse(SubAgentKind.requiresWritePaths(SubAgentKind.EXPLORE, 4))
        assertFalse(SubAgentKind.requiresWritePaths(SubAgentKind.GENERAL, 3))
        assertTrue(SubAgentKind.canWrite(SubAgentKind.WORKER))
        assertTrue(SubAgentKind.canWrite(SubAgentKind.GENERAL))
        assertFalse(SubAgentKind.canWrite(SubAgentKind.EXPLORE))
    }

    @Test
    fun nestedObjectParamSerializesItems() {
        val param = AgentToolParam(
            type = "array",
            description = "tasks",
            items = AgentToolParam(
                type = "object",
                description = "task",
                properties = mapOf("prompt" to AgentToolParam("string", "brief")),
                required = listOf("prompt"),
            ),
        )
        val json = param.toJson().toString()
        assertTrue(json.contains("\"type\":\"array\""))
        assertTrue(json.contains("\"items\""))
        assertTrue(json.contains("\"prompt\""))
        val gemini = param.toGeminiJson().toString()
        assertTrue(gemini.contains("\"type\":\"ARRAY\""))
    }

    @Test
    fun exploreShellAllowsInspectionAndDeniesWrites() {
        assertNull(SubAgentKind.readOnlyShellDenial("date"))
        assertNull(SubAgentKind.readOnlyShellDenial("uname -a"))
        assertNull(SubAgentKind.readOnlyShellDenial("cat /etc/os-release"))
        assertNull(SubAgentKind.readOnlyShellDenial("ls -l /var/minis"))
        assertNull(SubAgentKind.readOnlyShellDenial("df -h"))
        assertNull(SubAgentKind.readOnlyShellDenial("date 2>&1"))
        assertNull(SubAgentKind.readOnlyShellDenial("uname 2>/dev/null"))
        assertNull(SubAgentKind.readOnlyShellDenial("echo hi >/dev/null"))
        assertNull(SubAgentKind.readOnlyShellDenial("echo \"a > b\""))
        assertNull(SubAgentKind.readOnlyShellDenial("echo 'rm /tmp/x'"))
        assertNull(SubAgentKind.readOnlyShellDenial("echo \$(date)"))
        assertNull(SubAgentKind.readOnlyShellDenial("sed -n 's/a/b/p' file"))
        assertNull(SubAgentKind.readOnlyShellDenial("cat <<EOF\na > b\nEOF\ndate"))
        assertTrue(SubAgentKind.readOnlyShellDenial("echo hi > /tmp/out") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("echo hi 2> /tmp/out") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("echo hi 1>>/tmp/out") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("echo hi &> /tmp/out") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("echo hi >| /tmp/out") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("rm -f /tmp/out") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("sudo rm /tmp/out") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("bash -c 'echo hi 2> /tmp/out'") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("busybox sh -c 'rm /tmp/out'") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("env bash -c 'rm /tmp/out'") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("bash -lc 'echo hi 2> /tmp/out'") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("echo \$(rm /tmp/out)") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("sed -i 's/a/b/' file") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("perl -pi -e 's/a/b/' file") != null)
        assertTrue(SubAgentKind.readOnlyShellDenial("echo hi > out") != null)
    }

    @Test
    fun dedupeByNameKeepsFirstAndOrder() {
        val t = AgentToolDefinition("file_read", "r", mapOf("x" to AgentToolParam("string", "x")), listOf("x"))
        val t2 = AgentToolDefinition("file_read", "r2", mapOf("y" to AgentToolParam("string", "y")), listOf("y"))
        val w = AgentToolDefinition("file_write", "w", mapOf("x" to AgentToolParam("string", "x")), listOf("x"))
        val deduped = SubAgentKind.dedupeByName(listOf(t, t2, w, t2))
        assertEquals(listOf("file_read", "file_write"), deduped.map { it.name })
        assertTrue(deduped[0] === t) // first definition wins
    }

    @Test
    fun filterToolsDedupesEarlyReturnPath() {
        // worker 无 allow-list 走 base 早退路径，去重必须覆盖这条路径。
        val t = AgentToolDefinition("grep", "g", mapOf("x" to AgentToolParam("string", "x")), listOf("x"))
        val t2 = AgentToolDefinition("grep", "g2", mapOf("y" to AgentToolParam("string", "y")), listOf("y"))
        val f = AgentToolDefinition("file_read", "r", mapOf("x" to AgentToolParam("string", "x")), listOf("x"))
        val names = SubAgentKind.filterTools(SubAgentKind.WORKER, listOf(t, f, t2)).map { it.name }
        assertEquals(listOf("grep", "file_read"), names)
    }
}
