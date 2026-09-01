import SwiftUI

/// Phase 2 §3 — the Thinking Rules section on a provider's detail page.
///
/// Shows the rules that decide what thinking parameters this provider receives, in the
/// order they are evaluated (design §7):
///   • User rules sit ABOVE built-ins and can be added, edited, deleted and reordered.
///     Ordering is priority — the first rule whose scope matches the model wins.
///   • Built-ins are COLLAPSED behind a single "Default rules (N)" row rather than listed
///     one per line. A user tuning their own rules cares that defaults exist and what
///     currently wins; the individual vendor shapes are reference material, one tap away.
///     They remain read-only and un-deletable — a custom rule placed above one overrides
///     it, but removing the floor could leave a provider with nothing matching at all.
///
/// [T-thinking-rules-ui-fix] This view returns a `Group` of Sections, NOT a bare
/// `Section`. Presentation modifiers (`.sheet`) attached to a Section inside a Form have
/// no stable host: on device, tapping a row dismissed the whole settings stack back to the
/// chat home instead of presenting. The sheet is now owned by the enclosing screen via a
/// binding, which is also how every other sheet on this page is wired.
struct ThinkingRulesSection: View {
    let instanceId: String
    /// A representative model id, used for the "which rule wins" preview. Taken from the
    /// provider's first model entry so the preview reflects something real.
    let sampleModelId: String?

    @Binding var editorRequest: ThinkingRuleEditorRequest?

    /// [T-thinking-rules-ui-scope] Re-read on every render so the notice tracks the
    /// Chat Completions / Responses API picker on this same screen live, rather than
    /// freezing whatever was true when the page opened.
    @ObservedObject private var store = ProviderConfigStore.shared

    @State private var userRules: [ThinkingRule] = []
    @State private var builtInRules: [ThinkingRule] = []
    @State private var showBuiltIns = false
    @State private var didLoad = false

    /// Whether custom rules can actually affect this provider's requests.
    ///
    /// [T-thinking-rules-ui-scope] `ThinkingRuleResolver` — and therefore every
    /// user-authored rule — is consulted from exactly ONE place:
    /// `OpenAIAgentProvider.injectThinkingParams`, on the Chat Completions path. The
    /// Responses API builds its `reasoning` object inline, and Gemini / Anthropic go
    /// through `geminiThinkingConfig` / `anthropicThinkingShape`, neither of which takes
    /// user rules. Showing an editable rule list there promised something the request
    /// path does not honour: the rule saved, listed as active, and was silently ignored.
    ///
    /// That is the boundary this mirrors, not a limitation to route around — official
    /// protocols have documented, versioned shapes, so their thinking parameters are
    /// ours to maintain. The rules engine exists for the OpenAI-compatible gateway
    /// ecosystem, where an unforeseen endpoint shape otherwise means waiting for a
    /// release (OpenMinis#86 Venice, #87 Mistral).
    ///
    /// Kept deliberately parallel to `OpenAIProvider.usesChatCompletionsAPI` — if that
    /// predicate changes, this must change with it.
    private var supportsCustomRules: Bool {
        guard let instance = store.instance(for: instanceId) else { return false }
        switch instance.providerType {
        case .anthropic, .gemini, .antigravity, .openAIResponses:
            return false
        default:
            // A Codex-style OAuth instance with no custom base URL also resolves to the
            // Responses path, even though its type is plain `.openAI`.
            if instance.credentialType == .oauth && instance.effectiveCustomBaseURL == nil {
                return false
            }
            return true
        }
    }

    var body: some View {
        if supportsCustomRules {
            ruleListSection
        } else {
            unsupportedNoticeSection
        }
    }

