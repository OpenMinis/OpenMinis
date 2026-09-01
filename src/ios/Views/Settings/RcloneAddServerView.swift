import SwiftUI

private let logger = AppLogger(category: "Rclone")

/// Add a network drive as a backup destination.
///
/// Three steps, in the order the user asked for:
///   1. pick a type (SMB / WebDAV / SFTP / S3 / FTP)
///   2. enter address + credentials, and connect
///   3. browse into a folder and confirm "Save Here"
///
/// Step 3 exists because a server's root is rarely where backups belong. The
/// user drills in the same way they would in a file manager, and the folder
/// they are standing in becomes the destination.
struct RcloneAddServerView: View {

    var onAdded: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var backend: RcloneBackendCatalog.Backend?
    @State private var values: [String: String] = [:]
    @State private var displayName = ""

    @State private var isConnecting = false
    @State private var errorText: String?
    /// Set when a connection failed specifically because the server's TLS
    /// certificate could not be verified — the one case where offering to
    /// continue anyway is reasonable.
    @State private var certificateRejected = false
    /// New-folder prompt for the directory browser.
    @State private var showNewFolder = false
    @State private var newFolderName = ""
    /// User's decision to trust this server's certificate anyway.
    @State private var allowInsecureTLS = false

    /// Non-nil once the connection works — this is what unlocks browsing.
    @State private var connectedRemote: RcloneRemoteStore.Remote?
    @State private var currentDir = ""
    /// [T-sftp-absolute-path] Manual path entry, so a directory the browser
    /// cannot walk to is still reachable.
    @State private var showPathEditor = false
    @State private var pathInput = ""

    /// How the current location reads in the header.
    ///
    /// [T-sftp-absolute-path] An empty `currentDir` is NOT `/` on SFTP: rclone
    /// resolves `remote:` to the login user's HOME, so labelling it `/` told
    /// the user they were at the filesystem root while showing them the
    /// contents of their home directory — the exact confusion behind
    /// "选不到根目录". `~` says what it actually is. URL-based backends
    /// (WebDAV, S3, …) keep `/`, where an empty path really is the configured
    /// root.
    private var displayPath: String {
        let isSFTP = RcloneBackendCatalog.usesAbsolutePaths(backend?.type ?? "")
        if currentDir.isEmpty { return isSFTP ? "~" : "/" }
        if isSFTP { return currentDir.hasPrefix("/") ? currentDir : "~/\(currentDir)" }
        return "/\(currentDir)"
    }
    @State private var entries: [DirEntry] = []
    @State private var isListing = false

    private struct DirEntry: Identifiable {
        var id: String { path }
        let name: String
        let path: String
        let isDir: Bool
    }

