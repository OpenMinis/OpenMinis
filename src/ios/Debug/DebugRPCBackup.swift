// Wrapped in #if DEBUG like every other DebugRPC* file: DebugRPCErr and the
// whole debug-server surface only exist in Debug builds, so an unguarded
// reference here broke the RELEASE archive ("cannot find 'DebugRPCErr' in
// scope") while Debug builds compiled fine — invisible until the first
// TestFlight upload attempt.
#if DEBUG
import Foundation

/// DEBUG-only RPC surface for the backup exporter.
///
/// Exists so stage 1 is verifiable on a real device before any UI is built:
/// trigger an export, read back the manifest, and inspect the package's
/// internal structure without having to extract it by hand.
enum DebugRPCBackup {

    /// `debug.backup.export` — run a full export and report the result.
    ///
    /// Params (all optional):
    ///   - `categories`: [String], raw category values. Default = all.
    ///   - `maxFileBytes`: Int, the §3.4 cap. Default = unlimited (null).
    ///     0 means "skip every file's contents", recording tombstones only.
    ///   - `keep`: Bool, copy the package into Documents so it survives the
    ///     temp directory and can be pulled with `debug.readFile`.
    static func export(params: [String: Any]) async throws -> [String: Any] {
        var options = BackupExporter.Options()

        if let raw = params["categories"] as? [String] {
            let parsed = Set(raw.compactMap(BackupCategory.init(rawValue:)))
            guard !parsed.isEmpty else {
                throw DebugRPCErr(-32602, "No valid categories. Known: "
                    + BackupCategory.allCases.map(\.rawValue).joined(separator: ", "))
            }
            options.categories = parsed
        }
        // `>= 0`, not `> 0`: 0 is a MEANINGFUL cap — it means every file is
        // over it, which is how "don't back up file contents" is expressed.
        // Rejecting it here made that setting untestable through this path.
        if let cap = params["maxFileBytes"] as? Int, cap >= 0 {
            options.maxFileBytes = Int64(cap)
        }
        // §3.3's "export copy without credentials" share path.
        if let inc = params["includeCredentials"] as? Bool {
            options.includeCredentials = inc
        }
        if let pass = params["passphrase"] as? String, !pass.isEmpty {
            options.passphrase = pass
        }
        // Snapshot cut-off, as a unix timestamp, so a device test can pin the
        // export to a known instant and assert on what got excluded.
        if let snap = params["snapshotAt"] as? Double {
            options.snapshotAt = Date(timeIntervalSince1970: snap)
        }

        let summary = try await BackupExporter().export(options: options)

        var packagePath = summary.packageURL.path
        if params["keep"] as? Bool == true {
            let docs = FileManager.default.urls(for: .documentDirectory,
                                                in: .userDomainMask)[0]
            let dest = docs.appendingPathComponent(summary.packageURL.lastPathComponent)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: summary.packageURL, to: dest)
            packagePath = dest.path
        } else if params["deliver"] as? Bool == true {
            // Exercise the real user-facing delivery path (§6.2 path 1): move
            // the package out of tmp/ into the Files-visible shared storage,
            // exactly as BackupSettingsView does after an export.
            packagePath = try BackupDelivery.moveToVisibleStorage(summary.packageURL).path
        }

        var categories: [String: Any] = [:]
        for (key, stat) in summary.categories {
            var d: [String: Any] = ["entries": stat.entries, "bytes": stat.bytes]
            if let m = stat.messages { d["messages"] = m }
            if let f = stat.files { d["files"] = f }
            if let c = stat.includesCredentials { d["includesCredentials"] = c }
            categories[key] = d
        }

