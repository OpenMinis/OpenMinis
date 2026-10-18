package com.openminis.app.scheduled

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-model-resolve] `minis-scheduled create --model` must be
 * understood at CREATION time.
 *
 * Every case here used to be deferred to fire time, where a wrong value cost a
 * silently missed run hours later instead of a CLI error the caller could see.
 */
class ScheduledModelResolverTest {

    private fun entry(
        wire: String,
        displayName: String = wire,
        uuid: String = "uuid-$wire",
        hidden: Boolean = false,
        instance: String = "inst-1",
    ) = ModelEntry(
        providerInstanceId = instance,
        baseModel = LLMModel(id = wire, displayName = displayName, provider = "openai"),
        isHidden = hidden,
        uuid = uuid,
    )

    private val entries = listOf(
        entry("gpt-5", "GPT-5"),
        entry("claude-opus-4", "Claude Opus 4"),
    )

    @Test
    fun blankModelIsUnspecified() {
        assertEquals(ScheduledModelResolver.Result.Unspecified, ScheduledModelResolver.resolve(null, entries))
        assertEquals(ScheduledModelResolver.Result.Unspecified, ScheduledModelResolver.resolve("   ", entries))
    }

    @Test
    fun wireIdResolves() {
        val r = ScheduledModelResolver.resolve("gpt-5", entries)
        assertEquals(ScheduledModelResolver.Result.Resolved("gpt-5", ScheduledModelResolver.Via.MODEL_ID), r)
    }

    @Test
    fun wireIdIsMatchedCaseInsensitively() {
        // A script author should not have to remember the vendor's capitalisation.
        val r = ScheduledModelResolver.resolve("GPT-5", entries)
        assertEquals(ScheduledModelResolver.Result.Resolved("gpt-5", ScheduledModelResolver.Via.MODEL_ID), r)
    }

    @Test
    fun entryUuidResolvesToItsWireId() {
        // What the model picker stores is the entry uuid, not the wire id.
        val r = ScheduledModelResolver.resolve("uuid-claude-opus-4", entries)
        assertEquals(
            ScheduledModelResolver.Result.Resolved("claude-opus-4", ScheduledModelResolver.Via.ENTRY_ID),
            r,
        )
    }

    @Test
    fun displayNameResolves() {
        val r = ScheduledModelResolver.resolve("Claude Opus 4", entries)
        assertEquals(
            ScheduledModelResolver.Result.Resolved("claude-opus-4", ScheduledModelResolver.Via.DISPLAY_NAME),
            r,
        )
    }

    @Test
    fun ambiguousDisplayNameIsRejectedRatherThanGuessed() {
        // The same model name on two instances must not silently pick one: the
        // caller is told to use the id instead. (The name deliberately does not
        // collide with either wire id — a name that matches an id resolves
        // through the id path first, which is unambiguous.)
        val ambiguous = listOf(
            entry("gpt-5", "Flagship", uuid = "a", instance = "inst-1"),
            entry("gpt-5-mini", "Flagship", uuid = "b", instance = "inst-2"),
        )
        val r = ScheduledModelResolver.resolve("Flagship", ambiguous)
        assertTrue("expected Unknown, got $r", r is ScheduledModelResolver.Result.Unknown)
    }

    @Test
    fun unknownValueListsKnownIds() {
        val r = ScheduledModelResolver.resolve("gtp-5", entries)
        assertTrue(r is ScheduledModelResolver.Result.Unknown)
        val unknown = r as ScheduledModelResolver.Result.Unknown
        assertEquals("gtp-5", unknown.raw)
        assertEquals(listOf("claude-opus-4", "gpt-5"), unknown.known)
    }

    @Test
    fun knownIdListIsCapped() {
        val many = (1..40).map { entry("model-$it") }
        val r = ScheduledModelResolver.resolve("nope", many) as ScheduledModelResolver.Result.Unknown
        assertEquals(12, r.known.size)
    }

    @Test
    fun hiddenEntryStillResolves() {
        // Hiding is a picker preference, not a routing decision: a task may
        // legitimately target a model the user keeps out of the list.
        val withHidden = entries + entry("o3", "o3", uuid = "uuid-o3", hidden = true)
        val r = ScheduledModelResolver.resolve("o3", withHidden)
        assertEquals(ScheduledModelResolver.Result.Resolved("o3", ScheduledModelResolver.Via.MODEL_ID), r)
    }

    @Test
    fun emptyConfigReportsNoKnownIds() {
        val r = ScheduledModelResolver.resolve("gpt-5", emptyList())
        assertEquals(ScheduledModelResolver.Result.Unknown("gpt-5", emptyList()), r)
    }
}
