#if DEBUG
import Foundation
import SwiftUI
import UIKit
import SQLite3
import Compression
import os.log

private let logger = AppLogger(category: "ICloudBackup")

// MARK: - ICloudBackupManager

@MainActor
final class ICloudBackupManager: ObservableObject {

    static let shared = ICloudBackupManager()

    enum BackupCategory: String, CaseIterable, Identifiable {
        case sessions           // minis.db + media/ + minis/
        case skillsAndMemories  // skills.db + skills/ + memory/
        case full               // all

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .sessions: return "Sessions"
            case .skillsAndMemories: return "Skills & Memories"
            case .full: return "Full Backup"
            }
        }

        var filePrefix: String {
            switch self {
            case .sessions: return "sessions"
            case .skillsAndMemories: return "skills-memories"
            case .full: return "full"
            }
        }

        var systemImage: String {
            switch self {
            case .sessions: return "bubble.left.and.bubble.right"
            case .skillsAndMemories: return "brain.head.profile"
            case .full: return "archivebox"
            }
        }

        var iconColor: Color {
            switch self {
            case .sessions:          return .blue
            case .skillsAndMemories: return .purple
            case .full:              return .orange
            }
        }

        var description: String {
            switch self {
            case .sessions:
                return "Chat history, media attachments, and session workspace files"
            case .skillsAndMemories:
                return "Skills, memory logs, and skill configuration files"
            case .full:
                return "Complete backup including sessions, skills, and memories"
            }
        }
    }

    struct BackupEntry: Identifiable {
        let id: String          // file name
        let deviceName: String
        let category: BackupCategory
        let date: Date
        let fileSize: Int64
        let url: URL
    }

    @Published var isBackingUp = false
    @Published var isRestoring = false
    @Published var progress: Double = 0
    @Published var statusMessage: String = ""
    @Published var availableBackups: [BackupEntry] = []

    private let fm = FileManager.default
    private let containerID = "iCloud.com.openminis.app"

    // MARK: - iCloud Container

    private var iCloudContainerURL: URL? {
        fm.url(forUbiquityContainerIdentifier: containerID)
    }

    var isICloudAvailable: Bool { iCloudContainerURL != nil }

    private var backupDirectoryURL: URL? {
        guard let container = iCloudContainerURL else { return nil }
        // Deliberately NOT `DeviceIdentity.displayName`, even though the user
        // can now rename this device ([T-backup-device-name-setting]). This
        // string is a DIRECTORY PATH in the ubiquity container, not a label:
        // honouring a rename would point the app at a new, empty folder and
        // silently strand every backup already sitting in the old one. The
        // folder keeps the name it was created under; renaming what a user
        // sees is the settings field's job, relocating their existing iCloud
        // backups is not.
        let deviceName = UIDevice.current.name
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
        return container
            .appendingPathComponent("Documents/backups/\(deviceName)", isDirectory: true)
    }

    private var backupsRootURL: URL? {
        guard let container = iCloudContainerURL else { return nil }
        return container.appendingPathComponent("Documents/backups", isDirectory: true)
    }

    // MARK: - Local Paths

    private var minisBaseURL: URL {
        let lib = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        return lib.appendingPathComponent("MinisChat", isDirectory: true)
    }

    private var minisDBURL: URL { minisBaseURL.appendingPathComponent("minis.db") }
    private var skillsDBURL: URL { minisBaseURL.appendingPathComponent("skills.db") }
    private var mediaURL: URL { minisBaseURL.appendingPathComponent("media", isDirectory: true) }
    private var sessionMinisURL: URL { minisBaseURL.appendingPathComponent("minis", isDirectory: true) }
    private var skillsURL: URL { minisBaseURL.appendingPathComponent("skills", isDirectory: true) }
    private var memoryURL: URL { minisBaseURL.appendingPathComponent("memory", isDirectory: true) }

    // MARK: - Backup

    func backup(category: BackupCategory) async throws {
        guard !isBackingUp else { return }
        guard let destDir = backupDirectoryURL else {
            throw BackupError.iCloudUnavailable
        }

        isBackingUp = true
        progress = 0
        statusMessage = "Preparing backup..."
        defer {
            isBackingUp = false
            statusMessage = ""
        }

        // Ensure destination directory exists
        try fm.createDirectory(at: destDir, withIntermediateDirectories: true)

        // Create temp staging directory
        let tempDir = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try fm.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: tempDir) }

        let stageDir = tempDir.appendingPathComponent("backup", isDirectory: true)
        try fm.createDirectory(at: stageDir, withIntermediateDirectories: true)

        // Stage files based on category
        statusMessage = "Copying files..."
        progress = 0.1

        switch category {
        case .sessions:
            try await stageSessionFiles(to: stageDir)
        case .skillsAndMemories:
            try await stageSkillsAndMemoryFiles(to: stageDir)
        case .full:
            try await stageSessionFiles(to: stageDir)
            try await stageSkillsAndMemoryFiles(to: stageDir)
        }

        progress = 0.6
        statusMessage = "Creating archive..."

        // Create ZIP
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HHmmss"
        let timestamp = formatter.string(from: Date())
        let zipName = "\(category.filePrefix)_\(timestamp).zip"
        let zipURL = tempDir.appendingPathComponent(zipName)

        try await createZip(from: stageDir, to: zipURL)

        progress = 0.8
        statusMessage = "Uploading to iCloud..."

        // Move ZIP to iCloud
        let destURL = destDir.appendingPathComponent(zipName)
        if fm.fileExists(atPath: destURL.path) {
            try fm.removeItem(at: destURL)
        }
        try fm.copyItem(at: zipURL, to: destURL)

        progress = 1.0
        statusMessage = "Backup complete"
        logger.info("Backup created: \(zipName)")

        // Refresh backup list
        await listBackups()
    }

    // MARK: - Stage Files

    private func stageSessionFiles(to dir: URL) async throws {
        // WAL checkpoint minis.db before copying
        try walCheckpoint(dbPath: minisDBURL.path)

        // Copy minis.db
        let dbDest = dir.appendingPathComponent("minis.db")
        if fm.fileExists(atPath: minisDBURL.path) {
            try fm.copyItem(at: minisDBURL, to: dbDest)
        }

        // Copy media/
        if fm.fileExists(atPath: mediaURL.path) {
            try fm.copyItem(at: mediaURL, to: dir.appendingPathComponent("media"))
        }

        // Copy minis/ (session workspace files)
        if fm.fileExists(atPath: sessionMinisURL.path) {
            try fm.copyItem(at: sessionMinisURL, to: dir.appendingPathComponent("minis"))
        }
    }

    private func stageSkillsAndMemoryFiles(to dir: URL) async throws {
        // WAL checkpoint skills.db before copying
        try walCheckpoint(dbPath: skillsDBURL.path)

        // Copy skills.db
        if fm.fileExists(atPath: skillsDBURL.path) {
            try fm.copyItem(at: skillsDBURL, to: dir.appendingPathComponent("skills.db"))
        }

        // Copy skills/
        if fm.fileExists(atPath: skillsURL.path) {
            try fm.copyItem(at: skillsURL, to: dir.appendingPathComponent("skills"))
        }

        // Copy memory/
        if fm.fileExists(atPath: memoryURL.path) {
            try fm.copyItem(at: memoryURL, to: dir.appendingPathComponent("memory"))
        }
    }

    // MARK: - WAL Checkpoint

    private func walCheckpoint(dbPath: String) throws {
        guard fm.fileExists(atPath: dbPath) else { return }

        var db: OpaquePointer?
        let rc = sqlite3_open_v2(dbPath, &db, SQLITE_OPEN_READWRITE, nil)
        guard rc == SQLITE_OK, let db = db else {
            throw BackupError.databaseError("Failed to open DB for checkpoint: \(rc)")
        }
        defer { sqlite3_close(db) }

        let checkpointRC = sqlite3_wal_checkpoint_v2(db, nil, SQLITE_CHECKPOINT_FULL, nil, nil)
        if checkpointRC != SQLITE_OK {
            logger.warning("WAL checkpoint returned \(checkpointRC) for \(dbPath)")
        }
    }

    // MARK: - ZIP Creation (using NSFileCoordinator)

    private func createZip(from sourceDir: URL, to zipURL: URL) async throws {
        // Use Foundation's built-in ZIP capability via NSFileCoordinator
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let coordinator = NSFileCoordinator()
            var error: NSError?

            coordinator.coordinate(readingItemAt: sourceDir,
                                   options: .forUploading,
                                   error: &error) { zippedURL in
                do {
                    try self.fm.copyItem(at: zippedURL, to: zipURL)
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }

            if let error = error {
                continuation.resume(throwing: error)
            }
        }
    }

    // MARK: - Unzip

    private func unzip(from zipURL: URL, to destDir: URL) async throws {
        // Use Process/spawning is unavailable on iOS, use a manual approach
        // We'll use NSFileCoordinator reading + FileManager to extract
        // Actually, on iOS we need a different approach since there's no built-in unzip API
        // We'll shell out to the approach of creating a temp dir and using the
        // compression framework, or use a simple custom unzip

        // For iOS, the simplest approach is to use the `Archive` type if available,
        // or we can use the command-line zip format. Let's use a minimal unzip implementation.
        try extractZip(at: zipURL, to: destDir)
    }

    /// Minimal ZIP extraction using Apple's compression support.
    /// ZIP files created by NSFileCoordinator are standard ZIP archives.
    private func extractZip(at zipURL: URL, to destination: URL) throws {
        let zipData = try Data(contentsOf: zipURL)
        try fm.createDirectory(at: destination, withIntermediateDirectories: true)

        // Parse ZIP file structure
        try zipData.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            guard let base = buffer.baseAddress else {
                throw BackupError.zipError("Empty ZIP data")
            }
            let size = buffer.count

            // Find End of Central Directory record
            var eocdOffset = -1
            for i in stride(from: size - 22, through: 0, by: -1) {
                let sig = base.load(fromByteOffset: i, as: UInt32.self)
                if sig == 0x06054b50 {
                    eocdOffset = i
                    break
                }
            }

            guard eocdOffset >= 0 else {
                throw BackupError.zipError("Invalid ZIP: no EOCD")
            }

            let cdOffset = Int(base.load(fromByteOffset: eocdOffset + 16, as: UInt32.self))
            let cdEntries = Int(base.load(fromByteOffset: eocdOffset + 10, as: UInt16.self))

            var offset = cdOffset
            for _ in 0..<cdEntries {
                guard offset + 46 <= size else { break }
                let sig = base.load(fromByteOffset: offset, as: UInt32.self)
                guard sig == 0x02014b50 else { break }

                let method = base.load(fromByteOffset: offset + 10, as: UInt16.self)
                let compressedSize = Int(base.load(fromByteOffset: offset + 20, as: UInt32.self))
                let uncompressedSize = Int(base.load(fromByteOffset: offset + 24, as: UInt32.self))
                let nameLen = Int(base.load(fromByteOffset: offset + 28, as: UInt16.self))
                let extraLen = Int(base.load(fromByteOffset: offset + 30, as: UInt16.self))
                let commentLen = Int(base.load(fromByteOffset: offset + 32, as: UInt16.self))
                let localHeaderOffset = Int(base.load(fromByteOffset: offset + 42, as: UInt32.self))

                let nameData = Data(bytes: base + offset + 46, count: nameLen)
                let name = String(data: nameData, encoding: .utf8) ?? ""

                // Skip to next central directory entry
                offset += 46 + nameLen + extraLen + commentLen

                // Read from local file header
                guard localHeaderOffset + 30 <= size else { continue }
                let localNameLen = Int(base.load(fromByteOffset: localHeaderOffset + 26, as: UInt16.self))
                let localExtraLen = Int(base.load(fromByteOffset: localHeaderOffset + 28, as: UInt16.self))
                let dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen

                let fileURL = destination.appendingPathComponent(name)

                if name.hasSuffix("/") {
                    // Directory
                    try fm.createDirectory(at: fileURL, withIntermediateDirectories: true)
                } else {
                    // File
                    try fm.createDirectory(at: fileURL.deletingLastPathComponent(),
                                           withIntermediateDirectories: true)

                    if method == 0 {
                        // Stored (no compression)
                        let fileData = Data(bytes: base + dataStart, count: uncompressedSize)
                        try fileData.write(to: fileURL)
                    } else if method == 8 {
                        // Deflate
                        let compressedData = Data(bytes: base + dataStart, count: compressedSize)
                        let decompressed = try decompressDeflate(compressedData, expectedSize: uncompressedSize)
                        try decompressed.write(to: fileURL)
                    } else {
                        logger.warning("Unsupported compression method \(method) for \(name)")
                    }
                }
            }
        }
    }

    private func decompressDeflate(_ data: Data, expectedSize: Int) throws -> Data {
        // Use Compression framework for raw deflate
        let destinationBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: max(expectedSize, 1))
        defer { destinationBuffer.deallocate() }

        let decompressedSize = data.withUnsafeBytes { (srcBuffer: UnsafeRawBufferPointer) -> Int in
            guard let src = srcBuffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
            return compression_decode_buffer(
                destinationBuffer, expectedSize,
                src, data.count,
                nil,
                COMPRESSION_ZLIB
            )
        }

        guard decompressedSize > 0 else {
            throw BackupError.zipError("Decompression failed")
        }

        return Data(bytes: destinationBuffer, count: decompressedSize)
    }

    // MARK: - List Backups

    @discardableResult
    func listBackups() async -> [BackupEntry] {
        guard let root = backupsRootURL else {
            availableBackups = []
            return []
        }

        var entries: [BackupEntry] = []

        do {
            // Ensure directory exists
            if !fm.fileExists(atPath: root.path) {
                try? fm.createDirectory(at: root, withIntermediateDirectories: true)
            }

            let deviceDirs = try fm.contentsOfDirectory(at: root,
                                                         includingPropertiesForKeys: [.isDirectoryKey])
            for deviceDir in deviceDirs {
                let isDir = (try? deviceDir.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
                guard isDir else { continue }

                let deviceName = deviceDir.lastPathComponent
                let files = (try? fm.contentsOfDirectory(at: deviceDir,
                                                          includingPropertiesForKeys: [.fileSizeKey])) ?? []

                for file in files {
                    guard file.pathExtension == "zip" else { continue }
                    let name = file.deletingPathExtension().lastPathComponent

                    guard let entry = parseBackupFileName(name, deviceName: deviceName, url: file) else {
                        continue
                    }
                    entries.append(entry)
                }
            }
        } catch {
            logger.error("Failed to list backups: \(error.localizedDescription)")
        }

        entries.sort { $0.date > $1.date }
        availableBackups = entries
        return entries
    }

    private func parseBackupFileName(_ name: String, deviceName: String, url: URL) -> BackupEntry? {
        // Format: <category>_<yyyy-MM-dd_HHmmss>
        let parts = name.split(separator: "_", maxSplits: 1)
        guard parts.count == 2 else { return nil }

        let categoryStr = String(parts[0])
        let dateStr = String(parts[1])

        let category: BackupCategory
        switch categoryStr {
        case "sessions": category = .sessions
        case "skills-memories": category = .skillsAndMemories
        case "full": category = .full
        default: return nil
        }

        // Re-parse: prefix may contain hyphen (skills-memories)
        let actualDateStr: String
        if categoryStr == "skills-memories" {
            // name = "skills-memories_2026-03-05_143022"
            let underscoreParts = name.components(separatedBy: "_")
            // ["skills-memories", "2026-03-05", "143022"]
            guard underscoreParts.count >= 3 else { return nil }
            actualDateStr = underscoreParts[1] + "_" + underscoreParts[2]
        } else {
            actualDateStr = dateStr
        }

        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HHmmss"
        guard let date = formatter.date(from: actualDateStr) else { return nil }

        let fileSize = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).flatMap { Int64($0) } ?? 0

        return BackupEntry(id: name, deviceName: deviceName, category: category,
                          date: date, fileSize: fileSize, url: url)
    }

    // MARK: - Restore

    func restore(from entry: BackupEntry) async throws {
        guard !isRestoring else { return }

        isRestoring = true
        progress = 0
        statusMessage = "Downloading backup..."
        defer {
            isRestoring = false
            statusMessage = ""
        }

        // Ensure file is downloaded from iCloud
        try await ensureDownloaded(entry.url)

        progress = 0.3
        statusMessage = "Extracting backup..."

        // Extract to temp directory
        let tempDir = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try fm.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: tempDir) }

        let extractDir = tempDir.appendingPathComponent("extracted", isDirectory: true)
        try await unzip(from: entry.url, to: extractDir)

        // The ZIP may contain a "backup" subdirectory from our staging
        let contentDir: URL
        let backupSubdir = extractDir.appendingPathComponent("backup", isDirectory: true)
        if fm.fileExists(atPath: backupSubdir.path) {
            contentDir = backupSubdir
        } else {
            contentDir = extractDir
        }

        progress = 0.5
        statusMessage = "Restoring data..."

        // Restore based on category
        switch entry.category {
        case .sessions:
            try await restoreSessionFiles(from: contentDir)
        case .skillsAndMemories:
            try await restoreSkillsAndMemoryFiles(from: contentDir)
        case .full:
            try await restoreSessionFiles(from: contentDir)
            try await restoreSkillsAndMemoryFiles(from: contentDir)
        }

        progress = 0.9
        statusMessage = "Reloading..."

        // Reload stores
        await ChatStore.shared.reloadDatabase()
        await SkillStore.shared.reloadDatabase()

        progress = 1.0
        statusMessage = "Restore complete"
        logger.info("Restored backup: \(entry.id)")
    }

    private func restoreSessionFiles(from dir: URL) async throws {
        let dbSrc = dir.appendingPathComponent("minis.db")
        if fm.fileExists(atPath: dbSrc.path) {
            // Close existing DB connection before replacing
            await ChatStore.shared.closeDatabase()

            if fm.fileExists(atPath: minisDBURL.path) {
                try fm.removeItem(at: minisDBURL)
            }
            // Also remove WAL and SHM files
            let walPath = minisDBURL.path + "-wal"
            let shmPath = minisDBURL.path + "-shm"
            try? fm.removeItem(atPath: walPath)
            try? fm.removeItem(atPath: shmPath)

            try fm.copyItem(at: dbSrc, to: minisDBURL)
        }

        let mediaSrc = dir.appendingPathComponent("media")
        if fm.fileExists(atPath: mediaSrc.path) {
            if fm.fileExists(atPath: mediaURL.path) {
                try fm.removeItem(at: mediaURL)
            }
            try fm.copyItem(at: mediaSrc, to: mediaURL)
        }

        let minisSrc = dir.appendingPathComponent("minis")
        if fm.fileExists(atPath: minisSrc.path) {
            if fm.fileExists(atPath: sessionMinisURL.path) {
                try fm.removeItem(at: sessionMinisURL)
            }
            try fm.copyItem(at: minisSrc, to: sessionMinisURL)
        }
    }

    private func restoreSkillsAndMemoryFiles(from dir: URL) async throws {
        let dbSrc = dir.appendingPathComponent("skills.db")
        if fm.fileExists(atPath: dbSrc.path) {
            await SkillStore.shared.closeDatabase()

            if fm.fileExists(atPath: skillsDBURL.path) {
                try fm.removeItem(at: skillsDBURL)
            }
            let walPath = skillsDBURL.path + "-wal"
            let shmPath = skillsDBURL.path + "-shm"
            try? fm.removeItem(atPath: walPath)
            try? fm.removeItem(atPath: shmPath)

            try fm.copyItem(at: dbSrc, to: skillsDBURL)
        }

        let skillsSrc = dir.appendingPathComponent("skills")
        if fm.fileExists(atPath: skillsSrc.path) {
            if fm.fileExists(atPath: skillsURL.path) {
                try fm.removeItem(at: skillsURL)
            }
            try fm.copyItem(at: skillsSrc, to: skillsURL)
        }

        let memorySrc = dir.appendingPathComponent("memory")
        if fm.fileExists(atPath: memorySrc.path) {
            if fm.fileExists(atPath: memoryURL.path) {
                try fm.removeItem(at: memoryURL)
            }
            try fm.copyItem(at: memorySrc, to: memoryURL)
        }
    }

    // MARK: - Download from iCloud

    private func ensureDownloaded(_ url: URL) async throws {
        let values = try url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey])
        if values.ubiquitousItemDownloadingStatus == .current {
            return
        }

        try fm.startDownloadingUbiquitousItem(at: url)

        // Poll until downloaded (with timeout)
        let deadline = Date().addingTimeInterval(120) // 2 min timeout
        while Date() < deadline {
            try await Task.sleep(nanoseconds: 500_000_000) // 0.5s
            let updated = try url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey])
            if updated.ubiquitousItemDownloadingStatus == .current {
                return
            }
        }

        throw BackupError.downloadTimeout
    }

    // MARK: - Delete Backup

    func deleteBackup(_ entry: BackupEntry) throws {
        try fm.removeItem(at: entry.url)
        availableBackups.removeAll { $0.id == entry.id }
    }

    // MARK: - Errors

    enum BackupError: LocalizedError {
        case iCloudUnavailable
        case databaseError(String)
        case zipError(String)
        case downloadTimeout

        var errorDescription: String? {
            switch self {
            case .iCloudUnavailable: return "iCloud is not available. Please sign in to iCloud in Settings."
            case .databaseError(let msg): return "Database error: \(msg)"
            case .zipError(let msg): return "Archive error: \(msg)"
            case .downloadTimeout: return "Timed out downloading backup from iCloud."
            }
        }
    }
}

// MARK: - Helpers

extension Int64 {
    var formattedFileSize: String {
        ByteCountFormatter.string(fromByteCount: self, countStyle: .file)
    }
}
#endif
