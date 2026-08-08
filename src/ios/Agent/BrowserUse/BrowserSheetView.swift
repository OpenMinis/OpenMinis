import SwiftUI

/// Sheet view for observing the browser's live WKWebView(s) via a tab pool.
struct BrowserSheetView: View {
    @ObservedObject var pool: BrowserTabPool
    var isAgentBusy: Bool = false
    var onTakeover: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var addressText: String = ""
    @State private var showHistory = false
    @State private var isFullscreen = false
    @State private var toastMessage: String = ""
    @FocusState private var addressFocused: Bool

    private var manager: BrowserUseManager? { pool.activeManager }

    /// Engine shown in the toolbar badge (live from the active tab).
    private var currentEngineKind: BrowserEngineKind {
        manager?.engineKind ?? BrowserEngineSettings.shared.effectiveKind
    }

    /// Switch the pool's engine (persisted; current tab rebuilt at same URL).
    private func switchEngine(to kind: BrowserEngineKind) {
        if kind == .blink && !BlinkEngineBridge.isFrameworkPresent() {
            toastMessage = "Blink 引擎不可用：当前构建未嵌入 content_shell_framework"
            return
        }
        let result = pool.switchEngine(to: kind)
        toastMessage = result.success ? "" : result.text
        if let activeURL = manager?.currentURL, !activeURL.isEmpty {
            addressText = activeURL
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if !isFullscreen {
                    // Tab bar
                    tabBar
                        .allowsHitTesting(!isAgentBusy)

                    // URL bar
                    HStack(spacing: 8) {
                        BrowserAddressBarIcon(isLoading: manager?.isLoading ?? false)

                        TextField("Search or enter URL", text: $addressText)
                            .font(.system(size: 13, design: .monospaced))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                            .submitLabel(.go)
                            .focused($addressFocused)
                            .disabled(isAgentBusy)
                            .onSubmit {
                                let trimmed = addressText.trimmingCharacters(in: .whitespacesAndNewlines)
                                guard !trimmed.isEmpty else { return }
                                pool.touchActivity()
                                manager?.loadURL(trimmed)
                            }

                        if manager?.isLoading ?? false {
                            Button { manager?.stopLoading() } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(.secondary)
                            }
                            .transition(.identity)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color(UIColor.secondarySystemBackground))
                    .allowsHitTesting(!isAgentBusy)
                }

                // Live webview + agent overlay
                ZStack {
                    if let manager {
                        BrowserWebView(manager: manager)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .id(pool.selectedTabId)
                        // [T-ios-webview-error-ui] Safari-style error overlay for
                        // agent/manual navigation failures (blank page otherwise).
                        BrowserLoadErrorOverlay(manager: manager)
                    } else {
                        Color(UIColor.systemBackground)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }

                    if isAgentBusy {
                        AgentBrowsingOverlay(onTakeover: onTakeover)
                            .transition(.opacity)
                    }

                    // Engine / action toast
                    if !toastMessage.isEmpty {
                        Text(toastMessage)
                            .font(.footnote)
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Capsule().fill(Color.black.opacity(0.75)))
                            .padding(.bottom, 24)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                            .allowsHitTesting(false)
                            .transition(.opacity)
                            .task(id: toastMessage) {
                                try? await Task.sleep(nanoseconds: 3_000_000_000)
                                withAnimation { toastMessage = "" }
                            }
                    }

                    // Fullscreen exit button
                    if isFullscreen {
                        VStack {
                            HStack {
                                Spacer()
                                Button {
                                    withAnimation(.easeInOut(duration: 0.25)) { isFullscreen = false }
                                } label: {
                                    Image(systemName: "arrow.down.right.and.arrow.up.left")
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundStyle(.white)
                                        .padding(8)
                                        .background(Circle().fill(Color.black.opacity(0.5)))
                                }
                                .padding(.trailing, 12)
                                .padding(.top, 8)
                            }
                            Spacer()
                        }
                    }
                }
                .animation(.easeInOut(duration: 0.3), value: isAgentBusy)

                // Download progress banner — visible in both normal and
                // fullscreen modes so an in-flight download is never silent.
                if let manager {
                    BrowserDownloadBanner(manager: manager)
                }

                if !isFullscreen {
                    // Separator
                    Divider()

                    // Bottom navigation toolbar
                    HStack(spacing: 0) {
                        Button { pool.touchActivity(); manager?.goBack() } label: {
                            Image(systemName: "chevron.left")
                                .frame(maxWidth: .infinity)
                        }
                        .disabled(isAgentBusy || !(manager?.canGoBack ?? false))

                        Button { pool.touchActivity(); manager?.goForward() } label: {
                            Image(systemName: "chevron.right")
                                .frame(maxWidth: .infinity)
                        }
                        .disabled(isAgentBusy || !(manager?.canGoForward ?? false))

                        if manager?.isLoading ?? false {
                            Button { pool.touchActivity(); manager?.stopLoading() } label: {
                                Image(systemName: "xmark")
                                    .frame(maxWidth: .infinity)
                            }
                            .disabled(isAgentBusy)
                            .transition(.identity)
                        } else {
                            Button { pool.touchActivity(); manager?.reload() } label: {
                                Image(systemName: "arrow.clockwise")
                                    .frame(maxWidth: .infinity)
                            }
                            .transition(.identity)
                            .disabled(isAgentBusy)
                        }

                        Button {
                            withAnimation(.easeInOut(duration: 0.25)) { isFullscreen = true }
                        } label: {
                            Image(systemName: "arrow.up.left.and.arrow.down.right")
                                .frame(maxWidth: .infinity)
                        }
                        .disabled(isAgentBusy)
                    }
                    .font(.system(size: 18))
                    .padding(.vertical, 10)
                    .background(Color(UIColor.secondarySystemBackground))
                    .allowsHitTesting(!isAgentBusy)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    HStack(spacing: 5) {
                        Image(systemName: pool.userAgentProfile.icon)
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                        Text(navigationTitle)
                            .font(.headline)
                            .lineLimit(1)
                    }
                }
            }
            .toolbar(isFullscreen ? .hidden : .visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    HStack(spacing: 12) {
                        Button {
                            _ = pool.newTab()
                        } label: {
                            Image(systemName: "plus")
                        }
                        .disabled(isAgentBusy || pool.tabs.count >= BrowserTabPool.maxTabs)

                        Button {
                            showHistory = true
                        } label: {
                            Image(systemName: "clock.arrow.circlepath")
                        }
                        .disabled(isAgentBusy)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 14) {
                        // P2: open in system default browser
                        Button {
                            if let url = manager?.currentURL, !url.isEmpty {
                                if let err = OpenInSystemBrowser.open(url) {
                                    toastMessage = err
                                }
                            }
                        } label: {
                            Image(systemName: "safari")
                        }
                        .disabled(isAgentBusy || (manager?.currentURL.isEmpty ?? true))

                        // Engine switcher (WebKit / Blink / SSR)
                        Menu {
                            ForEach(BrowserEngineKind.allCases) { kind in
                                Button {
                                    switchEngine(to: kind)
                                } label: {
                                    if kind == currentEngineKind {
                                        Label(kind.displayName, systemImage: "checkmark")
                                    } else {
                                        Text(kind.displayName)
                                    }
                                }
                            }
                            Divider()
                            Text("引擎切换对新建标签页生效；当前标签会在同一 URL 重建")
                                .font(.caption)
                        } label: {
                            Text(currentEngineKind.badge)
                                .font(.caption2.weight(.bold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(Capsule().fill(Color.accentColor.opacity(0.15)))
                                .foregroundStyle(Color.accentColor)
                        }
                        .disabled(isAgentBusy)

                        Button("Done") { dismiss() }
                    }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button { addressFocused = false } label: {
                        Image(systemName: "keyboard.chevron.compact.down")
                    }
                }
            }
            .sheet(isPresented: $showHistory) {
                BrowserHistoryView(historyStore: BrowserHistoryStore.shared) { url in
                    // Ensure a tab exists, then navigate
                    if pool.tabs.isEmpty {
                        pool.ensureTabForUI()
                    }
                    manager?.loadURL(url)
                }
            }
            .onAppear {
                // Ensure at least one tab exists when opened manually
                if pool.tabs.isEmpty {
                    pool.ensureTabForUI()
                }
                addressText = manager?.currentURL ?? ""
            }
            .onDisappear {
                // The sheet stretched each tab's offscreen WKWebView to the
                // sheet width (~620pt), reflowing the page's CSS viewport away
                // from the configured size. Restore every tab's frame + force
                // a reflow so subsequent agent screenshots capture the page at
                // its intended viewport (e.g. 390) instead of a 620-wide
                // layout squeezed into a 390 rect.
                // (T-ios-browser-sheet-viewport-pollution)
                for tab in pool.tabs {
                    tab.manager.restoreViewportFrame()
                }
            }
            .onChange(of: manager?.currentURL) { newURL in
                if !addressFocused {
                    addressText = newURL ?? ""
                }
            }
            .onChange(of: pool.selectedTabId) { _ in
                addressText = manager?.currentURL ?? ""
            }
        }
    }

    private var navigationTitle: String {
        let title = manager?.pageTitle ?? ""
        return title.isEmpty ? "Browser" : title
    }

    // MARK: - Tab Bar

    private var tabBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(pool.tabs) { tab in
                    tabButton(for: tab)
                }

                // Tab count badge
                Text("\(pool.tabs.count)/\(BrowserTabPool.maxTabs)")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(.tertiary)
                    .padding(.leading, 4)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
        }
        .background(Color(UIColor.tertiarySystemBackground))
    }

    private func tabButton(for tab: BrowserTabPool.Tab) -> some View {
        let isSelected = tab.id == pool.selectedTabId
        return Button {
            pool.selectedTabId = tab.id
        } label: {
            HStack(spacing: 4) {
                Text(tabLabel(for: tab))
                    .font(.system(size: 12, weight: isSelected ? .semibold : .regular))
                    .lineLimit(1)
                    .frame(maxWidth: 120)

                if !isAgentBusy {
                    Button {
                        _ = pool.closeTab(id: tab.id)
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(isSelected ? Color.accentColor.opacity(0.15) : Color(UIColor.systemGray5))
            .clipShape(Capsule())
            .overlay(
                Capsule().stroke(isSelected ? Color.accentColor.opacity(0.4) : Color.clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func tabLabel(for tab: BrowserTabPool.Tab) -> String {
        let title = tab.manager.pageTitle
        if !title.isEmpty { return String(title.prefix(20)) }
        let url = tab.manager.currentURL
        if !url.isEmpty { return String(url.prefix(20)) }
        return "Tab \(tab.id)"
    }
}

// MARK: - Address Bar Loading Icon

/// Globe icon with a spinning arc border when loading.
private struct BrowserAddressBarIcon: View {
    let isLoading: Bool
    @State private var spinning = false

    var body: some View {
        ZStack {
            Image(systemName: "globe")
                .font(.system(size: 14))
                .foregroundStyle(isLoading ? Color.accentColor : .secondary)

            if isLoading {
                Circle()
                    .trim(from: 0, to: 0.3)
                    .stroke(Color.accentColor, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                    .frame(width: 22, height: 22)
                    .rotationEffect(.degrees(spinning ? 360 : 0))
                    .animation(.linear(duration: 1).repeatForever(autoreverses: false), value: spinning)
                    .onAppear { spinning = true }
                    .onDisappear { spinning = false }
                    .transition(.identity)
            }
        }
        .frame(width: 24, height: 24)
    }
}

// MARK: - Agent Browsing Overlay

/// Breathing-light overlay shown when the agent is controlling the browser.
/// Blocks all user interaction on the webview while providing a visual cue.
private struct AgentBrowsingOverlay: View {
    var onTakeover: (() -> Void)?
    @State private var breathing = false

    var body: some View {
        ZStack {
            // Semi-transparent backdrop that blocks touches
            Color.black.opacity(0.35)

            // Centered indicator with breathing glow
            HStack(spacing: 12) {
                Circle()
                    .fill(Color.accentColor)
                    .frame(width: 8, height: 8)
                    .opacity(breathing ? 1.0 : 0.3)
                    .shadow(color: Color.accentColor.opacity(breathing ? 0.8 : 0), radius: breathing ? 8 : 0)

                Text("Minis is browsing")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white.opacity(0.9))

                if let onTakeover {
                    Divider()
                        .frame(height: 14)
                        .overlay(Color.white.opacity(0.3))

                    Button("Takeover") {
                        onTakeover()
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.accentColor)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(
                Capsule()
                    .fill(Color.black.opacity(0.7))
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle()) // capture all touches
        .onAppear {
            withAnimation(.easeInOut(duration: 1.5).repeatForever(autoreverses: true)) {
                breathing = true
            }
        }
    }
}

// MARK: - Download Progress Banner

/// Slim banner listing this session's downloads inside the browser sheet.
/// [T-browser-download-ux] Now driven by the session-scoped
/// BrowserDownloadCenter (covers all tabs) and reuses the same card rows as
/// the chat's floating overlay, so the sheet shows live percent/bytes too.
struct BrowserDownloadBanner: View {
    @ObservedObject var manager: BrowserUseManager
    @ObservedObject private var center = BrowserDownloadCenter.shared

    var body: some View {
        let items = center.downloads(for: manager.sessionIdProvider?() ?? "")
        if !items.isEmpty {
            VStack(spacing: 6) {
                ForEach(items) { entry in
                    BrowserDownloadCardRow(entry: entry,
                                           onTap: nil,
                                           onDismiss: { center.dismiss(id: entry.id) })
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color(UIColor.secondarySystemBackground))
        }
    }
}

// MARK: - Floating download button (chat overlay)

/// Round 36×36 floating button shown near the scroll-jump buttons while the
/// session has active/unviewed native browser downloads. Mirrors the scroll
/// buttons' visual language (semi-transparent system background circle, gray
/// stroke, soft shadow). Badge = in-flight + unviewed terminal entries;
/// hidden entirely at badge 0. Tapping opens BrowserDownloadPanelSheet.
/// [T-browser-download-ux-v2]
struct BrowserDownloadFloatingButton: View {
    @ObservedObject private var center = BrowserDownloadCenter.shared
    @Environment(\.colorScheme) private var colorScheme
    let sessionId: String
    let action: () -> Void

    @State private var legacyPulse = false

    var body: some View {
        let items = center.downloads(for: sessionId)
        // Visible while ANY entries exist (user hasn't cleared them yet) —
        // the button disappears the moment the last record is cleared. The
        // badge itself only renders when there is something "active" to
        // count (in-flight + unviewed terminal entries).
        if !items.isEmpty {
            let badge = center.badgeCount(for: sessionId)
            let downloading = items.contains { $0.state == .downloading }
            Button(action: action) {
                Image(systemName: "arrow.down.circle")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .modifier(DownloadPulseModifier(active: downloading, legacyPulse: $legacyPulse))
                    .frame(width: 36, height: 36)
                    .background {
                        Color(.systemBackground)
                            .opacity(colorScheme == .dark ? 0.8 : 0.5)
                            .clipShape(Circle())
                    }
                    .overlay(Circle().stroke(Color.gray.opacity(0.25), lineWidth: 0.5))
                    .shadow(color: .black.opacity(0.12), radius: 4, y: 2)
                    .overlay(alignment: .topTrailing) {
                        if badge > 0 {
                            Text("\(badge)")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, badge > 9 ? 4 : 0)
                                .frame(minWidth: 16, minHeight: 16)
                                .background(Color.red, in: Capsule())
                                .offset(x: 5, y: -5)
                        }
                    }
            }
            .buttonStyle(.plain)
            .transition(.opacity.combined(with: .scale(scale: 0.8)))
        }
    }
}

/// Pulse on iOS 17+ via symbolEffect; below that, a gentle repeating opacity
/// breath driven by an explicit animation.
private struct DownloadPulseModifier: ViewModifier {
    let active: Bool
    @Binding var legacyPulse: Bool

    func body(content: Content) -> some View {
        if #available(iOS 17, *) {
            content.symbolEffect(.pulse, options: .repeating, isActive: active)
        } else {
            content
                .opacity(active && legacyPulse ? 0.35 : 1.0)
                .animation(active
                           ? .easeInOut(duration: 0.9).repeatForever(autoreverses: true)
                           : .default,
                           value: legacyPulse)
                .onAppear { if active { legacyPulse = true } }
                .onChange(of: active) { nowActive in
                    legacyPulse = nowActive
                }
        }
    }
}

// MARK: - Downloads panel sheet

/// Download manager panel opened from the floating button: all of the
/// session's downloads newest-first — cancel for in-flight rows, jump to the
/// file browser (located at the file) for completed rows, reason + clear for
/// failed rows. [T-browser-download-ux-v2]
struct BrowserDownloadPanelSheet: View {
    @ObservedObject private var center = BrowserDownloadCenter.shared
    @Environment(\.dismiss) private var dismiss
    let sessionId: String
    /// Called with the workspace filename when the user opens a completed row.
    let onLocate: (String) -> Void

    var body: some View {
        NavigationStack {
            Group {
                let items = center.downloads(for: sessionId)
                    .sorted { $0.startedAt > $1.startedAt }
                if items.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "arrow.down.circle")
                            .font(.system(size: 42))
                            .foregroundStyle(.secondary)
                        Text("No downloads", comment: "Empty downloads panel")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(items) { entry in
                            BrowserDownloadPanelRow(
                                entry: entry,
                                onCancel: { center.cancel(id: entry.id) },
                                onLocate: { onLocate(entry.filename) },
                                onClear: { center.dismiss(id: entry.id) }
                            )
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(Text("Downloads", comment: "Downloads panel title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    // [T-browser-download-ux-v3] "Clear" wipes every finished
                    // record (completed + failed); in-flight rows stay.
                    let hasFinished = center.downloads(for: sessionId).contains {
                        $0.state != .downloading
                    }
                    if hasFinished {
                        Button {
                            center.clearFinished(for: sessionId)
                        } label: {
                            Text("Clear", comment: "Downloads panel action")
                        }
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(String(localized: "Close")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        // Viewing the panel clears the badge; records themselves persist
        // until the user clears them (rows / Clear Completed) — the floating
        // button hides only when the list is actually empty.
        .onAppear { center.markAllSeen(for: sessionId) }
    }
}

/// One panel row: state icon + filename + progress/summary, with a
/// state-appropriate trailing action (cancel / locate chevron / clear).
private struct BrowserDownloadPanelRow: View {
    let entry: BrowserDownloadCenter.Entry
    let onCancel: () -> Void
    let onLocate: () -> Void
    let onClear: () -> Void
    @StateObject private var progress: DownloadProgressObserver

    init(entry: BrowserDownloadCenter.Entry,
         onCancel: @escaping () -> Void,
         onLocate: @escaping () -> Void,
         onClear: @escaping () -> Void) {
        self.entry = entry
        self.onCancel = onCancel
        self.onLocate = onLocate
        self.onClear = onClear
        _progress = StateObject(wrappedValue: DownloadProgressObserver(progress: entry.progress))
    }

    var body: some View {
        HStack(spacing: 12) {
            stateIcon
                .font(.system(size: 24))
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 4) {
                Text(entry.filename)
                    .font(.system(size: 14, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                switch entry.state {
                case .downloading:
                    let done = ByteCountFormatter.string(fromByteCount: progress.completed, countStyle: .file)
                    let pctText = progress.total > 0 ? "\(Int(progress.fraction * 100))% · " : ""
                    let totalText = progress.total > 0
                        ? " / \(ByteCountFormatter.string(fromByteCount: progress.total, countStyle: .file))" : ""
                    Text("\(pctText)\(done)\(totalText)")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                    ProgressView(value: progress.fraction)
                        .progressViewStyle(.linear)
                        .tint(Color.accentColor)
                case .completed(let sizeText):
                    Text("\(sizeText) · \(String(localized: "Show in Files", comment: "Completed download row hint"))")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                case .failed(let reason):
                    Text(reason)
                        .font(.system(size: 12))
                        .foregroundStyle(.red)
                        .lineLimit(3)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            switch entry.state {
            case .downloading:
                Button(action: onCancel) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            case .completed:
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            case .failed:
                Button(action: onClear) {
                    Image(systemName: "trash")
                        .font(.system(size: 15))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
        .onTapGesture {
            if case .completed = entry.state { onLocate() }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if entry.state != .downloading {
                Button(role: .destructive, action: onClear) {
                    Label(String(localized: "Clear"), systemImage: "trash")
                }
            }
        }
    }

    @ViewBuilder private var stateIcon: some View {
        switch entry.state {
        case .downloading:
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(Color.accentColor)
                .symbolEffectPulseIfAvailable()
        case .completed:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .foregroundStyle(.red)
        }
    }
}

/// One download card: state icon + filename + live progress (percent and
/// downloaded/total bytes) or completion/failure summary. Completed cards
/// are tappable (open the file preview).
struct BrowserDownloadCardRow: View {
    let entry: BrowserDownloadCenter.Entry
    let onTap: (() -> Void)?
    let onDismiss: (() -> Void)?
    @StateObject private var progress: DownloadProgressObserver

    init(entry: BrowserDownloadCenter.Entry,
         onTap: (() -> Void)?,
         onDismiss: (() -> Void)?) {
        self.entry = entry
        self.onTap = onTap
        self.onDismiss = onDismiss
        _progress = StateObject(wrappedValue: DownloadProgressObserver(progress: entry.progress))
    }

    var body: some View {
        HStack(spacing: 10) {
            stateIcon
                .font(.system(size: 22))
                .frame(width: 26)

            VStack(alignment: .leading, spacing: 3) {
                Text(entry.filename)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                    .truncationMode(.middle)
                subtitle
                if case .downloading = entry.state {
                    ProgressView(value: progress.fraction)
                        .progressViewStyle(.linear)
                        .tint(Color.accentColor)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let onDismiss {
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .padding(6)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
        .contentShape(Rectangle())
        .onTapGesture { onTap?() }
    }

    @ViewBuilder private var stateIcon: some View {
        switch entry.state {
        case .downloading:
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(Color.accentColor)
                .symbolEffectPulseIfAvailable()
        case .completed:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failed:
            Image(systemName: "xmark.circle.fill")
                .foregroundStyle(.red)
        }
    }

    @ViewBuilder private var subtitle: some View {
        switch entry.state {
        case .downloading:
            let done = ByteCountFormatter.string(fromByteCount: progress.completed, countStyle: .file)
            let pctText = progress.total > 0 ? "\(Int(progress.fraction * 100))% · " : ""
            let totalText = progress.total > 0
                ? " / \(ByteCountFormatter.string(fromByteCount: progress.total, countStyle: .file))" : ""
            Text("\(pctText)\(done)\(totalText)")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
        case .completed(let sizeText):
            Text("\(sizeText) · \(String(localized: "Tap to view", comment: "Completed download card"))")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
        case .failed(let reason):
            Text(reason)
                .font(.system(size: 11))
                .foregroundStyle(.red)
                .lineLimit(2)
        }
    }
}

private extension View {
    /// Gentle pulse on the in-progress icon where the OS supports it.
    @ViewBuilder func symbolEffectPulseIfAvailable() -> some View {
        if #available(iOS 17, *) {
            self.symbolEffect(.pulse)
        } else {
            self
        }
    }
}

/// KVO bridge: republishes a WKDownload's NSProgress (fractionCompleted /
/// byte counts) on the main thread so SwiftUI text refreshes live — a bare
/// `Progress` held in a struct is not observed by SwiftUI for Text purposes.
final class DownloadProgressObserver: ObservableObject {
    @Published var fraction: Double
    @Published var completed: Int64
    @Published var total: Int64
    private var observation: NSKeyValueObservation?

    init(progress: Progress) {
        fraction = progress.fractionCompleted
        completed = progress.completedUnitCount
        total = progress.totalUnitCount
        // fractionCompleted changes whenever completed/total unit counts do,
        // so one observation covers all three published values.
        observation = progress.observe(\.fractionCompleted, options: [.new]) { [weak self] p, _ in
            DispatchQueue.main.async {
                self?.fraction = p.fractionCompleted
                self?.completed = p.completedUnitCount
                self?.total = p.totalUnitCount
            }
        }
    }
}

/// [T-ios-webview-error-ui] Observes the manager's `loadError` and renders the
/// shared Safari-style overlay (with Retry) over the agent browser's blank
/// page. Split into its own `@ObservedObject` view so a `loadError` change
/// actually re-renders — the parent reads `manager` via a computed property and
/// wouldn't otherwise observe it.
private struct BrowserLoadErrorOverlay: View {
    @ObservedObject var manager: BrowserUseManager

    var body: some View {
        if let err = manager.loadError {
            WebLoadErrorOverlay(error: err) { manager.retryFailedLoad() }
        }
    }
}
