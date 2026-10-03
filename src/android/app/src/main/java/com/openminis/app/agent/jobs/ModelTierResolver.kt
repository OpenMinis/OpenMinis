package com.openminis.app.agent.jobs

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import org.json.JSONObject

// [T-p1-delegate-task] Which model tier a helper runs on (design v4 §5).
// Default `primary`; the delegating model opts IN to `sub` only for simple,
// bounded, self-verifiable work. No `auto`: a rule keyed on "is a Sub group
// configured" would downgrade complex work purely because a cheaper group
// exists, which has nothing to do with task fitness.
/**
 * [T-sub-agents-v1] Which model an Auto sub agent runs on.
 *
 * Only consulted when the definition pins no Model Group — a pinned one
 * outranks the model's choice entirely, because the user set it deliberately.
 *
 * Port of iOS `SubAgentModelChoice` (Agent/Jobs/ModelTierResolver.swift).
 */
enum class SubAgentModelChoice(val wire: String) {
    /** Continue with the same model this conversation runs on. */
    SAME_AS_PARENT("same_as_me"),

    /** The user's default group — typically their strongest model. */
    DEFAULT_MODEL("default_model"),

    /** The user's light group — typically smaller, faster, cheaper. */
    SUB_MODEL("sub_model"),
    ;

    companion object {
        /**
         * Unknown / absent values fall back to the parent's model: the safe
         * direction, since it is what the conversation is already using and
         * what the user actually chose. A fan-out that guessed
         * `default_model` would silently spend far more than the model the
         * user picked.
         */
        fun parse(raw: String?): SubAgentModelChoice {
            val v = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == v } ?: SAME_AS_PARENT
        }
    }
}

enum class HelperModelTier(val wire: String) {
    PRIMARY("primary"), SUB("sub");

    companion object {
        fun parse(raw: String?): HelperModelTier = when (raw?.trim()?.lowercase()) {
            "sub" -> SUB
            else -> PRIMARY
        }
    }
}

/**
 * [T-sub-agents-v1] Where a sub agent's model came from. Reported to the parent
 * model as `model_origin`, so a fan-out that behaves inconsistently can be
 * explained without guessing.
 *
 * Port of iOS `HelperModelOrigin`; the wire values must match.
 */
enum class HelperModelOrigin(val wire: String) {
    /** The definition pinned a Model Group — the user's choice, outranks all. */
    PINNED("pinned"),

    /** The parent conversation's own model, passed through. */
    INHERITED("inherited"),

    /** The user's default group, because the model asked for `default_model`. */
    DEFAULT_GROUP("default_group"),

    /** The user's light group, because the model asked for `sub_model`. */
    SUB_GROUP("sub_group"),
}

/** The binding a helper session should be created with, plus what was actually used. */
data class HelperModelResolution(
    /** `sessions.model_binding` JSON, or null to leave the row pinned to [seedModelId]. */
    val bindingJson: String?,
    /** `sessions.model_id` seed. */
    val seedModelId: String,
    val modelLabel: String,
    val tierUsed: HelperModelTier,
    /** [T-sub-agents-v1] How this binding was chosen. */
    val origin: HelperModelOrigin = HelperModelOrigin.INHERITED,
    /**
     * [T-sub-agents-v1] True when the definition pinned a group that could not
     * be routed, so this fell back to inheriting.
     *
     * Surfaced in the result JSON as `model_group_unavailable`: silently
     * running on a different model than the user pinned is exactly the kind of
     * thing that reads as a bug months later.
     */
    val modelGroupUnavailable: Boolean = false,
    /**
     * [T-android-subagent-model-strategy] The Model Group's own name, when the
     * binding came from one. The card names the group the user configured
     * rather than a group id, which means nothing to them.
     */
    val modelGroupName: String? = null,
    /**
     * [T-android-agent-model-identity] The RESOLVED binding's identity, as
     * three separate facts iOS keeps separate too (HelperModelIdentity.swift):
     * which configured entry the resolver picked, on which provider instance,
     * and which model id that entry names.
     *
     * [modelLabel] alone is a display string; it cannot answer "which of my two
     * Anthropic instances ran this" or survive a rename. These are what the
     * detail sheet's Model group / Model tier rows read, and what a payload
     * reloaded after a restart needs in order to still say where the run went.
     *
     * All nullable: a binding inherited from the parent conversation names no
     * entry of its own, and an old payload carries none of them.
     */
    val resolvedEntryId: String? = null,
    val resolvedProviderLabel: String? = null,
    val resolvedProviderType: String? = null,
    val resolvedModelId: String? = null,
    val resolvedModelName: String? = null,
)

object ModelTierResolver {
    private const val TAG = "ModelTierResolver"

