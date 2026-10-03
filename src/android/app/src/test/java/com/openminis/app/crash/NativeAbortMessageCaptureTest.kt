package com.openminis.app.crash

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [OpenMinis#363] The native handler must READ the abort message, not describe
 * where to find it.
 *
 * Before this change `crash_handler.cpp` wrote a paragraph telling the reader
 * to go look in logcat or the tombstone, and never read the message itself —
 * so the one file a user can actually retrieve carried everything except the
 * reason the process died. That is a failure mode with no build-time signal:
 * the handler still compiles, still writes a report, and the report simply
 * lacks a field. These assertions are the only thing standing between a future
 * tidy-up and a silent return to that state.
 *
 * Source-level by necessity — the unit-test JVM cannot raise SIGABRT in a
 * bionic process, and asserting on C++ through JNI would need an on-device
 * instrumentation run.
 */
class NativeAbortMessageCaptureTest {

    private val src: String by lazy {
        val javaRoot = ProductionSources.mainRoot()
            ?: error("production source root not found")
        // .../src/main/java/com/openminis/app -> .../src/main/cpp
        val cpp = File(javaRoot.parentFile.parentFile.parentFile.parentFile, "cpp/crash_handler.cpp")
        require(cpp.isFile) { "crash_handler.cpp missing: ${cpp.absolutePath}" }
        cpp.readText()
    }

    @Test
    fun `the liblog aborter hook is installed`() {
        assertTrue(
            "__android_log_set_aborter is the supported way to see a LOG_ALWAYS_FATAL message",
            src.contains("__android_log_set_aborter"),
        )
        assertTrue(
            "resolved via dlsym because minSdk 26 < the symbol's __INTRODUCED_IN(30)",
            src.contains("dlsym(RTLD_DEFAULT, \"__android_log_set_aborter\")"),
        )
    }

    @Test
    fun `the bionic global is read as the fallback`() {
        // This is the path that covers plain abort(), Scudo and ART aborts —
        // i.e. most of what is actually seen in the field. The aborter hook
        // alone would miss all of them.
        assertTrue(src.contains("__abort_message"))
    }

    @Test
    fun `the non-public read is guarded at every step`() {
        // A diagnostic that dereferences a private bionic layout must fail
        // closed. Losing the message is acceptable; faulting inside the crash
        // handler destroys the report it was about to produce.
        assertTrue("null slot", src.contains("if (slot == nullptr) return nullptr;"))
        assertTrue("null block", src.contains("if (block == nullptr) return nullptr;"))
        assertTrue("sane size", src.contains("block->size > 64 * 1024"))
    }

    @Test
    fun `the message is written into the report`() {
        assertTrue(src.contains("\"\\nAbort message: \""))
    }

    @Test
    fun `the read happens after the summary is already on disk`() {
        // Same discipline the file already applies to _Unwind_Backtrace and
        // dladdr: dlsym is not async-signal-safe, so it must never run before
        // the bytes that matter have been written.
        val summaryWrite = src.indexOf("written += w;")
        // The CALL SITE, not the definition — the helper is declared far above
        // the handler, so a plain indexOf would find the declaration and pass
        // regardless of where it is actually invoked.
        val abortRead = src.indexOf("const char* amsg = abort_message_or_null();")
        assertTrue("summary write not found", summaryWrite > 0)
        assertTrue("abort message call site not found", abortRead > 0)
        assertTrue(
            "abort message must be read AFTER the summary is flushed",
            abortRead > summaryWrite,
        )
    }

    @Test
    fun `the advice text survives as the fallback`() {
        // A runtime that aborts without bionic — the Go runtime in
        // libgojni.so is the live example — leaves no message at all. The
        // pointer to logcat/tombstone/exitinfo is still the best output there.
        assertTrue(src.contains("(none captured)"))
        assertTrue(src.contains("exitinfo-*.log"))
    }
}