        return [
            "backupId": summary.backupId,
            "resumedCategories": summary.resumedCategories,
            "path": packagePath,
            "bytes": summary.totalBytes,
            "durationMs": Int(summary.duration * 1000),
            "categories": categories,
            "skippedFiles": summary.skippedFiles,
            "skippedBytes": summary.skippedBytes,
            "skippedPaths": summary.skippedPaths.prefix(20).map {
                ["path": $0.path, "size": $0.size]
            },
        ]
    }

    /// `debug.backup.restore` — import a `.minisbak` package (stage 2, Merge).
    ///
    /// Params:
    ///   - `path` (required): package to restore.
    ///   - `categories`: raw values; default = every category in the package.
    ///   - `skipIntegrityCheck`: diagnostics only.
    static func restore(params: [String: Any]) async throws -> [String: Any] {
        guard let path = params["path"] as? String else {
            throw DebugRPCErr(-32602, "Missing required param: path")
        }
        let url = URL(fileURLWithPath: path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw DebugRPCErr(-32602, "No package at \(path)")
        }

        var options = BackupImporter.Options()
        if let raw = params["categories"] as? [String] {
            let parsed = Set(raw.compactMap(BackupCategory.init(rawValue:)))
            guard !parsed.isEmpty else {
                throw DebugRPCErr(-32602, "No valid categories. Known: "
                    + BackupCategory.allCases.map(\.rawValue).joined(separator: ", "))
            }
            options.categories = parsed
        }
        if params["skipIntegrityCheck"] as? Bool == true {
            options.skipIntegrityCheck = true
        }
        if let pass = params["passphrase"] as? String, !pass.isEmpty {
            options.passphrase = pass
        }

        let report = try await BackupImporter().import(from: url, options: options)

        return [
            "backupId": report.backupId,
            "durationMs": Int(report.duration * 1000),
            "wasEncrypted": report.wasEncrypted,
            "integrityChecked": report.integrityChecked,
            "integrityFailed": report.integrityFailed,
            "rolledBack": report.rolledBack,
            "totals": [
                "imported": report.totalImported,
                "updated": report.totalUpdated,
                "skipped": report.totalSkipped,
                "unreadable": report.totalUnreadable,
            ],
            "categories": report.categories.map { c -> [String: Any] in
                var d: [String: Any] = [
                    "category": c.category, "imported": c.imported, "updated": c.updated,
                    "skipped": c.skipped, "unreadable": c.unreadable,
                    "filesWritten": c.filesWritten, "bytesWritten": c.bytesWritten,
                    "sizeSkippedInPackage": c.sizeSkippedInPackage,
                    // [review S9/S7] Surfaced so a device test can assert on an
                    // incomplete package instead of only seeing "success".
                    "notDownloadedInPackage": c.notDownloadedInPackage,
                    "missingBlobs": c.missingBlobs,
                ]
                if let f = c.failed { d["failed"] = f }
                return d
            },
            "warnings": report.warnings,
        ]
    }

    /// `debug.backup.open` — drive the "opened a .minisbak from Files" entry
    /// point and report whether the restore sheet is now presentable.
    ///
    /// [review S14] Exists to verify the routing headlessly. The real trigger
    /// is `onOpenURL` / the scene URL path, neither of which can be reached
    /// from an RPC — but both of them funnel into `BackupOpenRouter.handle`,
    /// so calling it here exercises the identical code path (stage the file,
    /// publish `pendingPackage`) that the root sheet observes.
    ///
    /// Returns the router's post-state rather than just "ok", so a test can
    /// assert the package was actually staged and is not being suppressed by
    /// the lock gate.
    @MainActor
    static func open(params: [String: Any]) async throws -> [String: Any] {
        guard let path = params["path"] as? String else {
            throw DebugRPCErr(-32602, "Missing required param: path")
        }
        let url = URL(fileURLWithPath: path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw DebugRPCErr(-32602, "No such file: \(path)")
        }

        let recognised = BackupOpenRouter.isBackupPackage(url)
        let handled = BackupOpenRouter.handle(url)
        // `handle` now dismisses any competing sheet and waits ~350ms before
        // publishing the package, so sampling too early reads a stale `nil`
        // and reports sheetWouldPresent=false for a routing that actually
        // works. Wait past that window before reading back.
        try? await Task.sleep(nanoseconds: 700_000_000)

        let pending = BackupOpenRouter.shared.pendingPackage
        let locked = SessionLockStore.shared.appIsLocked
        return [
            "recognisedAsPackage": recognised,
            "handled": handled,
            "pendingPackage": pending?.url.lastPathComponent ?? "",
            "hasPendingPackage": pending != nil,
            "appIsLocked": locked,
            // What the root sheet will actually do with the above.
            "sheetWouldPresent": pending != nil && !locked,
        ]
    }

    /// `debug.backup.destinations` — inspect and drive §6.2 path 2 (delivery
    /// into user-authorised mounted folders) without the UI.
    ///
    /// Params (all optional):
    ///   - `select`: [String] mount ids to set as the destination list.
    ///   - `deliver`: String, path to a package to copy to every destination.
    ///
    /// With no params it just reports the current state, which is what makes
    /// "did my selection persist / is this mount actually writable" answerable
    /// from a test harness.
    @MainActor
    static func destinations(params: [String: Any]) async throws -> [String: Any] {
        if let ids = params["select"] as? [String] {
            BackupDestinations.selectedIds = ids.compactMap(UUID.init(uuidString:))
        }

        let manager = MountedFoldersManager.shared
        let mounts: [[String: Any]] = manager.entries.map { e in
            let resolved = manager.resolvedURL(for: e.id)
            return [
                "id": e.id.uuidString,
                "name": e.name,
                "source": e.sourceDisplayName,
                "isWritable": e.isWritable,
                "userAllowWrite": e.userAllowWrite,
                "effectiveWritable": e.effectiveWritable,
                "available": resolved != nil,
                "path": resolved?.path ?? "",
                "selected": BackupDestinations.isSelected(e.id),
            ]
        }

        var out: [String: Any] = [
            "mounts": mounts,
            "selectedIds": BackupDestinations.selectedIds.map(\.uuidString),
            "eligibleCount": BackupDestinations.eligibleFolders.count,
            "packagesInDestinations": BackupDestinations.listPackages().map {
                ["name": $0.url.lastPathComponent, "folder": $0.folderName, "size": $0.size]
            },
        ]

        if let path = params["deliver"] as? String {
            let url = URL(fileURLWithPath: path)
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw DebugRPCErr(-32602, "No such file: \(path)")
            }
            out["delivered"] = await BackupDestinations.deliver(packageURL: url).map {
                ["folder": $0.folderName, "ok": $0.succeeded,
                 "destination": $0.destination?.path ?? "", "error": $0.error ?? ""]
            }
        }
        return out
    }

    /// `debug.backup.rescue` — salvage what can be read from a damaged package.
    ///
    /// Deliberately reads in descending order of trust and reports how far it
    /// got, rather than failing at the first problem: the whole point is that
    /// the package is already broken, so "this stage failed" is information,
    /// not an error.
    ///
    ///   1. `rescue.json`         — plaintext, tiny, the blob→owner mapping
    ///   2. `manifest.json`       — what the package should contain
    ///   3. `manifest.rescue.json`— the tail copy, if the primary is gone
    ///   4. central directory     — which members physically survive
    ///
    /// Forward-scanning local headers (for a package whose central directory is
    /// destroyed) is NOT implemented — the task marked it optional, and
    /// everything above already covers the far more common tail-truncation and
    /// damaged-index cases. Reported honestly in `notImplemented` rather than
    /// silently omitted, so nobody mistakes this for a complete recovery tool.
    static func rescue(params: [String: Any]) async throws -> [String: Any] {
        guard let path = params["path"] as? String else {
            throw DebugRPCErr(-32602, "Missing required param: path")
        }
        let url = URL(fileURLWithPath: path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw DebugRPCErr(-32602, "No such file: \(path)")
        }

        var out: [String: Any] = ["path": path]
        var stages: [String: Any] = [:]

        // Forward scan runs FIRST and unconditionally: it is the only read path
        // that does not depend on the central directory, which lives at the end
        // of the file and is therefore the first thing a truncation destroys.
        // Its results are used as the fallback for every stage below.
        let scanned = BackupPackageReader.forwardScan(at: url)
        var scannedData: [String: Data] = [:]
        for e in scanned {
            let bare = e.name.split(separator: "/").last.map(String.init) ?? e.name
            if let d = e.data, scannedData[bare] == nil { scannedData[bare] = d }
        }
        stages["forwardScan"] = scanned.isEmpty ? "no members found" : "ok"
        out["scannedMembers"] = scanned.count
        out["scannedRecovered"] = scanned.filter { $0.data != nil }.count
        out["scannedProblems"] = scanned.compactMap { e -> [String: String]? in
            guard let p = e.problem else { return nil }
            return ["name": e.name, "problem": p]
        }.prefix(10).map { $0 }

        // 1. Rescue index — via the central directory, else from the scan.
        let rescueDecoder = JSONDecoder()
        rescueDecoder.dateDecodingStrategy = .iso8601
        let rescueIndex = BackupRescueIndex.read(fromPackageAt: url)
            ?? scannedData[BackupRescueIndex.filename].flatMap {
                try? rescueDecoder.decode(BackupRescueIndex.self, from: $0)
            }
        if let rescue = rescueIndex {
            stages["rescue.json"] = "ok"
            out["backupId"] = rescue.backupId
            out["snapshotAt"] = rescue.snapshotAt.map(ISO8601DateFormatter().string(from:)) ?? ""
            out["blobCount"] = rescue.blobs.count
            out["sessionCount"] = rescue.sessions.count
            out["sessions"] = rescue.sessions.prefix(50).map {
                ["id": $0.id, "title": $0.title ?? "", "messageCount": $0.messageCount]
            }
            // Enough of the mapping to attribute blobs by hand.
            out["blobs"] = rescue.blobs.prefix(100).map {
                ["sha256": $0.sha256, "size": $0.size, "category": $0.category,
                 "sessionId": $0.sessionId ?? "", "path": $0.path]
            }
            var byCategory: [String: Int] = [:]
            for b in rescue.blobs { byCategory[b.category, default: 0] += 1 }
            out["blobsByCategory"] = byCategory
        } else {
            stages["rescue.json"] = "unreadable"
        }

        // 2 & 3. Manifest, then its tail copy.
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        func manifest(named name: String) -> BackupManifest? {
            let data = (try? BackupPackageReader.readEntry(at: url, named: name) ?? nil)
                ?? scannedData[name]
            guard let data else { return nil }
            return try? decoder.decode(BackupManifest.self, from: data)
        }
        let primary = manifest(named: "manifest.json")
        stages["manifest.json"] = primary != nil ? "ok" : "unreadable"
        let copy = manifest(named: BackupRescueIndex.manifestCopyFilename)
        stages[BackupRescueIndex.manifestCopyFilename] = copy != nil ? "ok" : "unreadable"

        if let m = primary ?? copy {
            out["usedManifestFrom"] = primary != nil ? "manifest.json" : BackupRescueIndex.manifestCopyFilename
            out["format"] = m.format
            out["encrypted"] = m.encryption != nil
            out["deviceName"] = m.deviceName
            out["categories"] = m.categories.mapValues { ["entries": $0.entries, "bytes": $0.bytes] }
            // An encrypted package can still be inventoried — the user is told
            // what is in there and that a passphrase is needed to extract it,
            // which is far better than "unreadable".
            if m.encryption != nil {
                out["note"] = "Package is encrypted: content needs the passphrase, but rescue.json and the manifest are plaintext."
            }
        }

        // 4. Which members physically survive, and whether the manifest's
        //    integrity map still matches them.
        if let entries = try? BackupPackageReader.listEntries(at: url) {
            stages["centralDirectory"] = "ok"
            out["memberCount"] = entries.count
            out["hasRescueIndex"] = entries.contains { $0.hasSuffix(BackupRescueIndex.filename) }
            out["hasManifestCopy"] = entries.contains { $0.hasSuffix(BackupRescueIndex.manifestCopyFilename) }
            if let m = primary ?? copy {
                let present = Set(entries.map { $0.split(separator: "/").dropFirst().joined(separator: "/") })
                let missing = m.integrity.keys.filter { !present.contains($0) }
                out["missingMembers"] = missing.count
                out["missingSample"] = Array(missing.prefix(10))
            }
        } else {
            stages["centralDirectory"] = "unreadable"
        }

        out["stages"] = stages
        return out
    }

    /// `debug.rclone.info` — Stage 0 verification for the rclone integration.
    ///
    /// Answers the one question upstream leaves open ("iOS has not been
    /// tested"): does the linked-in Go runtime actually start and respond
    /// inside this app, alongside iSH? `core/version` needs no config, no
    /// network and no credentials, so a reply isolates exactly that.
    ///
    /// Also lists the backends really compiled in, which verifies the trim in
    /// deps/rclone-mobile/backends/backends.go rather than trusting it.
    static func rcloneInfo(params: [String: Any]) async throws -> [String: Any] {
        let smoke = RcloneBridge.smokeTest()
        let backends = RcloneBridge.supportedBackends()
        return [
            "smokeTest": smoke,
            "ok": !smoke.hasPrefix("FAILED"),
            "backendCount": backends.count,
            "backends": backends,
        ]
    }

    /// `debug.rclone.remote` — manage rclone remotes and drive a chunked
    /// upload, without any UI.
    ///
    /// Actions:
    ///   list                                  — configured remotes
    ///   add    {name,backend,params,secret,path}
    ///   remove {name}
    ///   ls     {name,dir?}                    — browse the remote
    ///   upload {name,path,backupId?}          — chunked, resumable upload
    ///
    /// Exists so the transfer path can be verified against a real server
    /// before any UI is built — and so an interrupted upload can be resumed on
    /// demand to prove the journal works.
    @MainActor
    static func rcloneRemote(params: [String: Any]) async throws -> [String: Any] {
        let action = (params["action"] as? String) ?? "list"
        RcloneRemoteStore.syncToRclone()

        switch action {
        case "add":
            guard let name = params["name"] as? String,
                  let backend = params["backend"] as? String else {
                throw DebugRPCErr(-32602, "add needs: name, backend")
            }
            try RcloneRemoteStore.add(
                name: name,
                backend: backend,
                params: (params["params"] as? [String: String]) ?? [:],
                secret: params["secret"] as? String,
                path: (params["path"] as? String) ?? "",
                allowInsecureTLS: (params["allowInsecureTLS"] as? Bool) ?? false)
            RcloneRemoteStore.syncToRclone()
            return ["ok": true, "remotes": RcloneRemoteStore.remotes.map(\.name)]

        case "raw":
            // Direct rclone RPC passthrough, for diagnosing which call
            // actually clears a cache rather than guessing from behaviour.
            guard let method = params["method"] as? String else {
                throw DebugRPCErr(-32602, "raw needs: method")
            }
            let out = try RcloneBridge.rpc(method, (params["params"] as? [String: Any]) ?? [:])
            return ["ok": true, "out": out]

        case "options":
            // Read rclone's live global options — the only honest way to
            // confirm a timeout/TLS setting actually landed.
            let out = try RcloneBridge.rpc("options/get")
            let main = (out["main"] as? [String: Any]) ?? [:]
            return ["ok": true, "main": [
                "Timeout": main["Timeout"] ?? "?",
                "ConnectTimeout": main["ConnectTimeout"] ?? "?",
                "LowLevelRetries": main["LowLevelRetries"] ?? "?",
                "Retries": main["Retries"] ?? "?",
                "InsecureSkipVerify": main["InsecureSkipVerify"] ?? "?",
            ]]

        case "update":
            guard let name = params["name"] as? String else {
                throw DebugRPCErr(-32602, "update needs: name")
            }
            let updated = try RcloneRemoteStore.update(
                name: name,
                newName: params["newName"] as? String,
                newPath: params["newPath"] as? String,
                newParams: params["newParams"] as? [String: String],
                newSecret: params["newSecret"] as? String)
            // Report what the Keychain holds AFTER the write, so a failed
            // secret update is visible instead of being inferred.
            let probe = updated.map { RcloneRemoteStore.debugSecretFingerprint(for: $0.name) }
            return ["ok": true,
                    "name": updated?.name ?? "",
                    "path": updated?.path ?? "",
                    "secretFingerprint": probe ?? "n/a"]

        case "remove":
            guard let name = params["name"] as? String else {
                throw DebugRPCErr(-32602, "remove needs: name")
            }
            RcloneRemoteStore.remove(name: name)
            return ["ok": true, "remotes": RcloneRemoteStore.remotes.map(\.name)]

        case "ls":
            guard let name = params["name"] as? String,
                  let r = RcloneRemoteStore.remote(named: name) else {
                throw DebugRPCErr(-32602, "no such remote")
            }
            let dir = (params["dir"] as? String) ?? r.path
            let out = try RcloneBridge.rpc("operations/list", [
                "fs": RcloneRemoteStore.fsSpec(for: r), "remote": dir,
            ])
            return ["ok": true, "list": out["list"] ?? []]

        case "packages":
            guard let name = params["name"] as? String,
                  let r = RcloneRemoteStore.remote(named: name) else {
                throw DebugRPCErr(-32602, "no such remote")
            }
            let pkgs = try await Task.detached(priority: .userInitiated) {
                try RcloneTransfer.listPackages(remote: r)
            }.value
            return ["ok": true, "packages": pkgs.map {
                ["name": $0.displayName, "key": $0.key, "size": $0.size]
            }]

        case "download":
            guard let name = params["name"] as? String,
                  let r = RcloneRemoteStore.remote(named: name),
                  let key = params["key"] as? String else {
                throw DebugRPCErr(-32602, "download needs: name, key")
            }
            let all = try await Task.detached(priority: .userInitiated) {
                try RcloneTransfer.listPackages(remote: r)
            }.value
            guard let pkg = all.first(where: { $0.key == key }) else {
                throw DebugRPCErr(-32602, "no such package")
            }
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("dl-\(UUID().uuidString).\(BackupFormat.fileExtension)")
            try await Task.detached(priority: .userInitiated) {
                try RcloneTransfer.download(pkg, from: r, to: dest)
            }.value
            let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
            return ["ok": true, "path": dest.path, "bytes": size ?? 0]

        case "upload":
            guard let name = params["name"] as? String,
                  let r = RcloneRemoteStore.remote(named: name),
                  let path = params["path"] as? String else {
                throw DebugRPCErr(-32602, "upload needs: name, path")
            }
            let url = URL(fileURLWithPath: path)
            let backupId = (params["backupId"] as? String) ?? url.lastPathComponent
            var lastFraction = 0.0
            // Count DISTINCT progress reports: one report means the callback
            // only fired at completion, which is exactly the regression being
            // checked for here.
            var samples: [Double] = []
            // Off the main actor: the upload does blocking file + network I/O.
            try await Task.detached(priority: .utility) {
                try RcloneTransfer.upload(
                    packageURL: url, remote: r, backupId: backupId,
                    progress: { p in
                        lastFraction = p.fraction
                        if samples.last != p.fraction { samples.append(p.fraction) }
                    })
            }.value
            return ["ok": true, "fraction": lastFraction,
                    "progressReports": samples.count,
                    "samples": samples.prefix(12).map { ($0 * 100).rounded() / 100 }]

        default:
            return [
                "remotes": RcloneRemoteStore.remotes.map { r -> [String: Any] in
                    ["name": r.name, "backend": r.backend, "path": r.path]
                },
            ]
        }
    }

    /// `debug.backup.cleanup` — delete packages left in Documents by
    /// `export {keep:true}`.
    ///
    /// Test packages can be hundreds of MB, and `keep` deliberately moves them
    /// somewhere that survives tmp cleanup — so there has to be a way to remove
    /// them again without reaching for a destructive shell command.
    /// Scoped to Documents and to the `.minisbak` extension so it cannot touch
    /// anything else.
    static func cleanup(params: [String: Any]) async throws -> [String: Any] {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        var removed: [String] = []
        var freed: Int64 = 0
        // Also sweep the real delivery directory, so device tests don't leave
        // packages behind in the place users will actually browse.
        var roots = [docs]
        if FileManager.default.fileExists(atPath: BackupDelivery.backupsDirectory.path) {
            roots.append(BackupDelivery.backupsDirectory)
        }
        let names = roots.flatMap { root in
            ((try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? [])
                .map { (root, $0) }
        }
        for (root, name) in names where name.hasSuffix("." + BackupFormat.fileExtension) {
            let url = root.appendingPathComponent(name)
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
            do {
                try FileManager.default.removeItem(at: url)
                removed.append(name)
                freed += size ?? 0
            } catch {
                continue
            }
        }
        return ["removed": removed, "count": removed.count, "freedBytes": freed]
    }

    /// `debug.backup.inspect` — read a package's structure back without
    /// extracting it manually. Reports the manifest plus per-entry counts, so a
    /// test can assert on what actually landed in the archive.
    static func inspect(params: [String: Any]) async throws -> [String: Any] {
        guard let path = params["path"] as? String else {
            throw DebugRPCErr(-32602, "Missing required param: path")
        }
        let url = URL(fileURLWithPath: path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw DebugRPCErr(-32602, "No package at \(path)")
        }

        let entries = try BackupPackageReader.listEntries(at: url)
        var manifest: Any = NSNull()
        if let data = try BackupPackageReader.readEntry(at: url, named: "manifest.json"),
           let obj = try? JSONSerialization.jsonObject(with: data) {
            manifest = obj
        }

        // Count the JSONL lines per index so a caller can verify the file index
        // and blob index agree with the manifest's category stats.
        var lineCounts: [String: Int] = [:]
        for name in ["files.index.jsonl", "blobs.index.jsonl"] {
            if let data = try BackupPackageReader.readEntry(at: url, named: name) {
                lineCounts[name] = data.split(separator: 0x0A).count
            }
        }

        return [
            "path": path,
            "entryCount": entries.count,
            // Array(...) matters: an ArraySlice is not a JSON-serializable type
            // and JSONSerialization rejects the whole response if one is left in.
            "entries": Array(entries.prefix(200)),
            "manifest": manifest,
            "lineCounts": lineCounts,
        ]
    }
}

#endif
