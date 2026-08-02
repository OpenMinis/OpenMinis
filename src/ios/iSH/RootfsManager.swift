//
//  RootfsManager.swift
//  MinisApp
//
//  Manages Alpine Linux rootfs installation and paths
//

import Foundation
import Compression
import SQLite3

class RootfsManager {
    static let shared = RootfsManager()
    private let logger = AppLogger(category: "RootfsManager")

    /// [T-mcp-cli-help-and-readonly-ios] Guest path of the bundled minis-mcp-cli
    /// Python lib. Its files are mounted read-only (see applyDefaultMountOverlay).
    private static let mcpCliLibDir = "/usr/local/lib/minis-mcp-cli"
    private static let mcpCliLibPrefix = "/usr/local/lib/minis-mcp-cli/"

    /// [T-rootfs-reset-terminal-crash] Set once `reset()` deletes the rootfs
    /// directory from disk. The `ISHKernel` singleton boots exactly once per
    /// process and irreversibly becomes PID 1 via `become_first_process()`, so
    /// after a reset it is still `isBooted == true` but its fakefs is mounted
    /// against a `data/`/`meta.db` tree that no longer exists on disk. Reusing
    /// that stale mount (skipping install+boot because `isBooted` is true)
    /// crashes on the next `executeCommand`. Once this flag is set, the terminal
    /// must refuse to start a shell until the app is relaunched — there is no
    /// in-process way to un-boot or re-mount the kernel. Never cleared within a
    /// process lifetime; it is reset naturally on the next cold launch.
    private(set) var didResetWhileBooted = false

    private init() {}

    // MARK: - Multi-rootfs profile model
    //
    // The rootfs is stored as a set of sibling "profiles" under Documents/.
    // Each profile is a complete fakefs tree: a `data/` directory of real
    // files plus a `meta.db` SQLite metadata database. The bundled Alpine
    // profile is always named "alpine-rootfs" (created from the bundled
    // .zip); additional profiles are created by importing tar.gz archives.
    // Exactly one profile is "active" and is mounted by ISHKernel at boot.

    /// Key in UserDefaults holding the currently active profile directory name.
    private static let activeProfileKey = "rootfs.activeProfile"
    private static let bundledProfileName = "alpine-rootfs"

    /// Directory under Documents containing all rootfs profiles.
    private var profilesRoot: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// The directory name of the active profile.
    private var activeProfileName: String {
        get {
            if let stored = UserDefaults.standard.string(forKey: Self.activeProfileKey),
               !stored.isEmpty,
               FileManager.default.fileExists(atPath: profilesRoot.appendingPathComponent(stored).path) {
                return stored
            }
            return Self.bundledProfileName
        }
        set { UserDefaults.standard.set(newValue, forKey: Self.activeProfileKey) }
    }

    /// Root path of the active profile (e.g. Documents/alpine-rootfs).
    var rootfsPath: URL {
        return profilesRoot.appendingPathComponent(activeProfileName)
    }

    /// The fakefs real-files directory of the active profile.
    var dataPath: URL {
        return rootfsPath.appendingPathComponent("data")
    }

