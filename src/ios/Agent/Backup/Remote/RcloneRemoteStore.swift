import Foundation

private let logger = AppLogger(category: "Rclone")

/// User-configured rclone remotes (SMB / WebDAV / S3 / …) usable as backup
/// destinations.
///
/// ## Where the credentials live
///
/// rclone's own config file stores passwords "obscured" — that is reversible
/// by design (`rclone reveal` undoes it), so it is NOT protection. Writing a
/// user's NAS password into a file in the app container would be the same
/// mistake §5.4 already calls out for backup packages.
///
/// So the split is:
///   - **Keychain** holds the secrets (password / secret key / token).
///   - **UserDefaults** holds everything non-secret: name, backend type, host,
///     share, user, path.
///   - rclone's in-memory config is populated per launch via `config/create`,
///     and its config file is pointed at a throwaway path so nothing is
///     persisted by rclone itself.
///
/// That keeps exactly one copy of each secret, in the place iOS provides for
/// it, and means deleting a remote actually removes the credential.
@MainActor
enum RcloneRemoteStore {

    /// A configured remote, minus its secret.
    struct Remote: Codable, Identifiable, Equatable, Sendable {
        var id: String { name }
        /// rclone remote name — also the Keychain account key.
        var name: String
        /// rclone backend type: "smb", "webdav", "s3", "sftp", …
        var backend: String
        /// Non-secret backend parameters (host, url, user, share, region…).
        var params: [String: String]
        /// Directory inside the remote that backups are written to.
        var path: String
        var createdAt: Date
        /// Whether new backups are delivered here.
        ///
        /// Disabling is NOT deleting: the server stays configured, with its
        /// credential, so a user who wants to skip one destination for a while
        /// doesn't have to re-enter an address and password to bring it back.
        /// Defaults true so existing remotes keep working after an upgrade
        /// (the key is absent in their stored JSON).
        var enabled: Bool = true

        /// Accept a self-signed / untrusted TLS certificate for this server.
        ///
        /// Off by default: turning it on means an attacker who can intercept
        /// the connection could impersonate the server, and a backup carries
        /// everything the user has. It exists because a home NAS serving
        /// HTTPS with its own certificate is completely ordinary, and without
        /// this the only alternatives are "cannot use the app" or "downgrade
        /// to plain HTTP", the second of which is strictly worse.
        var allowInsecureTLS: Bool = false

        /// Bumped whenever the connection changes, to defeat rclone's Fs
        /// cache (see `fsSpec`). Not user-visible.
        var revision: Int = 0

        /// The name this remote is registered under inside rclone. Distinct
        /// from `name`, which is the user's label and must stay stable.
        var configName: String { revision == 0 ? name : "\(name)__r\(revision)" }

        /// Join `name` under this remote's backup directory.
        ///
        /// Naive `"\(path)/\(name)"` breaks when the user picked the SERVER
        /// ROOT as the destination: `path` is "" and the join yields "/name" —
        /// a LEADING-SLASH path that rclone's WebDAV backend resolves against
        /// the server root, ESCAPING the folder baked into the fs URL. Seen
        /// live: a package assembled to `http://host:8099/backup-….minisbak`
        /// instead of `…/backups/…`, then reported missing. Trimming slashes
        /// on both sides keeps every produced path fs-relative.
        func join(_ name: String) -> String {
            // [T-sftp-absolute-path] SFTP keeps its leading slash: there,
            // `/srv/backup` and `srv/backup` are DIFFERENT locations (absolute
            // vs relative to the login user's home), so trimming it rewrites
            // the destination the user chose. Verified against a non-chrooted
            // server, where `remote:/` lists the real filesystem root.
            //
            // Every other backend still gets the original trim — for the
            // URL-based ones a leading `/` resolves against the SERVER root and
            // escapes the folder in the fs spec, which is exactly the WebDAV
            // bug 1dec9e650 fixed. Only the trailing slash is normalised here.
            if RcloneBackendCatalog.usesAbsolutePaths(backend) {
                // `/` is the filesystem ROOT, not an empty path: dropping its
                // trailing slash would leave "" and silently retarget the
                // package at the login user's home. Only strip a trailing
                // slash from a longer path (`/srv/` → `/srv`).
                if path == "/" { return "/\(name)" }
                let base = path.hasSuffix("/") ? String(path.dropLast()) : path
                return base.isEmpty ? name : "\(base)/\(name)"
            }
            let base = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            return base.isEmpty ? name : "\(base)/\(name)"
        }

