package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.applyUnrecognizedModelDefaults
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.model.withInferredImageModality
import com.openminis.app.data.model.withInferredVideoModality
import com.openminis.app.data.model.withInferredVoiceModality

/**
 * Pure half of [ProviderRepository.replaceEntries]: given what the provider
 * reported and what we already store, decide the entry list a refresh should
 * produce.
 *
 * Extracted for two reasons.
 *
 *  1. **The ratchet.** ProviderRepository is past its ceiling; the refresh
 *     decision was ~60 lines of nested mapping in the middle of it and is
 *     exactly the kind of pure logic that belongs in its own file.
 *  2. **It is the part that needs tests.** Carrying forward `uuid` / `overrides`
 *     / `isHidden` across a refresh is what keeps a user's renames and hidden
 *     flags alive, and getting it wrong is silent: the entry still exists, it
 *     just loses everything the user did to it. [ModelRefreshPlannerTest] pins
 *     that down without a database.
 *
 * Preference rules, all mirrored from iOS `replaceEntries`:
 *  - an API-reported model reuses the prior entry's uuid, overrides and
 *    isHidden; a model the provider stopped returning disappears unless it was
 *    custom or a voice-template seed;
 *  - when the same model id exists as BOTH a custom and an API entry, the
 *    non-custom row is the one carried forward (the custom row is the user's
 *    own copy and survives through [Plan.remainingCustom] instead);
 *  - a voice-template model's modality wins over the API's inferred modality
 *    ([T-android-provider-voice]) — MiMo and friends report no audio bits for
 *    their ASR/TTS ids.
 */
internal object ModelRefreshPlanner {

    data class Plan(
        val refreshed: List<ModelEntry>,
        val remainingCustom: List<ModelEntry>,
        val preservedVoice: List<ModelEntry>,
        /** Ids the provider reported — the custom/seed keep-tests use this. */
        val refreshedModelIds: Set<String>,
        /** Template voice models by id, empty when the instance has no template. */
        val templateVoiceModelById: Map<String, LLMModel>,
    )

    /**
     * @param models what the provider's `/models` returned, ALREADY filtered by
     *   any deletion tombstones — this planner has no opinion about deletions
     *   and must stay pure so it can be unit-tested against literals.
     */
    fun plan(
        instanceId: String,
        models: List<LLMModel>,
        existing: List<ModelEntry>,
        instanceBaseURL: String?,
    ): Plan {
        // Build lookup: baseModel.id → existing entry (prefer non-custom if duplicates exist)
        val existingByModelId = mutableMapOf<String, ModelEntry>()
        for (entry in existing) {
            val key = entry.baseModel.id
            val current = existingByModelId[key]
            if (current == null || current.isCustom) {
                existingByModelId[key] = entry
            }
        }

        val refreshedModelIds = models.map { it.id }.toSet()
        val templateVoiceModelById = VoiceProviderTemplate.template(instanceBaseURL)?.mockModels
            ?.filter { it.hasVoiceModality }
            ?.associateBy { it.id }
            ?: emptyMap()

        val refreshed = models.map { model ->
            val prior = existingByModelId[model.id]
            // Dedicated ASR/TTS id/name patterns fill the exact voice shape when
            // the API returned no modality info; the template's shape wins last.
            var resolved = model.withInferredVoiceModality()
                .withInferredVideoModality()
                .withInferredImageModality()
            templateVoiceModelById[model.id]?.let { tplModel ->
                resolved = resolved.copy(
                    inputModalities = tplModel.inputModalities,
                    outputModalities = tplModel.outputModalities,
                )
            }
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = applyUnrecognizedModelDefaults(resolved),
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.isHidden ?: false,
                uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
            )
        }

        // Keep custom entries that weren't in the refreshed list.
        val remainingCustom = existing.filter { it.isCustom && it.baseModel.id !in refreshedModelIds }

        // [T-android-provider-voice] Preserve voice-template SEED entries the
        // /models list didn't return. Vendors like MiMo never expose their
        // ASR/TTS voices via /v1/models, so without this every refresh wipes
        // the seeds (iOS regression cffbec0e: MiMo launch model count 15→11).
        // Only genuine template members are protected — never stray audio
        // entries. Mirrors iOS replaceEntries preservedVoice.
        val preservedVoice = existing.filter { e ->
            !e.isCustom &&
                e.baseModel.id !in refreshedModelIds &&
                e.baseModel.id in templateVoiceModelById
        }

        return Plan(refreshed, remainingCustom, preservedVoice, refreshedModelIds, templateVoiceModelById)
    }
}
