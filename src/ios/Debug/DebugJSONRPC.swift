#if DEBUG
import Foundation
import WebKit
import UIKit
import CloudKit
import AVFoundation
import SQLite3

/// In-memory ring buffer of screenshots for time-correlated debugging.
/// Captures are tagged with timestamp and optional label. Max 20 entries (~2MB at 0.5x scale).
final class DebugScreenshotBuffer: @unchecked Sendable {
    static let shared = DebugScreenshotBuffer()

    struct Entry {
        let id: Int
        let label: String
        let date: Date
        let pngData: Data

        var iso: String {
            Self.formatter.string(from: date)
        }
        private static let formatter: ISO8601DateFormatter = {
            let f = ISO8601DateFormatter()
            f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            return f
        }()
    }

    private var entries: [Entry] = []
    private var nextId = 1
    private let maxEntries = 20
    private let lock = NSLock()

    @MainActor
    func capture(label: String = "", scale: Double = 0.5) throws -> Entry {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first, let window = scene.windows.first else {
            throw NSError(domain: "DebugScreenshotBuffer", code: -1, userInfo: [NSLocalizedDescriptionKey: "No window"])
        }

        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = CGFloat(scale)
        let renderer = UIGraphicsImageRenderer(bounds: window.bounds, format: fmt)
        let image = renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        }
        guard let data = image.pngData() else {
            throw NSError(domain: "DebugScreenshotBuffer", code: -2, userInfo: [NSLocalizedDescriptionKey: "PNG encode failed"])
        }

        lock.lock()
        let entry = Entry(id: nextId, label: label, date: Date(), pngData: data)
        nextId += 1
        entries.append(entry)
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
        }
        lock.unlock()

        return entry
    }

    func captureAsync(label: String = "", scale: Double = 0.5) {
        Task { @MainActor in
            _ = try? capture(label: label, scale: scale)
        }
    }

    func list() -> [Entry] {
        lock.lock()
        defer { lock.unlock() }
        return entries
    }

    func get(id: Int) -> Entry? {
        lock.lock()
        defer { lock.unlock() }
        return entries.first(where: { $0.id == id })
    }

    func latest() -> Entry? {
        lock.lock()
        defer { lock.unlock() }
        return entries.last
    }

    func clear() -> Int {
        lock.lock()
        defer { lock.unlock() }
        let count = entries.count
        entries.removeAll()
        return count
    }
}

final class DebugJSONRPC: @unchecked Sendable {

    private let inspector: DebugViewInspector

    /// Static meta included in every JSON-RPC response.
    private static let responseMeta: [String: Any] = {
        let info = Bundle.main.infoDictionary ?? [:]
        return [
            "app": "MinisApp",
            "version": info["CFBundleShortVersionString"] as? String ?? "?",
            "build": info["CFBundleVersion"] as? String ?? "?",
            "device": DeviceIdentity.deviceName,
        ]
    }()

    init(inspector: DebugViewInspector) {
        self.inspector = inspector
    }

    // MARK: - Public

    func handle(json: String) async -> String {
        guard let data = json.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return errorResponse(code: -32700, message: "Parse error", id: nil)
        }

        let id = parsed["id"]
        let jsonrpc = parsed["jsonrpc"] as? String

        guard jsonrpc == "2.0" else {
            return errorResponse(code: -32600, message: "Invalid Request: missing jsonrpc 2.0", id: id)
        }

        guard let method = parsed["method"] as? String else {
            return errorResponse(code: -32600, message: "Invalid Request: missing method", id: id)
        }

        let params = parsed["params"] as? [String: Any] ?? [:]

