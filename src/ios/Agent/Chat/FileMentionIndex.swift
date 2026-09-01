//
//  FileMentionIndex.swift
//  MinisApp
//
//  Backing index for the chat input's `@` file-mention feature.
//  Layered scan (session → shared → mounts) with 10-minute cache and
//  progressive publishing so the UI can refresh as later layers arrive.
//

import Foundation
import Combine

/// One file entry that can be inserted into the input as `@<linuxPath>`.
struct FileMentionEntry: Identifiable, Equatable, Hashable {
    enum Scope: Int, Comparable {
        // Raw values double as the *default* sort order when no query is
        // active, so the case order here is the user-facing priority:
        // skills > attachments > mount > shared > workspace > memory.
        case skills = 0
        case attachments
        case mount
        case shared
        case workspace
        case memory

        static func < (l: Self, r: Self) -> Bool { l.rawValue < r.rawValue }

        var displayLabel: String {
            switch self {
            case .skills:      return "skills"
            case .attachments: return "attachments"
            case .mount:       return "mount"
            case .shared:      return "shared"
            case .workspace:   return "workspace"
            case .memory:      return "memory"
            }
        }

        /// Boost added on top of the name-match score. Differences are
        /// smaller than the smallest name-match step (1000), so a strong
        /// name match in any scope still wins over a weak match in a
        /// higher-priority scope.
        var rankBoost: Int {
            switch self {
            case .skills:      return 600
            case .attachments: return 500
            case .mount:       return 400
            case .shared:      return 300
            case .workspace:   return 200
            case .memory:      return 100
            }
        }
    }

    /// iSH-visible path (e.g. `/var/minis/workspace/foo/bar.py`). Inserted into input.
    let linuxPath: String
    let scope: Scope
    /// Name of the specific mount (only set for `.mount`), used for grouping.
    let mountName: String?
    /// Last modification time of the host file — used for recency sort.
    let modifiedAt: Date
    /// True for directories; we still surface them (useful as context references).
    let isDirectory: Bool

    var id: String { linuxPath }

    var basename: String {
        (linuxPath as NSString).lastPathComponent
    }

    /// Path shown in the menu (strips the `/var/minis/` prefix for density).
    var displayPath: String {
        let prefix = "/var/minis/"
        if linuxPath.hasPrefix(prefix) {
            return String(linuxPath.dropFirst(prefix.count))
        }
        return linuxPath
    }
}

/// Central index that powers the `@` mention menu.
///
/// Lifecycle per `@` trigger:
///   1. Caller invokes `refreshIfNeeded()`.
///   2. If the cached snapshot is younger than `cacheTTL`, nothing runs.
///   3. Otherwise a fresh scan is scheduled:
///        a. Session-scoped dirs (workspace, attachments) synchronously on a
///           background queue — published first so the menu opens fast.
///        b. Shared dirs (shared, skills, memory) — published next.
///        c. Each active external mount scanned with bounded depth (3) — each
///           one published as it finishes, so heavy iCloud mounts don't block.
///   4. `entries` is a `@Published` snapshot the UI observes; dedup is applied
///      by `linuxPath` so files reached from multiple roots appear once.
@MainActor
final class FileMentionIndex: ObservableObject {
    static let shared = FileMentionIndex()

    /// All known entries, progressively populated and deduped by `linuxPath`.
    @Published private(set) var entries: [FileMentionEntry] = []

    /// True while *any* scan layer is still running. The UI shows a progress hint.
    @Published private(set) var isScanning: Bool = false

