package com.openminis.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * [T-sub-agents-v1] Hard limits on the Sub Agent roster.
 *
 * Port of iOS `SubAgentLimits` (Providers/SubAgentDefinition.swift).
 *
 * These are not defensive "just in case" numbers: the roster is injected into
 * the main conversation's system prompt on every turn, so the count and the
 * description length ARE the fixed per-request cost. Bounding the input is what
 * removes the need for a runtime token warning — a user cannot configure a
 * roster that blows the budget. At the maximum (10 x (40 + 200) chars) the
 * roster is ~800 tokens.
 *
 * Shared by the editor UI, the load-time clamp and the roster generator, so the
 * three cannot drift apart.
 */
object SubAgentLimits {
    /** Including the built-in one. */
    const val MAX_COUNT = 10
    const val NAME_MAX_LENGTH = 40

    /** The only free text that reaches the main conversation. */
    const val DESCRIPTION_MAX_LENGTH = 200

    /** Child-session only; bounded so one definition cannot eat the child's context. */
    const val INSTRUCTIONS_MAX_LENGTH = 4000
}

/**
 * [T-sub-agents-v1] One named sub agent the main model can delegate to by name.
 *
 * Port of iOS `SubAgentDefinition`. Field names are the iOS wire names verbatim
 * — this rides the existing provider-config blob, which both platforms read.
 *
 * Replaces the old anonymous delegation, where a child's whole identity was the
 * free-text `task` string and its model was a binary primary/sub tier. A
 * definition now carries the identity (name, description, instructions) and the
 * model (a pinned group, or Auto).
 */
@Serializable
data class SubAgentDefinition(
    val id: String = UUID.randomUUID().toString(),
    /**
     * The wire identifier, NOT a label.
     *
     * This is the `enum` of `subagent_task.agent`, the value the model has to
     * emit, and the key [SubAgentRoster.resolve] matches on. It is deliberately
     * never localized: localizing it would put the UI language into the tool
     * schema, make the model emit non-ASCII identifiers, and — worse — break
     * every stored reference the moment the user switched language or synced to
     * a device set to another one. [displayName] localizes the built-in for
     * presentation only.
     */
    var name: String,
    /**
     * What the main model reads to decide whether to pick this agent. Bounded
     * because it is the only part that costs main-conversation tokens, and not
     * localized for the same reason as [name] — the model is not the user.
     */
    var description: String,
    /** Appended to the child session's brief. Empty = nothing appended. */
    var instructions: String = "",
    /** null = Auto: the delegating model chooses with `model_choice`. */
    var modelGroupId: String? = null,
    /**
     * [T-subagent-thinking-override] Reasoning intensity for runs of this sub
     * agent, overriding whatever the resolved model group defaults to.
     *
     * null = inherit (the group's `defaultThinkingLevel`, else the delegating
     * conversation) — the existing behaviour, and what every definition has
     * until the user sets one. Mirrors [ModelGroup.defaultThinkingLevel]: a
     * group sets the default for sessions bound to it, and this overrides that
     * for this sub agent, the same way a session-level pick overrides a group.
     */
    var thinkingLevelOverride: ThinkingLevel? = null,
    val isBuiltIn: Boolean = false,
    var sortOrder: Int = 0,
    /**
     * [T-android-provider-iso8601-wire] Epoch millis in memory, but
     * serialized as an ISO-8601 string on the wire (see
     * [com.openminis.app.backup.Iso8601MillisSerializer]). iOS
     * `SubAgentDefinition.updatedAt` is a `Date`, encoded with
     * `.iso8601` in the backup exporter — a bare Long here made a restore
     * of an iOS-produced provider_config.json fail JSON decoding entirely
     * (kotlinx.serialization aborts the whole document on one bad element),
     * dragging every provider/model/sub-agent in the same file down to
     * imported=0. The serializer still accepts a legacy plain numeric epoch
     * for old Android-written packages.
     */
    @kotlinx.serialization.Serializable(with = com.openminis.app.backup.Iso8601MillisSerializer::class)
    var updatedAt: Long = System.currentTimeMillis(),
) {
    /**
     * [T-sub-agents-v1] Localized label for the settings list and editor.
     *
     * ONLY the built-in has one — a user-created agent is shown exactly as the
     * user named it. This is presentation only: [name] stays canonical English
     * because it is the tool-schema enum value the model must emit.
     */
    fun displayName(context: android.content.Context): String =
        if (isBuiltIn) context.getString(com.openminis.app.R.string.sub_agent_builtin_name) else name

    /** Localized description for the settings screen, mirroring [displayName]. */
    fun displayDescription(context: android.content.Context): String =
        if (isBuiltIn) context.getString(com.openminis.app.R.string.sub_agent_builtin_description) else description

    /**
     * Field-level clamp applied on load and on save.
     *
     * Synced data can come from a future build or a hand-edited file, so the UI
     * limits alone are not enough — see [SubAgentRoster.normalize].
     */
    fun clamped(): SubAgentDefinition = copy(
        name = name.take(SubAgentLimits.NAME_MAX_LENGTH),
        description = description.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH),
        instructions = instructions.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH),
    )

    /** Whether any field was over its limit — used only to decide whether to log. */
    val exceedsLimits: Boolean
        get() = name.length > SubAgentLimits.NAME_MAX_LENGTH ||
            description.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH ||
            instructions.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH

    companion object {
        /**
         * The built-in definition's fixed id. Its display name is localizable
         * and user-editable; the id is what code and persisted payloads key on.
         */
        const val BUILT_IN_ID = "builtin.general"

        /**
         * [T-sub-agents-v1] The sub agent tool's wire name.
         *
         * Renamed from `delegate_task` so it reads as "the sub agent tool"
         * rather than a generic verb. The separate `agent_status` tool is
         * folded in as an `action`, so the model sees ONE tool for delegating
         * and for inspecting or stopping what it delegated.
         */
        const val TOOL_NAME = "subagent_task"

        /** The built-in's stored name, deliberately NOT localized. See [name]. */
        const val BUILT_IN_NAME = "General Sub Agent"

        /** Also not localized: this goes into the system-prompt roster. */
        const val BUILT_IN_DESCRIPTION =
            "Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, " +
                "digesting bulk output into a conclusion, or running independent branches in parallel."

        /** The built-in general sub agent, inserted by normalize when absent. */
        fun makeBuiltIn(sortOrder: Int = 0): SubAgentDefinition = SubAgentDefinition(
            id = BUILT_IN_ID,
            name = BUILT_IN_NAME,
            description = BUILT_IN_DESCRIPTION,
            instructions = "",
            modelGroupId = null,
            isBuiltIn = true,
            sortOrder = sortOrder,
        )
    }
}

