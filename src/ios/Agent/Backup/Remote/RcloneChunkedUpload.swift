import Foundation

private let logger = AppLogger(category: "Rclone")

/// Uploads a backup package to an rclone remote as ONE file, and reads
/// packages back.
///
/// ## History: this used to chunk
///
/// An earlier revision split the package into 8 MiB parts with a resume
/// journal, because rclone has no cross-process resume for a single file and
/// a killed 300-of-500 MB upload restarts from zero. That bought resumability
/// at a real cost: every package over 8 MiB lived on the server as a
/// directory of anonymous fragments — unusable by hand, dependent on our own
/// reassembly logic on the restore side, with a journal to maintain and a
/// parts/whole distinction leaking into every list/download call site.
///
/// The trade was re-evaluated (2026-08-16) and decided the other way: the
/// server always holds a clean, self-contained `.minisbak` a user can grab
/// with any client, and an interrupted upload simply re-runs. Interruption is
/// rare in practice — the upload runs under BackupBackgroundAssertion with
/// the user typically watching the progress screen — and a failure is
/// recorded per-destination in BackupHistory, so it is visible, not silent.
///
/// ## What "success" means
///
/// A transfer only counts once the remote object's SIZE matches the local
/// file. rclone's copyfile returning cleanly is necessary but not
/// sufficient — a FileProvider-style backend can acknowledge a truncated
/// write. Size is the strongest check that works on every backend (WebDAV
/// mostly has no server-side hashes; rclone's own transport already
/// error-checks each request).
///
/// The upload also goes to a hidden `.<name>.partial` first and is renamed
/// only after the size check passes — the same rule as the local
/// `.partial` rename in BackupDelivery (review I7): a half-uploaded file
/// must never be listable under the real package name.
/// Minimal thread-safe box for the progress-poll stop flag.
private final class Atomic<T> {
    private let lock = NSLock()
    private var _v: T
    init(_ v: T) { _v = v }
    var value: T {
        get { lock.lock(); defer { lock.unlock() }; return _v }
        set { lock.lock(); _v = newValue; lock.unlock() }
    }
}

enum RcloneTransfer {

    struct Progress {
        let bytesSent: Int64
        let totalBytes: Int64
        /// Bytes per second, or nil until there is enough of a sample to mean
        /// anything. Reported from where the bytes are actually observed
        /// rather than differenced in the UI, so it does not depend on how
        /// often the view happens to redraw.
        var bytesPerSecond: Double?

        var fraction: Double { totalBytes > 0 ? Double(bytesSent) / Double(totalBytes) : 0 }

        /// Seconds remaining at the current rate, or nil when that cannot be
        /// estimated. Deliberately nil rather than a made-up number: a wrong
        /// ETA is worse than none.
        var secondsRemaining: Double? {
            guard let rate = bytesPerSecond, rate > 0 else { return nil }
            let left = totalBytes - bytesSent
            guard left > 0 else { return 0 }
            return Double(left) / rate
        }
    }

    enum TransferError: LocalizedError {
        case unreadableSource
        case remoteRejected(String)
        case sizeMismatch(expected: Int64, actual: Int64)
        case cancelled

        var errorDescription: String? {
            switch self {
            case .unreadableSource: return AppLocalized("Couldn't read the backup file.")
            case .remoteRejected(let m): return m
            case .sizeMismatch(let expected, let actual):
                return AppLocalized("Upload verification failed: the server has \(actual) bytes but the backup is \(expected) bytes.")
            case .cancelled: return AppLocalized("Upload cancelled.")
            }
        }
    }

    // MARK: - Upload

