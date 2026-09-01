import SwiftUI
import UIKit

private let sheetLogger = AppLogger(category: "WebAppAddSheet")

/// "Add to Home Screen" sheet. Driven by a host file URL of an `.html` /
/// `.htm` file the user picked from one of the three entry points.
///
/// Flow:
///   1. Classify the host URL into `(scope, scopeContext, htmlPath)` and
///      persist a `WebAppShortcut` row so deep-link return can re-resolve
///      the file by id (or by session+path).
///   2. Build an `https://openminis.app/launch.html?…` URL whose query
///      encodes the scope-prefixed path + session + a category-driven
///      icon key, with the title in the fragment.
///   3. `UIApplication.shared.open(url)` hands the user off to Safari so
///      they can use Share → Add to Home Screen. The pinned tile loads
///      the launcher; when launched in standalone mode it deep-links back
///      to `minis://open?session=…&path=…`, which `DeepLinkRouter` routes
///      into the immersive WebView.
struct WebAppAddToHomeSheet: View {
    let htmlURL: URL
    let sourceSessionId: String?

    @Environment(\.dismiss) private var dismiss

    @State private var classification: WebAppPathClassifier.Classified?
    @State private var unsupportedScope = false
    @State private var titleInput: String = ""
    @State private var category: LauncherCategory = .other

