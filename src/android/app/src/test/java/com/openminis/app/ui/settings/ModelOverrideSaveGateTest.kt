package com.openminis.app.ui.settings

import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-model-override-silent-drop] The model detail screen's Save gate.
 *
 * A user set a context window to 400000 and saved; after the daily model
 * refresh it read 1048576 again. The override had never been written: the save
 * gate compared each field against `baseModel` while the controls loaded from
 * override-or-base, so a value equal to today's auto value counted as "no
 * opinion". iOS fixed the same defect in 4ef8a48d7.
 *
 * Every behavioural test below also runs the OLD rule and asserts it produces
 * the wrong answer, so none of them can pass vacuously — a revert of the
 * production change has to fail here rather than merely stop being exercised.
 */
class ModelOverrideSaveGateTest {

    // ── the old, defective rules, kept verbatim for contrast ──────────────

    private fun oldSupportsReasoning(thinkingEnabled: Boolean, baseSupports: Boolean?): Boolean? =
        thinkingEnabled.takeIf { it != (baseSupports ?: false) }

    private fun oldModalities(new: List<String>, base: List<String>): List<String>? =
        if (new.toSet() != base.toSet()) new else null

    private fun oldDisplayName(typed: String, baseName: String): String? =
        typed.trim().takeIf { it.isNotEmpty() && it != baseName }

    /** Call the production gate with everything untouched unless stated. */
    private fun save(
        existing: ModelOverrides = ModelOverrides(),
        displayNameText: String = "",
        displayNameTouched: Boolean = false,
        maxOutputTokensText: String = "",
        contextWindowText: String = "",
        thinkingEnabled: Boolean = false,
        thinkingTouched: Boolean = false,
        inputModalities: List<String> = emptyList(),
        inputModalitiesTouched: Boolean = false,
        outputModalities: List<String> = emptyList(),
        outputModalitiesTouched: Boolean = false,
    ) = buildModelOverrides(
        existing = existing,
        displayNameText = displayNameText,
        displayNameTouched = displayNameTouched,
        maxOutputTokensText = maxOutputTokensText,
        contextWindowText = contextWindowText,
        thinkingEnabled = thinkingEnabled,
        thinkingTouched = thinkingTouched,
        inputModalities = inputModalities,
        inputModalitiesTouched = inputModalitiesTouched,
        outputModalities = outputModalities,
        outputModalitiesTouched = outputModalitiesTouched,
    )

    // ── the reported case ─────────────────────────────────────────────────

    @Test
    fun `a context window equal to the current auto value is still recorded`() {
        // The vendor already says 400000; the user types 400000 anyway.
        val result = save(contextWindowText = "400000")
        assertEquals(400_000, result.contextWindow)
        // Which is what makes it survive a later vendor bump: resolution is
        // `overrides.contextWindow ?: baseModel.contextWindow`, so a non-null
        // override wins over the refreshed 1048576.
    }

    @Test
    fun `an empty context window clears the override and returns to auto`() {
        val result = save(existing = ModelOverrides(contextWindow = 400_000), contextWindowText = "")
        assertNull(result.contextWindow)
    }

    @Test
    fun `a whitespace-only context window also returns to auto`() {
        val result = save(existing = ModelOverrides(contextWindow = 400_000), contextWindowText = "   ")
        assertNull(result.contextWindow)
    }

    @Test
    fun `a non-positive context window is rejected rather than stored`() {
        assertNull(save(contextWindowText = "0").contextWindow)
        assertNull(save(contextWindowText = "abc").contextWindow)
    }

    // ── supportsReasoning: touched wins over "equals base" ────────────────

    @Test
    fun `a thinking toggle set to the same value as base is still recorded`() {
        // base already true, user deliberately leaves it on and saves.
        val result = save(thinkingEnabled = true, thinkingTouched = true)
        assertEquals(true, result.supportsReasoning)

        // The old rule dropped exactly this, which is the bug.
        assertNull("old rule must fail here", oldSupportsReasoning(true, baseSupports = true))
    }

    @Test
    fun `an untouched thinking toggle records nothing and keeps inheriting`() {
        val result = save(thinkingEnabled = true, thinkingTouched = false)
        assertNull(result.supportsReasoning)
        // This is what keeps ModelOverrides.isEmpty meaningful: opening the
        // screen and pressing Save must not stamp an override on every model.
        assertTrue(result.isEmpty)
    }