    /// Upload `packageURL` into `remote` as a single object, verifying the
    /// uploaded size before the final rename. Retries the transfer once —
    /// a transient network drop shouldn't fail the whole backup run.
    static func upload(packageURL: URL,
                       remote: RcloneRemoteStore.Remote,
                       backupId: String = "",
                       isCancelled: @escaping () -> Bool = { false },
                       progress: ((Progress) -> Void)? = nil) throws {
        let fm = FileManager.default
        guard let size = (try? fm.attributesOfItem(atPath: packageURL.path)[.size] as? Int64)
        else { throw TransferError.unreadableSource }

        let name = packageURL.lastPathComponent
        let fs = RcloneRemoteStore.fsSpec(for: remote)
        // [T-backup-webdav-hidden-partial] NOT dot-prefixed. WebDAV gateways
        // routinely filter dotfiles out of directory listings: verified
        // against alist (Docker, 2026-08-19) — after uploading
        // `.probe.txt.partial`, `operations/stat` on the exact path returns it
        // (Size 26) while `operations/list` of its parent returns only the
        // non-dotted entries. `sweepAbandonedPartials` finds its victims by
        // LISTING, so on those servers it can never see, and never delete, the
        // scratch files it exists to reclaim: every interrupted upload strands
        // a full-size object on the user's NAS permanently.
        //
        // A plain suffix is invisible to that filter and still cannot be
        // mistaken for a backup — the restore list matches `.minisbak` exactly
        // (see `packages(in:)`), and the sweep below matches the suffix.
        let partial = remote.join("\(name).partial")
        let final = remote.join(name)

        // Clear scratch objects abandoned by earlier interrupted uploads.
        //
        // A killed transfer leaves its `.partial` on the server for good. They
        // are hidden from the restore list (dot-prefixed) so they never look
        // like backups, but they are full-size — one per interruption, each
        // potentially gigabytes, quietly consuming the user's NAS. Nothing
        // else ever removed them.
        //
        // Only THIS run's own scratch name is reused, so the sweep is by
        // pattern: dot-prefixed, `.partial`-suffixed, in our own folder.
        sweepAbandonedPartials(fs: fs, remote: remote, keeping: partial)

        var lastError: Error?
        for attempt in 1...2 {
            if isCancelled() { throw TransferError.cancelled }
            do {
                // Same reason as download(): copyfile blocks until the whole
                // package has been sent, so live bytes come from core/stats.
                let baseline = (try? RcloneBridge.rpc("core/stats")["bytes"] as? Int64) ?? 0
                let done = Atomic(false)
                if progress != nil {
                    Thread.detachNewThread {
                        while !done.value {
                            Thread.sleep(forTimeInterval: 0.5)
                            guard !done.value else { break }
                            let now = (try? RcloneBridge.rpc("core/stats")["bytes"] as? Int64) ?? 0
                            let moved = max(0, (now ?? 0) - (baseline ?? 0))
                            progress?(Progress(bytesSent: min(moved, size), totalBytes: size))
                        }
                    }
                }
                defer { done.value = true }

                _ = try RcloneBridge.rpc("operations/copyfile", [
                    "srcFs": packageURL.deletingLastPathComponent().path,
                    "srcRemote": name,
                    "dstFs": fs,
                    "dstRemote": partial,
                ])
                // The size check IS the success condition, not decoration.
                let uploaded = try remoteSize(fs: fs, remote: partial)
                guard uploaded == size else {
                    throw TransferError.sizeMismatch(expected: size, actual: uploaded)
                }
                _ = try RcloneBridge.rpc("operations/movefile", [
                    "srcFs": fs, "srcRemote": partial,
                    "dstFs": fs, "dstRemote": final,
                ])
                progress?(Progress(bytesSent: size, totalBytes: size))
                logger.info("[Rclone] uploaded \(name) (\(size) bytes, verified) -> \(remote.name), attempt \(attempt)")
                return
            } catch {
                lastError = error
                // A failed or unverified transfer must not leave the scratch
                // object behind — a later attempt overwrites it anyway, but a
                // permanent failure shouldn't strand junk on the server.
                _ = try? RcloneBridge.rpc("operations/deletefile",
                                          ["fs": fs, "remote": partial])
                logger.warning("[Rclone] upload attempt \(attempt) to '\(remote.name)' failed: \(error.localizedDescription)")
            }
        }
        throw TransferError.remoteRejected((lastError as? TransferError)?.errorDescription
                                           ?? lastError?.localizedDescription
                                           ?? "upload failed")
    }

