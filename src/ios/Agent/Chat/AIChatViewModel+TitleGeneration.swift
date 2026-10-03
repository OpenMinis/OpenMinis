import Foundation

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Session Title Generation

extension AIChatViewModel {

    // MARK: - Session Title Generation

    /// [T-title-gen-fallback-first-message-ios] Build a fallback session title
    /// from the first user message when LLM title generation fails. Strips the
    /// `<user-attached-files>` metadata block + inline attachment markers,
    /// collapses whitespace/newlines, and truncates to ~30 chars to match the
    /// generated-title length. Returns nil if nothing usable remains.
    static func fallbackTitle(fromFirstUserMessage raw: String) -> String? {
        var t = raw
        // Drop the attached-files XML the composer injects.
        if let start = t.range(of: "<user-attached-files>") {
            let end = t.range(of: "</user-attached-files>")
            let endBound = end?.upperBound ?? t.endIndex
            t = String(t[t.startIndex..<start.lowerBound] + t[endBound...])
        }
        // Collapse all whitespace (incl. newlines) to single spaces.
        t = t.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        let maxLen = 30
        if t.count > maxLen {
            return String(t.prefix(maxLen)).trimmingCharacters(in: .whitespaces) + "…"
        }
        return t
    }

    /// Call after each LLM stream completes to asynchronously generate a session title.
    /// Retries up to 3 times across agent loop iterations if the title is still missing.
    /// Skips if a generation request is already in-flight to avoid wasting tokens.
    func generateSessionTitleIfNeeded(toolEntries: [(name: String, args: [String: Any])] = []) {
        // [T-p1-delegate-task] A helper session keeps its "Agent · <title>"
        // name; an auto-generated title would also cost an extra model call.
        guard helperConfig == nil else { return }
        guard let sessionId, titleGenAttempts < 3, !isTitleGenerating else { return }

        let userMessages = messages.filter { $0.role == .user }
        let assistantMessages = messages.filter { $0.role == .assistant }
        guard let firstUser = userMessages.first else { return }
        guard !assistantMessages.isEmpty else { return }

        // Extract plain text from an assistant message's blocks.
        func assistantText(_ msg: ChatMessage) -> String {
            msg.blocks
                .filter { $0.kind == .text }
                .map { $0.content }
                .joined(separator: "\n")
        }

        // First assistant message that actually carries text — early turns can
        // be tool-only, and a tool-only first message would summarize as "".
        let firstAssistantWithText = assistantMessages.first {
            !assistantText($0).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        var responseText = firstAssistantWithText.map(assistantText) ?? ""

        // If no assistant text anywhere yet, build a summary from tool call args
        if responseText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !toolEntries.isEmpty {
            let toolSummaries = toolEntries.prefix(3).map { entry in
                let argsPreview = (try? JSONSerialization.data(withJSONObject: entry.args))
                    .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
                return "\(entry.name)(\(String(argsPreview.prefix(200))))"
            }
            responseText = "[Tool calls: \(toolSummaries.joined(separator: ", "))]"
        }

        guard !responseText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        // Summary scope: first user + first assistant text (200 chars each);
        // when the session has more than one user turn, also append the last
        // user + last assistant pair (200 chars each) so titles track topic
        // drift in longer sessions. Mirrors regenerateSessionTitle's shape.
        var summary = "User: \(String(firstUser.content.prefix(200)))\n\nAssistant: \(String(responseText.prefix(200)))"
        if userMessages.count > 1, let lastUser = userMessages.last {
            summary += "\n\n[... middle of conversation omitted ...]\n"
            summary += "\nUser: \(String(lastUser.content.prefix(200)))"
            if let lastAssistant = assistantMessages.last,
               lastAssistant.id != firstAssistantWithText?.id {
                let lastText = assistantText(lastAssistant)
                if !lastText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    summary += "\n\nAssistant: \(String(lastText.prefix(200)))"
                }
            }
        }
        // Captured for the fallback-title path below (full first-user content).
        let firstUserRaw = firstUser.content
        let userTurnCount = userMessages.count

        titleGenAttempts += 1
        isTitleGenerating = true

        let attempt = titleGenAttempts
        let subEntry = resolveSubEntry()
        // [T-ios-switch-model-ghost-retry] Snapshot the generation token. Every
        // attempt below re-checks it against the live value, so a model switch
        // (which bumps it) stops this Task instead of letting it keep calling
        // the model the user just moved away from.
        let genEpoch = titleGenEpoch
        logger.info("[TitleGen] Starting title generation attempt \(attempt)/3 for session \(sessionId ?? "nil"), subEntry: \(subEntry?.model.id ?? "nil")")
        Task { [weak self, sessionId, subEntry, firstUserRaw, summary, genEpoch] in
            do {
                /// True once a model switch has superseded this Task.
                @MainActor func superseded() -> Bool {
                    guard let self else { return true }
                    return self.titleGenEpoch != genEpoch
                }
                if await superseded() {
                    logger.info("[TitleGen] attempt \(attempt) abandoned before start — model changed")
                    return
                }
                // If title already exists (e.g. set by a previous async Task), stop trying
                if let session = await ChatStore.shared.getSession(sessionId), session.title != nil {
                    await MainActor.run {
                        self?.titleGenAttempts = 3
                        self?.isTitleGenerating = false
                    }
                    return
                }

                logger.info("[TitleGen] Summary length: \(summary.count) chars, userTurns=\(userTurnCount)")
                // Auto-grouping (opt-in, Settings → Appearance): fold the
                // folder question into THIS call — no second round-trip. With
                // the toggle off, or with no folders to offer, the prompt and
                // schema are byte-identical to the title-only form.
                // [T-titlegen-folder-dedup] Shared with regenerate; a
                // first-generation session is unfiled by definition, so
                // requireUnfiled is a no-op safety here, not a behavior change.
                let folderOffer = await Self.buildFolderOfferContext(
                    sessionId: sessionId, requireUnfiled: true)
                let folderNames = folderOffer.folderNames
                let folderContext = folderOffer.folderContext
                // [T-titlegen-retry-not-retrying] The "N/3" in the logs used to
                // be a fiction: ONE model was called ONCE, any failure applied
                // the first-user-message fallback immediately, and the fallback
                // title then blocked every later attempt via the title-exists
                // guard above — a single transient 429 froze "title = prompt
                // text" permanently. Manual regenerate always worked because it
                // walks regenerateTitleCandidates; do the same here: up to 3
                // candidate models (session sub → session primary → other
                // eligible entries), short backoff between tries, and the
                // fallback only after ALL of them fail.
                var candidates = await Self.regenerateTitleCandidates(sessionId: sessionId)
                if candidates.isEmpty, let subEntry { candidates = [subEntry] }
                // [T-titlegen-group-order] Candidates now include the FULL
                // bound group in router order (A→B→C…) before cross-tier
                // degradation; cap at 6 so a typical 6-member sub group can be
                // exhausted before the first-message fallback, without letting
                // a huge model pool spin forever.
                let tries = Array(candidates.prefix(6))
                var trimmed = ""
                var category: String? = nil
                var folderName: String? = nil
                for (idx, entry) in tries.enumerated() {
                    // [T-ios-switch-model-ghost-retry] Re-check before EVERY
                    // candidate, not just at entry: the 1.5s pause below is
                    // exactly the window in which the user switches model, and
                    // `tries` was resolved from the OLD binding.
                    if await superseded() {
                        logger.info("[TitleGen] attempt \(attempt) abandoned at candidate \(idx + 1)/\(tries.count) — model changed")
                        return
                    }
                    do {
                        let result = try await Self.callSubModelForTitle(
                            conversationSummary: summary,
                            subEntry: entry,
                            folderNames: folderNames,
                            folderContext: folderContext,
                            // [T-opencode-dedicated-channel] Title generation is
                            // this conversation's traffic too — an OpenCode
                            // instance 400s a request without the session header.
                            sessionId: sessionId
                        )
                        let t = result.0.trimmingCharacters(in: .whitespacesAndNewlines)
                            .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                        if t.isEmpty {
                            logger.error("[TitleGen] FAILED attempt \(idx + 1)/\(tries.count) — model=\(entry.model.id) reason=empty-response (raw: \"\(result.0.prefix(80))\")")
                        } else {
                            trimmed = t
                            category = result.1
                            folderName = result.2
                            break
                        }
                    } catch {
                        let reason: String
                        if let urlErr = error as? URLError {
                            reason = urlErr.code == .timedOut ? "timeout" : "request-error(\(urlErr.code.rawValue))"
                        } else if let llmErr = error as? LLMError {
                            reason = "llm-error(\(llmErr.errorDescription ?? "unknown"))"
                        } else {
                            reason = "error(\(error.localizedDescription))"
                        }
                        logger.error("[TitleGen] FAILED attempt \(idx + 1)/\(tries.count) — model=\(entry.model.id) reason=\(reason)")
                    }
                    // Rate limits recover in seconds and the next candidate is
                    // usually a different provider — a short pause is enough.
                    if idx + 1 < tries.count {
                        try? await Task.sleep(nanoseconds: 1_500_000_000)
                    }
                }
                guard !trimmed.isEmpty else {
                    logger.error("[TitleGen] All \(tries.count) candidate model(s) failed — applying first-message fallback")
                    await Self.applyFallbackTitle(sessionId: sessionId, firstUserRaw: firstUserRaw, attempt: attempt)
                    await MainActor.run {
                        self?.titleGenAttempts = 3
                        self?.isTitleGenerating = false
                    }
                    return
                }
                await ChatStore.shared.updateSessionTitle(sessionId, title: trimmed, category: category)
                // Folder assignment is best-effort and strictly subordinate to
                // the title. [T-titlegen-folder-dedup]
                await Self.applyFolderSuggestionIfOffered(
                    folderName: folderName, offering: folderOffer.offering,
                    sessionId: sessionId, logTag: "Auto-group")
                await MainActor.run {
                    self?.titleGenAttempts = 3
                    self?.isTitleGenerating = false
                    SessionActivityTracker.shared.updateSessionTitle(sessionId, title: trimmed)
                }
                logger.info("[TitleGen] Attempt \(attempt): saved session title: \(trimmed), category: \(category ?? "nil")")
            } catch {
                // Classify the failure so logs show WHY (request error / timeout /
                // model-unavailable / parse failure all funnel here via thrown
                // NSError or LLMError).
                let reason: String
                if let urlErr = error as? URLError {
                    reason = urlErr.code == .timedOut ? "timeout" : "request-error(\(urlErr.code.rawValue))"
                } else if let llmErr = error as? LLMError {
                    reason = "llm-error(\(llmErr.errorDescription ?? "unknown"))"
                } else {
                    reason = "error(\(error.localizedDescription))"
                }
                logger.error("[TitleGen] FAILED attempt \(attempt)/3 — reason=\(reason)")
                // Fallback so the session never stays untitled.
                await Self.applyFallbackTitle(sessionId: sessionId, firstUserRaw: firstUserRaw, attempt: attempt)
                await MainActor.run {
                    self?.titleGenAttempts = 3
                    self?.isTitleGenerating = false
                }
            }
        }
    }

