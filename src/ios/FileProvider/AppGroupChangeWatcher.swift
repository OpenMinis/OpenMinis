import Foundation
import FileProvider
import UIKit

/// Watches the App Group `MinisFileProvider/{shared,skills,memory}/` subtrees and
/// signals the FileProvider extension whenever directory contents change.
///
/// Why this exists: iSH shell commands and the in-app FileBrowserView both write
/// to these directories via plain POSIX I/O, bypassing the FileProvider create/move
/// APIs. iOS therefore never learns about the new files and the Files app keeps
/// displaying its stale enumerator cache (the "shared looks empty" bug).
///
/// Strategy: per-directory `DispatchSourceFileSystemObject` (kqueue VNODE) watches
/// across the entire subtree. New subdirs get a watch attached on the fly; deleted
/// ones get their watch dropped. Bursts coalesce within a short window so a `cp -r`
/// of 1000 files signals once per touched parent, not 1000 times.
@MainActor
final class AppGroupChangeWatcher {
    static let shared = AppGroupChangeWatcher()

    private let logger = AppLogger(category: "FPWatcher")
    private let queue = DispatchQueue(label: "com.openminis.app.fpwatcher", qos: .utility)
    private let domainIdentifier = NSFileProviderDomainIdentifier("com.openminis.app.files")

    /// Hard cap on simultaneous watched directories. Each watch holds an `O_EVTONLY`
    /// fd; iOS apps typically have a per-process limit around 256. We cap well below
    /// to leave room for the rest of the app.
    private let maxWatches = 180

    private struct Watch {
        let url: URL
        let rootKey: String           // "shared" | "skills" | "memory"
        let relativePath: String      // path inside the root, "" for root itself
        let source: DispatchSourceFileSystemObject
        var childNames: Set<String>   // baseline for new/deleted subdir detection
    }

    private var watches: [URL: Watch] = [:]
    private var pendingSignals: [NSFileProviderItemIdentifier: DispatchWorkItem] = [:]
    private let signalCoalesceMs = 250
    private var started = false

    private init() {}

