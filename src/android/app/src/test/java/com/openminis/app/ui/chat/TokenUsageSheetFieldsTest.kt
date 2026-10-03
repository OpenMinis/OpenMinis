package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-token-usage-cache-hit-rate] / [T-android-token-usage-output-speed]
 *
 * The "…" → Token Usage sheet was missing two rows iOS has shown all along:
 * Cache Hit Rate, and the whole Speed section (Output Speed). Reported as
 * "Android 的 Token Usage 不显示缓存率".
 *
 * Cache Hit Rate was pure UI — `input`/`cacheRead`/`cacheWrite` were already
 * aggregated and the sheet already computed the same denominator for its
 * "Input (incl. cache)" row. Output Speed needed a data source: Android had
 * none, and unlike iOS — which keeps `sessionStreamDuration` in memory on the
 * view model and loses it when the VM goes away — Android recomputes the whole
 * sheet from persisted `token_usage` rows, so the duration is persisted as a
 * new `streamMs` key and the average survives a restart.
 *
 * `SessionTokenStats` is a plain data class, so both derivations are tested
 * directly; the wiring that produces `streamMs` is pinned as source facts.
 */
class TokenUsageSheetFieldsTest {

    private fun stats(
        input: Long = 0,
        output: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        streamMs: Long = 0,
        streamOutput: Long = 0,
    ) = ChatViewModel.SessionTokenStats(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        context = 0,
        loopCount = 0,
        streamMs = streamMs,
        streamOutput = streamOutput,
    )

    // ── Cache Hit Rate ─────────────────────────────────────────────────────

    @Test
    fun `cache hit rate is cache read over total input including cache`() {
        // iOS: cacheRead / (input + cacheRead + cacheWrite) * 100 — the same
        // denominator as the sheet's "Input (incl. cache)" row.
        val s = stats(input = 1_000, cacheRead = 3_000, cacheWrite = 1_000)
        assertEquals(60.0, s.cacheHitRate!!, 0.001)
    }

    @Test
    fun `a fully cached session reports one hundred percent`() {
        assertEquals(100.0, stats(cacheRead = 5_000).cacheHitRate!!, 0.001)
    }

    @Test
    fun `no cache reads hides the row instead of showing zero`() {
        // A provider that does no caching must not look like a 0% cache MISS.
        assertNull("no cache read → hidden", stats(input = 10_000).cacheHitRate)
        assertNull("write-only (first turn) → hidden", stats(input = 10_000, cacheWrite = 2_000).cacheHitRate)
        assertNull("an empty session → hidden", stats().cacheHitRate)
    }

    @Test
    fun `cache write counts in the denominator, so the rate is not inflated`() {
        // Dropping cacheWrite would report 50% here instead of 25% — the tokens
        // written to cache were still billed as input this session.
        val s = stats(input = 4_000, cacheRead = 4_000, cacheWrite = 8_000)
        assertEquals(25.0, s.cacheHitRate!!, 0.001)
    }

    // ── Output Speed ───────────────────────────────────────────────────────

    @Test
    fun `output speed is streamed tokens over streamed seconds`() {
        // 900 tokens in 30s = 30 tok/s.
        assertEquals(30.0, stats(streamMs = 30_000, streamOutput = 900).outputTokensPerSecond!!, 0.001)
    }

    @Test
    fun `speed sums across turns, it is not a per-turn average`() {
        // A 10s/500-token turn plus a 30s/300-token turn is 800/40 = 20 tok/s,
        // NOT (50 + 10) / 2 = 30. The loader accumulates both sums.
        assertEquals(20.0, stats(streamMs = 40_000, streamOutput = 800).outputTokensPerSecond!!, 0.001)
    }

    @Test
    fun `speed is null until something measurable was recorded`() {
        // Sessions whose rows all predate `streamMs` must render "—", never
        // "0.0 tok/s", which would read as a stalled model.
        assertNull("no duration recorded", stats(output = 5_000).outputTokensPerSecond)
        assertNull("duration but no output", stats(streamMs = 10_000).outputTokensPerSecond)
        assertNull("fresh session", stats().outputTokensPerSecond)
    }

    @Test
    fun `speed uses the matched output, not the session total`() {
        // Legacy rows contribute `output` but no `streamMs`. If the rate used
        // the session total it would count tokens whose duration is unknown:
        // 5000 total / 10s = 500 tok/s, wildly wrong. Only the matched pair
        // counts → 200/10 = 20 tok/s.
        val s = stats(output = 5_000, streamMs = 10_000, streamOutput = 200)
        assertEquals(20.0, s.outputTokensPerSecond!!, 0.001)
    }

    // ── the wiring ─────────────────────────────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private val vmSrc by lazy { src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }
    private val sheetSrc by lazy { src("src/main/java/com/openminis/app/ui/chat/TokenUsageSheet.kt") }

    @Test
    fun `stream duration is persisted with the turn's usage row`() {
        assertTrue(
            "the usage JSON must carry streamMs, or the speed cannot survive a restart",
            vmSrc.contains(""""streamMs":${'$'}streamMs"""),
        )
        assertTrue(
            "the loader must read it back",
            vmSrc.contains("""obj.optLong("streamMs", 0L)"""),
        )
        assertTrue(
            "duration must only count when the turn also produced output",
            vmSrc.contains("if (turnMs > 0 && turnOutput > 0)"),
        )
    }

    @Test
    fun `the duration is accumulated in a finally block`() {
        // A turn that streamed 20s and then failed still spent those seconds.
        // Accumulating only on the success path would silently under-report.
        val region = vmSrc.substringAfter("stream ENDED-WITHOUT-CHUNK").substringBefore("} catch (e: Exception)")
        assertTrue(
            "turnStreamMs must be accumulated where the stream always unwinds",
            region.contains("turnStreamMs += android.os.SystemClock.elapsedRealtime() - streamStartMs"),
        )
        assertTrue(
            "must use a monotonic clock — wall time can jump backwards",
            vmSrc.contains("turnStreamMs += android.os.SystemClock.elapsedRealtime()"),
        )
    }

    @Test
    fun `both rows are wired into the sheet`() {
        assertTrue("cache hit rate row", sheetSrc.contains("R.string.token_usage_cache_hit_rate"))
        assertTrue("speed section", sheetSrc.contains("R.string.token_usage_section_speed"))
        assertTrue("output speed row", sheetSrc.contains("R.string.token_usage_output_speed"))
        // Hit rate hides itself; speed shows "—". The two must not be swapped.
        assertTrue("hit rate is conditional", sheetSrc.contains("s?.cacheHitRate?.let {"))
        assertTrue("speed falls back to a dash", sheetSrc.contains("""else "—""""))
    }

    @Test
    fun `every new string exists in all three shipped locales`() {
        // A missing translation silently falls back to English at runtime,
        // which review cannot see.
        val keys = listOf(
            "token_usage_cache_hit_rate",
            "token_usage_section_speed",
            "token_usage_output_speed",
        )
        for (locale in listOf("values", "values-zh", "values-zh-rTW")) {
            val xml = src("src/main/res/$locale/strings.xml")
            for (key in keys) {
                assertTrue("$locale is missing $key", xml.contains("""<string name="$key">"""))
            }
        }
    }
}
