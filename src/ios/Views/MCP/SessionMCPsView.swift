//
//  SessionMCPsView.swift
//  MinisApp
//
//  Per-session MCP-server toggle sheet: override which MCP servers are active
//  in a chat. Mirrors SessionSkillsView.
//

import SwiftUI

struct SessionMCPsView: View {
    /// The real session id, or nil for a draft chat that hasn't sent its first
    /// message yet. Never coerced to "" (T-ios-session-skill-override-init-timing).
    let sessionId: String?
    /// Materializes the draft into a real session and returns its id; called
    /// lazily on first toggle. Mirrors SessionModelPicker / SessionSkillsView.
    var ensureSessionId: (() async -> String)?
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = MCPStore.shared
    @State private var searchQuery = ""
    /// Id reads/writes resolve against; seeded from `sessionId`, upgraded to the
    /// real id once a draft is materialized on first toggle.
    @State private var resolvedSessionId: String?

    init(sessionId: String?, ensureSessionId: (() async -> String)? = nil) {
        self.sessionId = sessionId
        self.ensureSessionId = ensureSessionId
        _resolvedSessionId = State(initialValue: sessionId)
    }

    /// Whether `server` is enabled for this session. With no resolved session
    /// (draft) reads the server's GLOBAL default so the sheet shows correct state.
    private func isEnabled(_ server: MCPServerConfig) -> Bool {
        if let sid = resolvedSessionId {
            return store.isEnabledForSession(server.id, sessionId: sid)
        }
        return server.enabled
    }

    /// Apply a toggle: materialize the draft session if needed, then write the
    /// override against the real id.
    private func setOverride(_ server: MCPServerConfig, _ enabled: Bool) {
        if let sid = resolvedSessionId {
            store.setSessionOverride(serverId: server.id, sessionId: sid, enabled: enabled)
            return
        }
        Task {
            guard let sid = await ensureSessionId?() else { return }
            await MainActor.run {
                resolvedSessionId = sid
                store.setSessionOverride(serverId: server.id, sessionId: sid, enabled: enabled)
            }
        }
    }

    /// Enable/Disable All: materialize the draft session ONCE, then write each.
    private func applyToAll(_ scope: [MCPServerConfig], enabled: Bool) {
        if let sid = resolvedSessionId {
            for server in scope {
                store.setSessionOverride(serverId: server.id, sessionId: sid, enabled: enabled)
            }
            return
        }
        Task {
            guard let sid = await ensureSessionId?() else { return }
            await MainActor.run {
                resolvedSessionId = sid
                for server in scope {
                    store.setSessionOverride(serverId: server.id, sessionId: sid, enabled: enabled)
                }
            }
        }
    }

    private var filteredServers: [MCPServerConfig] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return store.servers }
        return store.servers.filter {
            $0.id.lowercased().contains(q) || ($0.note ?? "").lowercased().contains(q)
        }
    }

    var body: some View {
        // Reference the version counter so SwiftUI refreshes on override changes.
        let _ = store.sessionOverrideVersion

        NavigationStack {
            List {
                if store.servers.isEmpty {
                    Section {
                        Text("No MCP servers configured. Add servers in Settings → MCP Integrations.")
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    }
                } else if filteredServers.isEmpty {
                    Section {
                        Text(AppLocalized("No MCP servers match \"\(searchQuery)\"."))
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    }
                } else {
                    Section(footer: Text("Toggles override the server's default for this session only.")) {
                        ForEach(filteredServers) { server in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(server.id).font(.body)
                                    if let note = server.note, !note.isEmpty {
                                        Text(note)
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(2)
                                    }
                                }
                                Spacer()
                                Toggle("", isOn: Binding(
                                    get: { isEnabled(server) },
                                    set: { setOverride(server, $0) }
                                ))
                                .labelsHidden()
                            }
                        }
                    }
                }
            }
            .navigationTitle("MCPs in Session")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchQuery, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: Text(AppLocalized("Search MCP servers")))
            .onAppear { store.load() }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !filteredServers.isEmpty {
                        let scope = filteredServers
                        let allEnabled = scope.allSatisfy { isEnabled($0) }
                        Button(allEnabled ? "Disable All" : "Enable All") {
                            let newValue = !allEnabled
                            applyToAll(scope, enabled: newValue)
                        }
                        .font(.subheadline)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