    /// Start watching the three exposed roots. Idempotent — calling twice is a no-op.
    func start() {
        guard !started else { return }
        guard SharedContainerStore.isAppGroupAvailable else {
            logger.warning("App Group unavailable; Files change watcher is disabled for private storage.")
            return
        }
        started = true

        let fm = FileManager.default
        let roots: [(URL, String)] = [
            (AIChatViewModel.minisSharedPersistentDir, "shared"),
            (AIChatViewModel.minisSkillsPersistentDir, "skills"),
            (AIChatViewModel.minisMemoryPersistentDir, "memory"),
        ]

        for (rootURL, rootKey) in roots {
            // Make sure the root exists — otherwise kqueue can't open it.
            try? fm.createDirectory(at: rootURL, withIntermediateDirectories: true)
            let beforeCount = watches.count
            attachRecursive(at: rootURL, rootKey: rootKey, relativePath: "")
            let attached = watches.count - beforeCount
            let topChildren = (try? fm.contentsOfDirectory(atPath: rootURL.path).count) ?? -1
            logger.info("[FPSyncTrace] root=\(rootKey) path=\(rootURL.path) watchesAttached=\(attached) topLevelChildren=\(topChildren)")
        }

        logger.info("started — watching \(self.watches.count) directories")

        // When the app returns to foreground, kqueue events that fired while
        // suspended may have been dropped. Force a full reconciliation: re-walk
        // the trees and signal each root so the Files app refreshes.
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleForeground),
            name: UIApplication.willEnterForegroundNotification, object: nil)
    }

    @objc private func handleForeground() {
        queue.async { [weak self] in
            guard let self else { return }
            // Walk the trees and add watches for any subdirs that appeared while
            // suspended. Drop watches whose target vanished.
            for (_, watch) in self.watches {
                self.reconcileChildren(watch: watch)
            }
            // Signal all three roots + the working set to nudge Files app
            // to re-enumerate after we missed events while suspended.
            for rootKey in ["shared", "skills", "memory"] {
                self.scheduleSignal(itemID: NSFileProviderItemIdentifier(rootKey))
            }
            self.scheduleSignal(itemID: .workingSet)
        }
    }

    // MARK: - Watch lifecycle

    /// Attach a watch to `url` and recursively to all existing subdirectories.
    private func attachRecursive(at url: URL, rootKey: String, relativePath: String) {
        attachWatch(at: url, rootKey: rootKey, relativePath: relativePath)
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: url,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]) else { return }
        for child in entries {
            let isDir = (try? child.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            guard isDir else { continue }
            let childRel = relativePath.isEmpty
                ? child.lastPathComponent
                : "\(relativePath)/\(child.lastPathComponent)"
            attachRecursive(at: child, rootKey: rootKey, relativePath: childRel)
        }
    }

    private func attachWatch(at url: URL, rootKey: String, relativePath: String) {
        guard watches[url] == nil else { return }
        guard watches.count < maxWatches else {
            logger.warning("watch cap reached (\(self.maxWatches)); skipping \(url.path)")
            return
        }
        let fd = open(url.path, O_EVTONLY)
        guard fd >= 0 else {
            logger.warning("open(O_EVTONLY) failed for \(url.path) errno=\(errno)")
            return
        }
        let source = DispatchSource.makeFileSystemObjectSource(
            fileDescriptor: fd,
            eventMask: [.write, .delete, .rename, .extend, .attrib],
            queue: queue)

        source.setEventHandler { [weak self] in
            guard let self else { return }
            let mask = source.data
            self.handleEvent(url: url, rootKey: rootKey,
                             relativePath: relativePath, mask: mask)
        }
        source.setCancelHandler { close(fd) }
        source.resume()

        let initialChildren = listImmediateChildren(at: url)
        watches[url] = Watch(
            url: url, rootKey: rootKey, relativePath: relativePath,
            source: source, childNames: initialChildren)
    }

    private func detachWatch(at url: URL) {
        guard let watch = watches.removeValue(forKey: url) else { return }
        watch.source.cancel()
    }

    /// Detach watches for `url` and all its descendants. Used when a directory
    /// is removed/renamed.
    private func detachSubtree(at url: URL) {
        let prefix = url.path
        let toRemove = watches.keys.filter {
            $0.path == prefix || $0.path.hasPrefix(prefix + "/")
        }
        for key in toRemove {
            detachWatch(at: key)
        }
    }

    // MARK: - Event handling

    private func handleEvent(url: URL, rootKey: String,
                             relativePath: String,
                             mask: DispatchSource.FileSystemEvent) {
        // Directory itself was deleted/renamed — drop the entire subtree.
        if mask.contains(.delete) || mask.contains(.rename) {
            // The deleted directory's parent will get its own .write event,
            // which signals the parent. We just clean up watches here.
            detachSubtree(at: url)
            // Best-effort signal of this directory's parent.
            scheduleSignalForParent(rootKey: rootKey, relativePath: relativePath)
            return
        }

        // Contents changed: signal this directory's enumerator and reconcile
        // children so newly-created subdirs get a watch attached.
        scheduleSignalFor(rootKey: rootKey, relativePath: relativePath)
        if let watch = watches[url] {
            reconcileChildren(watch: watch)
        }
    }

    /// Compare the watched directory's current entries to the cached child
    /// snapshot; attach watches for new subdirs, detach for removed ones.
    private func reconcileChildren(watch: Watch) {
        let current = listImmediateChildren(at: watch.url)
        let added = current.subtracting(watch.childNames)
        let removed = watch.childNames.subtracting(current)

        if !added.isEmpty || !removed.isEmpty {
            // Counts only — entry names can include user-chosen filenames.
            logger.info("[FPSyncTrace] reconcile rootKey=\(watch.rootKey) depth=\(watch.relativePath.isEmpty ? 0 : watch.relativePath.split(separator: "/").count) added=\(added.count) removed=\(removed.count)")
        }

        for name in added {
            let childURL = watch.url.appendingPathComponent(name)
            let isDir = (try? childURL.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            guard isDir else { continue }
            let childRel = watch.relativePath.isEmpty
                ? name
                : "\(watch.relativePath)/\(name)"
            attachRecursive(at: childURL, rootKey: watch.rootKey, relativePath: childRel)
        }
        for name in removed {
            let childURL = watch.url.appendingPathComponent(name)
            detachSubtree(at: childURL)
        }

        if !added.isEmpty || !removed.isEmpty {
            // Mutate the watch's cached children. `watches` values are by-value
            // structs so we have to write back.
            if var stored = watches[watch.url] {
                stored.childNames = current
                watches[watch.url] = stored
            }
        }
    }

    private func listImmediateChildren(at url: URL) -> Set<String> {
        guard let entries = try? FileManager.default.contentsOfDirectory(
            atPath: url.path) else { return [] }
        return Set(entries)
    }

    // MARK: - Signal coalescing

    private func itemIdentifier(rootKey: String, relativePath: String) -> NSFileProviderItemIdentifier {
        if relativePath.isEmpty {
            return NSFileProviderItemIdentifier(rootKey)
        }
        return NSFileProviderItemIdentifier("\(rootKey)/\(relativePath)")
    }

    /// Fan-out the signal up the ancestor chain and to the working set.
    ///
    /// iOS Files may be currently displaying any ancestor of the changed
    /// directory (e.g. user is at `shared/` while a write happens in
    /// `shared/foo/bar/baz.txt`). Whichever level the user is at, that
    /// container's enumerator must be poked so it reports the new mtime
    /// and re-fetches children if the user opens deeper. Also signal
    /// `.workingSet` so spotlight / global search indexes update.
    private func scheduleSignalFor(rootKey: String, relativePath: String) {
        // The directory where the change happened.
        scheduleSignal(itemID: itemIdentifier(rootKey: rootKey, relativePath: relativePath))
        // Each ancestor up to and including the rootKey itself ("shared",
        // "skills", "memory"). We do NOT signal `.rootContainer` here —
        // its listing is just the three fixed top-level dirs and is
        // unaffected by writes inside them.
        var rel = relativePath
        while !rel.isEmpty {
            rel = (rel as NSString).deletingLastPathComponent
            if rel.isEmpty {
                scheduleSignal(itemID: NSFileProviderItemIdentifier(rootKey))
            } else {
                scheduleSignal(itemID: NSFileProviderItemIdentifier("\(rootKey)/\(rel)"))
            }
        }
        // Working set covers Spotlight + recent / unified search.
        scheduleSignal(itemID: .workingSet)
    }

    private func scheduleSignalForParent(rootKey: String, relativePath: String) {
        let parentRel = (relativePath as NSString).deletingLastPathComponent
        scheduleSignalFor(rootKey: rootKey, relativePath: parentRel)
    }

    /// Coalesce repeated requests for the same enumerator into a single signal
    /// fired after `signalCoalesceMs`. A burst of writes (e.g. `cp -r` of many
    /// files into the same folder) becomes one signal.
    private func scheduleSignal(itemID: NSFileProviderItemIdentifier) {
        // Cancel any previously-scheduled work for the same id.
        pendingSignals[itemID]?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.deliverSignal(itemID: itemID)
        }
        pendingSignals[itemID] = work
        queue.asyncAfter(deadline: .now() + .milliseconds(signalCoalesceMs), execute: work)
    }

    private func deliverSignal(itemID: NSFileProviderItemIdentifier) {
        pendingSignals.removeValue(forKey: itemID)
        let domainID = domainIdentifier
        NSFileProviderManager.getDomainsWithCompletionHandler { [logger] domains, error in
            if let error {
                logger.warning("[FPSyncTrace] getDomains failed: \(error.localizedDescription)")
                return
            }
            guard let domain = domains.first(where: { $0.identifier == domainID }) else {
                logger.warning("[FPSyncTrace] domain \(domainID.rawValue) not registered — skipping signal id=\(itemID.rawValue)")
                return
            }
            NSFileProviderManager(for: domain)?.signalEnumerator(for: itemID) { signalErr in
                if let signalErr {
                    logger.warning("[FPSyncTrace] signalEnumerator(\(itemID.rawValue)) failed: \(signalErr.localizedDescription)")
                } else {
                    // [T-ios-log-noise-reduction] INFO→DEBUG: fires every ~2 min
                    // per watched root; no production value. The failure branch
                    // above stays WARN.
                    logger.debug("[FPSyncTrace] signalled id=\(itemID.rawValue)")
                }
            }
        }
    }
}
