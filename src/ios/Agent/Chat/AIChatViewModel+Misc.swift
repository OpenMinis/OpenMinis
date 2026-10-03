import Foundation
import UIKit

private let logger = AppLogger(category: "AIChatVM")

// MARK: - Kernel Boot + Prompt Queue + Minis paths + Dynamic Max Tokens

extension AIChatViewModel {

    // MARK: - Kernel Boot

    func ensureKernelBooted() {
        guard kernelStatus == .notBooted else { return }
        kernelStatus = .booting

        Task {
            let bootStart = CFAbsoluteTimeGetCurrent()
            do {
                if !ISHKernel.shared.isBooted {
                    let installStart = CFAbsoluteTimeGetCurrent()
                    try RootfsManager.shared.installIfNeeded()
                    let installElapsed = (CFAbsoluteTimeGetCurrent() - installStart) * 1000
                    logger.info("[KernelBoot] installIfNeeded: \(String(format: "%.1f", installElapsed))ms")

                    let kernelStart = CFAbsoluteTimeGetCurrent()
                    let rootPath = RootfsManager.shared.rootfsPath.path
                    let err = ISHKernel.shared.boot(withRootPath: rootPath)
                    let kernelElapsed = (CFAbsoluteTimeGetCurrent() - kernelStart) * 1000
                    logger.info("[KernelBoot] kernel boot call: \(String(format: "%.1f", kernelElapsed))ms")
                    if err < 0 {
                        kernelStatus = .failed("Kernel boot failed: \(err)")
                        return
                    }
                    // Wire fakefs change events into the iCloud Sync v2
                    // SessionFile dirty pipeline. Must be done after boot
                    // (the C-side dispatch source is created by this call)
                    // and before any bind mount, so the first realfs op
                    // already has a consumer registered.
                    installSessionFileChangeTracker(kernel: ISHKernel.shared)

                    // Install per-session path-translate hook. Must run
                    // before any session task is spawned so the first
                    // /var/minis/* access already routes correctly.
                    MinisFsRouter.shared.installHook()
                }
                RootfsManager.shared.applyDefaultMountOverlay()
                Task { @MainActor in MirrorSpeedTestViewModel.shared.autoDetectOnceIfNeeded() }
                kernelStatus = .booted
                let totalElapsed = (CFAbsoluteTimeGetCurrent() - bootStart) * 1000
                logger.info("[KernelBoot] TOTAL: \(String(format: "%.1f", totalElapsed))ms")
            } catch {
                kernelStatus = .failed(error.localizedDescription)
                logger.error("[KernelBoot] error: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Prompt Queue

    /// Queue the current input text as a prompt to be injected into the running agent loop.
    /// The message immediately appears in the chat with a dashed border (isQueued=true).
    /// - Parameter silent: [T-p0-programmatic-prompt] skip the tap haptic; set
    ///   by `submitProgrammaticPrompt` for CLI / Shortcut / job origins.
    /// - Parameter overrideText: [T-programmatic-prompt-no-composer] the prompt
    ///   to queue, for callers that are not the composer. nil (the user's own
    ///   tap) means "take the draft", which is also the only case that clears
    ///   it. A programmatic caller passes its text here so the user's in-flight
    ///   draft is never read, written, or cleared.
    func enqueuePrompt(silent: Bool = false, deferUntilIdle: Bool = false,
                       overrideText: String? = nil) {
        let usingComposer = overrideText == nil
        let text = (overrideText ?? inputText).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !attachments.isEmpty, isProcessing else { return }
        if !silent {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
        let pendingAttachments = attachments
        // [T-paste-live-bubble-card] Same birth-time card plan as send(): the
        // queued bubble keeps the [Pasted#N] literal in its text and is born
        // with the chip-style cards; the drain writes the files to the
        // planned ids.
        let pastePlanned = planPastedCards(for: text)
        var prompt = QueuedPrompt(text: text, attachments: pendingAttachments,
                                  pastePlan: pastePlanned.plan)
        prompt.deferUntilIdle = deferUntilIdle
        promptQueue.append(prompt)
        // Show in chat immediately with queued styling
        let chatMsg = ChatMessage(role: .user, content: text, isQueued: true)
        chatMsg.queuedPromptId = prompt.id
        chatMsg.inputAttachments = pendingAttachments
        chatMsg.attachments = pastePlanned.metas
        messages.append(chatMsg)
        scrollToBottomSignal.send()
        // Only the composer's own send empties the composer. A programmatic
        // prompt carried its text in `overrideText` and must leave the user's
        // draft exactly as they left it.
        if usingComposer { inputText = "" }
        attachments = []
        logger.info("Enqueued prompt (\(text.count)ch, \(pendingAttachments.count) attachments), queue size=\(self.promptQueue.count)")
        // [T-ios-queued-candidate-not-onscreen] Log the queued candidate text
        // (prefix 100) + a queue/messages snapshot so we can later correlate a
        // queued send against whether it ever rendered.
        logger.info("📋[QueueDiag] ENQUEUE candidate id=\(prompt.id.uuidString.prefix(8)) text(prefix100)=\"\(text.prefix(100))\"")
        dumpQueueSnapshot("after-enqueue")
    }

    /// Remove a queued prompt by ID and its corresponding chat message.
    func removeQueuedPrompt(id: UUID) {
        promptQueue.removeAll { $0.id == id }
        messages.removeAll { $0.queuedPromptId == id }
    }

    /// Withdraw a queued message from the chat before it gets injected into the agent loop.
    func withdrawQueuedMessage(_ messageId: UUID) {
        guard let msg = messages.first(where: { $0.id == messageId }), msg.isQueued,
              let promptId = msg.queuedPromptId else { return }
        promptQueue.removeAll { $0.id == promptId }
        messages.removeAll { $0.id == messageId }
        logger.info("Withdrew queued message, queue size=\(self.promptQueue.count)")
    }

    // MARK: - /var/minis/ Shared Directory
    //
    // Unified bidirectional file area between iOS and iSH:
    //   /var/minis/attachments/  — screenshots, images, media
    //   /var/minis/offloads/     — large tool outputs (migrated from /var/offloads/)
    //   /var/minis/workspace/    — general session working area
    //
    // Persistent storage: Library/MinisChat/minis/<sessionId>/{attachments,offloads,workspace}/
    // iSH-visible path:   /var/minis/{attachments,offloads,workspace}/  (session-unaware)
    //
    // On session load/switch, the current session's files are synced into the
    // iSH-visible directory so the model and shell commands always see /var/minis/.

    nonisolated static let minisLinuxBaseDir = "/var/minis"
    nonisolated static let minisAttachmentsLinuxDir = "/var/minis/attachments"
    nonisolated static let minisOffloadsLinuxDir = "/var/minis/offloads"
    nonisolated static let minisWorkspaceLinuxDir = "/var/minis/workspace"
    nonisolated static let minisBrowserLinuxDir = "/var/minis/browser"
    nonisolated static let minisMemoryLinuxDir = "/var/minis/memory"
    nonisolated static let minisSkillsLinuxDir = "/var/minis/skills"
    nonisolated static let minisSharedLinuxDir = "/var/minis/shared"
    nonisolated static let minisMcpServersLinuxDir = "/var/minis/mcp-servers"
    nonisolated static let minisMountsLinuxDir = "/var/minis/mounts"

    /// iOS persistent base for all minis data (Library/MinisChat/minis/).
    nonisolated static var minisPersistentBase: URL {
        let lib = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        return lib.appendingPathComponent("MinisChat/minis", isDirectory: true)
    }

    /// Persistent storage directory for a specific session's offloads.
    nonisolated static func minisOffloadsPersistentDir(for sid: String) -> URL {
        minisPersistentBase.appendingPathComponent(sid, isDirectory: true)
            .appendingPathComponent("offloads", isDirectory: true)
    }

    /// Persistent storage directory for a specific session's attachments.
    nonisolated static func minisAttachmentsPersistentDir(for sid: String) -> URL {
        minisPersistentBase.appendingPathComponent(sid, isDirectory: true)
            .appendingPathComponent("attachments", isDirectory: true)
    }

    /// Persistent storage directory for a specific session's uploaded attachments.
    static func minisUploadsDir(for sid: String) -> URL {
        minisAttachmentsPersistentDir(for: sid)
            .appendingPathComponent("uploads", isDirectory: true)
    }

    /// Downsample a freshly-captured `UIImage` (e.g. from the built-in camera)
    /// and encode it as JPEG. Unlike `resizedImageData(_:maxLongEdge:)` this
    /// ALWAYS re-encodes — the camera hands us a full-resolution UIImage
    /// (~12MP on recent iPhones), and saving it via `pngData()` /
    /// `jpegData(compressionQuality: 1.0)` produced 15–25 MB files
    /// (GH#33 / #565). Capping the long edge and using a normal JPEG quality
    /// brings a single photo down to ~1–3 MB while keeping it legible, matching
    /// what photo-library / screenshot attachments already weigh. If the image
    /// is already at/under `maxLongEdge` we skip the resize but still JPEG-encode.
    /// Returns nil only if the image has no backing `cgImage`.
    static func downsampledCameraJPEG(_ image: UIImage,
                                      maxLongEdge: CGFloat = 2560,
                                      quality: CGFloat = 0.82) -> Data? {
        guard image.cgImage != nil else {
            // No pixel buffer to measure — fall back to a plain JPEG encode so
            // we never silently drop the capture.
            return image.jpegData(compressionQuality: quality)
        }
        let pixelW = image.size.width * image.scale
        let pixelH = image.size.height * image.scale
        let longest = max(pixelW, pixelH)
        guard longest > maxLongEdge else {
            // Already small enough — just compress, don't upscale.
            return image.jpegData(compressionQuality: quality)
        }
        let scale = maxLongEdge / longest
        let newSize = CGSize(width: (pixelW * scale).rounded(), height: (pixelH * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: newSize, format: format)
        let resized = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: newSize)) }
        return resized.jpegData(compressionQuality: quality)
    }

    /// Resize image so its longest edge is at most `maxLongEdge` pixels.
    /// Returns nil if the image is already within bounds.
    static func resizedImageData(_ data: Data, maxLongEdge: CGFloat = 2000) -> Data? {
        guard let image = UIImage(data: data), image.cgImage != nil else { return nil }
        let pixelW = image.size.width * image.scale
        let pixelH = image.size.height * image.scale
        let longest = max(pixelW, pixelH)
        guard longest > maxLongEdge else { return nil }
        let scale = maxLongEdge / longest
        let newSize = CGSize(width: pixelW * scale, height: pixelH * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: newSize, format: format)
        let resized = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: newSize)) }
        return resized.jpegData(compressionQuality: 0.85)
    }

