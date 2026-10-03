package com.openminis.app.data.db

import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Bidirectional ProviderConfig ↔ Room-entity mapping. Bundles all
 * five tables into one Snapshot so saveConfig writes them atomically
 * via [ProviderConfigDao.replaceAll].
 *
 * Entry IDs are rewritten to the composite "{instanceId}/{modelId}"
 * shape in [toSnapshot] so DB rows and the legacy JSON mirror share
 * the same id semantics post-migration. Group memberEntryIds and
 * agentLoopModelEntryIds are translated in lockstep using the same
 * map, so a group/agent-loop pin written before the migration still
 * resolves after.
 */
data class ProviderConfigSnapshot(
    val instances: List<ProviderInstanceEntity>,
    val entries: List<ProviderModelEntryEntity>,
    val groups: List<ProviderModelGroupEntity>,
    val loopIds: List<ProviderAgentLoopIdEntity>,
    val meta: List<ProviderConfigMetaEntity>,
)

object ProviderConfigMetaKeys {
    const val DEFAULT_PRIMARY_GROUP_ID = "default_primary_group_id"
    const val DEFAULT_SUB_GROUP_ID = "default_sub_group_id"
    // [T-android-provider-voice] Voice Input / Voice Output group bindings
    // (mirrors iOS provider_local_kv voiceInputGroupId / voiceOutputGroupId).
    // Meta KV rows are additive — no Room schema migration needed.
    const val VOICE_INPUT_GROUP_ID = "voice_input_group_id"
    const val VOICE_OUTPUT_GROUP_ID = "voice_output_group_id"
    // [T-android-vision-group / GH#182] Vision Group pointer (per-device meta KV).
    const val VISION_GROUP_ID = "vision_group_id"
    /**
     * [T-subagent-own-store] The sub agent roster, as a JSON array.
     *
     * The roster lives here rather than only in ProviderConfig because the
     * config is mirrored into TWO stores of different shape: the JSON blob,
     * which carries every field, and this database, which carries instances,
     * entries and groups. On launch the config is rebuilt FROM THIS DATABASE —
     * so any field the database does not hold comes back as its default and
     * then overwrites the complete copy on the next save.
     *
     * For the roster that is silent data loss: custom agents vanish on relaunch
     * and the list looks merely empty, because the read accessor re-inserts the
     * built-in. iOS reproduced exactly this (T-subagent-own-store). A meta row
     * is additive, so this needs no Room migration.
     */
    const val SUB_AGENTS_JSON = "sub_agents_json"
    const val JSON_SYNC_HASH = "json_sync_hash"
}

/**
 * Build a [ProviderConfigSnapshot] from [config]. The provided
 * [jsonForBlobs] must be the same kotlinx.serialization Json instance
 * the repository uses elsewhere — its `ignoreUnknownKeys` /
 * `encodeDefaults` settings shape every baseModel / overrides /
 * memberEntryIds blob on disk.
 *
 * [jsonSyncHash] is the hash of the legacy mirror JSON we just wrote
 * (or about to write) for downgrade-detection on next load. Pass null
 * when called from the load path's first-time JSON→DB import (we'll
 * compute and store it immediately after).
 */
