//
//  BrowserUseOffloadBridge.swift
//  MinisApp
//
//  Swift bridge for BrowserTabPool, called from BrowserUseOffload.m.
//  BrowserTabPool is Swift-only and @MainActor; this class exposes
//  per-session tab pools and a synchronous-facing execute method for
//  the ObjC handler to consume via a completion block.
//

import Foundation

@objc public class BrowserUseOffloadBridge: NSObject {

    private static let logger = AppLogger(category: "BrowserUseOffloadBridge")

    /// Fallback pools keyed by session id — used only when the corresponding
    /// `AIChatViewModel` is not currently cached (e.g. Terminal opened for a
    /// session whose chat UI hasn't been instantiated yet in this process).
    /// When the agent-side vm is cached we reuse its `browserTabPool` directly
    /// so the shell and the agent share one browser state.
    ///
    /// Sentinel key `"__unmounted__"` collects the rare invocations with no
    /// resolvable session (kernel not booted / mount missing).
    @MainActor
    private static var fallbackPools: [String: BrowserTabPool] = [:]

    /// Sentinel session id for invocations with no active mount.
    private static let unmountedSentinel = "__unmounted__"

    /// [T-browser-cli-timeout-reclaim] OpenMinis#245. Executions whose
    /// completion has not fired yet, so the ObjC handler can cancel the one it
    /// stopped waiting for. `tabId` is the tab the command asked for (`nil` =
    /// implicit tab). The task is filled in right after it is created. The
    /// entry is inserted before that and removed by the task's own `defer`, so
    /// a task that finishes early never leaves a stale entry. Lock-protected
    /// rather than MainActor: both ends are called from the guest thread.
    private struct InFlight {
        let sid: String
        let tabId: Int?
        var task: Task<Void, Never>?
    }
    private static let inFlightLock = NSLock()
    nonisolated(unsafe) private static var inFlight: [UInt64: InFlight] = [:]
    /// Starts at 1 so 0 can mean "no execution was registered" (the early
    /// rejections in `execute`); `cancelAndReclaim(invocation: 0)` is a no-op.
    nonisolated(unsafe) private static var nextInvocation: UInt64 = 1

    /// Synchronous so the lock is never held across a suspension point.
    private static func endInvocation(_ invocation: UInt64) {
        inFlightLock.lock()
        inFlight.removeValue(forKey: invocation)
        inFlightLock.unlock()
    }

    /// [T-browser-cli-timeout-reclaim] OpenMinis#245. Called by the ObjC
    /// handler when its 90s wait for `execute` runs out. Cancels exactly the
    /// execution it abandoned, named by the token `execute` returned.
    ///
    /// Why: the tab pool's own dead-tab ceiling is 300s, so the abandoned
    /// action kept the tab's serial slot for another ~210s. Every follow-up
    /// command on that tab queued behind it and failed its slot wait, which
    /// made the browser look hung for about 5 minutes.
    ///
    /// Cancelling the execution task reaches `BrowserTabPool.withDeadOnTimeout`,
    /// which fires the same abort `BrowserTabPool.abortAndRebuildTab` does. The
    /// op task is cancelled, the wedged tab is swapped for a fresh WebView, and
    /// the slot is released, so the next command runs at once. Cancelling the
    /// task instead of aborting "whatever runs on tabId" matters here: the pool
    /// is shared with the agent, and a blind abort could kill an agent action
    /// that took the slot after ours finished.
    ///
    /// [T-browser-cli-reclaim-scope] This used to match by (session id,
    /// requested tab id). The session id is the process-wide mounted-sid
    /// snapshot and most commands name no tab, so one CLI timeout cancelled
    /// every concurrent `minis-browser-use` call (main agent, sub-agents,
    /// parallel tool calls) and rebuilt their healthy tabs. Keying on the
    /// per-invocation token scopes the cancel to the one call that timed out.
    @objc public static func cancelAndReclaim(invocation: UInt64) {
        inFlightLock.lock()
        let hit = inFlight.removeValue(forKey: invocation)
        inFlightLock.unlock()

        guard let hit else {
            // Completion raced the timeout: nothing left holding the tab.
            logger.info("[BridgeTiming] cancelAndReclaim invocation=\(invocation): nothing in flight")
            return
        }
        logger.warning("[BridgeTiming] cancelAndReclaim invocation=\(invocation) sid=\(hit.sid.prefix(8)) tab=\(hit.tabId.map(String.init) ?? "nil"): cancelling the abandoned execution — tab will be rebuilt and its slot released")
        hit.task?.cancel()
    }