    /// Encode an image into a JPEG that fits under `targetMaxBytes`, falling
    /// back through progressively smaller max-edges and lower JPEG qualities.
    /// Returns the smallest acceptable encoding plus the mime type ("image/jpeg").
    /// When the input is already under target, returns the input data verbatim.
    /// When even the most aggressive pass still exceeds target, returns the
    /// best (smallest) encoding we managed — caller decides whether to drop
    /// the image or send it anyway. T-imgsize-13b7d81c.
    static func compressedImageDataUnderBudget(
        _ data: Data,
        targetMaxBytes: Int
    ) -> (data: Data, didCompress: Bool) {
        guard data.count > targetMaxBytes else { return (data, false) }
        // Re-encode passes ordered most-permissive → most-aggressive. Each
        // pass starts from the ORIGINAL bytes so we never compound JPEG
        // artifacts by re-encoding an already-encoded encoding.
        let passes: [(maxEdge: CGFloat, quality: CGFloat)] = [
            (2000, 0.80),
            (1600, 0.75),
            (1280, 0.70),
            (1024, 0.65),
            (800,  0.55),
            (640,  0.45),
        ]
        var best: Data = data
        for (edge, q) in passes {
            guard let encoded = jpegEncode(data, maxLongEdge: edge, quality: q) else { continue }
            if encoded.count < best.count { best = encoded }
            if encoded.count <= targetMaxBytes { return (encoded, true) }
        }
        return (best, true)
    }