    /// Shown instead of the rule list when this provider's requests never consult the
    /// resolver. A notice rather than a hidden section: silently dropping the whole
    /// section reads as "the feature is missing or I can't find it", whereas one line
    /// explains that thinking parameters here are maintained by Minis by design.
    private var unsupportedNoticeSection: some View {
        Section {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "info.circle")
                    .foregroundStyle(.secondary)
                Text("This provider uses an official protocol, so its thinking parameters are maintained by Minis. Custom rules apply to OpenAI-compatible providers on the Chat Completions API.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Thinking Rules")
        }
    }

    private var ruleListSection: some View {
        Section {
            ForEach(userRules) { rule in
                Button {
                    editorRequest = .init(rule: rule, isNew: false)
                } label: {
                    ruleRow(rule, isBuiltIn: false)
                }
                .buttonStyle(.plain)
            }
            .onDelete(perform: deleteUserRules)
            .onMove(perform: moveUserRules)

            // Built-ins, collapsed. Disclosure rather than a nav push so the user stays on
            // this page while comparing their rules against the defaults.
            DisclosureGroup(isExpanded: $showBuiltIns) {
                ForEach(builtInRules) { rule in
                    Button {
                        editorRequest = .init(rule: rule, isNew: true)
                    } label: {
                        ruleRow(rule, isBuiltIn: true)
                    }
                    .buttonStyle(.plain)
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "lock.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("Default rules")
                    Text("\(builtInRules.count)")
                        .font(.caption)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.secondary.opacity(0.15), in: Capsule())
                        .foregroundStyle(.secondary)
                }
            }

            Button {
                editorRequest = .init(rule: nil, isNew: true)
            } label: {
                Label("Add Rule", systemImage: "plus.circle")
            }
        } header: {
            HStack {
                Text("Thinking Rules")
                Spacer()
                if !userRules.isEmpty {
                    EditButton().font(.caption)
                }
            }
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("Rules are evaluated top to bottom. The first rule matching the model decides which thinking parameters are sent.")
                if let hit = resolvedHitDescription() {
                    // Design §8.2 — the minimum useful trace: which rule actually wins for
                    // a real model. Without it the rule list is another hidden variable.
                    Label(hit, systemImage: "scope")
                        .font(.caption)
                        .foregroundStyle(.blue)
                }
                Text("Default rules are built in and cannot be deleted. Add a rule above them to override one.")
                    .foregroundStyle(.secondary)
            }
            .font(.caption)
        }
        .task(id: instanceId) {
            guard !didLoad else { return }
            didLoad = true
            await reload()
        }
        .onChange(of: editorRequest?.id) { _ in
            // The parent clears this when the sheet dismisses; refresh so a newly saved
            // rule shows up without needing to leave and re-enter the page.
            // Single-argument onChange: deployment target is iOS 16, and the two-argument
            // form is 17+.
            if editorRequest == nil { Task { await reload() } }
        }
    }

    // MARK: - Rows

    @ViewBuilder
    private func ruleRow(_ rule: ThinkingRule, isBuiltIn: Bool) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(rule.label)
                        .font(isBuiltIn ? .callout : .body)
                        .foregroundStyle(.primary)
                    if isBuiltIn {
                        Image(systemName: "lock.fill")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                Text(rule.scope.displayText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let fmt = rule.wireFormat {
                    Text(fmt.displaySummary)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }

    // MARK: - Actions

    private func deleteUserRules(at offsets: IndexSet) {
        let ids = offsets.map { userRules[$0].id }
        Task {
            for id in ids {
                await ProviderConfigStore.shared.deleteThinkingRule(id: id, instanceId: instanceId)
            }
            await reload()
        }
    }

    private func moveUserRules(from source: IndexSet, to destination: Int) {
        var reordered = userRules
        reordered.move(fromOffsets: source, toOffset: destination)
        userRules = reordered   // optimistic, so the drag feels immediate
        let ids = reordered.map(\.id)
        Task {
            await ProviderConfigStore.shared.reorderThinkingRules(instanceId: instanceId, orderedIds: ids)
            await reload()
        }
    }

    private func reload() async {
        userRules = await ProviderConfigStore.shared.thinkingRules(for: instanceId)
        builtInRules = ThinkingRuleResolver.builtInRulesForDisplay(instanceId: instanceId)
    }

    /// Which rule currently wins for `sampleModelId` — design §8.2's minimum viable trace.
    private func resolvedHitDescription() -> String? {
        guard let model = sampleModelId else { return nil }
        let all = userRules + builtInRules
        guard let idx = all.firstIndex(where: { $0.scope.matches(model) }) else { return nil }
        let rule = all[idx]
        return AppLocalized("\(model) → rule #\(idx + 1) “\(rule.label)”")
    }
}

/// What the provider detail screen should present in the rule editor sheet.
///
/// Carried as one Optional so the sheet uses `.sheet(item:)` — the presentation is driven
/// by the value's identity, which avoids the "present with stale state" race that a
/// separate `isPresented` Bool plus a payload variable invites.
struct ThinkingRuleEditorRequest: Identifiable, Equatable {
    /// The rule to seed the editor with. nil = a blank new rule.
    let rule: ThinkingRule?
    /// True when saving should create a NEW rule rather than update `rule` in place.
    /// Tapping a built-in passes `isNew: true` with the built-in as the seed, which is the
    /// "duplicate as a custom rule" affordance from design §7.4 — the built-in itself is
    /// never modified.
    let isNew: Bool

    var id: String { (rule?.id ?? "new") + (isNew ? ":new" : ":edit") }
}
