import Foundation

// MARK: - Slash Commands

extension AIChatViewModel {

    struct SlashCommand: Identifiable {
        let id: String
        let icon: String
        let title: String
        let subtitle: String
        /// True when this row comes from a user-installed Skill (vs. one of
        /// the built-in commands). Skill rows fill the composer with
        /// "/<name>" on tap and dismiss the menu — the actual SKILL.md
        /// reading + invocation happens model-side once the message is
        /// sent (via the skillPromptFragment injection in runAgentLoop).
        let isSkill: Bool
        /// True when this row comes from a configured MCP server. Like skill
        /// rows, MCP rows are a typing aid: tap fills the composer with
        /// "/<name>" — the actual discovery/invocation happens model-side via
        /// minis-mcp-cli once the message is sent. Separate flag from isSkill so
        /// the picker can tag/icon them distinctly ([mcp] / wrench).
        let isMCP: Bool

        init(id: String, icon: String, title: String, subtitle: String, isSkill: Bool = false, isMCP: Bool = false) {
            self.id = id
            self.icon = icon
            self.title = title
            self.subtitle = subtitle
            self.isSkill = isSkill
            self.isMCP = isMCP
        }
    }

    static let availableSlashCommands: [SlashCommand] = [
        SlashCommand(id: "clear", icon: "trash", title: "Clear", subtitle: "Clear all messages in this session"),
        SlashCommand(id: "compact", icon: "arrow.down.right.and.arrow.up.left", title: "Compact", subtitle: "Compress conversation history into summary"),
        SlashCommand(id: "memory", icon: "brain.head.profile", title: "Memory", subtitle: "Toggle memory writes on/off (reads unaffected)"),
        SlashCommand(id: "memorydaily", icon: "brain.head.profile", title: "Memory Daily", subtitle: "[GH#357] Toggle daily-log injection on/off (global memory unchanged)"),
        SlashCommand(id: "thinking", icon: "lightbulb", title: "Thinking", subtitle: "Toggle deep thinking mode on/off"),
    ]

