package com.openminis.app.sandbox.offload

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-scheduled-tool-prefill] `minis-scheduled create --shell/--command/--tool`
 * parsing. Flag names and rules follow iOS (ScheduledOffload.m +
 * ScheduledPresetToolCall.fromCLI) so one instruction works on both.
 * The argv here is what the CLI handler receives after the subcommand.
 */
class ScheduledPrefillCliTest {

    private fun parse(vararg argv: String) =
        ScheduledTaskOffloadHandler.parsePrefill(
            OffloadArgs(listOf("create", *argv), booleanFlags = setOf("disabled", "h", "help")),
            label = "Weather",
        )

    private fun assertRejected(vararg argv: String, contains: String) {
        try {
            parse(*argv)
            fail("expected rejection for ${argv.toList()}")
        } catch (e: IllegalArgumentException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(contains))
        }
    }

    @Test
    fun `no prefill flags means no prefill`() {
        assertNull(parse("--time", "08:00", "--prompt", "hi"))
    }

    @Test
    fun `--shell takes the command verbatim and titles the card with the label`() {
        val call = parse("--shell", "curl -s 'wttr.in/Shanghai?format=3'")!!
        assertEquals("shell_execute", call.toolName)
        assertEquals("curl -s 'wttr.in/Shanghai?format=3'", call.args().getString("command"))
        assertEquals("Weather", call.args().getString("tool_title"))
        assertFalse("no timeout unless asked for", call.args().has("timeout"))
    }

    @Test
    fun `--command is the iOS name for the same flag`() {
        assertEquals(parse("--shell", "date"), parse("--command", "date"))
    }

    @Test
    fun `--shell-timeout and --command-timeout set the command's own timeout`() {
        assertEquals(120, parse("--shell", "make", "--shell-timeout", "2m")!!.args().getInt("timeout"))
        assertEquals(3600, parse("--command", "make", "--command-timeout", "1h")!!.args().getInt("timeout"))
        assertEquals(90, parse("--command", "make", "--command-timeout", "90")!!.args().getInt("timeout"))
    }

    @Test
    fun `--shell=value form carries a command that starts with a dash`() {
        assertEquals("-n 1", parse("--shell=-n 1")!!.args().getString("command"))
    }

    @Test
    fun `the general form accepts shell arguments as JSON`() {
        val call = parse("--tool", "shell", "--tool-args", """{"command":"make","timeout":1200}""")!!
        assertEquals("shell_execute", call.toolName)
        assertEquals(1200, call.args().getInt("timeout"))
        // --tool defaults to shell, as on iOS.
        assertEquals("make", parse("--tool-args", """{"command":"make"}""")!!.args().getString("command"))
        // A flag timeout wins over one inside --tool-args.
        assertEquals(60, parse("--tool-args", """{"command":"make","timeout":1200}""", "--shell-timeout", "60s")!!.args().getInt("timeout"))
    }

    @Test
    fun `bad input is refused with a message that says what to do`() {
        assertRejected("--shell", contains = "needs a value")
        assertRejected("--shell", "ls", "--tool-args", """{"command":"ls"}""", contains = "not both")
        assertRejected("--tool", "browser_use", "--tool-args", "{}", contains = "not supported as a prefilled tool yet")
        assertRejected("--tool", "shell", "--tool-args", "[1,2]", contains = "JSON object")
        assertRejected("--tool", "shell", "--tool-args", "{}", contains = "needs a \"command\" string")
        assertRejected("--tool-args", """{"command":"ls","cwd":"/"}""", contains = "unsupported key(s) cwd")
        assertRejected("--tool-args", """{"command":"ls","timeout":"soon"}""", contains = "number of seconds")
        assertRejected("--shell-timeout", "2m", contains = "needs a shell command")
        assertRejected("--shell", "ls", "--shell-timeout", "soon", contains = "duration like 90s")
        assertRejected("--shell", "ls", "--shell-timeout", "2h", contains = "between 1s and 1h")
        assertRejected("--shell", "x".repeat(16_001), contains = "longer than 16000")
    }

    @Test
    fun `durations parse like iOS`() {
        val p = ScheduledTaskOffloadHandler.Companion::parseDurationSec
        assertEquals(45, p("45"))
        assertEquals(45, p("45s"))
        assertEquals(150, p("2.5m"))
        assertEquals(7200, p("2H"))
        assertNull(p("2d"))
        assertNull(p("m"))
    }

    @Test
    fun `create wires the prefill into the task and refuses it on rerun`() {
        val src = ProductionSources.read("sandbox/offload/ScheduledTaskOffloadHandler.kt")
        assertTrue(src.contains("prefillToolCall = prefill,"))
        assertTrue(src.contains("if (prefill != null && target is ScheduledTargetMode.RerunMessage)"))
        assertTrue("list output shows it", src.contains("t.prefillToolCall?.let { put(\"prefillTool\", it.toJson()) }"))
        // [T-android-scheduled-triggers] --command leads, as on iOS (3b69e3d00);
        // --shell stays accepted as the alias.
        assertTrue("help documents the iOS names", src.contains("PREFILLED TOOL CALL (--command, alias --shell)"))
    }

    // ─── [T-android-scheduled-duration-finite] (iOS 40d74b5df) ───────────

    @Test
    fun `a timeout past the Int range is rejected, not wrapped`() {
        // 2^32 + 60: Math.round(...).toInt() used to wrap this to 60.
        assertRejected("--prompt", "p", "--shell", "date", "--shell-timeout", "4294967356", contains = "between 1s and 1h")
        assertRejected("--prompt", "p", "--shell", "date", "--shell-timeout", "99999999999999999999h", contains = "between 1s and 1h")
    }

    @Test
    fun `a tool-args timeout past the Int range is rejected, not wrapped`() {
        assertRejected("--prompt", "p", "--tool-args", "{\"command\":\"date\",\"timeout\":4294967356}", contains = "between 1s and 1h")
        assertRejected("--prompt", "p", "--tool-args", "{\"command\":\"date\",\"timeout\":1e20}", contains = "between 1s and 1h")
    }

    @Test
    fun `saturating seconds never wraps and maps NaN and infinities out of range`() {
        assertEquals(60, ScheduledTaskOffloadHandler.saturatingSeconds(60.4))
        assertEquals(Int.MAX_VALUE, ScheduledTaskOffloadHandler.saturatingSeconds(4294967356.0))
        assertEquals(Int.MAX_VALUE, ScheduledTaskOffloadHandler.saturatingSeconds(Double.POSITIVE_INFINITY))
        assertEquals(Int.MIN_VALUE, ScheduledTaskOffloadHandler.saturatingSeconds(Double.NaN))
        assertEquals(Int.MIN_VALUE, ScheduledTaskOffloadHandler.saturatingSeconds(Double.NEGATIVE_INFINITY))
    }
}
