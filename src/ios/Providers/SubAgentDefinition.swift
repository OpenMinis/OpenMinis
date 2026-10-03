import Foundation

/// Hard limits on the Sub Agent roster.
///
/// [T-sub-agents-v1] These are not defensive "just in case" numbers: the roster
/// is injected into the main conversation's system prompt on every turn, so the
/// count and the description length ARE the fixed per-request cost. Bounding the
/// input is what removes the need for a runtime token warning — a
/// user cannot configure a roster that blows the budget. At the maximum
/// (10 × (40 + 200) chars) the roster is ~800 tokens.
///
/// Shared by the editor UI, the store's load-time clamp and the roster
/// generator, so the three cannot drift apart.
enum SubAgentLimits {
    /// Including the built-in one.
    static let maxCount = 10
    static let nameMaxLength = 40
    /// The only free text that reaches the main conversation.
    static let descriptionMaxLength = 200
    /// Child-session only; bounded so one definition cannot eat the child's context.
    static let instructionsMaxLength = 4000
}

/// One named sub agent the main model can delegate to by name.
///
/// [T-sub-agents-v1] Sits on top of the existing primary/sub tier mechanism
/// rather than replacing it: a definition with no `modelGroupId` keeps today's
/// behaviour exactly (the delegating model picks a tier per call).
struct SubAgentDefinition: Identifiable, Codable, Hashable {
    /// The built-in definition's fixed id. Its display name is localizable and
    /// user-editable; the id is what code and persisted payloads key on.
    static let builtInId = "builtin.general"

    /// [T-sub-agents-v1] The sub agent tool's wire name.
    ///
    /// Renamed from `delegate_task` so it reads as "the sub agent tool" rather
    /// than a generic verb, matching the Sub Agents wording everywhere else.
    /// The separate `agent_status` tool is folded in as an `action`, so the
    /// model sees ONE tool for delegating and for
    /// inspecting or stopping what it delegated.
    ///
    /// No compatibility shim for the old names: the feature has not shipped, so
    /// there are no transcripts in the wild carrying them.
    static let toolName = "subagent_task"

    /// [T-sub-agents-v1] The built-in's stored name, deliberately NOT localized.
    ///
    /// `name` is an identifier before it is a label: it is the `enum` of
    /// `delegate_task.agent`, the value the model has to emit, and the key
    /// `SubAgentRoster.resolve(name:)` matches on. Localizing it would put the
    /// UI language into the tool schema, make the model emit non-ASCII
    /// identifiers, and — worse — break every stored reference the moment the
    /// user switched language or synced to a device set to another one.
    ///
    /// The UI shows `displayName` instead, which localizes this one value for
    /// presentation only. User-created agents are unaffected: their names are
    /// whatever the user typed, in whatever language they typed it.
    static let builtInName = "General Sub Agent"

    /// Also not localized: this goes into the system-prompt roster the model
    /// reads to decide what to delegate. It should say the same thing whatever
    /// language the app's UI happens to be in — the model is not the user.
    /// `displayDescription` localizes it for the settings screen.
    static let builtInDescription =
        "Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, "
        + "digesting bulk output into a conclusion, or running independent branches in parallel."

    /// Localized label for the settings list and editor. Only the built-in has
    /// one — a user-created agent is shown exactly as the user named it.
    var displayName: String {
        isBuiltIn ? AppLocalized("General Sub Agent") : name
    }

    /// Localized description for the settings screen, mirroring `displayName`.
    var displayDescription: String {
        isBuiltIn
            ? AppLocalized("Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, digesting bulk output into a conclusion, or running independent branches in parallel.")
            : description
    }

    let id: String
    var name: String
    /// What the main model reads to decide whether to pick this agent.
    /// Bounded because it is the only part that costs main-conversation tokens.
    var description: String
    /// Appended to the child session's brief. Empty = nothing appended.
    var instructions: String
    /// nil = the delegating model chooses primary/sub per task (today's behaviour).
    var modelGroupId: String?
    /// [T-subagent-thinking-override] Reasoning intensity for runs of this sub
    /// agent, overriding whatever the resolved model group defaults to.
    ///
    /// nil = inherit (the group's `defaultThinkingLevel`, else the parent
    /// conversation) — the existing behaviour, and what every definition has
    /// until the user sets one. Mirrors `ModelGroup.defaultThinkingLevel`: a
    /// group sets the default for sessions bound to it, and this overrides that
    /// for this sub agent, the same way a session-level pick overrides a group.
    var thinkingLevelOverride: ThinkingLevel?
    let isBuiltIn: Bool
    var sortOrder: Int
    var updatedAt: Date