/**
 * [T-sub-agents-v1] Roster-level rules: the built-in must exist, the list is
 * bounded, ordering is the disclosure order.
 *
 * Free functions on the list rather than a store type, so the load path, the
 * sync merge and the tests all share one implementation.
 */
object SubAgentRoster {

    /**
     * Normalise a decoded roster: guarantee the built-in, clamp every field,
     * bound the count, and renumber [SubAgentDefinition.sortOrder] densely
     * from 0.
     *
     * NEVER throws and never drops the built-in: this runs on data that may
     * have arrived from a possibly newer build, and a bad roster must not be
     * able to block startup. Anything discarded is logged.
     */
    fun normalize(
        input: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): List<SubAgentDefinition> {
        val list = input.toMutableList()

        // The built-in is pinned to the front regardless of its stored
        // sortOrder, so a synced roster that reordered it cannot bury it or
        // cost it its slot in the count bound below.
        val builtInIndex = list.indexOfFirst { it.id == SubAgentDefinition.BUILT_IN_ID }
        var builtIn: SubAgentDefinition
        if (builtInIndex >= 0) {
            builtIn = list.removeAt(builtInIndex)
            // The built-in's name and description are canonical English (they
            // are the tool-schema enum and the roster the model reads). Restore
            // them on every load: a row written before they were fixed carries
            // whatever the UI language was at the time, and one written by a
            // device set to another language would otherwise arrive here and
            // change what the model has to emit. The user's own fields — model
            // group, instructions, order — are untouched.
            if (builtIn.name != SubAgentDefinition.BUILT_IN_NAME ||
                builtIn.description != SubAgentDefinition.BUILT_IN_DESCRIPTION
            ) {
                log?.invoke("[SubAgents] restoring the built-in's canonical name/description")
                builtIn = builtIn.copy(
                    name = SubAgentDefinition.BUILT_IN_NAME,
                    description = SubAgentDefinition.BUILT_IN_DESCRIPTION,
                )
            }
        } else {
            builtIn = SubAgentDefinition.makeBuiltIn()
            log?.invoke("[SubAgents] built-in definition missing — reinserting")
        }

        // Custom entries keep the user's order; ties break by id so the result
        // is deterministic across devices.
        list.sortWith(compareBy({ it.sortOrder }, { it.id }))

        // Drop anything past the bound (the built-in already holds one slot).
        val allowedCustom = maxOf(0, SubAgentLimits.MAX_COUNT - 1)
        var custom: List<SubAgentDefinition> = list
        if (custom.size > allowedCustom) {
            val dropped = custom.drop(allowedCustom).map { it.name }
            log?.invoke(
                "[SubAgents] roster over the limit — keeping $allowedCustom of ${custom.size} " +
                    "custom definitions, dropping: ${dropped.joinToString(", ")}",
            )
            custom = custom.take(allowedCustom)
        }

        if (builtIn.exceedsLimits || custom.any { it.exceedsLimits }) {
            log?.invoke("[SubAgents] one or more definitions exceeded field limits — truncating")
        }

        val out = mutableListOf(builtIn.clamped().copy(sortOrder = 0))
        val seenNames = mutableSetOf(nameKey(builtIn.name))
        var next = 1
        for (def in custom) {
            // A custom definition must not claim the built-in flag: isBuiltIn
            // drives "cannot delete" in the UI, and a synced row could assert
            // it. Same for stealing the built-in's id.
            if (def.isBuiltIn || def.id == SubAgentDefinition.BUILT_IN_ID) continue
            // A nameless definition cannot be addressed: the name IS the enum
            // value the model emits and the key resolve() matches on. Left in,
            // it would advertise an empty string in the tool schema and put a
            // blank line in the roster the model reads. The settings screen
            // creates one the moment "Add" is tapped, so this is the ordinary
            // state of a row the user backed out of, not a corrupt one.
            if (def.name.isBlank()) continue
            // Two rows sharing a name make resolution ambiguous — first match
            // wins and the user cannot see why the other never runs.
            if (seenNames.contains(nameKey(def.name))) continue
            seenNames.add(nameKey(def.name))
            out.add(def.clamped().copy(sortOrder = next))
            next += 1
        }
        return out
    }

