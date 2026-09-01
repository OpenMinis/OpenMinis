import Foundation
import SQLite3
import os.log

private let logger = AppLogger(category: "ISHCoordinator")

/// Result of a coordinated shell command execution.
struct ISHCommandResult {
    let output: String
    let exitCode: Int
}

/// Errors specific to the execution coordinator.
enum ISHCoordinatorError: Error, LocalizedError {
    case kernelNotBooted
    case noSession

    var errorDescription: String? {
        switch self {
        case .kernelNotBooted: return "iSH kernel is not booted"
        case .noSession: return "No session ID provided"
        }
    }
}

/// Global coordinator for iSH shell execution. Commands run concurrently
/// — across sessions AND within the same session — because each
/// `executeExecutable("/bin/sh", stdinData:…, fsContext:…)` call forks
/// a fresh sh process with its own PID, its own stdin/stdout/stderr
/// pipes, and a per-process fs_context routing token. There is no
/// shared PTY or shell instance to serialize behind.
///
/// `perSessionInflight` is retained ONLY for stop / preempt bookkeeping
/// (so the stop button can enumerate every live PID for the session and
/// kill them). The historical FIFO wait-for-head logic was removed
/// 2026-05-25 because it made concurrent tool dispatch (TaskGroup
/// fan-out of N shell_execute calls) silently serialize at the
/// coordinator, defeating the upper-layer parallelism.
actor ISHExecutionCoordinator {
    static let shared = ISHExecutionCoordinator()

    // MARK: - State

    /// Tracks the most recently mounted session — kept for backward compat
    /// with consumers that still rely on `mountedSessionIdSnapshot`. Once the
    /// change-event tracker is fully migrated to fs_context based routing
    /// this field can go away.
    private(set) var mountedSessionId: String? {
        didSet {
            Self.mountedSidLock.lock()
            Self.mountedSidStorage = mountedSessionId
            Self.mountedSidLock.unlock()
        }
    }

    /// Per-session ordered queue of in-flight execute() PIDs. The head is
    /// the currently-running command for that session; the tail is the next
    /// to run. A session can only have one shell at a time today; cross-
    /// session commands run concurrently.
    private struct InflightExec {
        let id: UUID
        var pid: Int32 = 0
        let startTime: Date
        var preempted: Bool = false
        var waiterContinuation: CheckedContinuation<Void, Error>?
    }
    private var perSessionInflight: [String: [InflightExec]] = [:]

    /// Set of sessions for which the static (memory/skills/shared/external)
    /// mount layer has been initialized. The per-session 4 buckets are now
    /// handled by MinisFsRouter's hook, not by bind_mount, so they don't
    /// need to be re-mounted on session switch.
    private var staticMountsInitialized: Set<String> = []

    /// Maps linux mount paths (e.g. "/var/minis/memory") to their host
    /// persistent URLs. Holds only the static (global) mounts and external
    /// folders today. Per-session buckets are no longer in this map; use
    /// MinisFsRouter to resolve their host paths.
    private var mountedPaths: [String: URL] = [:]

    // MARK: - Constants

    private let preemptTimeout: TimeInterval = 600 // 10 minutes

    // MARK: - Public API

    /// Execute a shell command within a session's mount context.
    /// Fully concurrent — both cross-session AND same-session calls fan
    /// out in parallel. Each invocation forks an independent /bin/sh
    /// process with its own pipes and fs_context token; there is no
    /// shared PTY to serialize behind.
    /// [T-concurrent-tools 2026-05-25]
    func execute(
        sessionId: String,
        command: String,
        timeout: TimeInterval?,
        lineCallback: @escaping (String) -> Void,
        pidCallback: @escaping (Int32) -> Void
    ) async throws -> ISHCommandResult {
        guard ISHKernel.shared.isBooted else {
            throw ISHCoordinatorError.kernelNotBooted
        }

        // Register ourselves in the in-flight table purely for
        // stop/preempt visibility — we do NOT wait for prior entries.
        let myId = UUID()
        let head = InflightExec(id: myId, startTime: Date())
        perSessionInflight[sessionId, default: []].append(head)
        syncInflightPidSnapshot()

        try Task.checkCancellation()

        mountedSessionId = sessionId
        ensureStaticMountsInitialized(for: sessionId)

        let fsContext = MinisFsRouter.shared.context(for: sessionId)

        defer {
            // Dequeue self. No waiters to wake — concurrent dispatch.
            if var queue = perSessionInflight[sessionId],
               let idx = queue.firstIndex(where: { $0.id == myId }) {
                queue.remove(at: idx)
                perSessionInflight[sessionId] = queue.isEmpty ? nil : queue
                syncInflightPidSnapshot()
            }
        }

        return try await runCommand(
            sessionId: sessionId,
            myId: myId,
            fsContext: fsContext,
            command: command,
            timeout: timeout,
            lineCallback: lineCallback,
            pidCallback: pidCallback
        )
    }

    /// Called from loadSession() for UI readiness. Per-session buckets are
    /// routed by MinisFsRouter's hook now (no bind-mount swap needed), so
    /// this just kicks off the one-time static mount layer and remembers
    /// the most-recently-active session for backward-compat consumers.
    func mountForSession(_ sessionId: String) {
        // [MOUNT-DIAG] So we can see the exact ordering between session load
        // and the deferred external-mount snapshot apply.
        let booted = ISHKernel.shared.isBooted
        let snapCount = Self.getExternalMountSnapshot().count
        logger.info("MOUNT-DIAG mountForSession-entry sid=\(sessionId) booted=\(booted) externalSnapshotCount=\(snapCount) staticInit=\(self.staticMountsInitialized.count)")
        guard booted else {
            logger.warning("MOUNT mountForSession(\(sessionId)) skipped — kernel not booted")
            mountedSessionId = nil
            return
        }
        _ = MinisFsRouter.shared.context(for: sessionId)
        ensureStaticMountsInitialized(for: sessionId)
        mountedSessionId = sessionId
    }

    /// Ensure the static mount layer (memory/skills/shared + external folders)
    /// is in place. Safe to call repeatedly; idempotent at the iSH kernel level.
    func ensureMounted(for sessionId: String) {
        guard ISHKernel.shared.isBooted else {
            logger.warning("MOUNT ensureMounted(\(sessionId)) skipped — kernel not booted")
            return
        }
        ensureStaticMountsInitialized(for: sessionId)
        if mountedSessionId == nil { mountedSessionId = sessionId }
    }

    private func ensureStaticMountsInitialized(for sessionId: String) {
        // Static mounts are session-independent, but performMount needs a sid
        // to log against; once the first session has initialized them, all
        // others are no-ops. The per-session buckets are NOT touched here —
        // they're hooked by MinisFsRouter.
        if !staticMountsInitialized.isEmpty { return }
        performMount(sessionId)
        staticMountsInitialized.insert(sessionId)
    }

    #if DEBUG
    /// DEBUG-only snapshot of the current mount table, for diagnostic logging
    /// from call sites that need to print mount state.
    func debugMountSnapshot() -> (sessionId: String?, paths: [String: URL]) {
        return (mountedSessionId, mountedPaths)
    }
    #endif

    /// Resolve a Linux path under /var/minis/ to its current host URL.
    /// Tries the static bind-mount table first (memory/skills/shared +
    /// external folders), then falls back to MinisFsRouter for the per-
    /// session buckets. Per-session lookups need a sessionId because the
    /// router routes by fs_context; pass it via `sessionId` for those paths.
    func hostURL(for linuxPath: String, sessionId: String? = nil) -> URL? {
        for (prefix, hostBase) in mountedPaths {
            if linuxPath == prefix {
                return hostBase
            }
            if linuxPath.hasPrefix(prefix + "/") {
                let sub = String(linuxPath.dropFirst(prefix.count + 1))
                return hostBase.appendingPathComponent(sub)
            }
        }
        if let sid = sessionId ?? mountedSessionId,
           let url = MinisFsRouter.shared.hostURL(forGuest: linuxPath, sid: sid) {
            return url
        }
        return nil
    }

    /// Kill all currently-running commands across every session. Kept as the
    /// "broad stop" used by emergency cancel paths.
    func stopCurrentCommand() {
        for (sid, queue) in perSessionInflight {
            for entry in queue where entry.pid > 0 {
                logger.info("Coordinator stopping command pid=\(entry.pid) sid=\(sid)")
                ISHShellExecutor.killProcessGroup(entry.pid)
            }
        }
    }

    /// Kill any in-flight command(s) for the given session only. Used by the
    /// per-session cancel path.
    func stopCurrentCommand(sessionId: String) {
        guard let queue = perSessionInflight[sessionId] else { return }
        for entry in queue where entry.pid > 0 {
            logger.info("Coordinator stopping command pid=\(entry.pid) sid=\(sessionId)")
            ISHShellExecutor.killProcessGroup(entry.pid)
        }
    }

    /// Drop in-flight state when a session is deleted/cleared. Does not kill
    /// processes — the caller should stopCurrentCommand(sessionId:) first if
    /// it wants a hard stop.
    func sessionDidTerminate(sessionId: String) {
        perSessionInflight[sessionId] = nil
        syncInflightPidSnapshot()
        staticMountsInitialized.remove(sessionId)
        if mountedSessionId == sessionId { mountedSessionId = nil }
    }

    // MARK: - Private

    /// Kill the head command in `sessionId`'s queue if it has exceeded the
    /// preempt timeout AND has at least one waiter queued behind it.
    @discardableResult
    private func preemptIfNeeded(sessionId: String) -> Bool {
        guard let queue = perSessionInflight[sessionId],
              queue.count >= 2,
              let head = queue.first,
              head.pid > 0,
              Date().timeIntervalSince(head.startTime) >= preemptTimeout
        else { return false }
        logger.warning("Preempting command (pid \(head.pid), sid \(sessionId)) — exceeded \(self.preemptTimeout)s with \(queue.count - 1) waiter(s)")
        ISHShellExecutor.killProcess(head.pid, withSignal: 15)
        return true
    }

    /// Remove a cancelled waiter from its session queue.
    private func cancelInflightWaiter(sessionId: String, id: UUID) {
        guard var queue = perSessionInflight[sessionId],
              let idx = queue.firstIndex(where: { $0.id == id })
        else { return }
        let entry = queue.remove(at: idx)
        perSessionInflight[sessionId] = queue.isEmpty ? nil : queue
        syncInflightPidSnapshot()
        entry.waiterContinuation?.resume(throwing: CancellationError())
    }

    /// Bridge ISHShellExecutor's callback API to async/await.
    private func runCommand(
        sessionId: String,
        myId: UUID,
        fsContext: UInt64,
        command: String,
        timeout: TimeInterval?,
        lineCallback: @escaping (String) -> Void,
        pidCallback: @escaping (Int32) -> Void
    ) async throws -> ISHCommandResult {
        let effectiveTimeout = timeout ?? 300 // 5 minute default

        // Load user-defined env vars (nonisolated, reads from disk + Keychain)
        let customEnv = EnvVarStore.shared.allAsDict()

        // Feed the command as a script via stdin pipe to /bin/sh.
        // This avoids shell quoting issues with multi-line or special-char commands.
        // The command runs inside a subshell with stdin redirected to /dev/null,
        // so child processes (e.g. stdio MCP servers) get immediate EOF instead of
        // inheriting the spent pipe and hanging forever. Pipe input within the
        // command (e.g. echo "data" | cmd) still works because the shell creates
        // new pipes that override the subshell's fd 0.
        //
        // The `{ exec 0</dev/null; } 2>/dev/null` form only silences stderr for the
        // exec builtin itself (in case /dev/null is missing from meta.db), without
        // persistently redirecting the subshell's fd 2 — the command's own stderr
        // must still reach us so `--help` text and error messages are captured.
        // [T-heredoc-trailing-newline] The command is wrapped in a subshell and
        // must be followed by a NEWLINE before the closing `)`. Without it, a
        // command ending in a heredoc terminator (…\nEOF) becomes `…\nEOF)` —
        // the `)` glued onto the terminator line, so ash never recognises the
        // terminator and fails deterministically with
        // `unexpected end of file (expecting ")")`. A newline puts the closing
        // `)` on its own line; it is a no-op for every other command.
        let scriptContent = "cd /root\n({ exec 0</dev/null; } 2>/dev/null || true; \(command)\n)\n"
        let stdinData = scriptContent.data(using: .utf8)

        return try await withCheckedThrowingContinuation { continuation in
            // [T-ish-continuation-double-resume] `resumed` is read and written
            // from up to four different threads, so a bare `var` cannot guard
            // the continuation: the check and the set are separate operations
            // and two racers can both pass the check. Resuming a
            // CheckedContinuation twice is a fatalError — the EXC_BREAKPOINT
            // (SIGTRAP) crash reported 2026-08-14 21:26 on iOS 27 under
            // "github triggered action", i.e. a long command finishing right at
            // its timeout boundary.
            //
            // The racing paths and their queues:
            //   completion  — main queue, via processDidExit's dispatch_after
            //   completion  — GLOBAL UTILITY queue, via sweepStaleContexts →
            //                 finalizeContext (ISHShellExecutor.m:246); this is
            //                 why "both are on main" is not true
            //   pid < 0     — the calling thread, synchronously
            //   timeout     — main queue, via asyncAfter
            //
            // The lock covers ONLY the test-and-set. `continuation.resume` is
            // deliberately called OUTSIDE it: that hands control back to the
            // suspended async function, and running it under a lock is exactly
            // the "arbitrary caller code while holding a lock" hazard that
            // ISHShellExecutor's own sweeper documents. Both the lock and the
            // flag are per-call locals, so there is no cross-command contention.
            let resumeLock = NSLock()
            var resumed = false
            /// True for exactly one caller — the one that owns the resume.
            func claimResume() -> Bool {
                resumeLock.lock()
                defer { resumeLock.unlock() }
                if resumed { return false }
                resumed = true
                return true
            }
            var timeoutWork: DispatchWorkItem?

            let pid = ISHShellExecutor.executeExecutable(
                "/bin/sh",
                arguments: nil,
                environment: customEnv.isEmpty ? nil : customEnv,
                stdinData: stdinData,
                fsContext: fsContext,
                lineCallback: { line, _ in
                lineCallback(line)
            }, completion: { [weak self] result in
                guard claimResume() else { return }
                timeoutWork?.cancel()

                Task { await self?.recordInflightPid(sessionId: sessionId, id: myId, pid: 0) }

                var output = result.output
                let errOutput = result.errorOutput
                if !errOutput.isEmpty {
                    output += (output.isEmpty ? "" : "\n") + errOutput
                }

                if result.exitCode != 0 && !output.contains("exit code") {
                    output += "\n(exit code: \(result.exitCode))"
                }

                if output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    output = "(command completed with no output)"
                }

                logger.info("Command completed. Exit code: \(result.exitCode), output: \(output.count) chars")
                continuation.resume(returning: ISHCommandResult(output: output, exitCode: Int(result.exitCode)))
            })

            if pid < 0 {
                guard claimResume() else { return }
                let errorMsg: String
                switch ISHShellExecutorError(rawValue: Int(pid)) {
                case .processCreationFailed:
                    errorMsg = "Failed to create process"
                case .execFailed:
                    errorMsg = "Failed to execute command"
                case .timeout:
                    errorMsg = "Command timed out"
                case .cancelled:
                    errorMsg = "Command cancelled"
                default:
                    errorMsg = "Execution error (code: \(pid))"
                }
                logger.error("ISHShellExecutor failed: \(errorMsg)")
                continuation.resume(returning: ISHCommandResult(output: "Error: \(errorMsg)", exitCode: -1))
                return
            }

            // Track PID
            Task { await self.recordInflightPid(sessionId: sessionId, id: myId, pid: pid) }
            pidCallback(pid)

            // Timeout safety net
            let work = DispatchWorkItem { [weak self] in
                guard claimResume() else { return }
                // [T-ish-thread-leak] Reap the WHOLE process group, not just the
                // root pid. A bare killProcess(pid, SIGTERM) leaves children +
                // their emu task threads alive after the continuation resumes —
                // they accumulate (gh-NNN threads, footprint climbs → jetsam).
                // killProcessGroup sweeps pgid + descendants with a SIGTERM→
                // SIGKILL escalation, so a hung/ignoring-SIGTERM tree still dies.
                ISHShellExecutor.killProcessGroup(pid)
                // [T-ish-shell-timeout-leak] Release the execution context
                // ourselves instead of waiting for the exit notification that
                // killProcessGroup is supposed to provoke. That notification
                // never arrives when the task has already been reaped as a
                // zombie, and the context then leaks with its two reader
                // threads polling a dead pipe forever — about thirty timeouts
                // exhaust the concurrent queue and no command can run again
                // until the device is restarted.
                //
                // Called AFTER the kill so output written before the deadline
                // is still readable, and it is a no-op if the command turned
                // out to exit in time.
                ISHShellExecutor.finalizeTimedOutPid(pid)
                Task { await self?.recordInflightPid(sessionId: sessionId, id: myId, pid: 0) }
                pidCallback(0)
                logger.warning("Command timed out after \(effectiveTimeout)s — killing process group pid=\(pid)")
                continuation.resume(returning: ISHCommandResult(output: "(command timed out after \(Int(effectiveTimeout))s)", exitCode: -1))
            }
            timeoutWork = work
            DispatchQueue.main.asyncAfter(
                deadline: .now() + effectiveTimeout,
                execute: work
            )
        }
    }

    private func recordInflightPid(sessionId: String, id: UUID, pid: Int32) {
        guard var queue = perSessionInflight[sessionId],
              let idx = queue.firstIndex(where: { $0.id == id })
        else { return }
        queue[idx].pid = pid
        perSessionInflight[sessionId] = queue
        // The pid is only known here, so this is the sync that actually makes
        // a running command killable from the nonisolated stop path.
        syncInflightPidSnapshot()
    }

    // MARK: - Mount Logic

    /// Bind-mount a session's minis directories into iSH-visible /var/minis/.
    private func performMount(_ sid: String) {
        let mountStart = CFAbsoluteTimeGetCurrent()
        let fm = FileManager.default

        logger.info("MOUNT START for session \(sid)")

        // [MOUNT-DIAG] Capture external-snapshot state as performMount sees it.
        // If this prints 0 entries on a cold launch where the user has mounts
        // configured, the cold-start race is confirmed: performMount ran
        // before MountedFoldersManager.activateAll's resolve task pushed the
        // snapshot. The subsequent applyExternalMountSnapshot() (kicked from
        // pushExternalMountSnapshot once resolve finishes) should fix it up.
        let extSnap = Self.getExternalMountSnapshot()
        let booted = ISHKernel.shared.isBooted
        logger.info("MOUNT-DIAG performMount-entry sid=\(sid) booted=\(booted) snapshotCount=\(extSnap.count) staticInit=\(self.staticMountsInitialized.count)")

        // Ensure parent dirs exist in meta.db
        ensureParentDirsInMetaDB(for: "\(AIChatViewModel.minisLinuxBaseDir)/placeholder")
        ensureFakefsMetadata(for: AIChatViewModel.minisLinuxBaseDir, isDirectory: true)

        // Only the global (cross-session) directories are bind-mounted here.
        // The per-session buckets (offloads/attachments/workspace/browser) are
        // routed dynamically by MinisFsRouter's path-translate hook based on
        // the calling task's fs_context, so they don't need a static mount and
        // don't need to be swapped on session change.
        let subdirs: [(persistDir: URL, linuxDir: String)] = [
            (AIChatViewModel.minisMemoryPersistentDir, AIChatViewModel.minisMemoryLinuxDir),
            (AIChatViewModel.minisSkillsPersistentDir, AIChatViewModel.minisSkillsLinuxDir),
            (AIChatViewModel.minisSharedPersistentDir, AIChatViewModel.minisSharedLinuxDir),
            (AIChatViewModel.minisMcpServersPersistentDir, AIChatViewModel.minisMcpServersLinuxDir),
        ]

        for (idx, (persistDir, linuxDir)) in subdirs.enumerated() {
            logger.info("MOUNT [\(idx)] === Mounting subdir ===")
            logger.info("MOUNT [\(idx)] linuxDir        = \"\(linuxDir)\"")
            logger.info("MOUNT [\(idx)] linuxDir.count  = \(linuxDir.count)")
            logger.info("MOUNT [\(idx)] linuxDir bytes  = \(Array(linuxDir.utf8).map { String(format: "%02x", $0) }.joined(separator: " "))")
            logger.info("MOUNT [\(idx)] persistDir      = \"\(persistDir.path)\"")
            logger.info("MOUNT [\(idx)] persistDir.count= \(persistDir.path.count)")

            // Ensure persistent directory exists on host
            try? fm.createDirectory(at: persistDir, withIntermediateDirectories: true)

            // Remove old data directory or stale symlink so fakefs_bind_mount can succeed.
            // We must clear the path at data/<linuxDir> so that the C layer can create
            // a symlink there pointing to persistDir.
            if let hostDir = resolveHostPath(linuxDir) {
                logger.info("MOUNT [\(idx)] resolved hostDir = \"\(hostDir.path)\"")
                // Use lstat (not stat) so we can distinguish symlinks from directories.
                // FileManager.attributesOfItem follows symlinks, which breaks detection.
                var lstatBuf = stat()
                let lstatOk = lstat(hostDir.path, &lstatBuf) == 0

                if lstatOk && (lstatBuf.st_mode & S_IFMT) == S_IFLNK {
                    // It's a symlink — check if it resolves to the right place.
                    // Use resolvingSymlinksInPath on both sides to normalize aliases
                    // (e.g. GroupContainersAlias vs "Group Containers" on iOS).
                    let resolvedDest = hostDir.resolvingSymlinksInPath().standardized.path
                    let expectedDest = persistDir.resolvingSymlinksInPath().standardized.path
                    if resolvedDest != expectedDest {
                        logger.info("MOUNT [\(idx)] removing stale symlink (resolved to \(resolvedDest), expected \(expectedDest))")
                        unlink(hostDir.path)
                    } else {
                        logger.info("MOUNT [\(idx)] symlink already correct, skipping removal")
                    }
                } else if lstatOk {
                    // Real file or directory — migrate contents to persistent storage, then remove
                    let isDir = (lstatBuf.st_mode & S_IFMT) == S_IFDIR
                    if isDir {
                        logger.info("MOUNT [\(idx)] migrating real dir to persistDir")
                        if let enumerator = fm.enumerator(at: hostDir, includingPropertiesForKeys: nil, options: []) {
                            while let srcURL = enumerator.nextObject() as? URL {
                                let rel = srcURL.path.replacingOccurrences(of: hostDir.path, with: "")
                                let dstURL = persistDir.appendingPathComponent(rel)
                                if !fm.fileExists(atPath: dstURL.path) {
                                    try? fm.createDirectory(at: dstURL.deletingLastPathComponent(), withIntermediateDirectories: true)
                                    try? fm.copyItem(at: srcURL, to: dstURL)
                                }
                            }
                        }
                    }
                    // Force-remove whatever is at hostDir (file, dir, or non-empty dir)
                    // Safety: only remove paths under data/var/minis/ to avoid accidentally
                    // deleting other fakefs directories (e.g. data/dev/) due to path bugs.
                    let dataVarMinis = RootfsManager.shared.dataPath.appendingPathComponent("var/minis").path
                    guard hostDir.path.hasPrefix(dataVarMinis) else {
                        logger.error("MOUNT [\(idx)] SAFETY: refusing removeItem outside var/minis: \(hostDir.path)")
                        break
                    }
                    let kind = isDir ? "dir" : "file"
                    logger.info("MOUNT [\(idx)] removing real \(kind) at \(hostDir.path)")
                    do {
                        try fm.removeItem(at: hostDir)
                    } catch {
                        logger.error("MOUNT [\(idx)] removeItem failed: \(error.localizedDescription)")
                    }
                }
            }

            // Bind mount: creates symlink in data/ pointing to persistDir
            // Log files in the persistent directory before bind mount
            let persistContents = (try? fm.contentsOfDirectory(atPath: persistDir.path)) ?? []
            logger.info("MOUNT [\(idx)] persistDir has \(persistContents.count) items: \(persistContents.joined(separator: ", "))")

            logger.info("MOUNT [\(idx)] calling bindMountPath(\"\(linuxDir)\", toHostPath: \"\(persistDir.path)\")")
            let err = ISHKernel.shared.bindMountPath(linuxDir, toHostPath: persistDir.path)
            if err < 0 {
                logger.error("MOUNT [\(idx)] bind mount FAILED: \(linuxDir) -> \(persistDir.path) err=\(err)")
            } else {
                logger.info("MOUNT [\(idx)] bind mount OK: \(linuxDir) -> \(persistDir.path)")
                mountedPaths[linuxDir] = persistDir

                // Verify: list files visible via the host symlink path after mount
                if let hostDir = resolveHostPath(linuxDir) {
                    // Check what the symlink points to
                    var lstatBuf2 = stat()
                    if lstat(hostDir.path, &lstatBuf2) == 0 && (lstatBuf2.st_mode & S_IFMT) == S_IFLNK {
                        let symlinkTarget = try? fm.destinationOfSymbolicLink(atPath: hostDir.path)
                        logger.info("MOUNT [\(idx)] symlink at \(hostDir.path) -> \(symlinkTarget ?? "nil")")
                    } else {
                        logger.info("MOUNT [\(idx)] hostDir \(hostDir.path) is NOT a symlink (or doesn't exist)")
                    }
                    let hostContents = (try? fm.contentsOfDirectory(atPath: hostDir.path)) ?? []
                    logger.info("MOUNT [\(idx)] hostDir after mount has \(hostContents.count) items: \(hostContents.joined(separator: ", "))")
                }

                // Register existing files in meta.db so iSH fakefs can see them (recursive)
                // Collect all entries first, then batch-insert in a single transaction
                var metaEntries: [(path: String, isDirectory: Bool)] = [(linuxDir, true)]
                if let enumerator = fm.enumerator(at: persistDir, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) {
                    while let itemURL = enumerator.nextObject() as? URL {
                        let relativePath = itemURL.path.dropFirst(persistDir.path.count + 1) // strip prefix + "/"
                        let linuxFilePath = "\(linuxDir)/\(relativePath)"
                        let isDir = (try? itemURL.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
                        metaEntries.append((linuxFilePath, isDir))
                    }
                }
                let metaStart = CFAbsoluteTimeGetCurrent()
                batchEnsureFakefsMetadata(metaEntries)
                let metaMs = (CFAbsoluteTimeGetCurrent() - metaStart) * 1000
                logger.info("MOUNT [\(idx)] registered \(metaEntries.count) meta.db entries (batch) in \(String(format: "%.1f", metaMs))ms")
            }
        }

        // After session-scoped mounts, also bind-mount every user-mounted
        // external folder (see MountedFoldersManager) so iSH shell can read/write
        // them just like /var/minis/shared.
        mountExternalFoldersLocked()

        let elapsed = (CFAbsoluteTimeGetCurrent() - mountStart) * 1000
        logger.info("MOUNT bind-mounted \(subdirs.count) dirs for session \(sid) in \(String(format: "%.1f", elapsed))ms")
    }

    /// One entry in an external-folder mount snapshot.
    struct ExternalMountSpec: Sendable {
        let linuxDir: String
        let hostPath: String
        /// When true, iSH will expose this mount as a read-only directory
        /// (mode 0555), so shell writes fail with EACCES.
        let readOnly: Bool
    }

    /// Thread-safe shared snapshot of external-folder mounts. Written by
    /// MountedFoldersManager (main actor) and read by the coordinator actor
    /// during `performMount`. Using a lock instead of actor-isolated state
    /// avoids cross-actor hops during synchronous mount code.
    private static let externalMountLock = NSLock()
    nonisolated(unsafe) private static var externalMountSnapshotStorage: [ExternalMountSpec] = []

    /// Lock-protected mirror of `mountedSessionId`, kept in sync by the
    /// actor's `didSet` observer. Exposed via `mountedSessionIdSnapshot` so
    /// callers running on other actors (e.g. `BrowserUseOffloadBridge` on the
    /// main actor) can read the current mount owner without awaiting this
    /// actor. Without this, every cross-actor peek was a suspension point
    /// that serialized behind whatever was queued on the coordinator —
    /// which, during shell execution, is a continuation parked on the
    /// main-queue completion hop, starving the bridge's MainActor task.
    private static let mountedSidLock = NSLock()
    nonisolated(unsafe) private static var mountedSidStorage: String?

    /// [T-shell-stop-blocked-by-actor] Lock-protected mirror of the live PIDs
    /// in `perSessionInflight`, so STOP can kill them without entering this
    /// actor.
    ///
    /// The stop path used to be actor-isolated, which made it useless in the
    /// exact situation it exists for: `Task { await stop… }` has to queue on
    /// the coordinator, and when a guest process is wedged holding iSH's
    /// global `pids_lock` the coordinator is precisely what is blocked. The
    /// user pressed Stop and nothing happened — the field crash on
    /// 2026-08-23 20:11 has no `Coordinator stopping command` line anywhere,
    /// because the kill never got a turn to run.
    ///
    /// Killing is a plain `ISHShellExecutor` call that needs no actor state
    /// beyond the pid, so it reads from here instead. Same rationale as
    /// `mountedSidStorage` directly above.
    private static let inflightPidLock = NSLock()
    nonisolated(unsafe) private static var inflightPidStorage: [String: [Int32]] = [:]

    /// Mirror the actor's in-flight table into the lock-protected snapshot.
    /// Called from every site that mutates `perSessionInflight`.
    private func syncInflightPidSnapshot() {
        var snap: [String: [Int32]] = [:]
        for (sid, queue) in perSessionInflight {
            let pids = queue.map(\.pid).filter { $0 > 0 }
            if !pids.isEmpty { snap[sid] = pids }
        }
        Self.inflightPidLock.lock()
        Self.inflightPidStorage = snap
        Self.inflightPidLock.unlock()
    }

    /// Kill every in-flight command, or just one session's, WITHOUT entering
    /// the actor. Safe to call from any thread; returns how many pids it
    /// signalled so the caller can log a real outcome.
    @discardableResult
    nonisolated static func stopAllNonisolated(sessionId: String? = nil) -> Int {
        inflightPidLock.lock()
        let snapshot = inflightPidStorage
        inflightPidLock.unlock()

        var killed = 0
        for (sid, pids) in snapshot where sessionId == nil || sid == sessionId {
            for pid in pids where pid > 0 {
                logger.info("Coordinator stopping command pid=\(pid) sid=\(sid) (nonisolated)")
                ISHShellExecutor.killProcessGroup(pid)
                killed += 1
            }
        }
        return killed
    }

    /// Read the current mount owner without an actor hop. Safe to call from
    /// any thread. Returns nil when no session is mounted (kernel not booted
    /// or session just terminated).
    nonisolated static var mountedSessionIdSnapshot: String? {
        mountedSidLock.lock()
        defer { mountedSidLock.unlock() }
        return mountedSidStorage
    }

    static func setExternalMountSnapshot(_ snapshot: [ExternalMountSpec]) {
        externalMountLock.lock()
        externalMountSnapshotStorage = snapshot
        externalMountLock.unlock()
    }

    private static func getExternalMountSnapshot() -> [ExternalMountSpec] {
        externalMountLock.lock()
        defer { externalMountLock.unlock() }
        return externalMountSnapshotStorage
    }

    /// Tracks the set of external-folder linux paths currently bind-mounted so
    /// we can unbind stale ones when the user renames or removes a mount.
    private var externalMountLinuxPaths: Set<String> = []

    /// Apply the current shared snapshot to the kernel bind-mount table.
    /// Called by MountedFoldersManager after it updates the shared snapshot.
    /// No-op if kernel not booted (the snapshot will be applied on the next
    /// performMount when shell execution begins).
    func applyExternalMountSnapshot() {
        // [MOUNT-DIAG] Caller-side dump so we can see exactly what state the
        // coordinator was in when MountedFoldersManager pushed a snapshot —
        // particularly whether the kernel had booted by the time we got
        // here (vs the push falling on deaf ears at cold start).
        let snapshot = Self.getExternalMountSnapshot()
        let booted = ISHKernel.shared.isBooted
        logger.info("MOUNT-DIAG apply-entry snapshotCount=\(snapshot.count) booted=\(booted) staticInit=\(self.staticMountsInitialized.count) currentLinuxPaths=\(self.externalMountLinuxPaths.sorted())")
        for (i, e) in snapshot.enumerated() {
            logger.info("MOUNT-DIAG apply-entry [\(i)] \(e.linuxDir) -> \(e.hostPath) (ro=\(e.readOnly))")
        }
        guard booted else {
            logger.info("MOUNT external: kernel not booted, will apply snapshot on next performMount")
            return
        }
        mountExternalFoldersLocked()
    }

    /// Internal: actually perform the external-folder bind mounts.
    /// Reconciles the bind-mount table against the shared snapshot —
    /// unbinds stale ones, binds new ones, updates changed host paths.
    /// Caller guarantees kernel is booted.
    private func mountExternalFoldersLocked() {
        let snapshot = Self.getExternalMountSnapshot()
        logger.info("MOUNT external: applying snapshot with \(snapshot.count) entries")
        let desiredPaths = Set(snapshot.map(\.linuxDir))

        // Unbind any stale external mounts (renamed or removed entries).
        for stale in externalMountLinuxPaths.subtracting(desiredPaths) {
            let err = ISHKernel.shared.bindUnmountPath(stale)
            if err < 0 {
                logger.warning("MOUNT external: bindUnmountPath(\(stale)) err=\(err)")
            } else {
                logger.info("MOUNT external: unbound stale \(stale)")
            }
            mountedPaths.removeValue(forKey: stale)
        }
        externalMountLinuxPaths = desiredPaths

        guard !snapshot.isEmpty else { return }

        // Ensure the parent /var/minis/mounts dir exists in meta.db so fakefs
        // can list it.
        ensureParentDirsInMetaDB(for: "\(AIChatViewModel.minisMountsLinuxDir)/placeholder")
        ensureFakefsMetadata(for: AIChatViewModel.minisMountsLinuxDir, isDirectory: true)

        for (idx, entry) in snapshot.enumerated() {
            logger.info("MOUNT external [\(idx)] \(entry.linuxDir) -> \(entry.hostPath) (ro=\(entry.readOnly))")

            // [MOUNT-DIAG] Verify the host path is reachable from this process
            // BEFORE asking the kernel to bind it. Reports stat result + iCloud
            // FileProvider container state — common failure shapes:
            //   - ENOENT: bookmark resolved to a path that no longer exists
            //   - EPERM: security scope wasn't acquired (or expired)
            //   - EACCES: scope acquired but parent dir not readable yet (XPC
            //             still warming up after cold start)
            var hostStat = stat()
            let statRC = stat(entry.hostPath, &hostStat)
            if statRC == 0 {
                let isDir = (hostStat.st_mode & S_IFMT) == S_IFDIR
                logger.info("MOUNT-DIAG host-pre-check [\(idx)] OK isDir=\(isDir) mode=0o\(String(hostStat.st_mode & 0o777, radix: 8)) size=\(hostStat.st_size)")
            } else {
                let errStr = String(cString: strerror(errno))
                logger.error("MOUNT-DIAG host-pre-check [\(idx)] stat() FAILED errno=\(errno) (\(errStr)) — host path unreachable from app sandbox; bind will likely still 'succeed' but reads will fail")
            }

            let bindStart = CFAbsoluteTimeGetCurrent()
            let err = ISHKernel.shared.bindMountPath(
                entry.linuxDir,
                toHostPath: entry.hostPath,
                readOnly: entry.readOnly
            )
            let bindMs = (CFAbsoluteTimeGetCurrent() - bindStart) * 1000
            if err < 0 {
                logger.error("MOUNT external [\(idx)] bind mount FAILED err=\(err) bindMs=\(String(format: "%.1f", bindMs))")
                continue
            }
            logger.info("MOUNT-DIAG bind [\(idx)] OK err=\(err) bindMs=\(String(format: "%.1f", bindMs))")
            mountedPaths[entry.linuxDir] = URL(fileURLWithPath: entry.hostPath)

            // Register the top-level mount dir in meta.db. We deliberately do
            // NOT enumerate + register every file recursively: external vaults
            // can be huge, and fakefs auto-creates inodes on demand via
            // bind_mount_ensure_inode() when the shell actually touches a path.
            ensureFakefsMetadata(for: entry.linuxDir, isDirectory: true)

            // [MOUNT-DIAG] Post-bind verification: try to list one host-path
            // entry. If this comes back empty/fails after a successful stat
            // above, the security scope likely lapsed between resolve and bind.
            if let urls = try? FileManager.default.contentsOfDirectory(at: URL(fileURLWithPath: entry.hostPath), includingPropertiesForKeys: nil) {
                let names = urls.prefix(3).map { $0.lastPathComponent }
                logger.info("MOUNT-DIAG host-post-check [\(idx)] readable, \(urls.count) entries — sample: \(names)")
            } else {
                logger.error("MOUNT-DIAG host-post-check [\(idx)] contentsOfDirectory FAILED — host scope likely not held; iSH `ls` will return empty")
            }
        }

        logger.info("MOUNT external: bind-mounted \(snapshot.count) external folder(s)")
    }

    // MARK: - Fakefs Metadata (inline copies for coordinator use)

    private func resolveHostPath(_ linuxPath: String) -> URL? {
        guard linuxPath.hasPrefix("/"), !linuxPath.contains("..") else { return nil }
        let relative = String(linuxPath.dropFirst())
        if relative.isEmpty {
            return RootfsManager.shared.dataPath
        }
        return RootfsManager.shared.dataPath.appendingPathComponent(relative)
    }

    private static let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private func bindPathBlob(_ stmt: OpaquePointer, index: Int32, path: String) {
        let utf8 = Array(path.utf8)
        sqlite3_bind_blob(stmt, index, utf8, Int32(utf8.count), Self.SQLITE_TRANSIENT)
    }

    private func ensureFakefsMetadata(for linuxPath: String, isDirectory: Bool) {
        let metaDBPath = RootfsManager.shared.rootfsPath.appendingPathComponent("meta.db").path
        var db: OpaquePointer?
        guard sqlite3_open_v2(metaDBPath, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK,
              let db else { return }
        defer { sqlite3_close(db) }

        var checkStmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT inode FROM paths WHERE path = ?", -1, &checkStmt, nil) == SQLITE_OK,
              let checkStmt else { return }
        defer { sqlite3_finalize(checkStmt) }

        bindPathBlob(checkStmt, index: 1, path: linuxPath)

        if sqlite3_step(checkStmt) == SQLITE_ROW {
            return
        }

        let mode: UInt32 = isDirectory ? 0o040755 : 0o100644
        var statBytes: [UInt8] = Array(repeating: 0, count: 16)
        let leMode = mode.littleEndian
        withUnsafeBytes(of: leMode) { src in
            for i in 0..<4 { statBytes[i] = src[i] }
        }

        var insertStatStmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "INSERT INTO stats (stat) VALUES (?)", -1, &insertStatStmt, nil) == SQLITE_OK,
              let insertStatStmt else { return }
        defer { sqlite3_finalize(insertStatStmt) }

        sqlite3_bind_blob(insertStatStmt, 1, statBytes, Int32(statBytes.count), Self.SQLITE_TRANSIENT)
        guard sqlite3_step(insertStatStmt) == SQLITE_DONE else { return }

        var insertPathStmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO paths (path, inode) VALUES (?, last_insert_rowid())", -1, &insertPathStmt, nil) == SQLITE_OK,
              let insertPathStmt else { return }
        defer { sqlite3_finalize(insertPathStmt) }

        bindPathBlob(insertPathStmt, index: 1, path: linuxPath)
        sqlite3_step(insertPathStmt)
    }

    private func ensureParentDirsInMetaDB(for linuxPath: String) {
        var current = (linuxPath as NSString).deletingLastPathComponent
        var dirs: [String] = []
        while current != "/" && !current.isEmpty {
            dirs.append(current)
            current = (current as NSString).deletingLastPathComponent
        }
        for dir in dirs.reversed() {
            ensureFakefsMetadata(for: dir, isDirectory: true)
        }
    }

    // MARK: - Batch Fakefs Metadata

    /// Register multiple paths in meta.db within a single SQLite connection and transaction.
    /// Much faster than calling `ensureFakefsMetadata` per-file for large directories.
    private func batchEnsureFakefsMetadata(_ entries: [(path: String, isDirectory: Bool)]) {
        guard !entries.isEmpty else { return }

        let metaDBPath = RootfsManager.shared.rootfsPath.appendingPathComponent("meta.db").path
        var db: OpaquePointer?
        guard sqlite3_open_v2(metaDBPath, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK,
              let db else { return }
        defer { sqlite3_close(db) }

        // Prepare reusable statements
        var checkStmt: OpaquePointer?
        var insertStatStmt: OpaquePointer?
        var insertPathStmt: OpaquePointer?

        guard sqlite3_prepare_v2(db, "SELECT inode FROM paths WHERE path = ?", -1, &checkStmt, nil) == SQLITE_OK,
              let checkStmt else { return }
        defer { sqlite3_finalize(checkStmt) }

        guard sqlite3_prepare_v2(db, "INSERT INTO stats (stat) VALUES (?)", -1, &insertStatStmt, nil) == SQLITE_OK,
              let insertStatStmt else { return }
        defer { sqlite3_finalize(insertStatStmt) }

        guard sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO paths (path, inode) VALUES (?, last_insert_rowid())", -1, &insertPathStmt, nil) == SQLITE_OK,
              let insertPathStmt else { return }
        defer { sqlite3_finalize(insertPathStmt) }

        // Pre-compute stat blobs for file and directory
        func makeStatBlob(mode: UInt32) -> [UInt8] {
            var bytes: [UInt8] = Array(repeating: 0, count: 16)
            let le = mode.littleEndian
            withUnsafeBytes(of: le) { src in
                for i in 0..<4 { bytes[i] = src[i] }
            }
            return bytes
        }
        let dirStatBlob = makeStatBlob(mode: 0o040755)
        let fileStatBlob = makeStatBlob(mode: 0o100644)

        // Begin transaction
        sqlite3_exec(db, "BEGIN TRANSACTION", nil, nil, nil)

        var inserted = 0
        for (path, isDir) in entries {
            // Check if already exists
            sqlite3_reset(checkStmt)
            sqlite3_clear_bindings(checkStmt)
            bindPathBlob(checkStmt, index: 1, path: path)
            if sqlite3_step(checkStmt) == SQLITE_ROW { continue }

            // Insert stat
            let statBlob = isDir ? dirStatBlob : fileStatBlob
            sqlite3_reset(insertStatStmt)
            sqlite3_clear_bindings(insertStatStmt)
            sqlite3_bind_blob(insertStatStmt, 1, statBlob, Int32(statBlob.count), Self.SQLITE_TRANSIENT)
            guard sqlite3_step(insertStatStmt) == SQLITE_DONE else { continue }

            // Insert path
            sqlite3_reset(insertPathStmt)
            sqlite3_clear_bindings(insertPathStmt)
            bindPathBlob(insertPathStmt, index: 1, path: path)
            if sqlite3_step(insertPathStmt) == SQLITE_DONE {
                inserted += 1
            }
        }

        sqlite3_exec(db, "COMMIT", nil, nil, nil)
        logger.info("MOUNT batchEnsureFakefsMetadata: \(entries.count) checked, \(inserted) inserted")
    }
}