fun ProviderConfig.toSnapshot(
    jsonForBlobs: Json,
    jsonSyncHash: String? = null,
): ProviderConfigSnapshot {
    // Map every legacy entry uuid → composite "{instanceId}/{modelId}".
    // Duplicate composite keys are theoretically impossible (one entry
    // per (instance, model)) — if we see one, last-write-wins on the
    // map, but both legacy uuids point at the same composite, so group /
    // agent-loop references converge harmlessly.
    val idMap = HashMap<String, String>(modelEntries.size)
    for (entry in modelEntries) {
        idMap[entry.uuid] = compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
    }

    val instanceRows = instances.mapIndexed { idx, inst ->
        ProviderInstanceEntity(
            id = inst.id,
            label = inst.label,
            providerType = inst.providerType.name,
            credentialType = inst.credentialType.name,
            customBaseURL = inst.customBaseURL,
            appendV1Suffix = if (inst.appendV1Suffix) 1 else 0,
            useResponsesAPI = if (inst.useResponsesAPI) 1 else 0,
            azureMode = if (inst.azureMode) 1 else 0,
            // [GH#68] Persist the picker choice + probe cache; auto is stored
            // explicitly (not null) so a legit "auto" survives round-trips too.
            imageEndpointMode = inst.imageEndpointMode.name,
            imageEndpointResolved = inst.imageEndpointResolved?.name,
            customUserAgent = inst.customUserAgent,
            isEnabled = if (inst.isEnabled) 1 else 0,
            sortOrder = idx,
            createdAt = inst.createdAt,
        )
    }

    // Bucket entries by instance so sort_order is per-instance contiguous,
    // matching the loadEntries() ORDER BY clause.
    val entryRows = ArrayList<ProviderModelEntryEntity>(modelEntries.size)
    val grouped = modelEntries.groupBy { it.providerInstanceId }
    for ((instanceId, entries) in grouped) {
        entries.forEachIndexed { idx, e ->
            val composite = idMap[e.uuid] ?: compositeEntryKey(instanceId, e.baseModel.id)
            entryRows.add(
                ProviderModelEntryEntity(
                    id = composite,
                    providerInstanceId = instanceId,
                    baseModelJson = jsonForBlobs.encodeToString(LLMModel.serializer(), e.baseModel),
                    overridesJson = if (e.overrides.isEmpty) null
                        else jsonForBlobs.encodeToString(ModelOverrides.serializer(), e.overrides),
                    isCustom = if (e.isCustom) 1 else 0,
                    isHidden = if (e.isHidden) 1 else 0,
                    sortOrder = idx,
                    userModifiedAt = e.userModifiedAt,
                )
            )
        }
    }

    val groupRows = modelGroups.mapIndexed { idx, g ->
        // Translate every member uuid to the composite shape. If an id
        // is already in composite form (e.g. user upgraded once, wrote
        // some config, downgraded, re-upgraded) it won't be in idMap;
        // pass it through unchanged — both forms remain valid string ids
        // and the lookup paths in ProviderRepository compare by string
        // equality regardless of shape.
        val translatedMembers = g.memberEntryIds.map { idMap[it] ?: it }
        ProviderModelGroupEntity(
            id = g.id,
            name = g.name,
            strategy = g.strategy.name,
            fallbackStrategy = g.fallbackStrategy.name,
            defaultThinkingLevel = g.defaultThinkingLevel?.name,
            contextLimitTokens = g.contextLimitTokens,
            lastContextLimitTokens = g.lastContextLimitTokens,
            memberEntryIdsJson = jsonForBlobs.encodeToString(
                ListSerializer(String.serializer()),
                translatedMembers,
            ),
            sortOrder = idx,
        )
    }

    val loopRows = ArrayList<ProviderAgentLoopIdEntity>(
        agentLoopModelEntryIds.size + agentLoopGroupIds.size,
    )
    agentLoopModelEntryIds.forEachIndexed { idx, id ->
        loopRows.add(
            ProviderAgentLoopIdEntity(
                kind = "entry",
                targetId = idMap[id] ?: id,
                sortOrder = idx,
            )
        )
    }
    agentLoopGroupIds.forEachIndexed { idx, id ->
        loopRows.add(
            ProviderAgentLoopIdEntity(
                kind = "group",
                targetId = id,
                sortOrder = idx,
            )
        )
    }

    val metaRows = mutableListOf<ProviderConfigMetaEntity>()
    defaultPrimaryGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID, it))
    }
    defaultSubGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID, it))
    }
    voiceInputGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID, it))
    }
    voiceOutputGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID, it))
    }
    visionGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.VISION_GROUP_ID, it))
    }
    // [T-subagent-own-store] Always written, including when empty: an absent
    // row is indistinguishable from "this build did not know about sub agents",
    // and the read side has to be able to tell those apart.
    metaRows.add(
        ProviderConfigMetaEntity(
            ProviderConfigMetaKeys.SUB_AGENTS_JSON,
            runCatching {
                jsonForBlobs.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(
                        com.openminis.app.data.model.SubAgentDefinition.serializer(),
                    ),
                    subAgents.toList(),
                )
            }.getOrDefault("[]"),
        ),
    )
    jsonSyncHash?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.JSON_SYNC_HASH, it))
    }

    return ProviderConfigSnapshot(instanceRows, entryRows, groupRows, loopRows, metaRows)
}

/**
 * Reverse-map a snapshot back to [ProviderConfig]. Entries' uuid
 * field is set to the composite-key id stored in DB — so once we round-trip,
 * group/agentLoop refs in the mirror JSON also point at the composite shape.
 */