    /**
     * The definition a delegation should run under.
     *
     * Matching is case-insensitive and whitespace-trimmed because the name
     * comes back from a model. A null/blank name = the built-in. A name that
     * matches nothing returns null, which the caller turns into
     * `unknown_agent`.
     */
    fun resolve(name: String?, roster: List<SubAgentDefinition>): SubAgentDefinition? {
        val raw = name?.let { nameKey(it) }
        if (raw.isNullOrEmpty()) {
            return roster.firstOrNull { it.id == SubAgentDefinition.BUILT_IN_ID } ?: roster.firstOrNull()
        }
        return roster.firstOrNull { nameKey(it.name) == raw }
    }

    /**
     * The comparison form of a name: trimmed, case-folded and diacritic-folded.
     *
     * The name comes back from a MODEL, which may not reproduce accents exactly,
     * and the stored side is user-typed and may carry stray whitespace. Folding
     * both is what stops a roster entry named " Résumé-agent " from resolving on
     * one platform and failing with `unknown_agent` on the other. Matches iOS
     * SubAgentDefinition.nameKey.
     */
    fun nameKey(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()

    /** Hoisted: the merge path calls [nameKey] O(local × remote) times. */
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Outcome of [mergeBackup]: the roster to save plus the report counts. */
    data class BackupMerge(val roster: List<SubAgentDefinition>, val written: Int, val skipped: Int)

    /**
     * [T-android-backup-subagents] Fold a backup package's custom sub agents
     * into the local roster, with iOS's restore rules
     * (BackupImporter+Categories importSubAgents -> SubAgentRoster.merge):
     *  - an id not known locally is added, unless its NAME matches a local
     *    agent: the model picks agents by name, so two entries it cannot tell
     *    apart would make one unreachable; the local one stays;
     *  - a known id is replaced only when the package's copy is newer, so
     *    restoring an old package cannot roll back an agent edited since;
     *  - the built-in is never touched, and nothing is deleted.
     * The result is normalized (count bound, dense sortOrder).
     */
    fun mergeBackup(
        local: List<SubAgentDefinition>,
        incoming: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): BackupMerge {
        val out = local.toMutableList()
        var written = 0
        var skipped = 0
        for (r in incoming) {
            if (r.isBuiltIn || r.id == SubAgentDefinition.BUILT_IN_ID) { skipped++; continue }
            val at = out.indexOfFirst { it.id == r.id }
            if (at >= 0) {
                if (r.updatedAt > out[at].updatedAt) { out[at] = r.copy(isBuiltIn = false); written++ } else skipped++
                continue
            }
            if (out.any { nameKey(it.name) == nameKey(r.name) }) {
                log?.invoke("backup sub agent '${r.name}' skipped: a local agent already has that name")
                skipped++
                continue
            }
            out.add(r.copy(isBuiltIn = false))
            written++
        }
        return BackupMerge(normalize(out, log), written, skipped)
    }
}