    init(id: String = UUID().uuidString,
         name: String,
         description: String,
         instructions: String = "",
         modelGroupId: String? = nil,
         thinkingLevelOverride: ThinkingLevel? = nil,
         isBuiltIn: Bool = false,
         sortOrder: Int = 0,
         updatedAt: Date = Date()) {
        self.id = id
        self.name = name
        self.description = description
        self.instructions = instructions
        self.modelGroupId = modelGroupId
        self.thinkingLevelOverride = thinkingLevelOverride
        self.isBuiltIn = isBuiltIn
        self.sortOrder = sortOrder
        self.updatedAt = updatedAt
    }

    /// The built-in general sub agent, inserted by `ensureBuiltIn` when absent.
    ///
    /// Its name and description are localized at creation time. They are stored
    /// (not resolved per read) because the name is a user-editable field and the
    /// description is what the model sees — a value that changed under the user
    /// when they switched language would be worse than a stale one.
    static func makeBuiltIn(sortOrder: Int = 0) -> SubAgentDefinition {
        SubAgentDefinition(
            id: builtInId,
            name: builtInName,
            // Read by the delegating model to decide what to hand over, so it
            // names the shapes of work that pay off — many rounds of searching,
            // bulk output it only needs a conclusion from, independent branches
            // it can run at once — rather than merely saying "general". Kept
            // under the 200-char bound the roster budget assumes.
            description: builtInDescription,
            instructions: "",
            modelGroupId: nil,
            isBuiltIn: true,
            sortOrder: sortOrder
        )
    }

    // MARK: - Clamping

    /// Field-level clamp applied on load and on save.
    ///
    /// Synced data can come from a future build or a hand-edited file, so the UI
    /// limits alone are not enough — see `SubAgentRoster.normalize`.
    func clamped() -> SubAgentDefinition {
        var copy = self
        copy.name = String(name.prefix(SubAgentLimits.nameMaxLength))
        copy.description = String(description.prefix(SubAgentLimits.descriptionMaxLength))
        copy.instructions = String(instructions.prefix(SubAgentLimits.instructionsMaxLength))
        return copy
    }

    /// Whether any field was over its limit — used only to decide whether to log.
    var exceedsLimits: Bool {
        name.count > SubAgentLimits.nameMaxLength
            || description.count > SubAgentLimits.descriptionMaxLength
            || instructions.count > SubAgentLimits.instructionsMaxLength
    }
}

/// Roster-level rules: the built-in must exist, the list is bounded, ordering is
/// the disclosure order.
///
/// Free functions on the array rather than a store type, so the load path, the
/// sync merge and the tests all share one implementation.
enum SubAgentRoster {

    /// Normalise a decoded roster: guarantee the built-in, clamp every field,
    /// bound the count, and renumber `sortOrder` densely from 0.
    ///
    /// [T-sub-agents-v1] NEVER throws and never drops the built-in: this runs on
    /// data that arrived over iCloud from a possibly newer build, and a bad
    /// roster must not be able to block startup. Anything discarded is logged.
    static func normalize(_ input: [SubAgentDefinition],
                          log: ((String) -> Void)? = nil) -> [SubAgentDefinition] {
        var list = input

        // The built-in is pinned to the front regardless of its stored
        // sortOrder, so a synced roster that reordered it cannot bury it or
        // cost it its slot in the count bound below.
        let builtInIndex = list.firstIndex { $0.id == SubAgentDefinition.builtInId }
        var builtIn: SubAgentDefinition
        if let idx = builtInIndex {
            builtIn = list.remove(at: idx)
            // The built-in's name and description are canonical English (they
            // are the tool-schema enum and the roster the model reads). Restore
            // them on every load: a row written before they were fixed carries
            // whatever the UI language was at the time, and one written by a
            // device set to another language would otherwise arrive here and
            // change what the model has to emit. The user's own fields —
            // model group, instructions, order — are untouched.
            if builtIn.name != SubAgentDefinition.builtInName
                || builtIn.description != SubAgentDefinition.builtInDescription {
                log?("[SubAgents] restoring the built-in's canonical name/description")
                builtIn.name = SubAgentDefinition.builtInName
                builtIn.description = SubAgentDefinition.builtInDescription
            }
        } else {
            builtIn = SubAgentDefinition.makeBuiltIn()
            log?("[SubAgents] built-in definition missing — reinserting")
        }

        // Custom entries keep the user's order; ties break by id so the result
        // is deterministic across devices.
        list.sort { ($0.sortOrder, $0.id) < ($1.sortOrder, $1.id) }

        // Drop anything past the bound (the built-in already holds one slot).
        let allowedCustom = max(0, SubAgentLimits.maxCount - 1)
        if list.count > allowedCustom {
            let dropped = list.suffix(from: allowedCustom).map(\.name)
            log?("[SubAgents] roster over the limit — keeping \(allowedCustom) of \(list.count) custom definitions, dropping: \(dropped.joined(separator: ", "))")
            list = Array(list.prefix(allowedCustom))
        }

        if builtIn.exceedsLimits || list.contains(where: { $0.exceedsLimits }) {
            log?("[SubAgents] one or more definitions exceeded field limits — truncating")
        }

        // A custom definition must not claim the built-in flag: `isBuiltIn`
        // drives "cannot delete" in the UI, and a synced row could assert it.
        builtIn = builtIn.clamped()
        var out: [SubAgentDefinition] = [builtIn]
        for (i, def) in list.enumerated() {
            var d = def.clamped()
            if d.isBuiltIn || d.id == SubAgentDefinition.builtInId { continue }
            d.sortOrder = i + 1
            out.append(d)
        }
        out[0].sortOrder = 0
        return out
    }