    /// Cache window — within this interval, repeated `@` triggers reuse results.
    ///
    /// [T-ios-mention-index-stale-mounts] Was 600s. The mount-set case is now
    /// handled precisely by `invalidateCache`, but that cannot see the OTHER way
    /// the roots go stale: files created INSIDE an already-scanned root — by the
    /// agent itself, by a shell command, or by the user in Files. Those have no
    /// event to hook, so the TTL is the only thing that recovers them, and ten
    /// minutes is far too long for a picker whose whole job is "show me what's
    /// there now" (the reporter described exactly this: newly added files not
    /// showing up).
    ///
    /// 30s keeps the cache doing its real job — absorbing the burst of repeated
    /// `@` keystrokes within one composing session, where a rescan per character
    /// would be wasteful — while making staleness self-correct in a timeframe a
    /// user reads as "immediately" rather than "broken". A rescan is bounded by
    /// `globalScanBudget` and runs off the main thread, and the menu keeps
    /// showing the previous entries while it refreshes, so the cost of the
    /// shorter window is not visible in the UI.
    let cacheTTL: TimeInterval = 30

    /// Depth cap applied when walking mount directories.
    private let mountScanMaxDepth = 3

    /// Total number of entries collected across *all* roots in a single scan.
    /// Acts as a hard upper bound so a giant external mount can't balloon the
    /// in-memory index past a reasonable size.
    private let globalScanBudget = 5000

    /// Upper bound on results returned by `matches(for:)`.
    private let defaultMatchLimit = 100

    private let logger = AppLogger(category: "FileMentionIndex")

    private var lastScanAt: Date?
    private var lastScannedSessionId: String?
    private var scanQueue = DispatchQueue(label: "com.openminis.filementionindex", qos: .userInitiated)
    /// Monotonic token — cancels stale scans when a new one starts.
    private var currentScanToken: UUID?
    /// Entries produced by the IN-FLIGHT scan only. Main-thread confined (written
    /// and read solely inside `publish`'s main-queue block, cleared in
    /// `forceRefresh` before any layer can publish). See `publish` for why the
    /// displayed list and the scan's own output have to be tracked separately.
    private var scanAccumulator: [FileMentionEntry] = []

    private init() {}

    // MARK: - Public API

    /// Drop the cached scan so the next `@` rebuilds the index from disk.
    ///
    /// [T-ios-mention-index-stale-mounts] The TTL+session cache assumed the set of
    /// scan roots is fixed for the life of a session. Mounting or unmounting an
    /// external folder changes that set underneath it: the user mounts a folder,
    /// returns to a chat they already used `@` in, and gets "No matching files"
    /// for up to 10 minutes — while a NEW session (different `sessionId`, so a
    /// forced rescan) shows the folder immediately. That asymmetry is exactly
    /// what the bug report describes.
    ///
    /// Invalidating rather than rescanning here is deliberate: mount changes
    /// happen in Settings, where nobody is waiting on the index, and a mount can
    /// be added and removed several times in a row. Clearing `lastScanAt` makes
    /// the next `refreshIfNeeded` — i.e. the next `@` the user actually types —
    /// do the work, on the session that needs it.
    ///
    /// `entries` is intentionally NOT cleared: the current list stays usable
    /// (and the menu stays populated) until the fresh scan replaces it.
    func invalidateCache(reason: String) {
        lastScanAt = nil
        lastScannedSessionId = nil
        logger.info("cache invalidated (\(reason)) — next @ will rescan")
    }

    /// Trigger a scan if the cache has expired OR the session has changed.
    /// Safe to call every time the user types `@` — cheap when cached.
    func refreshIfNeeded(sessionId: String?) {
        let now = Date()
        let cacheFresh = lastScanAt.map { now.timeIntervalSince($0) < cacheTTL } ?? false
        let sessionMatches = (lastScannedSessionId == sessionId)
        #if DEBUG
        print("[MentionScan] refreshIfNeeded sid=\(sessionId ?? "<nil>") lastSid=\(lastScannedSessionId ?? "<nil>") cacheFresh=\(cacheFresh) sessionMatches=\(sessionMatches) entries.count=\(entries.count)")
        #endif
        if cacheFresh && sessionMatches && !entries.isEmpty {
            #if DEBUG
            print("[MentionScan] SKIP rescan — cache fresh, same session, \(entries.count) entries kept")
            #endif
            return
        }
        // Keep the existing rows on screen while this rescan runs — a TTL lapse
        // during typing must not blank an open menu. `forceRefresh` still clears
        // them when the session actually changed.
        forceRefresh(sessionId: sessionId, keepingEntries: true)
    }