    /// Delete `.partial` scratch objects left by interrupted uploads.
    ///
    /// Best-effort by design: a server that refuses the listing or the delete
    /// must not fail the backup that is about to run — the whole point is to
    /// reclaim space, not to gate the transfer on housekeeping.
    private static func sweepAbandonedPartials(fs: String,
                                               remote: RcloneRemoteStore.Remote,
                                               keeping current: String) {
        let dir = remote.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let out = try? RcloneBridge.rpc("operations/list", ["fs": fs, "remote": dir]),
              let list = out["list"] as? [[String: Any]] else { return }

        var removed = 0
        var bytes: Int64 = 0
        for e in list {
            let name = e["Name"] as? String ?? ""
            // [T-backup-webdav-hidden-partial] Suffix only. Requiring a leading
            // dot made this sweep a no-op on every server that hides dotfiles
            // from listings (alist, verified) — precisely the servers whose
            // leftovers it was written to reclaim. Dropping the prefix test
            // also lets it clear scratch files written by older builds, since
            // `.pkg.minisbak.partial` still ends in `.partial` and is still
            // matched here whenever the server does list it.
            guard e["IsDir"] as? Bool != true,
                  name.hasSuffix(".partial") else { continue }
            let path = remote.join(name)
            guard path != current else { continue }   // this run reuses its own
            bytes += (e["Size"] as? NSNumber)?.int64Value ?? 0
            if (try? RcloneBridge.rpc("operations/deletefile",
                                      ["fs": fs, "remote": path])) != nil {
                removed += 1
            }
        }
        if removed > 0 {
            logger.info("[Rclone] swept \(removed) abandoned .partial upload(s) from '\(remote.name)', freeing \(bytes) bytes")
        }
    }

    /// Size of a remote object, or throws if it does not exist.
    private static func remoteSize(fs: String, remote: String) throws -> Int64 {
        let out = try RcloneBridge.rpc("operations/stat", ["fs": fs, "remote": remote])
        guard let item = out["item"] as? [String: Any],
              let size = (item["Size"] as? NSNumber)?.int64Value
        else { throw TransferError.remoteRejected(AppLocalized("Uploaded file not found on the server.")) }
        return size
    }

    // MARK: - Reading back

    /// A backup package on a remote.
    struct RemotePackage: Identifiable, Sendable {
        var id: String { key }
        /// Path used to fetch it, relative to the remote's fs root.
        let key: String
        let displayName: String
        let size: Int64
        let modified: Date?
    }

    /// Every `.minisbak` in `remote`'s backup directory, newest first.
    ///
    /// In-flight scratch files never show up as restorable backups: they are
    /// named `<package>.minisbak.partial`, whose suffix is `.partial`, and the
    /// filter below requires the name to END in `.minisbak`.
    /// [T-backup-webdav-hidden-partial] That suffix test — not the leading dot
    /// this comment used to rely on — is what excludes them, which is why the
    /// scratch name could stop being dot-prefixed (dotfiles are invisible to
    /// directory listings on alist and similar WebDAV gateways, which broke
    /// the abandoned-partial sweep).
    static func listPackages(remote: RcloneRemoteStore.Remote) throws -> [RemotePackage] {
        let fs = RcloneRemoteStore.fsSpec(for: remote)
        let root = try RcloneBridge.rpc("operations/list",
                                        ["fs": fs, "remote": remote.path.trimmingCharacters(
                                            in: CharacterSet(charactersIn: "/"))])
        var found: [RemotePackage] = []
        for e in (root["list"] as? [[String: Any]]) ?? [] {
            let name = e["Name"] as? String ?? ""
            let isDir = e["IsDir"] as? Bool ?? false
            guard !isDir, !name.hasPrefix("."),
                  name.hasSuffix("." + BackupFormat.fileExtension) else { continue }
            found.append(RemotePackage(
                key: remote.join(name),
                displayName: name,
                size: (e["Size"] as? NSNumber)?.int64Value ?? 0,
                modified: parseTime(e["ModTime"] as? String)))
        }
        return found.sorted { ($0.modified ?? .distantPast) > ($1.modified ?? .distantPast) }
    }