    // MARK: - [T-titlegen-folder-dedup] Shared folder-suggestion flow

    /// Single source of truth for the folder-OFFER side, shared by every
    /// title-generation entry point (auto after first turn, manual
    /// regenerate, any future one): reads the auto-grouping toggle, applies
    /// the unfiled requirement, and formats the deduped
    /// `"name — description"` context lines. Two hand-copied versions of
    /// this had already drifted apart in shape — do not inline a third.
    ///
    /// `requireUnfiled` also skips asking the model for a folder at all when
    /// the session is filed: the answer could never be applied
    /// (setFolderIfUnfiled's `WHERE folder_id IS NULL` guard), so offering
    /// would only waste an output slot.
    static func buildFolderOfferContext(sessionId: String, requireUnfiled: Bool) async
        -> (folderNames: [String], folderContext: [String], offering: Bool) {
        guard UserDefaults.standard.bool(forKey: "autoGroupingEnabled") else { return ([], [], false) }
        if requireUnfiled {
            guard let session = await ChatStore.shared.getSession(sessionId),
                  session.folderId == nil else { return ([], [], false) }
        }
        var names: [String] = []
        var context: [String] = []
        var seen = Set<String>()
        for f in await ChatStore.shared.listFolders() where seen.insert(f.name.lowercased()).inserted {
            names.append(f.name)
            if let d = f.desc, !d.isEmpty {
                context.append("\"\(f.name)\" — \(d)")
            } else {
                context.append("\"\(f.name)\"")
            }
        }
        return (names, context, true)
    }

