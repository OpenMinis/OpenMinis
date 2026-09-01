//
//  MountedFoldersManager.swift
//  MinisApp
//
//  Manages user-picked external folders (e.g. Obsidian vaults in iCloud Drive)
//  mounted into /var/minis/mounts/<name> via security-scoped bookmarks.
//
//  Lifecycle:
//  - User picks a folder via UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
//  - We create a persistent bookmark and remember (id, name, bookmark, sourceDisplay)
//  - On app launch, activateAll() resolves bookmarks and holds security scopes
//    for the entire app lifetime
//  - ensureMinisSymlinks() creates /var/minis/mounts/<name> symlinks in fakefs
//
//  Note: iSH shell cannot follow these symlinks (no scope in kernel thread).
//  Only FileBrowserView and the Swift app process can read/write through them.
//

import Foundation
import FileProvider

private let mountLog = AppLogger(category: "MountedFolders")

/// A single user-mounted external folder.
struct MountedFolderEntry: Codable, Identifiable, Equatable {
    /// Stable identifier (used as the key in persistence).
    let id: UUID
    /// User-chosen name; becomes the folder name under /var/minis/mounts/<name>.
    /// Must match `isValidMountName`.
    var name: String
    /// Original folder name at pick time (for display only).
    let sourceDisplayName: String
    /// Security-scoped bookmark data. Re-resolved on each app launch.
    var bookmark: Data
    /// When the user added this mount (for display / sorting).
    let createdAt: Date

    /// Whether the source folder is actually writable by this app at the OS
    /// level. Determined by `probeWritable` at add time and refreshed on
    /// foreground. If false, the mount is effectively read-only regardless of
    /// `userAllowWrite`. For mounts persisted before this field existed,
    /// decoding defaults to `true` so existing entries keep working.
    var isWritable: Bool = true

    /// User intent: does the user *want* Minis (shell + AI + Browse Files) to
    /// be allowed to modify this folder? This is a soft lock layered on top
    /// of `isWritable` — it lets users mount a writable folder and still keep
    /// AI from deleting or editing its contents.
    /// Defaults to `true` for backward compatibility with mounts saved before
    /// this field existed.
    var userAllowWrite: Bool = true

    /// Effective writable flag — both must be true to allow writes.
    var effectiveWritable: Bool {
        isWritable && userAllowWrite
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, sourceDisplayName, bookmark, createdAt, isWritable, userAllowWrite
    }

    init(
        id: UUID,
        name: String,
        sourceDisplayName: String,
        bookmark: Data,
        createdAt: Date,
        isWritable: Bool,
        userAllowWrite: Bool
    ) {
        self.id = id
        self.name = name
        self.sourceDisplayName = sourceDisplayName
        self.bookmark = bookmark
        self.createdAt = createdAt
        self.isWritable = isWritable
        self.userAllowWrite = userAllowWrite
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(UUID.self, forKey: .id)
        self.name = try c.decode(String.self, forKey: .name)
        self.sourceDisplayName = try c.decode(String.self, forKey: .sourceDisplayName)
        self.bookmark = try c.decode(Data.self, forKey: .bookmark)
        self.createdAt = try c.decode(Date.self, forKey: .createdAt)
        // New fields — default to true for entries saved before they existed.
        self.isWritable = try c.decodeIfPresent(Bool.self, forKey: .isWritable) ?? true
        self.userAllowWrite = try c.decodeIfPresent(Bool.self, forKey: .userAllowWrite) ?? true
    }

    /// Returns true if `name` is a valid mount directory name (no /, not empty, not . or ..).
    static func isValidMountName(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return false }
        if trimmed == "." || trimmed == ".." { return false }
        if trimmed.contains("/") || trimmed.contains("\0") { return false }
        return true
    }
}

/// Result of activating a single mount on app launch.
enum MountActivationState: Equatable {
    case active(resolvedURL: URL)
    case stale
    case permissionDenied
    case failed(String)
    /// Bookmark resolved and security scope acquired, but the host path's
    /// contents are not materialized locally — the third-party FileProvider
    /// (e.g. NutStore, Aliyun Drive) evicted the placeholder while the app was
    /// in the background. We tried NSFileCoordinator to trigger
    /// materialization but it didn't complete in time. User needs to open
    /// the "Files" app and browse the folder once to wake the provider.
    case contentsUnavailable

    var isActive: Bool {
        if case .active = self { return true }
        return false
    }
}

/// Singleton that owns mount metadata + active security scopes.
///
/// All mutating operations are main-actor-isolated; read access (`list`,
/// `resolvedURL(for:)`) is safe from any actor because the backing storage
/// is copy-on-read.
@MainActor
final class MountedFoldersManager {
    static let shared = MountedFoldersManager()

    /// In-memory snapshot of persisted entries.
    private(set) var entries: [MountedFolderEntry] = []

    /// Resolved URL for each active mount (key = entry.id). Nil if activation failed.
    private var activeURLs: [UUID: URL] = [:]

