import Foundation
import ImageIO
import UIKit

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Context Window Offloading

extension AIChatViewModel {

    // MARK: - Context Window Offloading

    /// Save context content to disk for later retrieval via file_read.
    /// Returns the Linux-visible path where the file was saved.
    /// Derive a short unique suffix from a tool ID for use in file names.
    /// Anthropic IDs are `toolu_01XXXX...` (prefix is always `toolu_01`),
    /// so we take the last 12 characters to guarantee uniqueness.
    private nonisolated static func shortToolId(_ toolId: String) -> String {
        String(toolId.suffix(12))
    }

    /// [GH#343] True when `content` is an offload stub — either raw, or wrapped in the
    /// "[<path> | <n> bytes | <m> lines | …]" header that file_read prepends. A bare
    /// `hasPrefix("[CONTEXT OFFLOADED]")` misses the prefixed form, so the stub was
    /// offloaded again in a loop.
    private nonisolated static func isOffloadReadback(_ content: String) -> Bool {
        let prefix = "[CONTEXT OFFLOADED]"
        guard let marker = content.range(of: prefix) else { return false }
        let markerAt = content.distance(from: content.startIndex, to: marker.lowerBound)
        if markerAt == 0 { return true }
        if markerAt > 512 { return false }
        return content[..<marker.lowerBound].contains(Self.minisOffloadsLinuxDir)
    }

    private func offloadContextContent(_ content: String, toolId: String, toolName: String, ext: String = "txt") -> String {
        let fm = FileManager.default
        let sid = sessionId ?? "unknown"
        let persistDir = Self.minisOffloadsPersistentDir(for: sid)
            .appendingPathComponent("tools", isDirectory: true)
        try? fm.createDirectory(at: persistDir, withIntermediateDirectories: true)

        let sanitizedName = toolName.isEmpty ? "tool" : toolName.replacingOccurrences(of: "/", with: "_")
        let shortId = Self.shortToolId(toolId)
        let fileName = "\(sanitizedName)_\(shortId).\(ext)"
        let persistPath = persistDir.appendingPathComponent(fileName)
        try? content.write(to: persistPath, atomically: true, encoding: .utf8)

        return "\(Self.minisOffloadsLinuxDir)/tools/\(fileName)"
    }

    /// Save context image data to disk for later retrieval.
    /// Returns the Linux-visible path where the file was saved.
    private func offloadContextImage(_ data: Data, toolId: String, mimeType: String) -> String {
        let fm = FileManager.default
        let sid = sessionId ?? "unknown"
        let persistDir = Self.minisOffloadsPersistentDir(for: sid)
            .appendingPathComponent("tools", isDirectory: true)
        try? fm.createDirectory(at: persistDir, withIntermediateDirectories: true)

        let ext: String
        switch mimeType {
        case "image/png": ext = "png"
        case "image/jpeg": ext = "jpg"
        case "image/gif": ext = "gif"
        case "image/webp": ext = "webp"
        default: ext = "bin"
        }

        let shortId = Self.shortToolId(toolId)
        let fileName = "image_\(shortId).\(ext)"
        let persistPath = persistDir.appendingPathComponent(fileName)
        try? data.write(to: persistPath)

        return "\(Self.minisOffloadsLinuxDir)/tools/\(fileName)"
    }