    /// Resolve the tab pool for the given session id.
    ///
    /// Resolution order:
    ///   1. Live `AIChatViewModel.browserTabPool` from `ViewModelCache` —
    ///      unifies UI-visible tabs with shell CLI usage.
    ///   2. Fallback pool bound to this sid, reused across subsequent CLI
    ///      invocations so successive shell commands see consistent tabs.
    ///      Allocated on demand iff the session still exists in ChatStore.
    ///
    /// Returns `nil` when the session has been deleted — caller must surface
    /// an error to the shell.
    @MainActor
    private static func pool(for sid: String) async -> BrowserTabPool? {
        if sid == Self.unmountedSentinel {
            return sentinelPool()
        }
        if let vm = ViewModelCache.shared.get(for: sid) {
            return vm.browserTabPool
        }
        if let existing = fallbackPools[sid] {
            return existing
        }
        // No live vm and no prior fallback — confirm the session still exists
        // before allocating. A deleted session should surface as an error in
        // the shell rather than silently spinning up a zombie pool.
        let exists = await ChatStore.shared.getSession(sid) != nil
        guard exists else {
            logger.warning("minis-browser-use invoked for deleted session \(sid.prefix(8))")
            return nil
        }
        let p = BrowserTabPool()
        p.sessionId = sid
        fallbackPools[sid] = p
        logger.info("Allocated fallback browser pool for session \(sid.prefix(8)) (no cached vm)")
        return p
    }

    @MainActor
    private static func sentinelPool() -> BrowserTabPool {
        if let p = fallbackPools[Self.unmountedSentinel] { return p }
        let p = BrowserTabPool()
        p.sessionId = Self.unmountedSentinel
        fallbackPools[Self.unmountedSentinel] = p
        logger.info("Allocated sentinel browser pool (no mounted session)")
        return p
    }

    /// Release the fallback pool for a session. Live vm-owned pools are
    /// managed by the vm lifecycle; this only touches bridge-owned fallbacks.
    /// Called from `AIChatViewModel.clearChat` / session deletion paths.
    @objc public static func releasePool(forSession sessionId: String) {
        Task { @MainActor in
            if fallbackPools.removeValue(forKey: sessionId) != nil {
                logger.info("Released fallback browser pool for session \(sessionId.prefix(8))")
            }
        }
    }