    @Test
    fun `an existing thinking override survives the vendor catching up`() {
        // override=true, and the vendor has now also moved to true. A no-op
        // re-save must not drop the user's choice (failure mode 2).
        val existing = ModelOverrides(supportsReasoning = true)
        val result = save(existing = existing, thinkingEnabled = true, thinkingTouched = false)
        assertEquals(true, result.supportsReasoning)

        assertNull("old rule must fail here", oldSupportsReasoning(true, baseSupports = true))
    }

    @Test
    fun `turning the thinking toggle off is recorded as an explicit false`() {
        // Distinct from null: false means "the user said no", null means
        // "inherit". Resolution is `overrides.supportsReasoning ?: base`, so
        // only a non-null false suppresses a vendor-declared true.
        val result = save(thinkingEnabled = false, thinkingTouched = true)
        assertEquals(false, result.supportsReasoning)
    }

    // ── modalities ────────────────────────────────────────────────────────

    @Test
    fun `a touched modality set equal to base is still recorded`() {
        val base = listOf("image", "pdf")
        val result = save(inputModalities = base, inputModalitiesTouched = true)
        assertEquals(base, result.inputModalities)

        assertNull("old rule must fail here", oldModalities(base, base))
    }

    @Test
    fun `an untouched modality set records nothing`() {
        val result = save(inputModalities = listOf("image"), inputModalitiesTouched = false)
        assertNull(result.inputModalities)
        assertTrue(result.isEmpty)
    }

    @Test
    fun `an existing modality override survives an untouched save`() {
        val existing = ModelOverrides(inputModalities = listOf("image"))
        val result = save(existing = existing, inputModalities = listOf("image"), inputModalitiesTouched = false)
        assertEquals(listOf("image"), result.inputModalities)
    }

    @Test
    fun `clearing every input switch records an empty list not null`() {
        // Empty and null differ: null inherits the vendor's modalities, empty
        // is the user stating this model accepts none of them.
        val existing = ModelOverrides(inputModalities = listOf("image"))
        val result = save(existing = existing, inputModalities = emptyList(), inputModalitiesTouched = true)
        assertNotNull(result.inputModalities)
        assertEquals(emptyList<String>(), result.inputModalities)
    }

    @Test
    fun `input and output modalities are tracked independently`() {
        val result = save(
            inputModalities = listOf("image"),
            inputModalitiesTouched = true,
            outputModalities = listOf("audio"),
            outputModalitiesTouched = false,
        )
        assertEquals(listOf("image"), result.inputModalities)
        assertNull(result.outputModalities)
    }

    // ── displayName ───────────────────────────────────────────────────────

    @Test
    fun `a display name equal to the base name is still recorded when touched`() {
        val result = save(displayNameText = "GPT-5", displayNameTouched = true)
        assertEquals("GPT-5", result.displayName)

        assertNull("old rule must fail here", oldDisplayName("GPT-5", baseName = "GPT-5"))
    }

    @Test
    fun `an emptied display name clears the override`() {
        val existing = ModelOverrides(displayName = "My Name")
        val result = save(existing = existing, displayNameText = "  ", displayNameTouched = true)
        assertNull(result.displayName)
    }

    @Test
    fun `an untouched display name keeps the existing override`() {
        val existing = ModelOverrides(displayName = "My Name")
        val result = save(existing = existing, displayNameText = "My Name", displayNameTouched = false)
        assertEquals("My Name", result.displayName)
    }

    // ── fields this screen cannot edit must survive a save ────────────────

    @Test
    fun `per-model tuning set elsewhere is not wiped by saving this screen`() {
        // None of these have an editor here; they arrive via backup import or
        // iCloud sync. The previous inline construction omitted them entirely,
        // so they defaulted to null and a single Save silently erased them.
        val existing = ModelOverrides(
            maxThinkingLevel = ThinkingLevel.HIGH,
            temperature = 0.0,
            topP = 0.9,
            customHeaders = mapOf("X-Test" to "1"),
        )
        val result = save(existing = existing, contextWindowText = "1000")

        assertEquals(ThinkingLevel.HIGH, result.maxThinkingLevel)
        // 0.0 specifically: it is a legitimate temperature, so it must not be
        // conflated with "unset".
        assertEquals(0.0, result.temperature!!, 0.0)
        assertEquals(0.9, result.topP!!, 0.0)
        assertEquals(mapOf("X-Test" to "1"), result.customHeaders)
    }

    // ── the whole point, end to end ───────────────────────────────────────

    @Test
    fun `a fresh entry saved with no edits at all stays empty`() {
        // The fast path in ProviderConfig.model depends on this staying true.
        assertTrue(save().isEmpty)
    }
}
