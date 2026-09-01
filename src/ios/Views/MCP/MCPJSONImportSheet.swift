//
//  MCPJSONImportSheet.swift
//  MinisApp
//
//  Paste an MCP server config (Claude-Desktop mcpServers JSON or compatible
//  variants), preview the parsed servers, then commit. Mirrors the Skills
//  import UX.
//

import SwiftUI

struct MCPJSONImportSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = MCPStore.shared

    @State private var text: String = ""
    @State private var parsed: [MCPServerConfig] = []
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section(AppLocalized("Paste MCP JSON")) {
                    TextEditor(text: $text)
                        .font(.system(.footnote, design: .monospaced))
                        .frame(minHeight: 220)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: text) { _ in
                            parsed = []
                            errorMessage = nil
                        }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }

                if !parsed.isEmpty {
                    Section(AppLocalized("Preview")) {
                        Text(String(format: AppLocalized("Found %d server(s)"), parsed.count))
                            .font(.subheadline.weight(.semibold))
                        ForEach(parsed) { server in
                            HStack(spacing: 8) {
                                Image(systemName: server.isSTDIO ? "terminal" : "globe")
                                    .foregroundStyle(.secondary)
                                Text(server.id)
                                Spacer()
                                Text(server.isSTDIO ? "STDIO" : "HTTP")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle(Text("Import JSON"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalized("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if parsed.isEmpty {
                        Button(AppLocalized("Parse")) { parse() }
                            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    } else {
                        Button(AppLocalized("Import")) { commit() }
                    }
                }
            }
        }
    }

    private func parse() {
        do {
            parsed = try store.parseImport(text)
            errorMessage = nil
        } catch {
            parsed = []
            errorMessage = error.localizedDescription
        }
    }

    private func commit() {
        store.commitImport(parsed)
        dismiss()
    }
}