    /// Names of every installed rootfs profile.
    var installedProfiles: [String] {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: profilesRoot.path) else { return [] }
        return names.compactMap { name in
            let dir = profilesRoot.appendingPathComponent(name)
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: dir.path, isDirectory: &isDir), isDir.boolValue else { return nil }
            // A profile is a directory that contains a valid meta.db + data/.
            guard fm.fileExists(atPath: dir.appendingPathComponent("meta.db").path),
                  fm.fileExists(atPath: dir.appendingPathComponent("data").path) else { return nil }
            return name
        }.sorted()
    }

    /// Currently active profile name.
    var activeProfile: String { activeProfileName }

    /// Switch which profile is active. If the kernel is already booted, the
    /// change only takes effect on the next cold launch.
    func setActiveProfile(_ name: String) -> Bool {
        let profiles = installedProfiles
        guard profiles.contains(name) else { return false }
        activeProfileName = name
        if ISHKernel.shared.isBooted {
            logger.warning("Rootfs profile switch to '\(name)' requires app relaunch to take effect")
            didResetWhileBooted = true
        }
        return true
    }

    /// Current rootfs architecture tag — change this when switching guest arch
    private let currentArch = "aarch64"

    /// Path of the bundled Alpine profile (always alpine-rootfs). Installed
    /// once from the bundled zip; import tar.gz creates sibling profiles.
    private var bundledRootfsPath: URL {
        return profilesRoot.appendingPathComponent(Self.bundledProfileName)
    }

    private func archTagPath(for root: URL) -> URL {
        return root.appendingPathComponent(".arch")
    }

    /// True if the active profile is installed and bootable.
    var isInstalled: Bool {
        return isRootfsInstalled(at: rootfsPath)
    }

    private func isRootfsInstalled(at root: URL) -> Bool {
        let fm = FileManager.default
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: root.appendingPathComponent("data").path, isDirectory: &isDir),
              isDir.boolValue else { return false }
        guard fm.fileExists(atPath: root.appendingPathComponent("meta.db").path) else { return false }
        guard let tag = try? String(contentsOf: archTagPath(for: root), encoding: .utf8) else { return false }
        return tag.trimmingCharacters(in: .whitespacesAndNewlines) == currentArch
    }

    func installIfNeeded() throws {
        let root = bundledRootfsPath
        // If an imported profile is active, the bundled install path is
        // independent of it; only install if the bundled Alpine is missing.
        if FileManager.default.fileExists(atPath: root.path) && !isRootfsInstalled(at: root) {
            print("RootfsManager: Arch mismatch or missing tag, reinstalling \(currentArch) rootfs...")
            try FileManager.default.removeItem(at: root)
        }

        guard !isRootfsInstalled(at: root) else {
            print("RootfsManager: Already installed (\(currentArch)) at \(root.path)")
            return
        }

        guard let zipURL = Bundle.main.url(forResource: "alpine-rootfs", withExtension: "zip") else {
            throw NSError(
                domain: "RootfsManager",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "alpine-rootfs.zip not found in bundle"]
            )
        }

        print("RootfsManager: Installing rootfs from \(zipURL.path)...")

        // Create destination directory
        try FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true,
            attributes: nil
        )

        // Unzip using custom ZIP reader
        try unzipFile(at: zipURL, to: root)

        // Write architecture tag so we detect mismatches on future launches
        try currentArch.write(to: archTagPath(for: root), atomically: true, encoding: .utf8)

        // Pre-create /var/minis/ shared directory structure
        let minisSubdirs = ["var/minis/attachments", "var/minis/offloads", "var/minis/workspace", "var/minis/skills", "var/minis/shared"]
        for subdir in minisSubdirs {
            let dirPath = dataPath.appendingPathComponent(subdir)
            try? FileManager.default.createDirectory(at: dirPath, withIntermediateDirectories: true)
        }

        // Flag fresh install so first-boot tasks (e.g. mirror auto-detect) can trigger.
        UserDefaults.standard.set(true, forKey: "rootfs.freshInstall")

        print("RootfsManager: Installation complete (\(currentArch)) at \(rootfsPath.path)")
    }

    // MARK: - tar.gz mini-rootfs import

    /// Human-readable errors surfaced by the import flow.
    enum ImportError: LocalizedError {
        case invalidArchive(String)
        case noBootableRootfs
        case io(String)

        var errorDescription: String? {
            switch self {
            case .invalidArchive(let m): return "Could not read archive: \(m)"
            case .noBootableRootfs: return "The archive does not appear to be a bootable Linux rootfs (missing /bin/sh or /etc)."
            case .io(let m): return m
            }
        }
    }

    /// Decompress + extract a user-supplied mini-rootfs tarball and register
    /// it as a new rootfs profile.
    /// - Parameters:
    ///   - sourceURL: The .tar.gz / .tgz / .tar file the user picked.
    ///   - displayName: A human label for the new profile.
    ///   - activate: If true, make the imported rootfs the active profile.
    /// - Returns: The new profile name on success.
    @discardableResult
    func importFromTarGz(sourceURL: URL, displayName: String, activate: Bool = false) throws -> String {
        let fm = FileManager.default

        let baseName = sanitizedProfileName(displayName)
        var profileName = baseName
        var n = 2
        while fm.fileExists(atPath: profilesRoot.appendingPathComponent(profileName).path) {
            profileName = "\(baseName)-\(n)"
            n += 1
        }

        let profileRoot = profilesRoot.appendingPathComponent(profileName)
        let dataDir = profileRoot.appendingPathComponent("data")

        let decompressed = try RootfsArchiveDecompressor.decompress(url: sourceURL)
        let tarData = decompressed.data

        do {
            try fm.createDirectory(at: dataDir, withIntermediateDirectories: true, attributes: nil)
            try initializeFakefsDatabase(at: profileRoot)
            try extractTarIntoDataDir(tarData: tarData, dataDir: dataDir, databaseRoot: profileRoot)
        } catch {
            try? fm.removeItem(at: profileRoot)
            throw error
        }

        try currentArch.write(to: archTagPath(for: profileRoot), atomically: true, encoding: .utf8)

        guard isRootfsInstalled(at: profileRoot) else {
            try? fm.removeItem(at: profileRoot)
            throw ImportError.noBootableRootfs
        }

        finalizeImportedRootfs(at: dataDir)
        print("RootfsManager: Imported rootfs profile '\(profileName)' from \(sourceURL.lastPathComponent)")

        if activate {
            _ = setActiveProfile(profileName)
        }
        return profileName
    }

    /// Create a fresh fakefs SQLite database (schema) for a profile root.
    private func initializeFakefsDatabase(at profileRoot: URL) throws {
        let dbPath = profileRoot.appendingPathComponent("meta.db").path
        var db: OpaquePointer?
        guard sqlite3_open_v2(dbPath, &db, SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE, nil) == SQLITE_OK,
              let db else {
            throw ImportError.io("Could not create fakefs database.")
        }
        defer { sqlite3_close(db) }

        // The fakefs meta.db schema is authoritative — it must match the one
        // the iSH kernel's fake_db_init expects. Reproduce it exactly
        // (see tools/fakefs.c `schema` in the pinned deps/ish submodule).
        let schema = [
            "create table meta (id integer unique default 0, db_inode integer);",
            "insert into meta (db_inode) values (0);",
            "create table stats (inode integer primary key, stat blob);",
            "create table paths (path blob primary key, inode integer references stats(inode));",
            "create index inode_to_path on paths (inode, path);",
            "pragma user_version=3;"
        ]
        for stmt in schema {
            if sqlite3_exec(db, stmt, nil, nil, nil) != SQLITE_OK {
                throw ImportError.io("Failed to initialize fakefs schema.")
            }
        }
    }

    /// Stream-extract the tar bytes into `dataDir`, registering every node in
    /// the profile's `meta.db`. Preserves modes and symlinks.
    private func extractTarIntoDataDir(tarData: Data, dataDir: URL, databaseRoot: URL) throws {
        let fm = FileManager.default
        let stream = try TarStream(data: tarData)
        var seenDirs = Set<String>()

        func ensureDir(_ rel: String) throws {
            guard !rel.isEmpty, rel != "/" else { return }
            var current = (rel as NSString).deletingLastPathComponent
            while !current.isEmpty && current != "/" && !seenDirs.contains(current) {
                try registerMeta(at: current, isDirectory: true, for: databaseRoot)
                seenDirs.insert(current)
                current = (current as NSString).deletingLastPathComponent
            }
            if !seenDirs.contains(rel) {
                try registerMeta(at: rel, isDirectory: true, for: databaseRoot)
                seenDirs.insert(rel)
            }
        }

        while let entry = stream.nextEntry() {
            let rel = entry.normalizedPath
            guard !rel.isEmpty else { continue }

            if entry.type == .directory {
                try ensureDir(rel)
                try fm.createDirectory(at: dataDir.appendingPathComponent(rel), withIntermediateDirectories: true)
                continue
            }
            if entry.type == .symlink {
                try ensureDir(rel)
                let host = dataDir.appendingPathComponent(rel)
                let parent = host.deletingLastPathComponent()
                try fm.createDirectory(at: parent, withIntermediateDirectories: true)
                _ = try? fm.removeItem(at: host)
                try fm.createSymbolicLink(at: host, withDestinationURL: URL(fileURLWithPath: entry.linkTarget))
                try registerMeta(at: rel, isDirectory: false,
                                 explicitMode: UInt32(0o120000) | UInt32(entry.mode & 0o7777),
                                 for: databaseRoot)
                continue
            }
            if entry.type == .hardlink {
                try ensureDir(rel)
                let host = dataDir.appendingPathComponent(rel)
                let parent = host.deletingLastPathComponent()
                try fm.createDirectory(at: parent, withIntermediateDirectories: true)
                let reference = dataDir.appendingPathComponent(entry.linkTarget)
                if fm.fileExists(atPath: reference.path) {
                    _ = try? fm.removeItem(at: host)
                    _ = try? fm.copyItem(at: reference, to: host)
                } else {
                    try Data().write(to: host)
                }
                try registerMeta(at: rel, isDirectory: false,
                                 explicitMode: UInt32(0o100000) | UInt32(entry.mode & 0o7777),
                                 for: databaseRoot)
                continue
            }
            if entry.type == .regular {
                try ensureDir(rel)
                let host = dataDir.appendingPathComponent(rel)
                let parent = host.deletingLastPathComponent()
                try fm.createDirectory(at: parent, withIntermediateDirectories: true)
                try entry.payload.write(to: host)
                let perms = entry.mode & 0o777
                let iosMode = perms > 0 ? perms : 0o644
                try? fm.setAttributes([.posixPermissions: iosMode], ofItemAtPath: host.path)
                try registerMeta(at: rel, isDirectory: false,
                                 explicitMode: UInt32(0o100000) | UInt32(entry.mode & 0o7777),
                                 for: databaseRoot)
                continue
            }
        }
    }

    /// Register one path's metadata in a profile's fakefs DB.
    private func registerMeta(at linuxPath: String,
                              isDirectory: Bool,
                              explicitMode: UInt32? = nil,
                              for profileRoot: URL) throws {
        let mode: UInt32 = explicitMode ?? (isDirectory ? 0o040755 : 0o100644)
        var statBytes = [UInt8](repeating: 0, count: 16)
        let leMode = mode.littleEndian
        withUnsafeBytes(of: leMode) { src in
            for i in 0..<4 { statBytes[i] = src[i] }
        }

        let dbPath = profileRoot.appendingPathComponent("meta.db").path
        var db: OpaquePointer?
        guard sqlite3_open_v2(dbPath, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK, let db else {
            throw ImportError.io("Could not open fakefs database for registration.")
        }
        defer { sqlite3_close(db) }

        _ = sqlite3_exec(db, "BEGIN TRANSACTION;", nil, nil, nil)
        var insStat: OpaquePointer?
        if sqlite3_prepare_v2(db, "INSERT INTO stats (stat) VALUES (?)", -1, &insStat, nil) == SQLITE_OK,
           let insStat {
            sqlite3_bind_blob(insStat, 1, statBytes, Int32(statBytes.count), Self.SQLITE_TRANSIENT)
            sqlite3_step(insStat)
            let inode = sqlite3_last_insert_rowid(db)
            sqlite3_finalize(insStat)

            var insPath: OpaquePointer?
            if sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO paths (path, inode) VALUES (?, ?)", -1, &insPath, nil) == SQLITE_OK,
               let insPath {
                bindPathBlob(insPath, index: 1, path: linuxPath)
                sqlite3_bind_int64(insPath, 2, inode)
                sqlite3_step(insPath)
                sqlite3_finalize(insPath)
            }
        }
        _ = sqlite3_exec(db, "COMMIT;", nil, nil, nil)
    }

    /// Post-import fixtures so the imported rootfs works with the Minis runtime.
    private func finalizeImportedRootfs(at dataDir: URL) {
        let fm = FileManager.default
        for rel in ["var/minis/attachments", "var/minis/offloads", "var/minis/workspace",
                    "var/minis/skills", "var/minis/shared"] {
            try? fm.createDirectory(at: dataDir.appendingPathComponent(rel),
                                    withIntermediateDirectories: true, attributes: nil)
        }
    }

    /// Sanitize a user-supplied name into a safe profile directory name.
    private func sanitizedProfileName(_ name: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let cleaned = name.unicodeScalars.map { allowed.contains($0) ? String($0) : "_" }.joined()
        let trimmed = cleaned.trimmingCharacters(in: CharacterSet(charactersIn: "_-"))
        if trimmed.isEmpty { return "imported" }
        return trimmed.hasPrefix("alpine-rootfs") ? "\(trimmed)-imported" : trimmed
    }

    /// Delete an installed profile (never the active or bundled one).
    func deleteProfile(_ name: String) throws {
        guard name != Self.bundledProfileName, name != activeProfileName else {
            throw ImportError.io("Cannot delete the active or bundled profile.")
        }
        let dir = profilesRoot.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: dir.path) {
            try FileManager.default.removeItem(at: dir)
        }
    }

    /// Reset (delete) the rootfs, forcing a fresh install on next launch
    /// - Parameter keepUserData: If true, backs up /root directory before deletion
    /// - Returns: URL of backup directory if keepUserData was true, nil otherwise
    @discardableResult
    func reset(keepUserData: Bool = false) throws -> URL? {
        print("RootfsManager: Resetting rootfs...")

        var backupURL: URL?

        if keepUserData {
            // Backup /root directory
            let userDataPath = dataPath.appendingPathComponent("root")
            if FileManager.default.fileExists(atPath: userDataPath.path) {
                let backupPath = FileManager.default.temporaryDirectory
                    .appendingPathComponent("rootfs-backup-\(Date().timeIntervalSince1970)")
                    .appendingPathComponent("root")

                try FileManager.default.createDirectory(
                    at: backupPath.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )

                try FileManager.default.copyItem(at: userDataPath, to: backupPath)
                backupURL = backupPath
                print("RootfsManager: User data backed up to \(backupPath.path)")
            }
        }

        // Delete rootfs
        if FileManager.default.fileExists(atPath: rootfsPath.path) {
            try FileManager.default.removeItem(at: rootfsPath)
            print("RootfsManager: Rootfs deleted from \(rootfsPath.path)")
        }

        // [T-rootfs-reset-terminal-crash] If the kernel already booted this
        // session, its fakefs is now mounted against a deleted tree. The kernel
        // cannot be re-mounted or un-booted in-process, so mark the session
        // poisoned; the terminal start path checks this and refuses to reuse the
        // stale mount (which would crash) until the app is relaunched.
        if ISHKernel.shared.isBooted {
            didResetWhileBooted = true
            logger.warning("Rootfs reset while kernel booted; terminal disabled until app relaunch")
        }

        print("RootfsManager: Reset complete. Run installIfNeeded() to reinstall.")

        return backupURL
    }

    /// Restore user data from a backup
    /// - Parameter backupURL: URL of backup created by reset(keepUserData: true)
    func restoreUserData(from backupURL: URL) throws {
        guard isInstalled else {
            throw NSError(
                domain: "RootfsManager",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: "Rootfs not installed. Run installIfNeeded() first."]
            )
        }

        let userDataPath = dataPath.appendingPathComponent("root")

        // Remove existing /root directory
        if FileManager.default.fileExists(atPath: userDataPath.path) {
            try FileManager.default.removeItem(at: userDataPath)
        }

        // Copy backup to rootfs
        try FileManager.default.copyItem(at: backupURL, to: userDataPath)

        print("RootfsManager: User data restored from \(backupURL.path)")
    }

    /// Overlay default configuration files from the bundle onto the rootfs.
    /// Runs on every boot so app updates with new defaults take effect.
    func applyDefaultMountOverlay() {
        let totalStart = CFAbsoluteTimeGetCurrent()
        logger.info("[DefaultMount] applyDefaultMountOverlay() called")

        guard let overlayURL = Bundle.main.url(forResource: "default_mount", withExtension: nil) else {
            logger.info("[DefaultMount] No default_mount found in bundle")
            return
        }

        let fm = FileManager.default
        guard let enumerator = fm.enumerator(at: overlayURL, includingPropertiesForKeys: [.isRegularFileKey]) else {
            return
        }

        var fileCount = 0
        var copyTotalMs: Double = 0
        var metaDbTotalMs: Double = 0
        while let fileURL = enumerator.nextObject() as? URL {
            guard let values = try? fileURL.resourceValues(forKeys: [.isRegularFileKey]),
                  values.isRegularFile == true else { continue }

            // relativePath e.g. "/etc/pip/pip.conf"
            let relativePath = fileURL.path.replacingOccurrences(of: overlayURL.path, with: "")
            let destURL = dataPath.appendingPathComponent(relativePath)

            do {
                let copyStart = CFAbsoluteTimeGetCurrent()
                try fm.createDirectory(at: destURL.deletingLastPathComponent(), withIntermediateDirectories: true)
                if fm.fileExists(atPath: destURL.path) {
                    try fm.removeItem(at: destURL)
                }
                try fm.copyItem(at: fileURL, to: destURL)
                copyTotalMs += (CFAbsoluteTimeGetCurrent() - copyStart) * 1000
                fileCount += 1

                // Determine the fakefs mode. iOS strips POSIX execute bits
                // from some bundle resources during install, so reading
                // `attributesOfItem` is unreliable for the +x case. Fall
                // back to a path-based heuristic: anything shipped under
                // a standard *bin directory is forced to 0755 so scripts
                // like /usr/local/bin/minis-open are always executable.
                var posixMode: UInt16 = 0o644
                if let attrs = try? fm.attributesOfItem(atPath: fileURL.path),
                   let num = attrs[.posixPermissions] as? NSNumber {
                    posixMode = num.uint16Value
                }
                let binDirs = ["/bin/", "/sbin/", "/usr/bin/", "/usr/sbin/", "/usr/local/bin/", "/usr/local/sbin/"]
                if binDirs.contains(where: { relativePath.hasPrefix($0) }) {
                    posixMode = 0o755
                }
                // [T-mcp-cli-help-and-readonly-ios] The bundled minis-mcp-cli
                // Python lib is app-managed and shipped read-only so users
                // cannot tamper with it from inside iSH (e.g. `vi`). Register
                // these regular files as 0o444 (read-only) — iSH's access_check
                // then rejects writes in-guest. The host copy is unaffected, so
                // the every-boot overwrite still updates them on app upgrade.
                // Scoped to this lib subtree ONLY; the sh wrapper under
                // /usr/local/bin/ stays 0o755 (handled by binDirs above).
                if relativePath.hasPrefix(Self.mcpCliLibPrefix) {
                    posixMode = 0o444
                }
                let fakefsMode: UInt32 = 0o100000 | UInt32(posixMode)

                // Register in fakefs meta.db so iSH kernel can see the file
                let metaStart = CFAbsoluteTimeGetCurrent()
                ensureParentDirsInMetaDB(for: relativePath)
                ensureFakefsMetadata(for: relativePath, isDirectory: false, mode: fakefsMode)
                metaDbTotalMs += (CFAbsoluteTimeGetCurrent() - metaStart) * 1000
            } catch {
                logger.error("[DefaultMount] ERROR \(relativePath): \(error)")
            }
        }

        // [T-mcp-cli-help-and-readonly-ios] The per-file loop above stamped the
        // minis-mcp-cli lib FILES read-only (0o444), but ensureParentDirsInMetaDB
        // creates the containing directories with the default writable mode
        // (0o040755) — which would still let the guest create/delete files
        // inside them. Re-register the lib directories as 0o555 (read+execute,
        // no write) so the whole subtree is read-only to the guest. Idempotent:
        // ensureFakefsMetadata updates the stat row in place each boot.
        for dir in [Self.mcpCliLibDir, "\(Self.mcpCliLibDir)/transport", "\(Self.mcpCliLibDir)/utils"] {
            ensureFakefsMetadata(for: dir, isDirectory: true, mode: 0o040555)
        }

        // Remove PEP 668 EXTERNALLY-MANAGED marker so pip install works without
        // --break-system-packages. Safe in this embedded Alpine rootfs.
        let markerStart = CFAbsoluteTimeGetCurrent()
        removeExternallyManagedMarker()
        let markerMs = (CFAbsoluteTimeGetCurrent() - markerStart) * 1000

        let totalMs = (CFAbsoluteTimeGetCurrent() - totalStart) * 1000
        let avgCopyMs = fileCount > 0 ? copyTotalMs / Double(fileCount) : 0
        let avgMetaMs = fileCount > 0 ? metaDbTotalMs / Double(fileCount) : 0
        logger.info("[DefaultMount] Done. \(fileCount) file(s) overlaid in \(String(format: "%.1f", totalMs))ms — copy: \(String(format: "%.1f", copyTotalMs))ms (avg \(String(format: "%.2f", avgCopyMs))ms/file), metaDB: \(String(format: "%.1f", metaDbTotalMs))ms (avg \(String(format: "%.2f", avgMetaMs))ms/file), marker: \(String(format: "%.1f", markerMs))ms")
    }

    /// Remove the PEP 668 EXTERNALLY-MANAGED marker file from all Python versions.
    private func removeExternallyManagedMarker() {
        let fm = FileManager.default
        let usrLib = dataPath.appendingPathComponent("usr/lib")
        guard let contents = try? fm.contentsOfDirectory(atPath: usrLib.path) else { return }
        for entry in contents where entry.hasPrefix("python3") {
            let marker = usrLib.appendingPathComponent(entry).appendingPathComponent("EXTERNALLY-MANAGED")
            if fm.fileExists(atPath: marker.path) {
                try? fm.removeItem(at: marker)
                print("[DefaultMount] Removed EXTERNALLY-MANAGED from \(entry)")
            }
        }
    }

    // MARK: - Fakefs meta.db helpers

    private static let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private func bindPathBlob(_ stmt: OpaquePointer, index: Int32, path: String) {
        let utf8 = Array(path.utf8)
        sqlite3_bind_blob(stmt, index, utf8, Int32(utf8.count), Self.SQLITE_TRANSIENT)
    }

    func ensureFakefsMetadata(for linuxPath: String, isDirectory: Bool, mode: UInt32? = nil) {
        let metaDBPath = rootfsPath.appendingPathComponent("meta.db").path
        var db: OpaquePointer?
        guard sqlite3_open_v2(metaDBPath, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK,
              let db else { return }
        defer { sqlite3_close(db) }

        let effectiveMode: UInt32 = mode ?? (isDirectory ? 0o040755 : 0o100644)
        var statBytes: [UInt8] = Array(repeating: 0, count: 16)
        let leMode = effectiveMode.littleEndian
        withUnsafeBytes(of: leMode) { src in
            for i in 0..<4 { statBytes[i] = src[i] }
        }

        // If the path already exists, update its stat row in place so we can
        // refresh mode bits (e.g. when a newer app version ships an updated
        // default_mount file with different permissions).
        var checkStmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT inode FROM paths WHERE path = ?", -1, &checkStmt, nil) == SQLITE_OK,
              let checkStmt else { return }
        defer { sqlite3_finalize(checkStmt) }

        bindPathBlob(checkStmt, index: 1, path: linuxPath)
        if sqlite3_step(checkStmt) == SQLITE_ROW {
            let existingInode = sqlite3_column_int64(checkStmt, 0)
            var updateStmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "UPDATE stats SET stat = ? WHERE inode = ?", -1, &updateStmt, nil) == SQLITE_OK,
                  let updateStmt else { return }
            defer { sqlite3_finalize(updateStmt) }
            sqlite3_bind_blob(updateStmt, 1, statBytes, Int32(statBytes.count), Self.SQLITE_TRANSIENT)
            sqlite3_bind_int64(updateStmt, 2, existingInode)
            sqlite3_step(updateStmt)
            return
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

    func ensureParentDirsInMetaDB(for linuxPath: String) {
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

    /// Remove a path (and, if it's a directory subtree, all of its descendants)
    /// from fakefs `meta.db`. Also drops orphaned `stats` rows. Safe to call on
    /// paths that don't exist in meta.db (it's a no-op in that case).
    func removeFakefsPath(_ linuxPath: String) {
        let metaDBPath = rootfsPath.appendingPathComponent("meta.db").path
        var db: OpaquePointer?
        guard sqlite3_open_v2(metaDBPath, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK,
              let db else { return }
        defer { sqlite3_close(db) }

        // Collect inodes of rows that will be deleted so we can drop their
        // stat rows too (meta.db has no FK cascade).
        var inodes: [Int64] = []
        let prefix = linuxPath.hasSuffix("/") ? linuxPath : linuxPath + "/"
        var selectStmt: OpaquePointer?
        if sqlite3_prepare_v2(db,
            "SELECT inode FROM paths WHERE path = ? OR substr(path, 1, ?) = ?",
            -1, &selectStmt, nil) == SQLITE_OK, let selectStmt {
            bindPathBlob(selectStmt, index: 1, path: linuxPath)
            sqlite3_bind_int(selectStmt, 2, Int32(Array(prefix.utf8).count))
            bindPathBlob(selectStmt, index: 3, path: prefix)
            while sqlite3_step(selectStmt) == SQLITE_ROW {
                inodes.append(sqlite3_column_int64(selectStmt, 0))
            }
            sqlite3_finalize(selectStmt)
        }
        if inodes.isEmpty { return }

        var deletePathsStmt: OpaquePointer?
        if sqlite3_prepare_v2(db,
            "DELETE FROM paths WHERE path = ? OR substr(path, 1, ?) = ?",
            -1, &deletePathsStmt, nil) == SQLITE_OK, let deletePathsStmt {
            bindPathBlob(deletePathsStmt, index: 1, path: linuxPath)
            sqlite3_bind_int(deletePathsStmt, 2, Int32(Array(prefix.utf8).count))
            bindPathBlob(deletePathsStmt, index: 3, path: prefix)
            sqlite3_step(deletePathsStmt)
            sqlite3_finalize(deletePathsStmt)
        }

        var deleteStatsStmt: OpaquePointer?
        if sqlite3_prepare_v2(db, "DELETE FROM stats WHERE inode = ?", -1, &deleteStatsStmt, nil) == SQLITE_OK,
           let deleteStatsStmt {
            for inode in inodes {
                sqlite3_reset(deleteStatsStmt)
                sqlite3_bind_int64(deleteStatsStmt, 1, inode)
                sqlite3_step(deleteStatsStmt)
            }
            sqlite3_finalize(deleteStatsStmt)
        }
    }

    /// Register every existing file/directory under `hostRoot` into `meta.db`,
    /// using Linux paths relative to `dataPath`. Used after copying a directory
    /// subtree from the host filesystem (e.g. FileBrowser import/copy/move)
    /// so that the iSH kernel can see the new entries.
    func registerSubtreeInMetaDB(hostRoot: URL) {
        let fm = FileManager.default
        let dataPrefix = dataPath.standardized.path
        let rootStd = hostRoot.standardized.path
        guard rootStd == dataPrefix || rootStd.hasPrefix(dataPrefix + "/") else { return }

        func linuxPath(for hostPath: String) -> String? {
            if hostPath == dataPrefix { return "/" }
            guard hostPath.hasPrefix(dataPrefix + "/") else { return nil }
            return String(hostPath.dropFirst(dataPrefix.count))
        }

        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: rootStd, isDirectory: &isDir) else { return }
        guard let rootLinux = linuxPath(for: rootStd) else { return }

        ensureParentDirsInMetaDB(for: rootLinux)
        ensureFakefsMetadata(for: rootLinux, isDirectory: isDir.boolValue)
        guard isDir.boolValue else { return }

        var stack: [URL] = [URL(fileURLWithPath: rootStd)]
        while let dir = stack.popLast() {
            guard let children = try? fm.contentsOfDirectory(at: dir,
                                                             includingPropertiesForKeys: [.isDirectoryKey],
                                                             options: []) else { continue }
            for child in children {
                let childStd = child.standardized.path
                guard let childLinux = linuxPath(for: childStd) else { continue }
                var childIsDir: ObjCBool = false
                fm.fileExists(atPath: childStd, isDirectory: &childIsDir)
                ensureFakefsMetadata(for: childLinux, isDirectory: childIsDir.boolValue)
                if childIsDir.boolValue {
                    stack.append(URL(fileURLWithPath: childStd))
                }
            }
        }
    }

    /// Get the size of the rootfs installation
    /// - Returns: Size in bytes
    func getRootfsSize() throws -> Int64 {
        guard FileManager.default.fileExists(atPath: rootfsPath.path) else { return 0 }

        var totalSize: Int64 = 0
        let enumerator = FileManager.default.enumerator(at: rootfsPath, includingPropertiesForKeys: [.fileSizeKey])

        while let fileURL = enumerator?.nextObject() as? URL {
            if let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
               let fileSize = attributes[.size] as? Int64 {
                totalSize += fileSize
            }
        }

        return totalSize
    }

    private func unzipFile(at sourceURL: URL, to destinationURL: URL) throws {
        let fileManager = FileManager.default

        guard let archive = ZIPArchive(url: sourceURL) else {
            throw NSError(
                domain: "RootfsManager",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "Failed to open zip archive"]
            )
        }

        // Find common prefix to strip (e.g., "alpine-rootfs/")
        let prefix = "alpine-rootfs/"

        for entry in archive.entries {
            var relativePath = entry.path

            // Strip the common prefix if present
            if relativePath.hasPrefix(prefix) {
                relativePath = String(relativePath.dropFirst(prefix.count))
            }

            // Skip empty paths (the root directory itself)
            guard !relativePath.isEmpty else { continue }

            let entryPath = destinationURL.appendingPathComponent(relativePath)

            // Create parent directories if needed
            let parentDir = entryPath.deletingLastPathComponent()
            if !fileManager.fileExists(atPath: parentDir.path) {
                try fileManager.createDirectory(at: parentDir, withIntermediateDirectories: true)
            }

            // Extract entry
            if entry.isDirectory {
                try fileManager.createDirectory(at: entryPath, withIntermediateDirectories: true)
            } else {
                if let data = try archive.extractData(for: entry) {
                    try data.write(to: entryPath)
                }
            }
        }
    }
}

// Minimal ZIP archive reader with safe unaligned memory access
private class ZIPArchive {
    struct Entry {
        let path: String
        let isDirectory: Bool
        let compressedSize: Int
        let uncompressedSize: Int
        let compressionMethod: UInt16
        let localHeaderOffset: UInt64
    }

    private let fileHandle: FileHandle
    private(set) var entries: [Entry] = []

    init?(url: URL) {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        self.fileHandle = handle

        do {
            try parseZipFile()
        } catch {
            print("ZIPArchive: Parse error: \(error)")
            return nil
        }
    }

    deinit {
        try? fileHandle.close()
    }

    // Safe unaligned read helpers
    private func readUInt16(_ data: Data, at offset: Int) -> UInt16 {
        return UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
    }

    private func readUInt32(_ data: Data, at offset: Int) -> UInt32 {
        return UInt32(data[offset]) |
               (UInt32(data[offset + 1]) << 8) |
               (UInt32(data[offset + 2]) << 16) |
               (UInt32(data[offset + 3]) << 24)
    }

    private func parseZipFile() throws {
        fileHandle.seekToEndOfFile()
        let fileSize = fileHandle.offsetInFile

        // Search for End of Central Directory
        let searchSize: UInt64 = Swift.min(fileSize, 65557)
        fileHandle.seek(toFileOffset: fileSize - searchSize)
        let searchData = fileHandle.readDataToEndOfFile()

        guard let eocdOffset = findEOCD(in: searchData) else {
            throw NSError(domain: "ZIPArchive", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid ZIP"])
        }

        let eocdStart = Int(fileSize - searchSize) + eocdOffset

        fileHandle.seek(toFileOffset: UInt64(eocdStart))
        let eocdData = fileHandle.readData(ofLength: 22)
        guard eocdData.count == 22 else { throw NSError(domain: "ZIPArchive", code: 2, userInfo: nil) }

        let centralDirOffset = readUInt32(eocdData, at: 16)
        let entryCount = readUInt16(eocdData, at: 10)

        fileHandle.seek(toFileOffset: UInt64(centralDirOffset))

        for _ in 0..<entryCount {
            guard let entry = readCentralDirectoryEntry() else { break }
            entries.append(entry)
        }
    }

    private func findEOCD(in data: Data) -> Int? {
        let signature: [UInt8] = [0x50, 0x4B, 0x05, 0x06]
        for i in stride(from: data.count - 22, through: 0, by: -1) {
            if data[i] == signature[0] && data[i+1] == signature[1] &&
               data[i+2] == signature[2] && data[i+3] == signature[3] {
                return i
            }
        }
        return nil
    }

    private func readCentralDirectoryEntry() -> Entry? {
        let headerData = fileHandle.readData(ofLength: 46)
        guard headerData.count == 46 else { return nil }

        let sig = readUInt32(headerData, at: 0)
        guard sig == 0x02014B50 else { return nil }

        let compressionMethod = readUInt16(headerData, at: 10)
        let compressedSize = readUInt32(headerData, at: 20)
        let uncompressedSize = readUInt32(headerData, at: 24)
        let fileNameLength = readUInt16(headerData, at: 28)
        let extraLength = readUInt16(headerData, at: 30)
        let commentLength = readUInt16(headerData, at: 32)
        let localHeaderOffset = readUInt32(headerData, at: 42)

        let fileNameData = fileHandle.readData(ofLength: Int(fileNameLength))
        let fileName = String(data: fileNameData, encoding: .utf8) ?? ""

        fileHandle.seek(toFileOffset: fileHandle.offsetInFile + UInt64(extraLength + commentLength))

        return Entry(
            path: fileName,
            isDirectory: fileName.hasSuffix("/"),
            compressedSize: Int(compressedSize),
            uncompressedSize: Int(uncompressedSize),
            compressionMethod: compressionMethod,
            localHeaderOffset: UInt64(localHeaderOffset)
        )
    }

    func extractData(for entry: Entry) throws -> Data? {
        fileHandle.seek(toFileOffset: entry.localHeaderOffset)

        let localHeader = fileHandle.readData(ofLength: 30)
        guard localHeader.count == 30 else { return nil }

        let fileNameLen = readUInt16(localHeader, at: 26)
        let extraLen = readUInt16(localHeader, at: 28)

        fileHandle.seek(toFileOffset: fileHandle.offsetInFile + UInt64(fileNameLen + extraLen))

        let compressedData = fileHandle.readData(ofLength: entry.compressedSize)

        if entry.compressionMethod == 0 {
            return compressedData
        } else if entry.compressionMethod == 8 {
            return decompressDeflate(compressedData, expectedSize: entry.uncompressedSize)
        } else {
            print("ZIPArchive: Unsupported compression method: \(entry.compressionMethod)")
            return nil
        }
    }

    private func decompressDeflate(_ data: Data, expectedSize: Int) -> Data? {
        guard expectedSize > 0 else { return Data() }

        var decompressed = Data(count: expectedSize)
        let result = decompressed.withUnsafeMutableBytes { destPtr -> Int in
            data.withUnsafeBytes { srcPtr -> Int in
                let dstSize = expectedSize
                let status = compression_decode_buffer(
                    destPtr.baseAddress!.assumingMemoryBound(to: UInt8.self),
                    dstSize,
                    srcPtr.baseAddress!.assumingMemoryBound(to: UInt8.self),
                    data.count,
                    nil,
                    COMPRESSION_ZLIB
                )
                return status
            }
        }

        if result > 0 {
            decompressed.count = result
            return decompressed
        }
        return nil
    }
}
