package com.openminis.app.data

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentLimits
import com.openminis.app.data.model.SubAgentRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sub-agents-v1] The roster's invariants.
 *
 * These are not incidental behaviours: the roster is decoded from a blob that
 * can arrive from a newer build, from another platform, or from a device set to
 * a different language, and `normalize` is the only thing standing between that
 * data and both the tool schema and the system prompt. Each test below pins one
 * way the roster could otherwise go wrong at runtime.
 *
 * Mirrors iOS MinisTests/SubAgentDefinitionTests.swift.
 */
class SubAgentDefinitionTest {

    private fun custom(
        id: String,
        name: String,
        sortOrder: Int = 0,
        description: String = "d",
        isBuiltIn: Boolean = false,
    ) = SubAgentDefinition(
        id = id,
        name = name,
        description = description,
        sortOrder = sortOrder,
        isBuiltIn = isBuiltIn,
    )

    // ── The built-in always exists and always leads ─────────────────────────

    @Test
    fun `an empty roster normalizes to the built-in alone`() {
        val out = SubAgentRoster.normalize(emptyList())
        assertEquals(1, out.size)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, out[0].id)
        assertEquals(SubAgentDefinition.BUILT_IN_NAME, out[0].name)
        assertTrue(out[0].isBuiltIn)
        assertEquals(0, out[0].sortOrder)
    }

    @Test
    fun `a roster missing the built-in gets it reinserted at the front`() {
        val out = SubAgentRoster.normalize(listOf(custom("a", "Researcher", sortOrder = 0)))
        assertEquals(2, out.size)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, out[0].id)
        assertEquals("Researcher", out[1].name)
    }

    @Test
    fun `the built-in is pinned to the front even when its stored order says otherwise`() {
        // A synced roster that reordered the built-in must not bury it — it
        // would also cost it its slot in the count bound.
        val input = listOf(
            custom("a", "Alpha", sortOrder = 0),
            SubAgentDefinition.makeBuiltIn(sortOrder = 99),
        )
        val out = SubAgentRoster.normalize(input)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, out[0].id)
        assertEquals(0, out[0].sortOrder)
    }

    // ── The built-in's model-facing text is canonical ───────────────────────

    @Test
    fun `a renamed built-in has its canonical name and description restored`() {
        // This is the cross-language sync case: the stored name IS the tool
        // schema's enum value, so a copy written on a device in another
        // language would change what the model has to emit.
        val stored = SubAgentDefinition.makeBuiltIn().copy(
            name = "通用子代理",
            description = "翻译过的描述",
        )
        val out = SubAgentRoster.normalize(listOf(stored))
        assertEquals(SubAgentDefinition.BUILT_IN_NAME, out[0].name)
        assertEquals(SubAgentDefinition.BUILT_IN_DESCRIPTION, out[0].description)
    }

    @Test
    fun `restoring the built-in's text leaves the user's own fields alone`() {
        val stored = SubAgentDefinition.makeBuiltIn().copy(
            name = "renamed",
            modelGroupId = "group-7",
            instructions = "always cite sources",
        )
        val out = SubAgentRoster.normalize(listOf(stored))
        assertEquals(SubAgentDefinition.BUILT_IN_NAME, out[0].name)
        assertEquals("group-7", out[0].modelGroupId)
        assertEquals("always cite sources", out[0].instructions)
    }

    // ── Impostor protection ─────────────────────────────────────────────────

    @Test
    fun `a custom row claiming to be the built-in is dropped`() {
        // isBuiltIn drives "cannot delete" in the UI, so a synced row must not
        // be able to assert it.
        val input = listOf(
            SubAgentDefinition.makeBuiltIn(),
            custom("impostor", "Fake", isBuiltIn = true),
        )
        val out = SubAgentRoster.normalize(input)
        assertEquals(1, out.size)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, out[0].id)
    }

    @Test
    fun `a second row stealing the built-in id is dropped`() {
        val input = listOf(
            SubAgentDefinition.makeBuiltIn(),
            custom(SubAgentDefinition.BUILT_IN_ID, "Impostor"),
        )
        val out = SubAgentRoster.normalize(input)
        assertEquals(1, out.size)
        assertEquals(SubAgentDefinition.BUILT_IN_NAME, out[0].name)
    }

    // ── Bounds ──────────────────────────────────────────────────────────────

    @Test
    fun `the roster is capped at the maximum count including the built-in`() {
        val many = (1..20).map { custom("id$it", "Agent $it", sortOrder = it) }
        val out = SubAgentRoster.normalize(many)
        assertEquals(SubAgentLimits.MAX_COUNT, out.size)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, out[0].id)
    }

    @Test
    fun `over-long fields are truncated rather than rejected`() {
        val long = custom(
            "a",
            name = "n".repeat(100),
            description = "d".repeat(500),
        ).copy(instructions = "i".repeat(9000))
        val out = SubAgentRoster.normalize(listOf(long))
        val got = out[1]
        assertEquals(SubAgentLimits.NAME_MAX_LENGTH, got.name.length)
        assertEquals(SubAgentLimits.DESCRIPTION_MAX_LENGTH, got.description.length)
        assertEquals(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH, got.instructions.length)
        assertFalse(got.exceedsLimits)
    }

    @Test
    fun `sort order is renumbered densely from zero`() {
        val input = listOf(
            custom("a", "Alpha", sortOrder = 50),
            custom("b", "Beta", sortOrder = 10),
            custom("c", "Gamma", sortOrder = 30),
        )
        val out = SubAgentRoster.normalize(input)
        assertEquals(listOf(0, 1, 2, 3), out.map { it.sortOrder })
        // Ordering follows the stored sortOrder, not input order.
        assertEquals(listOf("Beta", "Gamma", "Alpha"), out.drop(1).map { it.name })
    }

    @Test
    fun `ties break by id so two devices normalize identically`() {
        val a = SubAgentRoster.normalize(
            listOf(custom("zzz", "Z", sortOrder = 5), custom("aaa", "A", sortOrder = 5)),
        )
        val b = SubAgentRoster.normalize(
            listOf(custom("aaa", "A", sortOrder = 5), custom("zzz", "Z", sortOrder = 5)),
        )
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(listOf("A", "Z"), a.drop(1).map { it.name })
    }

    @Test
    fun `normalize never throws on hostile input`() {
        val hostile = listOf(
            custom("", "", sortOrder = -99),
            custom("dup", "Same", sortOrder = Int.MIN_VALUE),
            custom("dup", "Same", sortOrder = Int.MAX_VALUE),
        )
        val out = SubAgentRoster.normalize(hostile)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, out[0].id)
        assertTrue(out.size >= 1)
    }

    // ── resolve ─────────────────────────────────────────────────────────────

    @Test
    fun `a null or blank name resolves to the built-in`() {
        val roster = SubAgentRoster.normalize(listOf(custom("a", "Researcher")))
        assertEquals(SubAgentDefinition.BUILT_IN_ID, SubAgentRoster.resolve(null, roster)?.id)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, SubAgentRoster.resolve("", roster)?.id)
        assertEquals(SubAgentDefinition.BUILT_IN_ID, SubAgentRoster.resolve("   ", roster)?.id)
    }

    @Test
    fun `a name from a model is matched case-insensitively and trimmed`() {
        // The name comes back from a model, so exact matching would reject
        // perfectly reasonable output.
        val roster = SubAgentRoster.normalize(listOf(custom("a", "Researcher")))
        for (probe in listOf("Researcher", "researcher", "RESEARCHER", "  Researcher  ")) {
            assertNotNull("should resolve: '$probe'", SubAgentRoster.resolve(probe, roster))
            assertEquals("a", SubAgentRoster.resolve(probe, roster)?.id)
        }
    }

    @Test
    fun `an unknown name resolves to null so the caller can report unknown_agent`() {
        val roster = SubAgentRoster.normalize(listOf(custom("a", "Researcher")))
        assertNull(SubAgentRoster.resolve("Nonexistent", roster))
    }

    @Test
    fun `the built-in resolves by its canonical name`() {
        val roster = SubAgentRoster.normalize(emptyList())
        assertEquals(
            SubAgentDefinition.BUILT_IN_ID,
            SubAgentRoster.resolve(SubAgentDefinition.BUILT_IN_NAME, roster)?.id,
        )
    }

    // ── The wire name ───────────────────────────────────────────────────────

    @Test
    fun `the tool wire name is subagent_task`() {
        // Renamed from delegate_task; agent_status folded in as an action.
        assertEquals("subagent_task", SubAgentDefinition.TOOL_NAME)
    }
}