    /// One entry when browsing a remote directory by hand.
    ///
    /// [T-restore-browse-tree] Carries directories as well as packages: the
    /// restore browser now walks the remote a level at a time instead of
    /// listing one configured folder, so it needs both.
    struct RemoteEntry: Identifiable, Sendable {
        var id: String { path }
        /// Path relative to the remote root, as rclone wants it.
        let path: String
        let name: String
        let isDirectory: Bool
        let size: Int64
        let modified: Date?

        /// The same entry expressed as a package, for handing to the download
        /// path. Only meaningful for a file.
        var asPackage: RemotePackage {
            RemotePackage(key: path, displayName: name, size: size, modified: modified)
        }
    }

    /// List ONE directory of a remote: its subdirectories, plus the
    /// `.minisbak` files directly inside it.
    ///
    /// [T-restore-browse-tree] Deliberately one level, and NOT recursive. The
    /// restore browser used to show every package under the destination's
    /// configured folder, which meant an `operations/list` that a large or
    /// deep share makes slow and expensive — and on a remote whose root holds
    /// unrelated data, potentially a very long walk to find a handful of
    /// files. Browsing a level at a time costs one cheap listing per tap and
    /// lets the user reach a package that is not under the configured folder
    /// at all.
    ///
    /// Directories are returned unfiltered (the user has to be able to walk
    /// through folders that contain no packages themselves) while files are
    /// filtered to the package extension, since nothing else here is
    /// selectable.
    static func listDirectory(remote: RcloneRemoteStore.Remote,
                              path: String) throws -> [RemoteEntry] {
        let fs = RcloneRemoteStore.fsSpec(for: remote)
        let cleaned = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let root = try RcloneBridge.rpc("operations/list", ["fs": fs, "remote": cleaned])
        var dirs: [RemoteEntry] = []
        var files: [RemoteEntry] = []
        for e in (root["list"] as? [[String: Any]]) ?? [] {
            let name = e["Name"] as? String ?? ""
            guard !name.isEmpty, !name.hasPrefix(".") else { continue }
            let isDir = e["IsDir"] as? Bool ?? false
            let entry = RemoteEntry(
                path: cleaned.isEmpty ? name : cleaned + "/" + name,
                name: name,
                isDirectory: isDir,
                size: (e["Size"] as? NSNumber)?.int64Value ?? 0,
                modified: parseTime(e["ModTime"] as? String))
            if isDir {
                dirs.append(entry)
            } else if name.hasSuffix("." + BackupFormat.fileExtension) {
                files.append(entry)
            }
        }
        // Folders first and alphabetical, which is how a file browser reads;
        // packages newest-first, which is the order someone restoring wants.
        return dirs.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
             + files.sorted { ($0.modified ?? .distantPast) > ($1.modified ?? .distantPast) }
    }

