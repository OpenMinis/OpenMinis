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
    const val COMPACT_FALLBACK_GROUP_ID = "compact_fallback_group_id"
    /**
     * [T-android-translation-single-source] LEGACY: no longer written or read (see
     * [toSnapshot] / [toProviderConfig]). Kept as the name of the row that may still
     * exist in an upgraded device's provider_config_meta table.
     */
    const val DEFAULT_TRANSLATION_MODEL_ID = "default_translation_model_id"
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
    // [T-android-composite-key-collision] Two entries on the SAME instance with
    // the SAME base-model id are legitimate — the common "same model, different
    // overrides" custom entry — but the composite key is (instance, model), so
    // both would land on one `provider_model_entries.id` row and
    // @Insert(REPLACE) would silently keep only one. The survivors' in-memory
    // ids would also be identical, which is exactly the duplicate LazyColumn
    // key [T-android-agentloop-dup-key-crash] warns about. Only the COLLIDING
    // entries get the uuid appended, so the overwhelmingly common single-entry
    // case keeps its stable "{instanceId}/{modelId}" id and every persisted
    // session binding keeps resolving.
    val idMap = HashMap<String, String>(modelEntries.size)
    val takenKeys = HashSet<String>(modelEntries.size)
    for (entry in modelEntries) {
        val base = compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
        idMap[entry.uuid] = if (takenKeys.add(base)) base else "$base#${entry.uuid}"
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
            pinned = if (inst.pinned) 1 else 0,
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
    compactFallbackGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.COMPACT_FALLBACK_GROUP_ID, it))
    }
    // [T-android-translation-single-source] defaultTranslationModelId is no longer
    // written as a meta row. Nothing reads ProviderConfig.defaultTranslationModelId
    // — live translation resolves TranslationPrefs — so persisting it in provider.db
    // made a second, never-consulted copy of a setting that already has one source of
    // truth (and a delete path that scrubbed a slot nobody resolved). Old snapshots
    // that still carry the row simply stop being read.
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
            // [T-android-provider-enum-decode] decoded, not valueOf(): a type or
            // credential name this build doesn't know degrades to `unsupported`
            // / `apiKey` instead of throwing and killing the whole provider load.
            providerType = ProviderType.decoded(row.providerType),
            credentialType = decodePersistedEnum(row.credentialType, ProviderCredential.apiKey),
            isEnabled = row.isEnabled != 0,
            pinned = row.pinned != 0,
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
            // [T-android-provider-enum-decode] Both routing enums degrade to the
            // safe default rather than throwing on a name a newer build wrote —
            // see the helper's KDoc for why a throw here costs every provider.
            strategy = decodePersistedEnum(row.strategy, RoutingStrategy.fallback),
            fallbackStrategy = decodePersistedEnum(row.fallbackStrategy, FallbackStrategy.default),
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
        compactFallbackGroupId = metaMap[ProviderConfigMetaKeys.COMPACT_FALLBACK_GROUP_ID],
        // [T-android-translation-single-source] Deliberately NOT read any more:
        // the field keeps its default (null) so the config still loads; the value
        // is not consulted anywhere (translation resolves TranslationPrefs), and
        // loading it made an abandoned DB row look like live state.
        agentLoopModelEntryIds = entryLoopIds,
        agentLoopGroupIds = groupLoopIds,
    )
}

/** Composite key shape used as ModelEntry id in DB and forward in JSON mirror. */
fun compositeEntryKey(instanceId: String, modelId: String): String = "$instanceId/$modelId"

/**
 * [T-android-provider-enum-decode] Name-based decode of an enum that came from
 * persisted storage, which NEVER throws.
 *
 * `Enum.valueOf` on a value THIS build doesn't know — a `ProviderType` /
 * `RoutingStrategy` / `FallbackStrategy` / `ProviderCredential` case a NEWER
 * build wrote — threw out of [toProviderConfig] mid-load. `loadConfigSuspending`
 * deliberately refuses to fall back to the JSON mirror once a DB snapshot read
 * fails ([T-android-provider-empty-load-wipe]), and the mirror decode throws on
 * the very same value, so one future enum case made EVERY provider feature dead
 * until provider.db was repaired by hand. `ThinkingLevel.decoded()` and
 * `ImageEndpointMode` were already hardened for exactly this; the four remaining
 * `valueOf` calls were not.
 */
private inline fun <reified T : Enum<T>> decodePersistedEnum(raw: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback
