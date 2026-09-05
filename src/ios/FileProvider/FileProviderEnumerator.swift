import FileProvider
import os.log

/// Enumerates the contents of a directory in the shared folder.
final class FileProviderEnumerator: NSObject, NSFileProviderEnumerator {
    private let containerItemIdentifier: NSFileProviderItemIdentifier
    private let recursive: Bool
    private static let log = OSLog(subsystem: "com.openminis.app.FileProvider", category: "Enumerator")

    init(containerItemIdentifier: NSFileProviderItemIdentifier, recursive: Bool = false) {
        self.containerItemIdentifier = containerItemIdentifier
        self.recursive = recursive
        super.init()
    }

    func invalidate() {}

    // MARK: - Full enumeration (initial sync)

    func enumerateItems(for observer: NSFileProviderEnumerationObserver, startingAt page: NSFileProviderPage) {
        guard let root = FileProviderExtension.providerRoot else {
            observer.finishEnumeratingWithError(FileProviderExtension.appGroupUnavailableError)
            return
        }
        let items = listItems(providerRoot: root)
        os_log("enumerateItems container=%{public}@ recursive=%d count=%d",
               log: Self.log, type: .info,
               containerItemIdentifier.rawValue, recursive ? 1 : 0, items.count)
        FPSyncTraceLog.log("enumerateItems id=\(containerItemIdentifier.rawValue) recursive=\(recursive) count=\(items.count)")
        observer.didEnumerate(items)
        observer.finishEnumerating(upTo: nil)
    }

    // MARK: - Change enumeration (incremental updates)

    func enumerateChanges(for observer: NSFileProviderChangeObserver, from syncAnchor: NSFileProviderSyncAnchor) {
        guard let root = FileProviderExtension.providerRoot else {
            observer.finishEnumeratingWithError(FileProviderExtension.appGroupUnavailableError)
            return
        }
        let currentAnchor = buildSyncAnchor(providerRoot: root)
        let items = listItems(providerRoot: root)
        os_log("enumerateChanges container=%{public}@ anchorMatch=%d itemCount=%d",
               log: Self.log, type: .info,
               containerItemIdentifier.rawValue,
               syncAnchor == currentAnchor ? 1 : 0,
               items.count)
        FPSyncTraceLog.log("enumerateChanges id=\(containerItemIdentifier.rawValue) anchorOld=\(Self.hex(syncAnchor.rawValue)) anchorNew=\(Self.hex(currentAnchor.rawValue)) match=\(syncAnchor == currentAnchor) count=\(items.count)")

        if syncAnchor == currentAnchor {
            observer.finishEnumeratingChanges(upTo: currentAnchor, moreComing: false)
            return
        }

        // External writes (iSH shell, FileBrowserView, etc.) cause the anchor to
        // change but we have no per-anchor history to compute a precise diff.
        //
        // Empirically, returning `.syncAnchorExpired` here causes iOS Files to
        // refuse re-enumeration in many cases — the container stays stuck on
        // its cached listing, which is the user-visible "writes don't appear"
        // bug. Instead, replay every current item via `didUpdate` and finish
        // with the new anchor: iOS dedups by itemIdentifier and refreshes
        // metadata for items that already existed, while picking up newly-
        // appeared ones. We can't report deletions this way, but in-app
        // deletions go through `deleteItem` (which signals directly) and
        // external deletions are handled by the AppGroupChangeWatcher's
        // periodic foreground reconciliation.
        observer.didUpdate(items)
        observer.finishEnumeratingChanges(upTo: currentAnchor, moreComing: false)
    }

    func currentSyncAnchor(completionHandler: @escaping (NSFileProviderSyncAnchor?) -> Void) {
        guard let root = FileProviderExtension.providerRoot else {
            // This protocol callback has no Error parameter; nil means no
            // usable anchor. Enumeration requests report the explicit error.
            os_log("currentSyncAnchor unavailable: App Group container is not authorized",
                   log: Self.log, type: .error)
            completionHandler(nil)
            return
        }
        completionHandler(buildSyncAnchor(providerRoot: root))
    }

    /// Hex-encode an 8-byte anchor for human-readable log lines. Falls back
    /// to a length tag for non-standard anchor sizes (shouldn't happen, but
    /// keeps the log line readable if framework changes the format).
    private static func hex(_ data: Data) -> String {
        if data.count == 8 {
            return data.map { String(format: "%02x", $0) }.joined()
        }
        return "len=\(data.count)"
    }

    // MARK: - Helpers

    /// True if `name` looks like a FileProvider framework-reserved identifier
    /// (e.g. "NSFileProviderTrashContainerItemIdentifier"). Such names must
    /// never appear as real filenames in providerRoot.
    private static func isReservedIdentifier(_ name: String) -> Bool {
        return name.hasPrefix("NSFileProvider") && name.hasSuffix("Identifier")
    }

    /// Names of metadata files/dirs that historically lived under providerRoot
    /// and must never be exposed to iOS Files. Even after migration to
    /// MinisConfig/, a stale copy might still be around after upgrading —
    /// this list protects against that.
    private static let metadataBlacklist: Set<String> = [
        "mounted-folders.json",
        "logs",
    ]

    /// True if `name` is either a dotfile, reserved FP identifier, or a
    /// known metadata filename that must not appear in iOS Files.
    private static func isHiddenMetadata(_ name: String) -> Bool {
        if name.hasPrefix(".") { return true }
        if isReservedIdentifier(name) { return true }
        if metadataBlacklist.contains(name) { return true }
        return false
    }