    /// Execute a browser action given a JSON payload matching the
    /// `browser_use` tool schema. The completion block receives a
    /// dictionary describing the result; the ObjC caller is responsible
    /// for serializing it to the guest's stdout.
    ///
    /// Session resolution: reads `ISHExecutionCoordinator.mountedSessionId`
    /// at the moment the handler fires. Because shell execution is
    /// serialized by the coordinator actor and the mount-swap happens
    /// synchronously before the guest command runs, this value is
    /// guaranteed to be the session that owns the current `/var/minis/`
    /// bind mount for the lifetime of this CLI invocation.
    ///
    /// When `withBase64` is false (default), any captured screenshot is
    /// persisted to that session's `/var/minis/browser/` directory and
    /// surfaced via `image_path` + `minis_url` instead of `image_base64`.
    /// Set `withBase64` to true only when the caller explicitly wants the
    /// raw base64 blob inline (e.g. piping to another tool).
    ///
    /// Keys on success: text, success, page_url?, image_path?,
    /// minis_url?, image_base64?, fetched_file?, fetched_bytes?,
    /// fetched_path?, fetched_minis_url?.
    /// Keys on failure: text, success=false.
    ///
    /// Returns the invocation token to pass to `cancelAndReclaim(invocation:)`
    /// if the caller stops waiting ([T-browser-cli-reclaim-scope]); 0 when the
    /// request was rejected before anything was started.
    @discardableResult
    @objc public static func execute(
        withJson json: String,
        withBase64: Bool,
        completion: @escaping (NSDictionary) -> Void
    ) -> UInt64 {
        let bridgeStart = CFAbsoluteTimeGetCurrent()

        // [T-tools-master-switch] minis-browser-use is the browser_use tool
        // behind a CLI; the same switch closes both.
        guard AgentToolSwitch.browser.isEnabled else {
            completion([
                "text": "Error: Browser Use is turned off in Settings › Agent Tools. Ask the user to enable it there.",
                "success": false,
            ] as NSDictionary)
            return 0
        }

        guard let input = BrowserActionInput.parse(from: json) else {
            completion([
                "text": "Error: Invalid browser_use input. Required: 'action' parameter.",
                "success": false,
            ] as NSDictionary)
            return 0
        }

        logger.info("[BridgeTiming] enter action=\(input.action.rawValue) tab_id=\(input.tabId.map(String.init) ?? "nil") url=\(input.url?.prefix(80) ?? "nil")")

        // Resolve the owning session via the lock-protected snapshot BEFORE
        // hopping to MainActor. Awaiting the coordinator actor here used to
        // deadlock under shell pressure: the guest task thread sits on
        // `dispatch_semaphore_wait`, and this task's MainActor hop has to
        // beat every `dispatch_async(main_queue, ^{ ctx.lineCallback(line) })`
        // coming out of ISHShellExecutor to get picked up. Reading the
        // nonisolated snapshot synchronously avoids both the coordinator
        // actor suspension and one MainActor ordering hop.
        let sid = ISHExecutionCoordinator.mountedSessionIdSnapshot
                  ?? Self.unmountedSentinel

        inFlightLock.lock()
        let invocation = nextInvocation
        nextInvocation &+= 1
        inFlight[invocation] = InFlight(sid: sid, tabId: input.tabId, task: nil)
        inFlightLock.unlock()

        let task = Task { @MainActor in
            defer { endInvocation(invocation) }
            guard let pool = await Self.pool(for: sid) else {
                let elapsedMs = Int((CFAbsoluteTimeGetCurrent() - bridgeStart) * 1000)
                logger.info("[BridgeTiming] pool_not_found elapsed=\(elapsedMs)ms sid=\(sid.prefix(8))")
                completion([
                    "text": "Error: session \(sid) no longer exists — cannot run browser_use.",
                    "success": false,
                ] as NSDictionary)
                return
            }

            let poolResolvedMs = Int((CFAbsoluteTimeGetCurrent() - bridgeStart) * 1000)
            logger.info("[BridgeTiming] pool_resolved elapsed=\(poolResolvedMs)ms sid=\(sid.prefix(8))")

            do {
                // CLI is a serial human/script driver — run in single-tab mode
                // so navigate → execute_js / get_page_info / get_text etc. all
                // hit the page just navigated to, instead of being fanned out
                // across grace-busy tabs. Explicit --tab-id still routes
                // normally. Agent tool path keeps the default (fan-out).
                // [T-browser-executejs-stale-context-ios]
                let result = try await pool.execute(action: input, singleTab: true)
                let poolDoneMs = Int((CFAbsoluteTimeGetCurrent() - bridgeStart) * 1000)
                logger.info("[BridgeTiming] pool_execute_done elapsed=\(poolDoneMs)ms action=\(input.action.rawValue)")

                let encoded = Self.encode(result, withBase64: withBase64, sid: sid)
                let totalMs = Int((CFAbsoluteTimeGetCurrent() - bridgeStart) * 1000)
                logger.info("[BridgeTiming] completion elapsed=\(totalMs)ms action=\(input.action.rawValue) success=\(result.success)")
                completion(encoded)
            } catch {
                let totalMs = Int((CFAbsoluteTimeGetCurrent() - bridgeStart) * 1000)
                logger.info("[BridgeTiming] error elapsed=\(totalMs)ms action=\(input.action.rawValue) error=\(error.localizedDescription.prefix(120))")
                completion([
                    "text": "Error: \(error.localizedDescription)",
                    "success": false,
                ] as NSDictionary)
            }
        }
        inFlightLock.lock()
        inFlight[invocation]?.task = task
        inFlightLock.unlock()
        return invocation
    }