        do {
            let result = try await dispatch(method: method, params: params)
            return successResponse(result: result, id: id)
        } catch let error as RPCError {
            return errorResponse(code: error.code, message: error.message, id: id)
        } catch let error as DebugRPCErr {
            return errorResponse(code: error.code, message: error.message, id: id)
        } catch {
            return errorResponse(code: -32000, message: error.localizedDescription, id: id)
        }
    }

    // MARK: - Dispatch

    private func dispatch(method: String, params: [String: Any]) async throws -> Any {
        switch method {
        case "rpc.discover":
            return DebugMethodRegistry.discover()
        case "debug.viewTree":
            return try await handleViewTree(params: params)
        case "debug.search":
            return try await handleSearch(params: params)
        case "debug.inspect":
            return try await handleInspect(params: params)
        case "debug.highlight":
            return try await handleHighlight(params: params)
        case "debug.agentTrace":
            return handleAgentTrace(params: params)
        case "debug.agentTrace.clear":
            return handleAgentTraceClear(params: params)
        case "debug.agentTrace.pause":
            return handleAgentTracePause(params: params)
        case "debug.ls":
            return try handleLS(params: params)
        case "debug.readFile":
            return try handleReadFile(params: params)
        case "debug.writeFile":
            return try handleWriteFile(params: params)
        case "debug.shellExecute":
            return try await handleShellExecute(params: params)
        case "debug.appInfo":
            return handleAppInfo(params: params)
        case "debug.backup.export":
            return try await DebugRPCBackup.export(params: params)
        case "debug.backup.inspect":
            return try await DebugRPCBackup.inspect(params: params)
        case "debug.backup.cleanup":
            return try await DebugRPCBackup.cleanup(params: params)
        case "debug.backup.open":
            return try await DebugRPCBackup.open(params: params)
        case "debug.backup.destinations":
            return try await DebugRPCBackup.destinations(params: params)
        case "debug.backup.rescue":
            return try await DebugRPCBackup.rescue(params: params)
        case "debug.rclone.info":
            return try await DebugRPCBackup.rcloneInfo(params: params)
        case "debug.rclone.remote":
            return try await DebugRPCBackup.rcloneRemote(params: params)
        case "debug.backup.restore":
            return try await DebugRPCBackup.restore(params: params)
        case "debug.auth.list":
            return ["tokens": DebugAuthenticator.shared.listTokens(),
                    "authRequired": DebugAuthenticator.shared.authRequired]
        case "debug.auth.revoke":
            guard let tok = params["tok"] as? String else {
                throw RPCError(code: -32602, message: "Missing required param: tok")
            }
            return ["revoked": DebugAuthenticator.shared.revoke(tokId: tok)]
        case "debug.auth.revokeAll":
            return ["revoked": DebugAuthenticator.shared.revokeAll()]
        case "debug.auth.setRequired":
            guard let required = params["required"] as? Bool else {
                throw RPCError(code: -32602, message: "Missing required param: required")
            }
            DebugAuthenticator.shared.setAuthRequired(required)
            return ["authRequired": required]
        case "debug.scrollMetrics":
            // [T-ios-scroll-metrics-ring] Recording is OFF by default; pass
            // enabled=true/false to arm/disarm (disarming empties the ring).
            // clear=true empties the ring after reading.
            if let enabled = params["enabled"] as? Bool {
                ScrollMetricsRecorder.shared.setEnabled(enabled)
                return ["enabled": enabled]
            }
            let clear = params["clear"] as? Bool ?? false
            let events = ScrollMetricsRecorder.shared.snapshot(clear: clear)
            return ["count": events.count, "enabled": ScrollMetricsRecorder.shared.isEnabled, "events": events]
        case "debug.browser.cancelTest":
            return try await handleBrowserCancelTest(params: params)
        case "debug.browser.concurrentTest":
            return try await handleBrowserConcurrentTest(params: params)
        case "debug.browser.agentNav":
            // One navigate through the agent path (singleTab:false) — lets tests
            // exercise implicit-tab fan-out / idle-tab reuse exactly as the agent
            // does. [T-browser-tab-reuse-silent-switch]
            let url = params["url"] as? String ?? "about:blank"
            let pool = try await getBrowserPool()
            guard let input = BrowserActionInput.parse(from: "{\"action\":\"navigate\",\"url\":\"\(url)\"}") else {
                throw RPCError(code: -32602, message: "bad url")
            }
            let r = try await pool.execute(action: input, singleTab: false)
            return ["tabId": r.tabId as Any, "success": r.success, "text": r.text]
        case "debug.browser.setDeadTimeout":
            let secs = params["seconds"] as? Double
            await MainActor.run { BrowserTabPool.actionDeadTimeoutOverride = secs }
            return ["actionDeadTimeout": secs ?? 300, "note": secs == nil ? "reset to default 300s" : "override active"]
        case "debug.db.list":
            return try handleDBList()
        case "debug.db.tables":
            return try handleDBTables(params: params)
        case "debug.db.schema":
            return try handleDBSchema(params: params)
        case "debug.db.query":
            return try handleDBQuery(params: params)
        case "debug.browser.listTabs":
            return try await handleBrowserListTabs()
        case "debug.browser.pageInfo":
            return try await handleBrowserPageInfo(params: params)
        case "debug.browser.executeJS":
            return try await handleBrowserExecuteJS(params: params)
        case "debug.browser.getReadable":
            return try await handleBrowserGetReadable(params: params)
        case "debug.browser.getText":
            return try await handleBrowserGetText(params: params)
        case "debug.browser.screenshot":
            return try await handleBrowserScreenshot(params: params)
        case "debug.llmRequests":
            return handleLLMRequests(params: params)
        case "debug.voiceInputs":
            return handleVoiceInputs(params: params)
        case "debug.voice.panel":
            return try await handleVoicePanel(params: params)
        case "debug.rootfs.reset":
            return try await handleRootfsReset(params: params)
        case "debug.logs.list":
            return handleLogsList()
        case "debug.logs.read":
            return try handleLogsRead(params: params)
        case "debug.logs.flush":
            return handleLogsFlush()
        case "debug.logs.setEnabled":
            return try await handleLogsSetEnabled(params: params)
        case "debug.toolExec.setEnabled":
            return try handleToolExecSetEnabled(params: params)
        case "debug.toolExec.getStatus":
            return handleToolExecGetStatus()
        case "debug.screenshot":
            return try await handleScreenshot(params: params)
        case "debug.screenshot.capture":
            return try await handleScreenshotCapture(params: params)
        case "debug.screenshot.list":
            return handleScreenshotList()
        case "debug.screenshot.get":
            return try handleScreenshotGet(params: params)
        case "debug.screenshot.clear":
            return handleScreenshotClear()
        case "debug.sync.transports":
            return await handleSyncTransports()
        case "debug.sync.tombstones":
            return await handleSyncTombstones(params: params)
        case "debug.sync.dirtyByType":
            return await handleSyncDirtyByType()
        case "debug.sync.providerV3.status":
            return await handleProviderV3Status()
        case "debug.sync.providerV3.setEnabled":
            return await handleProviderV3SetEnabled(params: params)
        case "debug.sync.ckQuery":
            return await handleSyncCKQuery(params: params)
        case "debug.sync.allZones":
            return await handleSyncAllZones()
        case "debug.sync.deviceId":
            return await handleSyncDeviceId()
        case "debug.sync.zoneStats":
            return await handleSyncZoneStats(params: params)
        case "debug.envvar.list":
            return await handleEnvVarList(params: params)
        case "debug.envvar.set":
            return await handleEnvVarSet(params: params)
        case "debug.envvar.delete":
            return await handleEnvVarDelete(params: params)
        case "debug.tap":
            return try await handleTap(params: params)
        case "debug.inputText":
            return try await handleInputText(params: params)
        case "debug.scroll":
            return try await handleScroll(params: params)
        case "debug.scrollOffset":
            return try await handleScrollOffset(params: params)
        case "debug.openSession":
            return try await handleOpenSession(params: params)
        // MARK: Hang detector
        case "debug.hangDetector.start":
            return DebugRPCHangDetector.start(params: params)
        case "debug.hangDetector.stop":
            return DebugRPCHangDetector.stop()
        case "debug.hangDetector.dump":
            return DebugRPCHangDetector.dump(params: params)
        case "debug.hangDetector.clear":
            return DebugRPCHangDetector.clear()
        // MARK: PerfTrace
        case "debug.perfTrace.start":
            return DebugRPCPerfTrace.start(params: params)
        case "debug.perfTrace.stop":
            return DebugRPCPerfTrace.stop()
        case "debug.perfTrace.status":
            return DebugRPCPerfTrace.status()
        case "debug.perfTrace.flush":
            return DebugRPCPerfTrace.flush()
        // MARK: Provider methods
        case "provider.types":
            return await MainActor.run { DebugRPCProvider.types() }
        case "provider.instances.list":
            let includeDisabled = (params["includeDisabled"] as? Bool) ?? true
            return await MainActor.run { DebugRPCProvider.instancesList(includeDisabled: includeDisabled) }
        case "provider.instances.create":
            return try await MainActor.run { try DebugRPCProvider.instancesCreate(params: params) }
        case "provider.instances.update":
            return try await MainActor.run { try DebugRPCProvider.instancesUpdate(params: params) }
        case "provider.instances.delete":
            return try await MainActor.run { try DebugRPCProvider.instancesDelete(params: params) }
        case "provider.models.list":
            return try await MainActor.run { try DebugRPCProvider.modelsList(params: params) }
        case "provider.thinkingRules.list":
            return try await DebugRPCProvider.thinkingRulesList(params: params)
        case "provider.thinkingRules.add":
            return try await DebugRPCProvider.thinkingRulesAdd(params: params)
        case "provider.thinkingRules.delete":
            return try await DebugRPCProvider.thinkingRulesDelete(params: params)
        case "provider.thinkingRules.resolve":
            return try await DebugRPCProvider.thinkingRulesResolve(params: params)
        // [T-config-debug-rpc] minis-config, driven remotely through the same
        // ConfigOffloadBridge entry points the in-guest CLI binary calls.
        case "config.get":
            return try await DebugRPCConfig.get(params: params)
        case "config.set":
            return try await DebugRPCConfig.set(params: params)
        case "config.topics":
            return try await DebugRPCConfig.topics(params: params)
        case "config.topicHelp":
            return try await DebugRPCConfig.topicHelp(params: params)
        case "config.audit":
            return try await DebugRPCConfig.audit(params: params)
        case "provider.models.add":
            return try await MainActor.run { try DebugRPCProvider.modelsAdd(params: params) }
        case "provider.models.update":
            return try await MainActor.run { try DebugRPCProvider.modelsUpdate(params: params) }
        case "provider.models.delete":
            return try await MainActor.run { try DebugRPCProvider.modelsDelete(params: params) }
        case "provider.models.refresh":
            return try await DebugRPCProvider.modelsRefresh(params: params)
        case "provider.export":
            return try await MainActor.run { try DebugRPCProvider.exportInstance(params: params) }
        case "provider.import":
            return try await MainActor.run { try DebugRPCProvider.importInstance(params: params) }
        case "provider.models.setAgentLoop":
            return try await MainActor.run { try DebugRPCProvider.modelsSetAgentLoop(params: params) }
        case "provider.models.setModality":
            return try await MainActor.run { try DebugRPCProvider.modelsSetModality(params: params) }
        case "provider.groups.list":
            return await MainActor.run { DebugRPCProvider.groupsList(params: params) }
        case "provider.groups.create":
            return try await MainActor.run { try DebugRPCProvider.groupsCreate(params: params) }
        case "provider.groups.update":
            return try await MainActor.run { try DebugRPCProvider.groupsUpdate(params: params) }
        case "provider.groups.delete":
            return try await MainActor.run { try DebugRPCProvider.groupsDelete(params: params) }
        case "provider.groups.setDefault":
            return try await MainActor.run { try DebugRPCProvider.groupsSetDefault(params: params) }
        case "provider.groups.setAgentLoop":
            return try await MainActor.run { try DebugRPCProvider.groupsSetAgentLoop(params: params) }
        // MARK: MCP methods
        case "debug.mcp.list":
            return await MainActor.run { DebugRPCMCP.list() }
        case "debug.mcp.import":
            return try await MainActor.run { try DebugRPCMCP.importJSON(params: params) }
        case "debug.mcp.delete":
            return try await MainActor.run { try DebugRPCMCP.delete(params: params) }
        // MARK: Chat methods
        case "chat.models.list":
            return try await MainActor.run { try DebugRPCChat.modelsList(params: params) }
        case "chat.sessions.list":
            return await DebugRPCChat.sessionsList(params: params)
        case "chat.sessions.get":
            return try await DebugRPCChat.sessionsGet(params: params)
        case "chat.messages.list":
            return try await DebugRPCChat.messagesList(params: params)
        case "chat.prompt":
            return try await DebugRPCChat.prompt(params: params)
        case "chat.session.status":
            return try await DebugRPCChat.sessionStatus(params: params)
        case "chat.session.blocksDiff":
            return try await DebugRPCChat.blocksDiff(params: params)
        case "debug.measureCompare":
            return try await DebugRPCChat.measureCompare(params: params)
        case "chat.session.cancel":
            return try await MainActor.run { try DebugRPCChat.sessionCancel(params: params) }
        case "chat.thinking.setExpanded":
            return try await MainActor.run { try DebugRPCChat.thinkingSetExpanded(params: params) }
        case "chat.session.delete":
            return try await DebugRPCChat.sessionDelete(params: params)
        case "debug.folders.list":
            return try await DebugRPCChat.foldersList(params: params)
        case "debug.folders.create":
            return try await DebugRPCChat.foldersCreate(params: params)
        case "debug.folders.move":
            return try await DebugRPCChat.foldersMove(params: params)
        case "debug.folders.dissolve":
            return try await DebugRPCChat.foldersDissolve(params: params)
        case "debug.folders.get":
            return try await DebugRPCChat.foldersGet(params: params)
        case "debug.folders.rename":
            return try await DebugRPCChat.foldersRename(params: params)
        case "debug.sessions.badge":
            return try await DebugRPCChat.sessionsBadge(params: params)
        case "debug.appearance.set":
            return try await DebugRPCChat.appearanceSet(params: params)
        case "debug.sessions.pin":
            return try await DebugRPCChat.sessionsPin(params: params)
        case "debug.folders.pin":
            return try await DebugRPCChat.foldersPin(params: params)
        case "debug.folders.setAutoGrouping":
            return try await DebugRPCChat.foldersSetAutoGrouping(params: params)
        case "chat.compact.markers.list":
            return try await DebugRPCChat.compactMarkersList(params: params)
        case "chat.compact.before":
            return try await DebugRPCChat.compactBefore(params: params)
        case "chat.compact.revert":
            return try await DebugRPCChat.compactRevert(params: params)
        case "chat.simulateLongMessage":
            return try await DebugRPCChat.simulateLongMessage(params: params)
        case "chat.debugRemoveMessages":
            return try await DebugRPCChat.debugRemoveMessages(params: params)
        case "chat.deleteFromMessage":
            return try await DebugRPCChat.deleteFromMessage(params: params)
        #if DEBUG
        // MARK: Voice correction (design §10) — DEBUG-only, like the rest of this block.
        case "debug.voiceCorrection.confusionList":
            return try await VoiceCorrectionDebugRPC.confusionList(params: params)
        case "debug.voiceCorrection.vocabularyList":
            return try await VoiceCorrectionDebugRPC.vocabularyList(params: params)
        case "debug.voiceCorrection.lookup":
            return try await VoiceCorrectionDebugRPC.lookup(params: params)
        case "debug.voiceCorrection.stats":
            return try await VoiceCorrectionDebugRPC.stats(params: params)
        case "debug.voiceCorrection.dryRunCorrection":
            return try await VoiceCorrectionDebugRPC.dryRunCorrection(params: params)
        case "debug.voiceCorrection.runCorrection":
            return try await VoiceCorrectionDebugRPC.runCorrection(params: params)
        case "debug.voiceCorrection.simulateVocabularyFilter":
            return try await VoiceCorrectionDebugRPC.simulateVocabularyFilter(params: params)
        case "debug.voiceCorrection.rebuildVocabulary":
            return try await VoiceCorrectionDebugRPC.rebuildVocabulary(params: params)
        case "debug.voiceCorrection.metrics":
            return try await VoiceCorrectionDebugRPC.metrics(params: params)
        case "debug.voiceCorrection.vocabularyStats":
            return try await VoiceCorrectionDebugRPC.vocabularyStats(params: params)
        case "debug.voiceCorrection.traceList":
            return try await VoiceCorrectionDebugRPC.traceList(params: params)
        case "debug.voiceCorrection.traceManualEdits":
            return try await VoiceCorrectionDebugRPC.traceManualEdits(params: params)
        case "debug.voiceCorrection.traceClear":
            return try await VoiceCorrectionDebugRPC.traceClear(params: params)
        case "debug.voiceCorrection.injectConfusionRecord":
            return try await VoiceCorrectionDebugRPC.injectConfusionRecord(params: params)
        case "debug.voiceCorrection.recordManualEdit":
            return try await VoiceCorrectionDebugRPC.recordManualEdit(params: params)
        case "debug.voiceCorrection.clearTable":
            return try await VoiceCorrectionDebugRPC.clearTable(params: params)
        case "debug.markdownSnapshot.setEnabled":
            return try handleMarkdownSnapshotSetEnabled(params: params)
        case "debug.markdownSnapshot.list":
            return handleMarkdownSnapshotList()
        case "debug.markdownSnapshot.get":
            return try handleMarkdownSnapshotGet(params: params)
        case "debug.markdownSnapshot.clear":
            return handleMarkdownSnapshotClear()
        case "debug.messageListSnapshot.setEnabled":
            return try handleMessageListSnapshotSetEnabled(params: params)
        case "debug.messageListSnapshot.list":
            return handleMessageListSnapshotList()
        case "debug.messageListSnapshot.get":
            return try handleMessageListSnapshotGet(params: params)
        case "debug.messageListSnapshot.clear":
            return handleMessageListSnapshotClear()
        case "debug.messageListSnapshot.captureNow":
            return try await handleMessageListSnapshotCaptureNow()
        case "debug.debugOverlay.setEnabled":
            return try await handleDebugOverlaySetEnabled(params: params)
        case "debug.debugOverlay.setMode":
            return try await handleDebugOverlaySetMode(params: params)
        case "debug.providers.quickTest":
            return try await handleProvidersQuickTest(params: params)
        #endif
        default:
            throw RPCError(code: -32601, message: "Method not found: \(method). Call 'rpc.discover' to list available methods.")
        }
    }

    #if DEBUG
    // MARK: - Markdown Snapshot handlers (DEBUG only)

    private func handleMarkdownSnapshotSetEnabled(params: [String: Any]) throws -> Any {
        guard let enabled = params["enabled"] as? Bool else {
            throw RPCError(code: -32602, message: "Missing required 'enabled' (bool).")
        }
        MarkdownSnapshotCollector.isEnabled = enabled
        return ["enabled": enabled]
    }

    private func handleMarkdownSnapshotList() -> Any {
        let brief = MarkdownSnapshotCollector.shared.listBrief()
        return ["count": brief.count, "snapshots": brief]
    }

    private func handleMarkdownSnapshotGet(params: [String: Any]) throws -> Any {
        guard let id = params["id"] as? String else {
            throw RPCError(code: -32602, message: "Missing required 'id' (string).")
        }
        guard let snapshot = MarkdownSnapshotCollector.shared.getFull(id: id) else {
            throw RPCError(code: -32004, message: "Snapshot '\(id)' not found (may have been evicted from ring buffer).")
        }
        return snapshot
    }

    private func handleMarkdownSnapshotClear() -> Any {
        MarkdownSnapshotCollector.shared.clear()
        return ["cleared": true]
    }

    // MARK: - Message-list snapshot handlers (DEBUG only)

    private func handleMessageListSnapshotSetEnabled(params: [String: Any]) throws -> Any {
        guard let enabled = params["enabled"] as? Bool else {
            throw RPCError(code: -32602, message: "Missing required 'enabled' (bool).")
        }
        MessageListSnapshotCollector.isEnabled = enabled
        return ["enabled": enabled]
    }

    private func handleMessageListSnapshotList() -> Any {
        let brief = MessageListSnapshotCollector.shared.listBrief()
        return ["count": brief.count, "snapshots": brief]
    }

    private func handleMessageListSnapshotGet(params: [String: Any]) throws -> Any {
        guard let id = params["id"] as? String else {
            throw RPCError(code: -32602, message: "Missing required 'id' (string).")
        }
        guard let snapshot = MessageListSnapshotCollector.shared.getFull(id: id) else {
            throw RPCError(code: -32004, message: "Snapshot '\(id)' not found.")
        }
        return snapshot
    }

    private func handleMessageListSnapshotClear() -> Any {
        MessageListSnapshotCollector.shared.clear()
        return ["cleared": true]
    }

    // MARK: - Debug View Overlay handlers (DEBUG only)

    private func handleDebugOverlaySetEnabled(params: [String: Any]) async throws -> Any {
        guard let enabled = params["enabled"] as? Bool else {
            throw RPCError(code: -32602, message: "Missing required 'enabled' (bool).")
        }
        await MainActor.run { DebugViewOverlay.shared.setEnabled(enabled) }
        return ["enabled": enabled]
    }

    private func handleDebugOverlaySetMode(params: [String: Any]) async throws -> Any {
        // mode = "all" | "byType" | "byTypePrefix" | "byAddress"
        guard let modeStr = params["mode"] as? String else {
            throw RPCError(code: -32602, message: "Missing required 'mode' (string: 'all'|'byType'|'byTypePrefix'|'byAddress').")
        }
        let mode: DebugViewOverlay.Mode
        switch modeStr {
        case "all":
            mode = .all
        case "byType":
            guard let t = params["type"] as? String else {
                throw RPCError(code: -32602, message: "'byType' requires 'type' (string class name).")
            }
            mode = .byType(t)
        case "byTypePrefix":
            guard let t = params["type"] as? String else {
                throw RPCError(code: -32602, message: "'byTypePrefix' requires 'type' (string class-name prefix).")
            }
            mode = .byTypePrefix(t)
        case "byAddress":
            guard let a = params["address"] as? String else {
                throw RPCError(code: -32602, message: "'byAddress' requires 'address' (string '0x...').")
            }
            mode = .byAddress(a)
        default:
            throw RPCError(code: -32602, message: "Unknown mode '\(modeStr)'. Expected 'all'|'byType'|'byTypePrefix'|'byAddress'.")
        }
        await MainActor.run { DebugViewOverlay.shared.setMode(mode) }
        return ["mode": modeStr]
    }

    /// Force one manual capture of the currently visible message list. Useful
    /// to grab a snapshot AFTER a layout has settled / after a bug repro,
    /// independently of the in-code capture sites.
    private func handleMessageListSnapshotCaptureNow() async throws -> Any {
        await MainActor.run {
            // Walk every window to find a NoAnimationCollectionView.
            for scene in UIApplication.shared.connectedScenes {
                guard let ws = scene as? UIWindowScene else { continue }
                for w in ws.windows {
                    var found: UICollectionView?
                    func dfs(_ v: UIView) {
                        if found != nil { return }
                        if let cv = v as? UICollectionView,
                           String(describing: type(of: cv)) == "NoAnimationCollectionView" {
                            found = cv; return
                        }
                        for s in v.subviews { dfs(s); if found != nil { return } }
                    }
                    dfs(w)
                    if let cv = found {
                        MessageListSnapshotCollector.captureManually(collectionView: cv)
                        return
                    }
                }
            }
        }
        return ["ok": true]
    }
    #endif

    // MARK: - Handlers

    private func handleViewTree(params: [String: Any]) async throws -> Any {
        let maxDepth = params["maxDepth"] as? Int ?? 50
        return await MainActor.run {
            let trees = inspector.buildTree(maxDepth: maxDepth)
            if trees.count == 1 { return trees[0] }
            return trees
        }
    }

    private func handleSearch(params: [String: Any]) async throws -> Any {
        guard let keyword = params["keyword"] as? String, !keyword.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'keyword' is required")
        }
        let scope = params["scope"] as? String ?? "all"
        return await MainActor.run {
            inspector.search(keyword: keyword, scope: scope)
        }
    }

    private func handleInspect(params: [String: Any]) async throws -> Any {
        guard let address = params["address"] as? String, !address.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'address' is required")
        }
        return await MainActor.run {
            guard let result = inspector.inspect(address: address) else {
                return ["error": "View not found at address \(address)"] as [String: Any]
            }
            return result
        }
    }

    private func handleHighlight(params: [String: Any]) async throws -> Any {
        guard let address = params["address"] as? String, !address.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'address' is required")
        }
        let color = params["color"] as? String ?? "red"
        let duration = params["duration"] as? Double ?? 2.0
        return await MainActor.run {
            let ok = inspector.highlight(address: address, color: color, duration: duration)
            return ["ok": ok]
        }
    }

    private func handleAgentTrace(params: [String: Any]) -> Any {
        let last = params["last"] as? Bool ?? false
        if last {
            return AgentRequestTrace.shared.getLast() ?? ["traces": []]
        }
        return ["traces": AgentRequestTrace.shared.getAll()]
    }

    /// [T-debug-trace-pause-clear] Drop recorded traces and free their memory.
    ///
    /// Clears BOTH in-memory recorders by default, because they are two halves
    /// of the same capture and clearing only one leaves the heap held by the
    /// other: `AgentRequestTrace` holds the step timeline, while
    /// `LastAPIRequestBody` holds the request/response BODIES — the latter is
    /// where the bulk actually sits (bodies are capped at 512KB each across a
    /// 5-entry ring plus per-tag sticky slots). Pass `llmRequests: false` to
    /// clear the step traces alone.
    private func handleAgentTraceClear(params: [String: Any]) -> Any {
        let alsoLLM = params["llmRequests"] as? Bool ?? true
        let freedTraces = AgentRequestTrace.shared.clear()
        var result: [String: Any] = [
            "clearedTraces": freedTraces,
            "clearedLLMRequests": false,
        ]
        if alsoLLM {
            let before = LastAPIRequestBody.shared.getAll().count
            LastAPIRequestBody.shared.clear()
            result["clearedLLMRequests"] = true
            result["clearedLLMRequestCount"] = before
        }
        return result
    }

    /// [T-debug-trace-pause-clear] Stop/resume recording, so a captured state
    /// can be inspected without later requests overwriting or growing it.
    ///
    /// Applies to both recorders for the same reason as clear. Omitting
    /// `paused` reports the current state without changing it, so a client can
    /// poll before deciding.
    private func handleAgentTracePause(params: [String: Any]) -> Any {
        let alsoLLM = params["llmRequests"] as? Bool ?? true
        if let paused = params["paused"] as? Bool {
            AgentRequestTrace.shared.setPaused(paused)
            if alsoLLM { LastAPIRequestBody.shared.setPaused(paused) }
        }
        return [
            "paused": AgentRequestTrace.shared.isPaused,
            "llmRequestsPaused": LastAPIRequestBody.shared.isPaused,
        ]
    }

    private func handleLLMRequests(params: [String: Any]) -> Any {
        let requests = LastAPIRequestBody.shared.getAll()
        let last = params["last"] as? Int
        let formatted = params["formatted"] as? Bool ?? false

        let selected: [CapturedAPIRequest]
        if let last, last > 0 {
            selected = Array(requests.suffix(last))
        } else {
            selected = requests
        }

        if formatted {
            let text = selected.enumerated().map { idx, req in
                req.formatted(index: idx + 1)
            }.joined(separator: "\n\n")
            return ["count": selected.count, "text": text]
        }

        let tf = ISO8601DateFormatter()
        tf.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let items: [[String: Any]] = selected.map { req in
            var dict: [String: Any] = [
                "provider": req.provider,
                "timestamp": tf.string(from: req.timestamp),
                "requestBody": req.requestBody,
            ]
            if let url = req.requestURL { dict["requestURL"] = url }
            if let method = req.requestMethod { dict["requestMethod"] = method }
            if let rh = req.requestHeaders { dict["requestHeaders"] = rh }
            if let d = req.durationMs { dict["durationMs"] = d }
            if let u = req.usage {
                dict["usage"] = [
                    "inputTokens": u.inputTokens,
                    "outputTokens": u.outputTokens,
                    "cacheCreationTokens": u.cacheCreationTokens,
                    "cacheReadTokens": u.cacheReadTokens,
                ] as [String: Any]
            }
            if let sc = req.responseStatusCode { dict["responseStatusCode"] = sc }
            if let rh = req.responseHeaders { dict["responseHeaders"] = rh }
            if let rb = req.responseBody { dict["responseBody"] = rb }
            if let tag = req.tag { dict["tag"] = tag }
            return dict
        }
        return ["count": items.count, "requests": items]
    }

    // MARK: - Voice Input Capture Handler

    /// [T-debug-voice-input-capture] Return the last ≤5 audio payloads that were
    /// actually sent to the ASR model, for diagnosing VAD trimming/drops.
    ///   params:
    ///     last          Int?   — return only the most recent N (default: all)
    ///     includeAudio  Bool?  — include base64 WAV per entry (default true)
    private func handleVoiceInputs(params: [String: Any]) -> Any {
        let includeAudio = params["includeAudio"] as? Bool ?? true
        var items = VoiceInputCapture.shared.getAll(includeAudio: includeAudio)
        if let last = params["last"] as? Int, last > 0 {
            items = Array(items.suffix(last))
        }
        return ["count": items.count, "voiceInputs": items]
    }

    /// debug.voice.panel — drive the inline voice-input panel for on-device
    /// e2e audio tests (synthetic debug.tap cannot reach SwiftUI buttons).
    ///   action  "open" | "close" | "mic"
    ///     open  → switch the composer into voice mode (panel appears, idle)
    ///     close → back to keyboard mode
    ///     mic   → same as tapping the panel's main mic button (start
    ///             recording / pause+flush to transcription / cancel)
    private func handleVoicePanel(params: [String: Any]) async throws -> Any {
        guard let action = params["action"] as? String else {
            throw RPCError(code: -32602, message: "Invalid params: 'action' is required (open|close|mic)")
        }
        switch action {
        case "open", "close":
            await MainActor.run {
                VoiceModePreference.shared.isVoiceActive = (action == "open")
            }
        case "mic":
            await MainActor.run {
                NotificationCenter.default.post(name: Notification.Name("MinisDebugVoiceMicTap"), object: nil)
            }
        default:
            throw RPCError(code: -32602, message: "Unknown action '\(action)' (open|close|mic)")
        }
        let active = await MainActor.run { VoiceModePreference.shared.isVoiceActive }
        return ["action": action, "voiceActive": active]
    }

    /// debug.rootfs.reset — delete the rootfs so the next COLD launch reinstalls
    /// a clean copy. Used by test harnesses to recover an iSH mount wedged by
    /// heavy apk churn (processCreationFailed). Reset while the kernel is booted
    /// poisons the current session; the RPC returns `relaunchRequired: true` and
    /// the caller must kill + relaunch the app to get a working shell again.
    private func handleRootfsReset(params: [String: Any]) async throws -> Any {
        let keepUserData = params["keepUserData"] as? Bool ?? true
        do {
            let backup = try await MainActor.run { try RootfsManager.shared.reset(keepUserData: keepUserData) }
            return [
                "reset": true,
                "keepUserData": keepUserData,
                "backupPath": backup?.path as Any,
                "relaunchRequired": true,
                "note": "Rootfs deleted. Kill + cold-launch the app; installIfNeeded() will reinstall a clean rootfs.",
            ]
        } catch {
            throw RPCError(code: -32000, message: "rootfs reset failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Log Handlers

    private func handleLogsList() -> Any {
        let mgr = LoggingManager.shared
        let iso = ISO8601DateFormatter()
        let files = mgr.logFiles().map { file -> [String: Any] in
            [
                "name": file.name,
                "size": file.size,
                "modified": iso.string(from: file.date),
                "type": file.type,
            ]
        }
        return [
            "enabled": mgr.isEnabled,
            "logDirectory": mgr.logDirectory.path,
            "totalSize": mgr.totalLogSize(),
            "files": files,
        ] as [String: Any]
    }

    /// Synchronously flush the in-memory log buffer to disk so a subsequent
    /// `debug.logs.read` sees the very latest lines (the writer batches +
    /// fsyncs lazily, so without this the freshest entries can lag on disk).
    /// Returns today's log name + post-flush size for an immediate tail read.
    private func handleLogsFlush() -> Any {
        let mgr = LoggingManager.shared
        mgr.flushSync()
        // Surface the current (post-flush) file so the caller can read its tail
        // immediately: read from offset = max(0, size - N).
        let iso = ISO8601DateFormatter()
        let newest = mgr.logFiles().max(by: { $0.date < $1.date })
        return [
            "ok": true,
            "enabled": mgr.isEnabled,
            "name": newest?.name as Any,
            "size": newest?.size as Any,
            "modified": newest.map { iso.string(from: $0.date) } as Any,
        ] as [String: Any]
    }

    private func handleLogsRead(params: [String: Any]) throws -> Any {
        guard let name = params["name"] as? String, !name.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'name' is required")
        }
        // Sanitize: only allow filenames, no path separators
        guard !name.contains("/"), !name.contains("..") else {
            throw RPCError(code: -32602, message: "Invalid params: 'name' must be a filename, not a path")
        }

        let mgr = LoggingManager.shared
        let fileURL = mgr.logDirectory.appendingPathComponent(name)

        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            throw RPCError(code: -32602, message: "Log file not found: \(name)")
        }

        // Cap response size hard so a multi-MB log never floods the
        // single-shot HTTP response and stalls the connection.
        let requestedLimit = params["limit"] as? Int ?? 262_144 // 256KB default
        let limit = min(max(requestedLimit, 0), 1_048_576)      // 1MB ceiling
        let offset = params["offset"] as? Int

        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { handle.closeFile() }

        let attrs = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let fileSize = attrs[.size] as? Int ?? 0

        let startOffset = offset.map { max(0, $0) } ?? 0
        if startOffset > 0 {
            handle.seek(toFileOffset: UInt64(startOffset))
        }

        let data = handle.readData(ofLength: limit)
        let content = String(data: data, encoding: .utf8) ?? data.base64EncodedString()

        var result: [String: Any] = [
            "name": name,
            "size": fileSize,
            "offset": startOffset,
            "bytesRead": data.count,
            "content": content,
        ]
        let endOffset = startOffset + data.count
        if endOffset < fileSize {
            result["truncated"] = true
            result["nextOffset"] = endOffset
        }
        return result
    }

    private func handleLogsSetEnabled(params: [String: Any]) async throws -> Any {
        guard let enabled = params["enabled"] as? Bool else {
            throw RPCError(code: -32602, message: "Invalid params: 'enabled' (bool) is required")
        }
        await MainActor.run {
            LoggingManager.shared.isEnabled = enabled
        }
        return ["enabled": enabled]
    }

    // MARK: - Tool-exec Breadcrumb Handlers

    /// Flip the durable `[ToolExec]` breadcrumb on/off at runtime. The state is
    /// persisted, so it survives the Jetsam/SIGKILL restarts this breadcrumb
    /// exists to investigate.
    private func handleToolExecSetEnabled(params: [String: Any]) throws -> Any {
        guard let enabled = params["enabled"] as? Bool else {
            throw RPCError(code: -32602, message: "Invalid params: 'enabled' (bool) is required")
        }
        CrashReporter.toolBreadcrumbEnabled = enabled
        return CrashReporter.toolBreadcrumbStatus()
    }

    /// Current switch state plus the breadcrumb file's path/size, so a caller
    /// can decide whether to follow up with `debug.readFile`. No dedicated read
    /// method: the file is a plain text file under Application Support and
    /// `debug.readFile` already handles it — the `path` returned here is what
    /// you feed it.
    private func handleToolExecGetStatus() -> Any {
        CrashReporter.toolBreadcrumbStatus()
    }

    // MARK: - Screenshot Handler

    private func handleScreenshot(params: [String: Any]) async throws -> Any {
        let scale = params["scale"] as? Double ?? 1.0
        let windowIndex = params["window"] as? Int ?? 0

        let pngData: Data = try await MainActor.run {
            let scenes = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .sorted { ($0.windows.first?.windowLevel.rawValue ?? 0) < ($1.windows.first?.windowLevel.rawValue ?? 0) }

            guard let scene = scenes.first,
                  windowIndex < scene.windows.count else {
                throw RPCError(code: -32000, message: "No window available (index: \(windowIndex))")
            }

            let window = scene.windows[windowIndex]
            let fmt = UIGraphicsImageRendererFormat()
            fmt.scale = CGFloat(scale)
            let renderer = UIGraphicsImageRenderer(bounds: window.bounds, format: fmt)
            let image = renderer.image { ctx in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
            }
            guard let data = image.pngData() else {
                throw RPCError(code: -32000, message: "Failed to encode screenshot")
            }
            return data
        }

        return [
            "base64": pngData.base64EncodedString(),
            "size": pngData.count,
            "encoding": "png",
        ] as [String: Any]
    }

    // MARK: - Screenshot Buffer Handlers

    private func handleScreenshotCapture(params: [String: Any]) async throws -> Any {
        let label = params["label"] as? String ?? ""
        let scale = params["scale"] as? Double ?? 0.5
        let entry = try await MainActor.run {
            try DebugScreenshotBuffer.shared.capture(label: label, scale: scale)
        }
        return [
            "id": entry.id,
            "label": entry.label,
            "timestamp": entry.iso,
            "size": entry.pngData.count,
        ] as [String: Any]
    }

    private func handleScreenshotList() -> Any {
        let entries = DebugScreenshotBuffer.shared.list()
        return [
            "count": entries.count,
            "entries": entries.map { [
                "id": $0.id,
                "label": $0.label,
                "timestamp": $0.iso,
                "size": $0.pngData.count,
            ] as [String: Any] },
        ] as [String: Any]
    }

    private func handleScreenshotGet(params: [String: Any]) throws -> Any {
        if let id = params["id"] as? Int {
            guard let entry = DebugScreenshotBuffer.shared.get(id: id) else {
                throw RPCError(code: -32602, message: "Screenshot not found: \(id)")
            }
            return [
                "id": entry.id,
                "label": entry.label,
                "timestamp": entry.iso,
                "base64": entry.pngData.base64EncodedString(),
                "size": entry.pngData.count,
                "encoding": "png",
            ] as [String: Any]
        }
        // Return the latest
        guard let entry = DebugScreenshotBuffer.shared.latest() else {
            throw RPCError(code: -32000, message: "No screenshots captured")
        }
        return [
            "id": entry.id,
            "label": entry.label,
            "timestamp": entry.iso,
            "base64": entry.pngData.base64EncodedString(),
            "size": entry.pngData.count,
            "encoding": "png",
        ] as [String: Any]
    }

    private func handleScreenshotClear() -> Any {
        let count = DebugScreenshotBuffer.shared.clear()
        return ["cleared": count]
    }

    // MARK: - Sync v2 Diagnostics

    /// debug.sync.transports — list registered transports + capabilities.
    private func handleSyncTransports() async -> Any {
        if #available(iOS 17.0, *) {
            let core = await MainActor.run { SyncCore.shared }
            let entries = await MainActor.run {
                core.transports.map { t -> [String: Any] in
                    [
                        "name": t.name,
                        "capabilities": t.capabilities.rawValue,
                        "isLan": t is LANTransport,
                    ] as [String: Any]
                }
            }
            return [
                "isRunning": await MainActor.run { core.isRunning },
                "transports": entries,
                "lanImplemented": LANTransport.isImplemented,
                "registeredRecordTypes": SyncableTypeRegistry.shared.allRecordTypes,
            ] as [String: Any]
        }
        return ["status": "unavailable"] as [String: Any]
    }

    /// debug.sync.tombstones — current tombstoned session ids.
    private func handleSyncTombstones(params: [String: Any]) async -> Any {
        if #available(iOS 17.0, *) {
            if let sid = params["sessionId"] as? String, !sid.isEmpty {
                let tomb = await TombstoneManager.shared.isTombstoned(sessionId: sid)
                return ["sessionId": sid, "tombstoned": tomb] as [String: Any]
            }
            let all = await TombstoneManager.shared.allTombstonedSessionIds()
            return ["count": all.count, "sessionIds": all] as [String: Any]
        }
        return ["status": "unavailable"] as [String: Any]
    }

    /// debug.sync.providerV3.status — report the v3 per-record provider
    /// sync state. Used by both manual debugging and the audit script
    /// that compares two devices' provider-config state.
    private func handleProviderV3Status() async -> Any {
        let enabled = await MainActor.run { ProviderV3Bootstrap.isEnabled }
        let migrated = UserDefaults.standard.bool(forKey: "cloudSync.providerV3.migrationCompleted")
        let db = await MainActor.run { ProviderConfigStore.shared.db }
        let counts: (instances: Int, entries: Int, groups: Int)
        if let db {
            counts = await db.counts()
        } else {
            counts = (0, 0, 0)
        }
        return [
            "enabled": enabled,
            "migrationCompleted": migrated,
            "dbOpen": db != nil,
            "counts": [
                "instances": counts.instances,
                "entries": counts.entries,
                "groups": counts.groups,
            ],
        ] as [String: Any]
    }

    /// debug.sync.providerV3.setEnabled — kill-switch for v3. Off resumes
    /// V2-merge inbound + stops V3 outbound apply behaviors so a faulty
    /// v3 rollout can be reverted in the field without a code change.
    /// Takes effect immediately for inbound; outbound V3 records already
    /// in the dirty queue will still drain on the next send cycle
    /// (harmless — peers on v3 will just LWW them).
    private func handleProviderV3SetEnabled(params: [String: Any]) async -> Any {
        let enable = (params["enabled"] as? Bool) ?? true
        await MainActor.run { ProviderV3Bootstrap.setEnabled(enable) }
        return ["enabled": enable] as [String: Any]
    }

    /// debug.sync.dirtyByType — same shape as the existing dump.dirty
    /// section but standalone (no sessionId required).
    private func handleSyncDirtyByType() async -> Any {
        let summary = await ChatStore.shared.countDirtyRecords()
        return [
            "total": summary.total,
            "byType": summary.byType,
        ] as [String: Any]
    }

    /// Run an ad-hoc CKQuery against the iCloud private database for
    /// experimentation. Lets us probe which predicates / sorts the
    /// container's current schema actually accepts without needing to
    /// rebuild the app for each variant.
    ///
    /// Params:
    ///   recordType  (string, default "MessageV2")
    ///   zone        (string, default "minis-shared")
    ///   predicate   (string, default "TRUEPREDICATE")
    ///   predicateArgs (array of strings, optional — passed positionally
    ///                  via NSPredicate(format:argumentArray:); each
    ///                  string parsed as ISO8601 date if it looks like one,
    ///                  otherwise as raw string)
    ///   sortKey     (string, optional — e.g. "modificationDate",
    ///                "creationDate", "___modTime", "title")
    ///   sortAscending (bool, default false)
    ///   limit       (int, default 5)
    ///   desiredKeys (array of strings, optional — narrow the fetched
    ///                fields to keep response small; pass [] for ALL)
    ///
    /// Returns one of:
    ///   { "ok": true, "count": N, "records": [{ "name": ..., "type": ...,
    ///     "modificationDate": "...", "creationDate": "...", "fields": {...} }, ...] }
    ///   { "ok": false, "errorCode": Int, "ckCode": Int, "description": "..." }
    private func handleSyncCKQuery(params: [String: Any]) async -> Any {
        let recordType = (params["recordType"] as? String) ?? "MessageV2"
        let zoneName = (params["zone"] as? String) ?? "minis-shared"
        let predicateString = (params["predicate"] as? String) ?? "TRUEPREDICATE"
        let predicateArgs = params["predicateArgs"] as? [String] ?? []
        let sortKey = params["sortKey"] as? String
        let sortAscending = (params["sortAscending"] as? Bool) ?? false
        let limit = (params["limit"] as? Int) ?? 5
        let desiredKeysParam = params["desiredKeys"] as? [String]

        let predicate: NSPredicate
        if predicateArgs.isEmpty {
            predicate = NSPredicate(format: predicateString)
        } else {
            // Parse ISO date-like strings into Date so the predicate
            // can compare date fields properly.
            let iso = ISO8601DateFormatter()
            iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let parsed: [Any] = predicateArgs.map { s in
                if let d = iso.date(from: s) { return d as NSDate }
                if let d = ISO8601DateFormatter().date(from: s) { return d as NSDate }
                return s as NSString
            }
            predicate = NSPredicate(format: predicateString, argumentArray: parsed)
        }
        let query = CKQuery(recordType: recordType, predicate: predicate)
        if let key = sortKey {
            query.sortDescriptors = [NSSortDescriptor(key: key, ascending: sortAscending)]
        }
        let zoneID = CKRecordZone.ID(zoneName: zoneName)
        let container = CKContainer(identifier: "iCloud.com.openminis.app")
        do {
            let result = try await container.privateCloudDatabase.records(
                matching: query,
                inZoneWith: zoneID,
                desiredKeys: desiredKeysParam,
                resultsLimit: limit
            )
            var out: [[String: Any]] = []
            for (_, recResult) in result.matchResults {
                if case .success(let rec) = recResult {
                    var entry: [String: Any] = [
                        "name": rec.recordID.recordName,
                        "type": rec.recordType,
                    ]
                    if let mod = rec.modificationDate {
                        entry["modificationDate"] = ISO8601DateFormatter().string(from: mod)
                    }
                    if let created = rec.creationDate {
                        entry["creationDate"] = ISO8601DateFormatter().string(from: created)
                    }
                    if desiredKeysParam == nil || !(desiredKeysParam?.isEmpty ?? true) {
                        var fields: [String: String] = [:]
                        for key in rec.allKeys() {
                            let val = rec[key]
                            fields[key] = String(describing: val).prefix(120).description
                        }
                        entry["fields"] = fields
                    }
                    out.append(entry)
                }
            }
            return [
                "ok": true,
                "count": out.count,
                "records": out,
            ] as [String: Any]
        } catch {
            let nse = error as NSError
            let ck = error as? CKError
            return [
                "ok": false,
                "errorCode": nse.code,
                "ckCode": ck?.code.rawValue ?? -1,
                "description": error.localizedDescription,
            ] as [String: Any]
        }
    }

    /// List all CKRecordZone IDs in the user's private database. Used to
    /// diagnose v1 zone presence (device-<id>) and confirm v2 zones
    /// (minis-shared / minis-devices / minis-secrets) when migration
    /// counters look suspect.
    private func handleSyncAllZones() async -> Any {
        let container = CKContainer(identifier: "iCloud.com.openminis.app")
        do {
            let zones = try await container.privateCloudDatabase.allRecordZones()
            return [
                "ok": true,
                "count": zones.count,
                "zones": zones.map { ["zoneName": $0.zoneID.zoneName, "ownerName": $0.zoneID.ownerName] },
            ] as [String: Any]
        } catch {
            let nse = error as NSError
            let ck = error as? CKError
            return [
                "ok": false,
                "errorCode": nse.code,
                "ckCode": ck?.code.rawValue ?? -1,
                "description": error.localizedDescription,
            ] as [String: Any]
        }
    }

    /// Return this device's stable identifier — used as the zone-name
    /// suffix for v1 ("device-<id>") and the device record key for
    /// SyncDevice records.
    private func handleSyncDeviceId() async -> Any {
        return [
            "ok": true,
            "deviceId": DeviceIdentity.deviceId,
        ] as [String: Any]
    }

    /// Per-zone record + asset-byte rollup. Walks the user's private
    /// database zones, counts each known record type via paginated
    /// `desiredKeys=["fileSize"]` queries, and reports the totals.
    ///
    /// CloudKit does NOT expose a per-zone "used storage" API, so this
    /// is the closest we can get to "how much space does X zone hold".
    /// Each recordType run is one CKQuery + cursor pagination capped at
    /// 200 pages × 400 results = 80k records per type. Body bytes
    /// (record fields other than the asset) are NOT included — we only
    /// sum `fileSize` because that's the dominant cost for media-heavy
    /// types like SessionFile/SessionFileV2.
    ///
    /// Params:
    ///   zone (optional): single zone name. Defaults to all v2 zones
    ///     + the caller's own v1 zone.
    ///   recordTypes (optional): override the default per-zone type
    ///     list. Useful for ad-hoc probes.
    private func handleSyncZoneStats(params: [String: Any]) async -> Any {
        let containerId = "iCloud.com.openminis.app"
        let container = CKContainer(identifier: containerId)
        let db = container.privateCloudDatabase

        // Resolve zone list.
        let requestedZone = params["zone"] as? String
        var zonesToScan: [(name: String, types: [String])] = []
        if let z = requestedZone {
            // Caller supplied a zone — let them also pass recordTypes
            // explicitly, otherwise we guess by zone name.
            let userTypes = params["recordTypes"] as? [String]
            zonesToScan.append((z, userTypes ?? Self.guessRecordTypes(forZone: z)))
        } else {
            // Default: all v2 zones + own v1 zone.
            let myDeviceId = DeviceIdentity.deviceId
            zonesToScan = [
                ("minis-shared", ["SessionV2", "MessageV2", "CompactMarkerV2", "SessionFileV2", "SkillV2"]),
                ("minis-devices", ["SyncDeviceV2"]),
                ("minis-secrets", ["ProviderConfigV2", "EnvVarV2", "EnvVarItem"]),
                ("device-\(myDeviceId)", ["Session", "Message", "CompactMarker", "SessionFile"]),
            ]
        }

        struct TypeStat {
            var count: Int = 0
            var assetBytes: Int64 = 0
        }
        var zoneResults: [[String: Any]] = []
        var totalRecords = 0
        var totalAssetBytes: Int64 = 0

        for (zoneName, recordTypes) in zonesToScan {
            let zoneID = CKRecordZone.ID(zoneName: zoneName)
            var perType: [String: TypeStat] = [:]
            var zoneCount = 0
            var zoneBytes: Int64 = 0
            var zoneMissing = false
            for type in recordTypes {
                do {
                    let stat = try await Self.scanZoneTypeStats(
                        type: type, zoneID: zoneID, db: db)
                    perType[type] = TypeStat(count: stat.count, assetBytes: stat.assetBytes)
                    zoneCount += stat.count
                    zoneBytes += stat.assetBytes
                } catch let error as CKError where error.code == .zoneNotFound {
                    zoneMissing = true
                    break
                } catch let error as CKError where error.code == .unknownItem
                                                || error.code == .invalidArguments {
                    // Schema doesn't have this type in this zone — skip.
                    continue
                } catch {
                    perType[type] = TypeStat(count: -1, assetBytes: -1)
                }
            }
            totalRecords += zoneCount
            totalAssetBytes += zoneBytes
            var entry: [String: Any] = [
                "zone": zoneName,
                "exists": !zoneMissing,
                "recordCount": zoneCount,
                "assetBytes": zoneBytes,
                "assetSizeFormatted": ByteCountFormatter.string(
                    fromByteCount: zoneBytes, countStyle: .file),
            ]
            entry["byType"] = perType.mapValues { stat -> [String: Any] in
                [
                    "count": stat.count,
                    "assetBytes": stat.assetBytes,
                    "assetSizeFormatted": ByteCountFormatter.string(
                        fromByteCount: stat.assetBytes, countStyle: .file),
                ]
            }
            zoneResults.append(entry)
        }
        return [
            "ok": true,
            "zones": zoneResults,
            "totalRecords": totalRecords,
            "totalAssetBytes": totalAssetBytes,
            "totalAssetSizeFormatted": ByteCountFormatter.string(
                fromByteCount: totalAssetBytes, countStyle: .file),
        ] as [String: Any]
    }

    /// Fallback recordType list when a caller asks for a single zone
    /// without specifying types. Matches the v2 zone layout (see
    /// ICloudSharedZoneTransport.zoneByRecordType) and treats anything
    /// matching `device-*` as a v1 zone.
    private static func guessRecordTypes(forZone zone: String) -> [String] {
        switch zone {
        case "minis-shared":
            return ["SessionV2", "MessageV2", "CompactMarkerV2", "SessionFileV2", "SkillV2"]
        case "minis-devices":
            return ["SyncDeviceV2"]
        case "minis-secrets":
            return ["ProviderConfigV2", "EnvVarV2", "EnvVarItem"]
        default:
            if zone.hasPrefix("device-") {
                return ["Session", "Message", "CompactMarker", "SessionFile"]
            }
            return []
        }
    }

    /// Paginated scan of one (recordType, zone) tuple. Predicate uses a
    /// queryable field appropriate to each known type so CK doesn't
    /// reject the query with "field not queryable" (recordName is the
    /// default but isn't marked queryable on these schemas).
    private static func scanZoneTypeStats(
        type: String, zoneID: CKRecordZone.ID, db: CKDatabase
    ) async throws -> (count: Int, assetBytes: Int64) {
        let epoch = Date(timeIntervalSince1970: 946_684_800) as NSDate
        let predicate: NSPredicate
        let assetField: String?
        switch type {
        case "SessionFile":
            // No createdAt/updatedAt — use sessionId.
            predicate = NSPredicate(format: "sessionId != %@", "__none__" as NSString)
            assetField = "fileSize"
        case "SessionFileV2":
            predicate = NSPredicate(format: "sessionId != %@", "__none__" as NSString)
            assetField = "asset_size"
        case "SkillV2":
            predicate = NSPredicate(format: "updatedAt > %@", epoch)
            assetField = "assetSize"
        case "ProviderConfigV2", "EnvVarV2", "EnvVarItem", "SyncDeviceV2":
            // Singletons / device records carry only updatedAt — there
            // is no createdAt to anchor on.
            predicate = NSPredicate(format: "updatedAt > %@", epoch)
            assetField = nil
        default:
            predicate = NSPredicate(format: "createdAt > %@", epoch)
            assetField = nil
        }
        var desired: [String] = []
        if let f = assetField { desired.append(f) }

        let query = CKQuery(recordType: type, predicate: predicate)
        var cursor: CKQueryOperation.Cursor? = nil
        var count = 0
        var bytes: Int64 = 0
        for _ in 0..<200 {
            let resp: (matchResults: [(CKRecord.ID, Swift.Result<CKRecord, Error>)],
                       queryCursor: CKQueryOperation.Cursor?)
            if let cur = cursor {
                resp = try await db.records(continuingMatchFrom: cur,
                                            desiredKeys: desired,
                                            resultsLimit: CKQueryOperation.maximumResults)
            } else {
                resp = try await db.records(matching: query,
                                            inZoneWith: zoneID,
                                            desiredKeys: desired,
                                            resultsLimit: CKQueryOperation.maximumResults)
            }
            count += resp.matchResults.count
            if let field = assetField {
                for (_, r) in resp.matchResults {
                    if case .success(let rec) = r,
                       let n = rec[field] as? Int64 {
                        bytes += n
                    } else if case .success(let rec) = r,
                              let n = rec[field] as? Int {
                        bytes += Int64(n)
                    }
                }
            }
            if let next = resp.queryCursor { cursor = next } else { break }
        }
        return (count, bytes)
    }

    // MARK: - Browser Debug Handlers

    private func getBrowserPool() async throws -> BrowserTabPool {
        return try await MainActor.run {
            guard let pool = BrowserTabPool.debugInstance else {
                throw RPCError(code: -32000, message: "No active browser session")
            }
            return pool
        }
    }

    @MainActor private func resolveTab(pool: BrowserTabPool, params: [String: Any]) throws -> BrowserTabPool.Tab {
        let tabId = params["tabId"] as? Int ?? pool.selectedTabId
        guard let tab = pool.tabs.first(where: { $0.id == tabId }) else {
            if pool.tabs.isEmpty {
                throw RPCError(code: -32000, message: "No tabs open")
            }
            throw RPCError(code: -32602, message: "Tab not found: \(tabId)")
        }
        return tab
    }

    private func handleBrowserListTabs() async throws -> Any {
        let pool = try await getBrowserPool()
        return await MainActor.run {
            var tabs: [[String: Any]] = []
            for tab in pool.tabs {
                tabs.append([
                    "id": tab.id,
                    "url": tab.manager.currentURL,
                    "title": tab.manager.pageTitle,
                    "selected": tab.id == pool.selectedTabId,
                    "inUse": tab.inUse,
                ])
            }
            return ["tabs": tabs] as [String: Any]
        }
    }

    private func handleBrowserPageInfo(params: [String: Any]) async throws -> Any {
        let pool = try await getBrowserPool()
        let (webView, info): (WKWebView, [String: Any]) = try await MainActor.run {
            let tab = try resolveTab(pool: pool, params: params)
            let wv = tab.manager.webView
            let dict: [String: Any] = [
                "tabId": tab.id,
                "url": wv.url?.absoluteString ?? tab.manager.currentURL,
                "title": wv.title ?? tab.manager.pageTitle,
                "isLoading": tab.manager.isLoading,
                "canGoBack": wv.canGoBack,
                "canGoForward": wv.canGoForward,
            ]
            return (wv, dict)
        }
        var result = info
        // Get viewport/scroll info via async JS
        let jsSource = "JSON.stringify({scrollX:window.scrollX,scrollY:window.scrollY,pageWidth:document.documentElement.scrollWidth,pageHeight:document.documentElement.scrollHeight,viewportWidth:window.innerWidth,viewportHeight:window.innerHeight,readyState:document.readyState})"
        if let raw = try? await webView.evaluateJavaScript(jsSource) as? String,
           let data = raw.data(using: .utf8),
           let viewport = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            result["viewport"] = viewport
        }
        return result
    }

    /// [T-browser-bg-stuck-diag] Probe whether a WKWebView whose WebContent JS
    /// thread is pinned (via a page with an infinite loop) can be recovered, and
    /// whether the tab is reusable afterwards. Answers the load-bearing question
    /// for the fix design: once we detect a stuck browser op, can we actually
    /// cancel it and re-issue, or is the tab poisoned?
    ///
    /// Params:
    ///   url    — page to load (should pin the JS thread). Required.
    ///   method — "stopLoading" | "loadBlank" | "reload" | "closeReopen" | "none"
    ///   tabId  — optional; else current tab.
    ///
    /// Sequence: fire a bounded evaluateJavaScript against the (pinned) page and
    /// confirm it hangs; apply the cancel `method`; then try a fresh
    /// evaluateJavaScript ("1+1") with a short bound and report whether it
    /// returns — i.e. whether the WebContent is responsive again.
    private func handleBrowserCancelTest(params: [String: Any]) async throws -> Any {
        guard let url = params["url"] as? String, !url.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'url' is required")
        }
        let method = params["method"] as? String ?? "stopLoading"
        let pool = try await getBrowserPool()

        // Bounded JS eval helper (wall-clock GCD timer, mirrors the manager's).
        func boundedEval(_ webView: WKWebView, _ js: String, _ timeout: TimeInterval) async -> (ok: Bool, ms: Int, value: String) {
            let start = Date()
            let done = NSLock()
            var finished = false
            return await withCheckedContinuation { (cont: CheckedContinuation<(Bool, Int, String), Never>) in
                let timer = DispatchSource.makeTimerSource(queue: .global())
                timer.schedule(deadline: .now() + timeout)
                func finish(_ ok: Bool, _ v: String) {
                    done.lock(); if finished { done.unlock(); return }; finished = true; done.unlock()
                    timer.cancel()
                    cont.resume(returning: (ok, Int(Date().timeIntervalSince(start) * 1000), v))
                }
                timer.setEventHandler { finish(false, "TIMEOUT") }
                timer.resume()
                Task { @MainActor in
                    webView.evaluateJavaScript(js) { value, error in
                        if let error { finish(false, "err:\(error.localizedDescription.prefix(40))") }
                        else { finish(true, "\(value ?? "nil")") }
                    }
                }
            }
        }

        // 1. Load the pinning page (don't await full navigation — it may hang).
        let webView: WKWebView = try await MainActor.run {
            let tab = try resolveTab(pool: pool, params: params)
            if let u = URL(string: url) {
                tab.manager.webView.load(URLRequest(url: u))
            }
            return tab.manager.webView
        }
        // Give the page a moment to start its busy loop.
        try? await Task.sleep(nanoseconds: 2_000_000_000)

        // 2. Confirm it's pinned: a trivial eval should time out (3s bound).
        let before = await boundedEval(webView, "1+1", 3)

        // 3. Apply the cancel method.
        var methodNote = method
        await MainActor.run {
            switch method {
            case "stopLoading":
                webView.stopLoading()
            case "loadBlank":
                webView.load(URLRequest(url: URL(string: "about:blank")!))
            case "reload":
                webView.reload()
            case "closeReopen":
                // Close the tab (drops the WKWebView) and open a fresh one.
                if let tab = try? resolveTab(pool: pool, params: params) {
                    _ = pool.closeTab(id: tab.id)
                }
                methodNote = "closeReopen (tab dropped)"
            case "none":
                break
            default:
                methodNote = "unknown:\(method)"
            }
        }
        // Let the cancel take effect.
        try? await Task.sleep(nanoseconds: 2_000_000_000)

        // 4. Probe recovery. For closeReopen, open a fresh tab + simple page.
        let after: (ok: Bool, ms: Int, value: String)
        var identityNote = ""
        if method == "closeReopen" {
            let oldPtr = ObjectIdentifier(webView)
            let freshWV: WKWebView? = await MainActor.run {
                _ = pool.newTab(url: "about:blank")
                // newTab sets selectedTabId to the new tab — resolve THAT, not
                // tabs.first (which may still be the pinned tab).
                let sel = pool.selectedTabId
                return pool.tabs.first(where: { $0.id == sel })?.manager.webView
            }
            if let fresh = freshWV {
                let same = ObjectIdentifier(fresh) == oldPtr
                identityNote = same ? " (WARNING: same WKWebView object as pinned!)" : " (distinct new WKWebView)"
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                after = await boundedEval(fresh, "1+1", 3)
            } else {
                after = (false, 0, "no fresh tab")
            }
        } else {
            after = await boundedEval(webView, "1+1", 3)
        }

        return [
            "url": url,
            "method": methodNote,
            "beforeCancel": ["responsive": before.ok, "ms": before.ms, "value": before.value],
            "afterCancel": ["responsive": after.ok, "ms": after.ms, "value": after.value, "identity": identityNote],
            "verdict": before.ok
                ? "page did NOT pin (test invalid — before eval succeeded)"
                : (after.ok ? "RECOVERED: \(methodNote) unblocked the WebView" : "STILL STUCK: \(methodNote) did NOT recover the WebView"),
        ]
    }

    /// [T-browser-bg-stuck-diag] Fire N concurrent navigate actions through the
    /// REAL agent path (`execute(singleTab:false)`, exactly what
    /// AIChatViewModel+ConcurrentTools does) and report which tab each landed on.
    /// Directly measures the concurrent-navigate tab race: if fan-out works, each
    /// gets a distinct tab; if the race bites, they pile onto one tab and
    /// serialize.
    private func handleBrowserConcurrentTest(params: [String: Any]) async throws -> Any {
        let url = params["url"] as? String ?? "about:blank"
        let count = min(max(params["count"] as? Int ?? 2, 2), 4)
        let pool = try await getBrowserPool()

        let results = await withTaskGroup(of: (Int, Int?, Double, Bool).self) { group in
            for i in 0..<count {
                group.addTask {
                    let start = Date()
                    let json = "{\"action\":\"navigate\",\"url\":\"\(url)\"}"
                    guard let input = BrowserActionInput.parse(from: json) else {
                        return (i, nil, 0, false)
                    }
                    do {
                        let r = try await pool.execute(action: input, singleTab: false)
                        return (i, r.tabId, Date().timeIntervalSince(start), r.success)
                    } catch {
                        return (i, nil, Date().timeIntervalSince(start), false)
                    }
                }
            }
            var out: [(Int, Int?, Double, Bool)] = []
            for await r in group { out.append(r) }
            return out
        }

        let perAction = results.sorted { $0.0 < $1.0 }.map { (i, tab, dur, ok) -> [String: Any] in
            ["index": i, "tabId": tab as Any, "durationMs": Int(dur * 1000), "success": ok]
        }
        let tabs = results.compactMap { $0.1 }
        let distinct = Set(tabs).count
        return [
            "url": url,
            "count": count,
            "perAction": perAction,
            "distinctTabs": distinct,
            "verdict": distinct == count
                ? "OK: all \(count) actions got distinct tabs (fan-out worked)"
                : "RACE: \(count) concurrent navigates collapsed onto \(distinct) tab(s) — they serialized instead of running in parallel",
        ]
    }

    private func handleBrowserExecuteJS(params: [String: Any]) async throws -> Any {
        guard let script = params["script"] as? String, !script.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'script' is required")
        }
        let pool = try await getBrowserPool()
        let webView: WKWebView = try await MainActor.run {
            let tab = try resolveTab(pool: pool, params: params)
            return tab.manager.webView
        }
        let wrapped = "(function(){\(script)})()"
        do {
            let raw = try await webView.evaluateJavaScript(wrapped)
            if let raw {
                return ["result": raw]
            }
            return ["result": NSNull()] as [String: Any]
        } catch {
            let nsError = error as NSError
            let detail = nsError.userInfo["WKJavaScriptExceptionMessage"] as? String
                ?? error.localizedDescription
            throw RPCError(code: -32000, message: "JavaScript error: \(detail)")
        }
    }

    private func evaluateBrowserJS(_ js: String, params: [String: Any]) async throws -> Any {
        let pool = try await getBrowserPool()
        let webView: WKWebView = try await MainActor.run {
            let tab = try resolveTab(pool: pool, params: params)
            return tab.manager.webView
        }
        do {
            let raw = try await webView.evaluateJavaScript(js)
            if let str = raw as? String,
               let data = str.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return json
            }
            return ["result": String(describing: raw ?? "null")]
        } catch {
            throw RPCError(code: -32000, message: "JavaScript error: \(error.localizedDescription)")
        }
    }

    private func handleBrowserGetReadable(params: [String: Any]) async throws -> Any {
        return try await evaluateBrowserJS(BrowserUseJS.getReadable(), params: params)
    }

    private func handleBrowserGetText(params: [String: Any]) async throws -> Any {
        let selector = params["selector"] as? String
        return try await evaluateBrowserJS(BrowserUseJS.getText(selector: selector), params: params)
    }

    private func handleBrowserScreenshot(params: [String: Any]) async throws -> Any {
        let pool = try await getBrowserPool()
        let webView: WKWebView = try await MainActor.run {
            let tab = try resolveTab(pool: pool, params: params)
            return tab.manager.webView
        }
        let config = WKSnapshotConfiguration()
        let image = try await webView.takeSnapshot(configuration: config)
        guard let pngData = image.pngData() else {
            throw RPCError(code: -32000, message: "Failed to encode screenshot")
        }
        return [
            "base64": pngData.base64EncodedString(),
            "size": pngData.count,
            "encoding": "png",
        ] as [String: Any]
    }

    // MARK: - File System Handlers

    private func resolveHostPath(_ path: String) -> URL {
        let stripped = path.hasPrefix("/") ? String(path.dropFirst()) : path
        if stripped.isEmpty {
            return RootfsManager.shared.dataPath
        }
        // Support Library/ prefix to access app Library directory
        if stripped.hasPrefix("Library/") {
            let fm = FileManager.default
            let library = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
            let relative = String(stripped.dropFirst("Library/".count))
            return relative.isEmpty ? library : library.appendingPathComponent(relative)
        }
        // Support Documents/ prefix to access app Documents directory
        if stripped.hasPrefix("Documents/") {
            let fm = FileManager.default
            let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first!
            let relative = String(stripped.dropFirst("Documents/".count))
            return relative.isEmpty ? docs : docs.appendingPathComponent(relative)
        }
        // Support AppGroup/ prefix to access the shared App Group container,
        // where the FileProvider extension writes its diagnostic log.
        if stripped.hasPrefix("AppGroup/") {
            let fm = FileManager.default
            if let container = fm.containerURL(forSecurityApplicationGroupIdentifier: "group.com.openminis.app") {
                let relative = String(stripped.dropFirst("AppGroup/".count))
                return relative.isEmpty ? container : container.appendingPathComponent(relative)
            }
        }
        return RootfsManager.shared.dataPath.appendingPathComponent(stripped)
    }

    private func guardPathTraversal(_ path: String) throws {
        let components = (path as NSString).pathComponents
        if components.contains("..") {
            throw RPCError(code: -32602, message: "Invalid path: '..' components not allowed")
        }
    }

    private func handleLS(params: [String: Any]) throws -> Any {
        let path = params["path"] as? String ?? "/"
        try guardPathTraversal(path)

        let recursive = params["recursive"] as? Bool ?? false
        let maxDepth = params["maxDepth"] as? Int ?? 3

        let url = resolveHostPath(path)
        let fm = FileManager.default

        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else {
            throw RPCError(code: -32602, message: "Not a directory: \(path)")
        }

        if recursive {
            return try listRecursive(url: url, fm: fm, depth: 0, maxDepth: maxDepth, basePath: path)
        }

        guard let contents = try? fm.contentsOfDirectory(at: url, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey, .isDirectoryKey, .isSymbolicLinkKey]) else {
            throw RPCError(code: -32000, message: "Cannot read directory: \(path)")
        }

        let iso = ISO8601DateFormatter()
        return contents.map { entry -> [String: Any] in
            entryDict(url: entry, fm: fm, iso: iso)
        }
    }

    private func listRecursive(url: URL, fm: FileManager, depth: Int, maxDepth: Int, basePath: String) throws -> [[String: Any]] {
        guard let contents = try? fm.contentsOfDirectory(at: url, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey, .isDirectoryKey, .isSymbolicLinkKey]) else {
            return []
        }
        let iso = ISO8601DateFormatter()
        var result: [[String: Any]] = []
        for entry in contents {
            var dict = entryDict(url: entry, fm: fm, iso: iso)
            if dict["type"] as? String == "directory" && depth < maxDepth {
                let children = try listRecursive(url: entry, fm: fm, depth: depth + 1, maxDepth: maxDepth, basePath: basePath)
                dict["children"] = children
            }
            result.append(dict)
        }
        return result
    }

    private func entryDict(url: URL, fm: FileManager, iso: ISO8601DateFormatter) -> [String: Any] {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .isDirectoryKey, .isSymbolicLinkKey])
        let isSymlink = values?.isSymbolicLink ?? false
        let isDirectory = values?.isDirectory ?? false
        let type: String = isSymlink ? "symlink" : (isDirectory ? "directory" : "file")
        var dict: [String: Any] = [
            "name": url.lastPathComponent,
            "type": type,
        ]
        if let size = values?.fileSize {
            dict["size"] = size
        }
        if let mod = values?.contentModificationDate {
            dict["modified"] = iso.string(from: mod)
        }
        return dict
    }

    private func handleReadFile(params: [String: Any]) throws -> Any {
        guard let path = params["path"] as? String, !path.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'path' is required")
        }
        try guardPathTraversal(path)

        let forceBase64 = params["base64"] as? Bool ?? false
        let offset = params["offset"] as? Int
        let limit = params["limit"] as? Int
        let maxSize = 524_288_000 // 500MB

        let url = resolveHostPath(path)
        let fm = FileManager.default

        guard fm.fileExists(atPath: url.path) else {
            throw RPCError(code: -32602, message: "File not found: \(path)")
        }

        let attrs = try fm.attributesOfItem(atPath: url.path)
        let fileSize = attrs[.size] as? Int ?? 0

        if offset == nil && limit == nil && fileSize > maxSize {
            throw RPCError(code: -32000, message: "File too large (\(fileSize) bytes). Use offset/limit for partial reads.")
        }

        let fileHandle = try FileHandle(forReadingFrom: url)
        defer { fileHandle.closeFile() }

        if let off = offset {
            fileHandle.seek(toFileOffset: UInt64(off))
        }

        let readLimit = limit ?? min(fileSize, maxSize)
        let data = fileHandle.readData(ofLength: readLimit)

        var result: [String: Any] = ["size": fileSize]

        if forceBase64 {
            result["content"] = data.base64EncodedString()
            result["encoding"] = "base64"
        } else if let text = String(data: data, encoding: .utf8), text.utf8.count == data.count {
            result["content"] = text
            result["encoding"] = "utf8"
        } else {
            result["content"] = data.base64EncodedString()
            result["encoding"] = "base64"
        }

        return result
    }

    // MARK: - File write (with fakefs meta.db registration)

    /// Write a file into the app sandbox, then optionally register it in the
    /// fakefs meta.db so the iSH guest can see it.
    /// Params:
    ///   - path: sandbox-relative path (resolved via `resolveHostPath`).
    ///     For /tmp, /usr/local/... the host path lives under `rootfs/data/`.
    ///   - content: payload string
    ///   - encoding: "utf8" (default) | "base64"
    ///   - overwrite: bool (default true). If false and file exists, errors.
    ///   - registerInGuest: bool (default true). Inserts into meta.db so
    ///     iSH-guest processes (ffmpeg offload, shell_execute) can see it.
    ///   - mode: octal file mode e.g. 0o644 (optional, default 0o644)
    private func handleWriteFile(params: [String: Any]) throws -> Any {
        guard let path = params["path"] as? String, !path.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'path' is required")
        }
        guard let content = params["content"] as? String else {
            throw RPCError(code: -32602, message: "Invalid params: 'content' is required")
        }
        try guardPathTraversal(path)

        let encoding = (params["encoding"] as? String) ?? "utf8"
        let overwrite = params["overwrite"] as? Bool ?? true
        let registerInGuest = params["registerInGuest"] as? Bool ?? true
        // Accept either "mode": 420 (decimal) or "mode": "0644" (octal string).
        let mode: UInt16 = {
            if let m = params["mode"] as? Int, m > 0 { return UInt16(m & 0xFFFF) }
            if let s = params["mode"] as? String, !s.isEmpty {
                let trimmed = s.hasPrefix("0o") ? String(s.dropFirst(2))
                              : (s.hasPrefix("0") && s.count > 1 ? String(s.dropFirst()) : s)
                if let parsed = UInt16(trimmed, radix: 8) { return parsed }
            }
            return 0o644
        }()

        let data: Data
        switch encoding {
        case "utf8":
            guard let d = content.data(using: .utf8) else {
                throw RPCError(code: -32602, message: "Invalid utf8 content")
            }
            data = d
        case "base64":
            guard let d = Data(base64Encoded: content) else {
                throw RPCError(code: -32602, message: "Invalid base64 content")
            }
            data = d
        default:
            throw RPCError(code: -32602, message: "Invalid encoding: \(encoding). Use 'utf8' or 'base64'.")
        }

        let url = resolveHostPath(path)
        let fm = FileManager.default

        if !overwrite && fm.fileExists(atPath: url.path) {
            throw RPCError(code: -32000, message: "File exists and overwrite=false: \(path)")
        }

        // Ensure parent dir exists on the host side.
        let parentDir = url.deletingLastPathComponent()
        try fm.createDirectory(at: parentDir, withIntermediateDirectories: true)

        try data.write(to: url, options: .atomic)
        // Apply requested permissions.
        try? fm.setAttributes([.posixPermissions: NSNumber(value: mode)], ofItemAtPath: url.path)

        // Compute the Linux-guest path by stripping the rootfs data/ prefix.
        // `resolveHostPath` writes into `<rootfsDataPath>/<relative>` for bare
        // paths, so the guest-visible path is `/<relative>`.
        let rootfsDataPath = RootfsManager.shared.dataPath.path
        var linuxPath: String? = nil
        if url.path.hasPrefix(rootfsDataPath) {
            let suffix = String(url.path.dropFirst(rootfsDataPath.count))
            linuxPath = suffix.hasPrefix("/") ? suffix : "/" + suffix
        }

        var registered = false
        if registerInGuest, let guestPath = linuxPath {
            let fakefsMode: UInt32 = 0o100000 | UInt32(mode)  // S_IFREG | mode
            RootfsManager.shared.ensureParentDirsInMetaDB(for: guestPath)
            RootfsManager.shared.ensureFakefsMetadata(for: guestPath, isDirectory: false, mode: fakefsMode)
            registered = true
        }

        return [
            "ok": true,
            "path": path,
            "hostPath": url.path,
            "linuxPath": linuxPath ?? NSNull(),
            "size": data.count,
            "registeredInGuest": registered,
        ] as [String: Any]
    }

    // MARK: - Shell execute (synchronous, returns stdout/stderr)

    /// Run a shell command inside the iSH guest. Blocks until the process
    /// exits or the timeout fires. Returns captured stdout/stderr plus exit
    /// code and duration. Intended for automated debugging / test harnesses
    /// — not for interactive sessions.
    ///
    /// Params:
    ///   - command: shell command string (passed to `/bin/sh -c` in default
    ///     mode, or written to `/bin/sh`'s stdin pipe when `stdinScript=true`
    ///     to mirror the agent's `ISHExecutionCoordinator.runCommand` path).
    ///   - timeout: max wait seconds (default 60, clamped to 600)
    ///   - stdinScript: when true, run `/bin/sh` with no argv and feed the
    ///     command as a script via stdin (the agent tool path). When false
    ///     (default), use `/bin/sh -c <command>` (the debug/test path).
    ///   - env: optional [String:String] of environment overrides to pass
    ///     to the child process (mirrors EnvVarStore injection in agent
    ///     path when `stdinScript=true`).
    private func handleShellExecute(params: [String: Any]) async throws -> Any {
        guard let command = params["command"] as? String, !command.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'command' is required")
        }
        let rawTimeout = (params["timeout"] as? NSNumber)?.doubleValue ?? 60
        let timeout = max(1, min(600, rawTimeout))
        let stdinScript = params["stdinScript"] as? Bool ?? false
        let env = params["env"] as? [String: String]
        // Optional sessionId to stamp the shell's task group with the matching
        // fs_context, so /var/minis/{offloads,attachments,workspace,browser}
        // route to that session's bucket. Omit (or pass nil) for the default
        // global view (fs_context = 0).
        let sessionId = params["sessionId"] as? String
        let fsContext: UInt64 = sessionId.map { MinisFsRouter.shared.context(for: $0) } ?? 0

        // Run off the main thread (blocks on semaphore).
        let (result, didTimeout): (ISHShellExecutionResult?, Bool) = await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                if stdinScript {
                    // Mirror agent path: sh with no argv, script via stdin.
                    let ctx = ISHShellExecutionContextBox()
                    let data = command.data(using: .utf8) ?? Data()
                    let pid = ISHShellExecutor.executeExecutable(
                        "/bin/sh",
                        arguments: nil,
                        environment: env,
                        stdinData: data,
                        fsContext: fsContext,
                        lineCallback: nil,
                        completion: { res in
                            ctx.result = res
                            ctx.sem.signal()
                        }
                    )
                    if pid < 0 {
                        continuation.resume(returning: (nil, false))
                        return
                    }
                    let waitResult = ctx.sem.wait(timeout: .now() + timeout)
                    if waitResult == .timedOut {
                        ISHShellExecutor.killProcess(pid, withSignal: 9)
                        // [T-ish-shell-timeout-leak] Same self-heal as the
                        // agent path: don't leave teardown to an exit
                        // notification that a zombie-reaped task never sends,
                        // or this context and its reader threads leak for the
                        // life of the process.
                        ISHShellExecutor.finalizeTimedOutPid(pid)
                        continuation.resume(returning: (ctx.result, true))
                        return
                    }
                    continuation.resume(returning: (ctx.result, false))
                } else {
                    let res = ISHShellExecutor.executeCommandSync(
                        command,
                        timeout: timeout,
                        lineCallback: nil
                    )
                    continuation.resume(returning: (res, res.error == .timeout))
                }
            }
        }

        guard let result else {
            return [
                "exitCode": -1,
                "pid": 0,
                "error": "processCreationFailed",
                "stdout": "",
                "stderr": "",
                "duration": 0,
                "timedOut": false,
            ] as [String: Any]
        }

        let errorName: String
        if didTimeout {
            errorName = "timeout"
        } else {
            switch result.error {
            case .none: errorName = "none"
            case .processCreationFailed: errorName = "processCreationFailed"
            case .execFailed: errorName = "execFailed"
            case .timeout: errorName = "timeout"
            case .cancelled: errorName = "cancelled"
            @unknown default: errorName = "unknown"
            }
        }

        // [T-ios-debugrpc-json-nsexception-abort] Cap stdout/stderr at the
        // SOURCE. Unbounded shell output flowed straight into the JSON writer;
        // a large-enough payload failed CFString reallocation inside
        // _convertJSONString (NSException → abort). 4 MB per stream is far
        // beyond any diagnostic need; callers get the tail truncated with an
        // explicit marker + the original byte counts so nothing fails silently.
        let stdoutCapped = Self.capRPCOutput(result.output)
        let stderrCapped = Self.capRPCOutput(result.errorOutput)
        var resp: [String: Any] = [
            "exitCode": Int(result.exitCode),
            "pid": Int(result.pid),
            "error": errorName,
            "stdout": stdoutCapped.text,
            "stderr": stderrCapped.text,
            "duration": result.duration,
            "timedOut": didTimeout || result.error == .timeout,
        ]
        if stdoutCapped.truncated { resp["stdoutTruncatedFrom"] = stdoutCapped.originalCount }
        if stderrCapped.truncated { resp["stderrTruncatedFrom"] = stderrCapped.originalCount }
        return resp
    }

    /// Truncate an RPC output string to a serialization-safe size (UTF-16
    /// unit cap ≈ 4 MB of UTF-8 for ASCII-ish shell output), keeping the HEAD
    /// (build logs / command output lead with the useful part) and appending
    /// an explicit truncation marker. [T-ios-debugrpc-json-nsexception-abort]
    private static func capRPCOutput(_ s: String, limit: Int = 4_000_000) -> (text: String, truncated: Bool, originalCount: Int) {
        let count = s.utf16.count
        guard count > limit else { return (s, false, count) }
        let head = String(s.prefix(limit))
        return (head + "\n…[truncated \(count - limit) of \(count) UTF-16 units by debug server]", true, count)
    }

    /// Holds state for the stdin-script path's async-to-sync bridge.
    private final class ISHShellExecutionContextBox {
        let sem = DispatchSemaphore(value: 0)
        var result: ISHShellExecutionResult?
    }

    private func handleAppInfo(params: [String: Any] = [:]) -> Any {
        let fm = FileManager.default
        let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dataPath = RootfsManager.shared.dataPath
        let bundlePath = Bundle.main.bundlePath
        let library = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!

        // Binary modification date as build date
        let executableURL = Bundle.main.executableURL
        let buildDate: String
        if let url = executableURL,
           let attrs = try? fm.attributesOfItem(atPath: url.path),
           let mod = attrs[.modificationDate] as? Date {
            let fmt = ISO8601DateFormatter()
            fmt.formatOptions = [.withInternetDateTime]
            buildDate = fmt.string(from: mod)
        } else {
            buildDate = "unknown"
        }

        var result: [String: Any] = [
            "documentsPath": docs.path,
            "libraryPath": library.path,
            "dataPath": dataPath.path,
            "rootfsPath": docs.appendingPathComponent("alpine-rootfs").path,
            "bundlePath": bundlePath,
            // [T-ish-shell-timeout-leak] Reader-thread accounting. `liveReaders`
            // should sit at 0 between commands; a value that only grows is the
            // shell timeout leak recurring, and it ends with the app unable to
            // run any command until the device restarts.
            "shellLeakGuard": ISHShellExecutor.leakGuardStatus(),
            "buildDate": buildDate,
        ]

        // Disk usage requires recursive directory walks that can be very slow
        // when alpine-rootfs/data has millions of files. Off by default; pass
        // {"includeDiskUsage": true} to opt in.
        if (params["includeDiskUsage"] as? Bool) == true {
            func dirSize(_ url: URL) -> Int64 {
                guard let enumerator = fm.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]) else { return 0 }
                var total: Int64 = 0
                for case let fileURL as URL in enumerator {
                    if let size = try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                        total += Int64(size)
                    }
                }
                return total
            }
            result["diskUsage"] = [
                "documents": dirSize(docs),
                "rootfsData": dirSize(dataPath),
                "bundle": dirSize(URL(fileURLWithPath: bundlePath)),
            ]
        }
        return result
    }

    // MARK: - Touch / Input / Scroll Handlers

    private func handleTap(params: [String: Any]) async throws -> Any {
        // Priority: identifier > label > text (partial) > address > x/y. The
        // accessibility-based paths sidestep the SwiftUI hit-test problem —
        // a synthetic UITouch dispatched at a coordinate often lands on the
        // containing ListCollectionViewCell but never reaches SwiftUI's
        // internal tap gesture. `accessibilityActivate()` DOES reach it,
        // because SwiftUI's Button/onTapGesture wire that up directly.
        //
        // Outer timeout: even though the inspector's walk is budget-bounded
        // internally (1.5s), an already-stuck main thread from prior work
        // could still delay THIS request's MainActor hop indefinitely. We
        // wrap the whole hop in a race against a 3s deadline so the client
        // ALWAYS gets a response within a bounded time.
        //
        // First-line strategy for text/label/identifier: forward to iCTRL's
        // find_and_tap RPC on 127.0.0.1:8200. iCTRL is a UI test bundle so
        // it has XCUIElement.tap(), which drives the OS's real touch
        // pipeline and works on SwiftUI Lists / hosting-view content that
        // MinisApp's app-embedded synthesis can't reach. iCTRL's isLoopback
        // check bypasses its bearer auth for us. If iCTRL isn't running (or
        // returns "no element found") we fall through to the existing app-
        // embedded escalation ladder — behaviour unchanged when the tool
        // isn't installed.
        // Order of preference for element-lookup taps:
        // 1. App-embedded ladder FIRST. If it engages `accessibilityActivate`
        //    (buttons, .button-trait AX nodes), `didSelectItemAt`, or
        //    UIControl.sendActions, the button's REAL handler runs — this is
        //    what worked for the "Default Models" toolbar chip.
        // 2. Only if the local ladder falls back to synthesized coordinate
        //    tap (`via` starts with "coordinate" or ends with
        //    "→coordinate(...)"), we escalate to iCTRL's XCUIElement.tap.
        //    That's the path for SwiftUI List rows / hosting-view content
        //    whose activation isn't published via the accessibility API.
        // 3. If iCTRL is unreachable or reports no matching element, the
        //    local result (the synthesized-tap envelope) is returned as-is.
        func localResultUsedCoordinateFallback(_ result: [String: Any]) -> Bool {
            guard let via = result["via"] as? String else { return false }
            if via == "coordinate" { return true }
            if via.hasSuffix("→coordinate") { return true }
            // Also matches "collectionCell:1-0→coordinate(207,541)" etc.
            if via.contains("→coordinate(") { return true }
            return false
        }
        func tryICTRLThenReturn(local: [String: Any]) async -> Any {
            if localResultUsedCoordinateFallback(local),
               let forwarded = await Self.forwardTapToICTRL(params: params) {
                return forwarded
            }
            return local
        }

        if let identifier = params["identifier"] as? String, !identifier.isEmpty {
            let local = await Self.withMainActorTimeout(strategy: "identifier=\(identifier)") {
                self.performTargetTap(strategy: "identifier=\(identifier)") {
                    self.inspector.findTarget(identifier: identifier)
                }
            }
            return await tryICTRLThenReturn(local: local)
        }
        if let label = params["label"] as? String, !label.isEmpty {
            let local = await Self.withMainActorTimeout(strategy: "label=\(label)") {
                self.performTargetTap(strategy: "label=\(label)") {
                    self.inspector.findTarget(text: label, partial: false)
                }
            }
            return await tryICTRLThenReturn(local: local)
        }
        if let text = params["text"] as? String, !text.isEmpty {
            let partial = (params["partial"] as? Bool) ?? true
            let local = await Self.withMainActorTimeout(strategy: "text=\(text) partial=\(partial)") {
                self.performTargetTap(strategy: "text=\(text) partial=\(partial)") {
                    self.inspector.findTarget(text: text, partial: partial)
                }
            }
            return await tryICTRLThenReturn(local: local)
        }

        guard let x = params["x"] as? Double, let y = params["y"] as? Double else {
            throw RPCError(code: -32602, message: "Invalid params: pass 'identifier', 'label', 'text' (with optional 'partial'), or numeric 'x' + 'y'")
        }
        let point = CGPoint(x: x, y: y)

        // Optional: tap on a specific view by address
        if let address = params["address"] as? String, !address.isEmpty {
            return await MainActor.run {
                guard let view = self.inspector.findView(byAddress: address) else {
                    return ["ok": false, "error": "View not found at address \(address)"] as [String: Any]
                }
                AutoTouch.simulateTap(at: point, in: view)
                return ["ok": true, "point": ["x": x, "y": y], "view": address, "via": "coordinate"] as [String: Any]
            }
        }

        await MainActor.run {
            AutoTouch.simulateTap(at: point, in: nil)
        }
        return ["ok": true, "point": ["x": x, "y": y], "via": "coordinate"] as [String: Any]
    }

    /// URL of iCTRL's JSON-RPC endpoint on this device. iCTRL is a co-
    /// installed XCTest automation service (bundle com.openminis.ictrl,
    /// port 8200). It runs inside a UI test runner so it can drive
    /// XCUIElement.tap() — which we can't from the app process.
    /// Loopback auth exemption on iCTRL side means we don't send a token.
    private static let ictrlRPCURL = URL(string: "http://127.0.0.1:8200/rpc")!

    /// Try iCTRL's find_and_tap. Maps our tap params to iCTRL's schema:
    ///   identifier / label → element_id + match:"exact"
    ///   text[, partial]    → element_id + match:"contains" | "exact"
    /// Returns iCTRL's result envelope when the call succeeded (with a
    /// synthesised via:"ictrl" so callers can see the mechanism). Returns
    /// nil when iCTRL isn't reachable or replied that no element matched
    /// — so the caller falls through to the app-embedded escalation.
    static func forwardTapToICTRL(params: [String: Any]) async -> [String: Any]? {
        // Assemble find_and_tap params from whichever field was supplied.
        var elementId: String = ""
        var matchMode: String = "exact"
        var strategy: String = ""
        if let identifier = params["identifier"] as? String, !identifier.isEmpty {
            elementId = identifier; strategy = "identifier=\(identifier)"
        } else if let label = params["label"] as? String, !label.isEmpty {
            elementId = label; strategy = "label=\(label)"
        } else if let text = params["text"] as? String, !text.isEmpty {
            let partial = (params["partial"] as? Bool) ?? true
            elementId = text
            matchMode = partial ? "contains" : "exact"
            strategy = "text=\(text) partial=\(partial)"
        } else {
            return nil
        }

        // iCTRL defaults to querying whatever app is currently bound (or
        // SpringBoard if none). Since debug.tap is called BY MinisApp asking
        // to tap something in ITSELF, pin the query to our own bundle id
        // unless the caller explicitly overrides via ictrl_bundle_id.
        let bundleId = (params["ictrl_bundle_id"] as? String) ?? Bundle.main.bundleIdentifier ?? "com.openminis.app"
        let rpcParams: [String: Any] = [
            "element_id": elementId,
            "match": matchMode,
            "bundle_id": bundleId,
        ]
        let payload: [String: Any] = [
            "jsonrpc": "2.0",
            "id": 1,
            "method": "find_and_tap",
            "params": rpcParams,
        ]
        guard let body = try? JSONSerialization.data(withJSONObject: payload) else { return nil }

        var request = URLRequest(url: ictrlRPCURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        // find_and_tap can take up to ~4s (locate + native XCUIElement.tap).
        // Give it 8s total headroom; we still return an error envelope on
        // timeout without hanging the client.
        request.timeoutInterval = 8.0

        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 8.0
        config.timeoutIntervalForResource = 10.0
        config.waitsForConnectivity = false
        let session = URLSession(configuration: config)

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { return nil }
            guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            // Success envelope from iCTRL. Return it verbatim with a stamp
            // so debug.tap callers can see the mechanism.
            if http.statusCode == 200, let result = obj["result"] as? [String: Any] {
                var out = result
                out["ok"] = true
                out["via"] = "ictrl"
                out["strategy"] = strategy
                return out
            }
            // iCTRL replied but couldn't tap (bad token, no element, etc.).
            // "No element matching" → nil so we retry with the app-embedded
            // path. Anything else we surface so the caller sees why iCTRL
            // rejected (auth failure, invalid params, method missing).
            if let err = obj["error"] as? [String: Any],
               let msg = err["message"] as? String {
                if msg.contains("no element matching") || msg.contains("No element matching") {
                    return nil
                }
                return [
                    "ok": false, "via": "ictrl", "strategy": strategy,
                    "error": "ictrl: \(msg)",
                    "ictrl_status": http.statusCode,
                ]
            }
            return nil
        } catch {
            // Connection refused / timeout / DNS — iCTRL isn't running.
            return nil
        }
    }

    /// Race a MainActor.run against a wall-clock deadline. If the main-actor
    /// work doesn't finish within the timeout, return an "ok:false, timeout"
    /// envelope so the client's RPC returns fast instead of hanging.
    ///
    /// Note: this can't ABORT main-thread work — Swift/Obj-C UI code isn't
    /// cancellable mid-call. What it does is stop THIS RPC's response from
    /// waiting on it. The stuck main thread will eventually finish (or crash
    /// the process); either way the client isn't held hostage.
    static func withMainActorTimeout(strategy: String,
                                     seconds: Double = 3.0,
                                     _ body: @escaping @MainActor () -> [String: Any]) async -> [String: Any] {
        // Kick off the main-actor work and a sleep in parallel; take whichever
        // finishes first. TaskGroup handles cleanup of the loser.
        return await withTaskGroup(of: (kind: String, value: [String: Any]).self) { group in
            group.addTask {
                let result = await MainActor.run { body() }
                return ("done", result)
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return ("timeout", [
                    "ok": false,
                    "strategy": strategy,
                    "error": "main thread busy — request timed out after \(seconds)s; try 'identifier' if you know one, or fall back to x/y",
                    "timedOutAfter": seconds,
                ] as [String: Any])
            }
            defer { group.cancelAll() }
            if let first = await group.next() {
                return first.value
            }
            return ["ok": false, "strategy": strategy, "error": "task group returned nothing"] as [String: Any]
        }
    }

    /// Walk a UICollectionViewCell subtree looking for something whose
    /// activation actually fires the SwiftUI row handler. We try, per node:
    ///   1. accessibilityActivate() — public; SwiftUI Buttons implement it.
    ///   2. _accessibilityActivate — private VoiceOver hook; SwiftUI's
    ///      internal cell hosting view implements this even for List rows
    ///      that don't publish `.button` traits.
    ///   3. Any accessibilityCustomActions[0] — Apple-recommended when a row
    ///      has a primary action but isn't a Button (e.g. List rows with
    ///      selection binding).
    /// Returns which mechanism won (or nil if nothing accepted). Capped at
    /// 32 nodes for safety — a SwiftUI row is a handful of views.
    @MainActor
    private static func activateCellOrDescendant(_ root: UIView) -> String? {
        // [T-ios-debugtap-nsexception-abort] Every activation below hands
        // control to arbitrary UIKit/SwiftUI accessibility machinery that can
        // raise NSExceptions (device crash 2026-07-23 20:41, Build 1.11(1):
        // customAction target.perform → Foundation recursion → NSException →
        // abort, taking the whole app down from a debug-server tap RPC fired
        // during launch). Swift can't catch ObjC exceptions — run each
        // invocation under MinisCatchObjCException and treat a raise as
        // "this node didn't activate" instead of terminating the process.
        var stack: [UIView] = [root]
        var visits = 0
        var caughtReason: NSString?
        func attempt(_ label: String, _ body: () -> Bool) -> Bool {
            var accepted = false
            var reason: NSString?
            let ok = MinisCatchObjCException({ accepted = body() }, &reason)
            if !ok {
                caughtReason = reason
                AppLogger(category: "DebugServer").error("[debug.tap] \(label) raised NSException (caught): \(reason ?? "?")")
                return false
            }
            return accepted
        }
        while let v = stack.popLast() {
            visits += 1
            if visits > 32 { break }
            if attempt("accessibilityActivate", { v.accessibilityActivate() }) {
                return "accessibilityActivate"
            }
            // Private VoiceOver hook. On iOS 17+, SwiftUI's cell hosting view
            // implements this; the public `accessibilityActivate` doesn't
            // forward to it for non-button rows.
            let sel = NSSelectorFromString("_accessibilityActivate")
            if v.responds(to: sel) {
                if attempt("_accessibilityActivate", { _ = v.perform(sel); return true }) {
                    return "_accessibilityActivate"
                }
            }
            if let actions = v.accessibilityCustomActions, !actions.isEmpty {
                let action = actions[0]
                if let target = action.target, let selector = action.selector as Selector?,
                   target.responds(to: selector) {
                    if attempt("customAction.perform", { _ = target.perform(selector, with: action); return true }) {
                        return "customAction(\(action.name))"
                    }
                } else if let handler = action.actionHandler {
                    if attempt("customAction.handler", { _ = handler(action); return true }) {
                        return "customAction(\(action.name))"
                    }
                }
            }
            for sub in v.subviews { stack.append(sub) }
        }
        _ = caughtReason
        return nil
    }

    /// Resolve a target via the lookup closure, then escalate to an ancestor
    /// that will actually respond before invoking. This matters because a
    /// SwiftUI `Text` inside a `List` row shows up in the accessibility tree
    /// as a `staticText` leaf — activating IT does nothing. The real handler
    /// lives on the `UICollectionViewCell` above, and the collection view's
    /// delegate dispatches selection. See DebugViewInspector.resolveActionable
    /// for the full escalation ladder.
    @MainActor
    private func performTargetTap(strategy: String, lookup: () -> DebugViewInspector.TapTarget?) -> [String: Any] {
        guard let target = lookup() else {
            return ["ok": false, "strategy": strategy, "error": "no on-screen element matched"]
        }
        let point = target.screenPoint
        let actionable = self.inspector.resolveActionable(target)
        var via = actionable.description
        switch actionable {
        case .control(let control):
            // sendActions is the exact same call the runtime makes when the
            // user taps a UIButton/UISwitch — no synthetic touch required.
            // Guarded: the invoked handler is arbitrary app code that may
            // raise an ObjC exception Swift can't catch.
            // [T-ios-debugtap-nsexception-abort]
            var sendReason: NSString?
            if !MinisCatchObjCException({ control.sendActions(for: .touchUpInside) }, &sendReason) {
                AppLogger(category: "DebugServer").error("[debug.tap] sendActions raised NSException (caught): \(sendReason ?? "?")")
                return ["ok": false, "strategy": strategy,
                        "error": "target action raised NSException: \(sendReason ?? "?")"]
            }
        case .collectionCell(let cv, let indexPath, let cell):
            // SwiftUI-backed cells (List / ForEach rows) frequently:
            //  * Return false from shouldSelectItemAt (the collection view's
            //    built-in selection is off because SwiftUI runs its own state).
            //  * Have NO UITapGestureRecognizer we can find — the tap handler
            //    is buried in a SwiftUI-internal recogniser.
            // In that setup neither delegate.didSelectItemAt nor a synthetic
            // UITouch does anything visible. But `accessibilityActivate()` on
            // the CellHostingView (or the cell itself) IS wired: VoiceOver
            // relies on it to activate SwiftUI List rows, so SwiftUI publishes
            // a working handler there. Try that path first.
            //
            // Order:
            //  1. cell.accessibilityActivate() and every direct descendant's
            //     accessibilityActivate() — one of them (the CellHostingView)
            //     usually returns true for SwiftUI rows.
            //  2. Delegate dispatch (works for classic UICollectionView code
            //     that gates via shouldSelectItemAt).
            //  3. Synthetic tap at the cell centre (last resort).
            let indexTag = "\(indexPath.section)-\(indexPath.item)"
            if let mechanism = Self.activateCellOrDescendant(cell) {
                via = "collectionCell:\(indexTag)→\(mechanism)"
            } else {
                let delegate = cv.delegate
                let allowed = delegate?.collectionView?(cv, shouldSelectItemAt: indexPath) ?? true
                if allowed {
                    cv.selectItem(at: indexPath, animated: true, scrollPosition: [])
                    cell.isSelected = true
                    delegate?.collectionView?(cv, didSelectItemAt: indexPath)
                    via = "collectionCell:\(indexTag)→didSelect"
                } else {
                    let centre = cell.convert(CGPoint(x: cell.bounds.midX, y: cell.bounds.midY), to: nil)
                    AutoTouch.simulateTap(at: centre, in: nil)
                    via = "collectionCell:\(indexTag)→coordinate(\(Int(centre.x)),\(Int(centre.y)))"
                }
            }
        case .tableCell(let tv, let indexPath, _):
            let delegate = tv.delegate
            // willSelectRowAt returns the target IndexPath (possibly a
            // different one, or nil to cancel).
            let target = delegate?.tableView?(tv, willSelectRowAt: indexPath) ?? indexPath
            tv.selectRow(at: target, animated: true, scrollPosition: .none)
            delegate?.tableView?(tv, didSelectRowAt: target)
        case .buttonElement(let element):
            if !element.accessibilityActivate() {
                AutoTouch.simulateTap(at: point, in: nil)
                via = "buttonElement→coordinate"
            }
        case .activatableView(let view):
            if !view.accessibilityActivate() {
                AutoTouch.simulateTap(at: point, in: nil)
                via = "activatableView→coordinate"
            }
        case .coordinate:
            AutoTouch.simulateTap(at: point, in: nil)
        }
        return [
            "ok": true,
            "strategy": strategy,
            "via": via,
            "point": ["x": point.x, "y": point.y],
        ]
    }

    private func handleInputText(params: [String: Any]) async throws -> Any {
        guard let text = params["text"] as? String, !text.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'text' (string) is required")
        }
        let ok = await MainActor.run {
            AutoTouch.inputText(text)
        }
        return ["ok": ok, "length": text.count] as [String: Any]
    }

    private func handleScroll(params: [String: Any]) async throws -> Any {
        guard let x = params["x"] as? Double, let y = params["y"] as? Double else {
            throw RPCError(code: -32602, message: "Invalid params: 'x' and 'y' (start point) are required")
        }
        let deltaX = params["deltaX"] as? Double ?? 0.0
        let deltaY = params["deltaY"] as? Double ?? 0.0

        if deltaX == 0 && deltaY == 0 {
            throw RPCError(code: -32602, message: "Invalid params: at least one of 'deltaX' or 'deltaY' must be non-zero")
        }

        await MainActor.run {
            AutoTouch.simulateScroll(at: CGPoint(x: x, y: y),
                                     deltaX: CGFloat(deltaX),
                                     deltaY: CGFloat(deltaY),
                                     in: nil)
        }
        return ["ok": true, "start": ["x": x, "y": y], "delta": ["x": deltaX, "y": deltaY]] as [String: Any]
    }

    /// debug.openSession — bring a specific chat session to the foreground.
    ///
    /// [T-ios-render-collapse-ca-limit] Photographing a specific conversation
    /// needs the chat view for THAT session actually frontmost.
    /// `debug.screenshot` captures the topmost view, and on iPhone the session
    /// list sits above the chat, so a harness that injects into session X and
    /// then screenshots keeps photographing the list instead. `debug.tap`
    /// cannot fix it: synthetic taps do not resolve against SwiftUI `List`
    /// rows (they miss the hit-test), and a coordinate tap opens whichever
    /// row happens to be at that point — not the session under test.
    ///
    /// This posts the SAME `.openSessionFromIntent` notification that Siri
    /// intents, deep links and the sessions offload already use, so it drives
    /// the app's real navigation path (including the iPhone
    /// `switchToSession` vs iPad `openSession` split) rather than a
    /// debug-only shortcut that could diverge from production behaviour.
    ///
    /// The session id is validated first so a typo fails loudly instead of
    /// posting a notification that silently does nothing.
    private func handleOpenSession(params: [String: Any]) async throws -> Any {
        guard let sessionId = params["sessionId"] as? String, !sessionId.isEmpty else {
            throw RPCError(code: -32602, message: "Missing required param: sessionId")
        }
        let sessions = await ChatStore.shared.listSessions()
        guard sessions.contains(where: { $0.id == sessionId }) else {
            throw RPCError(code: -32602, message: "Session not found: \(sessionId)")
        }
        await MainActor.run {
            NotificationCenter.default.post(
                name: .openSessionFromIntent,
                object: nil,
                userInfo: ["sessionId": sessionId]
            )
        }
        return ["ok": true, "sessionId": sessionId] as [String: Any]
    }

    /// debug.scrollOffset — read or SET the chat collection view's
    /// contentOffset.y directly.
    ///
    /// [T-ios-render-collapse-ca-limit] `debug.scroll` posts a synthetic pan,
    /// which stalls inside a cell whose layer exceeds the Core Animation
    /// backing limit — exactly the case we need to photograph. Writing
    /// contentOffset bypasses the gesture layer entirely, so a repro harness
    /// can page through a 9700pt+ cell one screen at a time deterministically.
    /// Omit `y` to just read the current position.
    private func handleScrollOffset(params: [String: Any]) async throws -> Any {
        return try await MainActor.run {
            var found: UIScrollView?
            func walk(_ v: UIView) {
                if found != nil { return }
                if let sv = v as? UIScrollView, sv.contentSize.height > sv.bounds.height * 1.5 {
                    found = sv
                    return
                }
                for sub in v.subviews { walk(sub) }
            }
            let windows = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
            for window in windows { walk(window) }
            guard let sv = found else {
                throw RPCError(code: -32000, message: "No scrollable collection view found")
            }
            if let y = params["y"] as? Double {
                // [T-ios-render-collapse-ca-limit] The list coordinator pins the
                // offset to the bottom while `scrollMode == .autoScrolling`, so a
                // plain setContentOffset is snapped back within a frame. Tell the
                // delegate the user is browsing first — same transition a real
                // drag causes — so the requested offset actually sticks.
                if let coord = sv.delegate as? CollectionViewMessageListV3.Coordinator {
                    coord.scrollMode = .userBrowsing
                }
                let maxY = max(0, sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom)
                let clamped = min(max(CGFloat(y), -sv.adjustedContentInset.top), maxY)
                sv.setContentOffset(CGPoint(x: sv.contentOffset.x, y: clamped), animated: false)
                sv.layoutIfNeeded()
            }
            return [
                "offsetY": Double(sv.contentOffset.y),
                "contentHeight": Double(sv.contentSize.height),
                "boundsHeight": Double(sv.bounds.height),
                "insetTop": Double(sv.adjustedContentInset.top),
                "insetBottom": Double(sv.adjustedContentInset.bottom),
            ] as [String: Any]
        }
    }

    // MARK: - JSON Response Helpers

    private func successResponse(result: Any, id: Any?) -> String {
        let response: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id ?? NSNull(),
            "result": result,
            "_meta": Self.responseMeta,
        ]
        return jsonString(response)
    }

    private func errorResponse(code: Int, message: String, id: Any?) -> String {
        let response: [String: Any] = [
            "jsonrpc": "2.0",
            "id": id ?? NSNull(),
            "error": [
                "code": code,
                "message": message,
            ],
            "_meta": Self.responseMeta,
        ]
        return jsonString(response)
    }

    private func jsonString(_ obj: Any) -> String {
        // [T-ios-debugrpc-json-nsexception-abort] `try?` only catches the
        // Swift-error path. NSJSONSerialization ALSO raises ObjC exceptions —
        // NSInvalidArgumentException for non-serializable values (NaN/Inf,
        // arbitrary objects) and NSMallocException when _convertJSONString's
        // CFString reallocation fails on a huge payload (device crash
        // 2026-07-23 20:58, Build 1.11(1): shell RPC stdout large enough that
        // __CFStringHandleOutOfMemory raised → abort on thread "sh-2").
        // Swift can't catch those; run the writer under the @try/@catch
        // bridge and fall back to a fixed error envelope.
        var data: Data?
        var reason: NSString?
        let ok = MinisCatchObjCException({
            data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
        }, &reason)
        guard ok, let data, let str = String(data: data, encoding: .utf8) else {
            if let reason {
                AppLogger(category: "DebugServer").error("[rpc] response serialization raised NSException (caught): \(reason)")
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32000,\"message\":\"Serialization failed\"}}"
        }
        return str
    }

    // MARK: - Env Var Handlers

    /// Serialize an EnvVarEntry for RPC. Includes hasValue (Keychain probe);
    /// optionally includes the decoded plaintext value when includeValues=true.
    @MainActor
    private func encodeEnvVarEntry(_ entry: EnvVarEntry, includeValue: Bool) -> [String: Any] {
        let iso = ISO8601DateFormatter()
        let stored = EnvVarStore.loadValueSync(forKey: entry.key)
        let hasValue = (stored?.isEmpty == false)
        var dict: [String: Any] = [
            "id": entry.id,
            "key": entry.key,
            "note": entry.note,
            "createdAt": iso.string(from: entry.createdAt),
            // EnvVarEntry has no per-row updatedAt yet — mirror createdAt so
            // callers can rely on the field being present.
            "updatedAt": iso.string(from: entry.createdAt),
            "hasValue": hasValue,
        ]
        if includeValue {
            dict["value"] = stored ?? ""
        }
        return dict
    }

    /// debug.envvar.list — return all env-var entries. With
    /// `includeValues=true`, also includes the decoded Keychain value.
    private func handleEnvVarList(params: [String: Any]) async -> Any {
        let includeValues = (params["includeValues"] as? Bool) ?? false
        return await MainActor.run { () -> [String: Any] in
            let entries = EnvVarStore.shared.entries
            let items = entries.map { self.encodeEnvVarEntry($0, includeValue: includeValues) }
            return ["count": items.count, "entries": items]
        }
    }

    /// debug.envvar.set — create or update an entry. Update by `id` first,
    /// else by `key`, else create new. Routes through EnvVarStore so the
    /// dirty-marking / sync push behaves identically to user edits.
    private func handleEnvVarSet(params: [String: Any]) async -> Any {
        let key = (params["key"] as? String) ?? ""
        let value = params["value"] as? String
        let note = params["note"] as? String
        let idParam = params["id"] as? String

        return await MainActor.run { () -> [String: Any] in
            let store = EnvVarStore.shared
            // Resolve target entry: explicit id, then matching key, else new.
            let existing: EnvVarEntry?
            if let idParam, !idParam.isEmpty {
                existing = store.entry(id: idParam)
            } else if !key.isEmpty {
                let upper = key.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                existing = store.entries.first(where: { $0.key == upper })
            } else {
                existing = nil
            }

            if let existing {
                let newKey = key.isEmpty ? existing.key : key
                // Use existing value when caller passed nil (note-only update).
                let newValue: String
                if let value {
                    newValue = value
                } else {
                    newValue = EnvVarStore.loadValueSync(forKey: existing.key) ?? ""
                }
                store.update(id: existing.id, key: newKey, value: newValue, note: note)
                if let updated = store.entry(id: existing.id) {
                    return self.encodeEnvVarEntry(updated, includeValue: false)
                }
                return ["error": "update failed"]
            } else {
                guard !key.isEmpty else {
                    return ["error": "missing key"]
                }
                store.add(key: key, value: value ?? "", note: note ?? "")
                let upper = key.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                if let created = store.entries.first(where: { $0.key == upper }) {
                    return self.encodeEnvVarEntry(created, includeValue: false)
                }
                return ["error": "add failed (invalid key or duplicate)"]
            }
        }
    }

    /// debug.envvar.delete — delete by `id` or by `key`. Routes through
    /// EnvVarStore so the entry's per-key sync record is queued for delete.
    private func handleEnvVarDelete(params: [String: Any]) async -> Any {
        let idParam = params["id"] as? String
        let keyParam = params["key"] as? String
        return await MainActor.run { () -> [String: Any] in
            let store = EnvVarStore.shared
            let target: EnvVarEntry?
            if let idParam, !idParam.isEmpty {
                target = store.entry(id: idParam)
            } else if let keyParam, !keyParam.isEmpty {
                let upper = keyParam.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                target = store.entries.first(where: { $0.key == upper })
            } else {
                return ["deleted": false, "error": "missing id or key"]
            }
            guard let target else {
                return ["deleted": false]
            }
            store.delete(id: target.id)
            return ["deleted": true, "id": target.id, "key": target.key]
        }
    }

    // MARK: - Error Type

    private struct RPCError: Error {
        let code: Int
        let message: String
    }

    // MARK: - Quick Test handler

    private func handleProvidersQuickTest(params: [String: Any]) async throws -> Any {
        guard let entryId = params["entryId"] as? String else {
            throw RPCError(code: -32602, message: "Missing required 'entryId' (string).")
        }
        let (entry, requestedKinds) = try await MainActor.run {
            let store = ProviderConfigStore.shared
            // Built-in System voices/ASR are virtual entries (no store record) —
            // reconstruct the per-voice entry so Quick Test works on them too.
            guard let entry = store.entry(for: entryId) ?? UnifiedModelPicker.systemEntry(for: entryId) else {
                throw RPCError(code: -32602, message: "No model entry found for id '\(entryId)'.")
            }
            let allKinds: [ModelQuickTestSheet.Kind] = [.text, .imageGen, .speechOut, .transcription]
            let kinds: [ModelQuickTestSheet.Kind]
            if let raw = params["kinds"] as? [String] {
                kinds = raw.compactMap { ModelQuickTestSheet.Kind(rawValue: $0) }
            } else {
                let m = entry.model.modalityOverride ?? entry.model.capabilities.supportedModalities
                kinds = allKinds.filter { kind in
                    switch kind {
                    case .text:          return m.contains(.textOutput)
                    case .imageGen:      return m.contains(.imageOutput)
                    case .speechOut:     return m.contains(.audioOutput)
                    case .transcription: return m.contains(.audioInput)
                    }
                }
            }
            return (entry, kinds)
        }
        guard !requestedKinds.isEmpty else {
            throw RPCError(code: -32602, message: "No applicable test kinds for model '\(entry.model.displayName)'.")
        }

        var results: [[String: Any]] = []
        await withTaskGroup(of: (ModelQuickTestSheet.Kind, String, String, TimeInterval).self) { group in
            for kind in requestedKinds {
                group.addTask { @MainActor in
                    let start = Date()
                    do {
                        let state = try await TestSession.performTestStatic(kind, entry: entry)
                        let elapsed = Date().timeIntervalSince(start)
                        let detail: String
                        switch state {
                        case .text(let t): detail = t
                        case .transcript(let spoken, let heard): detail = "spoken: \(spoken) | heard: \(heard)"
                        case .audio(let d):
                            let dur = (try? AVAudioPlayer(data: d))?.duration ?? -1
                            detail = "\(d.count) bytes, \(String(format: "%.2f", dur))s"
                        case .image(let d): detail = "\(d.count) bytes"
                        default: detail = ""
                        }
                        return (kind, "ok", detail, elapsed)
                    } catch {
                        return (kind, "error", error.localizedDescription, Date().timeIntervalSince(start))
                    }
                }
            }
            for await (kind, status, detail, elapsed) in group {
                results.append([
                    "kind": kind.rawValue,
                    "status": status,
                    "elapsed": String(format: "%.2fs", elapsed),
                    "detail": detail,
                ])
            }
        }
        return [
            "model": entry.model.displayName,
            "modelId": entry.model.id,
            "entryId": entryId,
            "results": results,
        ]
    }

    // MARK: - DB inspection (read-only SQL over the app's SQLite files)

    /// The app's SQLite databases, keyed by a short logical name. Paths resolved
    /// at call time so a not-yet-created db (e.g. voice-correction before first
    /// use) simply reports exists=false rather than erroring. This is the
    /// allow-list: `name` params for the other db.* methods must be one of these,
    /// so no arbitrary filesystem path can be opened.
    private func knownDatabases() -> [(name: String, url: URL)] {
        // Every app SQLite db lives under Library/MinisChat/ (verified against
        // ChatStore, ProviderConfigDB, VoiceCorrectionDB, AlarmOffloadBridge),
        // except the rootfs meta.db which lives inside the iSH rootfs.
        let fm = FileManager.default
        let library = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let chatBase = library.appendingPathComponent("MinisChat", isDirectory: true)
        let rootfs = RootfsManager.shared.rootfsPath
        return [
            ("minis", chatBase.appendingPathComponent("minis.db")),
            ("skills", chatBase.appendingPathComponent("minis").appendingPathComponent("skills.db")),
            ("provider-config", chatBase.appendingPathComponent("provider-config.db")),
            ("voice-correction", chatBase.appendingPathComponent("voice-correction.db")),
            ("alarm-labels", chatBase.appendingPathComponent("alarm-labels.db")),
            ("rootfs-meta", rootfs.appendingPathComponent("meta.db")),
        ]
    }

    private func resolveDatabase(_ name: String) throws -> URL {
        guard let entry = knownDatabases().first(where: { $0.name == name }) else {
            let names = knownDatabases().map(\.name).joined(separator: ", ")
            throw RPCError(code: -32602, message: "Unknown db '\(name)'. Known: \(names). Use debug.db.list.")
        }
        guard FileManager.default.fileExists(atPath: entry.url.path) else {
            throw RPCError(code: -32602, message: "Database '\(name)' does not exist yet at \(entry.url.path)")
        }
        return entry.url
    }

    /// Open a database strictly read-only. Uses a file: URI with mode=ro so we
    /// can never mutate the live db a running actor also holds open.
    private func openReadOnly(_ url: URL) throws -> OpaquePointer {
        var handle: OpaquePointer?
        let uri = "file:\(url.path)?mode=ro&immutable=0"
        let rc = sqlite3_open_v2(uri, &handle,
                                 SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, nil)
        guard rc == SQLITE_OK, let handle else {
            let msg = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "rc=\(rc)"
            handle.map { sqlite3_close($0) }
            throw RPCError(code: -32000, message: "Failed to open db read-only: \(msg)")
        }
        // 5s busy timeout so a concurrent writer's lock doesn't fail us outright.
        sqlite3_busy_timeout(handle, 5000)
        return handle
    }

    /// Reject anything that isn't a single read-only statement. Defence in depth:
    /// the connection is already opened read-only (writes fail at the engine),
    /// but this gives a clear error instead of a cryptic SQLITE_READONLY and
    /// blocks multi-statement smuggling.
    private func assertReadOnlySQL(_ sql: String) throws {
        let trimmed = sql.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw RPCError(code: -32602, message: "Empty sql.")
        }
        // Disallow statement separators that would allow a second statement.
        // A trailing semicolon is fine; an interior one is not.
        let withoutTrailing = trimmed.hasSuffix(";") ? String(trimmed.dropLast()) : trimmed
        if withoutTrailing.contains(";") {
            throw RPCError(code: -32602, message: "Only a single statement is allowed (no ';' inside the query).")
        }
        let head = withoutTrailing.lowercased()
        let allowed = ["select", "pragma", "explain", "with", "values"]
        guard allowed.contains(where: { head.hasPrefix($0) }) else {
            throw RPCError(code: -32602, message: "Only read-only queries allowed (SELECT/WITH/PRAGMA/EXPLAIN/VALUES). Got: \(head.prefix(20))…")
        }
    }

    /// Run a prepared read-only statement and materialise up to `limit` rows as
    /// [[column: value]]. NULL → NSNull, INTEGER/FLOAT → Number, TEXT → String,
    /// BLOB → {"__blob__": byteCount} (never inlined — could be huge/binary).
    private func runQuery(dbURL: URL, sql: String, limit: Int) throws -> [String: Any] {
        let db = try openReadOnly(dbURL)
        defer { sqlite3_close(db) }

        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            let msg = String(cString: sqlite3_errmsg(db))
            throw RPCError(code: -32000, message: "SQL error: \(msg)")
        }
        defer { sqlite3_finalize(stmt) }

        let colCount = Int(sqlite3_column_count(stmt))
        var columns: [String] = []
        for i in 0..<colCount {
            columns.append(String(cString: sqlite3_column_name(stmt, Int32(i))))
        }

        var rows: [[String: Any]] = []
        var truncated = false
        while sqlite3_step(stmt) == SQLITE_ROW {
            if rows.count >= limit { truncated = true; break }
            var row: [String: Any] = [:]
            for i in 0..<colCount {
                let c = Int32(i)
                let value: Any
                switch sqlite3_column_type(stmt, c) {
                case SQLITE_NULL:
                    value = NSNull()
                case SQLITE_INTEGER:
                    value = sqlite3_column_int64(stmt, c)
                case SQLITE_FLOAT:
                    value = sqlite3_column_double(stmt, c)
                case SQLITE_BLOB:
                    value = ["__blob__": Int(sqlite3_column_bytes(stmt, c))]
                default:
                    if let text = sqlite3_column_text(stmt, c) {
                        value = String(cString: text)
                    } else {
                        value = NSNull()
                    }
                }
                row[columns[i]] = value
            }
            rows.append(row)
        }
        return [
            "columns": columns,
            "rows": rows,
            "rowCount": rows.count,
            "truncated": truncated,
        ]
    }

    private func handleDBList() throws -> Any {
        let fm = FileManager.default
        let dbs = knownDatabases().map { entry -> [String: Any] in
            var info: [String: Any] = ["name": entry.name, "path": entry.url.path]
            if let attrs = try? fm.attributesOfItem(atPath: entry.url.path),
               let size = attrs[.size] as? Int {
                info["exists"] = true
                info["sizeBytes"] = size
            } else {
                info["exists"] = false
            }
            return info
        }
        return ["databases": dbs]
    }

    private func handleDBTables(params: [String: Any]) throws -> Any {
        guard let name = params["name"] as? String, !name.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'name' is required (a db from debug.db.list).")
        }
        let url = try resolveDatabase(name)
        let r = try runQuery(
            dbURL: url,
            sql: "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name",
            limit: 500
        )
        return ["db": name, "tables": r["rows"] ?? []]
    }

    private func handleDBSchema(params: [String: Any]) throws -> Any {
        guard let name = params["name"] as? String, !name.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'name' is required.")
        }
        let url = try resolveDatabase(name)
        // Optional 'table' → column layout via PRAGMA; else the full DDL dump.
        if let table = params["table"] as? String, !table.isEmpty {
            guard table.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" }) else {
                throw RPCError(code: -32602, message: "Invalid table name.")
            }
            let cols = try runQuery(dbURL: url, sql: "PRAGMA table_info(\(table))", limit: 200)
            let idx = try runQuery(dbURL: url, sql: "PRAGMA index_list(\(table))", limit: 100)
            return ["db": name, "table": table, "columns": cols["rows"] ?? [], "indexes": idx["rows"] ?? []]
        }
        let ddl = try runQuery(
            dbURL: url,
            sql: "SELECT name, type, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type, name",
            limit: 500
        )
        return ["db": name, "objects": ddl["rows"] ?? []]
    }

    private func handleDBQuery(params: [String: Any]) throws -> Any {
        guard let name = params["name"] as? String, !name.isEmpty else {
            throw RPCError(code: -32602, message: "Invalid params: 'name' is required.")
        }
        guard let sql = params["sql"] as? String else {
            throw RPCError(code: -32602, message: "Invalid params: 'sql' is required.")
        }
        try assertReadOnlySQL(sql)
        let url = try resolveDatabase(name)
        let requested = params["limit"] as? Int ?? 200
        let limit = min(max(requested, 1), 2000)
        var result = try runQuery(dbURL: url, sql: sql, limit: limit)
        result["db"] = name
        return result
    }
}
#endif
