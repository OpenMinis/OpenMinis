import Foundation
import SQLite3
import UIKit

private let logger = AppLogger(category: "AIChatVM")

// MARK: - File Tool Execution

extension AIChatViewModel {

    // MARK: - File Tool Execution
    //
    // iSH uses a fakefs format (SQLite meta.db + data/ directory).
    // File content lives at data/<linux-path> and metadata (mode, uid, gid)
    // lives in meta.db (tables: paths, stats).
    //
    // We read/write files directly via FileManager on the data/ directory,
    // then ensure metadata exists in meta.db for new files. This avoids all
    // shell escaping issues and is significantly faster than shell commands.

    struct FileToolResult {
        let output: String
        let success: Bool
    }

    /// Snapshot all files under /var/minis/ with their modification dates.
    func snapshotMinisFiles() -> [String: Date] {
        let fm = FileManager.default
        guard let hostBase = resolveHostPath(Self.minisLinuxBaseDir) else { return [:] }
        guard fm.fileExists(atPath: hostBase.path) else { return [:] }
        // Resolve symlinks (e.g. /var → /private/var) so path prefix stripping works reliably.
        let resolvedBase = hostBase.resolvingSymlinksInPath().path

        // Collect file URLs first in an autoreleasepool, then process.
        // FileManager.enumerator can encounter stale entries when iSH modifies
        // the filesystem concurrently — guard against nil / invalid URLs.
        var fileURLs: [URL] = []
        autoreleasepool {
            guard let enumerator = fm.enumerator(at: hostBase,
                                                 includingPropertiesForKeys: [.contentModificationDateKey],
                                                 options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { return }
            while let obj = enumerator.nextObject() {
                guard let fileURL = obj as? URL else { continue }
                fileURLs.append(fileURL)
            }
        }

        var result: [String: Date] = [:]
        result.reserveCapacity(fileURLs.count)
        for fileURL in fileURLs {
            autoreleasepool {
                var isDir: ObjCBool = false
                guard fm.fileExists(atPath: fileURL.path, isDirectory: &isDir) else { return }
                if isDir.boolValue { return }

                let resolvedFile = fileURL.resolvingSymlinksInPath().path
                let relativePath = resolvedFile.replacingOccurrences(of: resolvedBase, with: "")
                let linuxPath = Self.minisLinuxBaseDir + relativePath
                let modDate = (try? fileURL.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? Date.distantPast
                result[linuxPath] = modDate
            }
        }
        return result
    }

    /// Resolve a minis:// URL string to a host filesystem URL.
    /// Global dirs (memory/skills/shared) live in the App Group container;
    /// session dirs live under minisPersistentBase/<sessionId>/<host>/.
    private func resolveMinisURL(_ urlString: String) -> URL? {
        guard let url = URL(string: urlString), url.scheme == "minis",
              let host = url.host else { return nil }
        // Tolerate double-encoded links. Prefer a variant that exists on disk;
        // otherwise return the first (single-decoded) so the write path still
        // gets a valid destination for a not-yet-created file.
        // [T-fix-double-encoding]
        let base: URL
        switch host {
        case "memory": base = Self.minisMemoryPersistentDir
        case "skills": base = Self.minisSkillsPersistentDir
        case "shared": base = Self.minisSharedPersistentDir
        case "mcp-servers": base = Self.minisMcpServersPersistentDir
        default:
            guard let sid = sessionId else { return nil }
            base = Self.minisPersistentBase
                .appendingPathComponent(sid, isDirectory: true)
                .appendingPathComponent(host, isDirectory: true)
        }
        let subPaths = MinisURLPathDecoding.subPathCandidates(for: url)
        let candidates = subPaths.map { base.appendingPathComponent($0) }
        return candidates.first { FileManager.default.fileExists(atPath: $0.path) }
            ?? candidates.first
    }

    /// Convert a Linux path under /var/minis/ to a minis:// URL, or nil if not under /var/minis/.
    func linuxPathToMinisURL(_ path: String) -> String? {
        let prefix = "/var/minis/"
        guard path.hasPrefix(prefix) else { return nil }
        let rest = String(path.dropFirst(prefix.count))  // "attachments/foo.png"
        guard let slashIdx = rest.firstIndex(of: "/") else { return nil }
        let namespace = String(rest[rest.startIndex..<slashIdx])
        let filename = String(rest[rest.index(after: slashIdx)...])  // "foo.png"
        let encoded = filename.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? filename
        return "minis://\(namespace)/\(encoded)"
    }

    /// Resolve a Linux path for direct file reads (read_image, file_read, file_write).
    /// For /var/minis/ paths, queries ISHExecutionCoordinator's active bind mount table
    /// to get the actual host URL, avoiding races when mounts switch between concurrent sessions.
    /// Falls back to resolveHostPath for non-/var/minis/ paths (e.g. /tmp, /root).
    func resolvePathForDirectRead(_ linuxPath: String) async -> URL? {
        if linuxPath.hasPrefix("/var/minis/") || linuxPath == "/var/minis" {
            if let resolved = await ISHExecutionCoordinator.shared.hostURL(for: linuxPath) {
                let exists = FileManager.default.fileExists(atPath: resolved.path)
                logger.notice("📂[RESOLVE] \(linuxPath) → \(resolved.path) exists=\(exists) sid=\(self.sessionId ?? "nil")")
                if exists { return resolved }
                // Mount table returned a stale path — fall through to session/rootfs fallbacks
                logger.notice("📂[RESOLVE] stale mount entry, trying fallbacks…")
            }
            // Mount table has no entry — fall back to resolveMinisURL with self.sessionId
            if let minisURL = linuxPathToMinisURL(linuxPath),
               let resolved = resolveMinisURL(minisURL) {
                let exists = FileManager.default.fileExists(atPath: resolved.path)
                logger.notice("📂[RESOLVE-sessionFallback] \(linuxPath) → \(resolved.path) exists=\(exists) sid=\(self.sessionId ?? "nil")")
                return resolved
            }
        }
        let fallback = resolveHostPath(linuxPath)
        let exists = fallback.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
        logger.notice("📂[RESOLVE-hostPath] \(linuxPath) → hostPath=\(fallback?.path ?? "nil") exists=\(exists)")
        return fallback
    }

    /// Resolve a minis:// URL or /var/minis/ Linux path for direct reads.
    /// Unifies both URL schemes into a single async resolution path.
    func resolveMinisPath(_ pathOrURL: String) async -> URL? {
        if pathOrURL.hasPrefix("minis://") {
            // Convert minis:// URL to Linux path, then resolve via mount table
            guard let url = URL(string: pathOrURL), let host = url.host else { return nil }
            let subPath = url.path.hasPrefix("/") ? String(url.path.dropFirst()) : url.path
            let linuxPath = "/var/minis/\(host)" + (subPath.isEmpty ? "" : "/\(subPath)")
            return await resolvePathForDirectRead(linuxPath)
        }
        return await resolvePathForDirectRead(pathOrURL)
    }

    /// Resolve a Linux path (e.g. /tmp/test.py) to the host URL under data/.
    /// Returns nil for invalid paths.
    func resolveHostPath(_ linuxPath: String) -> URL? {
        guard linuxPath.hasPrefix("/"), !linuxPath.contains("..") else { return nil }
        // Strip leading / — fix_path() convention from iSH
        let relative = String(linuxPath.dropFirst())
        if relative.isEmpty {
            return RootfsManager.shared.dataPath
        }
        return RootfsManager.shared.dataPath.appendingPathComponent(relative)
    }

    /// Ensure a path exists in meta.db so iSH can see it.
    /// SQLITE_TRANSIENT tells SQLite to copy blob data immediately.
    private static let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Bind a Linux path as a blob to a prepared statement parameter.
    private func bindPathBlob(_ stmt: OpaquePointer, index: Int32, path: String) {
        let utf8 = Array(path.utf8)
        sqlite3_bind_blob(stmt, index, utf8, Int32(utf8.count), Self.SQLITE_TRANSIENT)
    }

    /// Creates entries in both `stats` and `paths` tables if missing.
    func ensureFakefsMetadata(for linuxPath: String, isDirectory: Bool) {
        let metaDBPath = RootfsManager.shared.rootfsPath.appendingPathComponent("meta.db").path
        var db: OpaquePointer?
        guard sqlite3_open_v2(metaDBPath, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK,
              let db else { return }
        defer { sqlite3_close(db) }

        // Check if path already exists
        var checkStmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT inode FROM paths WHERE path = ?", -1, &checkStmt, nil) == SQLITE_OK,
              let checkStmt else { return }
        defer { sqlite3_finalize(checkStmt) }

        bindPathBlob(checkStmt, index: 1, path: linuxPath)

        if sqlite3_step(checkStmt) == SQLITE_ROW {
            return // Already exists in meta.db
        }

        // Build 16-byte ish_stat blob: mode(u32) + uid(u32) + gid(u32) + rdev(u32)
        // Regular file: S_IFREG | 0644 = 0o100644
        // Directory:    S_IFDIR | 0755 = 0o040755
        let mode: UInt32 = isDirectory ? 0o040755 : 0o100644
        var statBytes: [UInt8] = Array(repeating: 0, count: 16)
        let leMode = mode.littleEndian
        withUnsafeBytes(of: leMode) { src in
            for i in 0..<4 { statBytes[i] = src[i] }
        }

        // Insert stat
        var insertStatStmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "INSERT INTO stats (stat) VALUES (?)", -1, &insertStatStmt, nil) == SQLITE_OK,
              let insertStatStmt else { return }
        defer { sqlite3_finalize(insertStatStmt) }

        sqlite3_bind_blob(insertStatStmt, 1, statBytes, Int32(statBytes.count), Self.SQLITE_TRANSIENT)
        guard sqlite3_step(insertStatStmt) == SQLITE_DONE else { return }

        // Insert path with last_insert_rowid() as inode
        var insertPathStmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO paths (path, inode) VALUES (?, last_insert_rowid())", -1, &insertPathStmt, nil) == SQLITE_OK,
              let insertPathStmt else { return }
        defer { sqlite3_finalize(insertPathStmt) }

        bindPathBlob(insertPathStmt, index: 1, path: linuxPath)
        sqlite3_step(insertPathStmt)
    }

    /// Ensure all ancestor directories of a path exist in meta.db.
    func ensureParentDirsInMetaDB(for linuxPath: String) {
        var current = (linuxPath as NSString).deletingLastPathComponent
        var dirs: [String] = []
        while current != "/" && !current.isEmpty {
            dirs.append(current)
            current = (current as NSString).deletingLastPathComponent
        }
        // Create from root towards leaf
        for dir in dirs.reversed() {
            ensureFakefsMetadata(for: dir, isDirectory: true)
        }
    }

    /// Execute a file_read tool call via direct FileManager access.
    func executeFileRead(from json: String) async throws -> FileToolResult {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let path = dict["path"] as? String else {
            return FileToolResult(output: "Error: Missing required 'path' parameter", success: false)
        }

        guard let hostURL = await resolvePathForDirectRead(path) else {
            return FileToolResult(output: "Error: Invalid path: \(path)", success: false)
        }

        let offset = max(1, (dict["offset"] as? Int) ?? 1)
        let maxLines = dict["lines"] as? Int  // nil means no line limit
        // T-FILEREAD-CAP: hard upper bound on returned content length.
        // Pre-cap, the agent could ask for `max_length=1_000_000` and we'd
        // happily inline a 400 KB base64 image into a tool_result —
        // observed on Android as a 43 s StaticLayout / LineBreaker stall
        // when that 800 KB string later renders as a single message bubble.
        // Mirror the Android FileReadTool 80 KB cap so both platforms refuse
        // the same oversized reads regardless of the agent-supplied value.
        let kMaxFileReadChars = 80_000
        let requested = (dict["max_length"] as? Int) ?? Self.kMaxToolResultChars
        let maxLength = min(requested, kMaxFileReadChars)
        let direction = (dict["direction"] as? String)?.lowercased() ?? "head"

        guard FileManager.default.fileExists(atPath: hostURL.path) else {
            return FileToolResult(output: "Error: No such file: \(path)", success: false)
        }

        // Check for binary content (null bytes in first 512 bytes)
        let fileData: Data
        do {
            fileData = try Data(contentsOf: hostURL)
        } catch {
            return FileToolResult(output: "Error reading \(path): \(error.localizedDescription)", success: false)
        }

        let sampleSize = min(fileData.count, 512)
        let sample = fileData.prefix(sampleSize)
        if sample.contains(0) {
            let hexPreview = fileData.prefix(64).map { String(format: "%02x", $0) }.joined(separator: " ")
            return FileToolResult(
                output: "[\(path) | \(fileData.count) bytes | binary file]\nHex preview: \(hexPreview)",
                success: true
            )
        }

        guard let fullText = String(data: fileData, encoding: .utf8) else {
            return FileToolResult(output: "Error: \(path) is not valid UTF-8 text", success: false)
        }

        let allLines = fullText.components(separatedBy: "\n")
        let totalLines = allLines.count

        // Apply offset/direction and line limit
        let selectedLines: ArraySlice<String>
        let displayStart: Int  // 1-based line number of first returned line
        let displayEnd: Int    // 1-based line number of last returned line

        if direction == "tail" {
            let lineLimit = maxLines ?? allLines.count
            let sliced = allLines.suffix(lineLimit)
            selectedLines = sliced
            displayStart = allLines.count - sliced.count + 1
            displayEnd = allLines.count
        } else {
            let startIdx = min(offset - 1, allLines.count)
            let lineLimit = maxLines ?? (allLines.count - startIdx)
            let endIdx = min(startIdx + lineLimit, allLines.count)
            selectedLines = allLines[startIdx..<endIdx]
            displayStart = startIdx + 1
            displayEnd = endIdx
        }
        var content = selectedLines.joined(separator: "\n")

        // Apply max_length truncation.
        //
        // [T-fileread-truncation-header] The header used to keep reporting the
        // line range chosen BEFORE this truncation, so a 1324-line file cut at
        // 80 000 chars still announced "showing lines 1-1324 of 1324" — the
        // agent read that as "the whole file arrived" and never paged on. The
        // `(truncated at N chars)` note contradicted the range right next to
        // it, and offered nothing actionable.
        //
        // So the truncated branch now recomputes which lines actually survived
        // and hands back the offset to resume from. Everything here is inside
        // `content.count > maxLength`; the untruncated path is byte-identical
        // to before.
        var wasTruncated = false
        var effectiveStart = displayStart
        var effectiveEnd = displayEnd
        var nextOffset: Int?
        if content.count > maxLength {
            wasTruncated = true
            if direction == "tail" {
                // tail means "the end of the file". Taking a prefix here threw
                // away the very lines that were asked for and returned the
                // start of the tail window instead.
                content = String(content.suffix(maxLength))
                // Drop a leading partial line so the first line is whole.
                if let firstNewline = content.firstIndex(of: "\n"),
                   content.distance(from: content.startIndex, to: firstNewline) < content.count - 1 {
                    content = String(content[content.index(after: firstNewline)...])
                }
                effectiveStart = displayEnd - content.components(separatedBy: "\n").count + 1
                // No next_offset for tail: paging forward from the end of the
                // file is meaningless, and inventing one would send the agent
                // somewhere it did not ask to go.
            } else {
                content = String(content.prefix(maxLength))
                // Back off to the last complete line. A half-line would either
                // be re-read whole on the next page (duplicated) or silently
                // stitched onto the next chunk (corrupted).
                if let lastNewline = content.lastIndex(of: "\n") {
                    content = String(content[content.startIndex..<lastNewline])
                }
                effectiveEnd = displayStart + content.components(separatedBy: "\n").count - 1
                if effectiveEnd < totalLines { nextOffset = effectiveEnd + 1 }
            }
        }

        let rangeInfo = "showing lines \(effectiveStart)-\(effectiveEnd) of \(totalLines)"
        var header = "[\(path) | \(fileData.count) bytes | \(totalLines) lines | \(rangeInfo)"
        if wasTruncated {
            header += " | truncated at \(maxLength) chars"
            if let nextOffset {
                // Named to match the tool's own `offset` parameter so the model
                // can copy it straight into the next call.
                header += ", next_offset=\(nextOffset)"
            } else {
                header += ", retry with a smaller lines value"
            }
        }
        header += "]"

        var output = "\(header)\n\(content)"
        if let minisURL = linuxPathToMinisURL(path) {
            output += "\nminis_url: \(minisURL)"
        }
        return FileToolResult(output: output, success: true)
    }

    /// Execute a file_write tool call via direct FileManager + meta.db update.
    func executeFileWrite(from json: String) async throws -> FileToolResult {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let path = dict["path"] as? String,
              let content = dict["content"] as? String else {
            return FileToolResult(output: "Error: Missing required 'path' and/or 'content' parameters", success: false)
        }

        #if DEBUG
        print("[FileWrite] ▶ START path=\(path) sessionId=\(self.sessionId ?? "<nil>") contentBytes=\(content.utf8.count)")
        #endif

        // Pre-reject writes to read-only mounts using the Linux path directly.
        // This avoids the firmlink / symlink / case-sensitivity pitfalls of
        // comparing resolved host URLs, and catches the bypass regardless of
        // which branch `resolvePathForDirectRead` takes below.
        if MountedFolderCoordinator.isLinuxPathUnderReadOnlyMount(path) {
            return FileToolResult(
                output: "Error: \(path) is inside a read-only mounted folder and cannot be modified. Toggle writability in Settings → Mount External Folders if this is a mistake.",
                success: false
            )
        }

        // If the target is under /var/minis/mounts/ (user-mounted external
        // folders bind-mounted by iSH), the mount table may not yet be
        // populated for this session — `performMount` only runs lazily when
        // the first shell command executes. Without this prime, `hostURL(for:)`
        // returns nil and we fall back to `resolveHostPath` which writes to
        // the fakefs data/ directory, silently diverging from the real host
        // folder. Force a mount prime here so the lookup hits the real path.
        if path.hasPrefix("/var/minis/mounts/"), let sid = self.sessionId {
            #if DEBUG
            let beforeSnap = await ISHExecutionCoordinator.shared.debugMountSnapshot()
            print("[FileWrite] ensureMounted BEFORE sid=\(sid) mountedSid=\(beforeSnap.sessionId ?? "<nil>") mountedPaths.count=\(beforeSnap.paths.count) keys=\(Array(beforeSnap.paths.keys))")
            #endif
            await ISHExecutionCoordinator.shared.ensureMounted(for: sid)
            #if DEBUG
            let afterSnap = await ISHExecutionCoordinator.shared.debugMountSnapshot()
            print("[FileWrite] ensureMounted AFTER sid=\(sid) mountedSid=\(afterSnap.sessionId ?? "<nil>") mountedPaths.count=\(afterSnap.paths.count) keys=\(Array(afterSnap.paths.keys))")
            for (k, v) in afterSnap.paths {
                print("[FileWrite]   mount[\(k)] -> \(v.path)")
            }
            #endif
        } else {
            #if DEBUG
            if path.hasPrefix("/var/minis/mounts/") {
                print("[FileWrite] ensureMounted SKIPPED path=\(path) — sessionId is nil")
            }
            #endif
        }

        // Write-specific resolution for /var/minis/mounts/ targets:
        // `resolvePathForDirectRead` gates its mount-table result on
        // `fileExists(resolved)` — fine for reads, wrong for writes because a
        // brand-new file doesn't exist yet. That gate silently falls through
        // to `resolveMinisURL` which returns a per-session fake path
        // (`minisPersistentBase/<sid>/mounts/...`) that's not inside the
        // fakefs `data/` dir, so the defense-in-depth check below also misses
        // it and the write "succeeds" to a location nobody sees.
        //
        // For writes we want the mount-table URL even when the target file
        // doesn't exist yet — the parent directory will be `mkdir -p`'d below,
        // and the file will be created at the real host path (bind-mounted
        // into iSH and visible in iOS Files).
        let hostURL: URL?
        if path.hasPrefix("/var/minis/mounts/") {
            let mountURL = await ISHExecutionCoordinator.shared.hostURL(for: path)
            #if DEBUG
            print("[FileWrite] hostURL(for:\(path)) → \(mountURL?.path ?? "<nil>")")
            #endif
            if let mountURL {
                hostURL = mountURL
                logger.notice("📂[RESOLVE-write] \(path) → \(mountURL.path) (mount table, unconditional for writes)")
                #if DEBUG
                print("[FileWrite] using MOUNT-TABLE URL: \(mountURL.path)")
                #endif
            } else {
                hostURL = await resolvePathForDirectRead(path)
                #if DEBUG
                print("[FileWrite] mount table miss, resolvePathForDirectRead → \(hostURL?.path ?? "<nil>")")
                #endif
            }
        } else {
            hostURL = await resolvePathForDirectRead(path)
            #if DEBUG
            print("[FileWrite] non-mounts path, resolvePathForDirectRead → \(hostURL?.path ?? "<nil>")")
            #endif
        }

        guard let hostURL else {
            #if DEBUG
            print("[FileWrite] ✗ ABORT hostURL is nil for path=\(path)")
            #endif
            return FileToolResult(output: "Error: Invalid path: \(path)", success: false)
        }
        #if DEBUG
        print("[FileWrite] FINAL hostURL=\(hostURL.path)")
        #endif

        // Defense-in-depth: also check after host-URL resolution in case the
        // path didn't match the Linux-prefix form but still resolved into a
        // mounted folder.
        if MountedFolderCoordinator.isUnderReadOnlyMount(hostURL) {
            return FileToolResult(
                output: "Error: \(path) is inside a read-only mounted folder and cannot be modified. Toggle writability in Settings → Mount External Folders if this is a mistake.",
                success: false
            )
        }

        let appendMode = (dict["append"] as? Bool) ?? false
        let createDirs = (dict["create_dirs"] as? Bool) ?? false

        let fm = FileManager.default
        let parentDir = hostURL.deletingLastPathComponent()

        // Create parent directories if needed
        if createDirs || !fm.fileExists(atPath: parentDir.path) {
            do {
                try fm.createDirectory(at: parentDir, withIntermediateDirectories: true)
            } catch {
                return FileToolResult(output: "Error creating directories: \(error.localizedDescription)", success: false)
            }
        }

        guard let contentData = content.data(using: .utf8) else {
            return FileToolResult(output: "Error: Content is not valid UTF-8", success: false)
        }

        // Defense-in-depth: if the Linux path is under /var/minis/mounts/ but
        // the resolved hostURL landed anywhere other than the real external
        // folder (the fakefs `data/var/minis/mounts/` shadow, or a per-session
        // `minisPersistentBase/<sid>/mounts/` fallback path), the mount-table
        // lookup missed and we'd be writing to an orphan file invisible to
        // both iSH and the real host folder. Reject rather than silently
        // "succeed".
        if path.hasPrefix("/var/minis/mounts/") {
            let resolvedPath = hostURL.standardizedFileURL.path
            let fakefsMountsPrefix = RootfsManager.shared.dataPath
                .appendingPathComponent("var/minis/mounts").standardizedFileURL.path
            let sessionFallbackPrefix = Self.minisPersistentBase
                .standardizedFileURL.path
            #if DEBUG
            print("[FileWrite] defenseCheck resolved=\(resolvedPath)")
            print("[FileWrite] defenseCheck fakefsMountsPrefix=\(fakefsMountsPrefix)")
            print("[FileWrite] defenseCheck sessionFallbackPrefix=\(sessionFallbackPrefix)")
            print("[FileWrite] defenseCheck hitFakefs=\(resolvedPath.hasPrefix(fakefsMountsPrefix)) hitSessionFallback=\(resolvedPath.hasPrefix(sessionFallbackPrefix))")
            #endif
            if resolvedPath.hasPrefix(fakefsMountsPrefix)
                || resolvedPath.hasPrefix(sessionFallbackPrefix) {
                #if DEBUG
                print("[FileWrite] ✗ DEFENSE REJECT path=\(path) resolved=\(resolvedPath)")
                #endif
                return FileToolResult(
                    output: "Error: mount for \(path) is not active — the file would be written to an internal location invisible to both iSH and the real folder. Ensure the folder is mounted in Settings → Mount External Folders, then retry.",
                    success: false
                )
            }
        }

        do {
            if appendMode, fm.fileExists(atPath: hostURL.path) {
                let handle = try FileHandle(forWritingTo: hostURL)
                handle.seekToEndOfFile()
                handle.write(contentData)
                handle.closeFile()
                #if DEBUG
                print("[FileWrite] APPENDED \(contentData.count) bytes to \(hostURL.path)")
                #endif
            } else {
                try contentData.write(to: hostURL)
                #if DEBUG
                let landedSize = (try? FileManager.default.attributesOfItem(atPath: hostURL.path)[.size] as? Int) ?? -1
                print("[FileWrite] WROTE \(contentData.count) bytes to \(hostURL.path) (on-disk size now \(landedSize))")
                #endif
            }
        } catch {
            #if DEBUG
            print("[FileWrite] ✗ WRITE ERROR path=\(path) url=\(hostURL.path) err=\(error.localizedDescription)")
            #endif
            return FileToolResult(output: "Error writing \(path): \(error.localizedDescription)", success: false)
        }

        // Ensure parent directories and file path exist in meta.db
        if createDirs {
            let parentLinux = (path as NSString).deletingLastPathComponent
            ensureParentDirsInMetaDB(for: parentLinux)
        }
        ensureFakefsMetadata(for: path, isDirectory: false)

        let bytesWritten = contentData.count
        let action = appendMode ? "Appended" : "Wrote"
        var result = "\(action) to \(path) (\(bytesWritten) bytes)"
        if let minisURL = linuxPathToMinisURL(path) {
            result += "\nminis_url: \(minisURL)"
        }
        return FileToolResult(output: result, success: true)
    }

    func executeFileEdit(from json: String) async throws -> FileToolResult {
        guard let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let path = dict["path"] as? String,
              let oldString = dict["old_string"] as? String,
              let newString = dict["new_string"] as? String else {
            return FileToolResult(output: "Error: Missing required parameters (path, old_string, new_string)", success: false)
        }

        // Pre-reject edits to read-only mounts via Linux path first — immune
        // to firmlink / case-sensitivity issues that can hide mount membership.
        if MountedFolderCoordinator.isLinuxPathUnderReadOnlyMount(path) {
            return FileToolResult(
                output: "Error: \(path) is inside a read-only mounted folder and cannot be modified. Toggle writability in Settings → Mount External Folders if this is a mistake.",
                success: false
            )
        }

        guard let hostURL = await resolvePathForDirectRead(path) else {
            return FileToolResult(output: "Error: Invalid path: \(path)", success: false)
        }

        // Defense-in-depth after host-URL resolution.
        if MountedFolderCoordinator.isUnderReadOnlyMount(hostURL) {
            return FileToolResult(
                output: "Error: \(path) is inside a read-only mounted folder and cannot be modified. Toggle writability in Settings → Mount External Folders if this is a mistake.",
                success: false
            )
        }

        guard FileManager.default.fileExists(atPath: hostURL.path) else {
            return FileToolResult(output: "Error: File does not exist: \(path). Use file_write to create new files.", success: false)
        }

        guard let fileContent = try? String(contentsOf: hostURL, encoding: .utf8) else {
            return FileToolResult(output: "Error: Could not read \(path) as UTF-8 text", success: false)
        }

        guard !oldString.isEmpty else {
            return FileToolResult(output: "Error: old_string must not be empty. Use file_write to create or overwrite files.", success: false)
        }

        let replaceAll = (dict["replace_all"] as? Bool) ?? false
        let strictRanges = Self.findAllRanges(of: oldString, in: fileContent)

        // Fuzzy fallback. AIs routinely emit smart quotes as ASCII quotes
        // (Claude tokenizer especially) and drop trailing whitespace or
        // collapse runs of inline spaces when re-emitting code/prose they
        // just read. Strict byte-equality matching then rejects edits the
        // user clearly intended. When strict fails, normalize BOTH sides
        // (quotes, trailing whitespace, runs of inline spaces) and search
        // the normalized text — but apply the replacement against the
        // ORIGINAL byte range so the file's actual bytes are preserved
        // around the swap. Surface "fuzzy" in the success message so the
        // model learns its old_string didn't match verbatim.
        let fuzzyNote: String?
        let editRanges: [Range<String.Index>]
        if !strictRanges.isEmpty {
            editRanges = strictRanges
            fuzzyNote = nil
        } else {
            let (normFile, fileMap) = Self.normalizeForFuzzyMatch(fileContent)
            let (normOld, _) = Self.normalizeForFuzzyMatch(oldString)
            let normRanges = Self.findAllRanges(of: normOld, in: normFile)
            if normRanges.isEmpty {
                let preview = Self.buildFileEditPreview(
                    fileContent: fileContent,
                    oldString: oldString,
                    normFile: normFile,
                    normOld: normOld
                )
                return FileToolResult(
                    output: "Error: old_string not found in \(path). Use file_read to view current file contents before editing.\n\n\(preview)",
                    success: false
                )
            }
            // Map normalized character offsets back to original String.Index
            // ranges. The map array stores the original index for each char
            // in normFile; we look up the start char's mapping and walk to
            // the end char's mapping (+1) to get the matching original
            // range. This is robust across multi-byte UTF-8 because both
            // sides are operated on as Character sequences.
            var mapped: [Range<String.Index>] = []
            mapped.reserveCapacity(normRanges.count)
            for r in normRanges {
                let startOffset = normFile.distance(from: normFile.startIndex, to: r.lowerBound)
                let endOffset = normFile.distance(from: normFile.startIndex, to: r.upperBound)
                guard startOffset < fileMap.count else { continue }
                let originalStart = fileMap[startOffset]
                let originalEnd: String.Index
                if endOffset == 0 {
                    originalEnd = fileContent.startIndex
                } else if endOffset <= fileMap.count {
                    // Use the next char's mapped start (or file end) as the
                    // exclusive upper bound so we don't truncate.
                    let lastIncluded = fileMap[endOffset - 1]
                    originalEnd = fileContent.index(after: lastIncluded)
                } else {
                    originalEnd = fileContent.endIndex
                }
                mapped.append(originalStart..<originalEnd)
            }
            editRanges = mapped
            fuzzyNote = "fuzzy-matched: quotes/whitespace normalized"
        }

        if editRanges.count > 1 && !replaceAll {
            return FileToolResult(
                output: "Error: old_string matches \(editRanges.count) locations in \(path). Provide more surrounding context to make the match unique, or set replace_all: true to replace all occurrences.",
                success: false
            )
        }

        // Apply replacements from the last range backwards so earlier
        // indices stay valid as we mutate the string.
        var updatedContent = fileContent
        for range in editRanges.reversed() {
            updatedContent.replaceSubrange(range, with: newString)
        }

        guard let writeData = updatedContent.data(using: .utf8) else {
            return FileToolResult(output: "Error: Updated content is not valid UTF-8", success: false)
        }

        do {
            try writeData.write(to: hostURL)
        } catch {
            return FileToolResult(output: "Error writing \(path): \(error.localizedDescription)", success: false)
        }

        ensureFakefsMetadata(for: path, isDirectory: false)

        let replacements = editRanges.count == 1 ? "1 replacement" : "\(editRanges.count) replacements"
        var result = "Edited \(path) (\(replacements), \(updatedContent.utf8.count) bytes)"
        if let note = fuzzyNote {
            result += " — \(note)"
        }
        if let minisURL = linuxPathToMinisURL(path) {
            result += "\nminis_url: \(minisURL)"
        }
        return FileToolResult(output: result, success: true)
    }

    /// Find every (non-overlapping) range of `needle` in `haystack`,
    /// returning ranges in ascending order. Empty needle returns [].
    private static func findAllRanges(of needle: String, in haystack: String) -> [Range<String.Index>] {
        guard !needle.isEmpty else { return [] }
        var results: [Range<String.Index>] = []
        var searchStart = haystack.startIndex
        while searchStart < haystack.endIndex,
              let r = haystack.range(of: needle, range: searchStart..<haystack.endIndex) {
            results.append(r)
            searchStart = r.upperBound
        }
        return results
    }

    /// Normalize text for fuzzy file_edit matching. Returns the normalized
    /// string AND a parallel array mapping each normalized character's
    /// position back to the corresponding original `String.Index`. The
    /// caller uses this map to translate a match position in the
    /// normalized string into the original file's byte range so the
    /// replacement preserves the file's actual bytes outside the match.
    ///
    /// Rules:
    ///   - Smart quotes → ASCII quotes
    ///     U+201C/U+201D → `"`,  U+2018/U+2019 → `'`
    ///   - Each line's trailing whitespace dropped
    ///   - Runs of inline ASCII spaces collapsed to a single space
    ///     (but only between non-whitespace — leading indent on a line
    ///     is preserved verbatim so source code formatting isn't lost)
    ///
    /// Tabs and other whitespace are left intact so indentation-significant
    /// files (Python, Makefile) are still matchable strictly when the
    /// model emits tabs correctly.
    private static func normalizeForFuzzyMatch(_ text: String) -> (String, [String.Index]) {
        var out = String()
        out.reserveCapacity(text.count)
        var map: [String.Index] = []
        map.reserveCapacity(text.count)

        // First pass: substitute characters one-by-one, tracking trailing
        // whitespace per line and inline-space collapsing.
        // We process character-by-character, peeking at the next non-space
        // to decide whether trailing spaces should be dropped.
        let chars = Array(text)
        let indices = Array(text.indices)
        var i = 0
        var atLineStart = true   // true until first non-whitespace on the line
        while i < chars.count {
            let ch = chars[i]
            // Newline always emitted (with original mapping). Resets
            // atLineStart for the next line.
            if ch == "\n" {
                out.append(ch); map.append(indices[i])
                atLineStart = true
                i += 1
                continue
            }
            // Substitute smart quotes.
            var emit: Character = ch
            switch ch {
            case "\u{201C}", "\u{201D}": emit = "\""
            case "\u{2018}", "\u{2019}": emit = "'"
            default: break
            }
            // Trailing whitespace drop: if this is an ASCII space and
            // every remaining char before the next '\n' (or end) is also
            // a space, drop the run.
            if ch == " " {
                // Look ahead to next non-space
                var j = i
                while j < chars.count, chars[j] == " " { j += 1 }
                let isTrailing = (j == chars.count) || (chars[j] == "\n")
                if isTrailing {
                    // Skip this whole trailing whitespace run.
                    i = j
                    continue
                }
                // Inline space — collapse runs to a single space UNLESS
                // we're at the start of a line (preserve indentation).
                if atLineStart {
                    // Leading indent: emit every space verbatim.
                    for k in i..<j {
                        out.append(" "); map.append(indices[k])
                    }
                    i = j
                    atLineStart = false
                    continue
                }
                // Inline collapse: emit one space mapped to the first
                // space's index.
                out.append(" "); map.append(indices[i])
                i = j
                continue
            }
            out.append(emit); map.append(indices[i])
            atLineStart = false
            i += 1
        }
        return (out, map)
    }

    /// Build a context-rich preview for file_edit miss. We try to locate
    /// the most likely region using a heuristic: search the first 60 chars
    /// of the (normalized) old_string against the (normalized) file. If
    /// found, return ±10 lines of context around that hit. Otherwise fall
    /// back to the original first-20-lines preview so the AI at least sees
    /// the file head.
    private static func buildFileEditPreview(
        fileContent: String,
        oldString: String,
        normFile: String,
        normOld: String
    ) -> String {
        // Use first ~60 chars of normalized old as anchor; many failed
        // matches differ later in the string but the start lines up.
        let probeLen = min(60, normOld.count)
        let probeEnd = normOld.index(normOld.startIndex, offsetBy: probeLen)
        let probe = String(normOld[..<probeEnd])
        var anchorLine: Int? = nil
        if !probe.isEmpty, let r = normFile.range(of: probe) {
            // Convert match offset to line number in the ORIGINAL file.
            let offset = normFile.distance(from: normFile.startIndex, to: r.lowerBound)
            // Walk normFile up to offset, counting newlines; this is the
            // same line index in the original file because normalization
            // never adds or removes \n.
            let prefix = normFile[normFile.startIndex..<r.lowerBound]
            anchorLine = prefix.filter { $0 == "\n" }.count
        }
        let lines = fileContent.components(separatedBy: "\n")
        if let a = anchorLine {
            let start = max(0, a - 10)
            let end = min(lines.count, a + 11)
            var out = "Closest match in file (lines \(start + 1)–\(end), normalized old_string anchor at line \(a + 1)):\n"
            out += lines[start..<end].enumerated()
                .map { "\(start + $0.offset + 1): \($0.element)" }
                .joined(separator: "\n")
            out += "\n\nNote: file may use smart quotes (\u{201C}\u{201D}\u{2018}\u{2019}), tabs, or trailing whitespace your old_string omits. Re-read the file and copy the exact bytes."
            return out
        }
        let preview = lines.prefix(20).enumerated().map { "\($0.offset + 1): \($0.element)" }.joined(separator: "\n")
        return "First 20 lines:\n\(preview)"
    }

}