    /// Single source of truth for the folder-APPLY side: existing folders
    /// only (never create one from a model's answer), unfiled sessions only
    /// (SQL-guarded), silent skip on no match. `logTag` is the only per-path
    /// difference — e.g. "Auto-group" vs "Auto-group (regenerate)".
    static func applyFolderSuggestionIfOffered(
        folderName: String?, offering: Bool, sessionId: String, logTag: String
    ) async {
        guard offering, let folderName, !folderName.isEmpty else { return }
        if let folder = await ChatStore.shared.findFolderByName(folderName) {
            let applied = await ChatStore.shared.setFolderIfUnfiled(folder.id, forSession: sessionId)
            logger.info("[TitleGen] \(logTag): '\(folderName)' -> \(folder.id.prefix(8)) applied=\(applied)")
        } else {
            logger.info("[TitleGen] \(logTag): model offered '\(folderName)' but no folder matches — leaving ungrouped")
        }
    }

    /// [T-title-gen-fallback-first-message-ios] Apply a first-user-message
    /// fallback title, but only if the session is still untitled (another
    /// attempt / regenerate may have set one in the meantime).
    private static func applyFallbackTitle(sessionId: String, firstUserRaw: String, attempt: Int) async {
        if let session = await ChatStore.shared.getSession(sessionId), session.title != nil {
            logger.info("[TitleGen] Fallback skipped — session already titled: \(session.title ?? "")")
            return
        }
        guard let fallback = fallbackTitle(fromFirstUserMessage: firstUserRaw) else {
            logger.warning("[TitleGen] Fallback unavailable — first user message empty after cleanup; session stays untitled (attempt \(attempt))")
            return
        }
        await ChatStore.shared.updateSessionTitle(sessionId, title: fallback, category: nil)
        logger.info("[TitleGen] Applied fallback title from first user message: \"\(fallback)\" (attempt \(attempt))")
    }

