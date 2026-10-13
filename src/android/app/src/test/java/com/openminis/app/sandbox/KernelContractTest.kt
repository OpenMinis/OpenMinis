package com.openminis.app.sandbox

import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.GuardianScript
import com.openminis.app.sandbox.kernel.WorkClass
import com.openminis.app.service.SessionConcurrencyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelContractTest {
    private val groupKill = "kill -TERM -" + "$" + "$"
    private val groupKillHard = "kill -KILL -" + "$" + "$"

    @Test
    fun oneshotKillsTheProcessGroupAndDoesNotUseKillZero() {
        val script = GuardianScript.oneshot("echo hi", BudgetClassifier.normal())
        assertTrue(script.contains("trap 'kill -KILL"))
        assertTrue(script.contains(groupKill))
        assertTrue(script.contains(groupKillHard))
        assertFalse(script.contains("kill -0"))
        assertFalse(script.contains("kill -TERM 0"))
        assertEquals(1, script.split("echo hi").size - 1)
    }

    @Test
    fun supervisorHasNoTrapAndNoUlimit() {
        val script = GuardianScript.supervisor(600)
        assertTrue(script.contains(groupKill))
        assertTrue(script.contains(groupKillHard))
        assertFalse(script.contains("trap"))
        assertFalse(script.contains("ulimit"))
        assertFalse(script.contains("kill -0"))
    }

    @Test
    fun setupIsNotABackgroundAmpersand() {
        val setup = BudgetClassifier.classify("sh /usr/local/bin/minis-dev-setup-full")
        assertEquals(WorkClass.SETUP, setup.workClass)
        assertEquals(ShellTimeoutPolicy.SETUP_WALL_MS, setup.wallMs)
        assertEquals(ShellTimeoutPolicy.SETUP_RATE_BPS, setup.outputRateBytesPerSec)
        assertEquals(ShellTimeoutPolicy.SETUP_FSIZE_BYTES, setup.fileSizeBytes)
        val service = BudgetClassifier.classify("sleep 100 &")
        assertEquals(WorkClass.SERVICE, service.workClass)
        assertEquals(ShellTimeoutPolicy.SERVICE_RATE_BPS, service.outputRateBytesPerSec)
        assertEquals(512L * 1024L, service.outputRateBytesPerSec)
    }

    @Test
    fun callerTimeoutShortensButNeverExtends() {
        // Extension is still blocked: the class ceiling stays the authority
        // (the old maxOf(requested, minimum) bug).
        assertEquals(60_000L, BudgetClassifier.armMs("ls /tmp", 10_000_000L))
        // [T-timeout-honored] Shortening is honored: a 1s request no longer
        // rides the full 1200s BATCH wall while the log says "ignored".
        assertEquals(1_000L, BudgetClassifier.armMs("apt-get install curl", 1_000L))
        // No request → the class wall.
        assertEquals(1_200_000L, BudgetClassifier.armMs("apt-get install curl"))
        // Zero/negative request → the class wall (not a zero-second kill).
        assertEquals(1_200_000L, BudgetClassifier.armMs("apt-get install curl", 0L))
        // The setup floor survives a short request: a killed
        // minis-dev-setup-full leaves a broken rootfs.
        assertEquals(
            ShellTimeoutPolicy.LONG_RUNNING_TIMEOUT_MS,
            BudgetClassifier.armMs("minis-dev-setup-full", 60_000L),
        )
        assertEquals(WorkClass.BATCH, BudgetClassifier.classify("apt-get install curl").workClass)
    }

    /**
     * [T-cd-readonly] The exact field-report shape: `cd repo && grep …` hung
     * the full 1200s BATCH wall because `cd` failed the read-only check →
     // isHeavy escalated it to forced BATCH, and the caller's 60s request was ignored.
     */
    @Test
    fun cdPrefixedReadPipelineIsInteractiveAndNotHeavy() {
        val reported = "cd /var/minis/workspace/OpenMinis-Linux && " +
            "grep -rn \"fun LargeContentGuard\" --include=\"*.kt\" src/ | head"
        assertEquals(WorkClass.INTERACTIVE, BudgetClassifier.classify(reported).workClass)
        assertEquals(60_000L, BudgetClassifier.classify(reported).wallMs)
        assertFalse(SandboxResourceGate.isHeavy(reported))
        // …and a caller asking for even less gets it.
        assertEquals(30_000L, BudgetClassifier.armMs(reported, 30_000L))
    }

    /**
     * [T-quote-aware-split] A `|` inside quotes must not create a phantom
     * segment: `grep -n "a\|b" file` is one INTERACTIVE command, not a
     * garbage-segmented NORMAL one.
     */
    @Test
    fun quotedPipeDoesNotCreatePhantomSegments() {
        assertEquals(WorkClass.INTERACTIVE, BudgetClassifier.classify("grep -n \"a\\|b\" file").workClass)
        assertEquals(WorkClass.INTERACTIVE, BudgetClassifier.classify("echo \"a && b\"").workClass)
        // Real pipes still segment — the max rank wins.
        assertEquals(WorkClass.NORMAL, BudgetClassifier.classify("grep -n \"a\\|b\" file | sort").workClass)
    }

    @Test
    fun unsetQueueWaitIsTwoMinutes() {
        assertEquals(120_000L, SessionConcurrencyManager.queueWaitMs(0))
        assertEquals(15_000L, SessionConcurrencyManager.queueWaitMs(15))
    }
}
