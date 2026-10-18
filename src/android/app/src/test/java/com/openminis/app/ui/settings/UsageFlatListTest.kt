package com.openminis.app.ui.settings

import com.openminis.app.data.db.UsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-usage-flat-list] The usage page renders one flat list sorted by input, and
 * each row carries its share of the CURRENTLY SELECTED window.
 *
 * Both properties were previously implicit in the Composable: the row order came
 * from provider grouping and the share was divided inline at render time. Moving
 * them into the aggregation makes the window-driven behaviour assertable — and
 * the window IS the thing users change, so a regression here re-orders the list
 * or shows stale percentages the moment they tap a chip.
 */
class UsageFlatListTest {
    private fun row(
        modelId: String,
        name: String = modelId,
        provider: String = "openAI",
        instance: String? = null,
        input: Long,
        output: Long = 0,
        createdAt: Long = 1_700_000_000_000,
    ) = UsageRecord(
        modelId = modelId,
        modelDisplayName = name,
        providerType = provider,
        providerInstanceId = instance,
        hasSnapshot = true,
        tokenUsage = """{"inputTokens":$input,"outputTokens":$output}""",
        createdAt = createdAt,
        sessionId = "s-$modelId-$instance",
    )

    @Test
    fun rowsAreSortedByInputDescending() {
        val agg = aggregateUsageRecords(
            listOf(
                row("small", input = 10),
                row("biggest", input = 500),
                row("middle", input = 80),
            ),
            emptyMap(),
            emptyMap(),
        )
        assertEquals(
            listOf("biggest", "middle", "small"),
            agg.rows.map { it.stats.modelId },
        )
    }

    @Test
    fun shareIsAFractionOfTheWindowTotal() {
        val agg = aggregateUsageRecords(
            listOf(row("a", input = 30), row("b", input = 70)),
            emptyMap(),
            emptyMap(),
        )
        val byId = agg.rows.associateBy { it.stats.modelId }
        assertEquals(0.7f, byId.getValue("b").shareOfWindow, 0.0001f)
        assertEquals(0.3f, byId.getValue("a").shareOfWindow, 0.0001f)
        assertEquals(1f, agg.rows.sumOf { it.shareOfWindow.toDouble() }.toFloat(), 0.0001f)
    }

    /**
     * The point of computing the share from the FILTERED records: a model that
     * dominates a 7-day window must show a smaller percentage in a 30-day
     * window once the older records (which belong to another model) come in.
     */
    @Test
    fun shareShrinksWhenTheWindowWidens() {
        val now = 1_700_000_000_000L
        val day = 24 * 60 * 60 * 1000L
        val records = listOf(
            // Dominant recently…
            row("recent", input = 900, createdAt = now - 1 * day),
            // …but an older model dwarfs the longer window.
            row("old", input = 9000, createdAt = now - 20 * day),
        )
        val models = emptyMap<String, Pair<String, String>>()

        val week = aggregateUsageRecords(
            filterUsageByRange(records, UsageTimeRange.LAST_7, now),
            models,
            emptyMap(),
        )
        val weekShare = week.rows.first { it.stats.modelId == "recent" }.shareOfWindow
        assertEquals(1f, weekShare, 0.0001f) // only model inside 7 days

        val month = aggregateUsageRecords(
            filterUsageByRange(records, UsageTimeRange.LAST_30, now),
            models,
            emptyMap(),
        )
        val monthShare = month.rows.first { it.stats.modelId == "recent" }.shareOfWindow
        assertEquals(900f / 9900f, monthShare, 0.0001f)
        assertTrue("widening the window must dilute the recent model", monthShare < weekShare)
    }

    @Test
    fun emptyWindowYieldsNoRowsAndZeroShares() {
        val agg = aggregateUsageRecords(emptyList(), emptyMap(), emptyMap())
        assertTrue(agg.rows.isEmpty())
        assertEquals(0L, agg.grandTotal.totalInput)
    }

    @Test
    fun rowsKeepProviderIdentityForDuplicateModelNames() {
        val agg = aggregateUsageRecords(
            listOf(
                row("glm-5.3", name = "GLM-5.3", provider = "openAI", instance = "inst-a", input = 100),
                row("glm-5.3", name = "GLM-5.3", provider = "openAI", instance = "inst-b", input = 200),
            ),
            emptyMap(),
            mapOf("inst-a" to "智谱 AI", "inst-b" to "天翼云"),
        )
        // Two rows, both named GLM-5.3. The SUBTITLE is what tells them apart
        // — the model name itself stays clean.
        assertEquals(2, agg.rows.size)
        assertEquals(
            setOf("智谱 AI", "天翼云"),
            agg.rows.map { it.stats.subtitle }.toSet(),
        )
        assertEquals(setOf("GLM-5.3"), agg.rows.map { it.stats.displayName }.toSet())
    }
}
