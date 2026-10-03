package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-custom-params] Per-model overrides must survive a model-list
 * refresh.
 *
 * `ProviderRepository.replaceEntries` rebuilds every entry from the freshly
 * fetched catalog. If it constructed them with a bare `ModelOverrides()`, a
 * user's temperature / headers / extra body params would be silently wiped the
 * next time the list refreshed — including the automatic daily refresh, so the
 * loss would look spontaneous and unattributable.
 *
 * The inheritance is ALREADY implemented (`overrides = prior?.overrides ?:
 * ModelOverrides()`); this pins it, because nothing else would fail if someone
 * simplified that expression. The real function needs a Context and a live
 * config, so the merge rule is restated here and `the restated rule matches the
 * source` guards the copy against drift — the same pattern
 * OverlayCompletionKeepAliveTest uses for an equally unreachable predicate.
 */
class ModelOverridesRefreshMergeTest {

    /** Mirrors the entry-rebuild rule in ProviderRepository.replaceEntries. */
    private fun rebuild(fresh: LLMModel, prior: ModelEntry?): ModelEntry =
        ModelEntry(
            providerInstanceId = "inst-1",
            baseModel = fresh,
            overrides = prior?.overrides ?: ModelOverrides(),
            isCustom = false,
            isHidden = prior?.isHidden ?: false,
            uuid = prior?.id ?: "generated",
            userModifiedAt = prior?.userModifiedAt,
        )

    private val tuned = ModelOverrides(
        temperature = 0.2,
        topP = 0.9,
        customHeaders = mapOf("X-Tenant" to "acme"),
        extraBodyParams = Json.parseToJsonElement("""{"seed":7}""") as JsonObject,
    )

    @Test
    fun `a refresh keeps the user's tuned parameters`() {
        val prior = ModelEntry("inst-1", LLMModel("m", "M", "P"), overrides = tuned)

        val after = rebuild(LLMModel("m", "M (renamed upstream)", "P"), prior)

        assertEquals(0.2, after.overrides.temperature!!, 1e-9)
        assertEquals(0.9, after.overrides.topP!!, 1e-9)
        assertEquals(mapOf("X-Tenant" to "acme"), after.overrides.customHeaders)
        assertNotNull(after.overrides.extraBodyParams)
    }

    /**
     * 0.0 is a real, meaningful temperature (fully deterministic), so it must
     * not be mistaken for "unset" and dropped. This is the case a naive
     * `?: 0.0`-style simplification would break.
     */
    @Test
    fun `a zero temperature survives rather than reading as unset`() {
        val prior = ModelEntry("inst-1", LLMModel("m", "M", "P"), ModelOverrides(temperature = 0.0))
        val after = rebuild(LLMModel("m", "M", "P"), prior)
        assertEquals(0.0, after.overrides.temperature!!, 1e-9)
        assertFalse("an entry carrying only 0.0 must not look empty", after.overrides.isEmpty)
    }

    /** A brand-new model has no prior, so it legitimately starts unset. */
    @Test
    fun `a newly discovered model starts with empty overrides`() {
        val after = rebuild(LLMModel("brand-new", "Brand New", "P"), prior = null)
        assertTrue(after.overrides.isEmpty)
    }

    /** The identity/visibility carry-forward must not regress either. */
    @Test
    fun `uuid and hidden state carry forward`() {
        val prior = ModelEntry(
            "inst-1", LLMModel("m", "M", "P"),
            overrides = tuned, isHidden = true, uuid = "stable-uuid",
        )
        val after = rebuild(LLMModel("m", "M", "P"), prior)
        assertEquals("stable-uuid", after.id)
        assertTrue(after.isHidden)
    }

    /** Guards the restated rule above against drifting from the source. */
    @Test
    fun `the restated rule matches the source`() {
        val src = java.io.File(
            "src/main/java/com/openminis/app/data/repository/ProviderRepository.kt",
        )
        assertTrue("ProviderRepository.kt not found (cwd=${java.io.File(".").absolutePath})", src.isFile)
        assertTrue(
            "replaceEntries must inherit prior overrides",
            src.readText().contains("overrides = prior?.overrides ?: ModelOverrides()"),
        )
    }
}