    var body: some View {
        NavigationStack {
            Form {
                if connectedRemote == nil {
                    typeSection
                    if backend != nil {
                        detailsSection
                        connectSection
                    }
                } else {
                    browseSection
                }

                // Connect-time errors stay inline: the user is looking at
                // the form they just filled in, and the message belongs next
                // to it. Errors raised while BROWSING (mkdir, listing) go to
                // the HUD instead — see backupHUD below — because the folder
                // list can be long enough to push an inline row off screen.
                if let errorText, connectedRemote == nil {
                    Section {
                        Text(errorText)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .backupHUD(Binding(
                get: { connectedRemote == nil ? nil : errorText },
                set: { if $0 == nil { errorText = nil } }))
            .navigationTitle(connectedRemote == nil ? "Add Server" : "Choose Folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { cancelAndDismiss() }
                }
                // The confirming action belongs in the top-right slot, where
                // iOS puts Done/Save everywhere else. As a row at the bottom
                // of a long directory listing it moved with the scroll and
                // could sit off-screen entirely in a folder with many
                // entries — the one control the screen exists to offer.
                if connectedRemote != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save Here") { saveHere() }
                            .disabled(isListing)
                    }
                }
            }
            .alert("New Folder", isPresented: $showNewFolder) {
                TextField("Folder name", text: $newFolderName)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Cancel", role: .cancel) { newFolderName = "" }
                // [T-backup-newfolder-create-missing] NO `.disabled()` here.
                // Alert buttons are not regular views: SwiftUI renders an
                // alert's actions through UIAlertController, and a disabled
                // action is OMITTED ENTIRELY rather than greyed out. The name
                // field starts empty, so the guard was true the moment the
                // alert appeared and the Create button simply did not exist —
                // the user saw a New Folder dialog offering only Cancel
                // (reported verbatim: "只有取消", with a screenshot).
                //
                // The sibling dialog in BackupDestinationDetailView never had
                // the modifier and always showed Create, which is why leaving
                // and re-entering "fixed" it: re-entry goes through that
                // screen, not this one.
                //
                // Empty input is rejected inside the action instead, where it
                // costs nothing — createFolder() already trims and ignores a
                // blank name.
                Button("Create") { Task { await createFolder() } }
            } message: {
                Text("Created inside \(displayPath).")
            }
            // [T-sftp-absolute-path] Go-to-path. No `.disabled()` on these
            // buttons: an alert's actions are UIAlertActions, and a disabled
            // one is omitted entirely rather than greyed out (that is what
            // removed the Create button in the New Folder dialog).
            .alert("Go to Path", isPresented: $showPathEditor) {
                TextField("Path", text: $pathInput)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Cancel", role: .cancel) { }
                Button("Go") {
                    let target = Self.normalizedInputPath(pathInput,
                                                          isSFTP: RcloneBackendCatalog.usesAbsolutePaths(backend?.type ?? ""))
                    Task { await list(dir: target) }
                }
            } message: {
                Text("Type a folder path to jump straight to it, including one outside the starting directory.")
            }
        }
    }

    // MARK: - Step 1

