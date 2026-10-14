package com.openminis.app.data.db

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure round-trip coverage for [ProviderConfigMapping]: the composite entry-id
 * collision that silently dropped custom entries, and the downgrade-safe enum
 * decode that must not throw when a NEWER build persisted a value this build
 * doesn't know.
 *
 * Both are storage-layer invariants the repository needs but cannot be reached
 * from a JVM test through `ProviderRepository` (Context + Room +
 * EncryptedSharedPreferences), so they are exercised directly on the mapping.
 */
class ProviderConfigMappingRoundTripTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private fun instance(id: String) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        createdAt = 1L,
    )

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "openai")

    private fun customEntry(instanceId: String, modelId: String, label: String, uuid: String) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = model(modelId).copy(displayName = label),
        isCustom = true,
        uuid = uuid,
    )

    private fun configWith(vararg entries: ModelEntry) = ProviderConfig(
        instances = mutableListOf(instance("inst")),
        modelEntries = entries.toMutableList(),
    )

    // ─── [T-android-composite-key-collision] ─────────────────────────────

    @Test
    fun twoCustomEntriesOnSameModelKeepDistinctRowIds() {
        // The common "same model, different overrides" case: two custom entries
        // on one instance sharing a base-model id. Both mapped to the composite
        // key `inst/gpt-4`, @Insert(REPLACE) kept one row, and until the id was
        // canonicalised the UI rendered both with the SAME id — the duplicate
        // LazyColumn key this file's own comments warn about.
        val a = customEntry("inst", "gpt-4", "gpt-4 fast", "uuid-a")
        val b = customEntry("inst", "gpt-4", "gpt-4 careful", "uuid-b")
        val snapshot = configWith(a, b).toSnapshot(json)

        val ids = snapshot.entries.map { it.id }
        assertEquals("both entries must survive as separate rows", 2, ids.size)
        assertNotEquals("colliding composite keys silently drop a row", ids[0], ids[1])
        assertEquals("inst/gpt-4", ids[0])
        assertEquals("inst/gpt-4#uuid-b", ids[1])
    }

    @Test
    fun singleEntryKeepsTheStableCompositeIdSoBindingsStillResolve() {
        // Non-colliding entries MUST keep the plain "{instanceId}/{modelId}"
        // shape: persisted session bindings and lastUsedEntryId store it, so
        // minting a suffixed id here would orphan every existing pin.
        val only = customEntry("inst", "gpt-4", "gpt-4", "uuid-a")
        val snapshot = configWith(only).toSnapshot(json)
        assertEquals(listOf("inst/gpt-4"), snapshot.entries.map { it.id })
    }

    @Test
    fun roundTripPreservesBothCustomEntriesAndTheirReferences() {
        val a = customEntry("inst", "gpt-4", "gpt-4 fast", "uuid-a")
        val b = customEntry("inst", "gpt-4", "gpt-4 careful", "uuid-b")
        val group = ModelGroup(
            id = "grp",
            name = "Both",
            memberEntryIds = mutableListOf(a.id, b.id),
        )
        val config = configWith(a, b).copy(
            modelGroups = mutableListOf(group),
            agentLoopModelEntryIds = mutableListOf(a.id, b.id),
        )
        val back = config.toSnapshot(json).toProviderConfig(json)

        assertEquals(2, back.modelEntries.size)
        assertEquals(
            listOf("inst/gpt-4", "inst/gpt-4#uuid-b"),
            back.modelEntries.map { it.id },
        )
        // References are rewritten in lockstep with the row id, so the group
        // still points at BOTH members and neither pin dangles.
        assertEquals(back.modelEntries.map { it.id }.toSet(), back.modelGroups[0].memberEntryIds.toSet())
        assertEquals(back.modelEntries.map { it.id }.toSet(), back.agentLoopModelEntryIds.toSet())
    }

    // ─── [T-android-provider-enum-decode] ────────────────────────────────

    private fun snapshotWithFutureEnums() = ProviderConfigSnapshot(
        instances = listOf(
            ProviderInstanceEntity(
                id = "inst",
                label = "Future",
                providerType = "moondream",              // ProviderType a newer build added
                credentialType = "deviceCode",            // ProviderCredential a newer build added
                createdAt = 1L,
            ),
        ),
        entries = listOf(
            ProviderModelEntryEntity(
                id = "inst/m1",
                providerInstanceId = "inst",
                baseModelJson = json.encodeToString(LLMModel.serializer(), model("m1")),
            ),
        ),
        groups = listOf(
            ProviderModelGroupEntity(
                id = "grp",
                name = "G",
                strategy = "weightedRoundRobin",          // RoutingStrategy a newer build added
                fallbackStrategy = "onContextOverflow",   // FallbackStrategy a newer build added
                memberEntryIdsJson = """["inst/m1"]""",
            ),
        ),
        loopIds = emptyList(),
        meta = emptyList(),
    )

    @Test
    fun unknownPersistedEnumNamesDecodeInsteadOfThrowing() {
        // Each of these four used to be `valueOf` — a single future value made
        // the whole snapshot throw, loadConfigSuspending refused to load and
        // would not fall back to the mirror, so every provider feature was dead
        // until provider.db was repaired by hand.
        val config = snapshotWithFutureEnums().toProviderConfig(json)

        assertEquals(ProviderType.unsupported, config.instances[0].providerType)
        assertEquals(ProviderCredential.apiKey, config.instances[0].credentialType)
        assertEquals(RoutingStrategy.fallback, config.modelGroups[0].strategy)
        assertEquals(FallbackStrategy.default, config.modelGroups[0].fallbackStrategy)
        assertEquals(1, config.modelEntries.size)
    }

    @Test
    fun knownEnumNamesStillDecodeToTheirRealCases() {
        val snapshot = ProviderConfigSnapshot(
            instances = listOf(
                ProviderInstanceEntity(
                    id = "inst",
                    label = "Kimi",
                    providerType = "kimiCode",
                    credentialType = "oauth",
                    createdAt = 1L,
                ),
            ),
            entries = emptyList(),
            groups = listOf(
                ProviderModelGroupEntity(
                    id = "grp",
                    name = "G",
                    strategy = "loadBalance",
                    fallbackStrategy = "always",
                    memberEntryIdsJson = "[]",
                ),
            ),
            loopIds = emptyList(),
            meta = emptyList(),
        )
        val config = snapshot.toProviderConfig(json)
        assertEquals(ProviderType.kimiCode, config.instances[0].providerType)
        assertEquals(ProviderCredential.oauth, config.instances[0].credentialType)
        assertEquals(RoutingStrategy.loadBalance, config.modelGroups[0].strategy)
        assertEquals(FallbackStrategy.always, config.modelGroups[0].fallbackStrategy)
    }

    @Test
    fun imageEndpointModeGuardIsUnchanged() {
        // The pre-existing hardened path stays as it was (auto on unknown).
        val snapshot = ProviderConfigSnapshot(
            instances = listOf(
                ProviderInstanceEntity(
                    id = "inst", label = "x", providerType = "openAI",
                    credentialType = "apiKey", imageEndpointMode = "someNewMode",
                    createdAt = 1L,
                ),
            ),
            entries = emptyList(), groups = emptyList(), loopIds = emptyList(), meta = emptyList(),
        )
        assertTrue(
            snapshot.toProviderConfig(json).instances[0].imageEndpointMode ==
                com.openminis.app.data.model.ImageEndpointMode.auto,
        )
    }
}