    /// Build a lightweight provider for the sub model and call it to generate a title and category.
    /// Regenerate the title and category for a session using its stored messages.
    /// Uses the first user/assistant pair + last user/assistant pair for better context.
    static func regenerateSessionTitle(sessionId: String) async throws {
        let rawMessages = await ChatStore.shared.loadMessages(sessionId: sessionId)

        let userMessages = rawMessages.filter { $0.role == .user }
        let assistantMessages = rawMessages.filter { $0.role == .assistant }
        guard let firstUser = userMessages.first,
              let firstAssistantAny = assistantMessages.first else { return }

        func extractText(_ msg: RawMessage, limit: Int) -> String {
            String(msg.parts.compactMap {
                if case .text(let s) = $0 { return s } else { return nil }
            }.joined(separator: "\n").prefix(limit))
        }
        func hasText(_ msg: RawMessage) -> Bool {
            !extractText(msg, limit: Int.max)
                .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }

        // First assistant message that actually carries text — a tool-only
        // first turn would emit an empty "Assistant:" line (aligns with
        // generateSessionTitleIfNeeded's firstAssistantWithText). When NO
        // assistant message has text, fall back to the raw first one so the
        // degenerate behavior stays exactly as before.
        let firstAssistant = assistantMessages.first(where: hasText) ?? firstAssistantAny

        // Build conversation summary: first pair + (optional) last pair.
        // 200 chars per excerpt — matches generateSessionTitleIfNeeded, enough
        // for topic signal without swamping the prompt on long messages.
        var summary = """
        [Start of conversation]
        User: \(extractText(firstUser, limit: 200))

        Assistant: \(extractText(firstAssistant, limit: 200))
        """

        let lastUser = userMessages.last
        let lastAssistant = assistantMessages.last
        if let lastUser, lastUser.id != firstUser.id {
            summary += "\n\n[... middle of conversation omitted ...]\n"
            summary += "\nUser: \(extractText(lastUser, limit: 200))"
            // Skip a tool-only tail assistant instead of appending an empty
            // "Assistant:" line (mirrors the auto-generation path's check).
            if let lastAssistant, lastAssistant.id != firstAssistant.id, hasText(lastAssistant) {
                summary += "\n\nAssistant: \(extractText(lastAssistant, limit: 200))"
            }
            summary += "\n[End of conversation]"
        }

        guard !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        // [T-ios-regen-title-model-resolution #112] Model resolution + candidate
        // fallback. The old inline resolution here was a hand-copied snapshot
        // of resolveSubEntry from BEFORE #34: it read the binding's cached
        // resolvedEntryId blindly (no availability check, no re-resolve, no
        // deleted-entry fall-through) and gave up entirely when the session
        // had no binding — producing all four #112 symptoms (stale model
        // after group reorder; silent no-op without a sub model; silent
        // no-op after delete/disable; no fallback when one model errors).
        // Resolution now shares the SAME static helpers as the auto path,
        // and mirrors Android's candidate loop: sub → primary → other
        // available title-eligible entries, first success wins.
        let candidates = await regenerateTitleCandidates(sessionId: sessionId)
        guard !candidates.isEmpty else {
            logger.error("[TitleGen] regenerate: no usable title-eligible model entry (no binding / all providers disabled)")
            throw NSError(domain: "TitleGen", code: -1, userInfo: [NSLocalizedDescriptionKey: "No sub model available"])
        }
        // Bounded attempts: the fallback pool can span every configured entry
        // (hundreds on heavy setups); a systemic outage would otherwise spam
        // each provider in sequence. 5 attempts covers sub + primary + three
        // alternates — beyond that the cause is systemic, not per-model.
        let maxAttempts = 5
        var lastError: Error? = nil
        // [T-titlegen-regenerate-folder] Folder suggestion under the same
        // rules as the auto path — shared helper, see
        // [T-titlegen-folder-dedup]. Filed sessions get no folder offer at
        // all; setFolderIfUnfiled's `WHERE folder_id IS NULL` remains the
        // authoritative guard.
        let folderOffer = await buildFolderOfferContext(
            sessionId: sessionId, requireUnfiled: true)
        for (idx, entry) in candidates.prefix(maxAttempts).enumerated() {
            do {
                let (title, category, folderName) = try await callSubModelForTitle(
                    conversationSummary: summary,
                    subEntry: entry,
                    folderNames: folderOffer.folderNames,
                    folderContext: folderOffer.folderContext,
                    // [T-opencode-dedicated-channel] Same as the auto path: a
                    // regenerate is still this conversation's traffic.
                    sessionId: sessionId
                )
                let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
                    .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                guard !trimmed.isEmpty else {
                    logger.warning("[TitleGen] regenerate candidate \(idx + 1)/\(min(candidates.count, maxAttempts)) (\(entry.model.id)) returned empty title — trying next")
                    continue
                }
                await ChatStore.shared.updateSessionTitle(sessionId, title: trimmed, category: category)
                // [T-titlegen-folder-dedup] Shared conservative application.
                await applyFolderSuggestionIfOffered(
                    folderName: folderName, offering: folderOffer.offering,
                    sessionId: sessionId, logTag: "Auto-group (regenerate)")
                if idx > 0 {
                    logger.info("[TitleGen] regenerate succeeded on fallback candidate \(idx + 1) (\(entry.model.id))")
                }
                return
            } catch {
                lastError = error
                logger.warning("[TitleGen] regenerate candidate \(idx + 1)/\(min(candidates.count, maxAttempts)) (\(entry.model.id)) failed: \(error.localizedDescription) — trying next")
            }
        }
        if candidates.count > maxAttempts {
            logger.warning("[TitleGen] regenerate exhausted \(maxAttempts) attempts (of \(candidates.count) candidates) without success")
        }
        if let lastError { throw lastError }
    }