    /// Fetch a package to a local file.
    /// [T-backup-remote-delete] Delete one package from a remote.
    ///
    /// Irreversible: rclone's `operations/deletefile` unlinks the object, and
    /// most backends (SMB, WebDAV, SFTP) have no trash to recover it from. The
    /// caller is responsible for confirming with the user first — this
    /// function deliberately takes no "are you sure" flag, so the decision
    /// lives in the UI where the user can actually see what is being removed.
    ///
    /// Throws on failure so the caller can surface the reason rather than
    /// silently leaving the row in place.
    static func deletePackage(_ pkg: RemotePackage,
                              from remote: RcloneRemoteStore.Remote) throws {
        let fs = RcloneRemoteStore.fsSpec(for: remote)
        _ = try RcloneBridge.rpc("operations/deletefile",
                                 ["fs": fs, "remote": pkg.key])
        logger.info("[Rclone] deleted '\(pkg.displayName)' from remote '\(remote.name)'")
    }

    static func download(_ pkg: RemotePackage,
                         from remote: RcloneRemoteStore.Remote,
                         to destination: URL,
                         isCancelled: @escaping () -> Bool = { false },
                         progress: ((Progress) -> Void)? = nil) throws {
        let fs = RcloneRemoteStore.fsSpec(for: remote)
        try? FileManager.default.removeItem(at: destination)

        // operations/copyfile is one blocking call that returns only when the
        // whole file has landed, so it can report nothing along the way. For
        // a 250 MB package over Wi-Fi that is minutes of a bare spinner, with
        // no way to tell a slow transfer from a stuck one.
        //
        // rclone tracks the transfer itself, so the bytes are polled from
        // core/stats on a separate task while the copy runs. Reported against
        // the package size we already know, which is what the caller wants to
        // show; `bytes` is cumulative for the session, so the baseline taken
        // before the copy starts is subtracted.
        // [T-restore-download-cancel] Run the copy as an ASYNC rclone job and
        // poll it, rather than making one blocking `operations/copyfile` call.
        //
        // Cancel used to do nothing. `RcloneBridge.rpc` is a synchronous call
        // into Go, so a blocking copyfile owns its thread until the whole file
        // has landed and Swift cannot interrupt it; the abort therefore has to
        // be asked of rclone. The old code asked via
        // `job/stopgroup {group: "global"}` — but no `_group` was ever set on
        // the copy, so that stopped a group the transfer was not in and the
        // download ran to completion regardless. The user's Cancel only took
        // effect on the check AFTER the transfer had already finished.
        //
        // `_async: true` returns a jobid immediately, and `job/stop` with that
        // id stops precisely this transfer. A `_group` is also set so the
        // stats poll below reads THIS job's bytes rather than the process-wide
        // counter, which was the other reason progress could look wrong when
        // anything else was transferring.
        let group = "restore-\(UUID().uuidString)"
        let started = try RcloneBridge.rpc("operations/copyfile", [
            "srcFs": fs, "srcRemote": pkg.key,
            "dstFs": destination.deletingLastPathComponent().path,
            "dstRemote": destination.lastPathComponent,
            "_async": true,
            "_group": group,
        ])
        guard let jobid = (started["jobid"] as? NSNumber)?.intValue else {
            // No jobid means the async request itself was rejected. Fall back
            // rather than silently doing nothing — better a download that
            // cannot be cancelled than no download at all.
            logger.error("[Rclone] copyfile did not return a jobid; falling back to blocking copy")
            _ = try RcloneBridge.rpc("operations/copyfile", [
                "srcFs": fs, "srcRemote": pkg.key,
                "dstFs": destination.deletingLastPathComponent().path,
                "dstRemote": destination.lastPathComponent,
            ])
            try verifyDownload(pkg, at: destination, progress: progress)
            return
        }

        // Poll the job: report progress, honour cancellation, and finish when
        // rclone says the job is done. 0.25s rather than 0.5s so Cancel feels
        // immediate — it is a user-facing control, and the poll interval is
        // the floor on how long it appears to hang.
        var cancelled = false
        // [T-restore-download-speed] Rolling rate, so the sheet can show how
        // fast the transfer is going and roughly how long is left.
        //
        // Smoothed over a short window rather than differenced between two
        // consecutive 0.25s polls: at that interval a single slow tick reads
        // as a near-stall and the number flickers unusably. An exponential
        // moving average settles within a second or two and then tracks real
        // changes (Wi-Fi dropping to cellular, say) without jitter.
        var lastBytes: Int64 = 0
        var lastTick = Date()
        var smoothedRate: Double?
        while true {
            Thread.sleep(forTimeInterval: 0.25)

            if !cancelled && isCancelled() {
                cancelled = true
                // Stop THIS job by id. Asked once; the loop then waits for the
                // job to actually report finished, so the file is not deleted
                // out from under a transfer that is still writing.
                _ = try? RcloneBridge.rpc("job/stop", ["jobid": jobid])
            }

            let status = (try? RcloneBridge.rpc("job/status", ["jobid": jobid])) ?? [:]
            let finished = (status["finished"] as? NSNumber)?.boolValue ?? false

            if !finished {
                // Per-group stats start at zero for this job, so no baseline
                // subtraction is needed the way the process-wide counter did.
                let stats = (try? RcloneBridge.rpc("core/stats", ["group": group])) ?? [:]
                let now = (stats["bytes"] as? NSNumber)?.int64Value ?? 0
                let moved = max(0, now)

                // Prefer rclone's own speed figure when it reports one; it
                // already averages over the transfer. Otherwise derive it.
                let tick = Date()
                let elapsed = tick.timeIntervalSince(lastTick)
                if let reported = (stats["speed"] as? NSNumber)?.doubleValue, reported > 0 {
                    smoothedRate = reported
                } else if elapsed > 0.05 {
                    let instant = Double(moved - lastBytes) / elapsed
                    if instant >= 0 {
                        // 0.3 favours responsiveness over smoothness — enough
                        // to kill per-tick jitter, quick enough that a real
                        // slowdown shows within a couple of seconds.
                        smoothedRate = smoothedRate.map { $0 * 0.7 + instant * 0.3 } ?? instant
                    }
                }
                if elapsed > 0.05 { lastBytes = moved; lastTick = tick }

                // Never report more than the file — stats can include other
                // bookkeeping, and a bar that overshoots reads as a bug even
                // when the transfer is fine.
                progress?(Progress(bytesSent: min(moved, pkg.size), totalBytes: pkg.size,
                                   bytesPerSecond: smoothedRate))
                continue
            }

            if cancelled {
                try? FileManager.default.removeItem(at: destination)
                throw TransferError.cancelled
            }
            let success = (status["success"] as? NSNumber)?.boolValue ?? false
            if !success {
                let msg = (status["error"] as? String) ?? "transfer failed"
                throw RcloneBridge.RPCError(status: -1, payload: msg)
            }
            break
        }
        // Same verification as upload, in the other direction: a downloaded
        // package that is short would fail later as a corrupt archive, which
        // is a far worse message to hand someone restoring a new device.
        if isCancelled() {
            try? FileManager.default.removeItem(at: destination)
            throw TransferError.cancelled
        }
        try verifyDownload(pkg, at: destination, progress: progress)
    }

    /// Size-check a downloaded package. Extracted so the async path and the
    /// no-jobid fallback cannot verify differently.
    private static func verifyDownload(_ pkg: RemotePackage, at destination: URL,
                                       progress: ((Progress) -> Void)?) throws {
        let local = (try? FileManager.default.attributesOfItem(
            atPath: destination.path)[.size] as? Int64) ?? -1
        guard local == pkg.size else {
            throw TransferError.sizeMismatch(expected: pkg.size, actual: max(local, 0))
        }
        progress?(Progress(bytesSent: pkg.size, totalBytes: pkg.size))
        logger.info("[Rclone] downloaded \(pkg.displayName) (\(pkg.size) bytes, verified)")
    }

    private static func parseTime(_ s: String?) -> Date? {
        guard let s else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: s) ?? ISO8601DateFormatter().date(from: s)
    }
}