/**
 * [T-sub-agents-v1] The roster rides the existing provider-config blob, so a
 * build that predates it must still load, and a build that has it must not lose
 * a user's definitions on the next write.
 */
class SubAgentPersistenceTest {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `config JSON written before sub agents existed still decodes`() {
        // The downgrade/upgrade path: the key is simply absent.
        val legacy = """{"instances":[],"modelEntries":[],"modelGroups":[]}"""
        val cfg = json.decodeFromString<com.openminis.app.data.model.ProviderConfig>(legacy)
        assertTrue(cfg.subAgents.isEmpty())
        // …and normalizing that empty list is what materializes the built-in.
        assertEquals(1, SubAgentRoster.normalize(cfg.subAgents).size)
    }

    @Test
    fun `a roster round-trips through the config blob unchanged`() {
        val cfg = com.openminis.app.data.model.ProviderConfig()
        cfg.subAgents.add(
            SubAgentDefinition(
                id = "a",
                name = "Researcher",
                description = "Reads a lot",
                instructions = "cite sources",
                modelGroupId = "grp-1",
                sortOrder = 1,
            ),
        )
        val decoded = json.decodeFromString<com.openminis.app.data.model.ProviderConfig>(
            json.encodeToString(
                com.openminis.app.data.model.ProviderConfig.serializer(),
                cfg,
            ),
        )
        assertEquals(1, decoded.subAgents.size)
        val got = decoded.subAgents[0]
        assertEquals("Researcher", got.name)
        assertEquals("cite sources", got.instructions)
        assertEquals("grp-1", got.modelGroupId)
        assertFalse(got.isBuiltIn)
    }

    @Test
    fun `an unknown future field does not break decoding`() {
        // Forward compatibility: a newer build may add fields we do not know.
        val future = """{"id":"a","name":"X","description":"d","futureField":42}"""
        val got = json.decodeFromString<SubAgentDefinition>(future)
        assertEquals("X", got.name)
    }
}