        // Hand-written so a record saved BEFORE `enabled` existed still
        // decodes. Swift's synthesized Decodable ignores the default value and
        // throws keyNotFound for a missing key — the same trap review S1 hit
        // on the backup manifest. Without this, adding the field would make
        // every previously-configured server vanish.
        enum CodingKeys: String, CodingKey {
            case name, backend, params, path, createdAt, enabled, allowInsecureTLS, revision
        }

        init(name: String, backend: String, params: [String: String],
             path: String, createdAt: Date, enabled: Bool = true,
             allowInsecureTLS: Bool = false) {
            self.name = name; self.backend = backend; self.params = params
            self.path = path; self.createdAt = createdAt; self.enabled = enabled
            self.allowInsecureTLS = allowInsecureTLS
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            name = try c.decode(String.self, forKey: .name)
            backend = try c.decode(String.self, forKey: .backend)
            params = try c.decodeIfPresent([String: String].self, forKey: .params) ?? [:]
            path = try c.decodeIfPresent(String.self, forKey: .path) ?? ""
            createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
            enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
            allowInsecureTLS = try c.decodeIfPresent(Bool.self, forKey: .allowInsecureTLS) ?? false
            revision = try c.decodeIfPresent(Int.self, forKey: .revision) ?? 0
        }
    }

    private static let defaultsKey = "backup.rclone.remotes"