    private static func encode(_ r: BrowserActionResult, withBase64: Bool, sid: String) -> NSDictionary {
        let out = NSMutableDictionary()
        out["text"] = r.text
        out["success"] = r.success
        if let url = r.pageURL, !url.isEmpty { out["page_url"] = url }

        // Persist screenshot + fetched bytes under the invoking session's
        // browser directory. We resolve the host path directly from the sid
        // captured at execute() entry instead of querying the coordinator's
        // live mount table — a concurrent UI session-switch could null that
        // out mid-flight. `minisBrowserPersistentDir` is a pure path join
        // against Library/MinisChat/minis/<sid>/browser/, which is exactly
        // what /var/minis/browser/ bind-mounts to for that session.
        let browserHostDir: URL? = (sid == Self.unmountedSentinel)
            ? nil
            : AIChatViewModel.minisBrowserPersistentDir(for: sid)

        // ── Screenshot / snapshot ──
        var persistedImagePath: String? = nil
        if let b64 = r.base64Image, !b64.isEmpty, let data = Data(base64Encoded: b64) {
            let filename = "screenshot_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
            if let hostDir = browserHostDir {
                try? FileManager.default.createDirectory(at: hostDir, withIntermediateDirectories: true)
                let dest = hostDir.appendingPathComponent(filename)
                do {
                    try data.write(to: dest)
                    let linuxPath = "\(AIChatViewModel.minisBrowserLinuxDir)/\(filename)"
                    persistedImagePath = linuxPath
                    out["image_path"] = linuxPath
                    out["minis_url"] = "minis://browser/\(filename)"
                } catch {
                    logger.warning("Failed to persist screenshot to \(dest.path): \(error.localizedDescription)")
                }
            } else {
                logger.warning("No /var/minis/browser mount — falling back to base64-only output")
            }
        }

        // Fall back to the in-memory host path only when we couldn't persist.
        if persistedImagePath == nil, let p = r.imageFilePath, !p.isEmpty {
            out["image_path"] = p
        }

        if withBase64, let b = r.base64Image, !b.isEmpty {
            out["image_base64"] = b
        }

        // ── Fetched file (fetch action) ──
        if let name = r.fetchedFileName, !name.isEmpty {
            out["fetched_file"] = name
            if let data = r.fetchedFileData {
                out["fetched_bytes"] = data.count
                if let hostDir = browserHostDir {
                    try? FileManager.default.createDirectory(at: hostDir, withIntermediateDirectories: true)
                    let dest = hostDir.appendingPathComponent(name)
                    do {
                        try data.write(to: dest)
                        let linuxPath = "\(AIChatViewModel.minisBrowserLinuxDir)/\(name)"
                        out["fetched_path"] = linuxPath
                        out["fetched_minis_url"] = "minis://browser/\(name)"
                    } catch {
                        logger.warning("Failed to persist fetched file to \(dest.path): \(error.localizedDescription)")
                    }
                }
            }
        }

        return out
    }
}