    // MARK: - Dynamic Max Tokens

    /// Effective context window for the given model, respecting the bound
    /// group's context-limit override. The slider's "Unlimited" stop stores
    /// `Int.max` as a sentinel meaning "no override — use whatever the model
    /// declares natively". This keeps the slider visible (toggle stays ON,
    /// slider at the rightmost stop) without leaking Int.max as a real ceiling.
    ///
    /// The group limit is read LIVE from the store on every call — it used to
    /// be mirrored into an in-memory `activeContextLimitTokens` that was only
    /// refreshed on model/group re-bind, so editing the group's limit (or the
    /// model's context window) with a session open kept judging capacity by
    /// the stale mirror until the user re-picked the model. One live lookup
    /// (binding + group) per capacity check is cheap and always current.
    func effectiveContextWindow(for model: LLMModel) -> Int {
        resolvedContextWindow(for: model).window
    }

    /// The window plus whether it came from a user-chosen group cap, which
    /// `ContextPolicy` needs in order to pick proportional thresholds
    /// ([T-ctx-user-cap]).
    ///
    /// The cap is applied as `min(modelWindow, groupLimit)`, matching Android's
    /// `effectiveContextWindowTokens()`. A cap set ABOVE the model's native
    /// window is not a licence to overflow the model — it just means "no
    /// practical limit", so it must not raise the ceiling. `isUserCap` is
    /// therefore true only when the cap actually binds.
    func resolvedContextWindow(for model: LLMModel) -> (window: Int, isUserCap: Bool) {
        let native = model.contextWindowTokens
        guard let override = activeGroupContextLimit(), override > 0, override < Int.max else {
            return (native, false)
        }
        guard native > 0 else { return (override, true) }
        return override < native ? (override, true) : (native, false)
    }

    /// The bound group's context-limit override for the current session, or nil
    /// when the session is bound to a direct entry / has no binding / the group
    /// has no limit configured.
    private func activeGroupContextLimit() -> Int? {
        guard let sid = sessionId,
              let binding = ProviderConfigStore.shared.binding(for: sid),
              case .group(let gid, _) = binding.primarySource,
              let group = ProviderConfigStore.shared.group(for: gid) else { return nil }
        return group.contextLimitTokens
    }