    /// Decode the pixel dimensions of an image blob without fully decoding it.
    /// Returns (width, height) or nil on failure.
    private static func imagePixelSize(_ data: Data) -> (Int, Int)? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int,
              let h = props[kCGImagePropertyPixelHeight] as? Int else { return nil }
        return (w, h)
    }

    private static let byteFormatter: ByteCountFormatter = {
        let f = ByteCountFormatter()
        f.countStyle = .file
        f.allowedUnits = [.useKB, .useMB]
        return f
    }()

    /// Build the text placeholder that replaces an image omitted from the
    /// sent context. Dimensions + byte size give the model enough semantic
    /// context to recognize the image from memory, and the path hints let
    /// it re-fetch the bytes via `read_image` when needed.
    ///
    /// Two forms:
    /// - Stable original (no snapshot needed): `originalPath` set,
    ///   `snapshotPath` nil.
    /// - Volatile original (snapshot written as fallback): both set.
    static func imagePlaceholderText(
        data: Data,
        originalPath: String?,
        snapshotPath: String?
    ) -> String {
        let size = byteFormatter.string(fromByteCount: Int64(data.count))
        let dims: String
        if let (w, h) = imagePixelSize(data) { dims = "\(w)×\(h), \(size)" }
        else { dims = size }

        switch (originalPath, snapshotPath) {
        case let (orig?, snap?):
            return "[image omitted to save context — \(dims) — original: \(orig); snapshot: \(snap), use read_image on snapshot if original is unavailable]"
        case let (orig?, nil):
            return "[image omitted to save context — \(dims) — original: \(orig), use read_image to view again]"
        case let (nil, snap?):
            return "[image omitted to save context — \(dims) — saved to \(snap), use read_image to view again]"
        case (nil, nil):
            return "[image omitted to save context — \(dims)]"
        }
    }

    /// Decide whether a file path (Linux view) or minis:// URL is backed by
    /// persistent session storage under `/var/minis/*`. Paths in those
    /// locations survive shell restarts, session reloads, and cross-day
    /// reopens, so we don't need to write a snapshot copy of the bytes.
    ///
    /// Anything else (`/tmp/*`, absent, unknown prefix) is considered
    /// volatile and must be snapshotted before the original is dropped from
    /// context.
    private static func isPersistentMinisPath(_ path: String) -> Bool {
        // Linux view: bind-mounted persistent directories under /var/minis
        if path.hasPrefix("/var/minis/workspace/") { return true }
        if path.hasPrefix("/var/minis/browser/") { return true }
        if path.hasPrefix("/var/minis/attachments/") { return true }
        if path.hasPrefix("/var/minis/offloads/") { return true }
        // minis:// URL view maps 1:1 to the directories above
        if path.hasPrefix("minis://workspace/") { return true }
        if path.hasPrefix("minis://browser/") { return true }
        if path.hasPrefix("minis://attachments/") { return true }
        if path.hasPrefix("minis://offloads/") { return true }
        return false
    }

    /// Extract the original accessible path of an image produced by a known
    /// tool, so the placeholder can reference it as a semantic hint (and,
    /// when the path is persistent, skip writing a snapshot copy).
    ///
    /// Returns nil when no path is recoverable — the caller must write a
    /// snapshot in that case.
    ///
    /// - `toolName`: the tool that produced the result. May be empty when
    ///   the message was rehydrated from persistence (ChatStore drops the
    ///   name on reload); callers pass the tool_use name they recovered
    ///   from the matching `tool_use` block.
    /// - `input`: the `tool_use` input JSON for this call.
    /// - `content`: the textual `tool_result` body. Used to recover paths
    ///   that only the tool knows at runtime (e.g. browser screenshot URLs
    ///   emitted as `minis_url: minis://...`).
    private static func originalImagePath(
        toolName: String,
        input: [String: Any]?,
        content: String
    ) -> String? {
        // read_image: absolute path is the first positional argument.
        if toolName == "read_image", let path = input?["path"] as? String, !path.isEmpty {
            return path
        }
        // browser_use (any action) appends `minis_url: minis://...` to the
        // result content when it persists a screenshot or fetched file to
        // /var/minis/browser/. Extract that URL.
        if toolName == "browser_use" || toolName.isEmpty {
            if let urlRange = content.range(of: #"minis://\S+"#, options: .regularExpression) {
                return String(content[urlRange])
            }
        }
        return nil
    }

    /// Trim old images from `agentHistory` so at most `kImageContextKeepCount`
    /// images remain in the context sent to the model. Older images are
    /// replaced with a text placeholder.
    ///
    /// Snapshot policy: an image is **not** copied to offloads when its
    /// original path lives under `/var/minis/*` (workspace, browser,
    /// attachments, offloads) — those directories are persistent and the
    /// placeholder's path hint is enough for the model to re-fetch via
    /// `read_image`. Any other origin — volatile (`/tmp/*`), unknown, or a
    /// standalone user attachment with no path at all — is snapshotted
    /// via `offloadContextImage` so inference can continue even if the
    /// original is lost. When a snapshot is taken alongside a volatile
    /// original path, both are surfaced in the placeholder so the model
    /// prefers the semantic original and falls back to the snapshot.
    ///
    /// This is a destructive in-place rewrite of `agentHistory` (same
    /// contract as `offloadContextIfNeeded`). It runs *before* token-based
    /// offloading so image bytes are removed first, reducing the pressure on
    /// the subsequent token-window offload decision.
    func trimOldImagesFromHistory() {
        let keep = Self.kImageContextKeepCount

        // First pass: index tool_use calls by id (for name + input lookup)
        // and count total images across the history. Rehydrated messages
        // lose the name on `.toolResult`, so the index is the canonical
        // source of truth for tool metadata.
        var toolCallById: [String: (name: String, input: [String: Any])] = [:]
        var totalImages = 0
        for msg in agentHistory {
            for part in msg.parts {
                switch part {
                case .toolUse(let id, let name, let input):
                    toolCallById[id] = (name: name, input: input)
                case .toolResult(_, _, _, _, let imgData, _, _, _):
                    if imgData != nil { totalImages += 1 }
                case .imageData:
                    totalImages += 1
                default:
                    break
                }
            }
        }
        guard totalImages > keep else { return }

        // Evict the oldest `totalImages - keep` images so the most recent
        // `keep` remain as real bytes.
        let evictCount = totalImages - keep
        var evicted = 0
        var snapshotsWritten = 0

        for mi in agentHistory.indices {
            if evicted >= evictCount { break }
            let parts = agentHistory[mi].parts
            var newParts = parts
            var mutated = false

            for pi in parts.indices {
                if evicted >= evictCount { break }
                switch parts[pi] {
                case .toolResult(let id, let name, let content, let isError, let imgData, let mime, let pageURL, _):
                    guard let data = imgData else { continue }

                    let call = toolCallById[id]
                    let effectiveName = !name.isEmpty ? name : (call?.name ?? "")
                    let originalPath = Self.originalImagePath(
                        toolName: effectiveName,
                        input: call?.input,
                        content: content
                    )

                    // Decide whether to snapshot: only skip the copy when the
                    // original path is under /var/minis/* (persistent).
                    let snapshotPath: String?
                    if let op = originalPath, Self.isPersistentMinisPath(op) {
                        snapshotPath = nil
                    } else {
                        snapshotPath = offloadContextImage(data, toolId: id, mimeType: mime ?? "image/jpeg")
                        snapshotsWritten += 1
                    }

                    let placeholder = Self.imagePlaceholderText(
                        data: data,
                        originalPath: originalPath,
                        snapshotPath: snapshotPath
                    )
                    let newContent = content.isEmpty ? placeholder : "\(content)\n\n\(placeholder)"
                    newParts[pi] = .toolResult(
                        id: id, name: name, content: newContent,
                        isError: isError, imageData: nil, imageMimeType: nil, pageURL: pageURL
                    )
                    mutated = true
                    evicted += 1

                case .imageData(let data, let mime, _):
                    // Standalone image (user attachment) — no tool id, no
                    // recoverable original path. Always snapshot.
                    let synthId = "img-\(sessionId ?? "s")-\(mi)-\(pi)"
                    let snap = offloadContextImage(data, toolId: synthId, mimeType: mime)
                    snapshotsWritten += 1
                    let placeholder = Self.imagePlaceholderText(
                        data: data, originalPath: nil, snapshotPath: snap
                    )
                    newParts[pi] = .text(placeholder)
                    mutated = true
                    evicted += 1

                default:
                    break
                }
            }

            if mutated {
                agentHistory[mi] = AgentMessage(
                    role: agentHistory[mi].role,
                    parts: newParts,
                    isInterrupted: agentHistory[mi].isInterrupted,
                    reasoningContent: agentHistory[mi].reasoningContent
                )
            }
        }

        if evicted > 0 {
            logger.info("🖼️ Context image trim: evicted \(evicted) old image(s) (snapshots: \(snapshotsWritten)), kept last \(keep) (total was \(totalImages))")
        }
    }

    /// Check if context is approaching the model's window limit and offload old tool content.
    ///
    /// Uses a **subtraction method**: starts from the real `lastContextTokens` reported by the API,
    /// and subtracts each offloaded part's token count (computed via BPETokenizer) until the
    /// context drops to the policy's offload target. This avoids unreliable full-history estimation.
    /// Estimate total agentHistory token count using character-based heuristic.
    /// Uses ~3.5 chars/token for mixed English/CJK/code content (conservative).
    /// Also accounts for image data tokens (~85 tokens per 1KB of base64).
    ///
    /// Estimates against `effectiveAgentHistory()` — the slice that actually
    /// reaches the model — not the raw `agentHistory`. After compact-v2
    /// (90ac63e8), the raw history is preserved while marker overlay trims
    /// what's sent; estimating against the raw log made post-compact usage
    /// look unchanged and fired the "near capacity" prompt before every send.
    func estimateContextTokens(_ caller: String = #function) -> Int {
        var totalChars = 0
        var imageTokens = 0
        let slice = effectiveAgentHistory()
        // DIAG: per-message breakdown so we can see EXACTLY what occupies the
        // post-compact context. Logs every message's role + part summary +
        // token estimate; lets us tell whether bloat comes from the injected
        // summary, pre-anchor warm-up turns, or post-anchor tool results.
        var perMsg: [String] = []
        var biggest: [(kind: String, chars: Int, msgIdx: Int)] = []
        for (idx, msg) in slice.enumerated() {
            var msgChars = 0
            var msgImgTok = 0
            var partTags: [String] = []
            for part in msg.parts {
                var partChars = 0
                var partKind = ""
                switch part {
                case .text(let t):
                    partChars = t.count
                    let isSummary = t.contains("<context-summary>")
                    partKind = isSummary ? "SUMMARY" : "text"
                case .toolUse(_, let name, let input):
                    if let data = try? JSONSerialization.data(withJSONObject: input) {
                        partChars = data.count
                    }
                    partKind = "tu:\(name)"
                case .toolResult(_, _, let content, _, let imgData, _, _, _):
                    partChars = content.count
                    if let imgData = imgData {
                        let it = BPETokenizer.shared.countImageTokens(imgData)
                        imageTokens += it
                        msgImgTok += it
                    }
                    partKind = "tr"
                case .imageData(let data, _, _):
                    let it = BPETokenizer.shared.countImageTokens(data)
                    imageTokens += it
                    msgImgTok += it
                    partKind = "img"
                }
                msgChars += partChars
                totalChars += partChars
                partTags.append("\(partKind)=\(partChars)c")
                if partChars >= 2000 {
                    biggest.append((partKind, partChars, idx))
                }
            }
            let msgTok = Int(Double(msgChars) / 3.5) + msgImgTok
            let dbId = msg.dbMessageId?.prefix(8) ?? "----"
            perMsg.append("[\(idx) \(msg.role.rawValue) db=\(dbId) ~\(msgTok)tok | \(partTags.joined(separator: ","))]")
        }
        let totalTokens = Int(Double(totalChars) / 3.5) + imageTokens
        biggest.sort { $0.chars > $1.chars }
        let top = biggest.prefix(5).map { "[\($0.msgIdx)\($0.kind)=\($0.chars)c]" }.joined(separator: " ")
        logger.info("[CompactDiag] estimate caller=\(caller) slice=\(slice.count) total=\(totalTokens)tok (\(totalChars)chars+\(imageTokens)imgTok) bigParts(≥2kc): \(top)")
        logger.info("[CompactDiag] estimate perMsg: \(perMsg.joined(separator: " "))")
        return totalTokens
    }

    /// - `force`: When true, skip the threshold check and offload all eligible candidates
    ///   regardless of current context usage. Used after compaction to unconditionally slim down
    ///   the kept messages.
    func offloadContextIfNeeded(model: LLMModel, lastContextTokens: Int, force: Bool = false) {
        let contextWindow = effectiveContextWindow(for: model)
        let policy = ContextPolicy(contextWindow: contextWindow)

        let hardOffloadChars = 32_768
        func isForceOffloadContent(_ content: String) -> Bool {
            content.count >= hardOffloadChars && !Self.isOffloadReadback(content)
        }
        let hasBomb = agentHistory.contains { msg in
            msg.parts.contains { part in
                switch part {
                case .toolResult(_, _, let content, _, _, _, _, _):
                    return isForceOffloadContent(content)
                case .text(let t):
                    return isForceOffloadContent(t)
                default:
                    return false
                }
            }
        }

        // Policy disables offloading for this context window tier
        if !force && policy.offloadThreshold == 0 && !hasBomb {
            logger.debug("Context offload: disabled for window \(contextWindow) tokens")
            return
        }

        // When no API baseline exists (first iteration of a new message),
        // use a fast char-based estimate to avoid sending an oversized prompt.
        var effectiveTokens = lastContextTokens
        if lastContextTokens == 0 {
            effectiveTokens = estimateContextTokens()
            if !force && !hasBomb {
                if effectiveTokens < policy.offloadThreshold {
                    let remaining = contextWindow - effectiveTokens
                    logger.debug("Context offload: skipped (estimate \(effectiveTokens)/\(contextWindow), remaining ~\(remaining) tokens)")
                    return
                }
            }
            logger.info("Context offload: no API baseline — using char estimate: ~\(effectiveTokens) tokens")
        }

        let pct = Int(Double(effectiveTokens) / Double(contextWindow) * 100)

        if !force && !hasBomb {
            guard effectiveTokens >= policy.offloadThreshold else {
                let remaining = contextWindow - effectiveTokens
                logger.debug("Context offload: below threshold — \(effectiveTokens)/\(contextWindow) (\(pct)%), remaining ~\(remaining) tokens")
                return
            }
        }

        // When forced (post-compact), offload everything eligible — no early stop target.
        let targetTokens = force ? 0 : policy.offloadTarget
        let beforeTokens = effectiveTokens
        var currentTokens = effectiveTokens

        let source = force ? "post-compact" : (lastContextTokens > 0 ? "API" : "estimate")
        let remaining = contextWindow - beforeTokens
        logger.info("━━━ Context Offload Triggered ━━━")
        logger.info("  Model: \(model.id) (window: \(contextWindow) tokens)")
        logger.info("  Before: \(beforeTokens) tokens (\(pct)% of window, ~\(remaining) remaining) [source: \(source)]")
        if force {
            logger.info("  Mode: FORCE (post-compact) — offloading all eligible candidates")
        } else {
            logger.info("  Threshold: \(policy.offloadThreshold) — Target: \(targetTokens)")
            logger.info("  Need to free: ~\(beforeTokens - targetTokens) tokens")
        }
        logger.info("  Agent history: \(self.agentHistory.count) messages")

        // Protect the last 4 messages from offloading, except GH#352 bombs.
        let protectedCount = min(4, agentHistory.count)
        let candidateUpper = agentHistory.count - protectedCount
        logger.info("  Scanning messages 0..<\(candidateUpper) (last \(protectedCount) protected; bombs still eligible)")

        // Collect offload candidates: (messageIndex, partIndex, tokenCount, byteCount)
        struct OffloadCandidate {
            let msgIdx: Int
            let partIdx: Int
            let tokens: Int
            let bytes: Int
            let toolId: String
            let toolName: String
        }

        var candidates: [OffloadCandidate] = []
        var skippedAlreadyOffloaded = 0
        var skippedTooSmall = 0

        for msgIdx in agentHistory.indices {
            let inProtectedTail = msgIdx >= candidateUpper
            let msg = agentHistory[msgIdx]
            for (partIdx, part) in msg.parts.enumerated() {
                switch part {
                case .toolResult(let id, let name, let content, _, let imgData, _, _, _):
                    // Skip already-offloaded parts, including file_read-prefixed stubs.
                    if Self.isOffloadReadback(content) {
                        skippedAlreadyOffloaded += 1
                        continue
                    }
                    if inProtectedTail && !isForceOffloadContent(content) { continue }
                    // Only offload substantial content (>500 chars) or large images (>1KB)
                    let hasLargeContent = content.count > 500
                    let hasLargeImage = (imgData?.count ?? 0) > 1024
                    guard hasLargeContent || hasLargeImage else {
                        skippedTooSmall += 1
                        continue
                    }

                    let tokens = BPETokenizer.shared.countPartTokens(part)
                    let bytes = content.utf8.count + (imgData?.count ?? 0)
                    candidates.append(OffloadCandidate(msgIdx: msgIdx, partIdx: partIdx, tokens: tokens, bytes: bytes, toolId: id, toolName: name))

                case .toolUse(let id, let name, let input):
                    if inProtectedTail { continue }
                    // Offload large file_write/file_edit tool inputs
                    guard name == "file_write" || name == "file_edit" else { continue }
                    if let content = input["content"] as? String, content.count > 500 {
                        let tokens = BPETokenizer.shared.countPartTokens(part)
                        let bytes = content.utf8.count
                        candidates.append(OffloadCandidate(msgIdx: msgIdx, partIdx: partIdx, tokens: tokens, bytes: bytes, toolId: id, toolName: name))
                    }

                case .imageData(let data, _, _):
                    if inProtectedTail { continue }
                    guard data.count > 1024 else {
                        skippedTooSmall += 1
                        continue
                    }
                    let tokens = BPETokenizer.shared.countPartTokens(part)
                    candidates.append(OffloadCandidate(msgIdx: msgIdx, partIdx: partIdx, tokens: tokens, bytes: data.count, toolId: "img\(msgIdx)_\(partIdx)", toolName: "image"))

                case .text(let t):
                    guard isForceOffloadContent(t) else { continue }
                    let tokens = BPETokenizer.shared.countPartTokens(part)
                    candidates.append(OffloadCandidate(msgIdx: msgIdx, partIdx: partIdx, tokens: tokens, bytes: t.utf8.count, toolId: "txt\(msgIdx)_\(partIdx)", toolName: "text"))
                }
            }
        }

        // Sort by token count descending (offload largest first)
        candidates.sort { $0.tokens > $1.tokens }

        let totalCandidateTokens = candidates.reduce(0) { $0 + $1.tokens }
        logger.info("  Candidates: \(candidates.count) parts (~\(totalCandidateTokens) tokens total)")
        logger.info("  Skipped: \(skippedAlreadyOffloaded) already offloaded, \(skippedTooSmall) too small")
        if !candidates.isEmpty {
            let top = candidates.prefix(5)
            for (i, c) in top.enumerated() {
                logger.info("  Candidate #\(i + 1): [\(c.toolName)] id:\(c.toolId.prefix(8)) msg[\(c.msgIdx)] ~\(c.tokens) tokens (\(c.bytes) bytes)")
            }
            if candidates.count > 5 {
                logger.info("  ... and \(candidates.count - 5) more candidates")
            }
        }

        var offloadedCount = 0
        var freedTokens = 0

        for candidate in candidates {
            let part = agentHistory[candidate.msgIdx].parts[candidate.partIdx]
            let isBomb: Bool = {
                switch part {
                case .toolResult(_, _, let content, _, _, _, _, _): return isForceOffloadContent(content)
                case .text(let t): return isForceOffloadContent(t)
                default: return false
                }
            }()
            if !isBomb && currentTokens <= targetTokens { break }
            var linuxPath = ""

            switch part {
            case .toolResult(let id, let name, let content, let isError, let imgData, let imgMime, _, _):
                // Offload text content
                if content.count > 500 {
                    linuxPath = offloadContextContent(content, toolId: id, toolName: name)
                }
                // Offload image data
                if let imgData = imgData, imgData.count > 1024 {
                    let imgPath = offloadContextImage(imgData, toolId: id, mimeType: imgMime ?? "image/png")
                    if linuxPath.isEmpty { linuxPath = imgPath }
                }

                let stub = "[CONTEXT OFFLOADED] Content (~\(candidate.tokens) tokens, \(candidate.bytes) bytes) saved to: \(linuxPath)\nUse file_read tool to retrieve if needed."
                agentHistory[candidate.msgIdx].parts[candidate.partIdx] = .toolResult(
                    id: id, name: name, content: stub, isError: isError
                )

            case .toolUse(let id, let name, let input):
                if let content = input["content"] as? String {
                    linuxPath = offloadContextContent(content, toolId: id, toolName: name)
                }
                var newInput = input
                newInput["content"] = "[CONTEXT OFFLOADED] Content (~\(candidate.tokens) tokens, \(candidate.bytes) bytes) saved to: \(linuxPath)\nUse file_read tool to retrieve if needed."
                agentHistory[candidate.msgIdx].parts[candidate.partIdx] = .toolUse(
                    id: id, name: name, input: newInput
                )

            case .imageData(let data, let mime, _):
                linuxPath = offloadContextImage(data, toolId: candidate.toolId, mimeType: mime)
                let stub = "[CONTEXT OFFLOADED] Image (~\(candidate.tokens) tokens, \(candidate.bytes) bytes) saved to: \(linuxPath)\nUse file_read tool to retrieve if needed."
                agentHistory[candidate.msgIdx].parts[candidate.partIdx] = .text(stub)

            case .text(let t):
                linuxPath = offloadContextContent(t, toolId: candidate.toolId, toolName: "text")
                let stub = "[CONTEXT OFFLOADED] Content (~\(candidate.tokens) tokens, \(candidate.bytes) bytes) saved to: \(linuxPath)\nUse file_read tool to retrieve if needed."
                agentHistory[candidate.msgIdx].parts[candidate.partIdx] = .text(stub)
            }

            currentTokens -= candidate.tokens
            freedTokens += candidate.tokens
            offloadedCount += 1
            let afterPct = Int(Double(currentTokens) / Double(contextWindow) * 100)
            logger.info("  ✂ Offloaded #\(offloadedCount): [\(candidate.toolName)] id:\(candidate.toolId.prefix(8)) ~\(candidate.tokens) tokens (\(candidate.bytes) bytes) → \(linuxPath) [now \(currentTokens) (\(afterPct)%)]")
        }

        if offloadedCount > 0 {
            let afterPct = Int(Double(currentTokens) / Double(contextWindow) * 100)
            logger.info("━━━ Context Offload Complete ━━━")
            logger.info("  Parts offloaded: \(offloadedCount)")
            logger.info("  Tokens freed: ~\(freedTokens)")
            logger.info("  Before: \(beforeTokens)/\(contextWindow) (\(pct)%)")
            logger.info("  After:  \(currentTokens)/\(contextWindow) (\(afterPct)%)")
            logger.info("  Delta:  -\(beforeTokens - currentTokens) tokens (-\(pct - afterPct)pp)")

            // List files in offloads/tools directory (top 20 by recency)
            let sid = sessionId ?? "unknown"
            let toolsDir = Self.minisOffloadsPersistentDir(for: sid)
                .appendingPathComponent("tools", isDirectory: true)
            if let files = try? FileManager.default.contentsOfDirectory(
                at: toolsDir, includingPropertiesForKeys: [.fileSizeKey, .creationDateKey],
                options: [.skipsHiddenFiles]
            ) {
                let sorted = files.sorted { a, b in
                    let da = (try? a.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
                    let db = (try? b.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast
                    return da > db
                }
                let top20 = sorted.prefix(20)
                var totalSize: Int64 = 0
                logger.info("  /var/minis/offloads/tools/ — \(files.count) files (showing top \(top20.count)):")
                for file in top20 {
                    let size = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    totalSize += Int64(size)
                    let sizeStr: String
                    if size >= 1_048_576 {
                        sizeStr = String(format: "%.1fMB", Double(size) / 1_048_576)
                    } else if size >= 1024 {
                        sizeStr = String(format: "%.1fKB", Double(size) / 1024)
                    } else {
                        sizeStr = "\(size)B"
                    }
                    logger.info("    \(file.lastPathComponent) (\(sizeStr))")
                }
                let totalStr: String
                if totalSize >= 1_048_576 {
                    totalStr = String(format: "%.1fMB", Double(totalSize) / 1_048_576)
                } else {
                    totalStr = String(format: "%.1fKB", Double(totalSize) / 1024)
                }
                logger.info("  Total offload size: \(totalStr) across \(files.count) files")
            }
            logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
    }

    /// Remove persistent minis data for a given session.
    private func cleanupMinis(for sid: String) {
        let fm = FileManager.default
        let persistDir = Self.minisPersistentBase.appendingPathComponent(sid, isDirectory: true)
        try? fm.removeItem(at: persistDir)
    }

    /// Sync a single minis subdirectory bidirectionally between persistent storage and iSH-visible path.
    /// One-time migration: moves old `Library/MinisChat/offloads/<sid>/` to
    /// `Library/MinisChat/minis/<sid>/offloads/`.
    func migrateOffloadsIfNeeded(for sid: String) {
        let fm = FileManager.default
        let lib = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let oldDir = lib.appendingPathComponent("MinisChat/offloads/\(sid)", isDirectory: true)
        guard fm.fileExists(atPath: oldDir.path) else { return }

        let newDir = Self.minisOffloadsPersistentDir(for: sid)
        try? fm.createDirectory(at: newDir, withIntermediateDirectories: true)

        // Move each file from old location to new
        if let files = try? fm.contentsOfDirectory(atPath: oldDir.path) {
            for file in files {
                let src = oldDir.appendingPathComponent(file)
                let dst = newDir.appendingPathComponent(file)
                try? fm.moveItem(at: src, to: dst)
            }
        }

        // Remove old directory
        try? fm.removeItem(at: oldDir)
        logger.info("Migrated offloads for session \(sid) to new minis path")
    }

    /// Bind-mount a session's minis directories into iSH-visible /var/minis/.
    /// Delegates to ISHExecutionCoordinator for serialized mount management.
    func mountMinis(for sid: String) {
        // Always ensure symlinks exist in data/ for the file browser,
        // even when the iSH kernel isn't booted yet.
        ensureMinisSymlinks(for: sid)
        Task { await ISHExecutionCoordinator.shared.mountForSession(sid) }
    }

    /// [T-ish-container-identity GH#99] One-shot per launch: detect an iOS
    /// data-container migration (the UUID rotates on EVERY app update /
    /// reinstall; contents migrate). All /var/minis symlinks carry absolute
    /// paths from the previous container, so right after an update — before
    /// the first session mounts and re-points them — the shell sees dangling
    /// links / empty dirs, which users read as data loss. Log the migration
    /// explicitly so that window is diagnosable from the log alone.
    private static var containerMigrationChecked = false
    private static func noteContainerMigrationIfNeeded() {
        guard !containerMigrationChecked else { return }
        containerMigrationChecked = true
        let current = URL(fileURLWithPath: NSHomeDirectory()).lastPathComponent
        let key = "lastKnownDataContainerUUID"
        let previous = UserDefaults.standard.string(forKey: key)
        if let previous, previous != current {
            AppLogger(category: "MinisSymlink").warning("[MinisSymlink] Data container migrated \(previous.prefix(8))… → \(current.prefix(8))… (normal after an app update; contents were migrated by iOS). Session symlinks will be re-pointed as sessions mount.")
        }
        if previous != current {
            UserDefaults.standard.set(current, forKey: key)
        }
    }

    /// Create symlinks in the fakefs data/ directory so the file browser
    /// can access minis directories without requiring the iSH kernel to be booted.
    private func ensureMinisSymlinks(for sid: String) {
        let fm = FileManager.default
        let dataPath = RootfsManager.shared.dataPath
        Self.noteContainerMigrationIfNeeded()

        // Ensure /var/minis/ exists in data/
        let minisDataDir = dataPath.appendingPathComponent("var/minis")
        try? fm.createDirectory(at: minisDataDir, withIntermediateDirectories: true)

        let mappings: [(persistDir: URL, linuxDir: String)] = [
            (Self.minisOffloadsPersistentDir(for: sid), Self.minisOffloadsLinuxDir),
            (Self.minisAttachmentsPersistentDir(for: sid), Self.minisAttachmentsLinuxDir),
            (Self.minisWorkspacePersistentDir(for: sid), Self.minisWorkspaceLinuxDir),
            (Self.minisBrowserPersistentDir(for: sid), Self.minisBrowserLinuxDir),
            (Self.minisMemoryPersistentDir, Self.minisMemoryLinuxDir),
            (Self.minisSkillsPersistentDir, Self.minisSkillsLinuxDir),
            (Self.minisSharedPersistentDir, Self.minisSharedLinuxDir),
            (Self.minisMcpServersPersistentDir, Self.minisMcpServersLinuxDir),
        ]

        for (persistDir, linuxDir) in mappings {
            try? fm.createDirectory(at: persistDir, withIntermediateDirectories: true)
            let hostPath = dataPath.appendingPathComponent(String(linuxDir.dropFirst()))

            var lstatBuf = stat()
            let exists = lstat(hostPath.path, &lstatBuf) == 0

            if exists && (lstatBuf.st_mode & S_IFMT) == S_IFLNK {
                // Already a symlink — check if target is correct.
                // Use resolvingSymlinksInPath on both sides to normalize aliases
                // (e.g. GroupContainersAlias vs "Group Containers" on iOS).
                let resolved = hostPath.resolvingSymlinksInPath().standardized.path
                let expected = persistDir.resolvingSymlinksInPath().standardized.path
                if resolved == expected { continue }
                logger.info("[MinisSymlink] \(linuxDir): stale symlink (-> \(resolved), want \(expected)), replacing")
                unlink(hostPath.path)
            } else if exists && (lstatBuf.st_mode & S_IFMT) == S_IFDIR {
                // Real directory found where a symlink should be.
                // Migrate any existing content to the persistent directory,
                // then remove the real directory so we can create a symlink.
                // This handles the case where the directory was created before
                // the persistent storage location changed (e.g. shared -> App Group).
                var moved = 0, skipped = 0
                if let contents = try? fm.contentsOfDirectory(at: hostPath, includingPropertiesForKeys: nil) {
                    for item in contents {
                        let dest = persistDir.appendingPathComponent(item.lastPathComponent)
                        if !fm.fileExists(atPath: dest.path) {
                            do {
                                try fm.moveItem(at: item, to: dest)
                                moved += 1
                            } catch {
                                logger.error("[MinisSymlink] \(linuxDir): migrate FAILED for \(item.lastPathComponent): \(error.localizedDescription)")
                            }
                        } else {
                            skipped += 1
                        }
                    }
                }
                logger.info("[MinisSymlink] \(linuxDir): real dir -> persistDir migration, moved=\(moved) skipped(existing)=\(skipped)")
                try? fm.removeItem(at: hostPath)
            } else if exists {
                // Regular file — unexpected, remove
                logger.info("[MinisSymlink] \(linuxDir): unexpected regular file at \(hostPath.path), removing")
                unlink(hostPath.path)
            }

            // Create symlink
            do {
                try fm.createSymbolicLink(at: hostPath, withDestinationURL: persistDir)
                logger.info("[MinisSymlink] \(linuxDir): symlink created -> \(persistDir.path)")
            } catch {
                logger.error("[MinisSymlink] \(linuxDir): createSymbolicLink FAILED: \(error.localizedDescription)")
            }
        }

        // Ensure user-mounted external folders are symlinked too.
        Self.refreshMountedFolderSymlinks()
    }

    /// Create/refresh `/var/minis/mounts/<name>` symlinks for every entry in
    /// `MountedFoldersManager`. Removes stale symlinks whose mounts were deleted.
    ///
    /// Called from:
    ///   - `ensureMinisSymlinks` on each session mount
    ///   - `MountedFoldersManager` after add/rename/remove
    ///   - App launch after `activateAll`
    ///
    /// Safe to call before the iSH kernel is booted — writes only into `data/`.
    ///
    /// **Must not do filesystem work on the main thread.** A mount's resolved
    /// `target` can be a network- or FileProvider-backed volume (SMB share,
    /// iCloud Drive vault). Any path-resolving call on such a URL —
    /// `resolvingSymlinksInPath()`, `appendingPathComponent` on a `file:` URL,
    /// `resourceValues(forKeys:)` — becomes a `getattrlist(2)` that traps into
    /// a synchronous XPC round-trip to the owning provider. When the volume is
    /// slow or unreachable that parks the calling thread indefinitely; doing it
    /// on the main thread from the launch `.onAppear` tripped the 10s
    /// `scene-update` watchdog and iOS SIGKILLed the app (0x8BADF00D).
    /// So: snapshot the manager state on the main actor (cheap dictionary
    /// reads only), then do every filesystem touch on a detached task.
    nonisolated static func refreshMountedFolderSymlinks() {
        if Thread.isMainThread {
            let snapshot = MainActor.assumeIsolated { mountedFolderSymlinkSnapshot() }
            Task.detached(priority: .utility) {
                performMountedFolderSymlinkRefresh(snapshot: snapshot)
            }
        } else {
            Task.detached(priority: .utility) {
                let snapshot = await MainActor.run { mountedFolderSymlinkSnapshot() }
                performMountedFolderSymlinkRefresh(snapshot: snapshot)
            }
        }
    }

    /// One mount's desired symlink state, captured on the main actor.
    /// `target` is nil for entries that failed activation (stale bookmark,
    /// permission denied, contents unavailable) — those get their leftover
    /// symlink removed so FileBrowserView doesn't show a broken entry.
    struct MountedFolderSymlinkSpec: Sendable {
        let name: String
        let targetPath: String?
    }

    /// Cheap main-actor snapshot: reads `entries` and the `activeURLs`
    /// dictionary. Deliberately does **no** filesystem I/O — `url.path` is a
    /// pure string accessor, unlike `resolvingSymlinksInPath()`.
    @MainActor
    private static func mountedFolderSymlinkSnapshot() -> [MountedFolderSymlinkSpec] {
        let manager = MountedFoldersManager.shared
        return manager.entries.map { entry in
            MountedFolderSymlinkSpec(
                name: entry.name,
                targetPath: manager.resolvedURL(for: entry.id)?.path
            )
        }
    }

    /// Reconcile `/var/minis/mounts/<name>` against `snapshot`. Runs entirely
    /// off the main thread — see `refreshMountedFolderSymlinks` for why.
    nonisolated private static func performMountedFolderSymlinkRefresh(
        snapshot: [MountedFolderSymlinkSpec]
    ) {
        let fm = FileManager.default
        let dataPath = RootfsManager.shared.dataPath
        let mountsDir = dataPath.appendingPathComponent("var/minis/mounts", isDirectory: true)
        try? fm.createDirectory(at: mountsDir, withIntermediateDirectories: true)

        let desiredNames = Set(snapshot.map(\.name))

        // 1. Remove symlinks for mounts that no longer exist.
        if let existing = try? fm.contentsOfDirectory(atPath: mountsDir.path) {
            for entry in existing where !desiredNames.contains(entry) {
                let stalePath = mountsDir.appendingPathComponent(entry)
                var buf = stat()
                if lstat(stalePath.path, &buf) == 0,
                   (buf.st_mode & S_IFMT) == S_IFLNK {
                    unlink(stalePath.path)
                }
            }
        }

        // 2. Create or fix symlinks for active mounts.
        for spec in snapshot {
            let link = mountsDir.appendingPathComponent(spec.name)
            guard let targetPath = spec.targetPath else {
                // Inactive (stale/denied) — remove any leftover symlink so the
                // user doesn't see a broken entry in FileBrowserView.
                var buf = stat()
                if lstat(link.path, &buf) == 0 { unlink(link.path) }
                continue
            }

            var buf = stat()
            let exists = lstat(link.path, &buf) == 0
            if exists && (buf.st_mode & S_IFMT) == S_IFLNK {
                // Compare the symlink's *stored* destination rather than
                // resolving both sides. `destinationOfSymbolicLink` is a plain
                // readlink(2) against the local fakefs — it never touches the
                // mount's backing volume, so an unreachable SMB/FileProvider
                // target can't stall us here. The previous
                // `resolvingSymlinksInPath()` comparison on both sides was the
                // watchdog-kill site. We write the link with `target.path`
                // below, so a byte-equal destination means it's already correct.
                let current = try? fm.destinationOfSymbolicLink(atPath: link.path)
                if current == targetPath { continue }
                unlink(link.path)
            } else if exists {
                // Non-symlink in the way — unexpected, remove.
                unlink(link.path)
            }
            try? fm.createSymbolicLink(
                atPath: link.path,
                withDestinationPath: targetPath
            )
        }
    }

    /// Save an attachment (image, media) to the session's persistent attachments directory.
    /// With bind mounts, writing to persistent storage is automatically visible to iSH.
    /// Returns a `minis://attachments/<filename>` URL string, or nil on failure.
    func saveAttachment(_ data: Data, filename: String) -> String? {
        let fm = FileManager.default
        let sid = sessionId ?? "unknown"

        // Write to persistent storage (bind-mounted, so iSH sees it automatically)
        let persistDir = Self.minisAttachmentsPersistentDir(for: sid)
        try? fm.createDirectory(at: persistDir, withIntermediateDirectories: true)

        let persistPath = persistDir.appendingPathComponent(filename)
        guard (try? data.write(to: persistPath)) != nil else { return nil }

        let encoded = filename.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? filename
        return "minis://attachments/\(encoded)"
    }

    /// [GH#352] Same hole as Android: a 1MB PNG inlined as text/base64 is ~960k
    /// tokens, lives in the protected last-4, and char/3.5 never trips offload.
    func scrubInlineMediaFromHistory() {
        let attach = activeModelHasNativeVision
        for mi in agentHistory.indices {
            var changed = false
            var newParts: [AgentContentPart] = []
            newParts.reserveCapacity(agentHistory[mi].parts.count)
            for part in agentHistory[mi].parts {
                switch part {
                case .toolResult(let id, let name, let content, let isError, let imgData, let imgMime, let pageURL, let imgPath):
                    let rewritten = materializeToolResult(
                        id: id, name: name, content: content, isError: isError,
                        imageData: imgData, imageMimeType: imgMime, pageURL: pageURL,
                        imageLinuxPath: imgPath, attachImage: attach
                    )
                    if case .toolResult(_, _, let newContent, _, _, _, _, _) = rewritten, newContent != content {
                        changed = true
                    }
                    newParts.append(rewritten)
                case .text(let t):
                    let scrubbed = InlineMediaScrubber.scrub(t)
                    if !scrubbed.changed {
                        newParts.append(part)
                    } else {
                        changed = true
                        newParts.append(.text(scrubbed.text))
                        if attach {
                            for (idx, img) in scrubbed.images.enumerated() {
                                let path = offloadContextImage(img.bytes, toolId: "txt\(mi)_\(idx)", mimeType: img.mimeType)
                                newParts.append(.imageData(data: img.bytes, mimeType: img.mimeType, linuxPath: path.isEmpty ? nil : path))
                            }
                        }
                    }
                default:
                    newParts.append(part)
                }
            }
            if changed {
                agentHistory[mi].parts = newParts
                logger.info("scrubInlineMedia: history[\(mi)] stripped inline base64")
            }
        }
    }

    private func materializeToolResult(
        id: String, name: String, content: String, isError: Bool,
        imageData: Data?, imageMimeType: String?, pageURL: String?,
        imageLinuxPath: String?, attachImage: Bool
    ) -> AgentContentPart {
        if Self.isOffloadReadback(content) {
            return .toolResult(id: id, name: name, content: content, isError: isError, imageData: imageData, imageMimeType: imageMimeType, pageURL: pageURL, imageLinuxPath: imageLinuxPath)
        }
        let scrubbed = InlineMediaScrubber.scrub(content)
        guard scrubbed.changed else {
            return .toolResult(id: id, name: name, content: content, isError: isError, imageData: imageData, imageMimeType: imageMimeType, pageURL: pageURL, imageLinuxPath: imageLinuxPath)
        }
        var nextImage = imageData
        var nextMime = imageMimeType
        var nextPath = imageLinuxPath
        if let first = scrubbed.images.first {
            let path = offloadContextImage(first.bytes, toolId: id, mimeType: first.mimeType)
            if !path.isEmpty { nextPath = path }
            if attachImage && nextImage == nil {
                nextImage = first.bytes
                nextMime = first.mimeType
            }
        } else {
            _ = offloadContextContent(content, toolId: id, toolName: name)
        }
        let note: String
        if let nextPath, !nextPath.isEmpty {
            note = scrubbed.text + "\n[saved to \(nextPath) — use read_image to view]"
        } else {
            note = scrubbed.text
        }
        return .toolResult(id: id, name: name, content: note, isError: isError, imageData: nextImage, imageMimeType: nextMime, pageURL: pageURL, imageLinuxPath: nextPath)
    }

}

// MARK: - [GH#352] Inline base64 / data-URI stripper (Android InlineMediaScrubber parity)

enum InlineMediaScrubber {
    static let minDataURIChars = 512
    static let minRawBlobChars = 8_192

    struct ExtractedImage {
        let bytes: Data
        let mimeType: String
    }

    struct Result {
        let text: String
        let images: [ExtractedImage]
        let strippedChars: Int
        var changed: Bool { strippedChars > 0 }
    }

    static func scrub(_ text: String) -> Result {
        let hits = findHits(text)
        guard !hits.isEmpty else { return Result(text: text, images: [], strippedChars: 0) }
        var images: [ExtractedImage] = []
        var out = ""
        out.reserveCapacity(min(text.count, 8_192))
        var last = text.startIndex
        var stripped = 0
        for hit in hits {
            out += text[last..<hit.start]
            let compact = String(hit.payload.filter { !$0.isWhitespace })
            let decoded = Data(base64Encoded: compact)
            let sniffed = decoded.flatMap { sniffImageMime($0) }
            let mime = sniffed ?? hit.mimeHint.flatMap { $0.hasPrefix("image/") ? $0 : nil }
            if let decoded, let mime {
                images.append(ExtractedImage(bytes: decoded, mimeType: mime))
                out += "[inline image extracted | \(mime) | \(decoded.count) bytes]"
            } else if let decoded {
                out += "[inline binary extracted | \(decoded.count) bytes]"
            } else {
                out += "[inline base64 omitted | \(text.distance(from: hit.start, to: hit.end)) chars]"
            }
            stripped += text.distance(from: hit.start, to: hit.end)
            last = hit.end
        }
        out += text[last...]
        return Result(text: out, images: images, strippedChars: stripped)
    }

    private struct Hit {
        let start: String.Index
        let end: String.Index
        let payload: String
        let mimeHint: String?
    }

    private static func findHits(_ text: String) -> [Hit] {
        guard text.count >= minDataURIChars else { return [] }
        var hits: [Hit] = []
        var search = text.startIndex
        while search < text.endIndex, let range = text.range(of: "data:", range: search..<text.endIndex) {
            if let parsed = parseDataURI(text, at: range.lowerBound) {
                hits.append(parsed)
                search = parsed.end
            } else {
                search = text.index(range.lowerBound, offsetBy: 5, limitedBy: text.endIndex) ?? text.endIndex
            }
        }
        search = text.startIndex
        while search < text.endIndex, let magic = indexOfMagicPrefix(text, from: search) {
            if hits.contains(where: { magic >= $0.start && magic < $0.end }) {
                search = text.index(after: magic)
                continue
            }
            let end = consumeBase64(text, from: magic)
            if text.distance(from: magic, to: end) >= minRawBlobChars {
                hits.append(Hit(start: magic, end: end, payload: String(text[magic..<end]), mimeHint: nil))
                search = end
            } else {
                search = text.index(after: magic)
            }
        }
        hits.sort { $0.start < $1.start }
        return hits
    }

    private static func parseDataURI(_ text: String, at: String.Index) -> Hit? {
        guard text[at...].hasPrefix("data:") else { return nil }
        let afterData = text.index(at, offsetBy: 5)
        guard let comma = text[afterData...].firstIndex(of: ","),
              text.distance(from: at, to: comma) <= 128 else { return nil }
        let header = String(text[afterData..<comma])
        guard let mark = header.range(of: ";base64") else { return nil }
        let mime = String(header[..<mark.lowerBound])
        let payloadStart = text.index(after: comma)
        let payloadEnd = consumeBase64(text, from: payloadStart)
        guard text.distance(from: at, to: payloadEnd) >= minDataURIChars else { return nil }
        return Hit(start: at, end: payloadEnd, payload: String(text[payloadStart..<payloadEnd]), mimeHint: mime.isEmpty ? nil : mime)
    }

    private static func indexOfMagicPrefix(_ text: String, from: String.Index) -> String.Index? {
        let keys = ["iVBORw0KGgo", "/9j/", "R0lGOD", "UklGR"]
        var best: String.Index?
        for key in keys {
            if let r = text.range(of: key, range: from..<text.endIndex) {
                if best == nil || r.lowerBound < best! { best = r.lowerBound }
            }
        }
        return best
    }

    private static func consumeBase64(_ text: String, from: String.Index) -> String.Index {
        var i = from
        while i < text.endIndex {
            let c = text[i]
            if c.isLetter || c.isNumber || c == "+" || c == "/" || c == "=" || c == "\n" || c == "\r" {
                i = text.index(after: i)
            } else {
                break
            }
        }
        return i
    }

    static func sniffImageMime(_ bytes: Data) -> String? {
        let b = [UInt8](bytes.prefix(12))
        if b.count >= 8 && b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
            && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A { return "image/png" }
        if b.count >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF { return "image/jpeg" }
        if b.count >= 6 && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38 { return "image/gif" }
        if b.count >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
            && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50 { return "image/webp" }
        return nil
    }
}
