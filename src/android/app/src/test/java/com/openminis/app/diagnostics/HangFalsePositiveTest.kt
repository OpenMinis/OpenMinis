package com.openminis.app.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-hang-false-positive] Simple streaming replies showed
 * "Performance protection: simplified rendering while streaming". The notice
 * came from a render breaker keyed on the GLOBAL hang count, and on the
 * reporting Pixel 6 that count was fed by:
 *  - false positives: wall-clock gaps from deep sleep ("41509s") and a
 *    post-boot clock correction ("16521299s", main thread idle);
 *  - real stalls OUTSIDE the renderer: 21 s of the launch resolver waiting on
 *    ProviderRepository.configLock behind a saveConfig.
 * None of the 33 recorded samples were in streaming rendering.
 */
class HangFalsePositiveTest {

    private fun frame(cls: String, method: String) = StackTraceElement(cls, method, "F.kt", 1)

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `a main thread parked in the looper is idle, not hung`() {
        val idle = arrayOf(
            frame("android.os.MessageQueue", "nativePollOnce"),
            frame("android.os.MessageQueue", "next"),
            frame("android.os.Looper", "loopOnce"),
        )
        assertTrue(HangDetector.isIdleMainStack(idle))
    }

    @Test
    fun `a main thread doing work or waiting on a lock is not idle`() {
        val lockWait = arrayOf(
            frame("com.openminis.app.data.repository.ProviderRepository", "getInstances"),
            frame("com.openminis.app.ui.navigation.AppNavigationKt\$AppNavigation\$2\$1", "invokeSuspend"),
        )
        val textLayout = arrayOf(frame("android.text.StaticLayout", "generate"))
        assertFalse(HangDetector.isIdleMainStack(lockWait))
        assertFalse(HangDetector.isIdleMainStack(textLayout))
        assertFalse(HangDetector.isIdleMainStack(emptyArray()))
    }

    @Test
    fun `gaps are measured on the monotonic clock`() {
        val body = src("src/main/java/com/openminis/app/diagnostics/HangDetector.kt")
        val watch = body.substringAfter("private fun watchLoop()").substringBefore("private fun writeStallSample(")
        assertTrue(watch.contains("val now = SystemClock.uptimeMillis()"))
        assertFalse("the wall clock must not measure a gap", watch.contains("System.currentTimeMillis()"))
        val heartbeat = body.substringAfter("private fun scheduleHeartbeat()").substringBefore("private fun watchLoop()")
        assertTrue(heartbeat.contains("lastHeartbeatAt.set(SystemClock.uptimeMillis())"))
    }

    @Test
    fun `an implausible gap or an idle main thread is rejected before it is counted`() {
        val watch = src("src/main/java/com/openminis/app/diagnostics/HangDetector.kt")
            .substringAfter("private fun watchLoop()").substringBefore("private fun writeStallSample(")
        val guard = watch.indexOf("since > MAX_PLAUSIBLE_GAP_MS")
        val idle = watch.indexOf("isIdleMainStack(mainThreadStack())")
        val count = watch.indexOf("recordHang(durationMs = since)")
        assertTrue(guard in 0 until count && idle in 0 until count)
    }

    @Test
    fun `the render breaker is gone from the watchdog and the renderer`() {
        assertFalse(src("src/main/java/com/openminis/app/diagnostics/HangDetector.kt").contains("renderBreakerActive"))
        val guard = src("src/main/java/com/openminis/app/ui/chat/LargeContentGuard.kt")
        assertFalse(
            "streaming degrade must depend on size only",
            guard.contains("renderBreakerActive") || guard.contains("diagnostics.HangDetector"),
        )
        assertTrue(guard.contains("if (content.length > STREAM_DEGRADE_CHARS)"))
    }

    @Test
    fun `reading provider instances never waits on configLock`() {
        val repo = src("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt")
        assertTrue(repo.contains("val instances: List<ProviderInstance>\n        get() = _config.value.instances.toList()"))
    }

    @Test
    fun `the launch resolver waits for the config by suspending, bounded`() {
        val nav = src("src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt")
        val wait = nav.indexOf("withTimeoutOrNull(2_000) { providerRepository.awaitConfigLoaded() }")
        val read = nav.indexOf("val hasAnyProvider = providerRepository.instances.isNotEmpty()")
        assertTrue(wait in 0 until read)
    }
}