    private func directoryURL(providerRoot: URL) -> URL {
        if containerItemIdentifier == .rootContainer {
            return providerRoot
        }
        return providerRoot
            .appendingPathComponent(containerItemIdentifier.rawValue)
    }

    private func listItems(providerRoot: URL) -> [FileProviderItem] {
        // Trash is not supported — always return empty.
        if containerItemIdentifier == .trashContainer {
            return []
        }

        let dirURL = directoryURL(providerRoot: providerRoot)
        let fm = FileManager.default
        if containerItemIdentifier == .rootContainer {
            try? fm.createDirectory(at: dirURL, withIntermediateDirectories: true)
        }

        if recursive {
            return listItemsRecursively(at: dirURL, providerRoot: providerRoot, parentIdentifier: containerItemIdentifier)
        }

        guard var contents = try? fm.contentsOfDirectory(
            at: dirURL,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        // Defensive: never expose a disk entry whose name collides with a
        // system-reserved FileProvider identifier. A prior bug allowed
        // `NSFileProviderTrashContainerItemIdentifier/` to be materialized
        // under providerRoot when iOS Files asked to move an item to trash.
        contents = contents.filter { !Self.isHiddenMetadata($0.lastPathComponent) }

        // At the root container, filter out top-level subdirs the user has
        // hidden from iOS Files via Settings → Shared Folders. The data still
        // lives on disk; it just doesn't appear in the Files app until the
        // user re-enables the toggle.
        if containerItemIdentifier == .rootContainer {
            let visible = SharedFolderVisibility.visibleFolderNames
            contents = contents.filter { url in
                // Only filter known top-level names; unknown entries (if any)
                // are passed through unchanged so future additions are safe.
                let name = url.lastPathComponent
                if SharedFolderVisibility.allFolderNames.contains(name) {
                    return visible.contains(name)
                }
                return true
            }
        }

        return contents.map { url in
            FileProviderItem(url: url, providerRoot: providerRoot, parentIdentifier: containerItemIdentifier)
        }
    }

    /// Recursively list all items under a directory for the working set.
    private func listItemsRecursively(at dirURL: URL, providerRoot: URL, parentIdentifier: NSFileProviderItemIdentifier) -> [FileProviderItem] {
        let fm = FileManager.default
        guard var contents = try? fm.contentsOfDirectory(
            at: dirURL,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        // Defensive: same reserved-identifier filter as the non-recursive path.
        contents = contents.filter { !Self.isHiddenMetadata($0.lastPathComponent) }

        let rootURL = providerRoot
        // At the provider root, apply the same hidden-folder filter as the
        // non-recursive path so hidden top-level subdirs don't leak into the
        // working set when iOS Files enumerates recursively.
        if dirURL.standardized.path == rootURL.standardized.path {
            let visible = SharedFolderVisibility.visibleFolderNames
            contents = contents.filter { url in
                let name = url.lastPathComponent
                if SharedFolderVisibility.allFolderNames.contains(name) {
                    return visible.contains(name)
                }
                return true
            }
        }

        let root = rootURL.path
        var items: [FileProviderItem] = []
        for url in contents {
            let item = FileProviderItem(url: url, providerRoot: providerRoot, parentIdentifier: parentIdentifier)
            items.append(item)

            var isDir: ObjCBool = false
            if fm.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
                let relative = url.path.replacingOccurrences(of: root, with: "")
                    .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                let childParentID = relative.isEmpty
                    ? NSFileProviderItemIdentifier.rootContainer
                    : NSFileProviderItemIdentifier(relative)
                items.append(contentsOf: listItemsRecursively(at: url, providerRoot: providerRoot, parentIdentifier: childParentID))
            }
        }
        return items
    }

    /// Build a compact, deterministic sync anchor from directory contents.
    /// Uses a fixed-size 8-byte representation to avoid exceeding the system's
    /// vendor-token size limit (which causes an assertion in FPXObserver).
    private func buildSyncAnchor(providerRoot: URL) -> NSFileProviderSyncAnchor {
        let items = listItems(providerRoot: providerRoot)

        // Base hash: item count (even 0 is fine — we still fold visibility in below).
        var hash: UInt64 = UInt64(items.count)

        // Mix the current visibility state into the anchor so toggling a
        // shared folder's "Show in Files app" switch causes iOS Files to
        // discard its cache and re-enumerate. Without this, the recursive
        // working-set path can keep returning stale entries even after the
        // non-recursive listItems() path has started filtering them out.
        for name in SharedFolderVisibility.allFolderNames {
            let isVisible = SharedFolderVisibility.visibleFolderNames.contains(name)
            hash = hash &* 31 &+ (isVisible ? 1 : 0)
            // Also fold in the name so adding new names later doesn't collide.
            for byte in name.utf8 {
                hash = hash &* 31 &+ UInt64(byte)
            }
        }

        for item in items {
            let mtime = item.contentModificationDate ?? Date.distantPast
            hash = hash &+ UInt64(bitPattern: Int64(mtime.timeIntervalSince1970 * 1000))
            // Mix in the identifier to detect renames
            for byte in item.itemIdentifier.rawValue.utf8 {
                hash = hash &* 31 &+ UInt64(byte)
            }
        }
        var hashBytes = hash
        return NSFileProviderSyncAnchor(Data(bytes: &hashBytes, count: 8))
    }

}
