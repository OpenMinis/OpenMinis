package com.openminis.app.debug

import com.openminis.app.BuildConfig
import com.openminis.app.ProductionSources
import com.openminis.app.agent.jobs.AgentJobRegistry
import com.openminis.app.sandbox.GlobalShellThrottle
import com.openminis.app.service.SessionConcurrencyManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-loadtest-limits] The DEV_TOOLS-only runtime overrides of the concurrency
 * caps used by on-device load tests: defaults unchanged, clamped, resettable,
 * and the admission paths read the effective value.
 */
class LoadTestLimitsTest {

    @After
    fun reset() {
        SessionConcurrencyManager.debugSetMaxConcurrent(null)
        AgentJobRegistry.debugSetMaxConcurrentChildJobs(null)
        GlobalShellThrottle.debugSetProcessBudget(null)
    }

    @Test
    fun `defaults are the shipped caps`() {
        // [T-new-limits] 6 session slots; 3 sub agents PER parent conversation.
        assertEquals(6, SessionConcurrencyManager.maxConcurrent)
        assertEquals(3, AgentJobRegistry.maxConcurrentChildJobsPerParent)
        assertEquals(10, AgentJobRegistry.MAX_QUEUED_DELEGATIONS_PER_PARENT)
        assertEquals(16, GlobalShellThrottle.processBudget)
    }

    @Test
    fun `set, clamp and reset`() {
        if (BuildConfig.DEV_TOOLS) {
            assertEquals(16, SessionConcurrencyManager.debugSetMaxConcurrent(16))
            assertEquals(32, SessionConcurrencyManager.debugSetMaxConcurrent(99))
            assertEquals(1, SessionConcurrencyManager.debugSetMaxConcurrent(0))
            assertEquals(6, SessionConcurrencyManager.debugSetMaxConcurrent(null))
            assertEquals(10, AgentJobRegistry.debugSetMaxConcurrentChildJobs(10))
            assertEquals(3, AgentJobRegistry.debugSetMaxConcurrentChildJobs(null))
            assertEquals(32, GlobalShellThrottle.debugSetProcessBudget(32))
            assertEquals(GlobalShellThrottle.PROCESSES_PER_COMMAND, GlobalShellThrottle.debugSetProcessBudget(1))
            assertEquals(16, GlobalShellThrottle.debugSetProcessBudget(null))
        } else {
            // A build without developer tools must ignore every override,
            // including valid values, clamping inputs and a reset request.
            for (value in listOf<Int?>(0, 1, 10, 16, 32, 99, null)) {
                assertEquals(6, SessionConcurrencyManager.debugSetMaxConcurrent(value))
                assertEquals(6, SessionConcurrencyManager.maxConcurrent)
                assertEquals(3, AgentJobRegistry.debugSetMaxConcurrentChildJobs(value))
                assertEquals(3, AgentJobRegistry.maxConcurrentChildJobsPerParent)
                assertEquals(16, GlobalShellThrottle.debugSetProcessBudget(value))
                assertEquals(16, GlobalShellThrottle.processBudget)
            }
        }
    }

    @Test
    fun `admission reads the effective caps, setters are DEV_TOOLS-gated`() {
        val scm = ProductionSources.read("service/SessionConcurrencyManager.kt")
        assertTrue(scm.contains("if (_runningSessions.value.size < maxConcurrent) {"))
        assertTrue(scm.contains("if (!com.openminis.app.BuildConfig.DEV_TOOLS) return maxConcurrent"))
        val reg = ProductionSources.read("agent/jobs/AgentJobRegistry.kt")
        assertTrue(reg.contains(
            "fun canStartChildJob(parentSessionId: String): Boolean =\n" +
                "        runningChildJobCount(parentSessionId) < maxConcurrentChildJobsPerParent",
        ))
        assertTrue(reg.contains("if (!com.openminis.app.BuildConfig.DEV_TOOLS) return maxConcurrentChildJobsPerParent"))
        val gst = ProductionSources.read("sandbox/GlobalShellThrottle.kt")
        assertTrue(gst.contains("<= processBudget) return"))
        assertTrue(gst.contains("if (!com.openminis.app.BuildConfig.DEV_TOOLS) return processBudget"))
        val rpc = ProductionSources.read("debug/LoadTestDebugMethods.kt")
        assertTrue(rpc.contains("if (!BuildConfig.DEV_TOOLS) throw RPCException("))
    }

    @Test
    fun `limits RPC keeps the childJobs key and accepts the per-parent alias`() {
        // [T-new-limits] Load-test scripts already send `childJobs`; it keeps
        // working and now means "per parent". `childJobsPerParent` wins.
        val rpc = ProductionSources.read("debug/LoadTestDebugMethods.kt")
        assertTrue(rpc.contains("\"childJobsPerParent\""))
        assertTrue(rpc.contains("\"childJobs\""))
        assertTrue(rpc.contains(".put(\"childJobsScope\", \"per_parent_session\")"))
        assertTrue(rpc.contains("\"childJobsActiveByParent\""))
    }

    @Test
    fun `seedSession is DEV_TOOLS-only and only writes loadtest-titled sessions`() {
        val rpc = ProductionSources.read("debug/LoadTestDebugMethods.kt")
        val seed = rpc.substringAfter("suspend fun seedSession(").substringBefore("fun snapshot(")
        assertTrue(seed.contains("requireDevTools()"))
        assertTrue(seed.contains("if (!title.startsWith(\"loadtest-\")) throw RPCException("))
        assertTrue("a preset title skips title generation", seed.contains("repo.createSession(modelId = modelId, title = title)"))
    }
}