    static var remotes: [Remote] {
        get {
            guard let data = UserDefaults.standard.data(forKey: defaultsKey),
                  let list = try? JSONDecoder().decode([Remote].self, from: data)
            else { return [] }
            return list
        }
        set {
            UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: defaultsKey)
        }
    }

    static func remote(named name: String) -> Remote? {
        remotes.first { $0.name == name }
    }

    // MARK: - Secrets

    private static let keychainService = "com.openminis.app.rclone"

    private static func storeSecret(_ secret: String, for name: String) {
        let account = name
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        guard !secret.isEmpty else { return }
        query[kSecValueData as String] = Data(secret.utf8)
        // The device must be unlocked, and this must never sync to iCloud or
        // land in an unencrypted backup — a NAS password is exactly the kind of
        // thing that should not travel.
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        if status != errSecSuccess {
            logger.error("[Rclone] keychain write failed for '\(name)': \(status)")
        }
    }

    private static func loadSecret(for name: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: name,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// DEBUG ONLY: a short, non-reversible fingerprint of the stored secret,
    /// so a test can tell "the password changed" from "the write silently
    /// failed" without ever printing the credential.
    static func debugSecretFingerprint(for name: String) -> String {
        guard let s = loadSecret(for: name) else { return "none" }
        var h: UInt64 = 5381
        for b in s.utf8 { h = (h &* 33) &+ UInt64(b) }
        return String(h % 100000)
    }

    private static func deleteSecret(for name: String) {
        SecItemDelete([
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: name,
        ] as CFDictionary)
    }

    // MARK: - Registration

    enum StoreError: LocalizedError {
        case nameTaken(String)
        case invalidName

        var errorDescription: String? {
            switch self {
            case .nameTaken(let n): return "A destination named “\(n)” already exists."
            case .invalidName: return "Choose a name using letters, numbers, - or _."
            }
        }
    }

    /// Add a remote. `secret` goes to the Keychain; everything else to defaults.
    static func add(name: String, backend: String, params: [String: String],
                    secret: String?, path: String,
                    allowInsecureTLS: Bool = false) throws {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty,
              trimmed.rangeOfCharacter(from: CharacterSet.alphanumerics.union(
                  CharacterSet(charactersIn: "-_")).inverted) == nil
        else { throw StoreError.invalidName }
        guard remote(named: trimmed) == nil else { throw StoreError.nameTaken(trimmed) }

        if let secret, !secret.isEmpty { storeSecret(secret, for: trimmed) }
        remotes.append(Remote(name: trimmed, backend: backend, params: params,
                              path: path, createdAt: Date(),
                              allowInsecureTLS: allowInsecureTLS))
        logger.info("[Rclone] remote added: \(trimmed) (\(backend))")
    }

    /// Remove a remote and its credential.
    static func remove(name: String) {
        remotes.removeAll { $0.name == name }
        deleteSecret(for: name)
        logger.info("[Rclone] remote removed: \(name)")
    }

    // MARK: - Handing config to rclone

    /// Which parameter carries the secret, per backend.
    ///
    /// rclone names these differently and there is no generic "password" key,
    /// so the mapping is explicit rather than guessed.
    /// Whether this backend's secret is a password rclone will de-obscure,
    /// as opposed to a key it uses literally.
    private static func secretNeedsObscuring(_ backend: String) -> Bool {
        switch backend {
        case "s3": return false
        default: return true
        }
    }

    private static func secretKey(for backend: String) -> String {
        switch backend {
        case "s3": return "secret_access_key"
        case "sftp", "smb", "webdav", "ftp": return "pass"
        default: return "pass"
        }
    }

    /// Push every configured remote into rclone's in-memory config.
    ///
    /// Runs per launch. rclone is told to use a config path under tmp/ so it
    /// never writes credentials to a file we would then have to protect —
    /// the Keychain stays the only copy.
    static func syncToRclone() {
        let configPath = FileManager.default.temporaryDirectory
            .appendingPathComponent("rclone-ephemeral.conf").path
        _ = try? RcloneBridge.rpc("config/setpath", ["path": configPath])



        // Global TLS relaxation is all-or-nothing (WebDAV and S3 expose no
        // per-remote option), so it goes on only when a configured server
        // actually asks for it — one NAS with a self-signed certificate must
        // not quietly lower the bar for every other destination the user has.
        RcloneBridge.setInsecureTLS(remotes.contains { $0.allowInsecureTLS })

        for r in remotes {
            var params: [String: Any] = r.params
            params["type"] = r.backend

            // FTP is the one backend with a real per-remote switch, so use it
            // rather than relying on the global flag — this keeps FTP correct
            // even when the global one is off for everyone else.
            if r.backend == "ftp", r.allowInsecureTLS {
                params["no_check_certificate"] = "true"
            }
            // Blank credentials mean GUEST, and that has to be said
            // explicitly. rclone's smb `user` option defaults to the OS login
            // name when the key is absent — on iOS that is "mobile" — so an
            // omitted username would silently try to authenticate as some
            // unrelated account instead of connecting as a guest.
            if r.backend == "smb",
               (r.params["user"] ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
                params["user"] = ""
            }

            // SMB is configured WITHOUT a share, so the share becomes the first
            // level of the folder browser — the same thing Finder and Windows
            // Explorer do when they enumerate shares after connecting.
            //
            // Asking for it as a text field copied rclone's config shape into
            // the UI, and it was the single worst field in the whole form: a
            // share is a flat network name, but a NAS shows the user its disk
            // path. Someone reading "/volume1/backups" on a Synology types
            // exactly that, and SMB answers a MISSING share with a logon-style
            // error — so the app reported a permission problem for a set of
            // perfectly good credentials, sending people to audit their account.
            //
            // Any `share` from an older build is dropped rather than migrated:
            // a share-less fs reaches every share the account can see, so the
            // stored path still resolves through the browser, and keeping the
            // key would pin those remotes to the one share they were created
            // with. Verified against Samba 4.19.5 — list, mkdir and file
            // upload all work share-less, anonymous and authenticated alike.
            if r.backend == "smb" {
                params.removeValue(forKey: "share")
            }

            // Anonymous access needs an OBSCURED EMPTY password, not a
            // missing one. rclone marks `pass` as IsPassword, and its FTP
            // backend de-obscures whatever it finds — including the option's
            // own "" default — so BOTH a bare "" and an absent key fail with
            // "input too short when revealing password", and the remote can't
            // connect at all. Verified directly against rclone: only
            // `pass = <obscure("")>` works for an anonymous server.
            //
            // S3 is excluded: its secret is consumed verbatim (see below), and
            // an anonymous bucket wants no key rather than an obscured blank.
            if r.backend != "s3",
               (params["pass"] as? String)?.isEmpty ?? true,
               loadSecret(for: r.name)?.isEmpty ?? true {
                if let blank = try? RcloneBridge.rpc("core/obscure", ["clear": ""]),
                   let value = blank["obscured"] as? String {
                    params["pass"] = value
                }
            }
            if let secret = loadSecret(for: r.name), !secret.isEmpty {
                // Obscure is rclone's expected on-the-wire form for PASSWORD
                // fields, and rclone reveals them again on use. It is NOT
                // encryption — the real protection is that the plaintext
                // lives in the Keychain and this copy is in-memory.
                //
                // API-key style fields are the exception: rclone consumes
                // `secret_access_key` verbatim, so obscuring it makes the
                // client sign requests with the obscured blob and every S3
                // call fails with SignatureDoesNotMatch. Found against a real
                // MinIO server — the connection test passed at add time
                // (that path used the plaintext) and only listing failed.
                if secretNeedsObscuring(r.backend),
                   let obscured = try? RcloneBridge.rpc("core/obscure", ["clear": secret]),
                   let value = obscured["obscured"] as? String {
                    params[secretKey(for: r.backend)] = value
                } else {
                    params[secretKey(for: r.backend)] = secret
                }
            }
            do {
                _ = try RcloneBridge.rpc("config/create", [
                    "name": r.configName,
                    "type": r.backend,
                    "parameters": params,
                    // Don't let rclone try to run an interactive OAuth flow.
                    "opt": ["nonInteractive": true],
                ])
            } catch {
                logger.error("[Rclone] config/create failed for '\(r.name)': \(error.localizedDescription)")
            }
        }
        logger.info("[Rclone] synced \(remotes.count) remote(s) into rclone config")
    }

    /// Remotes that new backups should actually be delivered to.
    static var enabledRemotes: [Remote] { remotes.filter(\.enabled) }

    /// Flip a remote on or off without touching its config or credential.
    /// Change a remote's display name and/or its backup folder.
    ///
    /// The Keychain account key IS the remote's name, so a rename has to move
    /// the secret across — otherwise the server keeps its settings but
    /// silently loses its password, and the next backup fails with an auth
    /// error the user has no way to connect to what they just did.
    ///
    /// Connection fields and the password are editable too — a rotated
    /// credential or a NAS that moved to a new address are ordinary events,
    /// and forcing the user to delete and retype the whole server for either
    /// is worse than letting them fix the one field that changed. The caller
    /// is expected to re-run a connection test afterwards; nothing here
    /// assumes the new values work.
    @discardableResult
    static func update(name: String, newName: String? = nil,
                       newPath: String? = nil,
                       newParams: [String: String]? = nil,
                       newSecret: String? = nil) throws -> Remote? {
        var all = remotes
        guard let i = all.firstIndex(where: { $0.name == name }) else { return nil }

        if let newName {
            let trimmed = newName.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty,
                  trimmed.rangeOfCharacter(from: CharacterSet.alphanumerics.union(
                      CharacterSet(charactersIn: "-_")).inverted) == nil
            else { throw StoreError.invalidName }
            if trimmed != name {
                guard remote(named: trimmed) == nil else { throw StoreError.nameTaken(trimmed) }
                // Move the secret BEFORE the record, so a failure here can't
                // leave a renamed remote pointing at a credential that is
                // still filed under the old name.
                if let secret = loadSecret(for: name) {
                    storeSecret(secret, for: trimmed)
                    deleteSecret(for: name)
                }
                all[i].name = trimmed
            }
        }
        if let newPath {
            // [T-sftp-absolute-path] Preserve a leading `/` for SFTP — it is a
            // filesystem-absolute path there, and stripping it turned the
            // user's chosen `/srv/backup` into a home-relative `srv/backup`
            // that usually does not exist. Other backends keep the original
            // strip (see `join` for why WebDAV must).
            if RcloneBackendCatalog.usesAbsolutePaths(all[i].backend) {
                let trimmed = newPath.trimmingCharacters(in: .whitespaces)
                all[i].path = trimmed.count > 1 && trimmed.hasSuffix("/")
                    ? String(trimmed.dropLast())
                    : trimmed
            } else {
                // Stored fs-relative, exactly as the folder browser produces it.
                all[i].path = newPath.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
            }
        }
        if let newParams {
            // Merge rather than replace: the caller sends only the fields it
            // showed, and a backend may carry keys (provider, vendor) that
            // were set at creation and have no editor.
            for (k, v) in newParams {
                let trimmed = v.trimmingCharacters(in: .whitespaces)
                if trimmed.isEmpty { all[i].params.removeValue(forKey: k) }
                else { all[i].params[k] = trimmed }
            }
        }
        if newParams != nil || newSecret != nil {
            // Any change to how we connect invalidates the cached Fs.
            all[i].revision += 1
        }
        if let newSecret {
            // An empty string means "clear it" — that is how a server is
            // switched to anonymous access without deleting it.
            if newSecret.isEmpty { deleteSecret(for: all[i].name) }
            else { storeSecret(newSecret, for: all[i].name) }
        }
        remotes = all
        syncToRclone()
        logger.info("[Rclone] remote updated: \(name) -> \(all[i].name):/\(all[i].path)")
        return all[i]
    }

    static func setEnabled(_ name: String, _ on: Bool) {
        var all = remotes
        guard let i = all.firstIndex(where: { $0.name == name }) else { return }
        all[i].enabled = on
        remotes = all
        logger.info("[Rclone] remote '\(name)' \(on ? "enabled" : "disabled")")
    }

    /// `remote:` as rclone expects it.
    ///
    /// `nonisolated` on purpose: it derives a string from its argument and
    /// touches no shared state, so the uploader (which runs off the main
    /// actor, since it does blocking I/O) can call it directly.
    nonisolated static func fsSpec(for r: Remote) -> String { "\(r.configName):" }
}
