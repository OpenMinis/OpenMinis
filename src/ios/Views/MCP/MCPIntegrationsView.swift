//
//  MCPIntegrationsView.swift
//  MinisApp
//
//  Management screen for MCP (Model Context Protocol) servers. Mirrors
//  SkillsManagementView: a list of servers with status dot + name + transport,
//  swipe-to-delete, and a toolbar "+" menu offering form-add or JSON-import.
//

import SwiftUI

struct MCPIntegrationsView: View {
    /// [T-mcp-oauth-deeplink] When set (minis://settings/mcp-servers/<id>),
    /// open this server's edit form on appear — that's where the Authorize
    /// button lives. Unknown/deleted ids fall through to the plain list.
    var initialEditServerId: String? = nil

    @ObservedObject private var store = MCPStore.shared

    @State private var editingServer: MCPServerConfig?
    @State private var showAddForm = false
    @State private var showJSONImport = false
    /// [T-mcp-tools-refresh] Server whose tools sheet is presented.
    @State private var toolsServer: MCPServerConfig?
    /// One-shot consumption of initialEditServerId.
    @State private var consumedInitialEdit = false

    var body: some View {
        List {
            if store.servers.isEmpty {
                emptyState
            } else {
                ForEach(store.servers) { server in
                    Button {
                        editingServer = server
                    } label: {
                        row(for: server)
                    }
                    .buttonStyle(.plain)
                    // [T-mcp-tools-refresh] Per-server tools entry: opens the
                    // sheet, which force-reconnects + re-pulls tools/list.
                    .contextMenu {
                        Button {
                            toolsServer = server
                        } label: {
                            Label(AppLocalized("Refresh Tools"), systemImage: "arrow.clockwise")
                        }
                    }
                    .swipeActions(edge: .leading, allowsFullSwipe: false) {
                        Button {
                            toolsServer = server
                        } label: {
                            Label(AppLocalized("Tools"), systemImage: "arrow.clockwise")
                        }
                        .tint(.blue)
                    }
                }
                .onDelete { offsets in
                    let ids = offsets.map { store.servers[$0].id }
                    for id in ids { store.delete(id: id) }
                }
            }
        }
        .navigationTitle(Text("MCP Integrations"))
        .navigationBarTitleDisplayMode(.inline)
        // [T-ios-mcp-list-reload-on-appear] Re-read servers.json on appear so a
        // server written by minis-mcp-cli after launch shows up without an app
        // restart (MCPStore.load() otherwise runs only in init). Mirrors
        // SkillsManagementView's .onAppear { store.reload() }.
        .onAppear {
            store.load()
            // [T-mcp-oauth-deeplink] Deep-linked server → open its edit form
            // once. Slightly deferred so the sheet presents after the push
            // animation settles (presenting during the transition is dropped
            // by UIKit on some iOS versions).
            if !consumedInitialEdit, let id = initialEditServerId {
                consumedInitialEdit = true
                if let server = store.servers.first(where: { $0.id == id }) {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                        editingServer = server
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        showAddForm = true
                    } label: {
                        Label(AppLocalized("Add Server"), systemImage: "plus")
                    }
                    Button {
                        showJSONImport = true
                    } label: {
                        Label(AppLocalized("Import JSON"), systemImage: "doc.text")
                    }
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showAddForm) {
            MCPFormSheet(server: nil)
        }
        .sheet(item: $editingServer) { server in
            MCPFormSheet(server: server)
        }
        .sheet(isPresented: $showJSONImport) {
            MCPJSONImportSheet()
        }
        .sheet(item: $toolsServer) { server in
            MCPToolsSheet(serverName: server.id)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "puzzlepiece.extension")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("No MCP Servers")
                .font(.headline)
            Text("Add an MCP server to give the agent extra tools. Tap + to add one manually or import a JSON config.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
        .listRowBackground(Color.clear)
    }

    private func row(for server: MCPServerConfig) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(server.enabled ? Color.green : Color.gray)
                .frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 3) {
                Text(server.id)
                    .font(.body)
                    .foregroundStyle(.primary)
                HStack(spacing: 4) {
                    Text(server.isHTTP ? "HTTP" : (server.isSTDIO ? "STDIO" : "—"))
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(server.transportSummary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }
}

// MARK: - Tools sheet [T-mcp-tools-refresh]

/// Live tools list for one server. Opening the sheet performs a FORCED
/// refresh (`minis-mcp-cli refresh <name>`: evict the daemon session,
/// re-handshake, tools/list) so tools the remote server added after we first
/// connected appear without restarting the app or the daemon.
struct MCPToolsSheet: View {
    let serverName: String
    @Environment(\.dismiss) private var dismiss
    @State private var tools: [MCPStore.MCPToolInfo] = []
    @State private var isLoading = false
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            List {
                if isLoading {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Reconnecting and fetching tools…")
                            .foregroundStyle(.secondary)
                    }
                } else if let errorText {
                    Section {
                        Label {
                            Text(errorText)
                        } icon: {
                            Image(systemName: "exclamationmark.triangle")
                                .foregroundStyle(.orange)
                        }
                        .font(.subheadline)
                    }
                } else if tools.isEmpty {
                    Text("The server reported no tools.")
                        .foregroundStyle(.secondary)
                } else {
                    Section(footer: Text("Fetched live from the server just now.")) {
                        ForEach(tools) { tool in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(tool.name)
                                    .font(.body.monospaced())
                                if !tool.description.isEmpty {
                                    Text(tool.description)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(3)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(Text(verbatim: serverName))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        Task { await refresh() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(isLoading)
                    .accessibilityLabel(Text("Refresh tools"))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(AppLocalized("Done")) { dismiss() }
                }
            }
            .task { await refresh() }
        }
    }

    private func refresh() async {
        isLoading = true
        errorText = nil
        do {
            tools = try await MCPStore.shared.refreshTools(server: serverName)
        } catch {
            errorText = error.localizedDescription
        }
        isLoading = false
    }
}