    /// Symlink-resolved, standardized host path for each active mount
    /// (key = entry.id), computed **once** off the main thread when the mount
    /// activates.
    ///
    /// Callers that need to test "is this URL under a mount?" used to call
    /// `resolvedURL(for:).resolvingSymlinksInPath()` on every check. For a
    /// mount backed by a network share or a FileProvider extension that is a
    /// `getattrlist(2)` → synchronous XPC to the provider, and on a slow or
    /// unreachable volume it parks the calling thread — fatal on the main
    /// thread, where it tripped the 10s scene-update watchdog. Resolving once
    /// at activation and reusing the string keeps those checks pure
    /// string comparisons.
    private var canonicalMountPaths: [UUID: String] = [:]

    /// State per mount, published so UI can reflect reauth needs.
    private(set) var activationStates: [UUID: MountActivationState] = [:]

    private init() {
        migrateStoreFileIfNeeded()
        load()
    }

    // MARK: - Persistence

    /// Mounts metadata lives in the App Group's private MinisConfig subdir
    /// so it does NOT leak into iOS Files' "On My iPhone → Minis" view.
    /// Historically stored in `minisAppGroupRoot/mounted-folders.json`, but
    /// that path sits inside the FileProvider's exposed root.
    private static var storeURL: URL {
        return AIChatViewModel.minisConfigRoot.appendingPathComponent("mounted-folders.json")
    }

    /// Old pre-migration path — kept as a fallback source so existing mounts
    /// aren't lost after upgrading.
    private static var legacyStoreURL: URL {
        AIChatViewModel.minisAppGroupRoot.appendingPathComponent("mounted-folders.json")
    }

    /// Move `mounted-folders.json` out of providerRoot into MinisConfig the
    /// first time this runs after the fix. Safe to call on every launch.
    private func migrateStoreFileIfNeeded() {
        let fm = FileManager.default
        let legacy = Self.legacyStoreURL
        let current = Self.storeURL
        guard fm.fileExists(atPath: legacy.path) else { return }
        if fm.fileExists(atPath: current.path) {
            // Current wins — just drop the stale legacy copy.
            try? fm.removeItem(at: legacy)
            mountLog.info("migrateStoreFile: removed stale legacy copy at \(legacy.path)")
            return
        }
        do {
            try fm.moveItem(at: legacy, to: current)
            mountLog.info("migrateStoreFile: moved \(legacy.lastPathComponent) to MinisConfig/")
        } catch {
            // Fallback: copy + delete, in case move-across-bind fails.
            do {
                try fm.copyItem(at: legacy, to: current)
                try fm.removeItem(at: legacy)
                mountLog.info("migrateStoreFile: copied \(legacy.lastPathComponent) to MinisConfig/ (move failed, copy+delete succeeded)")
            } catch {
                mountLog.warning("migrateStoreFile failed: \(error.localizedDescription)")
            }
        }
    }

    private func load() {
        let url = Self.storeURL
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        do {
            let data = try Data(contentsOf: url)
            entries = try JSONDecoder().decode([MountedFolderEntry].self, from: data)
        } catch {
            mountLog.warning("load failed: \(error.localizedDescription)")
        }
    }

    private func save() {
        do {
            let data = try JSONEncoder().encode(entries)
            try data.write(to: Self.storeURL, options: .atomic)
        } catch {
            mountLog.error("save failed: \(error.localizedDescription)")
        }
        // [T-ios-mention-index-stale-mounts] The mount set is one of the @-mention
        // index's scan roots, and that index caches for 10 minutes keyed on
        // sessionId. Without this, a folder mounted while an existing chat is open
        // stays invisible to `@` in that chat until the TTL lapses, even though a
        // newly-created chat finds it instantly.
        //
        // Hooked on `save()` because it is the single choke point both `add` and
        // `remove(id:)` already funnel through — a future mutation path gets the
        // invalidation for free instead of having to remember it.
        FileMentionIndex.shared.invalidateCache(reason: "mounted folders changed")
    }

    // MARK: - CRUD

    /// Hard cap on the number of concurrent external mounts. The iSH bind-mount
    /// table has a compile-time slot limit (currently 32, shared with 7
    /// built-in session mounts), but we cap well below that to keep directory
    /// enumeration and mount reconciliation fast, and to keep the UI sensible.
    static let maxMountCount = 10

    /// Returns true if `name` is unique among current entries (excluding `excludingId`).
    func isNameAvailable(_ name: String, excludingId: UUID? = nil) -> Bool {
        !entries.contains { $0.name == name && $0.id != excludingId }
    }

    enum AddError: Error, LocalizedError {
        case invalidName
        case nameTaken
        case scopeDenied
        case bookmarkFailed(String)
        case limitReached(Int)

