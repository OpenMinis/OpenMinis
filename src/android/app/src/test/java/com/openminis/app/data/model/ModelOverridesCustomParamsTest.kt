package com.openminis.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-custom-params] Backward compatibility and round-trip for the
 * four request-tuning fields added to [ModelOverrides].
 *
 * The compatibility half is the point of the whole change: a config or backup
 * written BEFORE these keys existed must still decode. kotlinx.serialization
 * throws MissingFieldException for an absent key unless the property declares a
 * default, so `= null` on each field is load-bearing — these tests fail loudly
 * if someone ever removes one.
 */
class ModelOverridesCustomParamsTest {

    /** Matches the app's real decoder configuration (ProviderRepository). */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `legacy JSON without the new keys decodes with nulls`() {
        // Exactly the shape an older build wrote: none of the four keys exist.
        val legacy = """{"displayName":"Old Model","maxOutputTokens":4096}"""
        val decoded = json.decodeFromString<ModelOverrides>(legacy)

        assertEquals("Old Model", decoded.displayName)
        assertEquals(4096, decoded.maxOutputTokens)
        assertNull("absent key must stay null, not 0.0", decoded.temperature)
        assertNull(decoded.topP)
        assertNull(decoded.customHeaders)
        assertNull(decoded.extraBodyParams)
    }

    @Test
    fun `a completely empty legacy object decodes`() {
        val decoded = json.decodeFromString<ModelOverrides>("{}")
        assertTrue("an all-default overrides object is empty", decoded.isEmpty)
    }

    @Test
    fun `round trip preserves all four new fields`() {
        val original = ModelOverrides(
            displayName = "Tuned",
            temperature = 0.35,
            topP = 0.9,
            customHeaders = mapOf("X-Trace" to "abc", "X-Tier" to "pro"),
            extraBodyParams = JsonObject(
                mapOf(
                    "reasoning_effort" to JsonPrimitive("high"),
                    "seed" to JsonPrimitive(42),
                ),
            ),
        )

        val decoded = json.decodeFromString<ModelOverrides>(json.encodeToString(original))

        assertEquals(0.35, decoded.temperature!!, 1e-9)
        assertEquals(0.9, decoded.topP!!, 1e-9)
        assertEquals(mapOf("X-Trace" to "abc", "X-Tier" to "pro"), decoded.customHeaders)
        assertEquals("high", (decoded.extraBodyParams!!["reasoning_effort"] as JsonPrimitive).content)
        assertEquals(original, decoded)
    }

    @Test
    fun `temperature of zero survives and is not mistaken for absent`() {
        // 0.0 is a legitimate value (fully deterministic). If any layer used
        // `takeIf { it > 0 }` or a 0-default it would be indistinguishable from
        // "unset" — this pins that it is not.
        val decoded = json.decodeFromString<ModelOverrides>(
            json.encodeToString(ModelOverrides(temperature = 0.0)),
        )
        assertEquals(0.0, decoded.temperature!!, 1e-9)
        assertTrue("a zero temperature is a real override", !decoded.isEmpty)
    }

    @Test
    fun `isEmpty accounts for each new field on its own`() {
        // isEmpty gates whether the overrides object is exported at all, so an
        // entry carrying ONLY one of these must not look empty and be dropped.
        assertTrue(ModelOverrides().isEmpty)
        assertTrue(!ModelOverrides(temperature = 0.7).isEmpty)
        assertTrue(!ModelOverrides(topP = 0.5).isEmpty)
        assertTrue(!ModelOverrides(customHeaders = mapOf("a" to "b")).isEmpty)
        assertTrue(
            !ModelOverrides(
                extraBodyParams = JsonObject(mapOf("k" to JsonPrimitive("v"))),
            ).isEmpty,
        )
    }

    @Test
    fun `unknown future keys are ignored rather than fatal`() {
        // Forward compatibility: a NEWER build's export must not break this one.
        val future = """{"temperature":0.2,"someFutureKnob":{"a":1}}"""
        val decoded = json.decodeFromString<ModelOverrides>(future)
        assertEquals(0.2, decoded.temperature!!, 1e-9)
    }
}
