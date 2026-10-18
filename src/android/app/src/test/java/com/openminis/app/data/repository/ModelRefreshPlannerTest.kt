package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh decision [ProviderRepository.replaceEntries] delegates to.
 *
 * These assertions are the whole reason [ModelRefreshPlanner] is a pure object:
 * carrying `uuid` / `overrides` / `isHidden` forward across a refresh is
 * invisible when it breaks — the entry still exists, it just quietly loses
 * everything the user did to it (a rename, a hidden flag, a per-model override)
 * the next time the model list refreshes.
 */
class ModelRefreshPlannerTest {

    private fun model(id: String, displayName: String = id) =
        LLMModel(id = id, displayName = displayName, provider = "openai")

    private fun entry(
        wire: String,
        uuid: String = "uuid-$wire",
        custom: Boolean = false,
        hidden: Boolean = false,
        overrides: ModelOverrides = ModelOverrides(),
    ) = ModelEntry(
        providerInstanceId = "inst-1",
        baseModel = model(wire),
        overrides = overrides,
        isCustom = custom,
        isHidden = hidden,
        uuid = uuid,
    )

    @Test
    fun refreshCarriesForwardUuidOverridesAndHiddenFlag() {
        // The user renamed the model and hid it; a refresh must not undo either.
        val prior = entry(
            "gpt-5",
            uuid = "keep-me",
            hidden = true,
            overrides = ModelOverrides(displayName = "My GPT"),
        )
        val plan = ModelRefreshPlanner.plan(
            instanceId = "inst-1",
            models = listOf(model("gpt-5")),
            existing = listOf(prior),
            instanceBaseURL = null,
        )

        assertEquals(1, plan.refreshed.size)
        val refreshed = plan.refreshed.single()
        assertEquals("keep-me", refreshed.uuid)
        assertEquals("My GPT", refreshed.model.displayName)
        assertTrue("hidden flag must survive a refresh", refreshed.isHidden)
        assertEquals(false, refreshed.isCustom)
    }

    @Test
    fun brandNewModelGetsFreshUuidAndNoOverrides() {
        val plan = ModelRefreshPlanner.plan(
            instanceId = "inst-1",
            models = listOf(model("gpt-5")),
            existing = emptyList(),
            instanceBaseURL = null,
        )
        val refreshed = plan.refreshed.single()
        assertNotEquals("", refreshed.uuid)
        assertTrue(refreshed.overrides.isEmpty)
        assertEquals(false, refreshed.isHidden)
    }

    @Test
    fun modelTheProviderStoppedReturningIsDropped() {
        val plan = ModelRefreshPlanner.plan(
            instanceId = "inst-1",
            models = listOf(model("gpt-5")),
            existing = listOf(entry("gpt-5"), entry("retired-model")),
            instanceBaseURL = null,
        )
        assertEquals(listOf("gpt-5"), plan.refreshed.map { it.model.id })
        assertTrue("a dropped API entry must not be preserved", plan.remainingCustom.isEmpty())
        assertTrue(plan.preservedVoice.isEmpty())
    }

    @Test
    fun customEntrySurvivesWhenProviderDoesNotReturnIt() {
        // Custom entries exist only locally; a refresh that omits them would
        // delete the user's own model.
        val custom = entry("my-local-model", uuid = "custom-1", custom = true)
        val plan = ModelRefreshPlanner.plan(
            instanceId = "inst-1",
            models = listOf(model("gpt-5")),
            existing = listOf(custom),
            instanceBaseURL = null,
        )
        assertEquals(listOf("custom-1"), plan.remainingCustom.map { it.uuid })
    }

    @Test
    fun customEntryForAReturnedModelStaysInRemainingCustom() {
        // The API now reports a model the user had added by hand: the refreshed
        // (non-custom) row takes over, and the custom row is not duplicated.
        val custom = entry("gpt-5", uuid = "custom-1", custom = true)
        val plan = ModelRefreshPlanner.plan(
            instanceId = "inst-1",
            models = listOf(model("gpt-5")),
            existing = listOf(custom),
            instanceBaseURL = null,
        )
        assertEquals(1, plan.refreshed.size)
        assertTrue(plan.remainingCustom.isEmpty())
        assertEquals(setOf("gpt-5"), plan.refreshedModelIds)
    }

    @Test
    fun nonCustomRowWinsTheUuidWhenBothExist() {
        // Two rows for one model id (custom + API): the API row carries forward
        // the NON-custom entry's uuid, so the custom row can be re-added without
        // the API row changing identity.
        val api = entry("gpt-5", uuid = "api-1", custom = false)
        val custom = entry("gpt-5", uuid = "custom-1", custom = true)
        val plan = ModelRefreshPlanner.plan(
            instanceId = "inst-1",
            models = listOf(model("gpt-5")),
            existing = listOf(api, custom),
            instanceBaseURL = null,
        )
        assertEquals("api-1", plan.refreshed.single().uuid)
    }
}
