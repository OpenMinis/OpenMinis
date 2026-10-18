package com.openminis.app.ui.settings

import com.openminis.app.data.db.UsageRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Usage page used to bucket rows by model id alone, so two provider
 * instances serving the same model id under the same display name (two
 * relays, official plus relay) had their billed tokens silently summed into
 * one row. The bucket key must include the provider instance.
 */
class UsageAggregationTest {
    private fun row(
        instance: String?,
        modelId: String = "claude-sonnet-4-5",
        name: String = "Claude Sonnet 4.5",
        provider: String = "anthropic",
        input: Long = 100,
    ) = UsageRecord(
        modelId = modelId,
        modelDisplayName = name,
        providerType = provider,
        providerInstanceId = instance,
        hasSnapshot = true,
        tokenUsage = """{"inputTokens":$input,"outputTokens":10}""",
        createdAt = 1_700_000_000_000,
        sessionId = "s-$instance",
    )

    private fun models(agg: UsageAggregation) = agg.groups.flatMap { it.models }

    @Test
    fun sameModelOnTwoInstancesDoesNotMerge() {
        val agg = aggregateUsageRecords(
            listOf(row("inst-a"), row("inst-b", input = 200)),
            emptyMap(),
            mapOf("inst-a" to "Relay A", "inst-b" to "Relay B"),
        )
        assertEquals(2, models(agg).size)
        assertEquals(setOf(100L, 200L), models(agg).map { it.inputTokens }.toSet())
        // [T-usage-provider-subtitle] The display name stays clean; the two
        // rows are told apart by the SUBTITLE, which carries the provider
        // instance label (unique by construction) instead of appending it to
        // the model name.
        assertEquals(
            setOf("Claude Sonnet 4.5"),
            models(agg).map { it.displayName }.toSet(),
        )
        assertEquals(
            setOf("Relay A", "Relay B"),
            models(agg).map { it.subtitle }.toSet(),
        )
        assertEquals(300L, agg.grandTotal.totalInput)
    }

    @Test
    fun sameInstanceSameModelStillMerges() {
        val agg = aggregateUsageRecords(
            listOf(row("inst-a"), row("inst-a", input = 50)),
            emptyMap(),
            emptyMap(),
        )
        assertEquals(1, models(agg).size)
        assertEquals(150L, models(agg).single().inputTokens)
    }

    @Test
    fun legacyRowsWithoutInstanceFallBackToProviderType() {
        val agg = aggregateUsageRecords(
            listOf(row(null, provider = "anthropic"), row(null, provider = "openAI")),
            emptyMap(),
            emptyMap(),
        )
        assertEquals(2, models(agg).size)
    }

    @Test
    fun uniqueNamesAreNotSuffixed() {
        val agg = aggregateUsageRecords(
            listOf(row("inst-a")),
            emptyMap(),
            mapOf("inst-a" to "Relay A"),
        )
        assertEquals("Claude Sonnet 4.5", models(agg).single().displayName)
        assertEquals("Relay A", models(agg).single().subtitle)
    }

    /**
     * [T-usage-provider-subtitle] A row written before the instance column
     * existed has no label — the subtitle must fall back to the provider type
     * rather than rendering empty.
     */
    @Test
    fun legacyRowSubtitleFallsBackToProviderType() {
        val agg = aggregateUsageRecords(
            listOf(row(null, provider = "anthropic")),
            emptyMap(),
            emptyMap(),
        )
        assertEquals("Anthropic", models(agg).single().subtitle)
    }
}
