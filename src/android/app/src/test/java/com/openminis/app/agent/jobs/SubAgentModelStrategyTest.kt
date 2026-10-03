package com.openminis.app.agent.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [T-android-subagent-model-strategy] The card's strategy line is built from
 * `model_origin`, so every origin the resolver can produce must have a wire
 * value the card recognises. A new origin added without a card branch would
 * silently render no strategy at all.
 */
class SubAgentModelStrategyTest {

    @Test
    fun `every origin has a stable wire value the card branches on`() {
        // These four strings are duplicated in HelperUi's when(); if an origin
        // is added or renamed, this fails and points at the card.
        assertEquals("pinned", HelperModelOrigin.PINNED.wire)
        assertEquals("inherited", HelperModelOrigin.INHERITED.wire)
        assertEquals("default_group", HelperModelOrigin.DEFAULT_GROUP.wire)
        assertEquals("sub_group", HelperModelOrigin.SUB_GROUP.wire)
        assertEquals(
            "a new origin needs a matching branch in helperModelStrategyLabel",
            4, HelperModelOrigin.entries.size,
        )
    }

    @Test
    fun `a group-backed resolution carries the group name for the card`() {
        // The three group origins are the ones whose label appends a name; the
        // resolution must therefore be able to carry one.
        val r = HelperModelResolution(
            bindingJson = """{"type":"group","groupId":"g1"}""",
            seedModelId = "m1",
            modelLabel = "Model One",
            tierUsed = HelperModelTier.PRIMARY,
            origin = HelperModelOrigin.PINNED,
            modelGroupName = "Fast Group",
        )
        assertEquals("Fast Group", r.modelGroupName)
        assertNotNull(r.bindingJson)
    }

    /** Inheriting is not group-backed, so it needs no name to render. */
    @Test
    fun `inherited needs no group name`() {
        val r = HelperModelResolution(
            bindingJson = null, seedModelId = "m1", modelLabel = "Model One",
            tierUsed = HelperModelTier.PRIMARY, origin = HelperModelOrigin.INHERITED,
        )
        assertEquals(null, r.modelGroupName)
    }
}