fun ProviderConfigSnapshot.toProviderConfig(jsonForBlobs: Json): ProviderConfig {
    val instances = this.instances.map { row ->
        ProviderInstance(
            id = row.id,
            label = row.label,
            providerType = ProviderType.valueOf(row.providerType),
            credentialType = ProviderCredential.valueOf(row.credentialType),
            isEnabled = row.isEnabled != 0,
            createdAt = row.createdAt,
            customBaseURL = row.customBaseURL,
            appendV1Suffix = row.appendV1Suffix != 0,
            customUserAgent = row.customUserAgent,
            useResponsesAPI = row.useResponsesAPI != 0,
            azureMode = row.azureMode != 0,
            // [GH#68] Safe parse: null (pre-migration rows) or an unknown
            // name from a future build falls back to auto / no cache rather
            // than throwing and wiping the whole provider load.
            imageEndpointMode = row.imageEndpointMode?.let { m ->
                runCatching { ImageEndpointMode.valueOf(m) }.getOrNull()
            } ?: ImageEndpointMode.auto,
            imageEndpointResolved = row.imageEndpointResolved?.let { m ->
                runCatching { ImageEndpointMode.valueOf(m) }.getOrNull()
            },
        )
    }.toMutableList()

    val entries = this.entries.map { row ->
        val baseModel = jsonForBlobs.decodeFromString(LLMModel.serializer(), row.baseModelJson)
        val overrides = row.overridesJson?.let {
            jsonForBlobs.decodeFromString(ModelOverrides.serializer(), it)
        } ?: ModelOverrides()
        ModelEntry(
            providerInstanceId = row.providerInstanceId,
            baseModel = baseModel,
            overrides = overrides,
            isCustom = row.isCustom != 0,
            isHidden = row.isHidden != 0,
            uuid = row.id,
            userModifiedAt = row.userModifiedAt,
        )
    }.toMutableList()

    val stringListSerializer = ListSerializer(String.serializer())
    val groups = this.groups.map { row ->
        ModelGroup(
            id = row.id,
            name = row.name,
            memberEntryIds = jsonForBlobs
                .decodeFromString(stringListSerializer, row.memberEntryIdsJson)
                .toMutableList(),
            strategy = RoutingStrategy.valueOf(row.strategy),
            fallbackStrategy = FallbackStrategy.valueOf(row.fallbackStrategy),
            // [T-android-thinking-level-arch] decoded() (not valueOf()) so a
            // level string a NEWER build persisted (e.g. "MAX"/"ULTRA") can't
            // throw and blow up the whole DB load — which would fall back to the
            // JSON mirror, which fails identically on the same enum value,
            // wiping all providers/groups from the UI. Unknown → XHIGH.
            defaultThinkingLevel = row.defaultThinkingLevel?.let { ThinkingLevel.decoded(it) },
            contextLimitTokens = row.contextLimitTokens,
            lastContextLimitTokens = row.lastContextLimitTokens,
        )
    }.toMutableList()

    val entryLoopIds = this.loopIds.filter { it.kind == "entry" }
        .sortedBy { it.sortOrder }
        .map { it.targetId }
        .toMutableList()
    val groupLoopIds = this.loopIds.filter { it.kind == "group" }
        .sortedBy { it.sortOrder }
        .map { it.targetId }
        .toMutableList()

    val metaMap = this.meta.associate { it.key to it.value }
    return ProviderConfig(
        instances = instances,
        modelEntries = entries,
        modelGroups = groups,
        defaultPrimaryGroupId = metaMap[ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID],
        defaultSubGroupId = metaMap[ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID],
        voiceInputGroupId = metaMap[ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID],
        voiceOutputGroupId = metaMap[ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID],
        visionGroupId = metaMap[ProviderConfigMetaKeys.VISION_GROUP_ID],
        agentLoopModelEntryIds = entryLoopIds,
        agentLoopGroupIds = groupLoopIds,
        // [T-subagent-own-store] Restored from the meta row; a build that
        // predates it has no row and gets an empty roster, which normalize
        // turns into "built-in only" — the same as a fresh install.
        subAgents = metaMap[ProviderConfigMetaKeys.SUB_AGENTS_JSON]
            ?.let { raw ->
                runCatching {
                    jsonForBlobs.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(
                            com.openminis.app.data.model.SubAgentDefinition.serializer(),
                        ),
                        raw,
                    )
                }.getOrDefault(emptyList())
            }
            .orEmpty()
            .toMutableList(),
    )
}

/** Composite key shape used as ModelEntry id in DB and forward in JSON mirror. */
fun compositeEntryKey(instanceId: String, modelId: String): String = "$instanceId/$modelId"
