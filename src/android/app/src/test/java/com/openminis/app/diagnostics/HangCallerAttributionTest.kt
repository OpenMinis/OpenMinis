package com.openminis.app.diagnostics

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-hang-caller-attribution] A main-thread stall must name who hung,
 * not only which framework call was slow.
 *
 * A user device (zzz, OnePlus PKG110, 2026-09-22 14:04:40) froze for 12.4 s in
 * text layout and was killed. Every JankDiag sample in the daily log was five
 * framework frames — `Paint.nGetRunCharacterAdvance`, `LineBreaker
 * .nComputeLineBreaks`, `StaticLayout.generate`… — so the log proved the main
 * thread was laying out text and said nothing about which screen or component
 * asked for it. The investigation could narrow it to "a dialog opened from the
 * session list" and no further.
 *
 * These pin the two fields that would have closed that gap: the screen on top
 * and the first app frame beneath the framework noise.
 */
class HangCallerAttributionTest {

    @After fun resetScreen() = HangDetector.noteScreen(null)

    private fun frame(cls: String, method: String, line: Int) =
        StackTraceElement(cls, method, "${cls.substringAfterLast('.')}.kt", line)

    /** The shape of the real stall: framework on top, our caller underneath. */
    private val textLayoutStall = arrayOf(
        frame("android.graphics.Paint", "nGetRunCharacterAdvance", -2),
        frame("android.text.TextLine", "handleText", 1371),
        frame("android.text.StaticLayout", "generate", 969),
        frame("androidx.compose.ui.text.android.TextLayout", "<init>", 212),
        frame("androidx.compose.ui.text.AndroidParagraph", "<init>", 140),
        frame("com.openminis.app.ui.sessions.SessionListScreenKt\$SessionRow\$1", "invoke", 2947),
        frame("com.openminis.app.ui.sessions.SessionListScreenKt", "SessionList", 1200),
    )

    @Test
    fun `the first app frame is found beneath the framework frames`() {
        assertEquals(
            "must skip every android.* / androidx.* frame and return the topmost of ours",
            "ui.sessions.SessionListScreenKt\$SessionRow\$1.invoke:2947",
            HangDetector.firstAppFrame(textLayoutStall),
        )
    }

    @Test
    fun `a pure framework stack reports no app frame rather than a wrong one`() {
        // Better an honest "none" than attributing the stall to a framework class.
        assertNull(HangDetector.firstAppFrame(textLayoutStall.take(5).toTypedArray()))
        assertNull(HangDetector.firstAppFrame(emptyArray()))
    }

    @Test
    fun `the jank line carries screen and app frame`() {
        val fields = HangDetector.attributionFields("sessions", textLayoutStall)
        assertEquals(
            " screen=sessions appFrame=ui.sessions.SessionListScreenKt\$SessionRow\$1.invoke:2947",
            fields,
        )
        assertEquals(
            " screen=sessions appFrame=none",
            HangDetector.attributionFields("sessions", emptyArray()),
        )
    }

    @Test
    fun `the published screen is what the sample reports`() {
        HangDetector.noteScreen("chat/{sessionId}")
        assertEquals("chat/{sessionId}", HangDetector.currentScreen)
        // A missing or blank route must not print an empty `screen=` field.
        HangDetector.noteScreen("  ")
        assertEquals("unknown", HangDetector.currentScreen)
        HangDetector.noteScreen(null)
        assertEquals("unknown", HangDetector.currentScreen)
    }

    @Test
    fun `the stall log keeps enough frames to leave the compose text stack`() {
        // 25 frames stopped inside Compose's own layout machinery. The deeper
        // log is what lets a text-layout stall reach an app frame at all.
        assertTrue(
            "STALL_STACK_DEPTH must stay well above the old 25-frame cut",
            HangDetector.STALL_STACK_DEPTH >= 100,
        )
    }

    // ── The live paths must actually use the pieces above ─────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `the sampler writes the deeper stack and appends attribution`() {
        val body = src("src/main/java/com/openminis/app/diagnostics/HangDetector.kt")
            .substringAfter("private fun writeStallSample(")
            .substringBefore("\n    private fun recordHang(")
        assertTrue("stall log must use STALL_STACK_DEPTH", body.contains("mainStack.take(STALL_STACK_DEPTH)"))
        assertTrue("the old 25-frame cut must be gone", !body.contains("take(25)"))
        assertTrue(
            "the JankDiag line must include the attribution fields",
            body.contains("attributionFields(currentScreen, mainStack)") && body.contains("\$attribution\""),
        )
    }

    @Test
    fun `main activity publishes the current route`() {
        assertTrue(
            src("src/main/java/com/openminis/app/MainActivity.kt")
                .contains("HangDetector.noteScreen(entry.destination.route)"),
        )
    }
}
