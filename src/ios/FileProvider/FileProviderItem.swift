import FileProvider
import UniformTypeIdentifiers

/// Maps a file or directory in the shared folder to FileProvider item metadata.
final class FileProviderItem: NSObject, NSFileProviderItem {
    private let fileURL: URL
    private let providerRoot: URL
    private let parentID: NSFileProviderItemIdentifier
    private let attrs: [FileAttributeKey: Any]?
    private let isRoot: Bool

    init(url: URL, providerRoot: URL, parentIdentifier: NSFileProviderItemIdentifier, isRoot: Bool = false) {
        self.fileURL = url
        self.providerRoot = providerRoot
        self.parentID = parentIdentifier
        self.attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        self.isRoot = isRoot
        super.init()
    }

    var itemIdentifier: NSFileProviderItemIdentifier {
        if isRoot { return .rootContainer }
        let root = providerRoot
        let relative = fileURL.path.replacingOccurrences(of: root.path, with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if relative.isEmpty {
            return .rootContainer
        }
        return NSFileProviderItemIdentifier(relative)
    }

    var parentItemIdentifier: NSFileProviderItemIdentifier {
        parentID
    }

    var filename: String {
        if isRoot { return "Minis" }
        return fileURL.lastPathComponent
    }

    var contentType: UTType {
        if isRoot { return .folder }
        var isDir: ObjCBool = false
        FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDir)
        if isDir.boolValue {
            return .folder
        }
        return UTType(filenameExtension: fileURL.pathExtension) ?? .data
    }

    /// Whether this item is one of the three fixed top-level subdirectories.
    private var isTopLevelSubdir: Bool {
        parentID == .rootContainer && FileProviderExtension.topLevelSubdirs.contains(fileURL.lastPathComponent)
    }

    private static let readOnlyDirs: Set<String> = ["memory", "skills"]

    /// Whether this item lives under memory/ or skills/ (read-only trees).
    private var isInReadOnlyTree: Bool {
        let id = itemIdentifier.rawValue
        return Self.readOnlyDirs.contains(id) || id.hasPrefix("memory/") || id.hasPrefix("skills/")
    }

    var capabilities: NSFileProviderItemCapabilities {
        if isRoot {
            return [.allowsReading, .allowsContentEnumerating]
        }
        if isInReadOnlyTree {
            if isTopLevelSubdir {
                return [.allowsReading, .allowsContentEnumerating]
            }
            return [.allowsReading]
        }
        if isTopLevelSubdir {
            // shared/ — allow adding sub-items but not rename/delete of the dir itself
            return [.allowsReading, .allowsContentEnumerating, .allowsAddingSubItems]
        }
        return [.allowsReading, .allowsWriting, .allowsRenaming, .allowsReparenting,
                .allowsDeleting]
    }

    var itemVersion: NSFileProviderItemVersion {
        let modDate = (attrs?[.modificationDate] as? Date) ?? Date()
        let stamp = "\(modDate.timeIntervalSince1970)".data(using: .utf8) ?? Data()
        return NSFileProviderItemVersion(contentVersion: stamp, metadataVersion: stamp)
    }

    var documentSize: NSNumber? {
        guard let size = attrs?[.size] as? UInt64 else { return nil }
        return NSNumber(value: size)
    }

    var contentModificationDate: Date? {
        attrs?[.modificationDate] as? Date
    }

    var creationDate: Date? {
        attrs?[.creationDate] as? Date
    }
}