    /// Build a lightweight provider for the sub model and call it to generate a title and category.
    // MARK: - Manual "AI Suggest" folder grouping (picker sheet)

    /// Outcome of the manual "✨ AI Suggest" flow. Unlike automatic grouping,
    /// this path MAY propose a new folder — but nothing is applied here:
    /// the picker sheet surfaces the suggestion and the user confirms with a
    /// tap (a wrong grouping is a batch data move; a wrong title is one edit).
    enum FolderSuggestion {
        case merge(folderId: String, folderName: String)
        case create(name: String, desc: String?)
    }

    /// Ask the sub model where the given sessions belong. Context is
    /// deliberately lightweight: existing folder names with a few member
    /// titles (for the merge judgment) and the selected sessions' titles +
    /// categories — titles are already AI-written semantic summaries, so no
    /// message content crosses into the sub model from this path.
    static func suggestFolder(forSessionIds sessionIds: [String]) async throws -> FolderSuggestion {
        // [T-ios-folder-suggest-retry] This path had NO logging at all, so a
        // user report of "works once, then always fails" left nothing in the
        // log to hunt with (the reporter searched sugg/generateGroupName and
        // got zero hits). Every exit below is now traced with its reason.
        logger.info("[FolderSuggest] START sessions=\(sessionIds.count)")
        guard !sessionIds.isEmpty else {
            logger.error("[FolderSuggest] FAILED reason=no-sessions-selected")
            throw NSError(domain: "TitleGen", code: -3, userInfo: [NSLocalizedDescriptionKey: "No sessions selected"])
        }
        // [T-ios-folder-suggest-anchor-nondeterminism] Don't let ONE session
        // decide whether this works. The caller's `sessionIds` originates from
        // a `Set<String>` (FolderPickerRequest), so `.first` is whatever the
        // per-process hash seed put there — a different session, and therefore
        // a different sub model, on each app launch. If that one session's
        // bound model happens to be unconfigured/uncredentialed,
        // `regenerateTitleCandidates` comes back empty and the whole feature
        // throws "No sub model available", silently, for a reason the user
        // cannot see or influence. Retrying inside the same sheet re-rolls
        // nothing (same process = same order), which is what makes it look
        // like a permanent "AI Suggest failed" once it starts.
        //
        // Walk the selection in a STABLE order and take the first session that
        // actually resolves a candidate, so the feature works whenever ANY
        // selected session can serve it.
        var entry: ModelEntry?
        for sid in sessionIds.sorted() {
            let candidates = await regenerateTitleCandidates(sessionId: sid)
            if let first = candidates.first {
                entry = first
                logger.info("[FolderSuggest] anchor=\(sid.prefix(8)) model=\(first.model.id) candidates=\(candidates.count)")
                break
            }
            logger.info("[FolderSuggest] anchor=\(sid.prefix(8)) has no candidates — trying next session")
        }
        guard let entry else {
            logger.error("[FolderSuggest] FAILED reason=no-sub-model-available (no enabled+credentialed+title-eligible entry across \(sessionIds.count) selected session(s))")
            throw NSError(domain: "TitleGen", code: -1, userInfo: [NSLocalizedDescriptionKey: "No sub model available"])
        }

        let folders = await ChatStore.shared.listFolders()
        var folderLines: [String] = []
        var seenNames = Set<String>()
        for f in folders where seenNames.insert(f.name.lowercased()).inserted {
            var memberTitles: [String] = []
            for sid in await ChatStore.shared.sessionIdsInFolder(f.id).prefix(3) {
                if let t = await ChatStore.shared.getSession(sid)?.title { memberTitles.append(t) }
            }
            let descPart = (f.desc?.isEmpty == false) ? " (\(f.desc!))" : ""
            folderLines.append("- \"\(f.name)\"\(descPart): \(memberTitles.joined(separator: " / "))")
        }

        // [T-ios-folder-suggest-anchor-nondeterminism] Sorted for the same
        // reason as the anchor loop above: the caller's ids come from a Set, so
        // an unsorted `prefix(20)` would send a DIFFERENT 20-session sample to
        // the model on each launch whenever more than 20 are selected — making
        // the suggestion itself irreproducible.
        var sessionLines: [String] = []
        for sid in sessionIds.sorted().prefix(20) {
            if let sess = await ChatStore.shared.getSession(sid) {
                sessionLines.append("- \(sess.title ?? "Untitled") [\(sess.category ?? "other")]")
            }
        }
        guard !sessionLines.isEmpty else {
            logger.error("[FolderSuggest] FAILED reason=no-sessions-found (\(sessionIds.count) id(s) selected, none resolved in ChatStore)")
            throw NSError(domain: "TitleGen", code: -3, userInfo: [NSLocalizedDescriptionKey: "No sessions found"])
        }

        let foldersBlock = folderLines.isEmpty
            ? "The user has no folders yet."
            : "Existing folders with sample member titles:\n\(folderLines.joined(separator: "\n"))"
        let prompt = """
        The user selected these chat sessions to file into a folder:
        \(sessionLines.joined(separator: "\n"))

        \(foldersBlock)

        Decide: merge them into ONE existing group (only if they clearly fit it), or propose ONE new group name (2-8 characters preferred, in the same language as the session titles). \
        For a new group also write "description": one sentence (under 100 characters, same language as the name) describing what belongs in it — it will guide future automatic grouping.

        You MUST respond with valid JSON only. Examples:
        {"decision": "merge", "folder": "Work"}
        {"decision": "create", "name": "Trip Planning", "description": "Flights, hotels and itineraries for upcoming trips"}
        """

        let provider = await AIChatViewModel.makeAgentProvider(for: entry)
        var text = ""
        do {
            let stream = try await provider.streamAgentMessage(
                messages: [AgentMessage(role: .user, parts: [.text(prompt)])],
                systemPrompt: "You organize chat sessions into folders. Respond with a single valid JSON object only.",
                tools: [],
                maxTokens: 256,
                thinkingLevel: .off
            )
            for try await event in stream {
                if case .textDelta(let d) = event { text += d }
            }
        } catch {
            // The stream error is the one the UI silently swallowed into
            // "AI Suggest (failed — try again)". Surface it verbatim.
            logger.error("[FolderSuggest] FAILED reason=stream-error model=\(entry.model.id) error=\(error) partial=\"\(text.prefix(80))\"")
            throw error
        }
        logger.info("[FolderSuggest] stream done chars=\(text.count)")
        guard let start = text.firstIndex(of: "{"), let end = text.lastIndex(of: "}"),
              let data = String(text[start...end]).data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            logger.error("[FolderSuggest] FAILED reason=unparseable model=\(entry.model.id) raw=\"\(text.prefix(160))\"")
            throw NSError(domain: "TitleGen", code: -2, userInfo: [NSLocalizedDescriptionKey: "Unparseable suggestion: \(text.prefix(120))"])
        }

