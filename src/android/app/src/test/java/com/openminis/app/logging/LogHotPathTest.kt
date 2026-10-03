package com.openminis.app.logging

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-log-hotpath] Diagnostics that fire per streamed event / chunk
 * must cost nothing when nobody can read them (release build at the default
 * Info level), and AppLogger must not format timestamps for a line it does
 * not write. Source guards: unit tests run the debug variant, where
 * BuildConfig.DEBUG keeps the trace gate open.
 */
class LogHotPathTest {

    private val logger by lazy { ProductionSources.read("logging/AppLogger.kt") }

    @Test
    fun `timestamps are formatted only for a line that is written`() {
        val body = logger.substringAfter("private fun log(level: String, category: String, message: String) {")
            .substringBefore("\n    }\n")
        val format = body.indexOf("dateFormat.format(now)")
        assertTrue("formats exist", format > 0)
        assertTrue("after the enabled check", format > body.indexOf("if (!enabled) return"))
        assertTrue("after the verbose check", format > body.indexOf("if (level == \"DEBUG\" && !verbose) return"))
        assertTrue("the Date is created after the checks too", body.indexOf("val now = Date()") > body.indexOf("if (!enabled) return"))
    }

    @Test
    fun `trace builds its message only when someone can read it`() {
        assertTrue(logger.contains("get() = com.openminis.app.BuildConfig.DEBUG || (enabled && verbose)"))
        val trace = logger.substringAfter("inline fun trace(category: String, message: () -> String) {").substringBefore("\n    }")
        assertTrue(trace.trimStart().startsWith("if (!traceEnabled || isDebugMuted(category)) return"))
    }

    @Test
    fun `per-chunk ToolInputDelta logs are gated in every provider and the view model`() {
        for (path in listOf(
            "provider/openai/OpenAIProvider.kt",
            "provider/anthropic/AnthropicProvider.kt",
            "ui/chat/ChatViewModel.kt",
        )) {
            val src = ProductionSources.read(path)
            val sites = Regex("""Log\.d\("ToolChain\[(Provider|VM)\]", "[^"]*ToolInputDelta""").findAll(src).toList()
            assertEquals("$path: one ToolInputDelta log", 1, sites.size)
            val before = src.substring(0, sites[0].range.first).takeLast(160)
            assertTrue("$path: gated by traceEnabled", before.contains("AppLogger.traceEnabled) {"))
        }
    }

    @Test
    fun `per-event SSE summaries go through trace`() {
        val src = ProductionSources.read("provider/openai/OpenAIProvider.kt")
        assertTrue(src.contains("AppLogger.trace(\"OpenAIProvider\") {\n                                \"[T321] SSE delta:"))
        assertTrue(src.contains("AppLogger.trace(\"OpenAIProvider\") {\n                                \"[T321] SSE responses type="))
        assertTrue("no eager per-event debug left", !src.contains("AppLogger.debug(\n                                \"OpenAIProvider\",\n                                \"[T321] SSE"))
    }

    @Test
    fun `raw SSE payload logging stays debug-only`() {
        for (path in listOf("provider/openai/OpenAIProvider.kt", "provider/anthropic/AnthropicProvider.kt")) {
            val src = ProductionSources.read(path)
            val at = src.indexOf("\"RAW SSE: \$payload\"")
            assertTrue(path, at > 0)
            assertTrue(path, src.substring(0, at).takeLast(120).contains("if (com.openminis.app.BuildConfig.DEBUG) {"))
        }
    }
}
