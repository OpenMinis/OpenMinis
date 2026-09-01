//
//  MCPFormSheet.swift
//  MinisApp
//
//  Add / edit form for a single MCP server. Transport picker switches between
//  HTTP (URL + custom headers) and STDIO (command + args + env). SSE is treated
//  as an HTTP-family URL transport (the CLI's HTTP transport parses SSE replies).
//

import SwiftUI

struct MCPFormSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = MCPStore.shared
    @ObservedObject private var envVarStore = EnvVarStore.shared

    /// nil = add mode; non-nil = edit the existing server (id locked).
    let server: MCPServerConfig?

    private enum Transport: String, CaseIterable, Identifiable {
        case http = "HTTP"
        case sse = "SSE"
        case stdio = "STDIO"
        var id: String { rawValue }
    }

    @State private var name: String = ""
    @State private var transport: Transport = .http
    @State private var note: String = ""

    // HTTP
    @State private var url: String = ""
    @State private var headers: [KeyValue] = []

    // [T-mcp-static-oauth] Authorization (HTTP transport only)
    private enum AuthMode: String, CaseIterable, Identifiable {
        case none = "None"
        case oauthStatic = "OAuth (Client ID + Secret)"
        var id: String { rawValue }
    }
    @State private var authMode: AuthMode = .none
    @State private var oauthClientId: String = ""
    @State private var oauthClientSecret: String = ""
    @State private var oauthAuthEndpoint: String = ""
    @State private var oauthTokenEndpoint: String = ""
    @State private var oauthScopes: String = ""
    @State private var oauthRedirectURI: String = ""
    @State private var isAuthorized = false
    @State private var isAuthorizing = false
    @State private var oauthError: String?

    // STDIO
    @State private var command: String = ""
    @State private var argsText: String = ""
    @State private var env: [KeyValue] = []
    /// Optional per-server startup/handshake timeout (seconds). Empty = daemon
    /// default (60s). Kept as text so it round-trips even values the daemon will
    /// warn about; parsed to Int on save. [T-mcp-startup-timeout]
    @State private var startupTimeoutText: String = ""

    /// Row (by id) awaiting delete confirmation because it has content. Row ids
    /// are globally-unique UUIDs, so this single field unambiguously targets one
    /// row across both the headers and env editors.
    @State private var pendingDeleteId: KeyValue.ID?

    private struct KeyValue: Identifiable, Hashable {
        let id = UUID()
        var key: String = ""
        var value: String = ""
    }

    // Export: transient "Copied" toast + the temp .json file URL to share.
    @State private var copiedToast = false
    @State private var shareURL: URL?

    private var isEditing: Bool { server != nil }

    private var canSave: Bool {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        switch transport {
        case .http, .sse: return !url.trimmingCharacters(in: .whitespaces).isEmpty
        case .stdio: return !command.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(AppLocalized("Server name"), text: $name)
                        .disabled(isEditing)   // id is the key; renaming = delete+add
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Picker(AppLocalized("Transport"), selection: $transport) {
                        ForEach(Transport.allCases) { t in
                            Text(t.rawValue).tag(t)
                        }
                    }
                }

                if transport == .stdio {
                    stdioSection
                } else {
                    httpSection
                }

                Section(AppLocalized("Note (shown to the agent)")) {
                    TextField(AppLocalized("Optional description"), text: $note, axis: .vertical)
                        .lineLimit(1...4)
                }
            }
            .navigationTitle(Text(isEditing ? "Edit Server" : "Add Server"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            // Defer the side effect to the next runloop tick: on
                            // iPad the toolbar Menu's own dismissal otherwise races
                            // the pasteboard write / sheet present and swallows it,
                            // so the tap appeared to do nothing.
                            DispatchQueue.main.async { copyJSON() }
                        } label: {
                            Label(AppLocalized("Copy JSON"), systemImage: "doc.on.doc")
                        }
                        Button {
                            DispatchQueue.main.async { shareJSON() }
                        } label: {
                            Label(AppLocalized("Share JSON"), systemImage: "square.and.arrow.up")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .disabled(!canSave)   // need a name + a transport to export anything
                }
            }
            // Full-width primary Save pinned to the bottom, above the keyboard.
            .safeAreaInset(edge: .bottom) {
                Button(action: save) {
                    Text("Save")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!canSave)
                .padding(.horizontal)
                .padding(.vertical, 12)
                .background(.bar)
            }
            // Self-dismissing "Copied" capsule, mirroring FileBrowserView.
            .overlay(alignment: .bottom) {
                if copiedToast {
                    Label(AppLocalized("Copied"), systemImage: "checkmark.circle.fill")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(.black.opacity(0.8), in: Capsule())
                        .padding(.bottom, 32)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.3), value: copiedToast)
            .sheet(item: $shareURL) { url in
                MCPConfigShareSheet(url: url)
            }
            .onAppear(perform: populate)
        }
    }

    private var httpSection: some View {
        Group {
            Section(AppLocalized("Server URL")) {
                HStack {
                    TextField("https://…", text: $url)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                    // URL references join with no separator (a URL is a base +
                    // token segment); header/env values join with a space.
                    envReferenceMenu(into: $url, separator: "")
                }
            }
            Section(AppLocalized("Custom Headers")) {
                keyValueEditor($headers, keyPlaceholder: AppLocalized("Header"),
                               valuePlaceholder: AppLocalized("Value"),
                               showEnvPicker: true)
            }
            oauthSection
        }
    }

    // [T-mcp-static-oauth] Static OAuth (user-supplied Client ID + optional
    // Secret, PKCE Authorization Code flow). Secrets go to the Keychain, never
    // into servers.json; the in-guest transport reads tokens from the bridge
    // file MCPOAuthController materializes.
    @ViewBuilder
    private var oauthSection: some View {
        Section {
            Picker(AppLocalized("Authorization"), selection: $authMode) {
                ForEach(AuthMode.allCases) { m in
                    Text(m.rawValue).tag(m)
                }
            }
            if authMode == .oauthStatic {
                TextField(AppLocalized("Client ID"), text: $oauthClientId)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                SecureField(AppLocalized("Client Secret (optional)"), text: $oauthClientSecret)
                    // [T-provider-label-keyboard] Same AutoFill opt-out as the
                    // provider form. This is the textbook trigger shape: a
                    // "Client ID" TextField directly above a SecureField reads
                    // to iOS as a username/password pair, so AutoFill hangs the
                    // password bar on the Client ID field. Unreported so far,
                    // but structurally identical to the provider-Label report.
                    .textContentType(.oneTimeCode)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField(AppLocalized("Authorization Endpoint (https://…/authorize)"), text: $oauthAuthEndpoint)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                TextField(AppLocalized("Token Endpoint (https://…/token)"), text: $oauthTokenEndpoint)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                TextField(AppLocalized("Scopes (space-separated)"), text: $oauthScopes)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                // [T-mcp-oauth-loopback] Default is the standardized loopback
                // address — shown as the placeholder; leave empty to use it.
                // Editable for providers that need a different port/path or a
                // custom-scheme redirect (auto-falls back to
                // ASWebAuthenticationSession for non-localhost schemes).
                TextField(MCPOAuthController.defaultRedirectURI, text: $oauthRedirectURI)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)

                HStack {
                    if isAuthorized {
                        Label(AppLocalized("Authorized"), systemImage: "checkmark.seal.fill")
                            .foregroundStyle(.green)
                            .font(.subheadline)
                    } else {
                        Label(AppLocalized("Not authorized"), systemImage: "xmark.seal")
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    }
                    Spacer()
                    if isAuthorized {
                        Button(AppLocalized("Sign Out"), role: .destructive) {
                            MCPOAuthController.signOut(server: name.trimmingCharacters(in: .whitespaces))
                            isAuthorized = false
                        }
                        .font(.subheadline)
                    }
                    Button {
                        Task { await runAuthorize() }
                    } label: {
                        if isAuthorizing {
                            ProgressView()
                        } else {
                            Text(isAuthorized ? AppLocalized("Re-authorize") : AppLocalized("Authorize…"))
                        }
                    }
                    .disabled(isAuthorizing || !canSave || oauthClientId.trimmingCharacters(in: .whitespaces).isEmpty)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                if let oauthError {
                    Text(oauthError)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
        } header: {
            Text("Authentication")
        } footer: {
            if authMode == .oauthStatic {
                Text("Standard OAuth Authorization Code + PKCE with your own app credentials. Register \(MCPOAuthController.defaultRedirectURI) as the Redirect URI in your OAuth client (Google: create a \"Web application\" or \"Desktop\" client — localhost redirects are accepted). Client Secret and tokens are stored in the device Keychain only — they never sync to iCloud, so other devices authorize separately.")
            }
        }
    }

    /// Save first (the guest transport keys everything off the persisted
    /// server), then run the interactive flow.
    private func runAuthorize() async {
        oauthError = nil
        isAuthorizing = true
        defer { isAuthorizing = false }
        let config = buildConfig()
        store.add(config)
        MCPOAuthController.setClientSecret(oauthClientSecret, server: config.id)
        guard let oauth = config.oauth else { return }
        do {
            try await MCPOAuthController.shared.authorize(server: config.id, oauth: oauth)
            isAuthorized = true
        } catch {
            oauthError = error.localizedDescription
        }
    }

    private var stdioSection: some View {
        Group {
            Section(AppLocalized("Command")) {
                TextField("npx", text: $command)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                TextField(AppLocalized("Arguments (space-separated)"), text: $argsText)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }
            Section {
                keyValueEditor($env, keyPlaceholder: AppLocalized("Name"),
                               valuePlaceholder: AppLocalized("Value"),
                               showEnvPicker: true)
            } header: {
                Text("Environment Variables")
            } footer: {
                Text("Use the braces button to insert a reference to an App environment variable as $$NAME. Its value is filled in at runtime.")
            }
            Section {
                TextField(AppLocalized("Default 60"), text: $startupTimeoutText)
                    .keyboardType(.numberPad)
            } header: {
                Text("Startup timeout (seconds)")
            } footer: {
                Text("How long to wait for this server's first initialize. Raise it for slow-starting servers (e.g. uvx). Leave blank for the default (60s). Minis setting, not part of the MCP protocol.")
            }
        }
    }

    /// Names of the App-level environment variables the user can reference.
    private var appEnvKeys: [String] {
        envVarStore.entries.map(\.key)
    }

    private func keyValueEditor(_ pairs: Binding<[KeyValue]>, keyPlaceholder: String,
                                valuePlaceholder: String, showEnvPicker: Bool = false) -> some View {
        Group {
            ForEach(pairs) { $pair in
                HStack {
                    TextField(keyPlaceholder, text: $pair.key)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Divider()
                    TextField(valuePlaceholder, text: $pair.value)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    if showEnvPicker {
                        envReferenceMenu(into: $pair.value)
                    }
                    // Explicit per-row delete: empty rows go immediately; rows
                    // with content ask first (swipe-to-delete still works too).
                    Button {
                        requestDelete(pair, in: pairs)
                    } label: {
                        Image(systemName: "minus.circle.fill")
                            .foregroundStyle(.red)
                    }
                    .buttonStyle(.plain)   // keep it tap-isolated inside the row
                    .frame(minWidth: 44, minHeight: 44, alignment: .trailing)
                    .accessibilityLabel(Text("Delete row"))
                }
            }
            .onDelete { offsets in
                pairs.wrappedValue.remove(atOffsets: offsets)
            }
            Button {
                pairs.wrappedValue.append(KeyValue())
            } label: {
                Label(AppLocalized("Add"), systemImage: "plus.circle")
            }
        }
        .confirmationDialog(
            Text("Delete this environment variable?"),
            isPresented: deleteConfirmBinding(for: pairs),
            titleVisibility: .visible
        ) {
            Button(AppLocalized("Delete"), role: .destructive) {
                confirmDelete(in: pairs)
            }
            Button(AppLocalized("Cancel"), role: .cancel) {
                pendingDeleteId = nil
            }
        }
    }

    /// Delete `pair` from `pairs`: immediately if blank, otherwise stage it for
    /// the confirmation dialog.
    private func requestDelete(_ pair: KeyValue, in pairs: Binding<[KeyValue]>) {
        let hasContent = !pair.key.trimmingCharacters(in: .whitespaces).isEmpty
            || !pair.value.trimmingCharacters(in: .whitespaces).isEmpty
        if hasContent {
            pendingDeleteId = pair.id
        } else {
            pairs.wrappedValue.removeAll { $0.id == pair.id }
        }
    }

    /// A presentation binding that is true only while the pending-delete row
    /// belongs to this editor's `pairs`, so the dialog fires on the right list.
    private func deleteConfirmBinding(for pairs: Binding<[KeyValue]>) -> Binding<Bool> {
        Binding(
            get: {
                guard let id = pendingDeleteId else { return false }
                return pairs.wrappedValue.contains { $0.id == id }
            },
            set: { presented in
                if !presented { pendingDeleteId = nil }
            }
        )
    }

    private func confirmDelete(in pairs: Binding<[KeyValue]>) {
        guard let id = pendingDeleteId else { return }
        pairs.wrappedValue.removeAll { $0.id == id }
        pendingDeleteId = nil
    }

    /// Per-field menu that inserts a `$$NAME` reference to an App environment
    /// variable into the target value. The `$$` form is the explicit
    /// "reference" syntax the CLI expander resolves at runtime (same value as
    /// `$NAME`); inserting it here keeps the literal/reference distinction visible.
    /// `separator` joins onto existing non-empty content — a space for header /
    /// env values (so refs/literals don't fuse into one token), empty for the URL
    /// (a base + token segment is contiguous).
    private func envReferenceMenu(into value: Binding<String>, separator: String = " ") -> some View {
        Menu {
            if appEnvKeys.isEmpty {
                Text("No App environment variables — add them in Settings → Environment Variables")
            } else {
                ForEach(appEnvKeys, id: \.self) { key in
                    Button("$$\(key)") {
                        insertEnvReference(key, into: value, separator: separator)
                    }
                }
            }
        } label: {
            Image(systemName: "curlybraces")
                .foregroundStyle(.secondary)
        }
        .accessibilityLabel(Text("Insert App environment variable reference"))
    }

    /// Append the `$$KEY` reference to the field, never overwriting existing
    /// content, so the user can insert several variables in a row. A non-empty
    /// `separator` is added before the reference only when the field already has
    /// content that doesn't end in whitespace, so two refs (or a literal + ref)
    /// don't fuse into one unreadable token.
    private func insertEnvReference(_ key: String, into value: Binding<String>, separator: String = " ") {
        let reference = "$$\(key)"
        let current = value.wrappedValue
        if current.isEmpty || separator.isEmpty || current.last?.isWhitespace == true {
            value.wrappedValue = current + reference
        } else {
            value.wrappedValue = current + separator + reference
        }
    }

    private func populate() {
        guard let server, name.isEmpty, url.isEmpty, command.isEmpty else { return }
        name = server.id
        note = server.note ?? ""
        if server.isSTDIO {
            transport = .stdio
            command = server.command ?? ""
            argsText = (server.args ?? []).joined(separator: " ")
            env = (server.env ?? [:]).map { KeyValue(key: $0.key, value: $0.value) }
            startupTimeoutText = server.startupTimeoutSeconds.map(String.init) ?? ""
        } else {
            transport = .http
            url = server.url ?? ""
            headers = (server.headers ?? [:]).map { KeyValue(key: $0.key, value: $0.value) }
            // [T-mcp-static-oauth]
            if let oauth = server.oauth {
                authMode = .oauthStatic
                oauthClientId = oauth.clientId
                oauthAuthEndpoint = oauth.authorizationEndpoint
                oauthTokenEndpoint = oauth.tokenEndpoint
                oauthScopes = oauth.scopes ?? ""
                oauthRedirectURI = oauth.redirectURI ?? ""
                // [T-mcp-cli-oauth-flags] Pull in a CLI-seeded secret before
                // reading the Keychain, so `minis-mcp-cli add
                // --oauth-client-secret` shows up pre-filled here.
                MCPOAuthController.importPendingSecretIfAny(server: server.id)
                oauthClientSecret = MCPOAuthController.clientSecret(server: server.id) ?? ""
                isAuthorized = MCPOAuthController.isAuthorized(server: server.id)
            }
        }
    }

    /// Build an MCPServerConfig from the form's CURRENT values, so both Save and
    /// Export reflect unsaved edits (what you see is what you copy).
    private func buildConfig() -> MCPServerConfig {
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        var config = MCPServerConfig(id: trimmedName, enabled: server?.enabled ?? true)
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        config.note = trimmedNote.isEmpty ? nil : trimmedNote

        switch transport {
        case .http, .sse:
            config.url = url.trimmingCharacters(in: .whitespaces)
            let hdrs = dict(from: headers)
            config.headers = hdrs.isEmpty ? nil : hdrs
            // [T-mcp-static-oauth] Non-secret oauth config rides servers.json;
            // the secret is Keychain-only (written on Save/Authorize).
            if authMode == .oauthStatic {
                var oauth = MCPOAuthConfig()
                oauth.clientId = oauthClientId.trimmingCharacters(in: .whitespaces)
                oauth.authorizationEndpoint = oauthAuthEndpoint.trimmingCharacters(in: .whitespaces)
                oauth.tokenEndpoint = oauthTokenEndpoint.trimmingCharacters(in: .whitespaces)
                let scopes = oauthScopes.trimmingCharacters(in: .whitespaces)
                oauth.scopes = scopes.isEmpty ? nil : scopes
                let redirect = oauthRedirectURI.trimmingCharacters(in: .whitespaces)
                oauth.redirectURI = redirect.isEmpty ? nil : redirect
                config.oauth = oauth
            }
        case .stdio:
            config.command = command.trimmingCharacters(in: .whitespaces)
            let parsedArgs = argsText.split(separator: " ").map(String.init)
            config.args = parsedArgs.isEmpty ? nil : parsedArgs
            let envDict = dict(from: env)
            config.env = envDict.isEmpty ? nil : envDict
            let trimmedTimeout = startupTimeoutText.trimmingCharacters(in: .whitespaces)
            config.startupTimeoutSeconds = trimmedTimeout.isEmpty ? nil : Int(trimmedTimeout)
        }
        return config
    }

    private func save() {
        let config = store_addAndSyncSecret()
        _ = config
        dismiss()
    }

    /// Persist the config, keep the Keychain secret in lockstep, and refresh
    /// the guest token bridge if this server is already authorized (so an
    /// edited secret/endpoint reaches the daemon's refresh path).
    @discardableResult
    private func store_addAndSyncSecret() -> MCPServerConfig {
        let config = buildConfig()
        store.add(config)
        if authMode == .oauthStatic {
            MCPOAuthController.setClientSecret(oauthClientSecret, server: config.id)
            if let oauth = config.oauth {
                MCPOAuthController.refreshBridgeIfAuthorized(server: config.id, oauth: oauth)
            }
        }
        return config
    }

    // MARK: - Export

    /// Copy the current form's config as standard mcpServers JSON to the clipboard.
    private func copyJSON() {
        guard let json = store.exportServerJSON(buildConfig()) else { return }
        UIPasteboard.general.string = json
        copiedToast = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { copiedToast = false }
    }

    /// Write the config to a temp `<id>.json` file and present the share sheet.
    private func shareJSON() {
        let config = buildConfig()
        guard let json = store.exportServerJSON(config) else { return }
        let safeName = config.id.isEmpty ? "mcp-server" : config.id
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(safeName).json")
        do {
            try json.data(using: .utf8)?.write(to: fileURL, options: .atomic)
            shareURL = fileURL
        } catch {
            AppLogger(category: "MCPFormSheet").error("Failed to write export temp file: \(error.localizedDescription)")
        }
    }

    private func dict(from pairs: [KeyValue]) -> [String: String] {
        var out: [String: String] = [:]
        for p in pairs {
            let k = p.key.trimmingCharacters(in: .whitespaces)
            guard !k.isEmpty else { continue }
            out[k] = p.value
        }
        return out
    }
}

/// Share sheet for the exported `<id>.json` config file. Mirrors ContentView's
/// ShareSheet, including the Mac Catalyst UTI sanitization guard.
private struct MCPConfigShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let safeURL = MinisShareSheet.sanitizedShareURL(url) ?? url
        return UIActivityViewController(activityItems: [safeURL], applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