        let decision = (json["decision"] as? String)?.lowercased()
        if decision == "merge", let name = json["folder"] as? String,
           let folder = await ChatStore.shared.findFolderByName(name) {
            logger.info("[FolderSuggest] OK decision=merge folder=\"\(folder.name)\"")
            return .merge(folderId: folder.id, folderName: folder.name)
        }
        // Everything else — including a "merge" naming a folder that doesn't
        // exist — degrades to the create branch with the string prefilled, so
        // the user still gets a one-tap path and nothing is invented silently.
        let newName = (json["name"] as? String) ?? (json["folder"] as? String) ?? ""
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            logger.error("[FolderSuggest] FAILED reason=no-folder-name json=\(json)")
            throw NSError(domain: "TitleGen", code: -2, userInfo: [NSLocalizedDescriptionKey: "Suggestion carried no folder name"])
        }
        let desc = (json["description"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        logger.info("[FolderSuggest] OK decision=create name=\"\(trimmed)\" hasDesc=\(desc?.isEmpty == false)")
        return .create(name: trimmed, desc: (desc?.isEmpty == false) ? String(desc!.prefix(100)) : nil)
    }

    /// Returns (title, category, folderName). folderName is non-nil only when
    /// `folderNames` was non-empty AND the model chose one of the offered
    /// EXISTING folder names — the model interface deals in names, never ids
    /// (a sub-model has no basis for reasoning about opaque ids; the caller
    /// resolves the name locally, where duplicate names tie-break by most
    /// recent member activity).
    /// - Parameter sessionId: [T-opencode-dedicated-channel] the conversation
    ///   this title is being generated FOR. Title generation is a sub-task, but
    ///   it is still traffic belonging to that conversation, and an OpenCode
    ///   instance rejects any request without `x-opencode-session` — so it must
    ///   carry the same id as the turns around it rather than going out bare.
    private static func callSubModelForTitle(
        conversationSummary: String,
        subEntry: ModelEntry? = nil,
        folderNames: [String] = [],
        folderContext: [String] = [],
        sessionId: String? = nil
    ) async throws -> (String, String?, String?) {
        guard let entry = subEntry else {
            throw NSError(domain: "TitleGen", code: -1, userInfo: [NSLocalizedDescriptionKey: "No sub model available"])
        }

        // Route title generation through AgentProvider (with thinkingLevel:
        // .off) rather than the lower-level LLMProvider.streamMessage. The
        // agent path knows how to explicitly DISABLE thinking on providers
        // like Qwen3 / DeepSeek-v4 / OpenRouter-compat, which would otherwise
        // run with thinking ON by default and return finish_reason=tool_calls
        // with an empty text body — exactly the failure mode reported on
        // qwen3.6-plus (T-title-gen-disable-reasoning-ios). The agent
        // streaming surface uses `streamAgentMessage` which routes through
        // `injectThinkingParams` and emits `enable_thinking: false` /
        // `reasoning_effort: "none"` / `thinking_budget: 0` per model
        // family.
        let provider = await AIChatViewModel.makeAgentProvider(for: entry, sessionId: sessionId)
        logger.info("[TitleGen] Using provider: \(provider.name), model: \(entry.model.id), thinkingLevel=.off")

        let langInjection = titleLanguageInjection()

        // Folder segment exists ONLY when auto-grouping supplied candidates —
        // with it absent, prompt and schema are byte-identical to the
        // title-only form. The null path is spelled out and given its own
        // example: a closed option list pushes sub-models toward always
        // picking something, and a wrongly-filed session costs far more than
        // a wrong category (which only drives a row icon).
        let folderSegment: String
        let exampleLine: String
        if folderNames.isEmpty {
            folderSegment = ""
            exampleLine = #"{"title": "Debug Login Page Issue", "category": "code"}"#
        } else {
            // Context lines are `"name" — description` where the user (or the
            // suggest flow) wrote one; the description is authored exactly to
            // sharpen this membership judgment.
            let list = (folderContext.isEmpty ? folderNames.map { "\"\($0)\"" } : folderContext)
                .joined(separator: "; ")
            folderSegment = """
            The user organizes chats into groups. Existing groups: [\(list)]. \
            If this conversation clearly belongs to one of these groups, set "folder" to that exact group name. \
            If it does not clearly match any group, or you are unsure, set "folder" to null. \
            Never invent a new group name.

            """
            exampleLine = #"{"title": "Debug Login Page Issue", "category": "code", "folder": null}"#
        }

        let prompt = """
        Based on the following conversation, generate a short title (max 6 words) that captures the topic. \
        Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.

        \(folderSegment)\(langInjection)

        You MUST respond with valid JSON only. Example:
        \(exampleLine)

        Conversation:
        \(conversationSummary)
        """

        let messages = [AgentMessage(role: .user, parts: [.text(prompt)])]
        let stream = try await provider.streamAgentMessage(
            messages: messages,
            systemPrompt: "You generate concise titles for conversations. You MUST respond with a single valid JSON object: {\"title\": \"...\", \"category\": \"...\"}. No other text.",
            tools: [],
            maxTokens: 1024,
            thinkingLevel: .off
        )

        var responseText = ""
        var stopReason: AgentStopReason?
        var usage: LLMUsage?
        var didTag = false
        for try await event in stream {
            // See compact path comment: tag on first chunk so the wire
            // request is actually in the ring buffer by then.
            #if DEBUG
            if !didTag {
                LastAPIRequestBody.shared.tagLatest("title")
                didTag = true
            }
            #endif
            switch event {
            case .textDelta(let delta):
                responseText += delta
            case .done(let reason):
                stopReason = reason
            case .usage(let u):
                usage = u
            // Title generation doesn't issue tools, so toolCallComplete /
            // toolInputDelta / contentBlockStart / reasoningContent /
            // reasoningEcho / thinkingDelta are all ignored. If a model
            // emits them anyway (qwen3 sometimes does even with thinking
            // off), we let the text accumulator finish what it can.
            default:
                break
            }
        }

        logger.info("[TitleGen] Raw LLM response (\(responseText.count) chars): \(responseText)")
        let stopStr: String
        switch stopReason {
        case .endTurn?:    stopStr = "endTurn"
        case .toolUse?:    stopStr = "toolUse"
        case .maxTokens?:  stopStr = "maxTokens"
        case .refusal?:    stopStr = "refusal"
        case nil:          stopStr = "nil"
        }
        logger.info("[TitleGen] stopReason: \(stopStr), usage: in=\(usage?.inputTokens ?? -1) out=\(usage?.outputTokens ?? -1)")

        // Strip markdown code fences if present (e.g. ```json ... ```)
        var cleaned = responseText.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("```") {
            // Remove opening fence line and closing fence
            let lines = cleaned.components(separatedBy: .newlines)
                .drop(while: { $0.hasPrefix("```") })
            cleaned = lines.joined(separator: "\n")
            if cleaned.hasSuffix("```") {
                cleaned = String(cleaned.dropLast(3))
            }
            cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        // Try to extract JSON object if response contains extra text around it
        let jsonString: String
        if let start = cleaned.firstIndex(of: "{"),
           let end = cleaned.lastIndex(of: "}") {
            jsonString = String(cleaned[start...end])
        } else {
            jsonString = cleaned
        }

        // Attempt JSON parse
        if let data = jsonString.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let title = json["title"] as? String, !title.isEmpty {
            let category = json["category"] as? String
            // "folder" may be absent, null, or a name; only a non-empty string
            // that was actually OFFERED counts — anything else (including a
            // hallucinated new name) collapses to nil. Case-insensitive match
            // mirrors the local resolution in findFolderByName.
            var folder: String? = nil
            if !folderNames.isEmpty, let f = json["folder"] as? String {
                let t = f.trimmingCharacters(in: .whitespacesAndNewlines)
                if !t.isEmpty, folderNames.contains(where: { $0.caseInsensitiveCompare(t) == .orderedSame }) {
                    folder = t
                }
            }
            logger.info("[TitleGen] Parsed JSON — title: \(title), category: \(category ?? "nil"), folder: \(folder ?? "nil")")
            return (title, category, folder)
        }

        logger.warning("[TitleGen] JSON parse failed for extracted: \(jsonString)")

        // Regex fallback: extract title value from malformed JSON
        if let titleMatch = jsonString.range(of: #""title"\s*:\s*"([^"]+)""#, options: .regularExpression) {
            let matchStr = String(jsonString[titleMatch])
            // Extract the captured group value
            if let valueStart = matchStr.range(of: #":\s*""#, options: .regularExpression)?.upperBound,
               let valueEnd = matchStr.lastIndex(of: "\""), valueStart < valueEnd {
                let title = String(matchStr[valueStart..<valueEnd])
                logger.info("[TitleGen] Regex fallback — title: \(title)")
                return (title, nil, nil)
            }
        }

        // Last resort: use raw text only if it looks like a plain title (no JSON artifacts)
        let raw = cleaned
            .trimmingCharacters(in: CharacterSet(charactersIn: "\"{}"))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !raw.isEmpty && raw.count < 60 && !raw.contains("{") && !raw.contains("}") {
            logger.info("[TitleGen] Plain text fallback — title: \(raw)")
            return (raw, nil, nil)
        }

        logger.error("[TitleGen] All parsing strategies failed. Response was: \(responseText)")
        throw NSError(domain: "TitleGen", code: -2, userInfo: [NSLocalizedDescriptionKey: "Failed to parse title from response: \(responseText.prefix(200))"])
    }

    /// Build a bilingual instruction asking the title-generation model to use
    /// the user's app interface language. The localized name is resolved from
    /// the same locale so it ships in the user's language (the native endonym
    /// for zh, "English" for en, etc.). Falls back to "en" if resolution fails.
    ///
    /// Resolution order:
    ///   1. The user's explicit `appLanguage` (Settings → Language, also
    ///      writable via minis-config `appearance.language`). Honors changes
    ///      made after launch — `Bundle.main.preferredLocalizations` is
    ///      computed once at launch from `AppleLanguages` and does NOT update
    ///      when `setLanguage(...)` rewrites that key at runtime, so reading
    ///      it here picked up the launch-time system locale instead of the
    ///      user's in-app choice.
    ///   2. `Bundle.main.preferredLocalizations.first` (launch-time matching
    ///      against the bundle's supported locales).
    ///   3. `Locale.current` language code.
    ///   4. "en".
    private static func titleLanguageInjection() -> String {
        let userSelected = (UserDefaults.standard.string(forKey: "appLanguage") ?? "")
            .trimmingCharacters(in: .whitespaces)
        let preferred: String = !userSelected.isEmpty
            ? userSelected
            : (Bundle.main.preferredLocalizations.first
               ?? Locale.current.language.languageCode?.identifier
               ?? "en")
        let locale = Locale(identifier: preferred)
        let localizedName = locale.localizedString(forIdentifier: preferred)
            ?? Locale(identifier: "en").localizedString(forIdentifier: preferred)
            ?? preferred
        return """
        The user's app interface language is "\(preferred)" (\(localizedName)). Generate the title primarily in this language. If the conversation content is in a different language, you may incorporate proper nouns from it, but the overall title language should match the interface language.
        用户的 App 界面语言是 "\(preferred)"（\(localizedName)）。请优先使用该语言生成标题。如果对话内容是其他语言，可保留专有名词，但标题整体语言应与界面语言一致。
        """
    }

}