    /// Unconditional refresh — re-scans all layers.
    ///
    /// `keepingEntries: true` leaves the current `entries` in place while the new
    /// scan runs, so the menu shows the previous (possibly slightly stale) list
    /// instead of going blank. Callers that are switching to a DIFFERENT session
    /// pass false, because those entries belong to the old session's roots and
    /// showing them would be wrong, not merely stale.
    ///
    /// [T-ios-mention-index-stale-mounts] This distinction only started to matter
    /// when the TTL dropped to 30s: with a same-session refresh now happening
    /// routinely mid-typing, the unconditional `entries = []` would blank the
    /// open mention menu every time the window lapsed.
    func forceRefresh(sessionId: String?, keepingEntries: Bool = false) {
        let token = UUID()
        currentScanToken = token
        let sessionChanged = (lastScannedSessionId != sessionId)
        lastScannedSessionId = sessionId
        lastScanAt = Date()
        if !keepingEntries || sessionChanged { entries = [] }
        scanAccumulator = []
        isScanning = true

        logger.info("scan start token=\(token.uuidString.prefix(8)) sid=\(sessionId ?? "nil")")

        // Build scan plan on main actor (needs MountedFoldersManager).
        let sessionRoots = Self.sessionRoots(sessionId: sessionId)
        let sharedRoots = Self.sharedRoots()
        let mountRoots = Self.mountRoots()
        #if DEBUG
        print("[MentionScan] scan start token=\(token.uuidString.prefix(8)) sid=\(sessionId ?? "nil") sessionRoots=\(sessionRoots.count) sharedRoots=\(sharedRoots.count) mountRoots=\(mountRoots.count) budget=\(globalScanBudget)")
        for r in sessionRoots {
            let exists = FileManager.default.fileExists(atPath: r.hostURL.path)
            print("[MentionScan]   sessionRoot scope=\(r.scope) linuxPrefix=\(r.linuxPrefix) host=\(r.hostURL.path) exists=\(exists)")
        }
        for r in sharedRoots {
            let exists = FileManager.default.fileExists(atPath: r.hostURL.path)
            let topLevelCount = (try? FileManager.default.contentsOfDirectory(atPath: r.hostURL.path))?.count ?? -1
            print("[MentionScan]   sharedRoot scope=\(r.scope) linuxPrefix=\(r.linuxPrefix) host=\(r.hostURL.path) exists=\(exists) topLevel=\(topLevelCount)")
        }
        for r in mountRoots {
            let exists = FileManager.default.fileExists(atPath: r.hostURL.path)
            print("[MentionScan]   mountRoot name=\(r.mountName ?? "?") linuxPrefix=\(r.linuxPrefix) host=\(r.hostURL.path) exists=\(exists)")
        }
        #endif

        scanQueue.async { [weak self] in
            guard let self else { return }
            var remaining = self.globalScanBudget

            // Layer 1 — session (fast, small).
            let sessionResults = Self.scanRoots(sessionRoots, maxDepth: Int.max, remaining: &remaining)
            #if DEBUG
            print("[MentionScan] layer=session yielded=\(sessionResults.count) remaining=\(remaining)")
            #endif
            self.publish(token: token, newEntries: sessionResults, layer: "session", done: false)

            // Layer 2 — shared / skills / memory.
            let sharedResults = Self.scanRoots(sharedRoots, maxDepth: Int.max, remaining: &remaining)
            #if DEBUG
            let skillEntries = sharedResults.filter { $0.scope == .skills }
            print("[MentionScan] layer=shared yielded=\(sharedResults.count) (skills=\(skillEntries.count)) remaining=\(remaining)")
            for e in skillEntries.prefix(20) {
                print("[MentionScan]   skills: \(e.linuxPath) isDir=\(e.isDirectory)")
            }
            #endif
            self.publish(token: token, newEntries: sharedResults, layer: "shared", done: mountRoots.isEmpty)

            // Layer 3 — each mount separately so slow ones don't block the rest.
            // Always surface the mount's root directory itself as an entry, so
            // typing `@<mountName>` can reference the mount as a whole.
            if mountRoots.isEmpty { return }
            for (idx, mount) in mountRoots.enumerated() {
                var mountResults: [FileMentionEntry] = []
                let mountRootMtime = (try? mount.hostURL.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                mountResults.append(FileMentionEntry(
                    linuxPath: mount.linuxPrefix,
                    scope: .mount,
                    mountName: mount.mountName,
                    modifiedAt: mountRootMtime,
                    isDirectory: true
                ))
                if remaining > 0 {
                    let children = Self.scanRoots([mount], maxDepth: 3, remaining: &remaining)
                    mountResults.append(contentsOf: children)
                }
                let isLast = idx == mountRoots.count - 1
                self.publish(token: token, newEntries: mountResults, layer: "mount[\(mount.mountName ?? "?")]", done: isLast)
                if remaining <= 0 && !isLast {
                    // Budget exhausted — still surface remaining mount roots so
                    // users can at least @-reference them by name.
                    for rest in mountRoots.dropFirst(idx + 1) {
                        let rootEntry = FileMentionEntry(
                            linuxPath: rest.linuxPrefix,
                            scope: .mount,
                            mountName: rest.mountName,
                            modifiedAt: .distantPast,
                            isDirectory: true
                        )
                        self.publish(token: token, newEntries: [rootEntry], layer: "mount-root[\(rest.mountName ?? "?")]", done: rest.mountName == mountRoots.last?.mountName)
                    }
                    break
                }
            }
        }
    }

    /// Return ranked matches for `query`. Empty query returns the top N by
    /// default ordering. Caller can override `limit` but typically shouldn't.
    ///
    /// Score components (combined additively, biggest first):
    ///   • name-match tier (0 / 1000 … 10 000) — drives the primary order
    ///   • scope.rankBoost (0–600) — secondary preference between scopes
    ///   • top-level scope-root bonus (0 / 50) — keeps a whole skill or
    ///     mount above its children
    ///   • recency micro-bonus (0–80) — newer wins on otherwise-equal items
    ///
    /// The smallest gap between adjacent name tiers (1000) is greater than
    /// the maximum sum of all secondary signals (600 + 50 + 80 = 730), so
    /// a stronger name match always wins regardless of scope.
    func matches(for query: String, limit: Int? = nil) -> [FileMentionEntry] {
        let cap = limit ?? defaultMatchLimit
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if q.isEmpty {
            let result = Array(entries.prefix(cap))
            #if DEBUG
            print("[MentionScan] matches(empty) → \(result.count)/\(entries.count)")
            #endif
            return result
        }

        // Subsequence/fuzzy matching is intentionally disabled — it
        // produced too many spurious hits (e.g. "mounts" fuzzily matching
        // "attachments"). Substring matching with tiered weighting gives
        // both predictable behavior and good ranking quality.

        struct Scored { let entry: FileMentionEntry; let score: Int }
        var scored: [Scored] = []
        scored.reserveCapacity(min(entries.count, 512))

        let now = Date()
        let recencyDayCap: TimeInterval = 56 * 86_400 // 8 weeks → 0..80

        for e in entries {
            let base = e.basename.lowercased()
            let path = e.linuxPath.lowercased()
            let stem = (e.basename as NSString).deletingPathExtension.lowercased()

            // Tier-based name match — pick the strongest applicable tier.
            let nameScore: Int
            if base == q {
                nameScore = 10_000                                    // exact full name
            } else if !stem.isEmpty && stem == q {
                nameScore = 9_000                                     // exact stem (basename minus ext)
            } else if base.hasPrefix(q) {
                nameScore = 7_000                                     // prefix match
            } else if Self.basenameWordBoundaryContains(base: base, query: q) {
                nameScore = 6_000                                     // word-boundary contains
            } else if base.contains(q) {
                nameScore = 4_000                                     // generic contains in basename
            } else if Self.anyPathComponentMatches(path: path, query: q) {
                nameScore = 2_000                                     // ancestor directory contains
            } else if path.contains(q) {
                nameScore = 1_000                                     // anywhere in full path
            } else {
                continue
            }

            // Scope priority (max 600 — well below the 1000 tier gap so it
            // only breaks ties between equally-named matches).
            let scopeScore = e.scope.rankBoost

            // Top-level scope-root bonus: typing `@codex` should surface the
            // whole `skills/codex-image` directory above its children.
            let topLevelBonus = (e.isDirectory && Self.isTopLevelScopeRoot(e)) ? 50 : 0

            // Recency micro-bonus (≤ 80). Linear decay over 8 weeks.
            let age = max(0, now.timeIntervalSince(e.modifiedAt))
            let recencyBonus = max(0, Int(80 * (1 - min(1, age / recencyDayCap))))

            let total = nameScore + scopeScore + topLevelBonus + recencyBonus
            scored.append(Scored(entry: e, score: total))
        }

        scored.sort { a, b in
            if a.score != b.score { return a.score > b.score }
            // Final tiebreak — same score → prefer higher-priority scope,
            // then more recent, then shorter path (a "closer" file).
            if a.entry.scope != b.entry.scope { return a.entry.scope < b.entry.scope }
            if a.entry.modifiedAt != b.entry.modifiedAt { return a.entry.modifiedAt > b.entry.modifiedAt }
            return a.entry.linuxPath.count < b.entry.linuxPath.count
        }

        let result = scored.prefix(cap).map(\.entry)
        #if DEBUG
        print("[MentionScan] matches(\"\(q)\") → \(result.count)/\(entries.count) (scored=\(scored.count))")
        for e in result.prefix(10) {
            print("[MentionScan]   match: \(e.linuxPath) scope=\(e.scope) isDir=\(e.isDirectory)")
        }
        // Defensive: every result *must* contain `q` somewhere in its path.
        // If this trips, scoring above admitted a non-matching entry and
        // we want to know loudly rather than silently mis-rank.
        for e in result {
            if !e.linuxPath.lowercased().contains(q) {
                print("[MentionScan] ⚠️ NON-MATCHING entry leaked into result: q=\"\(q)\" path=\(e.linuxPath)")
            }
        }
        #endif
        return result
    }

    /// Word-boundary substring match for the basename. Returns true if
    /// `query` appears at the start of a word fragment delimited by
    /// `_`, `-`, ` `, `.` (e.g. matches `parser` in `markdown_parser.py`,
    /// `web` in `image-web-search.md`). Helps elevate the kind of partial
    /// match users intuitively expect over arbitrary-position substrings.
    private static func basenameWordBoundaryContains(base: String, query: String) -> Bool {
        guard !query.isEmpty, !base.isEmpty else { return false }
        let separators: Set<Character> = ["_", "-", " ", ".", "/"]
        var i = base.startIndex
        while i < base.endIndex {
            // Check if substring starting at i begins with `query`.
            if base[i...].hasPrefix(query) {
                return true
            }
            // Advance to the next word boundary.
            guard let sep = base[i...].firstIndex(where: { separators.contains($0) }) else {
                return false
            }
            i = base.index(after: sep)
        }
        return false
    }

    // MARK: - Root discovery (main actor)

    /// Session-scoped roots. Browser/offloads/memory/skills intentionally excluded
    /// from session scope since browser snapshots aren't user-authored files and
    /// offloads/memory/skills are handled via their global roots.
    private static func sessionRoots(sessionId: String?) -> [ScanRoot] {
        guard let sid = sessionId, !sid.isEmpty else { return [] }
        return [
            ScanRoot(
                scope: .workspace,
                hostURL: AIChatViewModel.minisWorkspacePersistentDir(for: sid),
                linuxPrefix: AIChatViewModel.minisWorkspaceLinuxDir,
                mountName: nil
            ),
            ScanRoot(
                scope: .attachments,
                hostURL: AIChatViewModel.minisAttachmentsPersistentDir(for: sid),
                linuxPrefix: AIChatViewModel.minisAttachmentsLinuxDir,
                mountName: nil
            ),
        ]
    }

    private static func sharedRoots() -> [ScanRoot] {
        [
            ScanRoot(
                scope: .shared,
                hostURL: AIChatViewModel.minisSharedPersistentDir,
                linuxPrefix: AIChatViewModel.minisSharedLinuxDir,
                mountName: nil
            ),
            ScanRoot(
                scope: .skills,
                hostURL: AIChatViewModel.minisSkillsPersistentDir,
                linuxPrefix: AIChatViewModel.minisSkillsLinuxDir,
                mountName: nil
            ),
            ScanRoot(
                scope: .memory,
                hostURL: AIChatViewModel.minisMemoryPersistentDir,
                linuxPrefix: AIChatViewModel.minisMemoryLinuxDir,
                mountName: nil
            ),
        ]
    }

    private static func mountRoots() -> [ScanRoot] {
        let mgr = MountedFoldersManager.shared
        return mgr.entries.compactMap { entry -> ScanRoot? in
            guard let url = mgr.resolvedURL(for: entry.id) else { return nil }
            let linuxPrefix = "\(AIChatViewModel.minisMountsLinuxDir)/\(entry.name)"
            return ScanRoot(scope: .mount, hostURL: url, linuxPrefix: linuxPrefix, mountName: entry.name)
        }
    }

    // MARK: - Background scanning

    private struct ScanRoot {
        let scope: FileMentionEntry.Scope
        let hostURL: URL
        /// Linux-visible path prefix (e.g. `/var/minis/workspace`).
        let linuxPrefix: String
        let mountName: String?
    }

    /// Scan a list of roots, consuming from a shared `remaining` budget.
    /// Stops early (across all roots) once the budget hits zero.
    private static func scanRoots(_ roots: [ScanRoot], maxDepth: Int, remaining: inout Int) -> [FileMentionEntry] {
        var results: [FileMentionEntry] = []
        let fm = FileManager.default
        for root in roots {
            if remaining <= 0 { break }
            guard fm.fileExists(atPath: root.hostURL.path) else { continue }
            let basePath = root.hostURL.path
            let basePathWithSlash = basePath.hasSuffix("/") ? basePath : basePath + "/"
            let keys: [URLResourceKey] = [.isDirectoryKey, .contentModificationDateKey, .isRegularFileKey]
            guard let enumerator = fm.enumerator(
                at: root.hostURL,
                includingPropertiesForKeys: keys,
                options: [.skipsHiddenFiles, .skipsPackageDescendants]
            ) else { continue }

            while let item = enumerator.nextObject() as? URL {
                if remaining <= 0 { break }
                if maxDepth != Int.max {
                    // enumerator.level is 1-based for items directly under root.
                    if enumerator.level > maxDepth {
                        enumerator.skipDescendants()
                        continue
                    }
                }
                let values = try? item.resourceValues(forKeys: Set(keys))
                let isDir = values?.isDirectory ?? false
                // Skip VCS / build junk that users never @-mention.
                if isDir, skipDirNames.contains(item.lastPathComponent) {
                    enumerator.skipDescendants()
                    continue
                }
                let mtime = values?.contentModificationDate ?? .distantPast

                let itemPath = item.path
                let rel: String
                if itemPath.hasPrefix(basePathWithSlash) {
                    rel = String(itemPath.dropFirst(basePathWithSlash.count))
                } else {
                    rel = item.lastPathComponent
                }
                guard !rel.isEmpty else { continue }
                let linuxPath = "\(root.linuxPrefix)/\(rel)"
                results.append(FileMentionEntry(
                    linuxPath: linuxPath,
                    scope: root.scope,
                    mountName: root.mountName,
                    modifiedAt: mtime,
                    isDirectory: isDir
                ))
                remaining -= 1
            }
        }
        return results
    }

    private static let skipDirNames: Set<String> = [
        ".git", ".svn", ".hg",
        "node_modules", ".venv", "venv", "__pycache__",
        ".build", ".gradle", ".idea", ".tox",
        ".mypy_cache", ".pytest_cache", ".ruff_cache",
    ]

    /// True if any `/`-delimited component of `path` contains `query` as a
    /// substring. Lets `@mounts` match `/var/minis/mounts/<name>/...` even
    /// when the file's basename doesn't contain "mounts".
    private static func anyPathComponentMatches(path: String, query: String) -> Bool {
        for component in path.split(separator: "/") where component.contains(query) {
            return true
        }
        return false
    }

    /// Is this the immediate child of a scope root (e.g. `/var/minis/skills/foo`,
    /// `/var/minis/mounts/bar`, `/var/minis/shared/baz`)? These top-level
    /// directories are first-class `@` targets, boosted above their descendants.
    private static func isTopLevelScopeRoot(_ entry: FileMentionEntry) -> Bool {
        guard entry.isDirectory else { return false }
        let path = entry.linuxPath
        let tops = [
            "/var/minis/skills/",
            "/var/minis/mounts/",
            "/var/minis/shared/",
        ]
        for top in tops where path.hasPrefix(top) {
            let rest = path.dropFirst(top.count)
            // First-level child only — must not contain a further '/'.
            return !rest.contains("/") && !rest.isEmpty
        }
        return false
    }

    // MARK: - Merge / publish

    private func publish(token: UUID, newEntries: [FileMentionEntry], layer: String, done: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.currentScanToken == token else { return }

            // [T-ios-mention-index-stale-mounts] Accumulate THIS scan's own output
            // separately from what is on screen.
            //
            // Layers publish incrementally and each one only carries its own
            // roots, so the displayed list must stay a union as the scan
            // progresses. But a refresh that keeps the previous entries visible
            // (see `forceRefresh(keepingEntries:)`) would otherwise make deleted
            // files immortal: they are in `entries`, no layer ever re-reports
            // them, and nothing removes them. Tracking the fresh set lets the
            // final layer swap the list wholesale, so deletions land while the
            // menu still never goes blank mid-scan.
            self.scanAccumulator.append(contentsOf: newEntries)

            var seen = Set<String>()
            var merged: [FileMentionEntry] = []
            merged.reserveCapacity(self.scanAccumulator.count + self.entries.count)
            // Fresh results win; stale carry-overs only fill gaps until `done`.
            for e in self.scanAccumulator where !seen.contains(e.linuxPath) {
                seen.insert(e.linuxPath)
                merged.append(e)
            }
            let freshCount = merged.count
            if !done {
                for e in self.entries where !seen.contains(e.linuxPath) {
                    seen.insert(e.linuxPath)
                    merged.append(e)
                }
            }
            merged.sort(by: Self.defaultOrdering)
            self.entries = merged
            self.logger.info("scan layer=\(layer) fresh=\(freshCount) shown=\(merged.count) done=\(done)")
            if done {
                self.isScanning = false
                self.scanAccumulator = []
            }
        }
    }

    /// Default ordering when no query is active — scope first (session > shared > mount),
    /// directories below files within a scope, then recency.
    private static func defaultOrdering(_ a: FileMentionEntry, _ b: FileMentionEntry) -> Bool {
        if a.scope != b.scope { return a.scope < b.scope }
        // Top-level scope roots (a whole skill / mount / shared folder) come
        // first within their scope — a blank `@` query should present these
        // as the primary targets, ahead of individual files.
        let aTop = Self.isTopLevelScopeRoot(a)
        let bTop = Self.isTopLevelScopeRoot(b)
        if aTop != bTop { return aTop && !bTop }
        if a.isDirectory != b.isDirectory { return !a.isDirectory && b.isDirectory }
        return a.modifiedAt > b.modifiedAt
    }
}
