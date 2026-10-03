package com.openminis.app.data

import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.db.toSnapshot
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.SubAgentDefinition
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-own-store] The roster must survive the database mirror.
 *
 * ProviderConfig is mirrored into two stores of different shape: a JSON blob
 * carrying every field, and a Room database carrying instances, entries and
 * groups. The config is rebuilt FROM THE DATABASE on launch, so any field the
 * database does not hold returns as its default and then overwrites the
 * complete copy on the next save.
 *
 * For the roster that is silent data loss — custom agents vanish on relaunch,
 * and the list reads as merely empty because the accessor re-inserts the
 * built-in. iOS reproduced exactly this before moving the roster out of the
 * blob; this test is what stops it recurring here.
 */
class SubAgentPersistenceRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun configWithRoster(): ProviderConfig {
        val cfg = ProviderConfig()
        cfg.subAgents.add(SubAgentDefinition.makeBuiltIn())
        cfg.subAgents.add(
            SubAgentDefinition(
                id = "a", name = "Researcher", description = "Reads a lot",
                instructions = "cite sources", modelGroupId = "grp-1", sortOrder = 1,
            ),
        )
        return cfg
    }

    @Test
    fun `custom sub agents survive a database round trip`() {
        val restored = configWithRoster().toSnapshot(json).toProviderConfig(json)
        assertEquals(2, restored.subAgents.size)
        val custom = restored.subAgents.first { !it.isBuiltIn }
        assertEquals("Researcher", custom.name)
        assertEquals("cite sources", custom.instructions)
        assertEquals("grp-1", custom.modelGroupId)
    }

    @Test
    fun `an empty roster round trips as empty rather than becoming undefined`() {
        val restored = ProviderConfig().toSnapshot(json).toProviderConfig(json)
        assertTrue(restored.subAgents.isEmpty())
    }

    @Test
    fun `a database written before sub agents existed restores cleanly`() {
        // No meta row at all: the roster comes back empty, which normalize
        // turns into "built-in only" — the same as a fresh install, not a crash.
        val snapshot = ProviderConfig().toSnapshot(json)
        val withoutRow = snapshot.copy(
            meta = snapshot.meta.filterNot { it.key == "sub_agents_json" },
        )
        val restored = withoutRow.toProviderConfig(json)
        assertTrue(restored.subAgents.isEmpty())
    }

    @Test
    fun `a corrupt roster row does not take the whole config down with it`() {
        val snapshot = configWithRoster().toSnapshot(json)
        val corrupted = snapshot.copy(
            meta = snapshot.meta.map {
                if (it.key == "sub_agents_json") it.copy(value = "{not json") else it
            },
        )
        // The rest of the config must still load; the roster degrades to empty.
        val restored = corrupted.toProviderConfig(json)
        assertTrue(restored.subAgents.isEmpty())
    }
}