    @State private var opening = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                if unsupportedScope {
                    unsupportedSection
                } else {
                    previewSection
                    titleSection
                    categorySection
                    if let sid = sourceSessionId,
                       classification?.scope != .shared,
                       classification?.scope != .mount {
                        Section {
                            HStack {
                                Image(systemName: "link").foregroundStyle(.secondary)
                                Text("Associated with session")
                                Spacer()
                                Text(sid.prefix(8))
                                    .font(.caption.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    if let err = errorMessage {
                        Section {
                            Text(err).foregroundStyle(.red).font(.caption)
                        }
                    }
                    Section {
                        Button {
                            saveAndOpenLauncher()
                        } label: {
                            HStack {
                                Spacer()
                                if opening {
                                    ProgressView().tint(.white)
                                } else {
                                    Text("Continue in Safari")
                                        .fontWeight(.semibold)
                                }
                                Spacer()
                            }
                        }
                        .disabled(opening || titleInput.trimmingCharacters(in: .whitespaces).isEmpty)
                        .listRowBackground(Color.accentColor)
                        .foregroundStyle(.white)
                    } footer: {
                        Text("Safari will open the launcher page. Tap the Share button → Add to Home Screen to pin the icon.")
                    }
                }
            }
            .navigationTitle("Add to Home Screen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .task { await onAppear() }
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private var unsupportedSection: some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Can't add this file")
                        .font(.subheadline.weight(.semibold))
                    Text("Only files inside a session, the shared folder, or a mounted folder can be added to the Home Screen.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            }
        }
    }

    /// Live tile preview — mirrors what the home screen will actually
    /// show (aurora background + colored chip + glyph) so the user gets
    /// immediate feedback while flipping through the Category grid.
    @ViewBuilder
    private var previewSection: some View {
        Section {
            HStack {
                Spacer()
                LauncherTilePreview(category: category, title: titleInput)
                Spacer()
            }
            .listRowBackground(Color.clear)
            .padding(.vertical, 4)
        }
    }

    @ViewBuilder
    private var titleSection: some View {
        Section("Title") {
            TextField("Title", text: $titleInput)
                .textInputAutocapitalization(.words)
                .submitLabel(.done)
        }
    }

    @ViewBuilder
    private var categorySection: some View {
        Section("Category") {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 4), spacing: 12) {
                ForEach(LauncherCategory.allCases, id: \.self) { c in
                    Button {
                        category = c
                    } label: {
                        VStack(spacing: 4) {
                            ZStack {
                                Circle()
                                    .fill(c.color.opacity(0.22))
                                    .frame(width: 44, height: 44)
                                Image(systemName: c.symbol)
                                    .font(.system(size: 20, weight: .semibold))
                                    .foregroundStyle(c.color)
                                if category == c {
                                    Circle()
                                        .strokeBorder(c.color, lineWidth: 2.5)
                                        .frame(width: 44, height: 44)
                                }
                            }
                            Text(c.label)
                                .font(.caption2)
                                .foregroundStyle(category == c ? c.color : .secondary)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 6)
        }
    }

    // MARK: - Side-effects

    @MainActor
    private func onAppear() async {
        guard let cls = WebAppPathClassifier.classify(hostURL: htmlURL) else {
            unsupportedScope = true
            return
        }
        classification = cls

        let parsedMeta = await Task.detached(priority: .userInitiated) {
            WebAppHtmlMetadata.parse(htmlURL: htmlURL)
        }.value
        if let t = parsedMeta.title, !t.isEmpty {
            titleInput = t
        } else {
            titleInput = htmlURL.deletingPathExtension().lastPathComponent
        }
    }

    @MainActor
    private func saveAndOpenLauncher() {
        guard let cls = classification else { return }
        opening = true
        errorMessage = nil

        let id = UUID().uuidString
        let trimmedTitle = titleInput.trimmingCharacters(in: .whitespaces)

        // Persist a row so DeepLinkRouter (case "open") can re-resolve by
        // (session, scoped-path) — and so a future in-app "My WebApps"
        // listing has something to show. iconRef records the category for
        // diagnostics; the home-screen tile's actual icon comes from the
        // launcher page's <link rel="apple-touch-icon">.
        let shortcut = WebAppShortcut(
            id: id,
            htmlPath: cls.htmlPath,
            pathScope: cls.scope,
            scopeContext: cls.scopeContext,
            title: trimmedTitle,
            iconRef: .preset("category:\(category.rawValue)"),
            iconCachePath: nil,
            createdAt: Date(),
            sourceSessionId: sourceSessionId
        )

        guard let url = buildLauncherURL(classification: cls,
                                         title: trimmedTitle,
                                         category: category) else {
            errorMessage = AppLocalized("Couldn't build the launcher URL.")
            opening = false
            return
        }

        Task { @MainActor in
            await ChatStore.shared.saveWebAppShortcut(shortcut)
            sheetLogger.info("saved shortcut id=\(id.prefix(8)) title=\(trimmedTitle) scope=\(cls.scope.rawValue) → opening \(url.absoluteString)")
            UIApplication.shared.open(url, options: [:]) { ok in
                sheetLogger.info("UIApplication.open completed ok=\(ok)")
                opening = false
                dismiss()
            }
        }
    }

    // MARK: - URL builder

    /// Builds the launcher URL from the classification. `path` is
    /// scope-prefixed so the launcher (and the round-trip deep link) can
    /// recover the scope without out-of-band parameters:
    ///
    ///   session-attachment  →  path=attachments/<htmlPath>, session=<sid>
    ///   session-workspace   →  path=workspace/<htmlPath>,   session=<sid>
    ///   shared              →  path=shared:<htmlPath>       (no session)
    ///   mount               →  path=mount:<uuid>/<htmlPath> (no session)
    private func buildLauncherURL(classification: WebAppPathClassifier.Classified,
                                  title: String,
                                  category: LauncherCategory) -> URL? {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "openminis.app"
        components.path = "/launch.html"

        var items: [URLQueryItem] = [
            URLQueryItem(name: "icon", value: category.rawValue),
        ]

        switch classification.scope {
        case .sessionAttachment:
            guard let sid = classification.scopeContext else { return nil }
            items.append(URLQueryItem(name: "session", value: sid))
            items.append(URLQueryItem(name: "path", value: "attachments/\(classification.htmlPath)"))
        case .sessionWorkspace:
            guard let sid = classification.scopeContext else { return nil }
            items.append(URLQueryItem(name: "session", value: sid))
            items.append(URLQueryItem(name: "path", value: "workspace/\(classification.htmlPath)"))
        case .shared:
            items.append(URLQueryItem(name: "path", value: "shared:\(classification.htmlPath)"))
        case .mount:
            guard let ctx = classification.scopeContext else { return nil }
            items.append(URLQueryItem(name: "path", value: "mount:\(ctx)/\(classification.htmlPath)"))
        }
        components.queryItems = items

        // Title goes in fragment so it never reaches the server but is
        // available to the launcher's JS for window.title / suggested
        // home-screen name.
        guard var s = components.url?.absoluteString else { return nil }
        if let encoded = title.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) {
            s += "#" + encoded
        }
        return URL(string: s)
    }
}

// MARK: - Launcher category

/// The 16 home-screen-tile categories. Each one maps 1:1 to:
///   - a PNG at openminis.app/icons/category/<rawValue>.png
///   - the `(systemName, color)` pair shown in the iOS Category UI
///     (ContentView's session-category picker) so the sheet and the
///     home-screen tile feel like the same visual language.
enum LauncherCategory: String, CaseIterable, Hashable {
    case code, writing, research, analysis, creative, chat
    case math, translation, health, finance, travel, education
    case design, productivity, support, other

    var label: String {
        switch self {
        case .code:         return "Code"
        case .writing:      return "Writing"
        case .research:     return "Research"
        case .analysis:     return "Analysis"
        case .creative:     return "Creative"
        case .chat:         return "Chat"
        case .math:         return "Math"
        case .translation:  return "Translation"
        case .health:       return "Health"
        case .finance:      return "Finance"
        case .travel:       return "Travel"
        case .education:    return "Education"
        case .design:       return "Design"
        case .productivity: return "Productivity"
        case .support:      return "Support"
        case .other:        return "Other"
        }
    }

    /// SF Symbol — mirrors ContentView's session-category catalog.
    var symbol: String {
        switch self {
        case .code:         return "terminal.fill"
        case .writing:      return "doc.text.fill"
        case .research:     return "globe.americas.fill"
        case .analysis:     return "chart.pie.fill"
        case .creative:     return "paintbrush.pointed.fill"
        case .chat:         return "bubble.left.fill"
        case .math:         return "number.circle.fill"
        case .translation:  return "character.bubble"
        case .health:       return "heart.fill"
        case .finance:      return "banknote.fill"
        case .travel:       return "map.fill"
        case .education:    return "book.closed.fill"
        case .design:       return "paintpalette.fill"
        case .productivity: return "calendar.badge.checkmark"
        case .support:      return "gearshape.fill"
        case .other:        return "square.grid.2x2.fill"
        }
    }

    var color: Color {
        switch self {
        case .code:         return .orange
        case .writing:      return .blue
        case .research:     return .teal
        case .analysis:     return .indigo
        case .creative:     return .pink
        case .chat:         return .green
        case .math:         return .purple
        case .translation:  return .cyan
        case .health:       return .red
        case .finance:      return .mint
        case .travel:       return .orange
        case .education:    return .blue
        case .design:       return .pink
        case .productivity: return .yellow
        case .support:      return .brown
        case .other:        return .gray
        }
    }
}

// MARK: - Tile preview

/// SwiftUI re-creation of the launcher's PNG icon. Layout mirrors the
/// generator used to ship /icons/category/<name>.png so the preview
/// shown in the sheet matches what iOS will paint on the home screen.
private struct LauncherTilePreview: View {
    let category: LauncherCategory
    let title: String

    private let tile: CGFloat = 96
    private let chip: CGFloat = 60

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                // Aurora-ish background — a lightweight on-device
                // approximation; the actual home-screen tile uses the
                // exact PNG from openminis.app.
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [
                                Color(red: 0.78, green: 0.92, blue: 1.00),
                                Color(red: 0.92, green: 0.85, blue: 1.00),
                                Color(red: 1.00, green: 0.88, blue: 0.78),
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                Circle()
                    .fill(category.color.opacity(0.22))
                    .frame(width: chip, height: chip)
                Image(systemName: category.symbol)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(category.color)
            }
            .frame(width: tile, height: tile)
            Text(title.isEmpty ? category.label : title)
                .font(.caption)
                .foregroundStyle(.primary)
                .lineLimit(1)
                .truncationMode(.middle)
                .frame(maxWidth: tile + 30)
        }
    }
}