    /**
     * - PRIMARY: copy the parent's `model_binding` verbatim when it has one (a
     *   group binding keeps group fallback; an entry pin stays a pin) and seed
     *   with the parent's `model_id`. With no binding, pin the parent's active
     *   entry.
     * - SUB: `defaultSubGroupId` → first credentialed member → group binding.
     *   Not configured / no usable member → degrade to PRIMARY, and say so in
     *   [HelperModelResolution.tierUsed] (the parent model reads `tier_used`).
     *
     * Android note: iOS routes through `ModelGroupRouter.resolve` for a
     * load-balanced pick; Android's group routing lives inside ChatViewModel's
     * boot-from-binding path, which the child vm runs itself once the binding
     * is on its row. So this only has to choose the binding, not the member —
     * the same split `ScheduledAgentRunner` already uses.
     */
    fun resolve(
        tier: HelperModelTier,
        repo: ProviderRepository,
        parentBindingJson: String?,
        parentModelId: String?,
        parentActiveEntryId: String?,
    ): HelperModelResolution? {
        fun primary(): HelperModelResolution? {
            val entries = repo.config.value.modelEntries
            if (parentBindingJson != null && parentModelId != null) {
                val label = entries.firstOrNull { it.model.id == parentModelId }?.model?.displayName ?: parentModelId
                return HelperModelResolution(parentBindingJson, parentModelId, label, HelperModelTier.PRIMARY)
            }
            val entry: ModelEntry? = parentActiveEntryId?.let { id -> entries.firstOrNull { it.id == id } }
                ?: parentModelId?.let { mid -> entries.firstOrNull { it.model.id == mid } }
            if (entry == null) {
                AppLogger.warning(TAG, "resolve(primary): parent has no resolvable model")
                return null
            }
            val binding = JSONObject().put("type", "entry").put("entryId", entry.id).toString()
            return HelperModelResolution(binding, entry.model.id, entry.model.displayName, HelperModelTier.PRIMARY)
        }

        return when (tier) {
            HelperModelTier.PRIMARY -> primary()
            HelperModelTier.SUB -> {
                val gid = repo.defaultSubGroupId
                val group = gid?.let { repo.group(it) }
                if (group == null) {
                    AppLogger.info(TAG, "resolve(sub): no Sub group configured — degrading to primary")
                    return primary()
                }
                val member = repo.availableMemberEntries(group).firstOrNull()
                if (member == null) {
                    AppLogger.info(TAG, "resolve(sub): Sub group '${group.name}' has no usable member — degrading to primary")
                    return primary()
                }
                val binding = JSONObject().put("type", "group").put("groupId", gid).toString()
                HelperModelResolution(
                    binding, member.model.id, member.model.displayName, HelperModelTier.SUB,
                    origin = HelperModelOrigin.SUB_GROUP,
                    modelGroupName = group.name,
                    resolvedEntryId = member.id,
                    resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                    resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                    resolvedModelId = member.model.id,
                    resolvedModelName = member.model.displayName,
                )
            }
        }
    }

    /**
     * [T-sub-agents-v1] Resolve the model a NAMED sub agent runs on.
     *
     * The order is the whole point, and it is not symmetric:
     *
     * 1. A definition that pins a Model Group wins outright — `model_choice` is
     *    ignored for it. The user set that pin deliberately, and letting a model
     *    argument override it would make the setting advisory.
     * 2. Otherwise ("Auto") the model's `model_choice` decides, defaulting to
     *    the parent's own model. That default is load-bearing: the user chose
     *    the model this conversation runs on, and a sub agent quietly running on
     *    a stronger, costlier one — several at a time in a fan-out — spends
     *    their quota on a choice they never saw.
     * 3. Every branch degrades to the parent's model rather than failing the
     *    delegation. A sub agent that runs on the wrong model still does the
     *    work; one that refuses to start does not.
     *
     * A pinned group that no longer routes is the one case worth reporting, so
     * it comes back with [HelperModelResolution.modelGroupUnavailable] set.
     */
    fun resolveForSubAgent(
        pinnedGroupId: String?,
        choice: SubAgentModelChoice,
        repo: ProviderRepository,
        parentBindingJson: String?,
        parentModelId: String?,
        parentActiveEntryId: String?,
    ): HelperModelResolution? {
        fun inherited() = resolve(
            HelperModelTier.PRIMARY, repo, parentBindingJson, parentModelId, parentActiveEntryId,
        )

        // 1. A pin outranks the model's choice.
        if (pinnedGroupId != null) {
            val group = repo.group(pinnedGroupId)
            val member = group?.let { repo.availableMemberEntries(it).firstOrNull() }
            if (group != null && member != null) {
                val binding = JSONObject().put("type", "group").put("groupId", pinnedGroupId).toString()
                return HelperModelResolution(
                    binding, member.model.id, member.model.displayName, HelperModelTier.PRIMARY,
                    origin = HelperModelOrigin.PINNED,
                    modelGroupName = group.name,
                    resolvedEntryId = member.id,
                    resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                    resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                    resolvedModelId = member.model.id,
                    resolvedModelName = member.model.displayName,
                )
            }
            AppLogger.warning(
                TAG,
                "resolveForSubAgent: pinned group '$pinnedGroupId' " +
                    (if (group == null) "no longer exists" else "has no usable member") +
                    " — inheriting the parent's model instead",
            )
            return inherited()?.copy(modelGroupUnavailable = true)
        }

        // 2. Auto: the model chooses, and "same as me" is the default.
        return when (choice) {
            SubAgentModelChoice.SAME_AS_PARENT -> inherited()
            SubAgentModelChoice.DEFAULT_MODEL -> {
                val gid = repo.defaultPrimaryGroupId
                val group = gid?.let { repo.group(it) }
                val member = group?.let { repo.availableMemberEntries(it).firstOrNull() }
                if (gid == null || member == null) {
                    AppLogger.info(TAG, "resolveForSubAgent(default_model): unroutable — inheriting")
                    inherited()
                } else {
                    val binding = JSONObject().put("type", "group").put("groupId", gid).toString()
                    HelperModelResolution(
                        binding, member.model.id, member.model.displayName, HelperModelTier.PRIMARY,
                        origin = HelperModelOrigin.DEFAULT_GROUP,
                        modelGroupName = group.name,
                        resolvedEntryId = member.id,
                        resolvedProviderLabel = repo.instance(member.providerInstanceId)?.label,
                        resolvedProviderType = repo.instance(member.providerInstanceId)?.providerType?.name?.lowercase(),
                        resolvedModelId = member.model.id,
                        resolvedModelName = member.model.displayName,
                    )
                }
            }
            SubAgentModelChoice.SUB_MODEL ->
                resolve(HelperModelTier.SUB, repo, parentBindingJson, parentModelId, parentActiveEntryId)
        }
    }
}