    /// Show slash menu without replacing existing input text.
    ///
    /// Two modes:
    /// 1. Input is empty → set text to "/" so the existing
    ///    `updateSlashMenuState()` text-driven path opens the menu naturally.
    /// 2. Input already has text → save text + caret, then PREPEND "/ "
    ///    (slash + space) to the front so the visible composer looks like
    ///    the user just typed `/` at the start, then place the caret right
    ///    after the slash so the slash menu's text-driven filter sees an
    ///    empty filter. On dismiss / execute we strip the "/ " back off
    ///    and restore the saved caret (see dismissSlashMenu /
    ///    executeSlashCommand).
    func showSlashMenuOverInput() {
        let trimmed = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            inputText = "/"
            // updateSlashMenuState() in inputText didSet flips showSlashMenu
        } else {
            // Save original state.
            savedInputBeforeSlash = inputText
            savedCaretBeforeSlash = inputCaret
            // Prepend "/ " so the cursor visually anchors at start and the
            // composer reads "/<user’s text>" with a single space separator.
            inputText = "/ " + inputText
            // Caret right after the slash so the user types in the menu's
            // filter slot (and the standard text-driven menu logic kicks in
            // — same as if they had typed `/` at the start themselves).
            pendingCaret = 1
            showSlashMenu = true
            slashFilter = ""
            slashMenuSelectedIndex = -1
        }
    }

    /// Dismiss slash menu, restoring saved input + caret if any. Strips the
    /// "/ " prefix that `showSlashMenuOverInput` injected.
    func dismissSlashMenu() {
        if let saved = savedInputBeforeSlash {
            inputText = saved
            if let caret = savedCaretBeforeSlash {
                pendingCaret = caret
            }
            savedInputBeforeSlash = nil
            savedCaretBeforeSlash = nil
        } else {
            inputText = ""
        }
        showSlashMenu = false
        slashMenuSelectedIndex = -1
    }

    /// Update slash menu state based on current input text.
    func updateSlashMenuState() {
        let text = inputText
        // Over-input mode: composer reads `/<typed-filter> <saved-text>`
        // (see showSlashMenuOverInput). Filter is the token between the
        // leading "/" and the first whitespace — anything after that
        // belongs to the user's saved prompt and must NOT participate in
        // the filter or the menu would search "/<filter> <saved-text>"
        // verbatim and never match.
        if savedInputBeforeSlash != nil {
            guard text.hasPrefix("/") else {
                // The "/" got deleted — close the menu without restoring.
                showSlashMenu = false
                slashMenuSelectedIndex = -1
                return
            }
            let afterSlash = text.dropFirst()
            // First whitespace marks the end of the filter token. If the
            // user typed nothing yet the composer is exactly "/ <saved>",
            // so afterSlash starts with a space → filter is "".
            let filterEnd = afterSlash.firstIndex(where: { $0.isWhitespace }) ?? afterSlash.endIndex
            slashFilter = String(afterSlash[..<filterEnd])
            showSlashMenu = true
            return
        }
        if text.hasPrefix("/") && !text.contains("\n") && text.count < 30 {
            showSlashMenu = true
            slashFilter = String(text.dropFirst())
            slashMenuSelectedIndex = -1
        } else {
            showSlashMenu = false
            slashMenuSelectedIndex = -1
        }
    }

    /// Move slash menu selection up.
    func slashMenuUp() {
        let count = filteredSlashCommands.count
        guard count > 0 else { return }
        if slashMenuSelectedIndex <= 0 {
            slashMenuSelectedIndex = count - 1
        } else {
            slashMenuSelectedIndex -= 1
        }
    }

    /// Move slash menu selection down.
    func slashMenuDown() {
        let count = filteredSlashCommands.count
        guard count > 0 else { return }
        if slashMenuSelectedIndex >= count - 1 {
            slashMenuSelectedIndex = 0
        } else {
            slashMenuSelectedIndex += 1
        }
    }

    /// Execute the currently selected slash command, if any.
    /// Returns true if a command was executed.
    func executeSelectedSlashCommand() -> Bool {
        let commands = filteredSlashCommands
        guard slashMenuSelectedIndex >= 0, slashMenuSelectedIndex < commands.count else { return false }
        executeSlashCommand(commands[slashMenuSelectedIndex])
        return true
    }

    /// Filter available slash commands by current filter text, with dynamic subtitles.
    /// Built-in commands come first; user-installed Skills are appended as
    /// dynamic rows so typing "/feedback" picks up an installed `feedback`
    /// skill, etc. Disabled skills are intentionally hidden — toggling a
    /// skill off in Settings hides it from the slash menu without losing
    /// the install. [T-skill-slash a88ea8f9]
    var filteredSlashCommands: [SlashCommand] {
        let filter = slashFilter.lowercased()
        var commands = Self.availableSlashCommands.map { cmd -> SlashCommand in
            if cmd.id == "memory" {
                let status = memoryEnabled ? "on" : "off"
                return SlashCommand(id: cmd.id, icon: cmd.icon, title: cmd.title, subtitle: "Writes \(status) — tap to toggle")
            }
            return cmd
        }
        // Append enabled skills as slash entries.
        // - id is namespaced ("skill:<uuid>") so it never collides with a
        //   built-in id.
        // - title is the raw skill name (used both as the displayed label
        //   and as the literal that gets typed into the composer).
        // - subtitle is the skill description (or version fallback).
        // Session-scoped enable wins over the global default — a user can
        // re-enable a skill for THIS chat even when it's globally off, and
        // vice versa. Mirrors SkillStore.isEnabledForSession's precedence:
        // session override → global isEnabled. Falls back to the global
        // value for draft / not-yet-persisted sessions (sessionId == nil).
        // [T-session-skill-toggle-override-global-ios]
        let currentSessionId = self.sessionId
        let skillRows: [SlashCommand] = SkillStore.shared.skills
            .filter { skill in
                guard let sid = currentSessionId else { return skill.isEnabled }
                return SkillStore.shared.isEnabledForSession(skill.id, sessionId: sid)
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            .map { skill in
                // [T-skill-yaml-block-scalar] Strip residual YAML block
                // scalar indicators (`>-`, `|-`, `>+`, `|+`, bare `>` /
                // `|`) that older SkillStore.parse versions left in the
                // stored description. Without this, existing cached
                // skills with `description: >-` continue to render the
                // raw token in the slash picker until re-imported.
                var desc = skill.description.trimmingCharacters(in: .whitespacesAndNewlines)
                if desc.first == "|" || desc.first == ">" {
                    let rest = desc.dropFirst()
                    if rest.isEmpty || rest.allSatisfy({ $0 == "-" || $0 == "+" || $0.isNumber }) {
                        desc = ""
                    }
                }
                let sub = desc.isEmpty ? "Skill · v\(skill.version)" : desc
                return SlashCommand(
                    id: "skill:\(skill.id)",
                    icon: "puzzlepiece.extension",
                    title: skill.name,
                    subtitle: sub,
                    isSkill: true
                )
            }
        commands.append(contentsOf: skillRows)

        // [T-mcp-integration-ios] MCP server rows. Like skills, these are a
        // typing aid (tap fills "/<name>"). ALL configured servers appear (not
        // just the Top-20 prompt-injection set), but session-disabled ones are
        // hidden — respecting the per-session override, mirroring skill rows.
        let mcpRows: [SlashCommand] = MCPStore.shared.servers
            .filter { server in
                guard let sid = currentSessionId else { return server.enabled }
                return MCPStore.shared.isEnabledForSession(server.id, sessionId: sid)
            }
            .sorted { $0.id.localizedCaseInsensitiveCompare($1.id) == .orderedAscending }
            .map { server in
                let note = (server.note ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                let sub = note.isEmpty ? "MCP · \(server.isSTDIO ? "STDIO" : "HTTP")" : note
                return SlashCommand(
                    id: "mcp:\(server.id)",
                    icon: "wrench.and.screwdriver",
                    title: server.id,
                    subtitle: sub,
                    isMCP: true
                )
            }
        commands.append(contentsOf: mcpRows)

        if filter.isEmpty {
            // [T-slash-picker-recent] Default `/` view: top 100 by
            // recency. Builtins keep priority — they always appear in
            // their canonical order ahead of skills, and skills get
            // sorted within their own block by most-recently-used
            // (unused skills fall back to alphabetical via the existing
            // sort above). Cap raised 20 → 100 so users with large
            // skill libraries can scroll the full set without first
            // typing a filter.
            let usage = slashCommandUsage()
            let builtins = commands.filter { !$0.isSkill && !$0.isMCP }
            let skills = commands.filter { $0.isSkill }
            let mcps = commands.filter { $0.isMCP }
            let rankByUsage: ([SlashCommand]) -> [SlashCommand] = { rows in
                rows.sorted { a, b in
                    let ta = usage[a.id] ?? 0
                    let tb = usage[b.id] ?? 0
                    if ta != tb { return ta > tb }
                    return a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending
                }
            }
            return Array((builtins + rankByUsage(skills) + rankByUsage(mcps)).prefix(100))
        }
        return commands.filter { $0.title.lowercased().contains(filter) }
    }

    /// [T-slash-picker-recent] Per-slash-command last-used timestamp,
    /// loaded from UserDefaults. Map: `[command.id: unixSeconds]`.
    /// Capped at 200 entries so it doesn't grow unbounded over the
    /// lifetime of the install.
    private static let slashUsageDefaultsKey = "chat.slashUsage"
    private func slashCommandUsage() -> [String: Double] {
        (UserDefaults.standard.dictionary(forKey: Self.slashUsageDefaultsKey) as? [String: Double]) ?? [:]
    }
    private func recordSlashCommandUsage(id: String) {
        var usage = slashCommandUsage()
        usage[id] = Date().timeIntervalSince1970
        if usage.count > 200 {
            // Keep the most recent 200 entries — drop the oldest first.
            let keep = usage.sorted { $0.value > $1.value }.prefix(200)
            usage = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
        }
        UserDefaults.standard.set(usage, forKey: Self.slashUsageDefaultsKey)
    }

    /// Tab autocomplete: fill the selected (or only) command into the input
    /// field. Tab = FILL, never execute — but the insertion semantics mirror
    /// the tap path (executeSlashCommand's skill/MCP branch) exactly:
    /// trailing space so the user can type immediately, verbatim title (the
    /// send path lowercases+trims when matching built-ins), the
    /// savedInputBeforeSlash flavor preserved, and the picker dismissed.
    /// [T-ios-slash-tab-autocomplete-dismiss-space] Previously this assigned
    /// "/<title>" with no space and left showSlashMenu untouched — the new
    /// inputText still matched the filter, so updateSlashMenuState (inputText
    /// didSet) kept the panel open over the filled composer.
    func slashMenuAutocomplete() {
        let commands = filteredSlashCommands
        guard !commands.isEmpty else { return }
        // If a specific item is selected, use it; otherwise use the first match
        let idx = (slashMenuSelectedIndex >= 0 && slashMenuSelectedIndex < commands.count)
            ? slashMenuSelectedIndex : 0
        let cmd = commands[idx]
        if let saved = savedInputBeforeSlash {
            // Menu was opened over existing text: replace the injected "/ "
            // prefix with "/<name> " and land the caret right after it,
            // keeping the user's original prompt behind the caret.
            let prefix = "/\(cmd.title) "
            inputText = prefix + saved
            pendingCaret = prefix.count
            savedInputBeforeSlash = nil
            savedCaretBeforeSlash = nil
        } else {
            inputText = "/\(cmd.title) "
        }
        // AFTER the inputText assignment — its didSet re-evaluates the menu
        // state and would re-show the panel otherwise.
        showSlashMenu = false
        slashMenuSelectedIndex = -1
        requestComposerFocusSignal.send()
    }

    /// Check if the input text is a slash command and execute it instead of sending.
    /// Returns true if a command was matched and executed.
    func tryExecuteInputAsSlashCommand() -> Bool {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.hasPrefix("/") else { return false }
        let name = String(text.dropFirst()).lowercased()
        guard let cmd = Self.availableSlashCommands.first(where: { $0.title.lowercased() == name }) else {
            return false
        }
        executeSlashCommand(cmd)
        return true
    }

    func executeSlashCommand(_ cmd: SlashCommand) {
        // [T-slash-picker-recent] Record this use so the default `/`
        // picker (no filter) can rank by recency. Stored as
        // [id: lastUsedUnix] in UserDefaults — sub-ms, no persistence
        // ceremony, survives app restarts. Tag/no-op rows like
        // /memory and /thinking still count because the user *did*
        // pick them; ranking by raw pick count is the right signal.
        recordSlashCommandUsage(id: cmd.id)
        // [T-skill-slash a88ea8f9] Skill rows are not directly executable
        // here — they're a typing aid. Tap → fill composer with "/<name>"
        // and dismiss the menu so the user can hit Send (the actual SKILL.md
        // ingestion + behavior happens model-side via the existing
        // skillPromptFragment injection in runAgentLoop). DO NOT clear
        // savedInputBeforeSlash here — that flag isn't relevant to the
        // skill path (the menu was opened by typing "/", not by the "/"
        // button over existing text).
        if cmd.isSkill || cmd.isMCP {
            // Skill / MCP insertion has two flavors:
            //
            // 1. Menu was opened over EXISTING text (savedInputBeforeSlash
            //    non-nil) — the user already had something typed. Replace
            //    the leading "/ " injected by showSlashMenuOverInput() with
            //    "/<skill> ", so the final text reads
            //    "/<skill> <originalText>" and the caret lands at the end
            //    of "/<skill> ", which is right before the original text.
            //    The user can keep typing in front of the original prompt
            //    or move the caret as they like.
            //
            // 2. Menu opened with EMPTY input — original behavior: replace
            //    the whole composer with "/<skill> " so the user can type
            //    their question right after.
            //
            // Trailing space in both: caret lands after a delimiter so the
            // user can immediately type without backspacing off the name.
            if let saved = savedInputBeforeSlash {
                let prefix = "/\(cmd.title) "
                inputText = prefix + saved
                pendingCaret = prefix.count
                savedInputBeforeSlash = nil
                savedCaretBeforeSlash = nil
            } else {
                inputText = "/\(cmd.title) "
            }
            showSlashMenu = false
            slashMenuSelectedIndex = -1
            requestComposerFocusSignal.send()
            return
        }
        // Restore saved input + caret if slash menu was opened over
        // existing text (drops the "/ " prefix injected by
        // showSlashMenuOverInput). For built-in executable commands —
        // /compact, /memory, /clear — the original text is preserved
        // exactly so the user doesn't lose their in-progress prompt.
        if let saved = savedInputBeforeSlash {
            inputText = saved
            if let caret = savedCaretBeforeSlash {
                pendingCaret = caret
            }
            savedInputBeforeSlash = nil
            savedCaretBeforeSlash = nil
        } else {
            inputText = ""
        }
        showSlashMenu = false
        slashMenuSelectedIndex = -1
        switch cmd.id {
        case "compact":
            compactAll()
        case "memory":
            memoryEnabled.toggle()
            if let sid = sessionId {
                Task { await ChatStore.shared.setMemoryEnabled(sessionId: sid, enabled: memoryEnabled) }
            }
            let status = memoryEnabled ? "enabled" : "disabled"
            appendSystemInfo("Memory writes \(status). Reads are unaffected.", icon: "brain.head.profile")
        case "memorydaily":
            memoryDailyInjectionEnabled.toggle()
            let dailyStatus = memoryDailyInjectionEnabled ? "enabled" : "disabled"
            appendSystemInfo("Daily memory injection \(dailyStatus). Global memory and writes unaffected.", icon: "brain.head.profile")
        case "clear":
            clearChatConfirmRequested = true
        default:
            break
        }
    }
}