        var errorDescription: String? {
            switch self {
            case .invalidName: return AppLocalized("Mount name is invalid.")
            case .nameTaken: return AppLocalized("A mount with this name already exists.")
            case .scopeDenied: return AppLocalized("Could not access the selected folder.")
            case .bookmarkFailed(let msg): return msg
            case .limitReached(let max):
                return String(
                    format: AppLocalized("Mount limit reached (%d). Remove an existing mount before adding a new one."),
                    max
                )
            }
        }
    }

    /// Add a new mount from a freshly-picked document picker URL.
    /// The caller is responsible for presenting the picker; this takes the result URL.
    /// The URL must currently have an active security scope (as it does right after picker callback).
    ///
    /// `userAllowWrite` is the user's intent: pass `true` to allow AI and shell
    /// to modify the folder, or `false` to mount it as an internally-locked
    /// read-only folder (even if the source itself is writable).
    @discardableResult
    func add(pickedURL: URL, customName: String, userAllowWrite: Bool) throws -> MountedFolderEntry {
        let name = customName.trimmingCharacters(in: .whitespaces)
        guard MountedFolderEntry.isValidMountName(name) else { throw AddError.invalidName }
        guard isNameAvailable(name) else { throw AddError.nameTaken }
        guard entries.count < Self.maxMountCount else {
            throw AddError.limitReached(Self.maxMountCount)
        }

        // The picker gives us a URL with scope already active, but for safety
        // re-start it; balanced stop in defer.
        let startedHere = pickedURL.startAccessingSecurityScopedResource()
        defer { if startedHere { pickedURL.stopAccessingSecurityScopedResource() } }

        let bookmark: Data
        do {
            // iOS: do NOT pass .withSecurityScope (macOS-only).
            bookmark = try pickedURL.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
        } catch {
            throw AddError.bookmarkFailed(error.localizedDescription)
        }

        // Probe writability by creating + deleting a tiny hidden file.
        // This happens while the security scope is still active.
        let writable = Self.probeWritable(at: pickedURL)

        let entry = MountedFolderEntry(
            id: UUID(),
            name: name,
            sourceDisplayName: Self.humanReadableSourceName(for: pickedURL),
            bookmark: bookmark,
            createdAt: Date(),
            isWritable: writable,
            userAllowWrite: userAllowWrite
        )
        entries.append(entry)
        save()

        // Activate immediately so it's usable without restart.
        activate(entry: entry)
        // Create/refresh the fakefs symlink so FileBrowserView sees it.
        AIChatViewModel.refreshMountedFolderSymlinks()
        // Register the new mount with the iSH kernel bind-mount table so the
        // shell can read/write it directly (no-op if kernel not booted).
        pushExternalMountSnapshot()
        mountLog.info("added mount '\(entry.name)' -> \(pickedURL.lastPathComponent)")
        return entry
    }

