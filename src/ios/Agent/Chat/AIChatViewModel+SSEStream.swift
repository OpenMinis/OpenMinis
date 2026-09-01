import Foundation
import UIKit

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Stream Stall Timeout
//
// [T-ios-stream-stall-no-retry] The stall watchdog used to throw a PRIVATE
// `StreamStallError` struct here. That type was invisible to the agent loop's
// `catch let streamError as LLMError where streamError.isRetryable` gate, so a
// mid-stream stall skipped the entire existing recovery pipeline (uncommitted-
// tail cleanup → immediate same-model re-dispatch → group fallback on a second
// failure) and killed the loop outright. Field case 2026-08-18 22:11: upstream
// emitted a tool_use header + 2 bytes of arguments, went silent for exactly
// 120.0s, and the task died with no retry — while the request would almost
// certainly have succeeded on a re-send (and hit the prompt cache doing it).
// The watchdog now throws `LLMError.transientError`, which walks straight into
// that pipeline. Net behavior: one immediate retry on the same model; a second
// stall falls back to the next model in the group; turn budget untouched.

private final class StreamIteratorBox<T: Sendable>: @unchecked Sendable {
    private var iterator: AsyncThrowingStream<T, Error>.AsyncIterator
    init(_ stream: AsyncThrowingStream<T, Error>) {
        self.iterator = stream.makeAsyncIterator()
    }
    func next() async throws -> T? {
        try await iterator.next()
    }
}

// MARK: - Background SSE Stream Processing

extension AIChatViewModel {

    // MARK: - Stall diagnostics helpers (#181)