    private var typeSection: some View {
        Section {
            ForEach(RcloneBackendCatalog.all) { b in
                Button {
                    backend = b
                    values = [:]
                    if displayName.isEmpty { displayName = defaultName(for: b) }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: b.icon)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 28, height: 28)
                            .background(backend?.type == b.type ? Color.blue : Color.gray,
                                        in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(b.title).foregroundStyle(.primary)
                            Text(b.subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if backend?.type == b.type {
                            Image(systemName: "checkmark").foregroundStyle(.blue)
                        }
                    }
                }
            }
        } header: {
            Text("Type")
        }
    }

    // MARK: - Step 2

    @ViewBuilder
    private var detailsSection: some View {
        if let b = backend {
            Section {
                TextField("Name", text: $displayName)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            } header: {
                Text("Name")
            } footer: {
                Text("Shown in the destination list.")
            }

            Section {
                ForEach(b.fields) { f in
                    // The field's NAME is shown above the input rather than
                    // used as its placeholder. Using the placeholder alone
                    // meant a row read "backups" with nothing saying that was
                    // the Share — an example value looks like a label, and a
                    // label looks like a value. Name on top, example inside,
                    // one line of plain English underneath for the terms a
                    // user has no way to guess.
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(f.label)
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(.secondary)
                            if f.isOptional {
                                Text("Optional")
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        if f.isSecret {
                            SecureField(f.placeholder.isEmpty ? f.label : f.placeholder,
                                        text: binding(for: f.key))
                                .textContentType(.password)
                        } else {
                            TextField(f.placeholder.isEmpty ? f.label : f.placeholder,
                                      text: binding(for: f.key))
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .keyboardType(keyboardType(f.keyboard))
                        }
                        if !f.hint.isEmpty {
                            Text(f.hint)
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .padding(.vertical, 2)
                }
            } header: {
                Text("Connection")
            } footer: {
                // Say where the password goes. "Stored in the Keychain" is the
                // difference between this and typing a password into a text
                // file, and the user cannot see that from the form itself.
                Text("Passwords are stored in the iOS Keychain on this device only — never in the backup file.")
            }
        }
    }

    private var connectSection: some View {
        Section {
            Button {
                Task { await connect() }
            } label: {
                // Badged like every other action row in the backup screens
                // (Test Connection, Remove Destination, Choose from Files…):
                // a 21pt tinted circle with a white glyph, via
                // `BackupActionIcon`. This row was the odd one out as bare
                // text.
                //
                // The spinner occupies the SAME leading slot as the icon
                // rather than being pushed in front of the label, so the row's
                // text does not shift sideways when a connection starts.
                Label {
                    Text(isConnecting ? "Connecting…" : "Connect")
                        .foregroundStyle(isConnecting ? AnyShapeStyle(.secondary)
                                                      : AnyShapeStyle(.tint))
                } icon: {
                    if isConnecting {
                        ProgressView().frame(width: 21, height: 21)
                    } else {
                        BackupActionIcon(systemName: "bolt.horizontal.fill", tint: .blue)
                    }
                }
            }
            .disabled(isConnecting || !requiredFieldsFilled)

            if !requiredFieldsFilled && !isConnecting {
                Text("Fill in the required fields to continue.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            // Shown only after the server's certificate was actually
            // rejected. Not offered up-front on purpose — a checkbox that
            // says "don't verify certificates" sitting next to a password
            // field invites people to switch off a protection they never
            // needed to.
            if certificateRejected {
                Toggle(isOn: $allowInsecureTLS) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Trust this certificate")
                        Text("The server's certificate isn't signed by a known authority — usual for a NAS using its own certificate.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                if allowInsecureTLS {
                    Text("The connection stays encrypted, but its identity isn't verified. Only do this on a network and server you trust.")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }
        }
    }

    // MARK: - Step 3

    @ViewBuilder
    private var browseSection: some View {
        Section {
            if !currentDir.isEmpty {
                Button {
                    let parent = (currentDir as NSString).deletingLastPathComponent
                    Task { await list(dir: parent == "." ? "" : parent) }
                } label: {
                    Label("Up one level", systemImage: "arrow.up.left")
                }
            }
            if isListing {
                HStack { ProgressView(); Text("Loading…").foregroundStyle(.secondary) }
            } else if folderEntries.isEmpty {
                Text("No folders here.").foregroundStyle(.secondary)
            } else {
                // Folders only. This step picks a DESTINATION, and the files
                // already in a backups directory — often hundreds of them —
                // are un-tappable rows that push the folders being navigated
                // off the screen.
                ForEach(folderEntries) { e in
                    Button {
                        Task { await list(dir: e.path) }
                    } label: {
                        HStack {
                            Label(e.name, systemImage: "folder.fill")
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                    }
                }
            }
        } header: {
            HStack(spacing: 8) {
                // A deep path is easily wider than the screen. Truncating it
                // hides exactly the tail the user needs — the folder they are
                // standing in — so it scrolls instead, and the capsule makes
                // it read as one addressable object rather than loose text.
                // [T-sftp-absolute-path] Tapping the path opens an editor, so a
                // location that cannot be reached by clicking through folders
                // is still reachable: on SFTP the browser starts in the login
                // user's HOME, and everything above it — `/srv/backup` and the
                // like — was previously unreachable with no way to type it.
                Button {
                    pathInput = displayPath
                    showPathEditor = true
                } label: {
                    ScrollView(.horizontal, showsIndicators: false) {
                        Text(displayPath)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color(.secondarySystemFill), in: Capsule())
                    }
                }
                .buttonStyle(.plain)
                // Pinned so it stays reachable however long the path is.
                Button {
                    newFolderName = ""
                    showNewFolder = true
                } label: {
                    Label("New Folder", systemImage: "folder.badge.plus")
                        .labelStyle(.iconOnly)
                        .font(.body)
                }
                .buttonStyle(.borderless)
                .disabled(isListing)
            }
            .textCase(nil)
        } footer: {
            Text("Browse to the folder where backups should be saved, then tap Save Here.")
        }
    }

    // MARK: - Actions

    /// Only directories are navigable when choosing where backups go.
    private var folderEntries: [DirEntry] { entries.filter(\.isDir) }

    private func binding(for key: String) -> Binding<String> {
        Binding(get: { values[key] ?? "" }, set: { values[key] = $0 })
    }

    private var requiredFieldsFilled: Bool {
        guard let b = backend, !displayName.trimmingCharacters(in: .whitespaces).isEmpty
        else { return false }
        return b.fields.allSatisfy { f in
            f.isOptional || !(values[f.key] ?? "").trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private func defaultName(for b: RcloneBackendCatalog.Backend) -> String {
        var base = b.type
        var n = 1
        while RcloneRemoteStore.remote(named: base) != nil {
            n += 1
            base = "\(b.type)\(n)"
        }
        return base
    }

    /// Register the remote and prove it actually connects.
    ///
    /// The remote is added first because rclone can only talk to something in
    /// its config — but if the connection fails it is removed again, so a
    /// server that never worked does not linger in the destination list.
    private func connect() async {
        guard let b = backend else { return }
        isConnecting = true
        errorText = nil
        defer { isConnecting = false }

        let name = displayName.trimmingCharacters(in: .whitespaces)
        let secretKey = RcloneBackendCatalog.secretField(for: b.type)
        var params: [String: String] = [:]
        for f in b.fields where !f.isSecret {
            let v = (values[f.key] ?? "").trimmingCharacters(in: .whitespaces)
            if !v.isEmpty { params[f.key] = v }
        }

        do {
            try RcloneRemoteStore.add(
                name: name, backend: b.type, params: params,
                secret: secretKey.flatMap { values[$0] }, path: "",
                allowInsecureTLS: allowInsecureTLS)
            RcloneRemoteStore.syncToRclone()

            guard let r = RcloneRemoteStore.remote(named: name) else { return }
            // Listing the root is the cheapest honest proof that the address,
            // credentials and protocol are all right.
            _ = try await Task.detached(priority: .userInitiated) {
                try RcloneBridge.rpc("operations/list",
                                     ["fs": RcloneRemoteStore.fsSpec(for: r), "remote": ""])
            }.value

            connectedRemote = r
            await list(dir: "")
        } catch {
            RcloneRemoteStore.remove(name: name)
            // A certificate failure is offered a way forward instead of being
            // a dead end: a NAS with its own certificate is ordinary, and the
            // alternative users reach for otherwise is plain HTTP, which is
            // strictly worse than a trusted-once TLS connection.
            certificateRejected = isCertificateError(error) && !allowInsecureTLS
            errorText = friendlyError(error)
            logger.error("[Rclone] connect failed: \(error.localizedDescription)")
        }
    }

    private func list(dir: String) async {
        guard let r = connectedRemote else { return }
        isListing = true
        errorText = nil
        defer { isListing = false }
        do {
            let out = try await Task.detached(priority: .userInitiated) {
                try RcloneBridge.rpc("operations/list",
                                     ["fs": RcloneRemoteStore.fsSpec(for: r), "remote": dir])
            }.value
            let list = (out["list"] as? [[String: Any]]) ?? []
            entries = list.compactMap { e in
                guard let name = e["Name"] as? String else { return nil }
                // [T-sftp-absolute-path] `dir == "/"` would otherwise build
                // `//name`. Joining on a normalised base keeps the absolute
                // form (`/etc` → `/etc/apk`) without doubling the separator.
                let child: String = {
                    if dir.isEmpty { return name }
                    if dir == "/" { return "/\(name)" }
                    return "\(dir)/\(name)"
                }()
                return DirEntry(name: name, path: child,
                                isDir: (e["IsDir"] as? Bool) ?? false)
            }
            .sorted { ($0.isDir ? 0 : 1, $0.name.lowercased()) < ($1.isDir ? 0 : 1, $1.name.lowercased()) }
            currentDir = dir
        } catch {
            errorText = friendlyError(error)
        }
    }

    /// Create a subdirectory in the folder currently being browsed.
    ///
    /// Exists because the destination often does not exist yet: a user
    /// pointing at a NAS share usually wants "…/Minis Backups", and without
    /// this the only way to get one was to leave the app, make the folder in
    /// another client, and come back.
    private func createFolder() async {
        let name = newFolderName.trimmingCharacters(in: .whitespaces)
        newFolderName = ""
        guard !name.isEmpty, let r = connectedRemote else { return }
        // A name with a separator would silently create nested folders (or
        // escape the current directory), which is not what the field implies.
        guard !name.contains("/") else {
            errorText = AppLocalized("A folder name can't contain “/”.")
            return
        }
        // [T-sftp-absolute-path] Same `/` + name → `//name` guard as `list`.
        let path: String = {
            if currentDir.isEmpty { return name }
            if currentDir == "/" { return "/\(name)" }
            return "\(currentDir)/\(name)"
        }()
        errorText = nil
        do {
            _ = try await Task.detached(priority: .userInitiated) {
                try RcloneBridge.rpc("operations/mkdir", [
                    "fs": RcloneRemoteStore.fsSpec(for: r), "remote": path,
                ])
            }.value
            // Land inside the new folder: creating one is nearly always the
            // step before choosing it.
            await list(dir: path)
        } catch {
            errorText = friendlyError(error)
        }
    }

    private func saveHere() {
        guard var r = connectedRemote else { return }
        r.path = currentDir
        var all = RcloneRemoteStore.remotes
        if let i = all.firstIndex(where: { $0.name == r.name }) {
            all[i] = r
            RcloneRemoteStore.remotes = all
        }
        logger.info("[Rclone] destination saved: \(r.name):/\(currentDir)")
        onAdded()
        dismiss()
    }

    /// Leaving mid-flow must not strand a half-configured remote in the list.
    private func cancelAndDismiss() {
        if let r = connectedRemote, r.path.isEmpty {
            RcloneRemoteStore.remove(name: r.name)
        }
        dismiss()
    }

    /// Whether this failure was a TLS trust problem rather than a wrong
    /// address or password — those need different remedies.
    private func isCertificateError(_ error: Error) -> Bool {
        let raw = error.localizedDescription.lowercased()
        return raw.contains("certificate")
            || raw.contains("x509")
            || raw.contains("unknown authority")
            || raw.contains("tls")
    }

    /// rclone's errors are written for a terminal. Surface the useful part.
    /// [T-sftp-absolute-path] Turn what the user typed into the `remote` value
    /// rclone expects.
    ///
    /// `~` and `~/x` are how the header spells "the login user's home" on
    /// SFTP, and rclone expresses that as the EMPTY path (`remote:` already
    /// resolves there), so they are translated rather than sent literally.
    /// A leading `/` is preserved for SFTP — that is the whole point, it means
    /// filesystem-absolute — and stripped for every other backend, where it
    /// would escape the folder in the fs spec (the WebDAV bug 1dec9e650 fixed).
    static func normalizedInputPath(_ raw: String, isSFTP: Bool) -> String {
        var s = raw.trimmingCharacters(in: .whitespaces)
        if s == "~" { return "" }
        if s.hasPrefix("~/") { s = String(s.dropFirst(2)) }
        // A trailing slash is never meaningful for a directory here.
        while s.count > 1 && s.hasSuffix("/") { s = String(s.dropLast()) }
        if isSFTP {
            return s == "/" ? "/" : s
        }
        return s.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private func friendlyError(_ error: Error) -> String {
        let raw = error.localizedDescription
        if raw.localizedCaseInsensitiveContains("x509")
            || raw.localizedCaseInsensitiveContains("unknown authority")
            || raw.localizedCaseInsensitiveContains("certificate") {
            return AppLocalized("Couldn't verify the server's security certificate.")
        }
        if raw.localizedCaseInsensitiveContains("authentication")
            || raw.localizedCaseInsensitiveContains("permission denied")
            || raw.localizedCaseInsensitiveContains("401") {
            return AppLocalized("Wrong username or password.")
        }
        if raw.localizedCaseInsensitiveContains("no such host")
            || raw.localizedCaseInsensitiveContains("connection refused")
            || raw.localizedCaseInsensitiveContains("timeout") {
            return AppLocalized("Couldn't reach the server. Check the address and that you're on the same network.")
        }
        return raw
    }

    private func keyboardType(_ k: RcloneBackendCatalog.Field.KeyboardKind) -> UIKeyboardType {
        switch k {
        case .url: return .URL
        case .numeric: return .numberPad
        case .email: return .emailAddress
        case .default: return .default
        }
    }
}