    /// The definition a delegation should run under.
    ///
    /// Matching is case-insensitive and whitespace-trimmed because the name
    /// comes back from a model. `nil` name = the built-in. A name that matches
    /// nothing returns nil, which the caller turns into `unknown_agent`.
    static func resolve(name: String?, in roster: [SubAgentDefinition]) -> SubAgentDefinition? {
        guard let raw = name?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return roster.first { $0.id == SubAgentDefinition.builtInId } ?? roster.first
        }
        return roster.first { nameKey($0.name) == nameKey(raw) }
    }

    /// [T-subagent-sync-dedupe] The canonical form two names are compared by.
    ///
    /// Folded exactly the way `resolve(name:)` matches — trimmed, case- and
    /// diacritic-insensitive — so "two agents the merge considers the same" and
    /// "two agents the model cannot tell apart" are by construction the same
    /// question. If these ever diverge, the merge would keep a pair that
    /// `resolve` can only ever reach one of, which is the bug this exists to
    /// prevent.
    static func nameKey(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    /// [T-subagent-sync-dedupe] Merge two rosters, collapsing same-NAME records
    /// that carry different ids.
    ///
    /// Why name and not id: ids are UUIDs minted per device, but the NAME is
    /// what the model emits and what `resolve(name:)` matches on. Two devices
    /// that each add "coding-agent" produce two records that are distinct by id
    /// and indistinguishable to the model — the roster is injected every turn,
    /// so the duplicate costs prompt budget on all of them, `resolve` can only
    /// ever reach the first, and both count against `SubAgentLimits.maxCount`.
    ///
    /// Whole-record replacement, NOT a field-level merge: the loser is dropped
    /// entirely rather than having its instructions/modelGroupId folded into the
    /// winner. That matches the whole-blob granularity the rest of
    /// ProviderConfigV2 syncs at, and a field-level merge of two independently
    /// authored agents would synthesise a third agent neither user wrote.
    ///
    /// Determinism is the point of the tie-break. Both devices run this against
    /// mirrored inputs and must reach the SAME winner without talking to each
    /// other, or they re-upload rival rosters forever. Newer `updatedAt` wins;
    /// on a tie the lexicographically smaller id wins — the same rule, and the
    /// same reasoning, as the model-group name collapse in
    /// `CloudSyncEngine.mergeProviderConfig`.
    ///
    /// The built-in never loses and is never deduped away: `normalize` pins it
    /// first and restores its canonical name, so a remote record that collides
    /// with its name is the record that gets dropped.
    static func merge(local: [SubAgentDefinition],
                      remote: [SubAgentDefinition],
                      log: ((String) -> Void)? = nil) -> [SubAgentDefinition] {
        var winners: [String: SubAgentDefinition] = [:]   // nameKey -> winner
        var order: [String] = []                          // nameKey, first-seen

        func consider(_ def: SubAgentDefinition) {
            let key = nameKey(def.name)
            guard let held = winners[key] else {
                winners[key] = def
                order.append(key)
                return
            }
            if held.id == def.id {
                // Same record on both sides — ordinary last-writer-wins.
                if def.updatedAt > held.updatedAt { winners[key] = def }
                return
            }
            // The built-in outranks any same-named custom record, whichever
            // side it came from and whatever its timestamp says.
            if held.id == SubAgentDefinition.builtInId { return }
            if def.id == SubAgentDefinition.builtInId {
                winners[key] = def
                return
            }
            let winner: SubAgentDefinition
            if def.updatedAt != held.updatedAt {
                winner = def.updatedAt > held.updatedAt ? def : held
            } else {
                winner = def.id < held.id ? def : held
            }
            let loser = winner.id == def.id ? held : def
            winners[key] = winner
            log?("[SubAgents] name collision '\(def.name)' — kept \(winner.id.prefix(8)), dropped \(loser.id.prefix(8))")
        }

        // Local first so a first-seen ordering favours the arrangement the user
        // already sees on this device; `normalize` renumbers sortOrder after.
        for d in local { consider(d) }
        for d in remote { consider(d) }

        return normalize(order.compactMap { winners[$0] }, log: log)
    }
}
