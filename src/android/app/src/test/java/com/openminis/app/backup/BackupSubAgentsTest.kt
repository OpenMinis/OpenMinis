package com.openminis.app.backup

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-subagents] Custom sub agents survive an iOS <-> Android
 * backup round trip. iOS writes them only to `data/sub_agents.jsonl`; Android
 * used to write them only inside `provider_config.json` and never read the
 * jsonl, so each side's restore dropped the other's agents.
 */
class BackupSubAgentsTest {

    private fun def(
        id: String,
        name: String,
        updatedAt: Long = 1_000_000L,
        level: ThinkingLevel? = null,
        instructions: String = "",
    ) = SubAgentDefinition(
        id = id, name = name, description = "d-$name", instructions = instructions,
        thinkingLevelOverride = level, sortOrder = 0, updatedAt = updatedAt,
    )

    private val builtIn = SubAgentDefinition.makeBuiltIn()

    // ── Wire format ───────────────────────────────────────────────────────

    @Test
    fun `a line written by iOS decodes, lowercase level and ISO date included`() {
        // As iOS BackupJSONLWriter emits it: no "v", optionals omitted.
        val line = """{"t":"SubAgentV1","d":{"id":"5E7B0C1A-6D2F-4B8E-9A11-0B5A1D0C9F42","name":"researcher",""" +
            """"subAgentDescription":"Finds sources","instructions":"cite everything","sortOrder":1,""" +
            """"updatedAt":"2026-09-28T12:00:00Z","thinkingLevelOverride":"high"}}"""
        val env = BackupFormat.json.parseToJsonElement(line).jsonObject
        val r = BackupFormat.json.decodeFromJsonElement(BackupSubAgentRecord.serializer(), env["d"]!!)
        val d = BackupSubAgentMapping.fromRecord(r)!!
        assertEquals("researcher", d.name)
        assertEquals("Finds sources", d.description)
        assertEquals("cite everything", d.instructions)
        assertEquals(ThinkingLevel.HIGH, d.thinkingLevelOverride)
        assertNull(d.modelGroupId)
        assertFalse(d.isBuiltIn)
        assertEquals(java.time.Instant.parse("2026-09-28T12:00:00Z").toEpochMilli(), d.updatedAt)
    }

    @Test
    fun `older Android spelling and fractional dates are accepted too`() {
        val r = BackupSubAgentRecord("a", "x", "", "", null, 0, "2026-09-28T12:00:00.123+08:00", "HIGH")
        val d = BackupSubAgentMapping.fromRecord(r)!!
        assertEquals(ThinkingLevel.HIGH, d.thinkingLevelOverride)
        assertEquals(java.time.OffsetDateTime.parse("2026-09-28T12:00:00.123+08:00").toInstant().toEpochMilli(), d.updatedAt)
        assertEquals("bad date -> 0 (never overrides local)", 0L, BackupSubAgentMapping.parseMillis("yesterday"))
    }

    @Test
    fun `a record Android writes carries every field iOS requires, in iOS spelling`() {
        // Empty instructions and sortOrder 0 are exactly the values kotlinx
        // would drop if they had defaults; iOS's decoder requires them.
        val rec = BackupSubAgentMapping.toRecord(
            def("id-1", "writer", updatedAt = 1_790_000_000_123L, level = ThinkingLevel.XHIGH),
        )
        val obj = BackupFormat.json.encodeToJsonElement(BackupSubAgentRecord.serializer(), rec) as JsonObject
        for (k in listOf("id", "name", "subAgentDescription", "instructions", "sortOrder", "updatedAt")) {
            assertTrue("missing required '$k' in $obj", obj.containsKey(k))
        }
        assertEquals("\"xhigh\"", obj["thinkingLevelOverride"].toString())
        assertFalse("null optionals are omitted, as iOS does", obj.containsKey("modelGroupId"))
        // Whole seconds only: Swift's .iso8601 rejects fractional seconds.
        assertEquals("\"2026-09-21T14:13:20Z\"", obj["updatedAt"].toString())
    }

    @Test
    fun `Android record round trips`() {
        val original = def("id-1", "writer", updatedAt = 1_790_000_000_000L, level = ThinkingLevel.LOW, instructions = "be brief")
        val back = BackupSubAgentMapping.fromRecord(BackupSubAgentMapping.toRecord(original))!!
        assertEquals(original.copy(sortOrder = back.sortOrder), back)
    }

    @Test
    fun `the built-in is never exported nor restored`() {
        assertEquals(listOf("c"), BackupSubAgentMapping.exportable(listOf(builtIn, def("c", "custom"))).map { it.id })
        assertNull(BackupSubAgentMapping.fromRecord(BackupSubAgentMapping.toRecord(builtIn)))
        assertNull(BackupSubAgentMapping.fromRecord(BackupSubAgentRecord("x", " ", "", "", null, 0, "2026-01-01T00:00:00Z")))
    }

    // ── Merge rules (iOS importSubAgents / SubAgentRoster.merge) ────────────

    @Test
    fun `an unknown agent is added and nothing local is removed`() {
        val local = listOf(builtIn, def("a", "alpha"))
        val m = SubAgentRoster.mergeBackup(local, listOf(def("b", "beta")))
        assertEquals(1, m.written); assertEquals(0, m.skipped)
        assertEquals(setOf(SubAgentDefinition.BUILT_IN_ID, "a", "b"), m.roster.map { it.id }.toSet())
    }

    @Test
    fun `the same id is replaced only by a newer copy`() {
        val local = listOf(builtIn, def("a", "alpha", updatedAt = 2_000))
        val older = SubAgentRoster.mergeBackup(local, listOf(def("a", "alpha-OLD", updatedAt = 1_000)))
        assertEquals(0, older.written); assertEquals(1, older.skipped)
        assertEquals("alpha", older.roster.first { it.id == "a" }.name)
        val newer = SubAgentRoster.mergeBackup(local, listOf(def("a", "alpha-NEW", updatedAt = 3_000)))
        assertEquals(1, newer.written)
        assertEquals("alpha-NEW", newer.roster.first { it.id == "a" }.name)
    }

    @Test
    fun `a different id with a local agent's name keeps the local one`() {
        val local = listOf(builtIn, def("a", "Researcher"))
        val m = SubAgentRoster.mergeBackup(local, listOf(def("z", " researcher ")))
        assertEquals(0, m.written); assertEquals(1, m.skipped)
        assertEquals(1, m.roster.count { SubAgentRoster.nameKey(it.name) == "researcher" })
    }

    @Test
    fun `a package carrying agents in both files does not double them`() {
        // provider_config.json already merged "a" in; the jsonl copy is the same.
        val local = listOf(builtIn, def("a", "alpha", updatedAt = 5_000))
        val m = SubAgentRoster.mergeBackup(local, listOf(def("a", "alpha", updatedAt = 5_000)))
        assertEquals(0, m.written)
        assertEquals(2, m.roster.size)
    }

    @Test
    fun `the built-in is left alone`() {
        val local = listOf(builtIn)
        val m = SubAgentRoster.mergeBackup(local, listOf(builtIn.copy(description = "stale", updatedAt = Long.MAX_VALUE)))
        assertEquals(0, m.written)
        assertEquals(builtIn.description, m.roster.first().description)
    }
}
