package com.openminis.app.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeavyTaskAdmissionTest {
    @Test fun classifiersIncludeDecompilersButDoNotBlockOrdinaryReads() {
        assertTrue(SandboxResourceGate.isHeavy("/opt/jadx/bin/jadx app.apk"))
        assertTrue(SandboxResourceGate.isHeavy("JAVA_OPTS=-Xmx768m java -jar tool.jar"))
        assertFalse(SandboxResourceGate.isHeavy("cat report.txt"))
        assertFalse(SandboxResourceGate.isHeavy("./gradlew --stop"))
        assertFalse(SandboxResourceGate.isHeavy("pkill java"))
        // [T-cd-readonly] the field-report shape: a cd-prefixed read pipeline
        // must not consume the heavy budget — it used to, arming the 1200s
        // BATCH wall for a plain grep.
        assertFalse(SandboxResourceGate.isHeavy("cd /var/minis/workspace/repo && grep -rn foo src/ | head -5"))
        assertFalse(SandboxResourceGate.isHeavy("grep -n \"a\\|b\" file"))
    }

    @Test fun differentHeavyToolsShareOneBudgetAndCancellationReleasesIt() = runTest {
        val hold = CompletableDeferred<Unit>()
        val first = async { SandboxResourceGate.withCommandLock("jadx app.apk") { hold.await() } }
        runCurrent()
        var ran = false
        val second = async {
            SandboxResourceGate.withCommandLock("./gradlew assembleDebug") { ran = true }
        }
        runCurrent()
        assertFalse(ran)
        assertEquals("ok", SandboxResourceGate.withCommandLock("cat report.txt") { "ok" })
        first.cancelAndJoin()
        advanceTimeBy(100)
        runCurrent()
        second.await()
        assertTrue(ran)
    }

    @Test fun cancelledWaiterNeverRunsAndPackageManagersShareTheHeavyBudget() = runTest {
        val hold = CompletableDeferred<Unit>()
        val first = async { SandboxResourceGate.withCommandLock("jadx app.apk") { hold.await() } }
        runCurrent()
        var ran = false
        val waiter = async {
            SandboxResourceGate.withCommandLock("apt-get update") { ran = true }
        }
        runCurrent()
        assertFalse(ran)
        waiter.cancelAndJoin()
        hold.complete(Unit)
        first.await()
        assertFalse(ran)
        assertEquals("ok", SandboxResourceGate.withCommandLock("apt-get update") { "ok" })
    }

    @Test fun pressureWaitDoesNotStartTaskAndExplicitHeavyCoversWrappedScripts() = runTest {
        var pressure: String? = "low memory"
        var ran = false
        val job = async {
            SandboxResourceGate.withCommandLock(
                "bash long-job.sh",
                resourceClass = SandboxResourceGate.ResourceClass.HEAVY,
                pressure = { pressure },
            ) { ran = true }
        }
        runCurrent()
        assertFalse(ran)
        pressure = null
        advanceTimeBy(1000)
        runCurrent()
        job.await()
        assertTrue(ran)
    }
}
