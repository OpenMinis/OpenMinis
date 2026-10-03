package com.openminis.app.data

import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-thinking-level-arch] Phase 1 — deserialization safety for the
 * expanded ThinkingLevel enum. Guards the exact failure mode the architecture
 * spec calls out: an older build reading a level string a NEWER build wrote
 * must NOT throw (which would blow up the whole config load and wipe the UI).
 */
class ThinkingLevelTest {

    @Test
    fun decoded_returnsKnownLevels() {
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.decoded("MAX"))
        assertEquals(ThinkingLevel.ULTRA, ThinkingLevel.decoded("ULTRA"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("HIGH"))
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.decoded("OFF"))
    }

    @Test
    fun decoded_unknownValueClampsToXHigh_neverThrows() {
        // A completely unrecognized future value must clamp to the highest
        // level THIS build knows, not throw.
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("SUPREME"))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded(""))
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.decoded("garbage-token"))
    }

    @Test
    fun rank_followsDeclarationOrder() {
        assertEquals(0, ThinkingLevel.OFF.rank)
        assertEquals(4, ThinkingLevel.XHIGH.rank)
        assertEquals(5, ThinkingLevel.MAX.rank)
        assertEquals(6, ThinkingLevel.ULTRA.rank)
        // Sanity: intersection/clamp via rank works as expected.
        assertEquals(
            ThinkingLevel.HIGH,
            minOf(ThinkingLevel.ULTRA, ThinkingLevel.HIGH, compareBy { it.rank }),
        )
    }

    /**
     * The repository's Json instance uses coerceInputValues. ThinkingLevel no
     * longer relies on it: [ThinkingLevelSerializer] reads the value as a plain
     * string, so an unknown level (one a NEWER build wrote) clamps to XHIGH —
     * the same rule as [ThinkingLevel.decoded] and iOS — instead of being
     * coerced to null. It still never throws, so the config is never wiped.
     * [T-android-restore-thinking-level]
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun modelGroup_unknownThinkingLevel_clampsToXHigh_notThrow() {
        val wire = """{"id":"g1","name":"G","memberEntryIds":[],"defaultThinkingLevel":"SUPREME"}"""
        val group = json.decodeFromString(ModelGroup.serializer(), wire)
        assertEquals(ThinkingLevel.XHIGH, group.defaultThinkingLevel)
        assertEquals("G", group.name) // other fields intact — config NOT wiped
    }

    @Test
    fun modelOverrides_unknownMaxThinkingLevel_clampsToXHigh() {
        val wire = """{"maxThinkingLevel":"SUPREME","displayName":"X"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.XHIGH, ov.maxThinkingLevel)
        assertEquals("X", ov.displayName)
    }

    @Test
    fun decoded_acceptsIosRawValues() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.decoded("high"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.decoded("max"))
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.decoded("off"))
        assertEquals(null, ThinkingLevel.parseOrNull("SUPREME"))
    }

    @Test
    fun modelOverrides_knownMaxThinkingLevel_roundTrips() {
        val wire = """{"maxThinkingLevel":"MAX"}"""
        val ov = json.decodeFromString(ModelOverrides.serializer(), wire)
        assertEquals(ThinkingLevel.MAX, ov.maxThinkingLevel)
    }
}