    /// Millisecond-precision wall-clock stamp for stall diagnosis. The
    /// `AppLogger` line already carries a timestamp, but that one is written
    /// when the line is flushed; these are captured at the measurement point,
    /// which is what makes "was Task.sleep stretched?" answerable.
    nonisolated static func diagTimestamp(_ date: Date) -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "HH:mm:ss.SSS"
        return fmt.string(from: date)
    }

    /// Foreground/background at the instant the watchdog fired. If the app was
    /// backgrounded, candidate 3 (throttled/stretched timer) becomes the prime
    /// suspect; if it was active the whole time, candidate 3 is out.
    @MainActor
    static func diagAppStateName() -> String {
        switch UIApplication.shared.applicationState {
        case .active: return "active"
        case .inactive: return "inactive"
        case .background: return "background"
        @unknown default: return "unknown"
        }
    }

    /// Short event-kind label for the arrival timeline. Deliberately does NOT
    /// include payloads — this runs on a hot path and must never log message
    /// content.
    nonisolated static func diagEventName(_ event: AgentStreamEvent) -> String {
        switch event {
        case .contentBlockStart(let s):
            switch s {
            case .text: return "contentBlockStart(text)"
            case .toolUse(_, let name): return "contentBlockStart(tool:\(name))"
            }
        case .textDelta: return "textDelta"
        case .toolInputDelta(let name, _): return "toolInputDelta(\(name))"
        case .toolCallComplete(_, let name, _, _): return "toolCallComplete(\(name))"
        case .usage: return "usage"
        case .thinkingDelta: return "thinkingDelta"
        case .reasoningContent: return "reasoningContent"
        case .reasoningEcho: return "reasoningEcho"
        case .done(let stop): return "done(\(stop))"
        }
    }

    // MARK: - Background SSE Stream Processing

    /// Result of processing an SSE stream on a background thread.
    struct StreamResult {
        struct ToolEntry {
            let id: String
            let name: String
            let args: [String: Any]
            let blockIdx: Int
            let metadata: ToolCallMetadata?
            /// Last N `accumulated` JSON snapshots seen during streaming of this
            /// tool's input. Carried alongside so the preflight validator can
            /// dump them on failure for diagnosis. Empty for Gemini (no
            /// progressive deltas) and for whichever provider path didn't run
            /// through the streaming-delta handler.
            let inputChunkRing: [String]
        }
        var assistantText: String = ""
        var spokenTextOffset: Int = 0  // characters already submitted to TTS
        /// Set true after the first textDelta stops the previous reply's TTS.
        var hasStoppedPreviousTTS = false
        /// Completed sentences extracted but not yet flushed to TTS — accumulated
        /// to the dynamic-window size before being sent as one coherent batch.
        var pendingSpeakBuffer: String = ""
        var toolEntries: [ToolEntry] = []
        var stopReason: AgentStopReason? = nil
        var turnUsage: TokenUsage = TokenUsage()
        /// Reasoning content from thinking models — must be stored and echoed back.
        var reasoningContent: String? = nil
        /// Native reasoning payload (e.g. OpenAI Responses-API encrypted items)
        /// for in-memory multi-turn replay. See `ReasoningEcho` for cross-model
        /// isolation rules.
        var reasoningEcho: ReasoningEcho? = nil
        /// Accumulated thinking text for UI display.
        var thinkingText: String = ""
        /// True when the stream was cut short by an error (network drop, timeout, etc.).
        /// tool_use entries collected so far may have incomplete inputs (partialJson truncated).
        var isStreamInterrupted: Bool = false
        #if DEBUG
        var iterationUsage: TokenUsage = TokenUsage()
        #endif
    }

    /// Extract complete sentences from text starting at a character offset.
    /// Sentence endings: 。！？.!?\n
    /// Returns (array of sentences to speak, new offset into text).
    /// Split newly-streamed text into speakable units. A unit ends at a sentence
    /// terminator (。！？.!?) or a newline (so Markdown headings & list items read
    /// as their own line). To keep first-utterance latency low, a run that grows
    /// past `softLimit` characters without a terminator is forced out at the last
    /// comma/pause mark. Fenced code blocks (``` … ```) are skipped (not read).
    /// Markdown lead-in markers (#, -, *, >) are stripped before speaking.
    // MARK: - Speech segmentation tuning (constants — adjust from logs)
    /// Tier-1 boundaries (highest priority): full-sentence terminators + newline.
    nonisolated static let speechEnders: Set<Character> = ["。", "！", "？", ".", "!", "?", "\n", "；", ";"]
    /// Tier-2 boundaries: weaker pause marks used only when a run grows too long
    /// without a tier-1 ender (so we don't read an over-long unbroken clause).
    nonisolated static let speechPauses: Set<Character> = ["，", ",", "、", "：", ":"]
    /// Soft length (chars) past which a run is force-cut at the last pause mark.
    /// Sized below the dynamic-window max so a single extracted unit stays well
    /// under the TTS max-batch ceiling.
    nonisolated static let speechSoftLimit = 60

    /// Static entry point so callers without a vm reference (long-press TTS,
    /// markdown preview read-aloud) can split text with the same segmenter.
    nonisolated static func splitIntoSpeechSegments(_ text: String) -> [String] {
        let (segments, _) = extractSentencesStatic(from: text, spokenOffset: 0)
        return segments.isEmpty ? [text] : segments
    }

    nonisolated func extractNewSentences(from text: String, spokenOffset: Int) -> (sentences: [String], newOffset: Int) {
        Self.extractSentencesStatic(from: text, spokenOffset: spokenOffset)
    }

    nonisolated private static func extractSentencesStatic(from text: String, spokenOffset: Int) -> (sentences: [String], newOffset: Int) {
        guard spokenOffset < text.count else { return ([], spokenOffset) }
        let startIndex = text.index(text.startIndex, offsetBy: spokenOffset)
        let remaining = Array(text[startIndex...])
        let enders = Self.speechEnders
        let pauses = Self.speechPauses
        let softLimit = Self.speechSoftLimit

        var units: [String] = []
        var consumed = 0           // chars consumed from `remaining`
        var unitStart = 0
        var lastPause = -1         // index of last pause mark in the current run

        func isFence(at idx: Int) -> Bool {
            idx + 2 < remaining.count && remaining[idx] == "`"
                && remaining[idx+1] == "`" && remaining[idx+2] == "`"
        }

        // A `.` or `,` flanked by digits on BOTH sides is an intra-number
        // separator (decimal point "28.98" / "3.14" / "$1.5" / "2.0", European
        // decimal comma "€28,98", or thousands grouping "1,000,000"), NOT a
        // sentence boundary or clause pause. Splitting there mangles the number
        // into "28." + "98" and the TTS reads it wrong / mid-word. Both neighbors
        // must be digits so a real sentence end after a number ("He scored 5. Then…")
        // still cuts. Uses Character.isNumber so ASCII and full-width digits both count.
        func isIntraNumberSeparator(at idx: Int) -> Bool {
            let ch = remaining[idx]
            guard ch == "." || ch == "," else { return false }
            guard idx > 0, idx + 1 < remaining.count else { return false }
            return remaining[idx - 1].isNumber && remaining[idx + 1].isNumber
        }

        var i = 0
        while i < remaining.count {
            if isFence(at: i) {
                // Only SKIP a fenced block once we can see its CLOSING fence in the
                // current buffer. An unclosed fence is left for a later delta — we
                // stop here WITHOUT advancing `consumed` past the open fence, but we
                // also stop scanning, so the offset is whatever we consumed BEFORE
                // the fence (never stalls on already-consumed text). When the close
                // arrives we skip the whole block in one go.
                var j = i + 3
                var foundClose = false
                while j < remaining.count {
                    if isFence(at: j) { foundClose = true; break }
                    j += 1
                }
                guard foundClose else { break }   // incomplete fence → wait
                // Flush any pending text before the fence as a unit.
                if i > unitStart { appendUnit(remaining[unitStart..<i], into: &units) }
                let afterClose = j + 3
                unitStart = afterClose; consumed = afterClose; i = afterClose; lastPause = -1
                continue
            }

            let ch = remaining[i]
            // Skip intra-number separators entirely: they must never register as a
            // pause (else a long clause force-cuts inside "€28,98") nor as an ender.
            if isIntraNumberSeparator(at: i) {
                i += 1
                continue
            }
            // Streaming look-ahead: a `.`/`,` that trails a digit at the CURRENT END
            // of the buffer might be a decimal point whose fractional part hasn't
            // streamed yet ("price 28." with "98" still in flight). Committing it now
            // as a sentence ender would speak "28." early and split the number. Stop
            // scanning WITHOUT consuming it — the next delta reveals whether a digit
            // (→ intra-number, skip) or non-digit (→ real boundary) follows. Mirrors
            // the unclosed-fence wait above. Only applies at the very last char so
            // fully-arrived text is never delayed.
            if i == remaining.count - 1, (ch == "." || ch == ","), i > 0, remaining[i - 1].isNumber {
                break
            }
            if pauses.contains(ch) { lastPause = i }

            let runLen = i - unitStart + 1
            let isEnder = enders.contains(ch)
            // Force a cut at a pause mark if the run got too long without an ender.
            let forceCut = !isEnder && runLen >= softLimit && lastPause >= unitStart

            if isEnder {
                appendUnit(remaining[unitStart...i], into: &units)
                i += 1; unitStart = i; consumed = i; lastPause = -1
            } else if forceCut {
                appendUnit(remaining[unitStart...lastPause], into: &units)
                unitStart = lastPause + 1; consumed = unitStart; i += 1; lastPause = -1
            } else {
                i += 1
            }
        }
        return (units, spokenOffset + consumed)
    }

    /// Trim, strip Markdown lead-in markers, and append if non-empty/speakable.
    nonisolated private static func appendUnit(_ slice: ArraySlice<Character>, into units: inout [String]) {
        var s = String(slice).trimmingCharacters(in: .whitespacesAndNewlines)
        // Strip leading markdown markers: #, >, -, *, 1. etc.
        while let first = s.first, "#>-*".contains(first) {
            s.removeFirst()
            s = s.trimmingCharacters(in: .whitespaces)
        }
        // Ordered-list marker "1. " / "12) "
        if let r = s.range(of: "^\\d+[.)]\\s+", options: .regularExpression) {
            s.removeSubrange(r)
        }
        guard !s.isEmpty else { return }
        // Skip units that are only symbols / a bare URL (nothing useful to read).
        if s.range(of: "[\\p{L}\\p{N}]", options: .regularExpression) == nil { return }
        if s.range(of: "^https?://\\S+$", options: .regularExpression) != nil { return }
        units.append(s)
    }

    /// Process SSE stream events on a background thread, dispatching batched UI updates to MainActor.
    /// This keeps CFNetwork HTTP/2 HPACK decoding and JSON parsing off the main thread.
    nonisolated func processStreamEvents(
        stream: AsyncThrowingStream<AgentStreamEvent, Error>,
        msgIdx: Int,
        provider: any AgentProvider
    ) async throws -> StreamResult {
        var result = StreamResult()
        var currentTextBlockIdx: Int? = nil
        var currentThinkingBlockIdx: Int? = nil
        let speakEnabled = await MainActor.run { self.speakEnabled }
        await MainActor.run { VoiceLog.log("stream start: speakEnabled snapshot=\(self.speakEnabled)") }

        // Throttle timestamps (local to this stream processing call)
        var lastTextDeltaFlush: Date = .distantPast
        var lastTextDeltaFlushedLength: Int = 0
        var lastThinkingDeltaFlush: Date = .distantPast
        var lastFileWriteStreamUpdate: Date = .distantPast
        var lastToolInputStreamUpdate: Date = .distantPast
        var lastToolInputScroll: Date = .distantPast
        var currentStreamingToolId: String? = nil
        /// Per-tool-id ring of the most recent `accumulated` snapshots seen
        /// in `.toolInputDelta`. Capped to the last 10 entries to bound
        /// memory. Drained into `ToolEntry.inputChunkRing` on
        /// `.toolCallComplete` so the downstream preflight validator can
        /// dump them when an empty/invalid tool call is detected.
        var toolInputChunkRings: [String: [String]] = [:]
        let toolInputChunkRingMax = 10

        // [T-dedupe-toolcallid 03fbcbfd] Per-turn dedupe of tool_call_id.
        // Some upstream providers (mid-tier OpenAI-compat gateways have been
        // seen doing this) return multiple parallel tool_calls with the
        // SAME id but different name/args. Persisting both raw means the
        // next request's `tool_calls` array fails the receiver's uniqueness
        // check with HTTP 400. Rename collisions client-side: the first
        // occurrence keeps the raw id, second becomes "<id>-2", third
        // "<id>-3", etc. The counter is per-event-type so start / complete
        // get the same rewrite as long as the provider emits them in the
        // same order (which OpenAI-style streams do — both are driven by
        // the `index` field).
        var dedupeStartCounts: [String: Int] = [:]
        var dedupeCompleteCounts: [String: Int] = [:]
        func dedupeToolStartId(_ raw: String) -> String {
            let n = (dedupeStartCounts[raw] ?? 0) + 1
            dedupeStartCounts[raw] = n
            if n == 1 { return raw }
            let renamed = "\(raw)-\(n)"
            logger.warning("[ToolDedupe] duplicate tool_call id on stream start: '\(raw)' #\(n) → renamed '\(renamed)'")
            return renamed
        }
        func dedupeToolCompleteId(_ raw: String) -> String {
            let n = (dedupeCompleteCounts[raw] ?? 0) + 1
            dedupeCompleteCounts[raw] = n
            if n == 1 { return raw }
            return "\(raw)-\(n)"
        }

        let stallTimeoutSeconds: TimeInterval = 120
        var _streamError: Error? = nil
        let iterBox = StreamIteratorBox(stream)
        // [StreamDiag] (#181) Diagnostics for the "No response from the server
        // for 120 seconds" stall. Three hypotheses are still live and this
        // instrumentation is what separates them:
        //   1. the request never actually went out (loop stuck on an await)
        //   2. the stream really did go 120s without an event
        //   3. Task.sleep got stretched by background CPU throttling, so the
        //      watchdog fired on wall-clock far from the nominal 120s
        //      (BrowserTabPool.swift documents this exact trap, verified on
        //      device — that code switched to a GCD wall-clock timer; this
        //      watchdog is still a plain Task.sleep).
        // Both timestamps below are wall-clock, so comparing the measured gap
        // against `stallTimeoutSeconds` settles (3) on its own.
        let streamDiagSession = await MainActor.run { self.sessionId ?? "nil" }
        // [T-ios-streamdiag-phantom-model] Read the model from the PROVIDER —
        // the object this stream actually belongs to. This used to read
        // `self.selectedModel.id`, which is a never-written legacy property
        // whose compile-time default is `.claudeHaiku45`: every stream-open and
        // STALL line therefore claimed "claude-haiku-4-5" regardless of the
        // model the request was really for, and a stall investigation was
        // nearly pinned on a model the session never used.
        let streamDiagModel = provider.model.id
        var lastEventAt = Date()
        var eventSeq = 0
        logger.info("[StreamDiag] stream open session=\(streamDiagSession) provider=\(provider.name) model=\(streamDiagModel) at=\(Self.diagTimestamp(Date()))")
        do {
        while true {
            let iterationStartedAt = Date()
            let maybeEvent: AgentStreamEvent? = try await withThrowingTaskGroup(of: AgentStreamEvent?.self) { group in
                group.addTask { try await iterBox.next() }
                group.addTask {
                    try await Task.sleep(nanoseconds: UInt64(stallTimeoutSeconds * 1_000_000_000))
                    // Measured elapsed vs the nominal timeout: a gap far above
                    // 120s means the sleep was stretched (candidate 3); a gap
                    // very close to 120s rules that out and points at a genuine
                    // silent stream (candidate 2).
                    let firedAt = Date()
                    let measured = firedAt.timeIntervalSince(iterationStartedAt)
                    let sinceLastEvent = firedAt.timeIntervalSince(lastEventAt)
                    let appState = await MainActor.run { Self.diagAppStateName() }
                    logger.error(
                        "[StreamDiag][STALL] watchdog fired session=\(streamDiagSession) provider=\(provider.name) model=\(streamDiagModel) "
                        + "nominal=\(Int(stallTimeoutSeconds))s measured=\(String(format: "%.1f", measured))s "
                        + "sinceLastEvent=\(String(format: "%.1f", sinceLastEvent))s eventsThisStream=\(eventSeq) "
                        + "appState=\(appState) iterStart=\(Self.diagTimestamp(iterationStartedAt)) firedAt=\(Self.diagTimestamp(firedAt))"
                    )
                    // [T-ios-stream-stall-no-retry] An LLMError so the agent
                    // loop's retryable-error gate catches it — see the header
                    // comment at the top of this file.
                    throw LLMError.transientError(message: "No response from the server for \(Int(stallTimeoutSeconds)) seconds — the stream stalled mid-generation")
                }
                let result = try await group.next()!
                group.cancelAll()
                return result
            }
            guard let event = maybeEvent else { break }
            try Task.checkCancellation()
            // [StreamDiag] Per-event timeline. Throttled: the first 5 events of
            // a stream (covers "did the first byte ever arrive"), then only when
            // a gap exceeds 5s — enough to reconstruct the arrival timeline
            // without flooding the log on a normal fast stream.
            do {
                let now = Date()
                let gap = now.timeIntervalSince(lastEventAt)
                eventSeq += 1
                if eventSeq <= 5 || gap >= 5 {
                    logger.info("[StreamDiag] event #\(eventSeq) \(Self.diagEventName(event)) gap=\(String(format: "%.2f", gap))s at=\(Self.diagTimestamp(now)) session=\(streamDiagSession)")
                }
                lastEventAt = now
            }

            switch event {
            case .contentBlockStart(let start):
                // Model started producing output — clear the "thinking" indicator
                await MainActor.run {
                    guard msgIdx < messages.count else { return }
                    if messages[msgIdx].isAwaitingModelResponse {
                        messages[msgIdx].isAwaitingModelResponse = false
                    }
                }
                switch start {
                case .text:
                    // Finalize thinking block content if it was streaming
                    if let thinkIdx = currentThinkingBlockIdx, !result.thinkingText.isEmpty {
                        await MainActor.run {
                            guard msgIdx < messages.count,
                                  thinkIdx < messages[msgIdx].blocks.count else { return }
                            messages[msgIdx].blocks[thinkIdx].flushThinkingBuffer()
                        }
                        currentThinkingBlockIdx = nil
                        result.thinkingText = ""
                    }
                    if currentTextBlockIdx == nil {
                        let idx = await MainActor.run {
                            guard msgIdx < messages.count else { return -1 }
                            let blockCount = messages[msgIdx].blocks.count
                            let prevBlockKind: String = blockCount > 0 ? "\(messages[msgIdx].blocks[blockCount - 1].kind)" : "none"
                            messages[msgIdx].blocks.append(
                                AssistantBlock(kind: .text, content: "")
                            )
                            logger.info("[TextDrift] New empty text block appended at idx=\(blockCount) prevBlockKind=\(prevBlockKind) totalBlocks=\(blockCount + 1)")
                            if let sid = self.sessionId {
                                SessionActivityTracker.shared.updateToolInfo(sessionId: sid, toolName: "text", toolStatus: "streaming")
                            }
                            return messages[msgIdx].blocks.count - 1
                        }
                        guard idx >= 0 else { continue }
                        currentTextBlockIdx = idx
                    }
                case .toolUse(let rawTuId, let name):
                    // [T-dedupe-toolcallid] Rewrite duplicate id ASAP — the
                    // renamed value is what gets persisted to the
                    // AssistantBlock.toolUseId, which is the source of
                    // truth for the next request's tool_calls array.
                    let tuId = dedupeToolStartId(rawTuId)
                    // Finalize any in-flight thinking block before the tool block appends,
                    // so the next thinking segment (after this tool) opens its own block.
                    if let thinkIdx = currentThinkingBlockIdx, !result.thinkingText.isEmpty {
                        await MainActor.run {
                            guard msgIdx < messages.count,
                                  thinkIdx < messages[msgIdx].blocks.count else { return }
                            messages[msgIdx].blocks[thinkIdx].flushThinkingBuffer()
                        }
                        currentThinkingBlockIdx = nil
                        result.thinkingText = ""
                    }
                    // Cache markdown on any preceding text block BEFORE appending the
                    // tool block. This prevents an expensive Markdown re-parse when
                    // the blocks array change triggers a full ForEach re-evaluation.
                    let assistantText = result.assistantText
                    let textIdx = currentTextBlockIdx
                    let blockKind: AssistantBlockKind = switch name {
                    case "shell_execute": .shellTool(command: "")
                    case "file_read": .fileReadTool(path: "")
                    case "file_write": .fileWriteTool(path: "")
                    case "file_edit": .fileEditTool(path: "")
                    case "browser_use": .browserTool(action: "")
                    case "read_image": .readImageTool(path: "")
                    case "memory_write", "memory_get": .memoryTool(action: name)
                    default: .shellTool(command: name)
                    }
                    if name == "file_write" || name == "file_edit" {
                        lastFileWriteStreamUpdate = .distantPast
                    }
                    lastToolInputStreamUpdate = .distantPast
                    // Step 1: Flush any throttled text to the preceding text block.
                    // This is the FINAL flush before a tool block — must bypass
                    // streamingUIUpdatesSuspended to prevent "swallowed text" when
                    // the user is scrolling during streaming.
                    if let textIdx, !assistantText.isEmpty {
                        lastTextDeltaFlushedLength = assistantText.count
                        let prepared = prepareMarkdownForRender(assistantText)
                        let parsed = MarkdownContent(prepared)
                        await MainActor.run {
                            guard msgIdx < messages.count, textIdx < messages[msgIdx].blocks.count else { return }
                            // Write content directly, bypassing suspend check
                            let block = messages[msgIdx].blocks[textIdx]
                            let wasEmpty = block.content.isEmpty
                            block.content = assistantText
                            block.cachedMarkdown = parsed
                            cacheAttributedString(for: block)
                            // [T-ios-stream-publish-transition-gap] "Bypassing
                            // the suspend check" (above) refers to
                            // `streamingUIUpdatesSuspended` — the scroll/defer
                            // gate, which this path deliberately ignores so text
                            // is never swallowed. It must NOT also bypass
                            // `transitionSuspended`: that one exists solely to
                            // keep publishes out of a hosting-subgraph teardown.
                            publishUnlessTransitioning()
                            if wasEmpty {
                                blockContentFilledSignal.send((
                                    messageId: messages[msgIdx].id,
                                    blockId: block.id
                                ))
                            }
                        }
                        // Yield to let SwiftUI process the text update before we mutate blocks again.
                        await Task.yield()
                    }
                    // Step 2: Append the tool block in a separate transaction.
                    await MainActor.run {
                        guard msgIdx < messages.count else { return }
                        #if DEBUG
                        // [T-ios-concurrent-toolcall-dup-id] Collision check —
                        // if another block already carries this tuId, the
                        // tool_result lookup downstream will pick the wrong one
                        // and the loser shows as cancelled.
                        let priorMatches = messages[msgIdx].blocks.filter { $0.toolUseId == tuId }.count
                        if priorMatches > 0 {
                            logger.error("[ToolUseId] COLLISION on append! tuId=\(tuId) name=\(name) — \(priorMatches) existing block(s) already carry this id (dedupe broken or stream re-emitted same start)")
                        }
                        logger.info("[ToolUseId] APPEND tuId=\(tuId) name=\(name) rawIdWasDedupedFrom=\(rawTuId == tuId ? "n/a" : rawTuId) totalBlocksAfter=\(messages[msgIdx].blocks.count + 1)")
                        #endif
                        let toolBlock = AssistantBlock(kind: blockKind, content: "", toolStatus: .streaming(bytes: 0), toolUseId: tuId)
                        toolBlock.toolStartTime = Date()
                        messages[msgIdx].blocks.append(toolBlock)
                        #if DEBUG
                        logger.debug("[TOOL:CREATED] \(name) id:\(tuId.prefix(20)) displayText=\"\(toolBlock.toolSummary ?? toolBlock.toolDescription)\" content=\"\"")
                        #endif
                        logger.info("[ToolLifecycle] RECEIVED toolId=\(tuId.prefix(20)) tool=\(name) sid=\(self.sessionId?.prefix(8) ?? "nil") appState=\(UIApplication.shared.applicationState == .active ? "fg" : "bg") suspended=\(self.streamingUIUpdatesSuspended) isProcessing=\(self.isProcessing)")
                        // [T-ios-stream-publish-transition-gap]
                        publishUnlessTransitioning()
                        scrollToBottomSignal.send()
                    }
                    currentStreamingToolId = tuId
                }

            case .textDelta(let text):
                if let blockIdx = currentTextBlockIdx {
                    result.assistantText += text

                    // Streaming TTS: speak completed sentences as they arrive. Don't
                    // gate on the start-time `speakEnabled` snapshot — read it live
                    // so toggling read-replies ON mid-reply takes effect this turn.
                    // `speakQueued` itself re-checks `canSpeakNow`, so when off this
                    // just advances the spoken offset cheaply.
                    let liveSpeak = await MainActor.run { self.speakEnabled }
                    if liveSpeak {
                        // On the FIRST textDelta of this TURN, clear THIS session's
                        // previous reply from the TTS queue so the new content takes
                        // over. Deferred from send() so the old reply can keep playing
                        // until actual new text arrives. Session-scoped (NOT stopAll):
                        // with multiple chats running concurrently, a global clear
                        // here wiped other sessions' queued speech and lost their
                        // un-flushed text (queue hijack).
                        //
                        // ONCE PER TURN, not per iteration: StreamResult is recreated
                        // on every agent-loop iteration, so gating on its flag alone
                        // made each iteration's first textDelta stopSession the
                        // PREVIOUS iteration's still-playing speech. The vm-level
                        // hasClearedTTSForCurrentTurn (re-armed in send()) makes all
                        // iterations of one send share a single read-aloud context —
                        // iteration N+1 appends after iteration N and plays through.
                        if !result.hasStoppedPreviousTTS {
                            result.hasStoppedPreviousTTS = true
                            await MainActor.run {
                                guard !self.hasClearedTTSForCurrentTurn else { return }
                                self.hasClearedTTSForCurrentTurn = true
                                self.stopSpeechForThisSession()
                            }
                        }
                        let (sentences, newOffset) = extractNewSentences(from: result.assistantText, spokenOffset: result.spokenTextOffset)
                        if !sentences.isEmpty {
                            result.spokenTextOffset = newOffset
                            // Append to the pending buffer, keeping a newline between
                            // sentence units so the dynamic window can cut on a whole-
                            // sentence/paragraph boundary (no mid-sentence splits →
                            // consistent timbre across one synthesized batch).
                            if !result.pendingSpeakBuffer.isEmpty { result.pendingSpeakBuffer += "\n" }
                            result.pendingSpeakBuffer += sentences.joined(separator: "\n")
                            let buffered = result.pendingSpeakBuffer
                            result.pendingSpeakBuffer = await MainActor.run { self.feedDynamicTTS(buffered, force: false) }
                        }
                    }

                    let now = Date()
                    let elapsed = now.timeIntervalSince(lastTextDeltaFlush)
                    let len = result.assistantText.count
                    let unflushed = len - lastTextDeltaFlushedLength
                    // Tiered flush throttle (085e0cd6): protects the main thread
                    // from the O(N²) full-markdown re-parse storm on dense /
                    // box-drawing streams that tripped the 0x8BADF00D watchdog.
                    // The word-fade gets its smooth, steady reveal from the
                    // animation layer's fixed stagger window (see TextFadeAnimator),
                    // NOT from flushing more often — a faster flush here just
                    // re-introduced the SwiftUI ForEach-diff main-thread hang.
                    // First tier matches the streaming-grow layout animation
                    // duration (0.2s) so each flushed batch glides over exactly
                    // one animation window. [T-ios-stream-grow-anim]
                    let throttle: TimeInterval = len < 500 ? 0.20
                                                : len < 2000 ? 0.30
                                                : len < 32_000 ? 0.50
                                                : len < 64_000 ? 1.00
                                                : len < 128_000 ? 1.50
                                                : 2.00

                    // Newline fast-path:
                    //   - Below 5000 chars: flush on newline + ≥50 unflushed (≈ one box-drawing
                    //     line). This still gives a line-by-line feel for short replies.
                    //   - At/above 5000 chars: disable the fast-path. ASCII-art / table runs
                    //     emit a newline every ~2 chars; without this gate the time throttle
                    //     is pinned at the per-chunk delay (~30ms) and the main thread melts.
                    //     Pure time throttle (300-500ms) is the only safe pacing here.
                    let newlineFlush = len < 5000 && text.contains("\n") && unflushed >= 50

                    if elapsed >= throttle || newlineFlush {
                        lastTextDeltaFlush = now
                        lastTextDeltaFlushedLength = len
                        let snapshot = result.assistantText
                        // Parse markdown off main thread — cmark + math extraction + regex transforms
                        let prepared = prepareMarkdownForRender(snapshot)
                        let parsed = MarkdownContent(prepared)
                        await MainActor.run {
                            guard msgIdx < messages.count, blockIdx < messages[msgIdx].blocks.count else { return }
                            self.setStreamingTextBlockContent(
                                msgIdx: msgIdx,
                                blockIdx: blockIdx,
                                text: snapshot,
                                parsedMarkdown: parsed,
                                cacheAttributedString: false,
                                requestScroll: true
                            )
                        }
                    }
                }

            case .toolInputDelta(let name, let accumulated):
                // Maintain a per-tool ring buffer of the most recent `accumulated`
                // snapshots. Cheap (a single Array append + drop-first) and lives
                // outside the throttle so even a fast-truncating stream still
                // leaves a usable tail for diagnosis when the preflight validator
                // later flags an empty/invalid call. Tool-id-keyed so multiple
                // concurrent tool blocks in the same turn don't shadow each other.
                if let toolId = currentStreamingToolId {
                    var ring = toolInputChunkRings[toolId] ?? []
                    ring.append(accumulated)
                    if ring.count > toolInputChunkRingMax {
                        ring.removeFirst(ring.count - toolInputChunkRingMax)
                    }
                    toolInputChunkRings[toolId] = ring
                }
                // CPU-hot path: this fires for every input_json_delta SSE chunk
                // (Anthropic eager_input_streaming = ~150–200/sec for large tool
                // payloads). The accumulated JSON grows by O(N) each delta, so any
                // per-delta scan over the full buffer is O(N²) overall and burns
                // the phone. Gate everything heavy behind the throttle below; the
                // throttled branch must be a true no-op (no MainActor hop, no
                // string scan, no objectWillChange).
                let now = Date()
                let throttleInterval: TimeInterval = (name == "file_write" || name == "file_edit") ? 1.0 : 0.20
                let lastUpdate = (name == "file_write" || name == "file_edit") ? lastFileWriteStreamUpdate : lastToolInputStreamUpdate
                if now.timeIntervalSince(lastUpdate) < throttleInterval {
                    continue
                }
                if name == "file_write" || name == "file_edit" {
                    lastFileWriteStreamUpdate = now
                } else {
                    lastToolInputStreamUpdate = now
                }

                // Past the throttle — find the target block.
                let updateInfo: (blockIdx: Int, blk: AssistantBlock)? = await MainActor.run {
                    guard msgIdx < messages.count else { return nil }
                    if let toolId = currentStreamingToolId,
                       let blockIdx = messages[msgIdx].blocks.indices.last(where: {
                           messages[msgIdx].blocks[$0].toolUseId == toolId
                       }) {
                        return (blockIdx, messages[msgIdx].blocks[blockIdx])
                    }
                    // Fallback: last block with a toolStatus (preserves prior behaviour)
                    guard let blockIdx = messages[msgIdx].blocks.indices.last(where: {
                        messages[msgIdx].blocks[$0].toolStatus != nil
                    }) else { return nil }
                    return (blockIdx, messages[msgIdx].blocks[blockIdx])
                }
                guard let (_, blk) = updateInfo else { continue }

                // Update kind with path/command as soon as it's available in partial JSON.
                let kindUpdate: AssistantBlockKind? = {
                    switch name {
                    case "file_write":
                        if let path = extractPartialStringValue("path", from: accumulated) {
                            return .fileWriteTool(path: path)
                        }
                    case "file_edit":
                        if let path = extractPartialStringValue("path", from: accumulated) {
                            return .fileEditTool(path: path)
                        }
                    case "file_read":
                        if let path = extractPartialStringValue("path", from: accumulated) {
                            return .fileReadTool(path: path)
                        }
                    case "shell_execute":
                        if let cmd = extractPartialStringValue("command", from: accumulated) {
                            return .shellTool(command: cmd)
                        }
                    case "browser_use":
                        if let action = extractPartialStringValue("action", from: accumulated) {
                            return .browserTool(action: action)
                        }
                    case "read_image":
                        if let path = extractPartialStringValue("path", from: accumulated) {
                            return .readImageTool(path: path)
                        }
                    default: break
                    }
                    return nil
                }()

                let (preview, desc) = toolInputPreview(name: name, json: accumulated)
                let byteCount = accumulated.utf8.count
                let partialURL: String? = (name == "browser_use")
                    ? extractPartialStringValue("url", from: accumulated)
                    : nil
                let streamingContent: String? = (name == "file_write" || name == "file_edit") ? extractPartialStringValue("old_string", from: accumulated) ?? extractPartialStringValue("content", from: accumulated) : nil
                let shouldScroll = now.timeIntervalSince(lastToolInputScroll) >= 0.10
                if shouldScroll { lastToolInputScroll = now }
                await MainActor.run {
                    if let kindUpdate { blk.kind = kindUpdate }
                    let hadSummary = blk.toolSummary != nil
                    // Only overwrite the preview content when we have an
                    // actual user-friendly string. `preview == nil` means
                    // the primary param hasn't streamed yet (e.g. only
                    // `tool_title` arrived so far) — keep whatever was
                    // there to avoid flashing raw JSON in the terminal.
                    if let preview { blk.content = preview }
                    if let desc { blk.toolSummary = desc }
                    if let streamingContent { blk.streamingFileContent = streamingContent }
                    let streamingBrowserURL = partialURL ?? ((name == "browser_use") ? browserTabPool.activeManager?.currentURL : nil)
                    if let streamingBrowserURL, !streamingBrowserURL.isEmpty { blk.browserURL = streamingBrowserURL }
                    blk.toolStatus = .streaming(bytes: byteCount)
                    #if DEBUG
                    let displayContent = preview ?? blk.content
                    logger.debug("[TOOL:STREAMING] \(name) displayText=\"\(blk.toolSummary ?? blk.toolDescription)\" content=\"\(displayContent.prefix(120))\" bytes:\(byteCount)")
                    #endif
                    if !hadSummary && blk.toolSummary != nil {
                        // [T-ios-stream-publish-transition-gap] This is the
                        // `[TOOL:STREAMING]` site whose publishes straddled the
                        // teardown in the 2026-08-10 crash log.
                        publishUnlessTransitioning()
                    }
                    if shouldScroll { scrollToBottomSignal.send() }
                }

            case .toolCallComplete(let rawId, let name, let args, let metadata):
                // [T-dedupe-toolcallid] Mirror the rewrite applied at
                // contentBlockStart so the block-by-toolUseId lookup
                // below finds the renamed block (the FIRST tool keeps
                // the raw id; subsequent collisions get -2 / -3 / ...).
                let id = dedupeToolCompleteId(rawId)
                let content = makeToolPreview(name: name, args: args)
                let assistantText = result.assistantText
                let textIdx = currentTextBlockIdx

                // Force-flush any dynamically-buffered (extracted but un-sent) TTS
                // text before the tool announcement, so it isn't lost / reordered.
                if !result.pendingSpeakBuffer.isEmpty {
                    let buf = result.pendingSpeakBuffer
                    result.pendingSpeakBuffer = ""
                    _ = await MainActor.run { self.feedDynamicTTS(buf, force: true) }
                }

                // Flush any unspoken text before announcing the tool
                let remainingTextForTool: String
                if result.spokenTextOffset < result.assistantText.count {
                    let startIdx = result.assistantText.index(result.assistantText.startIndex, offsetBy: result.spokenTextOffset)
                    remainingTextForTool = String(result.assistantText[startIdx...]).trimmingCharacters(in: .whitespacesAndNewlines)
                    result.spokenTextOffset = result.assistantText.count
                } else {
                    remainingTextForTool = ""
                }

                // Re-parse the accumulated assistantText OFF the main thread
                // so the toolCallComplete flush below doesn't stall the UI
                // (prepareMarkdownForRender + cmark on 20 KB+ streamed text
                // can be 30–100 ms). The node equivalent of line 6024's
                // throttled-flush parse. Done here because we're still on
                // the stream-processing actor, not MainActor.
                let freshParsedMarkdown: MarkdownContent? = (textIdx != nil && !assistantText.isEmpty)
                    ? MarkdownContent(prepareMarkdownForRender(assistantText))
                    : nil

                let blockIdx: Int = await MainActor.run {
                    guard msgIdx < messages.count, !messages[msgIdx].blocks.isEmpty else { return -1 }
                    let blockIdx = messages[msgIdx].blocks.indices.last(where: {
                        messages[msgIdx].blocks[$0].toolUseId == id
                    }) ?? messages[msgIdx].blocks.indices.last(where: {
                        messages[msgIdx].blocks[$0].toolStatus != nil
                    }) ?? (messages[msgIdx].blocks.count - 1)
                    guard blockIdx >= 0, blockIdx < messages[msgIdx].blocks.count else { return -1 }

                    messages[msgIdx].blocks[blockIdx].content = content
                    messages[msgIdx].blocks[blockIdx].toolStatus = .running
                    messages[msgIdx].blocks[blockIdx].streamingFileContent = nil
                    switch name {
                    case "file_write":
                        if let path = args["path"] as? String {
                            messages[msgIdx].blocks[blockIdx].kind = .fileWriteTool(path: path)
                        }
                    case "file_edit":
                        if let path = args["path"] as? String {
                            messages[msgIdx].blocks[blockIdx].kind = .fileEditTool(path: path)
                        }
                    case "file_read":
                        if let path = args["path"] as? String {
                            messages[msgIdx].blocks[blockIdx].kind = .fileReadTool(path: path)
                        }
                    case "shell_execute":
                        if let cmd = args["command"] as? String {
                            messages[msgIdx].blocks[blockIdx].kind = .shellTool(command: cmd)
                        }
                    case "browser_use":
                        if let action = args["action"] as? String {
                            messages[msgIdx].blocks[blockIdx].kind = .browserTool(action: action)
                        }
                        if let url = args["url"] as? String {
                            messages[msgIdx].blocks[blockIdx].browserURL = url
                        } else if let mgr = browserTabPool.activeManager {
                            let cur = mgr.currentURL
                            if !cur.isEmpty {
                                messages[msgIdx].blocks[blockIdx].browserURL = cur
                            }
                        }
                    case "read_image":
                        if let path = args["path"] as? String {
                            messages[msgIdx].blocks[blockIdx].kind = .readImageTool(path: path)
                        }
                    default: break
                    }
                    // Store serialized tool input args for introspection
                    if let argsData = try? JSONSerialization.data(withJSONObject: args),
                       let argsStr = String(data: argsData, encoding: .utf8) {
                        messages[msgIdx].blocks[blockIdx].toolInputArgs = argsStr
                    }
                    if let desc = args["tool_title"] as? String {
                        messages[msgIdx].blocks[blockIdx].toolSummary = desc
                    }
                    if let sid = self.sessionId {
                        let blk = messages[msgIdx].blocks[blockIdx]
                        let snippet = blk.toolSummary ?? blk.toolDescription
                        SessionActivityTracker.shared.updateToolInfo(sessionId: sid, toolName: name, toolStatus: snippet)
                        logger.info("[ToolLifecycle] RENDERED_STATUSBAR toolId=\(id.prefix(20)) tool=\(name) sid=\(sid.prefix(8)) appState=\(UIApplication.shared.applicationState == .active ? "fg" : "bg") suspended=\(self.streamingUIUpdatesSuspended) isProcessing=\(self.isProcessing)")
                    }
                    let speakDesc: String
                    do {
                        let blk = messages[msgIdx].blocks[blockIdx]
                        speakDesc = blk.toolSummary ?? blk.toolDescription
                        #if DEBUG
                        logger.debug("[TOOL:RUNNING] \(name) id:\(id.prefix(8)) displayText=\"\(speakDesc)\" content=\"\(blk.content.prefix(120))\"")
                        #endif
                    }
                    if !remainingTextForTool.isEmpty { self.speakQueued(remainingTextForTool) }
                    // Read a spoken announcement for the tool — e.g. "正在执行脚本:…"
                    // — instead of the terse on-screen preview, so listeners get a
                    // natural cue about what's running. Queued (ordered) so it
                    // follows any preceding text and precedes the next text.
                    self.speakQueued(Self.makeToolSpeech(name: name, args: args, title: messages[msgIdx].blocks[blockIdx].toolSummary))
                    scrollToBottomSignal.send()

                    // Flush the fresh snapshot + its freshly-parsed markdown
                    // (computed above off the main thread). Don't fall back
                    // to block.cachedMarkdown — it may have been set from an
                    // earlier throttled snapshot that is now a tail-shorter
                    // subset of `assistantText`, which would cause
                    // SelectableMarkdownView to render the stale prefix
                    // instead of the full text (user-visible "dropped characters").
                    if let textIdx, let freshParsedMarkdown, !assistantText.isEmpty,
                       textIdx < messages[msgIdx].blocks.count {
                        self.setStreamingTextBlockContent(
                            msgIdx: msgIdx,
                            blockIdx: textIdx,
                            text: assistantText,
                            parsedMarkdown: freshParsedMarkdown,
                            cacheAttributedString: true,
                            requestScroll: false
                        )
                    }
                    return blockIdx
                }
                currentStreamingToolId = nil
                guard blockIdx >= 0 else { continue }
                // Harvest the per-tool streaming-chunk ring before it goes out
                // of scope — the preflight validator downstream uses it for
                // diagnosis when args turn out to be empty / missing fields.
                let harvestedRing = toolInputChunkRings.removeValue(forKey: id) ?? []
                result.toolEntries.append(StreamResult.ToolEntry(id: id, name: name, args: args, blockIdx: blockIdx, metadata: metadata, inputChunkRing: harvestedRing))

                // Record metadata for Gemini thought signature echoing
                if let geminiProvider = provider as? GeminiAgentProvider {
                    geminiProvider.recordToolCallMetadata(id: id, metadata: metadata)
                }

                // Reset text block tracking for potential next text block after tool use
                currentTextBlockIdx = nil
                result.assistantText = ""
                result.spokenTextOffset = 0

            case .thinkingDelta(let text):
                // Each contiguous thinking segment becomes its own block, appended in
                // arrival order so thinking interleaves naturally with text/tool blocks
                // (matches how messages render after reload from RawMessage history).
                if currentThinkingBlockIdx == nil {
                    let idx = await MainActor.run { () -> Int in
                        guard msgIdx < messages.count else { return -1 }
                        messages[msgIdx].blocks.append(
                            AssistantBlock(kind: .thinking, content: "")
                        )
                        return messages[msgIdx].blocks.count - 1
                    }
                    guard idx >= 0 else { continue }
                    currentThinkingBlockIdx = idx
                    // Reset accumulator — each thinking block has its own text.
                    result.thinkingText = ""
                }
                result.thinkingText += text
                // Append to the block's non-published buffer (O(1) for SwiftUI).
                await MainActor.run {
                    guard msgIdx < self.messages.count,
                          let thinkIdx = currentThinkingBlockIdx,
                          thinkIdx < self.messages[msgIdx].blocks.count else { return }
                    self.messages[msgIdx].blocks[thinkIdx].appendThinkingDelta(text)
                }
                // Throttled flush to @Published content — only fires
                // objectWillChange when we actually push to `content`. The
                // interval is ADAPTIVE [T-thinking-stream-jank]: it widens as
                // the thinking grows (0.3s under 1K → 1.5s past 6K, see
                // AssistantBlock.thinkingFlushTiers) so long thinking streams
                // trigger proportionally fewer body re-evals + scrollTos in
                // the expanded view. `.done` still force-flushes the tail.
                let thinkNow = Date()
                let flushInterval = AssistantBlock.thinkingFlushInterval(forLength: result.thinkingText.count)
                if thinkNow.timeIntervalSince(lastThinkingDeltaFlush) >= flushInterval {
                    lastThinkingDeltaFlush = thinkNow
                    await MainActor.run {
                        guard msgIdx < self.messages.count,
                              let thinkIdx = currentThinkingBlockIdx,
                              thinkIdx < self.messages[msgIdx].blocks.count else { return }
                        self.messages[msgIdx].blocks[thinkIdx].flushThinkingBuffer()
                        // [T-ios-stream-publish-transition-gap]
                        self.publishUnlessTransitioning()
                        if self.isNearBottom { self.scrollToBottomSignal.send() }
                    }
                }

            case .reasoningContent(let rc):
                // Aggregate reasoning text for AgentMessage persistence /
                // multi-turn echo. UI thinking blocks are already populated
                // incrementally via `.thinkingDelta`; no end-of-turn overwrite
                // (would conflate multiple reasoning items into block #0).
                result.reasoningContent = rc

            case .reasoningEcho(let echo):
                // In-memory only; replayed by convertMessagesResponsesAPI on
                // the next request when model id matches. See ReasoningEcho.
                result.reasoningEcho = echo

            case .usage(let u):
                result.turnUsage.add(u)
                #if DEBUG
                result.iterationUsage.add(u)
                #endif

            case .done(let reason):
                // Final flush of thinking block content
                if let thinkIdx = currentThinkingBlockIdx, !result.thinkingText.isEmpty {
                    let finalThinking = result.thinkingText
                    await MainActor.run {
                        guard msgIdx < messages.count,
                              thinkIdx < messages[msgIdx].blocks.count else { return }
                        messages[msgIdx].blocks[thinkIdx].content = finalThinking
                    }
                }
                result.stopReason = reason
            }
        }
        // The AsyncThrowingStream may silently terminate (return nil) on Task
        // cancellation instead of throwing CancellationError.  Re-check here so
        // the caller never receives a half-parsed StreamResult.
        try Task.checkCancellation()
        } catch is CancellationError {
            // Flush any throttled text to the block before propagating cancellation,
            // so handleUserCancelledCleanup sees the full streamed content.
            if let blockIdx = currentTextBlockIdx, !result.assistantText.isEmpty {
                await MainActor.run {
                    guard msgIdx < messages.count, blockIdx < messages[msgIdx].blocks.count else { return }
                    self.setStreamingTextBlockContent(
                        msgIdx: msgIdx,
                        blockIdx: blockIdx,
                        text: result.assistantText,
                        parsedMarkdown: MarkdownContent(prepareMarkdownForRender(result.assistantText)),
                        cacheAttributedString: true,
                        requestScroll: false
                    )
                }
            }
            throw CancellationError()
        } catch {
            result.isStreamInterrupted = true
            _streamError = error
        }
        if let err = _streamError { throw err }
        // Stream ended cleanly. First drain any un-extracted tail past
        // spokenTextOffset: the streaming look-ahead in extractSentencesStatic
        // intentionally STOPS on a digit-trailing `.`/`,` at the buffer end
        // (a possible in-flight decimal), so a reply that genuinely ends on a
        // number+period ("Total: 100.") leaves that final unit un-extracted.
        // Now that no more deltas are coming, extract with the complete text so
        // it isn't dropped, then merge into the pending buffer for the flush.
        if result.spokenTextOffset < result.assistantText.count {
            let (tail, newOffset) = extractNewSentences(from: result.assistantText, spokenOffset: result.spokenTextOffset)
            result.spokenTextOffset = newOffset
            let leftover = tail.joined(separator: "\n")
            if !leftover.isEmpty {
                if !result.pendingSpeakBuffer.isEmpty { result.pendingSpeakBuffer += "\n" }
                result.pendingSpeakBuffer += leftover
            }
            // Even after re-extraction a lone trailing "100." can remain unemitted
            // (still the last char, digit before it) — sweep the raw remainder so
            // the tail is never silently lost.
            if result.spokenTextOffset < result.assistantText.count {
                let startIdx = result.assistantText.index(result.assistantText.startIndex, offsetBy: result.spokenTextOffset)
                let rawTail = String(result.assistantText[startIdx...]).trimmingCharacters(in: .whitespacesAndNewlines)
                result.spokenTextOffset = result.assistantText.count
                if !rawTail.isEmpty {
                    if !result.pendingSpeakBuffer.isEmpty { result.pendingSpeakBuffer += "\n" }
                    result.pendingSpeakBuffer += rawTail
                }
            }
        }
        // Force-flush any dynamically-buffered TTS text that didn't reach the
        // window size, so the reply's tail is still read.
        if !result.pendingSpeakBuffer.isEmpty {
            let buf = result.pendingSpeakBuffer
            result.pendingSpeakBuffer = ""
            _ = await MainActor.run { self.feedDynamicTTS(buf, force: true) }
        }
        return result
    }

    /// Create initial display content from tool name and parsed arguments.
    /// A natural spoken announcement for a tool that just started running, e.g.
    /// "正在执行脚本:安装依赖". Follows the reply's language (Chinese when the
    /// model-provided title contains Han characters, else English). The detail is
    /// kept short — filename only for paths, a truncated command for shells.
    nonisolated static func makeToolSpeech(name: String, args: [String: Any], title: String?) -> String {
        let zh = (title?.range(of: "\\p{Han}", options: .regularExpression) != nil)
        func fileName(_ p: String?) -> String {
            guard let p, !p.isEmpty else { return zh ? "文件" : "a file" }
            return (p as NSString).lastPathComponent
        }
        func clip(_ s: String?, _ n: Int) -> String? {
            guard let s = s?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
            return s.count > n ? String(s.prefix(n)) + "…" : s
        }
        // Prefer the model's tool_title as the detail; fall back to a parameter.
        let detail = clip(title, 40)

        switch name {
        case "shell_execute":
            let d = detail ?? clip(args["command"] as? String, 40) ?? ""
            return zh ? "正在执行脚本:\(d)" : "Executing script: \(d)"
        case "file_read":
            let d = detail ?? fileName(args["path"] as? String)
            return zh ? "正在读取文件:\(d)" : "Reading file: \(d)"
        case "file_write":
            let d = detail ?? fileName(args["path"] as? String)
            return zh ? "正在写入文件:\(d)" : "Writing file: \(d)"
        case "file_edit":
            let d = detail ?? fileName(args["path"] as? String)
            return zh ? "正在编辑文件:\(d)" : "Editing file: \(d)"
        case "browser_use":
            let d = detail ?? clip(args["action"] as? String, 40) ?? ""
            return zh ? "正在操作浏览器:\(d)" : "Using browser: \(d)"
        case "read_image":
            let d = detail ?? fileName(args["path"] as? String)
            return zh ? "正在读取图片:\(d)" : "Reading image: \(d)"
        default:
            let d = detail ?? name
            return zh ? "正在执行:\(d)" : "Running: \(d)"
        }
    }

    nonisolated private func makeToolPreview(name: String, args: [String: Any]) -> String {
        switch name {
        case "shell_execute":
            return "Executing..."
        case "file_read":
            return "Reading \((args["path"] as? String) ?? "?")..."
        case "file_write":
            return "Writing \((args["path"] as? String) ?? "?")..."
        case "file_edit":
            return "Editing \((args["path"] as? String) ?? "?")..."
        case "browser_use":
            return "Browser: \((args["action"] as? String) ?? "?")..."
        case "read_image":
            return "Reading image \((args["path"] as? String) ?? "?")..."
        default:
            return "Tool: \(name)"
        }
    }

    /// Extract a human-readable preview from streaming partial JSON for tool input.
    /// The JSON may be incomplete (e.g. `{"command": "apt install py` ).
    ///
    /// Returns `(preview, description)`. `preview` is `nil` when the
    /// primary parameter hasn't streamed yet — the call site MUST keep
    /// the existing `blk.content` instead of overwriting with raw JSON.
    /// Without this, the LLM's first chunk (typically the `tool_title`
    /// property listed first in `propertyOrdering`) would be displayed
    /// in the terminal preview as literal `{"tool_title":"..."}`.
    nonisolated private func toolInputPreview(name: String, json: String) -> (preview: String?, description: String?) {
        let desc = extractPartialStringValue("tool_title", from: json)

        // Try to extract the primary parameter value from partial JSON
        switch name {
        case "shell_execute":
            if let val = extractPartialStringValue("command", from: json) {
                return ("\(val)", desc)
            }
        case "file_read":
            if let val = extractPartialStringValue("path", from: json) {
                return ("Reading \(val)...", desc)
            }
        case "file_write":
            let path = extractPartialStringValue("path", from: json)
            let content = extractPartialStringValue("content", from: json)
            if let path, let content {
                let bytes = content.utf8.count
                if bytes > 100 {
                    return ("Writing \(path)  (\(formatBytes(bytes)) received)", desc)
                } else {
                    return ("Writing \(path)...", desc)
                }
            } else if let path {
                return ("Writing \(path)...", desc)
            } else if let content {
                let bytes = content.utf8.count
                if bytes > 100 {
                    return ("Writing...  (\(formatBytes(bytes)) received)", desc)
                }
                return ("Writing...", desc)
            }
        case "file_edit":
            if let path = extractPartialStringValue("path", from: json) {
                return ("Editing \(path)...", desc)
            }
            // No path yet — leave preview unchanged (don't return "Editing...",
            // which would also overwrite a previously-good preview when the
            // streaming is briefly between fields).
        case "browser_use":
            if let action = extractPartialStringValue("action", from: json) {
                let detail: String
                if let url = extractPartialStringValue("url", from: json) {
                    detail = " \(url)"
                } else if let sel = extractPartialStringValue("selector", from: json) {
                    detail = " \(sel)"
                } else {
                    detail = ""
                }
                return ("Browser: \(action)\(detail)", desc)
            }
        case "read_image":
            if let val = extractPartialStringValue("path", from: json) {
                return ("Reading image \(val)...", desc)
            }
        default:
            break
        }
        // No primary param streamed yet — caller keeps the previous preview.
        // Returning the raw JSON here would surface `{"tool_title":"..."}`
        // (or any other internal field) to the UI for one or more frames.
        return (nil, desc)
    }

    /// Extract a partial string value for a key from potentially incomplete JSON.
    /// Handles incomplete JSON like `{"key": "partial value...` (no closing quote).
    ///
    /// Operates on `UTF8View` byte-by-byte to avoid Swift `String` grapheme-cluster
    /// iteration (which is O(scanned) per `index(after:)` for large strings) and
    /// avoids any `String(substring)` copies on the input — only the matched value
    /// is materialized as a new `String`. For typical LLM tool JSON (ASCII keys,
    /// largely ASCII values with short escapes) this turns the per-delta cost from
    /// O(N) memcpy + O(N) grapheme decode into O(N) byte scan with one allocation
    /// sized to the value (not the whole accumulated buffer).
    nonisolated private func extractPartialStringValue(_ key: String, from json: String) -> String? {
        let utf8 = json.utf8
        // Build pattern bytes once: `"key":` (whitespace before `"` allowed).
        // We accept either `"key": "` or `"key":"` (the only forms our providers emit).
        let keyBytes = Array(key.utf8)
        let n = utf8.count
        let patternLen = keyBytes.count + 3 // `"` + key + `":`
        guard n >= patternLen + 1 else { return nil }

        let bytes = Array(utf8) // single contiguous copy of *the input*; cheap vs the per-call work it replaces
        let count = bytes.count
        var i = 0
        let limit = count - patternLen
        outer: while i <= limit {
            // Scan for an opening `"` of a key
            guard let q = bytes[i...].firstIndex(of: 0x22) else { return nil }
            i = q + 1
            // Match key bytes
            if i + keyBytes.count + 2 > count { return nil }
            for k in 0..<keyBytes.count {
                if bytes[i + k] != keyBytes[k] { continue outer }
            }
            // Expect `":` after the key
            guard bytes[i + keyBytes.count] == 0x22, bytes[i + keyBytes.count + 1] == 0x3A else { continue outer }
            // Skip optional spaces
            var j = i + keyBytes.count + 2
            while j < count, bytes[j] == 0x20 { j += 1 }
            // Expect opening `"` of value
            guard j < count, bytes[j] == 0x22 else { return nil }
            j += 1
            return decodeJSONStringContent(bytes: bytes, start: j)
        }
        return nil
    }

    /// Decode JSON string content starting at `start` (just past the opening quote)
    /// up to the first unescaped `"` (or end-of-buffer for streaming partials).
    /// Builds the unescaped output in a single pass — no `replacingOccurrences` chain.
    nonisolated private func decodeJSONStringContent(bytes: [UInt8], start: Int) -> String {
        let count = bytes.count
        var out = [UInt8]()
        out.reserveCapacity(count - start)
        var i = start
        while i < count {
            let b = bytes[i]
            if b == 0x22 { // unescaped `"` — end of string
                break
            }
            if b == 0x5C { // backslash
                i += 1
                if i >= count { break } // streaming: backslash arrived, escapee not yet
                switch bytes[i] {
                case 0x6E: out.append(0x0A) // \n
                case 0x74: out.append(0x09) // \t
                case 0x72: out.append(0x0D) // \r
                case 0x22: out.append(0x22) // \"
                case 0x5C: out.append(0x5C) // \\
                case 0x2F: out.append(0x2F) // \/
                case 0x62: out.append(0x08) // \b
                case 0x66: out.append(0x0C) // \f
                case 0x75:
                    // \uXXXX — best-effort: pass through if incomplete; otherwise
                    // decode to UTF-8. Surrogate pairs handled by appending the
                    // raw escape if the second half hasn't arrived.
                    if i + 4 < count {
                        if let scalar = parseHex4(bytes: bytes, offset: i + 1) {
                            // Handle surrogate pair
                            if scalar >= 0xD800 && scalar <= 0xDBFF, i + 10 < count, bytes[i+5] == 0x5C, bytes[i+6] == 0x75,
                               let low = parseHex4(bytes: bytes, offset: i + 7),
                               low >= 0xDC00 && low <= 0xDFFF {
                                let combined = 0x10000 + ((scalar - 0xD800) << 10) + (low - 0xDC00)
                                if let u = Unicode.Scalar(combined) {
                                    appendUTF8(&out, u)
                                }
                                i += 10
                                continue
                            } else if let u = Unicode.Scalar(scalar) {
                                appendUTF8(&out, u)
                            }
                            i += 4
                        } else {
                            // Malformed; preserve literally
                            out.append(0x5C); out.append(0x75)
                        }
                    } else {
                        // Streaming partial — drop the dangling `\u` rather than emit garbage
                        return String(decoding: out, as: UTF8.self)
                    }
                default:
                    // Unknown escape — preserve verbatim
                    out.append(0x5C); out.append(bytes[i])
                }
                i += 1
                continue
            }
            out.append(b)
            i += 1
        }
        return String(decoding: out, as: UTF8.self)
    }

    nonisolated private func parseHex4(bytes: [UInt8], offset: Int) -> UInt32? {
        var v: UInt32 = 0
        for k in 0..<4 {
            let b = bytes[offset + k]
            let d: UInt32
            switch b {
            case 0x30...0x39: d = UInt32(b - 0x30)
            case 0x41...0x46: d = UInt32(b - 0x41 + 10)
            case 0x61...0x66: d = UInt32(b - 0x61 + 10)
            default: return nil
            }
            v = (v << 4) | d
        }
        return v
    }

    nonisolated private func appendUTF8(_ out: inout [UInt8], _ scalar: Unicode.Scalar) {
        let v = scalar.value
        if v < 0x80 {
            out.append(UInt8(v))
        } else if v < 0x800 {
            out.append(UInt8(0xC0 | (v >> 6)))
            out.append(UInt8(0x80 | (v & 0x3F)))
        } else if v < 0x10000 {
            out.append(UInt8(0xE0 | (v >> 12)))
            out.append(UInt8(0x80 | ((v >> 6) & 0x3F)))
            out.append(UInt8(0x80 | (v & 0x3F)))
        } else {
            out.append(UInt8(0xF0 | (v >> 18)))
            out.append(UInt8(0x80 | ((v >> 12) & 0x3F)))
            out.append(UInt8(0x80 | ((v >> 6) & 0x3F)))
            out.append(UInt8(0x80 | (v & 0x3F)))
        }
    }

    /// Format byte count into a human-readable string.
    nonisolated func formatBytes(_ bytes: Int) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        let kb = Double(bytes) / 1024.0
        if kb < 1024 { return String(format: "%.1f KB", kb) }
        let mb = kb / 1024.0
        return String(format: "%.1f MB", mb)
    }

    /// Parse a single string parameter from tool JSON.
    private func parseStringParam(_ key: String, from json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return dict[key] as? String
    }

    /// Parse tool use JSON into command, optional timeout, and optional delay.
    func parseToolInput(from json: String) -> (command: String, timeout: TimeInterval, delay: TimeInterval) {
        // Two failure modes are folded together: (a) JSON itself is invalid
        // (rare; would mean the provider's accumulated tool args were
        // truncated mid-stream) and (b) JSON parses fine but is missing the
        // required `command` key — the empty-args bug observed with Kimi
        // K2.6 / GPT-5.5 where the model emits `{}` as the entire arguments
        // payload. Return command="" in both cases so the caller can detect
        // the missing-arg condition and surface a meaningful tool_result
        // error to the model instead of trying to exec the literal string
        // "{}" via /bin/sh — which produced "/bin/sh: {}: not found".
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let command = dict["command"] as? String else {
            return ("", defaultCommandTimeout, 0)
        }
        let timeout = (dict["timeout"] as? NSNumber).map { TimeInterval($0.doubleValue) } ?? defaultCommandTimeout
        let delay = (dict["delay"] as? NSNumber).map { TimeInterval($0.doubleValue) } ?? 0
        return (command, timeout, delay)
    }

}