    /// Hard ceiling on max_tokens we ever send to a provider, regardless of
    /// what the model itself claims. Some models advertise very large output
    /// windows; this bounds a single turn so it can't run away and burn the
    /// whole context budget. The final effective limit is
    /// min(globalMaxTokensCeiling, contextWindow - used, modelMaxTokens).
    ///
    /// Raised 64K → 128K: newer models (and the number-budget thinking tiers
    /// whose budget is carved out of max_tokens — Anthropic legacy high/xhigh/
    /// max, Qwen) were being clipped at 64K, squeezing high-effort thinking.
    static let globalMaxTokensCeiling: Int = 128_000

    /// Compute max output tokens that fits within the remaining context window.
    /// Returns `min(provider.defaultMaxTokens, contextWindow - inputTokens)`, floored at 1024.
    /// `lastContextTokens` is the API-reported input token count from the last call;
    /// when 0 (first call in a turn), falls back to char-based estimate.
    func dynamicMaxTokens(provider: any AgentProvider, model: LLMModel, lastContextTokens: Int = 0) -> Int {
        // Upper bound: prefer the user's configured value on this model.
        //
        // `model.maxOutputTokens` already combines `ModelEntry.baseModel.maxOutputTokens`
        // (API truth, may be nil) with `ModelEntry.overrides.maxOutputTokens` (user edit
        // in the model detail view). If the user set an override, it wins here —
        // previously this function only looked at `provider.defaultMaxTokens` and
        // silently ignored the override, which was the bug that made tiny per-model
        // limits have no effect on the actual request body.
        //
        // Fall back to provider.defaultMaxTokens only when neither the API nor the user
        // gave us a model-specific cap.
        //
        // Then clamp by the global ceiling so a model claiming 200K output never
        // actually causes us to request 200K.
        let modelOrProvider = model.maxOutputTokens ?? provider.defaultMaxTokens
        let upperBound = min(Self.globalMaxTokensCeiling, modelOrProvider)

        let contextWindow = effectiveContextWindow(for: model)
        guard contextWindow > 0 else { return upperBound }

        let inputTokens = lastContextTokens > 0 ? lastContextTokens : estimateContextTokens()
        let remaining = contextWindow - inputTokens

        // [T-ctx-overflow-hard-stop] The input ALREADY does not fit. The floor
        // below would quietly turn a negative `remaining` back into 1024 and the
        // oversized request would go out anyway — silently, since only the
        // `result != upperBound` line logged. Record it loudly: the send and
        // in-loop guards both treat overflow as `.needsCompact` now, so reaching
        // here means something bypassed them (a path that skips the check, or an
        // estimate that was under the ceiling when checked and over it by send
        // time). The clamp still returns the floor — refusing to send is the
        // guards' job, not this function's — but it no longer does so unnoticed.
        if remaining <= 0 {
            logger.warning("⚠️ dynamicMaxTokens: input \(inputTokens) tokens EXCEEDS context window \(contextWindow) (over by \(inputTokens - contextWindow)) — request would overflow; compaction guard should have fired")
        }

        // Floor: originally a fixed 1024, to guarantee enough room for a useful reply
        // when the context window is nearly full. If the user deliberately set a tiny
        // upper bound (e.g. 32 to test something), respect it — cap the floor to the
        // upper bound so we never inflate a deliberately small value.
        let floor = min(1024, upperBound)
        let clamped = max(remaining, floor)
        let result = min(upperBound, clamped)

        if result != upperBound {
            logger.info("📐 dynamicMaxTokens: \(result) (upperBound \(upperBound), remaining \(remaining), floor \(floor), window \(contextWindow), input \(inputTokens))")
        }
        // [T-maxtokens-override-receipt] One line per computation naming the
        // SOURCE of the upper bound. Field reports of "I changed Max Output
        // Tokens and the request still says 16384" have two very different
        // causes — the override never reaching this computation (code bug) vs
        // the user editing a different entry than the one the session is
        // bound to (data/UX issue). The 2026-07-21 audit traced every request
        // path (group loop, auto-retry, reminder/retry call sites) to
        // entry-merged models, so src=model here with the expected value
        // proves the chain; src=providerDefault on a model the user "edited"
        // means the bound entry has no override — check WHICH entry they edited.
        logger.info("📐 maxTokens=\(result) src=\(model.maxOutputTokens != nil ? "model(\(model.maxOutputTokens!))" : "providerDefault(\(provider.defaultMaxTokens))") ceiling=\(Self.globalMaxTokensCeiling) model=\(model.id)")
        return result
    }

}