    /// Toggle the user's allow-write intent for a mount. This is the
    /// Minis-internal soft lock on top of the OS-level `isWritable`. Calling
    /// with `true` when the source is read-only at the OS level has no effect.
    func setUserAllowWrite(id: UUID, to allow: Bool) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        guard entries[idx].userAllowWrite != allow else { return }
        entries[idx].userAllowWrite = allow
        save()
        pushExternalMountSnapshot()
        mountLog.info("user allow-write for '\(entries[idx].name)' -> \(allow)")
    }

    /// Re-probe the writability of a mount (useful if the user changed
    /// permissions on the source folder in another app). Updates `isWritable`
    /// and re-pushes the snapshot so iSH rebinds with the new mode.
    func refreshWritability(id: UUID) {
        guard let idx = entries.firstIndex(where: { $0.id == id }),
              let url = activeURLs[id] else { return }
        let writable = Self.probeWritable(at: url)
        if entries[idx].isWritable != writable {
            entries[idx].isWritable = writable
            save()
            pushExternalMountSnapshot()
            mountLog.info("refreshed writability for '\(entries[idx].name)' -> \(writable ? "R/W" : "read-only")")
        }
    }

    /// Re-probe all active mounts. Called when the app returns to foreground
    /// so stale read-only state is corrected.
    func refreshAllWritability() {
        for entry in entries {
            refreshWritability(id: entry.id)
        }
    }

    /// Rename a mount. Stops old symlink, creates new one.
    func rename(id: UUID, to newName: String) throws {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard MountedFolderEntry.isValidMountName(name) else { throw AddError.invalidName }
        guard isNameAvailable(name, excludingId: id) else { throw AddError.nameTaken }
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        let oldName = entries[idx].name
        entries[idx].name = name
        save()
        // Remove old symlink; recreate all.
        removeMountSymlink(name: oldName)
        AIChatViewModel.refreshMountedFolderSymlinks()
        pushExternalMountSnapshot()
    }

    /// Remove a mount. Releases its scope and removes the symlink.
    func remove(id: UUID) {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return }
        let entry = entries[idx]

        if let url = activeURLs[id] {
            url.stopAccessingSecurityScopedResource()
        }
        activeURLs.removeValue(forKey: id)
        canonicalMountPaths.removeValue(forKey: id)
        activationStates.removeValue(forKey: id)

        removeMountSymlink(name: entry.name)

        entries.remove(at: idx)
        save()
        // Reconcile iSH bind-mount table so the removed entry is unbound.
        pushExternalMountSnapshot()
        mountLog.info("removed mount '\(entry.name)'")
    }

    // MARK: - Activation

    /// Resolve bookmarks and hold security scopes for all entries. Call once at app launch.
    ///
    /// **Must not block the main thread.** `URL(resolvingBookmarkData:)` for a
    /// bookmark pointing into another app's FileProvider domain (e.g. iCloud
    /// Drive, Obsidian) forwards through `FPDaemonConnection` which makes a
    /// **synchronous XPC call** to the owning FileProvider extension. If that
    /// extension is slow or unresponsive (seen in practice on iOS 26.4.1 with
    /// iCloud Drive vaults), the main thread hangs and iOS kills the app with
    /// 0x8BADF00D after ~20s. We therefore do the resolution on a background
    /// queue and hop back to the main actor to commit scope acquisition and
    /// state. A soft timeout ensures the main actor is never starved even if
    /// the background task itself hangs forever waiting on XPC.
    func activateAll() {
        let snapshot = entries
        mountLog.info("activateAll: \(snapshot.count) entries to resolve")
        guard !snapshot.isEmpty else {
            pushExternalMountSnapshot()
            return
        }
        Task.detached(priority: .userInitiated) {
            for entry in snapshot {
                await Self.resolveAndCommit(entry: entry)
            }
            await MainActor.run {
                MountedFoldersManager.shared.pushExternalMountSnapshot()
            }
        }
    }

    /// Resolve a single bookmark on a background queue (off the main thread)
    /// with a soft timeout, then hop to the main actor to commit the result.
    ///
    /// The soft timeout protects us from a hung FileProvider XPC reply: if the
    /// resolver doesn't return within the deadline, we record `.failed` and
    /// move on. The orphaned resolver task will eventually either complete
    /// (harmless — its result is dropped) or remain stuck on mach_msg forever
    /// in a background thread, which does **not** trip the watchdog.
    ///
    /// Implementation note: an earlier version of this used a
    /// `DispatchSemaphore` + a separate timeout queue both reading an
    /// unsynchronized `var result`. That had a data race — on ARM64 the
    /// timeout queue could observe `nil` even after the producer queue had
    /// finished — so mounts would be silently marked `.failed` on cold launch
    /// even when the bookmark resolved instantly. v1.8 shipped that bug; the
    /// user-visible symptom was a mounted folder appearing in `ls` but
    /// `cd` returning ENOENT because the host scope was never acquired and
    /// `pushExternalMountSnapshot` dropped it via `compactMap`. The fix below
    /// uses a single `Task` for the resolve and races it against
    /// `Task.sleep` — the `Result` lives entirely inside one task, so there
    /// is no cross-thread shared mutable state.
    private static func resolveAndCommit(entry: MountedFolderEntry) async {
        let idPrefix = String(entry.id.uuidString.prefix(8))
        let started = Date()
        mountLog.info("resolveAndCommit '\(entry.name)' [\(idPrefix)] start — bookmark=\(entry.bookmark.count)B")
        let timeoutNanos: UInt64 = 5 * 1_000_000_000

        let resolveTask = Task.detached(priority: .userInitiated) { () -> (URL, Bool) in
            var stale = false
            let u = try URL(
                resolvingBookmarkData: entry.bookmark,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            return (u, stale)
        }

        let timeoutTask = Task.detached(priority: .utility) { () -> (URL, Bool)? in
            try? await Task.sleep(nanoseconds: timeoutNanos)
            return nil
        }

        // Race: whichever finishes first wins. We deliberately don't cancel
        // the resolve task on timeout — `URL(resolvingBookmarkData:)` is a
        // synchronous XPC call and cannot be cancelled; let it finish in the
        // background and drop its result.
        let outcome: Result<(URL, Bool), Error>? = await withTaskGroup(
            of: ResolveOutcome.self
        ) { group in
            group.addTask {
                do { return .resolved(try await resolveTask.value) }
                catch { return .failed(error) }
            }
            group.addTask {
                _ = await timeoutTask.value
                return .timedOut
            }
            defer { group.cancelAll() }
            guard let first = await group.next() else { return nil }
            switch first {
            case .resolved(let v): return .success(v)
            case .failed(let e): return .failure(e)
            case .timedOut: return nil
            }
        }

        let elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
        await MainActor.run {
            let manager = MountedFoldersManager.shared
            // Entry may have been removed while we were resolving — bail out.
            guard manager.entries.contains(where: { $0.id == entry.id }) else {
                mountLog.info("resolveAndCommit '\(entry.name)' [\(idPrefix)] skipped — entry removed during resolve (elapsed=\(elapsedMs)ms)")
                return
            }
            guard manager.activeURLs[entry.id] == nil else {
                mountLog.info("resolveAndCommit '\(entry.name)' [\(idPrefix)] skipped — already active (elapsed=\(elapsedMs)ms)")
                return
            }

            switch outcome {
            case .none:
                let msg = "resolve timed out after 5s (FileProvider unresponsive)"
                manager.activationStates[entry.id] = .failed(msg)
                mountLog.warning("activate '\(entry.name)' [\(idPrefix)] \(msg) (elapsed=\(elapsedMs)ms)")
            case .failure(let error):
                manager.activationStates[entry.id] = .failed(error.localizedDescription)
                mountLog.warning("activate '\(entry.name)' [\(idPrefix)] resolve failed: \(error.localizedDescription) (elapsed=\(elapsedMs)ms)")
            case .success(let (url, stale)):
                mountLog.info("resolveAndCommit '\(entry.name)' [\(idPrefix)] resolved in \(elapsedMs)ms stale=\(stale) -> \(url.path)")
                manager.commitResolved(entry: entry, url: url, stale: stale)
            }
        }
    }

    private enum ResolveOutcome {
        case resolved((URL, Bool))
        case failed(Error)
        case timedOut
    }

    /// Main-actor tail of `resolveAndCommit`: start the security scope, record
    /// state, and optionally refresh a stale bookmark.
    private func commitResolved(entry: MountedFolderEntry, url: URL, stale: Bool) {
        let idPrefix = String(entry.id.uuidString.prefix(8))
        guard url.startAccessingSecurityScopedResource() else {
            activationStates[entry.id] = .permissionDenied
            mountLog.warning("activate '\(entry.name)' [\(idPrefix)] scope denied for \(url.path) — bookmark resolved but startAccessingSecurityScopedResource() returned false")
            return
        }
        activeURLs[entry.id] = url

        if stale {
            if let refreshed = try? url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            ), let idx = entries.firstIndex(where: { $0.id == entry.id }) {
                entries[idx].bookmark = refreshed
                save()
                mountLog.info("refreshed stale bookmark for '\(entry.name)' [\(idPrefix)]")
            } else {
                activationStates[entry.id] = .stale
                mountLog.warning("activate '\(entry.name)' [\(idPrefix)] bookmark stale and refresh failed")
                return
            }
        }

        activationStates[entry.id] = .active(resolvedURL: url)
        cacheCanonicalPath(for: entry.id, url: url)
        mountLog.info("activated '\(entry.name)' [\(idPrefix)] -> \(url.path) (stale=\(stale))")

        // Eagerly check whether the host path's contents are actually
        // materialized. Third-party FileProvider extensions (NutStore,
        // Aliyun Drive, etc.) evict placeholders while the app is backgrounded,
        // so resolve+scope-acquire can both succeed while stat() returns
        // ENOENT — leaving us with a "valid" URL pointing at nothing. If
        // we push that to the iSH coordinator the bind mount succeeds at
        // the API level but shell reads come back empty.
        ensureContentsMaterialized(entry: entry, url: url, idPrefix: idPrefix)
    }

    /// Probe the resolved URL with stat(); if ENOENT, attempt to materialize
    /// the evicted FileProvider placeholder (see `materialize`). On completion
    /// either (a) keep .active and let the next pushExternalMountSnapshot
    /// include this mount, or (b) downgrade to .contentsUnavailable + drop
    /// from activeURLs so the iSH coordinator doesn't try to bind an empty
    /// path.
    ///
    /// The initial stat() is done INSIDE the detached task, not on the main
    /// actor. stat() on an evicted FileProvider path can, for some provider
    /// implementations, fall through to a synchronous XPC round-trip to the
    /// extension — running that on the main actor risks the same hang the
    /// rest of this file goes out of its way to avoid (see resolveAndCommit's
    /// background-resolve rationale). Happy-path local folders still resolve
    /// in microseconds, just off the main actor.
    private func ensureContentsMaterialized(entry: MountedFolderEntry, url: URL, idPrefix: String) {
        let id = entry.id
        let name = entry.name
        Task.detached(priority: .userInitiated) {
            var st = stat()
            if stat(url.path, &st) == 0 {
                // Happy path — placeholder is materialized, nothing to do.
                return
            }
            let initialErrno = errno
            mountLog.warning("activate '\(name)' [\(idPrefix)] host path stat() failed errno=\(initialErrno) (\(String(cString: strerror(initialErrno)))) — attempting materialization")

            let materialized = await Self.materialize(url: url, idPrefix: idPrefix, deadlineNanos: 10 * 1_000_000_000)
            await MainActor.run {
                let manager = MountedFoldersManager.shared
                guard manager.entries.contains(where: { $0.id == id }) else { return }
                if materialized {
                    mountLog.info("activate '\(name)' [\(idPrefix)] materialization OK — content now reachable, pushing snapshot")
                    // Re-push so the coordinator now sees a working host path.
                    manager.pushExternalMountSnapshot()
                } else {
                    mountLog.warning("activate '\(name)' [\(idPrefix)] materialization timed out — marking contentsUnavailable. User needs to open Files.app to wake the provider.")
                    manager.activationStates[id] = .contentsUnavailable
                    // Drop from activeURLs so the next push excludes this
                    // entry — better to surface "unavailable" than to bind
                    // an empty path and let the shell return nothing.
                    if let dropped = manager.activeURLs.removeValue(forKey: id) {
                        dropped.stopAccessingSecurityScopedResource()
                    }
                    manager.canonicalMountPaths.removeValue(forKey: id)
                    manager.pushExternalMountSnapshot()
                }
            }
        }
    }

    /// Attempt to materialize a FileProvider-backed path that resolved to a
    /// valid URL but whose contents were evicted while the app was
    /// backgrounded. Tries, in order, within a single `deadlineNanos` budget:
    ///   1. NSFileCoordinator coordinated read (the documented trigger)
    ///   2. NSFileProviderManager.signalEnumerator on the owning domain
    ///      (asks a sleepy third-party provider to re-enumerate / re-stage)
    ///   3. poll stat() every 250ms until the path appears or the deadline
    ///      hits — providers materialize asynchronously after signaling
    /// Returns true as soon as stat() succeeds; false if the whole budget
    /// elapses. Never blocks the caller longer than the deadline.
    private static func materialize(url: URL, idPrefix: String, deadlineNanos: UInt64) async -> Bool {
        let deadline = DispatchTime.now().uptimeNanoseconds + deadlineNanos

        func reachable() -> Bool {
            var st = stat()
            return stat(url.path, &st) == 0
        }

        // 1. NSFileCoordinator — synchronous, cheap, works for well-behaved
        //    providers (iCloud Drive etc.). Run off the main thread.
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                var coordErr: NSError?
                NSFileCoordinator().coordinate(readingItemAt: url,
                                               options: [.withoutChanges],
                                               error: &coordErr) { _ in }
                if let e = coordErr {
                    mountLog.warning("materialize [\(idPrefix)] NSFileCoordinator error: \(e.localizedDescription)")
                }
                cont.resume()
            }
        }
        if reachable() {
            mountLog.info("materialize [\(idPrefix)] reachable after NSFileCoordinator")
            return true
        }

        // 2. NSFileProviderManager.signalEnumerator — find the domain that
        //    owns this URL and nudge it to re-stage. Third-party providers
        //    (e.g. "Shu Files") often need this explicit poke after relaunch
        //    because they don't keep placeholders alive across app launches.
        if let domain = await Self.domainForURL(url) {
            if let mgr = NSFileProviderManager(for: domain) {
                await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                    mgr.signalEnumerator(for: .rootContainer) { error in
                        if let error {
                            mountLog.warning("materialize [\(idPrefix)] signalEnumerator error: \(error.localizedDescription)")
                        } else {
                            mountLog.info("materialize [\(idPrefix)] signalEnumerator sent to domain '\(domain.displayName)'")
                        }
                        cont.resume()
                    }
                }
            } else {
                mountLog.warning("materialize [\(idPrefix)] NSFileProviderManager(for:) returned nil for domain '\(domain.displayName)'")
            }
        } else {
            mountLog.warning("materialize [\(idPrefix)] no FileProvider domain matched url — cannot signal")
        }

        // 3. Poll stat() until the path appears or the deadline hits.
        while DispatchTime.now().uptimeNanoseconds < deadline {
            if reachable() {
                mountLog.info("materialize [\(idPrefix)] reachable after signal + poll")
                return true
            }
            try? await Task.sleep(nanoseconds: 250 * 1_000_000)
        }
        mountLog.warning("materialize [\(idPrefix)] gave up after \(deadlineNanos / 1_000_000_000)s")
        return false
    }

    /// Find the NSFileProviderDomain whose backing storage contains `url`.
    /// FileProvider URLs live under each domain's
    /// `…/File Provider Storage/<domain-id>/…`, so we match the domain whose
    /// identifier appears as a path component. Returns nil for non-FileProvider
    /// URLs (e.g. plain on-device folders) or when enumeration fails.
    private static func domainForURL(_ url: URL) async -> NSFileProviderDomain? {
        let domains: [NSFileProviderDomain] = await withCheckedContinuation { cont in
            NSFileProviderManager.getDomainsWithCompletionHandler { domains, error in
                if let error {
                    mountLog.warning("domainForURL getDomains error: \(error.localizedDescription)")
                }
                cont.resume(returning: domains)
            }
        }
        let comps = Set(url.pathComponents)
        for d in domains {
            // Match by domain identifier appearing in the path. The raw
            // identifier is the hex segment under "File Provider Storage".
            if comps.contains(d.identifier.rawValue) {
                return d
            }
        }
        // Fallback: some providers nest content under a base path that
        // contains the identifier as a substring of a longer component.
        for d in domains where url.path.contains(d.identifier.rawValue) {
            return d
        }
        return nil
    }

    /// Build the current external-mount snapshot, write it to the shared
    /// storage read by the iSH execution coordinator, and ask the coordinator
    /// to apply it immediately (no-op if the kernel isn't booted yet —
    /// performMount will pick up the snapshot on the next session mount).
    func pushExternalMountSnapshot() {
        var droppedNames: [String] = []
        let snapshot: [ISHExecutionCoordinator.ExternalMountSpec] = entries.compactMap { entry in
            guard let url = activeURLs[entry.id] else {
                let state = activationStates[entry.id].map { "\($0)" } ?? "nil"
                droppedNames.append("\(entry.name)[\(String(entry.id.uuidString.prefix(8)))]:state=\(state)")
                return nil
            }
            let linuxDir = "\(AIChatViewModel.minisMountsLinuxDir)/\(entry.name)"
            // Effective writable = source is actually writable AND user allows it.
            return ISHExecutionCoordinator.ExternalMountSpec(
                linuxDir: linuxDir,
                hostPath: url.path,
                readOnly: !entry.effectiveWritable
            )
        }
        if droppedNames.isEmpty {
            mountLog.info("pushExternalMountSnapshot: \(snapshot.count) active / \(entries.count) total")
        } else {
            mountLog.warning("pushExternalMountSnapshot: \(snapshot.count) active / \(entries.count) total — dropped (no activeURL): \(droppedNames.joined(separator: ", "))")
        }
        // [MOUNT-DIAG] Snapshot contents at push time, so we can correlate
        // with the coordinator-side apply log. Each line includes whether the
        // host path is currently reachable from this process — distinguishes
        // "scope held + readable" from "scope held but XPC unresponsive" from
        // "scope dropped".
        //
        // **Runs off the main thread.** This whole loop exists only to emit a
        // log line, but `stat()` on a mount backed by a network share or
        // another app's FileProvider is a synchronous XPC round-trip to the
        // owning provider — on a slow or unreachable volume it parks the
        // calling thread. This type is `@MainActor`, and several callers reach
        // here from a detached task's `MainActor.run` tail (activateAll's
        // completion, ensureContentsMaterialized), so the block landed on the
        // main thread and tripped the 10s scene-update watchdog → SIGKILL
        // (0x8BADF00D). Nothing below depends on these values, so deferring
        // them is free.
        let diagSnapshot = snapshot
        Task.detached(priority: .utility) {
            for (i, s) in diagSnapshot.enumerated() {
                var st = stat()
                let rc = stat(s.hostPath, &st)
                // Capture errno immediately — any intervening call can clobber it.
                let err = errno
                if rc == 0 {
                    mountLog.info("MOUNT-DIAG push [\(i)] \(s.linuxDir) -> \(s.hostPath) ro=\(s.readOnly) hostStat=OK mode=0o\(String(st.st_mode & 0o777, radix: 8))")
                } else {
                    let errStr = String(cString: strerror(err))
                    mountLog.warning("MOUNT-DIAG push [\(i)] \(s.linuxDir) -> \(s.hostPath) ro=\(s.readOnly) hostStat=FAILED errno=\(err) (\(errStr))")
                }
            }
        }
        // Synchronous write to the thread-safe shared storage — visible to
        // performMount immediately, no actor hop required.
        ISHExecutionCoordinator.setExternalMountSnapshot(snapshot)
        // Also ask the coordinator to reconcile now (async, no-op if not booted).
        Task { await ISHExecutionCoordinator.shared.applyExternalMountSnapshot() }
    }

    /// Re-activate a single entry (used on add and manual reauth).
    @discardableResult
    func activate(entry: MountedFolderEntry) -> MountActivationState {
        // If already active with a URL, skip.
        if let existing = activeURLs[entry.id] {
            let state = MountActivationState.active(resolvedURL: existing)
            activationStates[entry.id] = state
            return state
        }

        var stale = false
        let url: URL
        do {
            url = try URL(
                resolvingBookmarkData: entry.bookmark,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
        } catch {
            let state = MountActivationState.failed(error.localizedDescription)
            activationStates[entry.id] = state
            mountLog.warning("activate '\(entry.name)' resolve failed: \(error.localizedDescription)")
            return state
        }

        guard url.startAccessingSecurityScopedResource() else {
            let state = MountActivationState.permissionDenied
            activationStates[entry.id] = state
            mountLog.warning("activate '\(entry.name)' scope denied")
            return state
        }

        activeURLs[entry.id] = url

        if stale {
            // Try to refresh the bookmark transparently.
            if let refreshed = try? url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            ), let idx = entries.firstIndex(where: { $0.id == entry.id }) {
                entries[idx].bookmark = refreshed
                save()
                mountLog.info("refreshed stale bookmark for '\(entry.name)'")
            } else {
                activationStates[entry.id] = .stale
                return .stale
            }
        }

        let state = MountActivationState.active(resolvedURL: url)
        activationStates[entry.id] = state
        cacheCanonicalPath(for: entry.id, url: url)
        mountLog.info("activated '\(entry.name)' -> \(url.path)")
        return state
    }

    /// Resolve `url`'s canonical path off the main thread and memoize it.
    /// Seeds the cache with the unresolved path immediately so callers have
    /// something usable before the background resolve lands — for the common
    /// case where the mount root contains no symlinks the two are identical.
    private func cacheCanonicalPath(for id: UUID, url: URL) {
        canonicalMountPaths[id] = url.standardized.path
        Task.detached(priority: .utility) {
            let canonical = url.resolvingSymlinksInPath().standardized.path
            await MainActor.run {
                let manager = MountedFoldersManager.shared
                // Only store if the mount is still active — it may have been
                // removed or deactivated while we were resolving.
                guard manager.activeURLs[id] != nil else { return }
                manager.canonicalMountPaths[id] = canonical
            }
        }
    }

    /// Returns the resolved host URL for a mount, if currently active.
    func resolvedURL(for id: UUID) -> URL? {
        activeURLs[id]
    }

    /// Canonical (symlink-resolved, standardized) host path for an active
    /// mount. Precomputed at activation — safe to call on the main thread,
    /// unlike `resolvedURL(for:)?.resolvingSymlinksInPath()`.
    func canonicalPath(for id: UUID) -> String? {
        canonicalMountPaths[id]
    }

    /// Returns the resolved host URL for a mount by name.
    func resolvedURL(forName name: String) -> URL? {
        guard let entry = entries.first(where: { $0.name == name }) else { return nil }
        return activeURLs[entry.id]
    }

    /// Returns the entry matching a fakefs symlink path (`/var/minis/mounts/<name>/...`).
    /// Useful for FileBrowserView to decide whether to use NSFileCoordinator.
    func entryForLinuxPath(_ linuxPath: String) -> MountedFolderEntry? {
        let prefix = AIChatViewModel.minisMountsLinuxDir + "/"
        guard linuxPath.hasPrefix(prefix) else { return nil }
        let rest = String(linuxPath.dropFirst(prefix.count))
        let name = rest.split(separator: "/", maxSplits: 1).first.map(String.init) ?? rest
        return entries.first { $0.name == name }
    }

    // MARK: - Symlink helpers

    /// Build a user-friendly source display for a picked folder URL.
    ///
    /// iCloud Drive app containers are nested as:
    ///     .../Mobile Documents/iCloud~<team>~<app>/Documents/[<subpath>]
    ///
    /// So the raw `lastPathComponent` is often literally "Documents" with no
    /// hint about which app the folder belongs to. This helper detects the
    /// pattern and produces a breadcrumb like "obsidian › Documents" instead.
    nonisolated static func humanReadableSourceName(for url: URL) -> String {
        let components = url.pathComponents
        // Find the iCloud container segment ("iCloud~md~obsidian" etc.) in
        // the path, then join everything from it onwards with a separator.
        if let idx = components.lastIndex(where: { $0.hasPrefix("iCloud~") }) {
            // Use just the app slug from the container name for readability:
            // "iCloud~md~obsidian" → "obsidian"
            let containerName = components[idx]
            let appSlug = containerName.split(separator: "~").last.map(String.init) ?? containerName
            let tail = components.dropFirst(idx + 1).joined(separator: " › ")
            if tail.isEmpty {
                return appSlug
            }
            return "\(appSlug) › \(tail)"
        }
        // Non-iCloud path — fall back to the last component.
        return url.lastPathComponent
    }

    /// Probe whether we can write to the given folder URL. The caller must
    /// currently hold a security scope for the URL (e.g. immediately after
    /// the document picker callback, or during `add()`).
    ///
    /// Strategy: try to create a tiny hidden probe file; if creation succeeds,
    /// delete it and report writable. Uses NSFileCoordinator so providers
    /// (iCloud Drive, Dropbox, Working Copy, etc.) see a coordinated write.
    nonisolated static func probeWritable(at url: URL) -> Bool {
        let probe = url.appendingPathComponent(".minis-probe-\(UUID().uuidString)")
        var coordError: NSError?
        var succeeded = false
        NSFileCoordinator().coordinate(
            writingItemAt: probe,
            options: .forReplacing,
            error: &coordError
        ) { target in
            do {
                try Data().write(to: target, options: .atomic)
                succeeded = true
                try? FileManager.default.removeItem(at: target)
            } catch {
                succeeded = false
            }
        }
        if coordError != nil { return false }
        return succeeded
    }

    /// Remove the fakefs symlink for a mount by name. Safe to call when it doesn't exist.
    private func removeMountSymlink(name: String) {
        let dataPath = RootfsManager.shared.dataPath
        let linkPath = dataPath
            .appendingPathComponent("var/minis/mounts", isDirectory: true)
            .appendingPathComponent(name)
        var lstatBuf = stat()
        if lstat(linkPath.path, &lstatBuf) == 0 {
            unlink(linkPath.path)
        }
    }
}
