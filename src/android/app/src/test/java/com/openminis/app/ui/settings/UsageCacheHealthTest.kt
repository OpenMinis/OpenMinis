package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-usage-cache-health] The 「缓存健康」 card's diagnostic arithmetic.
 *
 * The per-model hit rate already existed inside a row's expanded details; this
 * aggregate answers a different question — which VENDOR actually reports
 * caching. A relay that never forwards `cached_tokens` looks uncached while
 * the user pays full price for the entire prompt, so the card must flag that
 * case (`looksUnreported`) instead of rendering a misleading 0%.
 */
class UsageCacheHealthTest {
    private fun row(
        label: String,
        input: Long,
        cacheRead: Long,
        cacheCreation: Long = 0,
        provider: String = "openAI",
    ) = UsageRow(
        stats = ModelStats(
            modelId = "model-$label",
            displayName = "Model $label",
            provider = provider,
            attribution = Attribution.MEASURED,
            instanceLabel = label,
            inputTokens = input,
            cacheReadTokens = cacheRead,
            cacheCreationTokens = cacheCreation,
        ),
        shareOfWindow = 0f,
    )

    @Test
    fun `groups by provider label and folds cache numbers`() {
        val out = aggregateCacheByProvider(
            listOf(
                row("天翼云", input = 200, cacheRead = 800), // totalInput 1000
                row("天翼云", input = 100, cacheRead = 400), // totalInput 500
                row("星云", input = 300, cacheRead = 100),   // totalInput 400
            ),
        )
        assertEquals(listOf("天翼云", "星云"), out.map { it.label })
        // 天翼云: (800+400) / (1000+500) = 80.0%
        assertEquals(80.0, out[0].hitRate!!, 0.01)
        // 星云: 100 / 400 = 25.0%
        assertEquals(25.0, out[1].hitRate!!, 0.01)
    }

    @Test
    fun `sorts providers by input volume`() {
        val out = aggregateCacheByProvider(
            listOf(
                row("small", input = 10, cacheRead = 5),
                row("big", input = 990, cacheRead = 10),
            ),
        )
        assertEquals(listOf("big", "small"), out.map { it.label })
    }

    @Test
    fun `input without any cached tokens is flagged as unreported`() {
        val out = aggregateCacheByProvider(listOf(row("黑洞中转", input = 5000, cacheRead = 0)))
        assertEquals(1, out.size)
        assertNull(out[0].hitRate)
        assertTrue(out[0].looksUnreported)
    }

    @Test
    fun `provider with cached tokens is not flagged`() {
        val out = aggregateCacheByProvider(listOf(row("正常", input = 100, cacheRead = 900)))
        assertFalse(out[0].looksUnreported)
        assertEquals(90.0, out[0].hitRate!!, 0.01)
    }

    @Test
    fun `zero usage row yields no hit rate and no flag`() {
        val out = aggregateCacheByProvider(listOf(row("空", input = 0, cacheRead = 0)))
        assertNull(out[0].hitRate)
        assertFalse(out[0].looksUnreported)
    }

    @Test
    fun `cache creation is part of the hit-rate denominator`() {
        // Same formula as GrandTotal.cacheHitRate: read / (input + read + creation).
        val out = aggregateCacheByProvider(
            listOf(row("带写入", input = 100, cacheRead = 400, cacheCreation = 500)),
        )
        // 400 / 1000 = 40.0%
        assertEquals(40.0, out[0].hitRate!!, 0.01)
    }

    @Test
    fun `empty input list yields no rows`() {
        assertTrue(aggregateCacheByProvider(emptyList()).isEmpty())
    }
}
