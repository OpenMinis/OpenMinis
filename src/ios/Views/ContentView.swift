import SwiftUI

private let shareLog = AppLogger(category: "Share")
private let draftLog = AppLogger(category: "DraftSession")

// MARK: - Session context-menu action channel

/// [T-ios-crash-contextmenu-uaf] Action relay for the sidebar session context
/// menu. Stored as @State in ContentView (stable lifetime); the concrete
/// SessionContextMenu view holds a reference to it but Equatable ignores it,
/// so SwiftUI's attribute-graph copy/release of the view struct only
/// retains/releases this single class reference — no closure captures that
/// could dangle.
@MainActor
private final class SessionMenuActionChannel {
    var handler: ((SessionMenuAction) -> Void)?
    func send(_ action: SessionMenuAction) { handler?(action) }
}

/// One sidebar section: a date bucket (folderId == nil) or a folder section.
/// Equatable and cheap on purpose — the sidebar ForEach diffs this on every
/// transaction flush, so it must stay a [String] + small scalars, never a
/// [ChatSession] ([T-ios-session-list-equatable-jank]).
struct SidebarGroup: Equatable {
    var label: String            // English key for date buckets; folder name otherwise
    var ids: [String]            // emptied when the folder is collapsed
    var folderId: String? = nil
    var totalCount: Int = 0      // member count incl. hidden-by-collapse rows
    var glyphs: [FolderGlyph] = []   // top-3 distinct member category glyphs
    var anyUnread = false
    var anyActive = false
    var anyPaused = false
    var isCollapsed = false
    var isFolderPinned = false
    var latestDate: Date? = nil      // newest member updatedAt (nil = empty folder)
    var summaryTitle: String? = nil  // newest member's title, for the subtitle line
    /// True on the FIRST folder group only: renders the "Groups" divider
    /// label (styled like the date-bucket headers) above the folder block.
    var showsGroupsHeader = false
}

/// A single composed-icon glyph. Small struct instead of a tuple because
/// tuples can't satisfy Equatable inside SidebarGroup's array.
struct FolderGlyph: Equatable {
    var systemName: String
    var color: Color
}

/// Projection of EXACTLY the per-session fields the sidebar grouping reads —
/// the memo key's session component. Deliberately excludes `lastMessage`
/// (the expensive field at the heart of [T-ios-session-list-equatable-jank]);
/// comparing N of these is a handful of short-string compares per element,
/// far cheaper than re-running the grouping.
private struct SidebarSessionKey: Equatable {
    let id: String
    let updatedAt: Date
    let pinnedAt: Date?
    let isPinned: Bool
    let folderId: String?
    let category: String?
    let title: String?
}

/// Every input `groupedSessionIDs` depends on. Compared (not hashed) so a
/// hit is provably identical input — no collision risk. The badge/activity
/// sets are IN the key on purpose: they're both the aggregation inputs and
/// the invalidation signal (a badge aging past the 24h window changes the
/// freshly-built set, which misses the memo — same "next ambient refresh"
/// timing the unmemoized per-member query had). `dayStamp` invalidates the
/// date buckets when the calendar day flips.
private struct SidebarGroupsMemoKey: Equatable {
    let sessions: [SidebarSessionKey]
    let folders: [ChatFolder]
    let collapsedFolderIds: Set<String>
    let pendingDraftId: String?
    let pendingDraftFolderId: String?
    let isSearching: Bool
    let unreadIds: Set<String>
    let freshCornerIds: Set<String>
    let activeIds: Set<String>
    let dayStamp: Date
}

/// Reference-type memo box held in @State: mutating its contents during a
/// body evaluation is legal (no observed value changes, so no re-entrant
/// invalidation), and identity survives ContentView struct re-inits. A memo
/// hit returns the SAME [SidebarGroup] array instance, so the downstream
/// ForEach diff takes the identical-storage fast path instead of comparing
/// element-wise.
private final class SidebarGroupsMemo {
    var key: SidebarGroupsMemoKey?
    var groups: [SidebarGroup] = []
}

/// List-selection opt-out with an availability floor. The folder card row
/// must never become the List(selection:)'s selected value — its "tap" is
/// collapse/expand, not navigation — but .selectionDisabled is iOS 17+ and
/// the app floors at 16 (where the plain Button row doesn't self-select).
private struct SelectionDisabledIfAvailable: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.selectionDisabled()
        } else {
            content
        }
    }
}

/// Tracks whether an expanded folder's header card has scrolled out above
/// the list's visible top, reporting only on TRANSITIONS (Bool onChange) so
/// scroll frames don't churn ContentView state. Lives in the header row's
/// background — geometry is read in .global and compared against the
/// threshold the mini-bar overlay measured, which sidesteps the known-flaky
/// preference propagation across List cell hosting boundaries entirely.
///
/// `lastMaxY` exists for the CULLING edge: when the List recycles the header
/// cell far offscreen, onDisappear is the only signal left, and it alone
/// can't tell "culled above" from "culled below". The last observed frame
/// disambiguates. onDisappear also fires on collapse and on entering select
/// mode (the header row unmounts) — those reports are harmless because the
/// mini-bar's display guard independently requires an expanded folder
/// outside select mode.
private struct FolderHeaderVisibilityProbe: View {
    let folderId: String
    let thresholdY: CGFloat
    let onVisibilityChange: (String, Bool) -> Void

    @State private var lastMaxY: CGFloat = .infinity

    var body: some View {
        GeometryReader { geo in
            let maxY = geo.frame(in: .global).maxY
            let hidden = maxY < thresholdY
            Color.clear
                .onAppear {
                    lastMaxY = maxY
                    onVisibilityChange(folderId, hidden)
                }
                .onChange(of: maxY) { newValue in
                    lastMaxY = newValue
                }
                .onChange(of: hidden) { newValue in
                    onVisibilityChange(folderId, newValue)
                }
                .onDisappear {
                    onVisibilityChange(folderId, lastMaxY < thresholdY)
                }
        }
    }
}

/// Folder card container: what visually separates a folder from the flat
/// session rows around it. On iOS 26+ this is the system Liquid Glass
/// material (`glassEffect`) in a continuous rounded rect; on earlier systems
/// it degrades to a deliberately plain filled card with a hairline stroke —
/// no custom blur, since `.blur` is an offscreen pass and this list is the
/// site of two archived scroll-perf incidents. The drop-target state draws an
/// accent tint + stroke ON TOP of either material, so drag feedback reads the
/// same on both OS generations.
/// One row's share of the expanded container's OUTER border. The top kind
/// draws the top arc + upper side walls, middle draws side walls only, and
/// bottom draws the lower walls + bottom arc — tiled across the group's rows
/// they form one continuous rounded-rect highlight, with no horizontal lines
/// at the row boundaries (which a per-segment full stroke would create).
private struct FolderSegmentBorder: Shape {
    enum Kind { case top, middle, bottom }
    let kind: Kind
    var radius: CGFloat = 16

    func path(in rect: CGRect) -> Path {
        var p = Path()
        switch kind {
        case .top:
            p.move(to: CGPoint(x: rect.minX, y: rect.maxY))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
            p.addArc(center: CGPoint(x: rect.minX + radius, y: rect.minY + radius),
                     radius: radius, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
            p.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
            p.addArc(center: CGPoint(x: rect.maxX - radius, y: rect.minY + radius),
                     radius: radius, startAngle: .degrees(270), endAngle: .degrees(0), clockwise: false)
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        case .middle:
            p.move(to: CGPoint(x: rect.minX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            p.move(to: CGPoint(x: rect.maxX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        case .bottom:
            p.move(to: CGPoint(x: rect.minX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - radius))
            p.addArc(center: CGPoint(x: rect.minX + radius, y: rect.maxY - radius),
                     radius: radius, startAngle: .degrees(180), endAngle: .degrees(90), clockwise: true)
            p.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.maxY))
            p.addArc(center: CGPoint(x: rect.maxX - radius, y: rect.maxY - radius),
                     radius: radius, startAngle: .degrees(90), endAngle: .degrees(0), clockwise: true)
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        }
        return p
    }
}

/// Edge highlight for the simulated glass: bright hairline in dark mode
/// (the reference system-folder look), a subtle dark line in light mode
/// (white would vanish on the light page).
private let folderEdgeHighlight = Color(UIColor { traits in
    traits.userInterfaceStyle == .dark
        ? UIColor(white: 1, alpha: 0.30)
        : UIColor(white: 0, alpha: 0.08)
})

/// Background for the expanded FAB search bar. Liquid Glass capsule on iOS 26+,
/// the original opaque capsule + hand-rolled shadow below it.
///
/// A modifier rather than a background view because the bar's content has to sit
/// INSIDE the glass: `.glassEffect` styles the view it is applied to, so the
/// text field and its icons ride within the material instead of being composited
/// over a separately-drawn shape.
private struct SearchBarSurface: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            // No .clipShape needed — glassEffect(in:) already clips to the
            // capsule, and no .shadow: the material carries its own.
            content.glassEffect(.regular, in: .capsule)
        } else {
            content
                .background(Color(UIColor.secondarySystemBackground))
                .clipShape(Capsule())
                .shadow(color: .black.opacity(0.1), radius: 6, x: 0, y: 2)
        }
    }
}

/// Tags the search FAB and the expanded search bar with ONE shared
/// `glassEffectID`.
///
/// Currently INERT: `glassEffectID` only does anything inside a
/// `GlassEffectContainer`, and the FAB row no longer has one — that container
/// suppressed the new-chat FAB's context menu, see the note on `fabRow`. The
/// modifier is kept (harmless, and it costs nothing) so that if the container
/// is ever reinstated with the menu problem solved, the morph works again
/// without re-deriving the id plumbing. The row's scale+opacity transition is
/// what actually animates the swap today.
private struct FABGlassMorphID: ViewModifier {
    let namespace: Namespace.ID

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffectID("fabSearch", in: namespace)
        } else {
            content
        }
    }
}

/// THE single source of truth for a folder surface's background — used
/// identically by the collapsed lone card and by every row of an expanded
/// group. Per the user's directive after several rounds of expanded-only
/// material tuning: there is NO expanded-specific material/tint/opacity
/// definition anymore. The only thing that varies by position is the SHAPE
/// (lone / top / middle / bottom corner segmentation) and how the border is
/// tiled; the fill comes from one place.
private struct FolderSurface: ViewModifier {
    enum Kind { case lone, top, middle, bottom }
    let kind: Kind

    /// The collapsed card's ACTUAL rendered color, eyedropper-sampled from
    /// device screenshots. Dark: RGB 18/18/18 — re-sampled with a probe
    /// build rendering REAL glass over a pure-black list with no pinned rows
    /// behind it (24 points, zero variance). The earlier 57/57/60 was
    /// contaminated: that screenshot's card had blurred colorful content
    /// showing through the glass, and the too-light constant is what read as
    /// a heavy solid slab against the black page. True glass sits nearly on
    /// the background and lets the edge hairline do the lifting — that IS
    /// the lightweight look. Light: RGB 252/252/252 (sampled over a clean
    /// light background, unaffected).
    /// Used for the expanded segments on iOS 26 instead of per-row
    /// glassEffect: glass pieces cannot merge across List rows (edge lines
    /// between every segment — twice rejected), and in THIS context glass
    /// has nothing to blur behind it (the container sits on the flat list
    /// background), so its rendered output IS effectively this constant
    /// color. Sampling it gives pixel-level brightness parity with the
    /// collapsed card, perfectly seamless tiling, and zero perf risk —
    /// chosen by the user over a preference-propagation spike.
    static let sampledGlassColor = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 18/255.0, green: 18/255.0, blue: 18/255.0, alpha: 1)
            : UIColor(red: 252/255.0, green: 252/255.0, blue: 252/255.0, alpha: 1)
    })

    private var shape: AnyShape {
        switch kind {
        case .lone:
            return AnyShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        case .top:
            return AnyShape(UnevenRoundedRectangle(
                topLeadingRadius: 16, bottomLeadingRadius: 0,
                bottomTrailingRadius: 0, topTrailingRadius: 16, style: .continuous))
        case .middle:
            return AnyShape(Rectangle())
        case .bottom:
            return AnyShape(UnevenRoundedRectangle(
                topLeadingRadius: 0, bottomLeadingRadius: 16,
                bottomTrailingRadius: 16, topTrailingRadius: 0, style: .continuous))
        }
    }

    func body(content: Content) -> some View {
        Group {
            // Two states, two deliberate treatments (user decision):
            // - COLLAPSED lone card: the sampled constant. glassEffect is a
            //   live material and a collapsed card scrolls past heterogeneous
            //   content (blurred colorful pinned rows), so live glass
            //   flickered frame to frame; the constant is what that glass
            //   rendered over the flat background anyway.
            // - EXPANDED segments: a real material for translucency. Member
            //   rows sit over the STABLE list background only — nothing
            //   colorful ever passes behind them — so the flicker mechanism
            //   doesn't apply, and the material keeps the container from
            //   reading as a dull solid slab. regularMaterial (the brighter
            //   tier; ultraThin ran too dark here before).
            if kind == .lone {
                if #available(iOS 26.0, *) {
                    content.background(shape.fill(Self.sampledGlassColor))
                } else {
                    content.background(shape.fill(Color(UIColor.secondarySystemBackground)))
                }
            } else {
                if #available(iOS 26.0, *) {
                    content.background(shape.fill(.regularMaterial))
                } else {
                    content.background(shape.fill(Color(UIColor.secondarySystemBackground)))
                }
            }
        }
        .overlay {
            // Border: full hairline on the lone card (its existing look, sub-26
            // only — glass carries its own edge); tiled outer-perimeter
            // segments on expanded rows so the pieces read as one outline
            // without horizontal lines at the row boundaries.
            switch kind {
            case .lone:
                // With glass gone the card needs an explicit edge for
                // definition — the same highlight the expanded segments tile,
                // as a full perimeter here.
                if #available(iOS 26.0, *) {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(folderEdgeHighlight, lineWidth: 0.75)
                } else {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(Color(UIColor.separator).opacity(0.5), lineWidth: 0.5)
                }
            case .top:
                FolderSegmentBorder(kind: .top)
                    .stroke(folderEdgeHighlight, lineWidth: 0.75)
            case .middle:
                FolderSegmentBorder(kind: .middle)
                    .stroke(folderEdgeHighlight, lineWidth: 0.75)
            case .bottom:
                FolderSegmentBorder(kind: .bottom)
                    .stroke(folderEdgeHighlight, lineWidth: 0.75)
            }
        }
    }
}

private struct FolderCardBackground: ViewModifier {
    let isDropTarget: Bool
    let isExpanded: Bool

    private var dropShape: AnyShape {
        isExpanded
            ? AnyShape(UnevenRoundedRectangle(
                topLeadingRadius: 16, bottomLeadingRadius: 0,
                bottomTrailingRadius: 0, topTrailingRadius: 16, style: .continuous))
            : AnyShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    func body(content: Content) -> some View {
        content
            .modifier(FolderSurface(kind: isExpanded ? .top : .lone))
            .overlay {
                if isDropTarget {
                    ZStack {
                        dropShape.fill(Color.accentColor.opacity(0.15))
                        dropShape.stroke(Color.accentColor, lineWidth: 1.5)
                    }
                }
            }
    }
}

/// Middle/bottom rows of an expanded group: the SAME FolderSurface as the
/// collapsed card, with the corner segmentation and border tiling that
/// multi-row composition needs.
private struct FolderMemberRowBackground: View {
    let isLast: Bool

    var body: some View {
        Color.clear
            .modifier(FolderSurface(kind: isLast ? .bottom : .middle))
            .padding(.horizontal, 6)
            .padding(.bottom, isLast ? 4 : 0)
    }
}

/// Rename + dissolve alerts for the folder-header menu, extracted into a
/// modifier so their inline Binding(get:set:) expressions don't count against
/// ContentView.body's type-check budget (adding them inline tipped the
/// compiler into "unable to type-check in reasonable time").
private struct FolderAlertsModifier: ViewModifier {
    @Binding var folderToRename: ChatFolder?
    @Binding var renameFolderText: String
    @Binding var renameFolderDesc: String
    @Binding var folderToDissolve: ChatFolder?
    /// [T-folder-duplicate-name] Every existing group, so a rename can detect a
    /// name collision before writing.
    let allFolders: [ChatFolder]
    let memberCount: (String) -> Int
    let onRename: (ChatFolder, String, String) -> Void
    let onDissolve: (ChatFolder) -> Void
    /// Offer to merge `source` into the existing same-named `target`.
    let onMergeInto: (_ source: ChatFolder, _ target: ChatFolder) -> Void

    /// The rename was blocked because the typed name is already taken; holds
    /// the (renamed folder, existing folder) pair for the follow-up alert.
    @State private var renameCollision: (source: ChatFolder, target: ChatFolder)?

    /// [T-folder-duplicate-name] An OTHER group already carrying `name`.
    /// Excludes the folder being renamed so re-saving its own name (e.g. a
    /// description-only edit) is never treated as a collision.
    private func duplicate(of name: String, excluding id: String) -> ChatFolder? {
        let key = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !key.isEmpty else { return nil }
        return allFolders.first {
            $0.id != id
                && $0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == key
        }
    }

    func body(content: Content) -> some View {
        content
            .alert(
                "Rename Group",
                isPresented: Binding(
                    get: { folderToRename != nil },
                    set: { if !$0 { folderToRename = nil } }
                )
            ) {
                TextField("Group Name", text: $renameFolderText)
                // The one-sentence auto-grouping context — hidden in the
                // list, surfaced for editing exactly here as requested.
                TextField("Description (optional)", text: $renameFolderDesc)
                Button("Cancel", role: .cancel) { folderToRename = nil }
                Button("Rename") {
                    let name = renameFolderText.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let folder = folderToRename, !name.isEmpty {
                        // [T-folder-duplicate-name] Renaming onto an existing
                        // name would leave two groups the picker cannot tell
                        // apart. Stop and offer the merge instead of writing.
                        if let clash = duplicate(of: name, excluding: folder.id) {
                            renameCollision = (source: folder, target: clash)
                        } else {
                            onRename(folder, name, renameFolderDesc)
                        }
                    }
                    folderToRename = nil
                }
            }
            // [T-folder-duplicate-name] Follow-up for a blocked rename. A plain
            // `.alert` cannot host a picker, so the choice is spelled out: go
            // back and pick another name, or move this group's chats into the
            // existing one (which is what "use the existing group" means when
            // the group already has members).
            .alert(
                "Group Already Exists",
                isPresented: Binding(
                    get: { renameCollision != nil },
                    set: { if !$0 { renameCollision = nil } }
                ),
                presenting: renameCollision
            ) { pair in
                Button("Change Name") {
                    // Reopen the rename dialog with the text still in place so
                    // the user edits rather than retypes.
                    folderToRename = pair.source
                    renameCollision = nil
                }
                Button("Move Chats There") {
                    onMergeInto(pair.source, pair.target)
                    renameCollision = nil
                }
                Button("Cancel", role: .cancel) { renameCollision = nil }
            } message: { pair in
                Text("A group named “\(pair.target.name)” already exists. Choose a different name, or move this group's chats into it.")
            }
            .alert(
                "Dissolve Group?",
                isPresented: Binding(
                    get: { folderToDissolve != nil },
                    set: { if !$0 { folderToDissolve = nil } }
                ),
                presenting: folderToDissolve
            ) { folder in
                Button("Cancel", role: .cancel) { folderToDissolve = nil }
                Button("Dissolve") {
                    onDissolve(folder)
                    folderToDissolve = nil
                }
            } message: { folder in
                // Spell out that no session is deleted — this action sits one
                // menu away from the one that deletes everything, and the
                // wording is what keeps them apart.
                Text("\(memberCount(folder.id)) sessions will move back to the main list. No session will be deleted.")
            }
    }
}

/// A pending "move sessions to folder" interaction, presented as a sheet.
private struct FolderPickerRequest: Identifiable {
    let id = UUID()
    let sessionIds: Set<String>
    let fromMultiSelect: Bool
    let anyFiled: Bool
}

/// Shared folder picker: existing folders, inline "new folder" creation, and
/// (when a target is already filed) "remove from folder". One sheet serves the
/// multi-select toolbar, the row context menu, and later entry points, so the
/// flows can't drift apart.
private struct FolderPickerSheet: View {
    enum Choice {
        case existing(String)                    // folder id
        case create(name: String, desc: String?) // new group name + optional description
        case removeFromFolder
    }

    /// One pickable folder with the display aggregates the row needs — the
    /// same composed icon and "N chats · context" subtitle the home group
    /// card shows, so the picker and the list describe a group identically.
    /// Computed by the presenter (which owns sessions), not here.
    struct FolderItem: Identifiable {
        let folder: ChatFolder
        let glyphs: [FolderGlyph]   // top-3 distinct member category glyphs
        let count: Int
        let subtitle: String?       // desc if set, else newest member title
        var id: String { folder.id }
    }

    let items: [FolderItem]
    let sessionIds: [String]
    let anyFiled: Bool
    let onChoose: (Choice) -> Void

    @State private var newFolderName = ""
    @State private var newFolderDesc = ""
    @FocusState private var nameFieldFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var suggesting = false
    @State private var suggestedMerge: (folderId: String, folderName: String)?
    @State private var suggestFailed = false

    private var sessionCount: Int { sessionIds.count }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Image(systemName: "folder.badge.plus")
                            .foregroundStyle(Color.accentColor)
                        TextField("New Group Name", text: $newFolderName)
                            .focused($nameFieldFocused)
                            .submitLabel(.next)
                    }
                    // One-sentence auto-grouping context (≤100 chars). Typed
                    // here or prefilled by AI Suggest; never shown in the
                    // list, editable later from Rename Group.
                    TextField("Description (optional, guides auto-grouping)", text: $newFolderDesc, axis: .vertical)
                        .lineLimit(1...2)
                        .font(.subheadline)
                        .onChange(of: newFolderDesc) { v in
                            if v.count > 100 { newFolderDesc = String(v.prefix(100)) }
                        }
                    // ✨ AI suggest — nothing auto-applies. A merge suggestion
                    // renders as its own confirm row below; a create
                    // suggestion prefills the name field and waits for the
                    // user's Create tap. Failure degrades silently to the
                    // manual flow (the sheet is already the manual flow).
                    // Bottom row of the create area: AI Suggest leading,
                    // Create trailing. Two independent tap targets in one
                    // List row need .borderless — a plain Button row would
                    // swallow the whole-row tap.
                    HStack {
                        Button {
                            runSuggest()
                        } label: {
                            HStack {
                                if suggesting {
                                    ProgressView().controlSize(.small)
                                } else {
                                    Image(systemName: "sparkles")
                                        .foregroundStyle(Color.accentColor)
                                }
                                Text(suggestFailed ? "AI Suggest (failed — try again)" : "AI Suggest")
                            }
                        }
                        .buttonStyle(.borderless)
                        .disabled(suggesting)
                        Spacer()
                        Button("Create", action: createIfNamed)
                            .buttonStyle(.borderless)
                            .fontWeight(.semibold)
                            .disabled(trimmedName.isEmpty || duplicateFolder != nil)
                    }
                    // [T-folder-duplicate-name] Name already taken. Says so, and
                    // offers the one-tap way out — filing into the existing group
                    // is almost always what was meant. Create is disabled above,
                    // so this is a real choice rather than a warning to ignore.
                    if let dup = duplicateFolder {
                        Button {
                            onChoose(.existing(dup.id))
                        } label: {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    // Folder name is user data — interpolated, not a key.
                                    Text("“\(dup.name)” already exists")
                                        .font(.subheadline)
                                    Text("Rename to something else, or tap to use it")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            } icon: {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundStyle(.orange)
                            }
                        }
                    }
                    if let merge = suggestedMerge {
                        Button {
                            onChoose(.existing(merge.folderId))
                        } label: {
                            Label {
                                // Folder name is user data — interpolated, not a key.
                                Text("Move into “\(merge.folderName)”?")
                            } icon: {
                                Image(systemName: "sparkles")
                                    .foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }

                // Rendered when there is anything to put in it: existing groups to
                // pick from, or a membership the "No Group" row can clear. Both
                // empty (no folders and the target is unfiled) means no section at
                // all, rather than a bare "Groups" header over nothing.
                if !items.isEmpty || anyFiled {
                    Section("Groups") {
                    // "No Group" — the explicit way to end up ungrouped.
                    //
                    // FIRST row of the Groups section, not a trailing section, for
                    // two reasons. (1) Reachability: measured on an iPhone 11, a
                    // trailing section put this row at y≈940 while the .medium
                    // detent ends at 896 — the accessibility tree reported
                    // `hittable: false`, i.e. the option existed but could not be
                    // tapped without first scrolling or expanding the sheet.
                    // (2) Semantics: it is a peer choice among "where does this
                    // chat live", so it belongs with the destinations rather than
                    // formatted like a destructive afterthought.
                    //
                    // Gated on `anyFiled`: for an already-ungrouped session it
                    // would be a no-op row, which reads as a broken control.
                    if anyFiled {
                        Button {
                            onChoose(.removeFromFolder)
                        } label: {
                            HStack(spacing: 8) {
                                // Sized to match FolderComposedIcon below so the
                                // row's text baseline lines up with the group rows.
                                ZStack {
                                    Circle()
                                        .fill(Color(UIColor.tertiarySystemFill))
                                        .frame(width: 40, height: 40)
                                    Image(systemName: "folder.badge.minus")
                                        .foregroundStyle(.secondary)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("No Group")
                                        .foregroundStyle(Color(UIColor.label))
                                        .lineLimit(1)
                                    Text("Remove from its current group")
                                        .font(.system(size: 13))
                                        .foregroundStyle(Color(UIColor.secondaryLabel))
                                        .lineLimit(1)
                                }
                                Spacer()
                            }
                        }
                        // VoiceOver reads label + hint as ONE action; without this
                        // the two stacked Texts are announced as unrelated elements.
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel(Text("No Group"))
                        .accessibilityHint(Text("Remove from its current group"))
                    }
                    ForEach(items) { item in
                            Button {
                                onChoose(.existing(item.folder.id))
                            } label: {
                                // Mirrors the home group card's identity row:
                                // same composed circle icon, same
                                // "N chats · context" subtitle (and thus the
                                // same localization keys).
                                HStack(spacing: 8) {
                                    FolderComposedIcon(glyphs: item.glyphs, diameter: 40)
                                    VStack(alignment: .leading, spacing: 2) {
                                        // Folder names are user data — verbatim.
                                        Text(item.folder.name)
                                            .foregroundStyle(Color(UIColor.label))
                                            .lineLimit(1)
                                        Group {
                                            if let sub = item.subtitle {
                                                Text("\(item.count) chats · \(sub)")
                                            } else if item.count > 0 {
                                                Text("\(item.count) chats")
                                            } else {
                                                Text("Empty group")
                                            }
                                        }
                                        .font(.system(size: 13))
                                        .foregroundStyle(Color(UIColor.secondaryLabel))
                                        .lineLimit(1)
                                    }
                                    Spacer()
                                }
                            }
                        }
                    }
                }
            }
            // Explicitly typed: a bare ternary of two literals infers `String`,
            // which would pick the non-localizing navigationTitle overload and
            // ship the English source text to every locale. As
            // `LocalizedStringKey` both branches keep their `%lld` interpolation
            // and resolve through Localizable.xcstrings.
            .navigationTitle(anyFiled
                             ? LocalizedStringKey("Change Group for \(sessionCount)")
                             : LocalizedStringKey("Move \(sessionCount) to Group"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private var trimmedName: String {
        newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// [T-folder-duplicate-name] The existing group whose name matches what the
    /// user is typing, if any.
    ///
    /// Case- and whitespace-insensitive because "Work" / "work " are the same
    /// group to a person, and silently creating a second one is how a list ends
    /// up with two identically-labelled folders that are impossible to tell
    /// apart in the picker.
    private var duplicateFolder: ChatFolder? {
        let key = trimmedName.lowercased()
        guard !key.isEmpty else { return nil }
        return items.first { $0.folder.name.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased() == key }?.folder
    }

    private func createIfNamed() {
        guard !trimmedName.isEmpty else { return }
        // Never silently create a second group with an existing name — the
        // inline notice below offers the existing one instead.
        guard duplicateFolder == nil else { return }
        let d = newFolderDesc.trimmingCharacters(in: .whitespacesAndNewlines)
        onChoose(.create(name: trimmedName, desc: d.isEmpty ? nil : d))
    }

    private func runSuggest() {
        suggesting = true
        suggestFailed = false
        suggestedMerge = nil
        let ids = sessionIds
        Task { @MainActor in
            defer { suggesting = false }
            do {
                switch try await AIChatViewModel.suggestFolder(forSessionIds: ids) {
                case .merge(let folderId, let folderName):
                    suggestedMerge = (folderId, folderName)
                case .create(let name, let desc):
                    newFolderName = name
                    if let desc { newFolderDesc = desc }
                    nameFieldFocused = true
                }
            } catch {
                // [T-ios-folder-suggest-retry] This catch used to swallow the
                // error entirely — the user saw "failed — try again" and the
                // log had nothing. Keep the UI flag, but record why.
                AppLogger(category: "AIChatVM").error("[FolderSuggest] UI caught error=\(error)")
                suggestFailed = true
            }
        }
    }
}

private enum SessionMenuAction {
    case togglePin(String)
    case exportJSON(String)
    case exportText(String)
    case editTitle(String)
    case regenerateTitle(String)
    case lockSession(String)
    case unlockSession(String)
    case duplicate(String)
    case forceSync(String)
    case forcePull(String)
    case select(String)
    case moveToFolder(String)
    case delete(String)
}

#if DEBUG
// TEMPORARY: SessionRow height probe to confirm List cell-height estimation
// jitter. Logs each distinct measured row height once (deduped) so we know
// the true height distribution before pinning a fixed .frame. Remove after.
private struct RowHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}
private let rowHeightLog = AppLogger(category: "RowHeight")

// Availability-gated scroll-phase probe (onScrollPhaseChange is iOS 18+).
private struct ScrollPhaseProbe: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollPhaseChange { old, new in
                rowHeightLog.info("[SCROLL] \(old) -> \(new)")
            }
        } else {
            content
        }
    }
}

private let rowHeightSeenLock = NSLock()
nonisolated(unsafe) private var rowHeightSeen = Set<Int>()
private func probeRowHeight(_ h: CGFloat, _ tag: String) {
    // Ignore the pre-layout zero/garbage pass — only real measured heights.
    guard h > 1, h.isFinite else { return }
    let key = Int((h * 2).rounded())  // 0.5pt buckets
    rowHeightSeenLock.lock()
    let isNew = rowHeightSeen.insert(key).inserted
    rowHeightSeenLock.unlock()
    if isNew {
        rowHeightLog.info("[ROWH] \(tag) height=\(String(format: "%.1f", h))pt")
    }
}
#endif

/// Sheets triggered from the toolbar menu, consolidated into a single `.sheet(item:)`.
enum ToolSheet: String, Identifiable {
    case settings
    case rootfsManagement
    case browser
    case browserManagement
    case syncMigrationDetail
    var id: String { rawValue }
}

struct ContentView: View {
    /// Prefix for draft session IDs. Each new chat gets a unique suffix
    /// so SwiftUI's `.id()` correctly destroys old views and creates new ones.
    private static let newSessionPrefix = "__new__"

    /// Whether a session ID represents an unsaved draft session.
    private static func isNewSessionId(_ id: String?) -> Bool {
        id?.hasPrefix(newSessionPrefix) == true
    }

    /// Generate a unique draft session ID.
    private static func makeNewSessionId() -> String {
        "\(newSessionPrefix)\(UUID().uuidString)"
    }

    private static let groupSeparator = "__grp__"

    /// Generate a draft session ID that also carries a model group selection.
    private static func makeNewSessionId(groupId: String) -> String {
        "\(newSessionPrefix)\(UUID().uuidString)\(groupSeparator)\(groupId)"
    }

    /// Extract the group ID embedded in a draft session ID, if present.
    private static func extractGroupId(from id: String) -> String? {
        guard let range = id.range(of: groupSeparator) else { return nil }
        let groupId = String(id[range.upperBound...])
        return groupId.isEmpty ? nil : groupId
    }

    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject var shareCoordinator: ShareCoordinator
    @ObservedObject private var deepLink = DeepLinkCoordinator.shared
    /// Subscribe to the router so changes to its `@Published` fields are
    /// observed by SwiftUI — without this, `onChange(of: router.newChatTrigger)`
    /// reads a static value and never fires after the view mounts. Quick
    /// Actions that arrive before this view's `.onReceive(.newChatRequested)`
    /// publisher is attached (typical cold-launch race) would otherwise be
    /// dropped.
    @ObservedObject private var quickActionRouter = QuickActionRouter.shared
    // [T-ios-ipad-sidebar-running-indicator-stale] Observe the activity /
    // concurrency trackers AT THE LIST LEVEL too (SessionRow already observes
    // them, but the #584 id-list ForEach reuses an already-materialized cell
    // for the running session and swallows the row's @ObservedObject change
    // when only the running flag flips — id list & resolved ChatSession value
    // are unchanged). Observing here re-evaluates the list body when
    // activeSessions / suspended flips, which regenerates each row's composite
    // diff key (see sessionRowKeys) so the ForEach can't skip re-materializing
    // the row whose running state changed.
    @ObservedObject private var sidebarActivityTracker = SessionActivityTracker.shared
    // Folder-header aggregates (anyUnread / anyPaused) read the badge store
    // during sidebarGroups; observing it here is what re-derives those
    // aggregates when a badge flips. Row-level observation alone would only
    // refresh the rows, not the header pass.
    @ObservedObject private var sidebarBadgeStore = SessionBadgeStore.shared
    @ObservedObject private var sidebarConcurrencyManager = SessionConcurrencyManager.shared
    @State private var sessions: [ChatSession] = []
    @State private var folders: [ChatFolder] = []
    /// Collapsed folder sections. Pure UI view-state: persisted locally, never
    /// synced (like SessionBadgeStore's .unread — cross-device expand state is
    /// noise, not signal).
    @State private var collapsedFolderIds: Set<String> =
        Set(UserDefaults.standard.stringArray(forKey: "collapsedFolderIds") ?? [])
    /// Folder ids whose EXPANDED header card is currently scrolled out above
    /// the list's visible top — drives the floating mini-bar. A Set (not a
    /// single id) because "expanded" is the DEFAULT folder state: before the
    /// accordion ever runs, several folders can be expanded at once, so more
    /// than one header can be offscreen. Display picks one deterministically.
    @State private var offscreenFolderHeaderIds: Set<String> = []
    /// Global Y of the list's visible top edge (under the nav bar), measured
    /// by the mini-bar overlay. The header probe compares its own global
    /// frame against this to decide "scrolled out". Changes on rotation only.
    @State private var folderMiniBarTopY: CGFloat = 0
    /// [T-ios-folder-accordion-scroll-anchor] Last observed global minY of each
    /// folder header row, keyed by folder id. Written by a lightweight probe on
    /// every header (collapsed ones included — the accordion needs the position
    /// of the header ABOUT to expand, which by definition is still collapsed
    /// when the tap lands). Read once per toggle to decide whether a scroll
    /// correction is needed at all; never drives layout, so the per-frame writes
    /// cost nothing but a dictionary store.
    @State private var folderHeaderTopY: [String: CGFloat] = [:]
    /// The list's visible BOTTOM edge in global coords, the lower bound of the
    /// "still on screen" test. Paired with `folderMiniBarTopY` (the top edge).
    @State private var folderListBottomY: CGFloat = 0
    /// Memo for the sidebar grouping — see SidebarGroupsMemo. Keeps the
    /// O(N) partition + per-folder aggregation off every AttributeGraph
    /// flush (activity ticks during streaming re-eval this body at high
    /// frequency, including mid-scroll).
    @State private var sidebarGroupsMemo = SidebarGroupsMemo()
    /// Pending "move to folder" — non-nil presents the picker sheet. Shared by
    /// the multi-select toolbar and the row context menu (single-selection is a
    /// one-element set).
    @State private var folderPickerRequest: FolderPickerRequest?
    /// Set when the picker applied a move that originated from multi-select;
    /// selection mode is exited in the sheet's onDismiss (not in the apply
    /// callback) to avoid the documented animation conflict.
    @State private var folderMoveApplied = false
    // Folder-header context-menu state (rename / dissolve / delete-all / new chat).
    @State private var folderToRename: ChatFolder?
    @State private var renameFolderText = ""
    @State private var renameFolderDesc = ""
    @State private var folderToDissolve: ChatFolder?
    /// When delete-all runs for a folder, the (by then empty) folder row is
    /// dropped after the sessions are gone. Cleared on cancel in onDismiss.
    @State private var pendingDeleteFolderId: String?
    /// "New chat in folder": the folder intent rides with the DRAFT ID, not a
    /// bare flag — a draft is promoted to a real row only on first message, so
    /// folder_id can't be written at draft time, and keying on the draft id
    /// means an abandoned draft never mis-files a later unrelated session.
    @State private var pendingFolderDraft: (draftId: String, folderId: String)?
    /// Folder id currently hovered by a drag (empty string = the "ungroup"
    /// target on a date-bucket header). Drives the drop highlight.
    @State private var dropTargetFolderId: String?
    /// [T-ios-session-list-equatable-jank] id → ChatSession lookup backing the
    /// sidebar rows. Held in @State (not a per-body-eval computed `[String:
    /// ChatSession]`) on purpose: a plain `let byId = computedDict` inside the
    /// List builder was captured as an AttributeGraph node input, so EVERY
    /// transaction flush deep-compared the whole dictionary
    /// (Dictionary.== → ChatSession.== × N) — the dominant scroll-deceleration
    /// CPU cost (HangDetector: flushTransactions → AGGraphSetOutputValue →
    /// Dictionary.== → ChatSession.==; Instruments: app-update phase 60–587ms
    /// spikes). @State is diffed by storage-box identity, not by deep value, so
    /// reading it from the row closure costs nothing per frame. Rebuilt only
    /// when `sessions` changes (see .onChange below).
    @State private var sessionsByIdCache: [String: ChatSession] = [:]
    /// Soul name shown as the sidebar title. Sourced from SOUL.md, falls
    /// back to "Minis". Refreshed whenever SoulStore posts .soulMdChanged.
    @State private var soulName: String = SoulStore.cachedMetadata.name.isEmpty
        ? "Minis" : SoulStore.cachedMetadata.name
    /// Subtitle state shown under the "Minis" sidebar title. nil hides the
    /// row; otherwise it renders as small capsules per type or a single
    /// status string. Refreshed by a 5s timer.
    @State private var migrationSubtitle: SyncSubtitleState?

    enum SyncSubtitleState: Equatable {
        case paused
        case migrating(percent: Int, byType: [(label: String, count: Int)])
        case syncing(byType: [(label: String, count: Int)])
        case upToDate
        /// Engine is not running yet (boot delay / transport still
        /// connecting / coming back from background). Title shows a
        /// neutral 'waiting' icon — not an error.
        case waiting

        static func == (a: SyncSubtitleState, b: SyncSubtitleState) -> Bool {
            switch (a, b) {
            case (.paused, .paused): return true
            case (.upToDate, .upToDate): return true
            case (.waiting, .waiting): return true
            case (.migrating(let p1, let t1), .migrating(let p2, let t2)):
                return p1 == p2 && t1.map { $0.label + "\($0.count)" } == t2.map { $0.label + "\($0.count)" }
            case (.syncing(let t1), .syncing(let t2)):
                return t1.map { $0.label + "\($0.count)" } == t2.map { $0.label + "\($0.count)" }
            default: return false
            }
        }
    }
    // [T-ios-migration-timer-sessionlist-uaf-crash] The migration-subtitle
    // refresh cadence used to be a process-lived `Timer.publish(every:5).autoconnect()`
    // subscribed via `.onReceive` on the sidebar Group (see sessionList). A
    // process-lived Combine publisher whose sink attribute AttributeGraph rebuilds
    // on every body transaction is a use-after-free hazard: a 5s tick delivered into
    // the sink while the graph tears down/rebuilds that attribute releases a dangling
    // sink closure (crash below). Replaced with a `.task`-driven async loop whose
    // lifetime SwiftUI owns and cancels deterministically — no graph-bound publisher.
    // See migrationSubtitleRefreshInterval / migrationSubtitleLoop.
    @State private var remoteDeviceSessions: [(device: SyncDevice, sessions: [ChatSession])] = []
    // showSettings consolidated into activeToolSheet (.settings)
    @State private var showTerminal = false
    @State private var showAlarmList = false
    @State private var hasAlarms = false
    @State private var activeToolSheet: ToolSheet?
    #if DEBUG
    // [debug] Keep the screen awake (disable the idle/auto-lock timer) while the
    // app is in the foreground. Memory-only on purpose — NOT persisted, so it
    // resets to off on every app launch. iOS automatically clears
    // `isIdleTimerDisabled` when the app leaves the foreground, so this is
    // re-applied on scenePhase == .active below; "foreground only" falls out for
    // free. Toggled from the DEBUG-only menu item in the sidebar more-menu.
    @State private var keepScreenAwake = false
    #endif
    @StateObject private var browserPool = BrowserTabPool()
    @State private var selectedSessionId: String?
    /// Shadow of the previously-selected session id, used to identify the
    /// outgoing vm in `onChange(selectedSessionId)` so we can suspend it
    /// for the AttributeGraph race window. SwiftUI's iOS 16 onChange API
    /// only delivers `newValue`; we maintain the previous value ourselves.
    @State private var previousSelectedSessionId: String?
    /// [T-ios-session-coldload-listsessions-block] Coalesces the delayed
    /// outgoing-session preview refresh so rapid session switching schedules
    /// one trailing listSessions() (after the incoming load settles) instead
    /// of stacking full scans onto the serialized ChatStore actor.
    @State private var outgoingPreviewRefreshWork: DispatchWorkItem?
    // [T-ios-listsessions-refresh-coalesce] Serialize session-list refreshes so
    // the ~12 refresh call sites never run listSessions() concurrently. A second
    // request that lands while one is in flight sets `pending` and is served by a
    // single trailing run — instead of each site spawning its own Task and
    // assigning its own array snapshot (the memgraph showed 3 live [ChatSession]
    // arrays from concurrent refreshes). Combined with the ChatStore-side cache
    // (T-ios-listsessions-cache), a clean refresh now returns the same cached
    // array, so this mainly prevents redundant Task churn.
    @State private var sessionRefreshInFlight = false
    @State private var sessionRefreshPending = false
    /// Whether the initial session load has completed (prevents showing the list before we decide to auto-navigate).
    @State private var didInitialLoad = false
    /// Controls sidebar visibility on iPad (automatic handles iPhone collapse).
    @State private var columnVisibility: NavigationSplitViewVisibility = .automatic

    /// Launch screen preference: 0=Auto, 1=Last Session, 2=New Chat.
    @AppStorage("launchScreen") private var launchScreen: Int = 0
    /// FAB position preference: false = right (default), true = left.
    @AppStorage("fabOnLeft") private var fabOnLeft = false
    /// Mirror of `SyncV2Bootstrap.isEnabled` so SwiftUI re-evaluates
    /// the iCloud-gated menu entries (per-session Force Sync / Force
    /// Pull and the multi-select Force Sync) the moment the user
    /// flips the toggle in Settings — without this, calling the
    /// static getter inline would only re-read on the next unrelated
    /// state change. `SyncV2Bootstrap.setEnabled` writes the same key.
    @AppStorage("cloudSync.v2.enabled") private var iCloudSyncEnabled: Bool = false
    /// Current drag offset of the FAB (reset to 0 on drop).
    @State private var fabDragOffset: CGFloat = 0

    // Multi-select
    @State private var isSelecting = false
    @State private var selectedIds: Set<String> = []
    /// Transient banner shown after a Force Sync action runs against
    /// a selection. Cleared after a few seconds via DispatchQueue.
    @State private var forceSyncToast: String? = nil
    @State private var forceSyncInFlight = false
    @State private var scrollToId: String?
    @State private var showDeleteConfirm = false
    @State private var isComputingDelete = false
    @State private var showExportPreview = false
    @State private var isExporting = false
    @State private var exportFileURL: URL?
    /// [T-export-preview-blank] The UNZIPPED payload backing `exportFileURL`, used
    /// only to render the preview. `exportFileURL` is the artifact the user shares
    /// or saves (a .zip), and zip bytes cannot be decoded as text — previewing it
    /// produced a blank page. Nil when there is nothing text-previewable.
    @State private var exportPreviewURL: URL?
    @State private var exportProgress: (done: Int, total: Int)? = nil
    @State private var exportSummary: ExportSummary? = nil
    @State private var deleteInfo: DeleteInfo?

    // Single delete
    @State private var sessionToDelete: ChatSession?
    @State private var singleDeleteInfo: DeleteInfo?

    // Title regeneration
    @State private var regeneratingTitleSessionId: String?

    // Edit session
    @State private var sessionToEdit: ChatSession?

    // [T-ios-crash-contextmenu-uaf] Stable action relay for session context menus.
    @State private var menuActions = SessionMenuActionChannel()

    // Search
    @State private var showSearchBar = false
    private var isSearching: Bool { showSearchBar && !searchText.isEmpty }
    @State private var searchText = ""
    /// Session IDs that matched the current search query (nil = no active filter).
    @State private var searchMatchedIds: Set<String>?
    /// Per-session matched-content snippet for sessions whose match was on
    /// message body rather than title — surfaced in the row's subtitle slot
    /// in place of the generic `lastMessage` preview. Empty when no active
    /// search or for title-only matches. Mirrors Android
    /// `SessionListViewModel.searchSnippets` (commit 545d585).
    /// T-search-highlight 8edb74f2.
    @State private var searchMatchSnippets: [String: String] = [:]
    @State private var searchTask: Task<Void, Never>?

    /// Width threshold below which the layout collapses to single-column (iPhone-style).
    private let compactThreshold: CGFloat = 700
    /// Whether the device is an iPad (iPhones always use stack layout regardless of width).
    private let isIPad = UIDevice.current.userInterfaceIdiom == .pad
    /// Whether the current window is wide enough for two-column layout.
    @State private var isWideLayout = false
    /// Navigation path for stack (compact) layout.
    @State private var navigationPath = NavigationPath()
    /// Tracks the session ID currently visible on the compact navigation stack.
    @State private var currentStackSessionId: String?
    /// [T-ios-stacknav-transition-attributegraph-race] Compact-layout analogue
    /// of `previousSelectedSessionId`: the session that was on the stack before
    /// the most recent `navigationPath` change, so the `onChange` observer can
    /// suspend the OUTGOING vm for the transition.
    ///
    /// A separate property is required — `currentStackSessionId` cannot serve
    /// here. `.onChange` is a view-update observer, so it runs after the state
    /// change is committed, and every navigation helper assigns
    /// `currentStackSessionId = newId` in the same synchronous block that
    /// mutates the path (see `switchToSession` / `handleNewChatRequest` /
    /// `openSession`). By observer time it therefore already holds the INCOMING
    /// id, and reading it would suspend the wrong vm — the one being mounted.
    @State private var previousStackSessionId: String?
    /// The real session ID after a draft session is persisted (iPad only).
    /// While set, the AIChatView for this session stays alive even though
    /// selectedSessionId may still be a draft ID.
    @State private var newSessionRealId: String?
    /// The draft ID that owns the current new-session view (iPad only).
    /// Used to redirect back when the user taps the real session row.
    @State private var activeDraftId: String?

    /// Last `QuickActionRouter.newChatTrigger` value we've already routed.
    /// SwiftUI's `onChange` fires for transitions, not for initial values,
    /// so we use this to decide whether `onAppear` (or `onChange`) still
    /// owes a `handleNewChatRequest()` call.
    @State private var consumedQuickActionTrigger: Int = 0

    /// Set when `handleNewChatRequest()` had to pop the iPhone
    /// NavigationStack before it could open the new draft session.
    /// `onChange(of: navigationPath.count == 0)` watches this and
    /// dispatches the new-draft open once the pop has fully settled.
    @State private var pendingNewChatAfterPop: Bool = false
    /// Pre-computed session id for the pending pop+open dance, so the
    /// id we hand to `QuickActionWorkflow.attachTargetSession` here
    /// matches the one `openSession` will use after the pop commits.
    @State private var pendingNewChatTargetId: String? = nil

    /// [T-ios-bg-nav-push-watchdog] A `navigationPath` write that arrived while
    /// the app was NOT active, held back until it is.
    ///
    /// Four `.ips` reports (1.12(16) 03:58 + 04:09, 1.13(1) 19:28 + 20:04, all
    /// `WatchdogVisibility: Background`) share one main-thread stack:
    ///
    ///     NavigationStackCoordinator.update(to:from:navigationController:…)
    ///     → UIKitNavigationController.pushViewController(_:animated:)
    ///     → -[UINavigationController _immediatelyApplyViewControllers:…]
    ///     → -[UINavigationBar _redisplayItems]
    ///     → -[UIView(Hierarchy) layoutBelowIfNeeded]      ← synchronous, whole tree
    ///
    /// `_immediatelyApplyViewControllers` is the non-animated branch: it runs
    /// the pushed screen's ENTIRE first layout pass inline, with no yield point.
    /// The screen being pushed is always `AIChatView` (the sole
    /// `navigationDestination` below) — the heaviest view in the app — so that
    /// pass costs hundreds of ms even on a good day.
    ///
    /// The reports' CPU statistics show the main thread pinned at ~100% of one
    /// core for the whole window (10.230s of application CPU inside a 10.00s
    /// scene-update allowance; 5.309 / 5.643 / 5.517s inside the 5.0s
    /// terminate allowance — the "17% CPU" line is percent-of-six-cores). No
    /// other thread holds a lock; SwiftUI's AsyncRenderer is itself parked on
    /// `_MovableLockLock` waiting for the ViewGraph lock main holds. So this is
    /// a compute-bound main-thread stall, not a deadlock.
    ///
    /// While the agent loop runs backgrounded (`beginBackgroundTask("AgentLoop")`
    /// + silent-audio keep-alive), notification / deep-link / QuickAction /
    /// share routes can all still write `navigationPath`. Backgrounded, that
    /// push renders nothing a user can see, yet spends its full layout cost
    /// inside the 5s termination or 10s scene-update budget — and the process
    /// gets SIGKILL'd. (The 03:58 report's `procExitAbsTime` lines up exactly
    /// with the 04:09 report's `procLaunch`: killed, relaunched, and killed
    /// again 11 minutes later on the same path.)
    ///
    /// Deferring costs nothing: the destination is offscreen either way, and
    /// on foreground return the same layout runs with a full frame budget
    /// instead of against a watchdog clock.
    ///
    /// Carries the deferral instant so a stale request can be dropped rather
    /// than flushed — see `pendingBackgroundNavigationTTL`.
    @State private var pendingBackgroundNavigation: (path: NavigationPath, deferredAt: Date)?

    /// [T-ios-bg-nav-push-watchdog] How long a deferred push stays valid.
    ///
    /// The gate above answers "don't push while backgrounded"; this answers
    /// "…but for how long is that push still what the user wants?". A push
    /// deferred at 02:00 by a background agent-loop notification is not a
    /// destination the user asked for when they open the app at 09:00 to
    /// glance at the session list — flushing it would yank them into an
    /// unrelated session with no action of theirs behind it. That is a
    /// navigation-semantics bug, not a crash, but it is the direct cost of the
    /// deferral this mechanism introduced, so it belongs here.
    ///
    /// 90s: the case worth honouring is "user taps a notification / deep link,
    /// app foregrounds, push lands" — that round trip is seconds, and even a
    /// slow cold launch with a rootfs warm-up stays well inside a minute.
    /// Anything older is the app having sat suspended, where the reopen is far
    /// more likely to be the user's own intent than a reply to that stale
    /// request. Deliberately much tighter than the 15-minute "latest session is
    /// stale, open a new one instead" heuristic further down: that one picks
    /// between two reasonable destinations, whereas this one decides whether to
    /// override where the user just chose to be.
    private static let pendingBackgroundNavigationTTL: TimeInterval = 90

    var body: some View {
        GeometryReader { geo in
            let wide = isIPad && geo.size.width >= compactThreshold
            Group {
                if wide {
                    splitLayout
                } else {
                    stackLayout
                }
            }
            .overlay(alignment: .top) {
                if let toast = forceSyncToast {
                    ForceSyncToastBanner(text: toast)
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .padding(.top, 8)
                }
            }
            .animation(.easeInOut(duration: 0.25), value: forceSyncToast)
            .onChange(of: wide) { newWide in
                isWideLayout = newWide
            }
            .onAppear {
                isWideLayout = wide
                wireMenuActions()
            }
        }
        .onReceive(
            NotificationCenter.default.publisher(for: .sessionDidCreate),
            perform: handleSessionCreatedForPendingFolder
        )
        .onReceive(NotificationCenter.default.publisher(for: .newChatRequested)) { _ in
            handleNewChatRequest()
        }
        // Cold-launch belt-and-braces: a Home Screen Quick Action that
        // fires before `.onReceive(.newChatRequested)` is attached
        // (WindowGroup still mounting) would otherwise be lost. The
        // router bumps `newChatTrigger` whenever it handles a shortcut.
        //
        // `quickActionRouter` is an `@ObservedObject`, so SwiftUI re-runs
        // body on every bump and `onChange` actually fires. The
        // `consumedQuickActionTrigger` state below tracks the last value
        // we've already routed — on cold launch the router might bump to
        // 1 (or higher, if the user invokes shortcuts multiple times
        // before the WindowGroup mounts) BEFORE our `.onAppear` runs;
        // we therefore also check the initial value on appear and route
        // any unconsumed bumps then.
        .onChange(of: quickActionRouter.newChatTrigger) { newValue in
            guard newValue != consumedQuickActionTrigger else { return }
            consumedQuickActionTrigger = newValue
            handleNewChatRequest()
        }
        .onReceive(QuickActionWorkflow.shared.$state) { newState in
            // Workflow advanced to pendingDispatch (either same-runloop
            // because we were already home, or after markHome fired
            // from a navigation change). Open the new session now.
            if case .pendingDispatch = newState {
                openSessionForPendingQuickAction()
            }
        }
        .onAppear {
            if quickActionRouter.newChatTrigger != consumedQuickActionTrigger {
                consumedQuickActionTrigger = quickActionRouter.newChatTrigger
                // Defer one runloop so the NavigationStack body has a
                // chance to attach `$navigationPath` before we append to
                // it — otherwise the append on a freshly-mounted stack
                // can be lost.
                DispatchQueue.main.async {
                    handleNewChatRequest()
                }
            }
            // The Appearance language picker wrote "pendingSettingsReopen"
            // right before changing appLanguage, which forced the root
            // `.id(appLanguage)` rebuild that just dropped + re-mounted us.
            // Reopen the Settings sheet so the user lands back where they
            // were instead of stranded on the chat list. SettingsSheet's
            // own onAppear pushes the saved destination onto its navPath.
            if UserDefaults.standard.string(forKey: "pendingSettingsReopen") != nil {
                DispatchQueue.main.async {
                    activeToolSheet = .settings
                }
            }
            // [T-ios-bg-nav-push-watchdog] Backstop for the scenePhase flush.
            // `.onChange(of: scenePhase)` only fires on a TRANSITION, so a
            // deferral that happened before this view mounted — a cold launch
            // straight into the background, or a root remount (the
            // `.id(appLanguage)` rebuild above) — would leave the push stranded
            // with no later transition to release it. Deferred one runloop for
            // the same reason the quick-action path above is: the
            // NavigationStack must have attached `$navigationPath` first.
            DispatchQueue.main.async {
                flushPendingBackgroundNavigation()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .sessionDidCreate)) { note in
            guard isWideLayout, let newId = note.object as? String else { return }
            let noteDraftId = (note.userInfo as? [String: String])?["draftId"]
            draftLog.info("🔑DRAFT sessionDidCreate realId=\(newId) noteDraftId=\(noteDraftId ?? "nil") selId=\(selectedSessionId ?? "nil") curReal=\(newSessionRealId ?? "nil") curDraft=\(activeDraftId ?? "nil")")
            // Verify this notification came from the currently active draft.
            // A late notification from a previous (now-destroyed) draft must be ignored.
            guard let selId = selectedSessionId, Self.isNewSessionId(selId),
                  noteDraftId == selId else {
                draftLog.info("🔑DRAFT sessionDidCreate IGNORED (draftId mismatch or not a draft)")
                // [T-ios-state-publish-offmain-crash] ChatStore (an actor) posts
                // .sessionDidCreate/.sessionDidUpdate from its background
                // executor; NotificationCenter delivers synchronously on that
                // thread, so this onReceive closure can run off-main. A bare
                // Task{} started here inherits the (background) execution context,
                // so `sessions =` (a @State write) lands off-main → "Publishing
                // changes from background threads" + AttributeGraph corruption of
                // the [ChatSession]/[String:ChatSession] state it deep-compares,
                // crashing in ChatSession.== / deinit during flushTransactions.
                // This onReceive can be delivered off-main, so hop explicitly —
                // refreshSessionList's @State writes must land on the main thread.
                Task { @MainActor in
                    refreshSessionList()
                }
                return
            }
            newSessionRealId = newId
            activeDraftId = selId
            draftLog.info("🔑DRAFT sessionDidCreate ACCEPTED newSessionRealId=\(newId) activeDraftId=\(selId)")
            // [T-ios-state-publish-offmain-crash] force main-thread @State write
            Task { @MainActor in
                refreshSessionList()
            }
        }
        .onReceive(
            // Throttle (not debounce): session-list updates are infrequent (one
            // per agent tool round, seconds apart), but a long multi-tool task
            // emits a steady stream of them. `.debounce` was reset by every new
            // event, so during a continuously-running task the list NEVER
            // refreshed until the task fully stopped — the row stayed on its
            // stale preview ("No messages yet") the whole time. `.throttle`
            // fires the first event right away and then at most once per second,
            // so the preview keeps up with each tool round without thrashing
            // `listSessions`.
            NotificationCenter.default.publisher(for: .sessionDidUpdate)
                .throttle(for: .seconds(1), scheduler: RunLoop.main, latest: true)
        ) { _ in
            // [T-ios-state-publish-offmain-crash] force main-thread @State write
            Task { @MainActor in
                refreshSessionList()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .moveInputToSession)) { note in
            guard let targetId = (note.userInfo as? [String: String])?["targetId"] else { return }
            // Skip navigation if the target session is already visible
            if isWideLayout {
                guard selectedSessionId != targetId && newSessionRealId != targetId else { return }
                // Wide layout replaces selection — no stack to worry about
                openSession(targetId)
            } else {
                // [T-ios-moveto-transfer-race] No early-return when the target
                // already reads as current: a swallowed push leaves
                // `currentStackSessionId` set to a target that never appeared,
                // and bailing here is exactly what made a retry do nothing.
                // switchToSession is idempotent, so re-running it for a target
                // that genuinely is on screen is harmless.
                switchToSession(targetId)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .openSessionFromIntent)) { note in
            guard let sessionId = (note.userInfo as? [String: String])?["sessionId"] else { return }
            // [T-notification-tap-vs-launch-session] Warm path owns this
            // navigation: drop the cold-launch buffer copy and stamp the
            // handling time so an in-flight launch `.task` (the post can land
            // during its `await listSessions()`) doesn't clobber the target
            // session with the Launch Session default afterwards.
            NotificationNavigationStore.shared.markHandled()
            // Skip navigation if the target session is already visible
            if isWideLayout {
                guard selectedSessionId != sessionId && newSessionRealId != sessionId else { return }
                openSession(sessionId)
            } else {
                // [T-ios-moveto-transfer-race] Same atomic replacement as the
                // move path — the old animated-pop-then-delayed-push had the
                // same swallowed-push failure mode here.
                guard currentStackSessionId != sessionId else { return }
                switchToSession(sessionId)
            }
        }
        .fullScreenCover(isPresented: $showTerminal) {
            NavigationStack {
                ISHTerminalView(showCloseButton: true)
            }
        }
        .sheet(isPresented: $showAlarmList, onDismiss: { fetchAlarmsIfNeeded() }) {
            AlarmListView()
        }
        .sheet(item: $activeToolSheet) { sheet in
            switch sheet {
            case .settings:
                SettingsSheet(showTerminal: $showTerminal)
            case .rootfsManagement:
                NavigationStack {
                    RootfsManagementView()
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("Done") { activeToolSheet = nil }
                            }
                        }
                }
            case .browser:
                BrowserSheetView(pool: browserPool)
            case .browserManagement:
                NavigationStack {
                    BrowserManagementView(pool: browserPool)
                }
            case .syncMigrationDetail:
                NavigationStack {
                    SyncMigrationDetailView()
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("Done") { activeToolSheet = nil }
                            }
                        }
                }
            }
        }
        // Something else is taking over the screen (an incoming share, a
        // WebApp deep link, a `.minisbak` opened from Files). iOS will not
        // present a second sheet from the same root while one is up, so a tool
        // sheet left open here silently swallows the new presentation — which
        // is exactly how opening a backup while sitting in Settings did
        // nothing at all. Clearing it lets the incoming content surface.
        .onReceive(NotificationCenter.default.publisher(for: .dismissAllImmersivePresentations)) { _ in
            if activeToolSheet != nil { activeToolSheet = nil }
        }
        .sheet(item: $sessionToDelete) { session in
            DeleteConfirmSheet(info: $singleDeleteInfo, isLoading: false) {
                print("[DELETE] onDelete called for session: \(session.id)")
                deleteSession(session)
                sessionToDelete = nil
                singleDeleteInfo = nil
            }
            .onAppear {
                print("[DELETE] Sheet appeared. singleDeleteInfo is \(singleDeleteInfo == nil ? "nil" : "non-nil, sessionCount=\(singleDeleteInfo!.sessionCount)")")
            }
            .presentationDetents([.medium])
        }
        .sheet(item: $sessionToEdit) { session in
            SessionEditSheet(session: session) { newTitle, newCategory in
                // [T-ios-state-publish-offmain-crash] @MainActor so the @State
                // write after the actor-hop await stays on the main thread.
                Task { @MainActor in
                    await ChatStore.shared.updateSessionTitle(session.id, title: newTitle, category: newCategory)
                    refreshSessionList()
                }
                sessionToEdit = nil
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showDeleteConfirm, onDismiss: {
            if deleteInfo == nil {
                // Deletion was performed — reset selection mode after sheet is fully dismissed
                isSelecting = false
                selectedIds.removeAll()
            } else {
                // Cancelled — a pending delete-folder-with-sessions must not
                // linger and attach itself to a later unrelated delete.
                pendingDeleteFolderId = nil
            }
        }) {
            DeleteConfirmSheet(info: $deleteInfo, isLoading: isComputingDelete) {
                deleteSelectedSessions()
                showDeleteConfirm = false
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showExportPreview) {
            ExportPreviewSheet(fileURL: exportFileURL, previewURL: exportPreviewURL, summary: exportSummary)
        }
        .sheet(item: $folderPickerRequest, onDismiss: {
            // Mirror the delete sheet's pattern (see the comment near
            // computeDeleteInfo): selection state is reset only after the
            // sheet is fully dismissed to avoid animation conflicts.
            if folderMoveApplied {
                folderMoveApplied = false
                isSelecting = false
                selectedIds.removeAll()
            }
        }) { req in
            FolderPickerSheet(
                items: folderPickerItems(),
                sessionIds: Array(req.sessionIds),
                anyFiled: req.anyFiled
            ) { choice in
                Task { @MainActor in
                    switch choice {
                    case .existing(let folderId):
                        await ChatStore.shared.setFolder(folderId, forSessions: Array(req.sessionIds))
                    case .create(let name, let desc):
                        let folder = await ChatStore.shared.createFolder(name: name, desc: desc)
                        await ChatStore.shared.setFolder(folder.id, forSessions: Array(req.sessionIds))
                    case .removeFromFolder:
                        await ChatStore.shared.setFolder(nil, forSessions: Array(req.sessionIds))
                    }
                    refreshSessionList()
                }
                if req.fromMultiSelect { folderMoveApplied = true }
                folderPickerRequest = nil
            }
            .presentationDetents([.medium, .large])
        }
        .modifier(FolderAlertsModifier(
            folderToRename: $folderToRename,
            renameFolderText: $renameFolderText,
            renameFolderDesc: $renameFolderDesc,
            folderToDissolve: $folderToDissolve,
            allFolders: folders,
            memberCount: { fid in sessions.filter { $0.folderId == fid }.count },
            onRename: { folder, name, desc in
                Task { @MainActor in
                    // Always pass desc (empty clears): the dialog is seeded with
                    // the stored value when it opens ([T-folder-rename-desc-wipe]),
                    // so whatever is in the field on Rename is the user's
                    // intended state.
                    await ChatStore.shared.renameFolder(folder.id, name: name, desc: desc)
                    refreshSessionList()
                }
            },
            onDissolve: { folder in
                Task { @MainActor in
                    _ = await ChatStore.shared.dissolveFolder(folder.id)
                    refreshSessionList()
                }
            },
            // [T-folder-duplicate-name] Merge the renamed group into the
            // existing same-named one: move its chats over, then dissolve the
            // now-empty source. Dissolve (not delete) is deliberate — it only
            // ever ungroups, so a mistake here can never cost a session.
            onMergeInto: { source, target in
                Task { @MainActor in
                    let memberIds = sessions.filter { $0.folderId == source.id }.map(\.id)
                    if !memberIds.isEmpty {
                        await ChatStore.shared.setFolder(target.id, forSessions: memberIds)
                    }
                    _ = await ChatStore.shared.dissolveFolder(source.id)
                    refreshSessionList()
                }
            }
        ))
        .overlay {
            if isExporting {
                ZStack {
                    Color.black.opacity(0.3).ignoresSafeArea()
                    VStack(spacing: 12) {
                        ProgressView()
                            .controlSize(.large)
                        if let p = exportProgress, p.total > 0 {
                            Text(AppLocalized("Exporting… \(p.done) / \(p.total)"))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        } else {
                            Text(AppLocalized("Exporting…"))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(24)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
                .transition(.opacity)
                .animation(.easeInOut(duration: 0.2), value: isExporting)
            }
        }
        .task {
            sessions = await ChatStore.shared.listSessions()
            // Folders must load WITH the first session batch: groupedSessionIDs
            // treats a folder_id whose folder isn't loaded as an orphan and
            // renders the session ungrouped, so a first paint with sessions
            // but no folders shows a flat list and the folder cards only
            // "appear after a while" (whenever refreshSessionList next ran —
            // the exact symptom reported from the Mac build).
            folders = await ChatStore.shared.listFolders()
            let shareAlreadyHandled = shareCoordinator.bufferVersion > 0
            // A Home Screen Quick Action that fired during launch will
            // open the right session itself via `quickActionRouter.newChatTrigger`.
            // Skip the Launch Session logic so we don't open a second,
            // conflicting session (the "last session" / "new chat"
            // launchScreen branch races the shortcut and the user ends
            // up watching one view replaced by the other).
            // Two signals indicate a quick-action launch is in flight:
            //   1. Router bumped newChatTrigger but ContentView hasn't
            //      consumed it yet (race: .task runs before .onAppear).
            //   2. QuickActionWorkflow is past .idle — router already
            //      called start(), workflow owns the next session to
            //      open. Even if (1) flipped because .onAppear already
            //      ran and consumed the trigger, the workflow is still
            //      mid-flight and the launch session would clobber it.
            let workflowActive: Bool = {
                if case .idle = QuickActionWorkflow.shared.state { return false }
                return true
            }()
            let quickActionPending = quickActionRouter.newChatTrigger != consumedQuickActionTrigger || workflowActive
            shareLog.info("[Share] .task: hasPendingShare=\(shareCoordinator.hasPendingShare) launchScreen=\(launchScreen) sessions=\(sessions.count) bufferVersion=\(shareCoordinator.bufferVersion) shareAlreadyHandled=\(shareAlreadyHandled) quickActionPending=\(quickActionPending) workflowActive=\(workflowActive)")

            // [T-notification-tap-vs-launch-session] A notification tap's
            // explicit target session outranks every launch-screen default.
            // Cold launch: didReceive fired before our .onReceive subscriber
            // existed, so the post was lost — the buffered copy is the only
            // surviving signal. Consume it and navigate. Warm-ish overlap: the
            // post arrived while this .task was awaiting listSessions() and
            // .onReceive already navigated — handledRecently suppresses the
            // launch-screen default so it can't clobber that navigation.
            if let notificationTarget = NotificationNavigationStore.shared.takePending() {
                shareLog.info("[Share] .task: notification tap target=\(notificationTarget.prefix(8)) — overriding launchScreen logic")
                var tx = Transaction()
                tx.disablesAnimations = true
                withTransaction(tx) { openSession(notificationTarget) }
            } else if NotificationNavigationStore.shared.handledRecently {
                shareLog.info("[Share] .task: notification navigation just handled — skipping launchScreen logic")
            } else if quickActionPending {
                shareLog.info("[Share] .task: quick action pending — deferring launchScreen logic to QuickActionRouter")
            } else if shareAlreadyHandled {
                // onChange(hasPendingShare) already processed the share and
                // opened a new session before .task ran. Skip normal launch
                // screen logic so we don't clobber it with a different session.
                shareLog.info("[Share] .task: share already handled by onChange — skipping launchScreen logic")
            } else if shareCoordinator.hasPendingShare {
                // onChange hasn't fired yet (e.g. onOpenURL arrived during await).
                // Process share here and open a new session for it.
                shareLog.info("[Share] .task: processing pending share")
                processPendingShare()
                shareLog.info("[Share] .task: buffer stored, bufferVersion=\(shareCoordinator.bufferVersion) buffer=\(shareCoordinator.pendingShareBuffer != nil)")
                // [T-share-routes-to-background-session] Cold launch: nothing is
                // on screen yet (this branch runs before any launch-screen
                // navigation), so the "foreground session" the warm path looks
                // for does not exist and a new session IS the right
                // destination. It is still stamped onto the buffer, which is
                // what stops a session restored moments later — e.g. the
                // Launch-Session default, or a chat resuming an agent loop —
                // from mounting first and swallowing the share.
                let target = Self.makeNewSessionId()
                shareCoordinator.setBufferTarget(target)
                shareLog.info("[Share] .task: cold launch — opening new session \(target.prefix(16)) for share")
                var tx = Transaction()
                tx.disablesAnimations = true
                withTransaction(tx) { openSession(target) }
            } else if CrashReporter.shared.shouldBypassSessionRestore {
                // [T-ios-session-crash-loop] The last two launches both died in
                // the foreground within a minute of each other — the signature
                // of a session that faults while loading and is then re-opened
                // automatically on the next launch, which the user cannot
                // escape from inside the app (they can reach neither Settings
                // to change the launch screen nor the list to delete it).
                //
                // Open nothing: fall through to the session list so the app is
                // usable again. Deliberately placed AFTER the notification-tap
                // and share branches — those are explicit, just-expressed user
                // intent, and a stale crash flag must not swallow them.
                CrashReporter.shared.clearCrashLoopFlag()
                shareLog.warning("[Share] .task: crash-loop detected — skipping session restore, landing on the session list")
            } else {
                // No share — normal launch screen behavior
                switch launchScreen {
                case 1:
                    if let latest = sessions.first {
                        var tx = Transaction()
                        tx.disablesAnimations = true
                        withTransaction(tx) { openSession(latest.id) }
                    }
                case 2:
                    var tx = Transaction()
                    tx.disablesAnimations = true
                    withTransaction(tx) { openSession(Self.makeNewSessionId()) }
                case 3:
                    break
                default:
                    if !sessions.isEmpty,
                       let latest = sessions.first,
                       Date().timeIntervalSince(latest.updatedAt) > 15 * 60 {
                        var tx = Transaction()
                        tx.disablesAnimations = true
                        withTransaction(tx) { openSession(Self.makeNewSessionId()) }
                    } else if isWideLayout, let latest = sessions.first {
                        var tx = Transaction()
                        tx.disablesAnimations = true
                        withTransaction(tx) { openSession(latest.id) }
                    }
                }
            }
            // iPad split launch: every launchScreen branch above has resolved
            // by now, so if the restored selection lives inside a collapsed
            // folder, expand that folder (accordion — closes the others) so
            // the selected row is actually visible in the sidebar instead of
            // hidden behind a collapsed card.
            if isWideLayout, let sid = selectedSessionId,
               let fid = sessions.first(where: { $0.id == sid })?.folderId,
               collapsedFolderIds.contains(fid) {
                toggleFolderCollapsed(fid)
            }
            didInitialLoad = true
            fetchAlarmsIfNeeded()
            await refreshRemoteDeviceSessions()
        }
        .onReceive(NotificationCenter.default.publisher(for: .cloudSyncDidFetchChanges)) { _ in
            // [T-ios-state-publish-offmain-crash] cloud-sync fetch fires off-main;
            // force the @State write onto the main thread.
            Task { @MainActor in
                refreshSessionList()
            }
        }
        // [T-ios-session-list-equatable-jank] Keep the id→session lookup cache
        // in sync with `sessions`. Rebuilding here (on actual list mutation)
        // instead of per body-eval is what removes the per-frame Dictionary.==
        // / ChatSession.== diff from the scroll transaction.
        .onChange(of: sessions) { _ in
            rebuildSessionsByIdCache()
        }
        .onChange(of: selectedSessionId) { newValue in
            // [T-ios-session-switch-attributegraph-race] Hosting-view race
            // mitigation: when the user switches between two sessions while
            // both vms are mid-stream, the outgoing AIChatView's UIHostingView
            // subgraph is being torn down at the same instant the new
            // session's hosting views are mounting. A concurrent mutation on
            // the outgoing vm (any @Published delta, scroll signal, etc.)
            // races with `AG::Subgraph::NodeCache::~NodeCache` on the same
            // AsyncRenderer thread → EXC_BAD_ACCESS (build-48 crash
            // Minis-2026-06-01-134710.ips). Suspend the outgoing vm here,
            // then schedule a resume on a short delay so when the user comes
            // back to that session everything catches up. Run before the
            // redirect/tracking-clear logic so we always pin the right id.
            // [T-ios-session-coldload-listsessions-block] Were we leaving a real
            // (persisted, non-new) session? Only then can its list-row preview
            // have drifted and need a navigation-time refresh. Captured before
            // previousSelectedSessionId is overwritten below.
            let hadOutgoingRealSession: Bool = {
                guard let outgoing = previousSelectedSessionId, outgoing != newValue else { return false }
                return !Self.isNewSessionId(outgoing)
            }()
            if let outgoingId = previousSelectedSessionId, outgoingId != newValue {
                if let outgoingVm = ViewModelCache.shared.get(for: outgoingId) {
                    outgoingVm.setSuspendedForTransition(true)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak outgoingVm] in
                        outgoingVm?.setSuspendedForTransition(false)
                    }
                }
            }
            previousSelectedSessionId = newValue
            if let sid = newValue {
                SessionBadgeStore.shared.remove(.unread, for: sid)
                AIChatViewModel.activeSessionId = sid
            } else {
                AIChatViewModel.activeSessionId = nil
            }
            draftLog.info("🔑DRAFT onChange(selectedSessionId) newValue=\(newValue ?? "nil") realId=\(newSessionRealId ?? "nil") draftId=\(activeDraftId ?? "nil")")
            // If user taps the real session row that was created from a draft,
            // redirect back to the draft ID to preserve the AIChatView instance.
            if let realId = newSessionRealId, newValue == realId,
               let draftId = activeDraftId {
                draftLog.info("🔑DRAFT onChange REDIRECT to draftId=\(draftId)")
                selectedSessionId = draftId
                return
            }
            // Clear new-session tracking when navigating to a different session
            if !Self.isNewSessionId(newValue) {
                draftLog.info("🔑DRAFT onChange CLEAR tracking (non-draft)")
                newSessionRealId = nil
                activeDraftId = nil
            }
            // iPad: detail cleared (newValue == nil) — if a quick-action
            // workflow is ensuring-home, advance it now.
            if newValue == nil, case .ensuringHome = QuickActionWorkflow.shared.state {
                QuickActionWorkflow.shared.markHome()
            }
            // [T-ios-search-focus-sticky] iPad split: selecting a session (or
            // new chat) with an empty search drops the sticky search bar.
            if newValue != nil {
                dismissSearchIfEmptyOnNavigate()
            }
            // [T-ios-session-coldload-listsessions-block] Only refresh the list
            // when LEAVING a real session — that outgoing session's preview may
            // have changed while the user was inside it. Entering never changes
            // existing rows. Even so, this listSessions() is a ~1.4s full scan
            // on a large DB and, on the serialized ChatStore actor, is
            // non-preemptible — if it starts before the incoming session's
            // loadSession() reaches its 2nd actor hop (getMemoryEnabled →
            // loadMessages), that hop waits the whole scan out and the spinner
            // hangs ~1.5s. So DELAY the outgoing-preview refresh well past the
            // incoming load (which completes in ~50-150ms) instead of racing it
            // onto the actor. Content changes are already covered by
            // .sessionDidUpdate / .sessionDidCreate / .cloudSyncDidFetchChanges
            // / pin / delete / edit, so this delayed refresh is belt-and-braces.
            if hadOutgoingRealSession {
                scheduleOutgoingPreviewRefresh()
            }
        }
        .onChange(of: navigationPath) { _ in
            // [T-ios-stacknav-transition-attributegraph-race] The SAME hosting-
            // view teardown race the `selectedSessionId` observer above guards
            // — but that observer only fires in the SPLIT (iPad / wide) layout.
            // iPhone drives navigation through `navigationPath`, so the
            // outgoing chat's vm was NEVER suspended for a push/pop, and the
            // mitigation added for the 2026-06-01 build-48 crash simply did not
            // exist on the compact path.
            //
            // Crash 2026-08-10 19:23 (Minis 1.12(1), iOS 26.5.2, iPhone18,1 —
            // a STACK-layout device): EXC_BAD_ACCESS at 0xffffffff00000000 in
            // AG::Subgraph::~Subgraph → NodeCache::~NodeCache, reached from
            // `NavigationStackCoordinator.navigationController(_:willShow:)` →
            // `ejectDeferred/sanitize` → `replaceRootViewWhenSafe` → a
            // synchronous `ViewGraph.updateOutputs` inside the UIKit
            // transition-completion callback. The device log shows exactly the
            // documented precondition: the agent loop kept streaming straight
            // through the transition and PAST the view's teardown —
            //
            //   19:23:26.731  chat slides offscreen (x=402…804, transition starts)
            //   19:23:26.735  [TOOL:STREAMING] shell_execute …
            //   19:23:27.138  [TOOL:STREAMING] …
            //   19:23:27.213  AIChatView.onDisappear vm.isProcessing=true
            //   19:23:27.349  [TOOL:STREAMING] …   (still mutating after teardown)
            //
            // so @Published deltas were feeding a subgraph while UIKit was
            // invalidating it. Suspend the outgoing vm for the transition,
            // exactly as the split path does, and resume on the same 0.4s
            // delay so the session catches up when the user returns.
            //
            // The outgoing id is tracked separately (`previousStackSessionId`)
            // rather than read from `currentStackSessionId` — see that
            // property's doc-comment for why the latter is already the INCOMING
            // id by the time this observer runs.
            // The incoming id is `currentStackSessionId` only while the path is
            // NON-empty; a pop to the session list leaves it momentarily stale
            // (it is cleared further down in this same handler), so an empty
            // path means "incoming = nothing" and the outgoing vm must still be
            // suspended. Getting this wrong would skip the pop — which is the
            // exact transition the crash log captured.
            let incomingStackId: String? = navigationPath.isEmpty ? nil : currentStackSessionId
            let outgoingStackId = previousStackSessionId
            previousStackSessionId = incomingStackId
            if let outgoingId = outgoingStackId,
               outgoingId != incomingStackId,
               let outgoingVm = ViewModelCache.shared.get(for: outgoingId) {
                outgoingVm.setSuspendedForTransition(true)
                // Unconditional resume on the same 0.4s delay the split path
                // uses. `weak` so a deallocated vm simply drops it, and
                // `setSuspendedForTransition` is guarded on an actual change,
                // so overlapping transitions cannot leave the flag stuck.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak outgoingVm] in
                    outgoingVm?.setSuspendedForTransition(false)
                }
            }
            // [T-ios-search-focus-sticky] iPhone stack: pushing into a session
            // (or new chat) with an empty search drops the sticky search bar.
            // Only on push (path non-empty) — popping back must NOT clear an
            // active search the user is returning to.
            if !navigationPath.isEmpty {
                dismissSearchIfEmptyOnNavigate()
            }
            if navigationPath.isEmpty {
                currentStackSessionId = nil
                AIChatViewModel.activeSessionId = nil
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                // If a quick-action workflow asked us to ensure-home,
                // we're there now — let it advance to pendingDispatch.
                if case .ensuringHome = QuickActionWorkflow.shared.state {
                    QuickActionWorkflow.shared.markHome()
                }
            }
            // [T-ios-session-coldload-listsessions-block] Only refresh when
            // popping back to home (path emptied) — that's when a preview that
            // changed inside the session the user just left needs to show. On
            // PUSH (entering a session, the cold-open hot path) this scan only
            // head-of-line-blocked loadSession on the ChatStore actor. Delayed
            // so it never races the incoming load's actor hops.
            if navigationPath.isEmpty {
                scheduleOutgoingPreviewRefresh()
            }
            fetchAlarmsIfNeeded()
        }
        .onChange(of: shareCoordinator.hasPendingShare) { hasPending in
            if hasPending {
                let hadRecord = SharedContainerStore.loadPendingShare() != nil
                processPendingShare()
                // [T-share-double-raise] Only navigate when this raise actually
                // carried content. A duplicate raise finds the record already
                // consumed; opening a second blank session for it would replace
                // the one the first pass just populated.
                //
                // [T-share-routes-to-background-session] Route to the chat the
                // user is LOOKING AT, and only open a new session when there
                // isn't one.
                //
                // The bug this fixes was never "a share reached an open chat" —
                // it was a share reaching a chat the user was NOT looking at.
                // Device log 2026-08-18 23:55:42: the file landed in session
                // 87B79110, mid-agent-run in the BACKGROUND, purely because
                // that view happened to be mounted; nothing checked whether the
                // share was meant for it.
                //
                // So the destination is decided here, explicitly, and stamped
                // onto the buffer — a foreground chat when there is one, a
                // fresh session otherwise — and only that destination's view is
                // allowed to consume it (see injectPendingShareIfNeeded).
                // `navigationPath.isEmpty ? nil : currentStackSessionId` is the
                // same "what is actually on screen" test the outgoing-session
                // tracker at line ~1879 uses.
                if hadRecord {
                    let foreground: String? = navigationPath.isEmpty ? nil : currentStackSessionId
                    if let foreground {
                        // Already on screen — stamp it and navigate nowhere.
                        shareCoordinator.setBufferTarget(foreground)
                        shareLog.info("[Share] onChange: routing share into the foreground session \(foreground.prefix(16)) (no navigation needed)")
                    } else {
                        let target = Self.makeNewSessionId()
                        shareCoordinator.setBufferTarget(target)
                        shareLog.info("[Share] onChange: no session on screen — opening new session \(target.prefix(16)) for share")
                        var tx = Transaction()
                        tx.disablesAnimations = true
                        withTransaction(tx) { openSession(target) }
                    }
                }
            }
        }
        .onChange(of: deepLink.showEnvironmentVariables) { show in
            if show {
                activeToolSheet = .settings
            }
        }
        .onChange(of: deepLink.showPermissions) { show in
            if show {
                activeToolSheet = .settings
            }
        }
        .onChange(of: deepLink.showAlarmList) { show in
            if show {
                showAlarmList = true
                deepLink.showAlarmList = false
            }
        }
        // Open the SettingsSheet whenever a deep link sets a settings
        // target. SettingsSheet itself reads `deepLink.pendingSettingsTarget`
        // in onAppear/onChange to push the right destination, then clears it.
        .onChange(of: deepLink.pendingSettingsTarget) { target in
            guard target != nil else { return }
            if activeToolSheet != .settings {
                activeToolSheet = .settings
            }
        }
        .onChange(of: deepLink.pendingRootfsManagement) { pending in
            if pending {
                activeToolSheet = .rootfsManagement
                deepLink.pendingRootfsManagement = false
            }
        }
        .onChange(of: deepLink.pendingSessionId) { sid in
            guard let sid, !sid.isEmpty else { return }
            // Switch to the requested session if it exists in the loaded
            // list. If not loaded yet (cold launch with deep link), set
            // it now — the session-load path will pick it up once the
            // list refresh completes.
            selectedSessionId = sid
            deepLink.pendingSessionId = nil
        }
        .onChange(of: scenePhase) { phase in
            // [T-ios-scenephase-active-sigkill] Defer ALL .active work off the
            // synchronous callback. Writing @Published (SyncCore.isAppInBackground)
            // here triggers objectWillChange → SwiftUI view invalidation in the
            // same runloop tick as the foreground view-graph re-evaluation → SIGTRAP.
            if phase == .active {
                Task { @MainActor in
                    await Task.yield()
                    // Guard against rapid bg→fg→bg: if scenePhase already
                    // changed back, skip the stale .active work.
                    guard scenePhase == .active else { return }
                    // [T-ios-bg-nav-push-watchdog] Commit any push that arrived
                    // while backgrounded. Runs here — after the `Task.yield()`
                    // that keeps .active work off the synchronous callback — so
                    // the pushed screen's first layout pass lands on its own
                    // runloop turn with a full frame budget, never inside the
                    // foreground-transition tick.
                    flushPendingBackgroundNavigation()
                    fetchAlarmsIfNeeded()
                    if #available(iOS 17.0, *) {
                        SyncCore.shared.isAppInBackground = false
                    }
                    #if DEBUG
                    UIApplication.shared.isIdleTimerDisabled = keepScreenAwake
                    #endif
                    // [T-home-fab-keyboard-inset] Defense-in-depth behind the
                    // structural .ignoresSafeArea immunity on the session lists:
                    // on foreground return with the HOME screen actually showing
                    // (no pushed chat on compact, no selected session on split —
                    // a split chat column may legitimately hold composer focus)
                    // and the inline search bar not focused, no responder is
                    // legitimate. Resign whatever UIKit resurrected during the
                    // background snapshot pass so its stale keyboard inset can't
                    // inflate the window's bottom safe area.
                    if !searchFocused, navigationPath.isEmpty, selectedSessionId == nil {
                        UIApplication.shared.sendAction(
                            #selector(UIResponder.resignFirstResponder),
                            to: nil, from: nil, for: nil)
                    }
                }
            } else {
                if #available(iOS 17.0, *) {
                    SyncCore.shared.isAppInBackground = (phase != .active)
                }
            }
        }
    }

    // MARK: - Split Layout (iPad / wide window)

    private var splitLayout: some View {
        // [T-ios-gh29-font-scale-split-column] App-base font scale for the iPad
        // split view. `appFontScale()` is environment-based (`.dynamicTypeSize`),
        // and SwiftUI environment does NOT bridge across NavigationSplitView's
        // per-column UIHostingController boundaries — applying it on the
        // container (the previous GH#29 fix) left the column contents unscaled,
        // which is why the font slider still didn't take effect on iPad/Mac.
        // Apply it INSIDE each column instead so the modifier lands on the
        // hosting boundary's inner side and reaches the content. The earlier
        // session-switch / tap lag is a separate issue (ChatSession Array `==`
        // in SwiftUI's transaction flush; an A/B test confirmed the font
        // injection is not its cause), so per-column injection is safe here.
        NavigationSplitView(columnVisibility: $columnVisibility) {
            sessionList(useNavigationLinks: false)
                .appFontScale()
        } detail: {
            detailView
                .appFontScale()
        }
    }

    // MARK: - Stack Layout (iPhone / narrow window)

    private var stackLayout: some View {
        NavigationStack(path: $navigationPath) {
            sessionList(useNavigationLinks: true)
                .navigationDestination(for: String.self) { id in
                    // `.id(id)` mirrors detailView (iPad): navigationDestination
                    // views are identified by stack depth, not path value, so
                    // replacing the top element in place (menu "New Chat" swaps
                    // [current] → [draft]) would otherwise reuse the old view's
                    // @StateObject vm and nothing visibly changes.
                    if id.hasPrefix("remote:") {
                        let parts = id.split(separator: ":", maxSplits: 2)
                        if parts.count == 3 {
                            AIChatView(sessionId: String(parts[2]), remoteDeviceId: String(parts[1]))
                                .id(id)
                        }
                    } else {
                        AIChatView(sessionId: Self.isNewSessionId(id) ? nil : id, draftId: Self.isNewSessionId(id) ? id : nil, initialGroupId: Self.extractGroupId(from: id))
                            .id(id)
                            .onAppear {
                                if currentStackSessionId != id {
                                    currentStackSessionId = id
                                    // [T-ios-stacknav-transition-attributegraph-race]
                                    // Keep the outgoing-id tracker in lockstep.
                                    // This branch fires exactly when the two
                                    // have DIVERGED — the "swallowed push left
                                    // currentStackSessionId set to a target
                                    // that never appeared" case documented on
                                    // the .moveInputToSession handler — and it
                                    // mounts a chat WITHOUT a navigationPath
                                    // change, so the observer that normally
                                    // maintains previousStackSessionId does not
                                    // run. Left unsynced, the next real
                                    // transition would suspend whichever id the
                                    // last observer pass recorded instead of
                                    // the vm actually on screen: the wrong vm
                                    // stalls and the real outgoing one keeps
                                    // publishing into its teardown.
                                    previousStackSessionId = id
                                }
                                SessionBadgeStore.shared.remove(.unread, for: id)
                                shareLog.info("🔄SESSION stackNav APPEAR id=\(id)")
                            }
                            .onDisappear { shareLog.info("🔄SESSION stackNav DISAPPEAR id=\(id)") }
                    }
                }
        }
    }

    // MARK: - Detail View

    @ViewBuilder
    private var detailView: some View {
        if let id = selectedSessionId {
            // Draft sessions always pass nil — the VM creates a real session internally
            // on first send. The .id() ensures each draft gets its own View lifecycle.
            let isDraft = Self.isNewSessionId(id)
            let effectiveId: String? = isDraft ? nil : id
            AIChatView(sessionId: effectiveId, draftId: isDraft ? id : nil, initialGroupId: Self.extractGroupId(from: id))
                .id(id)
                .onAppear {
                    draftLog.info("🔑DRAFT detailView APPEAR id=\(id) effectiveId=\(effectiveId ?? "nil") isDraft=\(isDraft)")
                }
                .onDisappear {
                    draftLog.info("🔑DRAFT detailView DISAPPEAR id=\(id)")
                }
        } else {
            VStack(spacing: 12) {
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 48))
                    .foregroundStyle(.secondary)
                Text("No Conversation Selected")
                    .font(.title3.bold())
                Text("Select a conversation or start a new one")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    // MARK: - Display Sessions

    /// Sessions filtered by active search query, or all sessions if not searching.
    private var filteredSessions: [ChatSession] {
        guard let matchedIds = searchMatchedIds else { return sessions }
        return sessions.filter { matchedIds.contains($0.id) }
    }

    /// Group sessions by date period for section display, with pinned sessions at the top.
    private func groupedSessions(_ list: [ChatSession]) -> [(label: String, sessions: [ChatSession])] {
        let calendar = Calendar.current
        let now = Date()
        var buckets: [(label: String, sessions: [ChatSession])] = []
        var pinned: [ChatSession] = []
        var today: [ChatSession] = []
        var yesterday: [ChatSession] = []
        var thisWeek: [ChatSession] = []
        var thisMonth: [ChatSession] = []
        var earlier: [ChatSession] = []

        let weekAgo = calendar.date(byAdding: .day, value: -7, to: now) ?? now
        let monthAgo = calendar.date(byAdding: .month, value: -1, to: now) ?? now

        for session in list {
            if session.isPinned {
                pinned.append(session)
                continue
            }
            let d = session.updatedAt
            if calendar.isDateInToday(d) {
                today.append(session)
            } else if calendar.isDateInYesterday(d) {
                yesterday.append(session)
            } else if d > weekAgo {
                thisWeek.append(session)
            } else if d > monthAgo {
                thisMonth.append(session)
            } else {
                earlier.append(session)
            }
        }

        // Sort pinned by last-modified first, then pin time as tiebreaker
        pinned.sort {
            if $0.updatedAt != $1.updatedAt { return $0.updatedAt > $1.updatedAt }
            return ($0.pinnedAt ?? .distantPast) > ($1.pinnedAt ?? .distantPast)
        }

        if !pinned.isEmpty    { buckets.append(("Pinned", pinned)) }
        if !today.isEmpty     { buckets.append(("Today", today)) }
        if !yesterday.isEmpty { buckets.append(("Yesterday", yesterday)) }
        if !thisWeek.isEmpty  { buckets.append(("This Week", thisWeek)) }
        if !thisMonth.isEmpty { buckets.append(("This Month", thisMonth)) }
        if !earlier.isEmpty   { buckets.append(("Earlier", earlier)) }
        return buckets
    }

    // MARK: - Sidebar diff cost (T-ios-session-list-equatable-jank)
    //
    // The sidebar `ForEach(groups)` fed SwiftUI the full `(label, [ChatSession])`
    // tuples. SwiftUI deep-compares the ForEach data on every transaction flush,
    // so it ran `Array<ChatSession>.==` over the whole list — O(session count) ×
    // a per-element ChatSession compare. On a device with many sessions that
    // pinned the main thread for ~1s (Debug only; Release optimizes the compare
    // away, and a small-library iPad never hit the threshold). Cheapening
    // ChatSession.== helped but didn't remove the O(N) array compare.
    //
    // These two helpers let the ForEach diff a `[String]` id list instead of a
    // `[ChatSession]` value array: the outer ForEach carries only labels + ids,
    // and rows look the session up by id. Grouping/sorting logic is unchanged —
    // groupedSessionIDs() just projects groupedSessions() down to ids.

    /// Grouped sidebar sections carrying only session IDs (cheap to diff),
    /// with folder sections interleaved after Pinned.
    ///
    /// Folder aggregates (glyphs / unread / active / paused) are computed HERE,
    /// in this single pass, and never in the header's body: the header is a
    /// high-frequency recompute path, and deriving "any member active" there
    /// would cost O(members) × folders × every AttributeGraph re-eval.
    private func groupedSessionIDs(_ list: [ChatSession]) -> [SidebarGroup] {
        // Build the store snapshots ONCE per evaluation (each is O(its own
        // small population), replacing per-member store entry + per-member
        // Date allocation), then memo-check: on a hit — the common case for
        // the high-frequency re-eval paths (activity ticks, badge publishes
        // that changed nothing the sidebar shows) — the whole partition +
        // aggregation below is skipped and the previous array instance is
        // returned.
        let unreadIds = sidebarBadgeStore.unreadSessionIds
        let freshCornerIds = sidebarBadgeStore.freshCornerBadgeSessionIds(within: 24 * 3600)
        let activeIds = sidebarActivityTracker.resolvedActiveSessionIds
        let key = SidebarGroupsMemoKey(
            sessions: list.map {
                SidebarSessionKey(
                    id: $0.id, updatedAt: $0.updatedAt, pinnedAt: $0.pinnedAt,
                    isPinned: $0.isPinned, folderId: $0.folderId,
                    category: $0.category, title: $0.title)
            },
            folders: folders,
            collapsedFolderIds: collapsedFolderIds,
            pendingDraftId: pendingFolderDraft?.draftId,
            pendingDraftFolderId: pendingFolderDraft?.folderId,
            isSearching: isSearching,
            unreadIds: unreadIds,
            freshCornerIds: freshCornerIds,
            activeIds: activeIds,
            dayStamp: Calendar.current.startOfDay(for: Date()))
        if sidebarGroupsMemo.key == key {
            return sidebarGroupsMemo.groups
        }
        let groups = computeGroupedSessionIDs(
            list, unreadIds: unreadIds, freshCornerIds: freshCornerIds, activeIds: activeIds)
        sidebarGroupsMemo.key = key
        sidebarGroupsMemo.groups = groups
        #if DEBUG
        // [T-ios-badge-diag] Why the group card decided to light (or not).
        //
        // Placed AFTER the memo check on purpose: this is the miss path, which
        // only runs when something the sidebar actually shows has changed, so
        // it cannot spam. It is further gated to groups that have at least one
        // paused member, and to DEBUG — the aggregation itself is a hot
        // AttributeGraph path and must not pay for logging in Release.
        //
        // `paused` vs `fresh` is the whole diagnosis in one line: a group with
        // paused=3 fresh=0 badge=false is the window working; paused=3 fresh=3
        // on days-old sessions is the bug returning.
        // `g.ids` is emptied for a COLLAPSED folder — the very case the badge is
        // about — so member ids come from the source list by folderId instead.
        for g in groups {
            guard let fid = g.folderId else { continue }
            let members = list.filter { $0.folderId == fid }.map(\.id)
            let pausedCount = members.filter {
                sidebarBadgeStore.topCornerBadge(for: $0) == .paused
            }.count
            guard pausedCount > 0 else { continue }
            let freshCount = members.filter { freshCornerIds.contains($0) }.count
            AppLogger(category: "SessionBadgeStore").debug(
                "[BadgeGroup] folder=\(fid.prefix(8)) members=\(members.count) " +
                "paused=\(pausedCount) freshWithin24h=\(freshCount) collapsed=\(g.isCollapsed) " +
                "badgeLit=\(g.isCollapsed && g.anyPaused)")
        }
        #endif
        return groups
    }

    private func computeGroupedSessionIDs(
        _ list: [ChatSession],
        unreadIds: Set<String>,
        freshCornerIds: Set<String>,
        activeIds: Set<String>
    ) -> [SidebarGroup] {
        let folderById = Dictionary(folders.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })

        var filed: [(fid: String, session: ChatSession)] = []
        var unfiled: [ChatSession] = []
        for s in list {
            // Folder membership outranks pin FOR PLACEMENT: a filed session
            // renders inside its group even when pinned. (The original
            // pin-wins rule made "file a pinned session" look like a no-op —
            // the write succeeded but the session stayed in Pinned and the
            // group showed empty, reported as a bug.) The pin itself is NOT
            // touched: pinned members sort first within their group and keep
            // the row's pin glyph — filing must never silently unpin.
            //
            // A folder_id pointing at a folder we don't have locally (record
            // not yet synced in, or folder deleted on a peer) renders as
            // ungrouped rather than vanishing — the orphan-reference rule
            // from the design doc's sync section.
            //
            // A "New Chat in Group" DRAFT has no sessions row yet (folder_id
            // is written at promotion), so it rides pendingFolderDraft: the
            // draft renders inside its target group from the first moment
            // instead of popping into Today until the first message lands.
            let effectiveFid: String? = s.folderId
                ?? (pendingFolderDraft?.draftId == s.id ? pendingFolderDraft?.folderId : nil)
            if let fid = effectiveFid, folderById[fid] != nil {
                filed.append((fid: fid, session: s))
            } else {
                unfiled.append(s)
            }
        }

        let dateGroups = groupedSessions(unfiled).map {
            SidebarGroup(label: $0.label, ids: $0.sessions.map(\.id))
        }

        // `list` arrives updated_at DESC, so first-encounter order over the
        // filed sessions IS the folders' activity order (max member updatedAt
        // descending) — no separate sort needed.
        var folderOrder: [String] = []
        var members: [String: [ChatSession]] = [:]
        for entry in filed {
            if members[entry.fid] == nil { folderOrder.append(entry.fid) }
            members[entry.fid, default: []].append(entry.session)
        }
        // Folders with no (visible) members still render — a folder that
        // disappears whenever its sessions are filtered out reads as data
        // loss. EXCEPT during an active search: there the list is a result
        // set, and padding it with every non-matching folder as an empty card
        // is noise (found in review — search for a message and every folder
        // you own would tag along).
        if !isSearching {
            for f in folders.sorted(by: { $0.updatedAt > $1.updatedAt }) where members[f.id] == nil {
                folderOrder.append(f.id)
                members[f.id] = []
            }
        }

        var folderGroups: [SidebarGroup] = []
        for fid in folderOrder {
            guard let folder = folderById[fid] else { continue }
            let m = members[fid] ?? []
            // Display order: pinned members first (stable partition), the
            // rest by recency. Aggregates below deliberately keep using the
            // recency-ordered `m` — latestDate/summary mean "newest activity",
            // not "first displayed row".
            let displayOrdered = m.filter(\.isPinned) + m.filter { !$0.isPinned }
            var g = SidebarGroup(label: folder.name, ids: displayOrdered.map(\.id), folderId: fid)
            g.totalCount = m.count
            // Top-3 DISTINCT category glyphs by recency (m is recency-sorted).
            // Distinct because three code sessions rendering three identical
            // terminal glyphs carries no information.
            var seenCategories = Set<String>()
            for s in m where seenCategories.insert(s.category ?? "").inserted {
                let icon = sessionCategoryIcon(for: s.category)
                g.glyphs.append(FolderGlyph(systemName: icon.systemName, color: icon.color))
                if g.glyphs.count == 3 { break }
            }
            // Status passthrough as ONE early-exiting pass of hash lookups
            // (was three `contains` passes entering the observed stores per
            // member). anyPaused carries only FRESH corner badges (entered in
            // the last 24h; covers paused and every future non-unread kind) —
            // rows keep rendering their badges unfiltered; the window only
            // stops long-stale states from flagging the whole group forever.
            if !unreadIds.isEmpty || !activeIds.isEmpty || !freshCornerIds.isEmpty {
                for s in m {
                    if !g.anyUnread, unreadIds.contains(s.id) { g.anyUnread = true }
                    if !g.anyActive, activeIds.contains(s.id) { g.anyActive = true }
                    if !g.anyPaused, freshCornerIds.contains(s.id) { g.anyPaused = true }
                    if g.anyUnread, g.anyActive, g.anyPaused { break }
                }
            }
            g.isCollapsed = collapsedFolderIds.contains(fid)
            g.isFolderPinned = folder.isPinned
            // m is recency-sorted, so .first is the newest member.
            g.latestDate = m.first?.updatedAt
            g.summaryTitle = m.first?.title
            if g.isCollapsed { g.ids = [] }
            folderGroups.append(g)
        }
        // Pinned folders float above unpinned ones. Within each half the
        // activity order (first-encounter over the updated_at-DESC list) is
        // preserved — stable partition, not a re-sort.
        folderGroups = folderGroups.filter(\.isFolderPinned) + folderGroups.filter { !$0.isFolderPinned }
        // "Groups" divider above the folder block, mirroring the Today/
        // Yesterday labels, so the block separates from the pinned sessions
        // above it at a glance.
        if !folderGroups.isEmpty { folderGroups[0].showsGroupsHeader = true }

        // Assembly: Pinned → folders (by activity) → date buckets. This is the
        // design doc's simplified layout — a fixed folder block rather than
        // folders mixed into the date buckets — chosen because interleaving
        // would push cross-bucket decisions into groupedSessions, a function
        // already implicated in [T-ios-session-list-equatable-jank].
        if let first = dateGroups.first, first.label == "Pinned" {
            return [first] + folderGroups + dateGroups.dropFirst()
        }
        return folderGroups + dateGroups
    }

    /// Toggle a folder section collapsed/expanded and persist the set.
    /// Expansion is EXCLUSIVE (accordion): opening a folder closes every
    /// other one — only one folder interior is on screen at a time, which
    /// keeps the wrapped-container look unambiguous and the list short.
    private func toggleFolderCollapsed(_ folderId: String, scrollProxy: ScrollViewProxy? = nil) {
        // [T-ios-folder-accordion-scroll-anchor] Keep the tapped folder in view
        // across the swap.
        //
        // Opening folder B closes folder A (accordion). When A sat ABOVE B and
        // the user had scrolled deep into A's long member list, A's rows are
        // removed from the List and everything below — including B, the folder
        // they just tapped — slides UP by A's full expanded height. That height
        // is routinely taller than the viewport, so B lands off-screen above the
        // top edge and the user has to scroll back to find what they opened.
        //
        // Fix: after the state change, bring B's header back into view IF it
        // left. This reuses the same anchor and proxy the mini-bar's "back to
        // header" jump already uses, so no new scroll infrastructure is
        // introduced. See the correction block at the end of this function for
        // why the scroll is conditional rather than unconditional.
        //
        // Only on EXPAND — collapsing is self-anchoring (the header the user
        // tapped stays exactly where it is; scrolling then would move content
        // out from under the finger for no reason).
        let willExpand = collapsedFolderIds.contains(folderId)

        // Animated again by request — with the jitter history baked into the
        // parameters. The original shaking had two ingredients: per-row glass
        // segments re-rendering every animation frame (that structure is gone;
        // the group is now ONE card, one glass shape), and a springy curve
        // whose oscillation read as continuous wobble. Hence a short plain
        // easeInOut: monotonic (no overshoot to re-measure), brief enough
        // that the List's per-frame self-size pass stays cheap.
        withAnimation(.easeInOut(duration: 0.25)) {
            if collapsedFolderIds.contains(folderId) {
                collapsedFolderIds = Set(folders.map(\.id))
                collapsedFolderIds.remove(folderId)
            } else {
                collapsedFolderIds.insert(folderId)
            }
        }
        UserDefaults.standard.set(Array(collapsedFolderIds), forKey: "collapsedFolderIds")

        // Deferred one runloop turn on purpose: the rows removed by the collapse
        // are still in the List when this returns, so measuring now would read
        // the pre-collapse geometry. Letting SwiftUI apply the structural change
        // first means the probe values below describe the layout the user will
        // actually see.
        //
        // MINIMAL CORRECTION, not "scroll to top". The first version of this fix
        // called `scrollTo(anchor: .top)` unconditionally on every expand, which
        // did keep the folder on screen but slammed it to the top edge even when
        // it had never moved — the tap read as "jump to top" rather than "expand
        // in place". Now the header's post-layout position is measured and a
        // scroll happens ONLY when it actually left the viewport:
        //
        //   - still fully on screen  → do nothing at all (the true in-place case)
        //   - pushed above the top   → bring it just inside the top edge
        //   - pushed below the bottom→ bring it just inside, still near the top,
        //                              since an expanded folder needs the space
        //                              BELOW its header to show members
        //
        // `anchor: .top` is still the landing spot when a correction is needed —
        // what changed is that it is now conditional. SwiftUI's ScrollViewProxy
        // has no "scroll by delta" primitive, so top-anchoring is the only way to
        // express "bring this into view"; the win is that the common case no
        // longer triggers it.
        if willExpand, let scrollProxy {
            DispatchQueue.main.async {
                let topEdge = folderMiniBarTopY
                let bottomEdge = folderListBottomY
                guard let headerY = folderHeaderTopY[folderId], bottomEdge > topEdge else {
                    // No probe reading yet (header never rendered, e.g. expanded
                    // programmatically while off screen). Fall back to the old
                    // unconditional behaviour rather than risk leaving the user
                    // staring at the wrong part of the list.
                    withAnimation(.easeInOut(duration: 0.25)) {
                        scrollProxy.scrollTo("folderHeader-\(folderId)", anchor: .top)
                    }
                    return
                }

                // Keep a little slack at the top so a header sitting *exactly* on
                // the boundary isn't judged off-screen by a sub-point rounding
                // difference, and so a corrected header lands visibly inside the
                // edge rather than flush against it.
                let slack: CGFloat = 8
                let isAboveViewport = headerY < topEdge + slack
                let isBelowViewport = headerY > bottomEdge - slack

                guard isAboveViewport || isBelowViewport else {
                    // In place — the whole point of this refinement.
                    return
                }
                withAnimation(.easeInOut(duration: 0.25)) {
                    scrollProxy.scrollTo("folderHeader-\(folderId)", anchor: .top)
                }
            }
        }
    }

    /// Probe report sink. Animated so the mini-bar's insertion/removal
    /// transition actually plays; guarded so the (per-frame-capable) probe
    /// only ever mutates state on a real transition.
    private func setFolderHeaderOffscreen(_ folderId: String, _ offscreen: Bool) {
        guard offscreenFolderHeaderIds.contains(folderId) != offscreen else { return }
        withAnimation(.easeInOut(duration: 0.2)) {
            if offscreen {
                offscreenFolderHeaderIds.insert(folderId)
            } else {
                offscreenFolderHeaderIds.remove(folderId)
            }
        }
    }

    /// The folder the mini-bar represents, or nil = no bar. Requires an
    /// EXPANDED folder (collapse may happen while the header is culled, with
    /// no probe alive to retract the offscreen mark — this guard is what
    /// keeps the bar honest) whose header is scrolled out, outside select
    /// mode. Deterministic pick in folder order when several qualify (only
    /// possible before the accordion has ever run).
    private var folderMiniBarFolder: ChatFolder? {
        guard !isSelecting else { return nil }
        return folders.first {
            offscreenFolderHeaderIds.contains($0.id) && !collapsedFolderIds.contains($0.id)
        }
    }

    /// Top-aligned overlay for both list layouts: measures the list's visible
    /// top edge (the probe threshold — the GeometryReader content respects
    /// the safe area, so its global minY IS "just under the nav bar") and
    /// floats the mini-bar when an expanded group's header is scrolled out.
    /// Pure overlay: the List's structure is untouched, and everything but
    /// the bar itself passes touches through.
    private func folderMiniBarOverlay(_ scrollProxy: ScrollViewProxy) -> some View {
        GeometryReader { geo in
            let topY = geo.frame(in: .global).minY
            ZStack(alignment: .top) {
                if let folder = folderMiniBarFolder {
                    folderMiniBar(for: folder, scrollProxy: scrollProxy)
                        .frame(maxWidth: .infinity)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .frame(maxWidth: .infinity, alignment: .top)
            .onAppear {
                folderMiniBarTopY = topY
                folderListBottomY = geo.frame(in: .global).maxY
            }
            .onChange(of: topY) { folderMiniBarTopY = $0 }
            .onChange(of: geo.frame(in: .global).maxY) { folderListBottomY = $0 }
        }
    }

    /// Composed-icon glyphs for the mini-bar, resolved from the memoized
    /// groups (same aggregation that feeds the home card, so the two icons
    /// can't drift). The memo box may briefly hold the PREVIOUS evaluation's
    /// groups mid-update — visually identical for an icon tint, and the next
    /// evaluation converges.
    private func folderMiniBarGlyphs(_ folderId: String) -> [FolderGlyph] {
        sidebarGroupsMemo.groups.first { $0.folderId == folderId }?.glyphs ?? []
    }

    /// The floating mini-bar, 48pt capsule with TWO interaction zones:
    /// icon + name (leading) scrolls the list back to the group's header;
    /// the trailing filled-circle chevron collapses the group. Split per the
    /// redesign — the whole-bar-collapses behavior made "where am I" and
    /// "close this" the same target.
    private func folderMiniBar(for folder: ChatFolder, scrollProxy: ScrollViewProxy) -> some View {
        HStack(spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.25)) {
                    scrollProxy.scrollTo("folderHeader-\(folder.id)", anchor: .top)
                }
            } label: {
                HStack(spacing: 8) {
                    FolderComposedIcon(glyphs: folderMiniBarGlyphs(folder.id), diameter: 30)
                    // Folder names are user data — verbatim.
                    Text(folder.name)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color(UIColor.label))
                        .lineLimit(1)
                }
                // Widen the tap zone to everything left of the chevron.
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button {
                // Retract the mark ourselves: if the header cell is culled
                // there is no probe alive to do it, and the display guard
                // alone would leave a stale entry pinning the NEXT
                // expansion's bar on.
                withAnimation(.easeInOut(duration: 0.2)) {
                    _ = offscreenFolderHeaderIds.remove(folder.id)
                }
                toggleFolderCollapsed(folder.id)
            } label: {
                Image(systemName: "chevron.up")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color(UIColor.secondaryLabel))
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(Color(UIColor.tertiarySystemFill)))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Collapse Group"))
        }
        .padding(.leading, 10)
        .padding(.trailing, 8)
        .frame(height: 48)
        .frame(maxWidth: 320)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().stroke(Color(UIColor.separator).opacity(0.5), lineWidth: 0.5))
        .shadow(color: Color.black.opacity(0.15), radius: 8, y: 2)
        .padding(.top, 8)
        .padding(.horizontal, 24)
    }

    /// [T-ios-session-list-equatable-jank] Rebuild the id→ChatSession cache.
    /// Called from `.onChange(of: sessions)` — NOT per body eval — so the
    /// dictionary value is stable between session-list mutations and the row
    /// closures never trigger a per-frame Dictionary.== / ChatSession.== diff.
    private func rebuildSessionsByIdCache() {
        sessionsByIdCache = Dictionary(sessions.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
    }

    /// Resolve a session for a sidebar row by id, reading the @State cache.
    /// `displaySessions` may prepend a transient draft proxy (id ==
    /// selectedSessionId) that isn't in `sessions`; overlay that one case here
    /// so the iPad split list still resolves the New-Chat placeholder row.
    private func sessionForRow(_ id: String) -> ChatSession? {
        if let cached = sessionsByIdCache[id] { return cached }
        // Draft proxy / placeholder rows live only in displaySessions, never in
        // `sessions` — fall back to a linear scan of that (tiny: base + 1).
        return displaySessions.first(where: { $0.id == id })
    }

    /// Sessions to display in the sidebar. Prepends a placeholder entry
    /// in iPad split mode when the user has opened a new (unsaved) session.
    /// The placeholder keeps the draft ID as its tag so `List(selection:)` stays matched.
    private var displaySessions: [ChatSession] {
        let base = filteredSessions
        guard isWideLayout, let selId = selectedSessionId, Self.isNewSessionId(selId) else {
            return base
        }
        if let realId = newSessionRealId {
            // Session has been persisted — show it under the draft tag so the selection stays valid.
            // Use the real session's data (title, lastMessage) but with the draft ID.
            if let realSession = sessions.first(where: { $0.id == realId }) {
                let proxy = ChatSession(
                    id: selId,
                    title: realSession.title,
                    category: realSession.category,
                    modelId: realSession.modelId,
                    createdAt: realSession.createdAt,
                    updatedAt: realSession.updatedAt,
                    lastMessage: realSession.lastMessage
                )
                // Replace the real session with the proxy so there's no duplicate
                return [proxy] + base.filter { $0.id != realId }
            }
        }
        // Not yet persisted — show a "New Chat" placeholder
        let placeholder = ChatSession(
            id: selId,
            title: "New Chat",
            category: nil,
            modelId: "",
            createdAt: Date(),
            updatedAt: Date(),
            lastMessage: nil
        )
        return [placeholder] + base
    }

    // MARK: - Session List

    @ViewBuilder
    private func sessionList(useNavigationLinks: Bool) -> some View {
        Group {
            if useNavigationLinks {
                stackList
            } else {
                splitList
            }
        }
        // Hardware ⌘F → focus search, available while the session list is on
        // screen (iPad/Mac keyboards). A zero-opacity button carries the
        // shortcut without affecting layout; it lives in the list's view tree so
        // the command only fires when the list is visible, not inside a chat.
        .background {
            Button(action: focusSearch) { EmptyView() }
                .keyboardShortcut("f", modifiers: .command)
                .opacity(0)
                .accessibilityHidden(true)
        }
        // [T-ios-migration-timer-toolbar-uaf-crash / T-ios-migration-timer-sessionlist-uaf-crash]
        // Own the migration-subtitle refresh driver here, on the stable sidebar list
        // body, instead of on the churny toolbar principal item (see `titleLabel`).
        // This Group has identity-stable lifetime across the sidebar's life — the
        // inner if/else only swaps which List renders, the wrapper itself is never
        // torn down — so the driver isn't rebuilt when the list content changes.
        //
        // The driver is a `.task` async loop, NOT a `Timer.publish().autoconnect()`
        // subscribed via `.onReceive`. The earlier `.onReceive(migrationProgressTimer)`
        // form still crashed (build 1.10(1)): a process-lived publisher's sink is a
        // SwiftUI attribute that AttributeGraph re-creates on every `body` transaction,
        // and a 5s tick delivered while that attribute is being torn down/rebuilt
        // releases the dangling sink closure → use-after-free (KERN_PROTECTION_FAILURE
        // in _AppearanceActionModifier.MergedCallbacks.updateValue → swift_release_dealloc).
        // The `.task` loop has no graph-bound publisher: SwiftUI owns the Task's
        // lifetime by view identity and cancels it deterministically on teardown, so
        // there is no sink to release mid-transaction. The loop also parks while the
        // app is backgrounded (the crash reproduced with the app in the background).
        // refreshMigrationSubtitle is idempotent (Task{@MainActor} + diff-before-assign).
        .task { await migrationSubtitleLoop() }
        // [T-ios-soul-name-sidebar-stale] Refresh the sidebar title from SOUL.md.
        // Moved here off the churny toolbar `titleLabel` Text (which rebuilds on
        // every canOpenSync/soulName/migrationSubtitle/isSelecting change) for the
        // same reason as the migration timer above: this Group's identity is stable
        // across the sidebar's life, so the sink is never torn down mid-transaction
        // and can't drop a .soulMdChanged notification arriving during reconstruction.
        .onReceive(NotificationCenter.default.publisher(for: .soulMdChanged)) { _ in
            let n = SoulStore.cachedMetadata.name
            soulName = n.isEmpty ? "Minis" : n
        }
    }

    /// Plain List with NavigationLink for stack (iPhone) layout.
    /// ScrollViewReader feeds the mini-bar's "back to header" jump; wrapping
    /// the List is inert otherwise (no layout/behavior change).
    private var stackList: some View {
        ScrollViewReader { scrollProxy in
        List {
            // [T-ios-session-list-equatable-jank] id-list projection — see splitList.
            let groups = groupedSessionIDs(filteredSessions)
            ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
                Section {
                    // Folder card as the section's FIRST ROW, not its header:
                    // plain-List headers carry platform-specific chrome
                    // (spacing/padding differ between iPhone, iPad sidebar and
                    // Catalyst), which visibly detached the card from its
                    // member rows on iPad/macOS. Row-to-row adjacency is a
                    // guaranteed 0pt on every platform, so the container
                    // segments actually weld. (Cost: the card no longer pins
                    // while its members scroll — acceptable, accordion keeps
                    // sections short.)
                    if group.folderId != nil, !isSelecting {
                        folderSectionHeader(group, scrollProxy: scrollProxy)
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .modifier(SelectionDisabledIfAvailable())
                    }
                    ForEach(group.ids, id: \.self) { sessionId in
                        // [T-ios-session-list-equatable-jank] Resolve via the
                        // @State cache (sessionForRow), NOT a captured
                        // [String:ChatSession] — see sessionsByIdCache.
                        if let session = sessionForRow(sessionId) {
                        if isSelecting {
                            selectableRow(session)
                                .id("select-\(session.id)")
                                .listRowInsets(EdgeInsets())
                        } else {
                            SessionRow(
                                session: session,
                                // [T-ios-ipad-sidebar-running-indicator-stale]
                                // Pass running/suspended as VALUES so a flip
                                // changes the SessionRow value → body re-evals
                                // in place (same identity, no cell rebuild).
                                isActive: sidebarActivityTracker.isActive(session.id),
                                isSuspended: sidebarConcurrencyManager.isSuspended(session.id),
                                highlightQuery: isSearching ? searchText : nil,
                                matchSnippet: isSearching ? searchMatchSnippets[session.id] : nil
                            )
                                // [T-ios-session-list-equatable-jank] Gate
                                // parent-driven re-eval on SessionRow's cheap
                                // custom == (rendered fields only), so a
                                // transaction flush from unrelated ContentView
                                // state churn doesn't deep-compare ChatSession.
                                .equatable()
                                // Entry D: long-press then move = drag the
                                // session id (never the ChatSession value —
                                // same id-only discipline as the list
                                // projection and the menu's value-semantics
                                // constraint). The SYSTEM arbitrates against
                                // .contextMenu on the same press: hold still →
                                // menu, hold then move → drag. Do not replace
                                // with a hand-rolled gesture sequence — the
                                // gesture layer is where system gestures are
                                // beaten (see the WebView sheet-dismiss fix).
                                .draggable(session.id)
                                .overlay {
                                    if regeneratingTitleSessionId == session.id {
                                        ZStack {
                                            Color(.systemBackground).opacity(0.7)
                                            ProgressView()
                                        }
                                    }
                                }
                                .background(
                                    NavigationLink(value: session.id) { EmptyView() }
                                        .opacity(0)
                                )
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Group {
                                if group.folderId != nil {
                                    FolderMemberRowBackground(isLast: sessionId == group.ids.last)
                                } else {
                                    Color(.systemBackground)
                                }
                            })
                            .contextMenu {
                                // [T-ios-crash-contextmenu-uaf] Value-only menu view,
                                // no closure captures — see SessionContextMenu.
                                SessionContextMenu(
                                    key: MenuKey(sid: session.id, pinned: session.isPinned, title: session.title, filed: session.isFiled),
                                    actions: menuActions
                                )
                                .equatable()
                            }
                        }
                        }  // if let session
                    }
                } header: {
                    // Folder groups render their card as the section's first
                    // ROW (above); the header slot serves the date buckets,
                    // the select-mode select-all, and — on the first folder
                    // group only — the "Groups" divider label.
                    if group.folderId == nil || isSelecting {
                        sectionHeader(index: index, group: group)
                    } else if group.showsGroupsHeader {
                        Text("Groups")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color(UIColor.secondaryLabel))
                            .textCase(nil)
                    }
                }
            }

        }
        .listStyle(.plain)
        #if DEBUG
        // TEMPORARY scroll-phase markers to bracket the jitter window in the
        // log. Pair with the [ROWH] probe: a [ROWH] line appearing during
        // `decelerating`/`idle` = a self-size correction mid/post-scroll =
        // the visible jump. Remove with the probe. (iOS 18+ only.)
        .modifier(ScrollPhaseProbe())
        #endif
        .opacity(didInitialLoad ? 1 : 0)
        .overlay { if didInitialLoad, filteredSessions.isEmpty, !isSearching { emptyState } }
        .overlay(alignment: .top) { folderMiniBarOverlay(scrollProxy) }
        .safeAreaInset(edge: .bottom) { if isSelecting { selectionToolbar } else { fabRow } }
        // [T-home-fab-keyboard-inset] Mirror of the voice panel's structural
        // immunity (604a9947 / T-voice-bg-fg-gap): with the inline search bar
        // closed, nothing down here accepts text — any keyboard inset reaching
        // this list is a stale/zombie one (stranded responder, interrupted
        // bg-snapshot dismiss) and must not push the 新建/搜索 FABs up. With
        // the search bar open its TextField legitimately rises with the
        // keyboard, so normal avoidance is restored.
        .ignoresSafeArea(.keyboard, edges: showSearchBar ? [] : .bottom)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { sidebarToolbarContent }
        }
    }

    /// Selection-bound List for split (iPad) layout.
    /// ScrollViewReader: same mini-bar jump wiring as stackList.
    private var splitList: some View {
        ScrollViewReader { scrollProxy in
        List(selection: $selectedSessionId) {
            // [T-ios-session-list-equatable-jank] Diff a (label, ids) projection
            // so SwiftUI compares a [String] id list, not a [ChatSession] value
            // array. Rows resolve the model via displaySessionsById.
            let groups = groupedSessionIDs(displaySessions)
            ForEach(Array(groups.enumerated()), id: \.offset) { index, group in
                Section {
                    // Folder card as first row — see the sidebar list's
                    // comment: plain-List headers carry platform-specific
                    // chrome and detached the card from its members on
                    // iPad/macOS; row adjacency welds on every platform.
                    if group.folderId != nil, !isSelecting {
                        folderSectionHeader(group, scrollProxy: scrollProxy)
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                            .modifier(SelectionDisabledIfAvailable())
                    }
                    ForEach(group.ids, id: \.self) { sessionId in
                        // [T-ios-session-list-equatable-jank] Resolve via the
                        // @State cache (sessionForRow), NOT a captured
                        // [String:ChatSession] — see sessionsByIdCache.
                        if let session = sessionForRow(sessionId) {
                        if isSelecting {
                            selectableRow(session)
                                .id("select-\(session.id)")
                                .listRowInsets(EdgeInsets())
                        } else {
                            SessionRow(
                                session: session,
                                isHighlighted: isSessionHighlighted(session.id),
                                // [T-ios-ipad-sidebar-running-indicator-stale]
                                // Pass running/suspended as VALUES so a flip
                                // changes the SessionRow value → body re-evals
                                // in place (same identity, no cell rebuild).
                                isActive: sidebarActivityTracker.isActive(session.id),
                                isSuspended: sidebarConcurrencyManager.isSuspended(session.id),
                                highlightQuery: isSearching ? searchText : nil,
                                matchSnippet: isSearching ? searchMatchSnippets[session.id] : nil
                            )
                                // [T-ios-session-list-equatable-jank] Gate
                                // parent-driven re-eval on SessionRow's cheap
                                // custom == (rendered fields only), so a
                                // transaction flush from unrelated ContentView
                                // state churn doesn't deep-compare ChatSession.
                                .equatable()
                                // Entry D: long-press then move = drag the
                                // session id (never the ChatSession value —
                                // same id-only discipline as the list
                                // projection and the menu's value-semantics
                                // constraint). The SYSTEM arbitrates against
                                // .contextMenu on the same press: hold still →
                                // menu, hold then move → drag. Do not replace
                                // with a hand-rolled gesture sequence — the
                                // gesture layer is where system gestures are
                                // beaten (see the WebView sheet-dismiss fix).
                                .draggable(session.id)
                                .overlay {
                                    if regeneratingTitleSessionId == session.id {
                                        ZStack {
                                            Color(.systemBackground).opacity(0.7)
                                            ProgressView()
                                        }
                                    }
                                }
                                // [T-ios-selected-session-contextmenu] Attach the
                                // contextMenu to the row CONTENT, not to the list-row
                                // modifier chain. On iPadOS / macOS this List uses
                                // `List(selection:)` + `.tag()`; the currently-selected
                                // row's selection gesture swallows the long-press (iPad)
                                // / right-click (mac), so a contextMenu placed at the
                                // list-row level never fires for the open session
                                // (GH#30 / TG36272). Binding it to the SessionRow view
                                // itself puts it below the selection layer, so it
                                // triggers on every row regardless of selection state.
                                // iPhone uses `stackList` (no selection:) and is
                                // unaffected.
                                .contextMenu {
                                    // [T-ios-ipad-new-session-contextmenu-broken / GH#30]
                                    // The selected new-chat row keeps the DRAFT id as its
                                    // tag even after the user sends a message and the
                                    // session is persisted (displaySessions swaps in a
                                    // proxy with realSession data under the draft id, so
                                    // List(selection:) stays matched). The old guard
                                    // `if !isNewSessionId(session.id)` then suppressed the
                                    // context menu for that row forever — until the user
                                    // switched away and the id resolved to the real one.
                                    // Fix: a draft row that has already been persisted
                                    // (newSessionRealId != nil) is a real, operable
                                    // session — surface the menu, targeting the REAL id.
                                    // Only a truly empty, never-sent draft (no realId)
                                    // still has no menu.
                                    // [T-ios-crash-contextmenu-uaf] Value-only menu, no closure.
                                    let menuSid = Self.isNewSessionId(session.id)
                                        ? newSessionRealId
                                        : session.id
                                    if let menuSid {
                                        SessionContextMenu(
                                            key: MenuKey(sid: menuSid, pinned: session.isPinned, title: session.title, filed: session.isFiled),
                                            actions: menuActions
                                        )
                                        .equatable()
                                    }
                                }
                                .tag(session.id)
                                .listRowInsets(EdgeInsets())
                                .listRowSeparator(.hidden)
                                .listRowBackground(
                                    ZStack {
                                        if group.folderId != nil {
                                            let isLast = sessionId == group.ids.last
                                            FolderMemberRowBackground(isLast: isLast)
                                            // Flush selection band inside the group;
                                            // the last member's band takes the
                                            // container's bottom radii so it stays
                                            // wrapped by the corners.
                                            if isSessionHighlighted(session.id) {
                                                UnevenRoundedRectangle(
                                                    topLeadingRadius: 0,
                                                    bottomLeadingRadius: isLast ? 16 : 0,
                                                    bottomTrailingRadius: isLast ? 16 : 0,
                                                    topTrailingRadius: 0,
                                                    style: .continuous
                                                )
                                                .fill(Color(red: 183/255.0, green: 175/255.0, blue: 150/255.0).opacity(0.3))
                                                .padding(.horizontal, 6)
                                                .padding(.bottom, isLast ? 4 : 0)
                                            }
                                        } else {
                                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                                .fill(isSessionHighlighted(session.id)
                                                      ? Color(red: 183/255.0, green: 175/255.0, blue: 150/255.0).opacity(0.3)
                                                      : Color.clear)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                        }
                                    }
                                )
                        }
                        }  // if let session
                    }
                } header: {
                    // Folder groups render their card as the section's first
                    // ROW (above); the header slot serves the date buckets,
                    // the select-mode select-all, and — on the first folder
                    // group only — the "Groups" divider label.
                    if group.folderId == nil || isSelecting {
                        sectionHeader(index: index, group: group)
                    } else if group.showsGroupsHeader {
                        Text("Groups")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color(UIColor.secondaryLabel))
                            .textCase(nil)
                    }
                }
            }

        }
        .listStyle(.plain)
        .navigationSplitViewColumnWidth(min: 340, ideal: 380, max: 500)
        .opacity(didInitialLoad ? 1 : 0)
        .overlay { if didInitialLoad, displaySessions.isEmpty, !isSearching { emptyState } }
        .overlay(alignment: .top) { folderMiniBarOverlay(scrollProxy) }
        .safeAreaInset(edge: .bottom) { if isSelecting { selectionToolbar } else { fabRow } }
        // [T-home-fab-keyboard-inset] Same structural immunity as the compact
        // list above — see that call site for the full rationale. On iPad the
        // sidebar column never hosts a keyboard unless the inline search bar
        // is open (the chat column's composer avoidance is its own subtree).
        .ignoresSafeArea(.keyboard, edges: showSearchBar ? [] : .bottom)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { sidebarToolbarContent }
        }
    }
    // MARK: - Sidebar Toolbar

    /// Refresh cadence for the sidebar migration subtitle, in seconds.
    private static let migrationSubtitleRefreshInterval: UInt64 = 5

    /// [T-ios-migration-timer-sessionlist-uaf-crash] Self-cancelling refresh loop
    /// for `migrationSubtitle`, driven by `.task` on the identity-stable sidebar
    /// Group. Replaces the process-lived `Timer.publish().autoconnect()` +
    /// `.onReceive` that AttributeGraph could tear down mid-transaction (UAF).
    ///
    /// SwiftUI cancels this Task when the Group's identity ends, so the loop stops
    /// cleanly with no dangling subscription. It parks (no refresh, cheap poll)
    /// while the app is backgrounded — the crash reproduced with the app in the
    /// background, and a hidden sidebar has nothing to display anyway.
    @MainActor
    private func migrationSubtitleLoop() async {
        // Mirror the old `.onAppear { refreshMigrationSubtitle() }`: refresh once
        // immediately so the subtitle is correct as soon as the sidebar appears.
        refreshMigrationSubtitle()
        while !Task.isCancelled {
            do {
                try await Task.sleep(nanoseconds: Self.migrationSubtitleRefreshInterval * 1_000_000_000)
            } catch {
                return  // cancelled during sleep
            }
            // Skip the refresh while backgrounded; keep looping so it resumes on
            // return to foreground without needing a separate scene-phase wake.
            // Read the process-level flag (kept current by the scenePhase observer)
            // rather than the `@Environment(\.scenePhase)` captured in this `.task`
            // closure's `self` snapshot, which would be stale.
            let backgrounded: Bool
            if #available(iOS 17.0, *) {
                backgrounded = SyncCore.shared.isAppInBackground
            } else {
                backgrounded = false
            }
            if !backgrounded {
                refreshMigrationSubtitle()
            }
        }
    }

    private func refreshMigrationSubtitle() {
        guard #available(iOS 17.0, *), SyncV2Bootstrap.isEnabled else {
            if migrationSubtitle != nil { migrationSubtitle = nil }
            return
        }
        Task { @MainActor in
            // Engine reports paused first regardless of dirty queue.
            if SyncCore.shared.pausedUntil != nil {
                if migrationSubtitle != .paused { migrationSubtitle = .paused }
                return
            }
            let counts = await ChatStore.shared.countDirtyRecords()
            let byType = Self.formatV2DirtyByType(counts.byType)
            let engineUp = SyncCore.shared.isRunning
            // Engine down but dirty queue is non-empty → stalled, not
            // syncing. The rotating icon would lie about progress.
            if !engineUp, !byType.isEmpty {
                if migrationSubtitle != .waiting { migrationSubtitle = .waiting }
                return
            }
            if let summary = await MigrationEngine.shared.progressSummary() {
                let pct = summary.pushTotal > 0 ? Int(Double(summary.pushDone) * 100.0 / Double(summary.pushTotal)) : 0
                let next: SyncSubtitleState = engineUp
                    ? .migrating(percent: pct, byType: byType)
                    : .waiting
                if migrationSubtitle != next { migrationSubtitle = next }
                return
            }
            if !byType.isEmpty {
                let next: SyncSubtitleState = engineUp ? .syncing(byType: byType) : .waiting
                if migrationSubtitle != next { migrationSubtitle = next }
            } else {
                // No active sync work — hide the subtitle entirely so the
                // "Minis" title sits at its normal size.
                if migrationSubtitle != nil { migrationSubtitle = nil }
            }
        }
    }

    /// Map v2 dirty-by-type counts → display capsules. Only types with
    /// dirty>0 are returned, sorted by count desc, top 3.
    private static func formatV2DirtyByType(_ byType: [String: Int]) -> [(label: String, count: Int)] {
        let labels: [String: String] = [
            "SessionV2": "Ses",
            "MessageV2": "Msg",
            "SessionFileV2": "Fil",
            "CompactMarkerV2": "Cmp",
            "ProviderConfigV2": "Prv",
            "EnvVarV2": "Env",
            "SyncDeviceV2": "Dev",
            "SkillV2": "Skl",
        ]
        return byType
            .compactMap { (k, v) -> (String, Int)? in
                guard v > 0, let label = labels[k] else { return nil }
                return (label, v)
            }
            .sorted { $0.1 > $1.1 }
            .prefix(3)
            .map { (label: $0.0, count: $0.1) }
    }

    /// Tiny indicator next to the "Minis" title showing the sync state.
    @ViewBuilder
    private func titleSyncIndicator(for state: SyncSubtitleState?) -> some View {
        switch state {
        case .none, .upToDate:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.green)
        case .paused:
            Image(systemName: "pause.circle.fill")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.orange)
        case .migrating, .syncing:
            PulseRotateIcon()
        case .waiting:
            Image(systemName: "clock")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func syncSubtitleView(_ state: SyncSubtitleState) -> some View {
        HStack(spacing: 5) {
            switch state {
            case .paused:
                Image(systemName: "pause.circle.fill")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("Sync paused")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            case .migrating(let pct, let byType):
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("\(pct)%")
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.secondary)
                ForEach(byType.indices, id: \.self) { i in
                    syncTypeChip(label: byType[i].label, count: byType[i].count)
                }
            case .syncing(let byType):
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                ForEach(byType.indices, id: \.self) { i in
                    syncTypeChip(label: byType[i].label, count: byType[i].count)
                }
            case .upToDate:
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(.green)
                Text("Up to date")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            case .waiting:
                Image(systemName: "clock")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.secondary)
                Text("Waiting")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Color.secondary.opacity(0.12), in: Capsule())
    }

    @ViewBuilder
    private func syncTypeChip(label: String, count: Int) -> some View {
        HStack(spacing: 3) {
            Text(label)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(.secondary)
            Text(formatCompact(count))
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    /// Compact integer formatting: 89,401 → "89k", 1,234 → "1.2k", 740 → "740".
    private func formatCompact(_ n: Int) -> String {
        if n < 1000 { return "\(n)" }
        if n < 10_000 {
            let v = Double(n) / 1000.0
            return String(format: "%.1fk", v)
        }
        return "\(n / 1000)k"
    }

    @ToolbarContentBuilder
    private var sidebarToolbarContent: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            if isSelecting {
                Text(selectedIds.isEmpty ? "Select Sessions" : "\(selectedIds.count) Selected")
                    .font(.headline)
            } else {
                let canOpenSync: Bool = {
                    if #available(iOS 17.0, *) { return SyncV2Bootstrap.isEnabled }
                    return false
                }()
                // Title text comes from SOUL.md (falls back to "Minis"). The
                // leading sync indicator floats as an overlay so it doesn't
                // take layout space — title stays perfectly centered in the
                // navigation bar regardless of whether the indicator is visible.
                // When iCloud sync isn't enabled the title is a plain Text;
                // wrapping it in a `.disabled` Button would drain SwiftUI's
                // default disabled-button tint into the label and render the
                // SOUL name grey, which read as a styling bug rather than the
                // intended "no sync detail to open" state.
                // [T-ios-soul-name-sidebar-stale] The `.onReceive(soulMdChanged)`
                // that refreshes `soulName` used to live HERE. It was moved to the
                // stable `sessionList(useNavigationLinks:)` body for the SAME reason
                // the migration timer was (see the T-ios-migration-timer note below):
                // this toolbar principal item rebuilds on every canOpenSync /
                // soulName / migrationSubtitle / isSelecting change, so a sink
                // attached here gets torn down and re-created constantly and can
                // drop a .soulMdChanged notification that arrives during the gap.
                // This Text now only READS `soulName`.
                let titleLabel = Text(soulName)
                    .font(.system(size: 18.5, weight: .semibold))
                    .foregroundStyle(.primary)
                    .overlay(alignment: .leading) {
                        if canOpenSync {
                            Button {
                                activeToolSheet = .syncMigrationDetail
                            } label: {
                                titleSyncIndicator(for: migrationSubtitle)
                                    .contentShape(Rectangle())
                                    .padding(4)
                            }
                            .buttonStyle(.plain)
                            .offset(x: -27)
                        }
                    }
                // [T-ios-migration-timer-toolbar-uaf-crash] The migration-subtitle
                // refresh driver used to live HERE, on this `titleLabel` Text inside
                // the churny toolbar principal item. That item rebuilds whenever
                // canOpenSync / soulName / migrationSubtitle / isSelecting change, so
                // AttributeGraph repeatedly tore down the driver's sink — a
                // use-after-free release in the setBody transaction (EXC_BAD_ACCESS,
                // build 309). The driver now lives on the stable
                // `sessionList(useNavigationLinks:)` body (identity-stable across the
                // sidebar lifetime) as a `.task` async loop, not a Combine timer sink
                // (see migrationSubtitleLoop, T-ios-migration-timer-sessionlist-uaf-crash);
                // this Text only READS the `migrationSubtitle` @State.

                if canOpenSync {
                    Button {
                        activeToolSheet = .syncMigrationDetail
                    } label: {
                        titleLabel
                    }
                    .buttonStyle(.plain)
                } else {
                    titleLabel
                }
            }
        }
        ToolbarItem(placement: .topBarLeading) {
            if isSelecting {
                Button("Cancel") {
                    isSelecting = false
                    selectedIds.removeAll()
                }
            } else {
                Button {
                    activeToolSheet = .settings
                } label: {
                    Image(systemName: "gear")
                }
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            if isSelecting {
                Button(selectedIds.count == sessions.count ? "Deselect All" : "Select All") {
                    if selectedIds.count == sessions.count {
                        selectedIds.removeAll()
                    } else {
                        selectedIds = Set(sessions.map(\.id))
                    }
                }
            } else if hasAlarms {
                Button {
                    showAlarmList = true
                } label: {
                    Image(systemName: "alarm")
                        .font(.system(size: 15, weight: .medium))
                }
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            if !isSelecting {
                Menu {
                    Button {
                        showTerminal = true
                    } label: {
                        Label("Shell Terminal", systemImage: "terminal")
                    }
                    Button {
                        activeToolSheet = .rootfsManagement
                    } label: {
                        Label("Rootfs Management", systemImage: "externaldrive")
                    }
                    Divider()
                    Button {
                        activeToolSheet = .browser
                    } label: {
                        Label("Open Browser", systemImage: "globe")
                    }
                    Button {
                        activeToolSheet = .browserManagement
                    } label: {
                        Label("Browser Settings", systemImage: "globe.badge.chevron.backward")
                    }
                    #if DEBUG
                    Divider()
                    // [debug] Keep Screen Awake — disables auto-lock while the app
                    // is foregrounded. Tap toggles; a checkmark shows the current
                    // state. Memory-only (not persisted). DEBUG builds only.
                    Button {
                        keepScreenAwake.toggle()
                        UIApplication.shared.isIdleTimerDisabled = keepScreenAwake
                    } label: {
                        Label("Keep Screen Awake", systemImage: keepScreenAwake ? "checkmark.circle.fill" : "sun.max")
                    }
                    #endif
                } label: {
                    Image("TerminalCircle")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 24, height: 24)
                }
            }
        }
    }

    // MARK: - Selection Helpers

    /// Whether a session row should be highlighted as selected.
    private func isSessionHighlighted(_ sessionId: String) -> Bool {
        if selectedSessionId == sessionId { return true }
        // Highlight the real session when a draft is selected and it has been persisted
        if let selId = selectedSessionId, Self.isNewSessionId(selId), sessionId == newSessionRealId { return true }
        return false
    }

    private func fetchAlarmsIfNeeded() {
        // AlarmOffloadBridge references AlarmKit types (AlarmMetadata).
        // On OS versions where AlarmKit doesn't exist (e.g. macOS 14),
        // merely referencing the class triggers Swift runtime type resolution
        // that crashes in swift_getTypeByMangledNameInContextImpl.
        // Use a helper that isolates the reference behind @available.
        guard #available(iOS 26.0, *) else { return }
        _fetchAlarmsImpl()
    }

    @available(iOS 26.0, *)
    private func _fetchAlarmsImpl() {
        AlarmOffloadBridge.listAlarms { arr, _ in
            DispatchQueue.main.async {
                hasAlarms = (arr?.count ?? 0) > 0
            }
        }
    }

    // MARK: - Navigation Helper

    /// Handle a "new chat" request (notification or Quick Action trigger).
    ///
    /// Contract: "land on a fresh new chat, never hijack an existing one."
    /// Concretely:
    ///
    ///   1. If any chat is currently open (real OR draft, on iPad detail
    ///      OR iPhone navigation stack), unwind back to the session list
    ///      root WITHOUT animation — animated transitions race the
    ///      subsequent `openSession()` and the new chat sometimes never
    ///      appears (push coalesced into the running pop).
    ///   2. Once the unwind has settled (NavigationStack path empty on
    ///      iPhone, `selectedSessionId == nil` on iPad), open the new
    ///      draft session.
    ///   3. AIChatView's onAppear then consumes any
    ///      `QuickActionRouter.pendingChatAction` (.startVoice / .openCamera)
    ///      so voice / camera fires *inside* the new chat — never inside
    ///      the previous one.
    private func handleNewChatRequest() {
        let newId = Self.makeNewSessionId()
        // Clear any stale flags left over from a prior quick-action
        // request that didn't run to completion (user backgrounded the
        // app, the pop-then-push dance got interrupted, etc).
        pendingNewChatAfterPop = false
        pendingNewChatTargetId = nil
        // If a workflow is mid-flight in ensuringHome, ContentView's
        // observers (`onChange(navigationPath)` / `onChange(selectedSessionId)`)
        // drive the home-then-open sequence. Otherwise this call came
        // from a non-quick-action source (in-app menu "New Chat") and
        // we just open directly.
        let workflowEnsuringHome: Bool = {
            if case .ensuringHome = QuickActionWorkflow.shared.state { return true }
            return false
        }()
        if workflowEnsuringHome {
            popToHomeForQuickAction()
            return
        }
        if isWideLayout {
            openSession(newId)
        } else {
            commitNavigationPath(NavigationPath([newId]))
            currentStackSessionId = newId
        }
    }

    /// Drive the quick-action workflow's `ensuringHome → pendingDispatch
    /// → waitingForChatMount` sequence. Pops whatever session is on top
    /// without animation; observers (see below) call `markHome` once
    /// the unwind is observable, and a separate observer for
    /// `pendingDispatch` opens the new session.
    private func popToHomeForQuickAction() {
        // [T-ios-bg-nav-push-watchdog] A pop supersedes any push still held
        // back from the background — flushing it later would resurrect a
        // session the workflow just asked us to leave. Dropped before the
        // `alreadyHome` check so the early return can't strand it either.
        //
        // Popping itself is NOT deferred: it tears a screen down rather than
        // laying a new one out (so it doesn't carry the cost this gate exists
        // to keep out of the watchdog window), and the quick-action state
        // machine advances from the `onChange(of: navigationPath)` observer
        // this write triggers — holding it back would stall `markHome`.
        pendingBackgroundNavigation = nil
        let alreadyHome = isWideLayout ? (selectedSessionId == nil) : navigationPath.isEmpty
        if alreadyHome {
            // Same-runloop advance — no SwiftUI commit needed.
            QuickActionWorkflow.shared.markHome()
            return
        }
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) {
            if isWideLayout {
                selectedSessionId = nil
            } else {
                navigationPath = NavigationPath()
                currentStackSessionId = nil
            }
        }
        // `onChange(navigationPath)` / `onChange(selectedSessionId)`
        // will fire next runloop with the empty state and call
        // `markHome` from there.
    }

    /// React to workflow state advancing past ensuringHome. When the
    /// workflow says it's ready for a session, mint a fresh draft id
    /// and open it; attach to the workflow so AIChatView's modifier
    /// can match.
    fileprivate func openSessionForPendingQuickAction() {
        guard case .pendingDispatch = QuickActionWorkflow.shared.state else { return }
        let newId = Self.makeNewSessionId()
        if isWideLayout {
            openSession(newId)
        } else {
            commitNavigationPath(NavigationPath([newId]))
            currentStackSessionId = newId
        }
        QuickActionWorkflow.shared.attachTargetSession(newId)
    }

    /// Switch to `id` as the one and only session on screen, replacing whatever
    /// is currently there.
    ///
    /// [T-ios-moveto-transfer-race] Use this for any "jump straight to session
    /// X" navigation that can fire while another session is already on the
    /// stack (move-to, notification/intent opens). The compact branch must NOT
    /// do "animated pop, then push after a blind delay": the push lands inside
    /// the ~0.35s pop animation and SwiftUI coalesces or drops it, leaving the
    /// user on the session list with `currentStackSessionId` already updated —
    /// which then makes the retry guard swallow a second attempt at the same
    /// target. Replacing the path atomically inside a disabled-animation
    /// transaction is the pattern `handleNewChatRequest` already proved safe,
    /// and it commits in one runloop with no window for the race.
    /// [T-ios-bg-nav-push-watchdog] Commit a compact-layout `navigationPath`
    /// change, or hold it until the app is active again.
    ///
    /// Every compact navigation helper routes its path write through here so
    /// the background gate cannot be bypassed by adding a new caller. See
    /// `pendingBackgroundNavigation` for the crash evidence.
    ///
    /// `currentStackSessionId` is still assigned by the callers, exactly as
    /// before — it is the app's own "which session did we navigate to" record,
    /// read by the quick-action / move-to retry guards, and it must reflect the
    /// requested target immediately even when the UIKit push is deferred.
    /// `previousStackSessionId` stays in lockstep because it is maintained by
    /// the `onChange(of: navigationPath)` observer, which simply runs later —
    /// when the deferred path is actually committed.
    private func commitNavigationPath(_ newPath: NavigationPath) {
        // [T-share-first-tap-no-response] `.inactive` is NOT the state this
        // gate was built for. The watchdog kills it prevents come from a push
        // running AIChatView's whole first layout while the app is genuinely
        // BACKGROUNDED (T-ios-bg-nav-push-watchdog); `.inactive` means the app
        // is on its way to the foreground — the system is about to give it a
        // full frame budget — and iOS delivers `openURL` in exactly that state.
        //
        // Device log 2026-08-18 23:55:31: the share URL arrived at .478 with
        // the app `.inactive`, the navigation was deferred, and `.active` only
        // landed 31ms later. The user saw the app open on the home screen with
        // nothing happening and tapped Share a second time — the reported
        // "first tap does nothing". Deferring here bought no safety: the flush
        // performs the identical push moments later, just after the user has
        // already given up on it.
        let appState = UIApplication.shared.applicationState
        if appState == .background {
            // Coalesce: only the newest requested destination matters. An
            // earlier pending push that never became visible has nothing to
            // preserve. Overwriting also restarts the TTL, which is correct —
            // the newest request is the one whose freshness we care about.
            pendingBackgroundNavigation = (newPath, Date())
            shareLog.info("🔄SESSION deferring nav commit — app backgrounded (count=\(newPath.count))")
            return
        }
        pendingBackgroundNavigation = nil
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) {
            navigationPath = newPath
        }
    }

    /// Apply a navigation change that was held back while backgrounded.
    /// Called from the `scenePhase == .active` observer.
    private func flushPendingBackgroundNavigation() {
        guard let pending = pendingBackgroundNavigation else { return }
        // Re-check rather than trust the caller: the `.onAppear` backstop can
        // run while still backgrounded, and committing there would reinstate
        // exactly the synchronous background push this gate exists to prevent.
        // Keep it pending — a later flush will take it.
        // [T-share-first-tap-no-response] Mirrors commitNavigationPath: only a
        // genuinely BACKGROUNDED app must hold the push back. Blocking on
        // `.inactive` here too would re-deny the flush during the very
        // foreground transition that is supposed to release it.
        guard UIApplication.shared.applicationState != .background else { return }
        // Consume before the staleness check, not after: an expired request is
        // dropped for good. Leaving it parked would let a later foreground —
        // which is even further from the original intent — try again.
        pendingBackgroundNavigation = nil
        let age = Date().timeIntervalSince(pending.deferredAt)
        guard age <= Self.pendingBackgroundNavigationTTL else {
            shareLog.info("🔄SESSION discarding deferred nav commit — stale (age=\(Int(age))s > \(Int(Self.pendingBackgroundNavigationTTL))s, count=\(pending.path.count))")
            return
        }
        shareLog.info("🔄SESSION flushing deferred nav commit (count=\(pending.path.count) age=\(Int(age))s)")
        var tx = Transaction()
        tx.disablesAnimations = true
        withTransaction(tx) {
            navigationPath = pending.path
        }
    }

    private func switchToSession(_ id: String) {
        if isWideLayout {
            openSession(id)
            return
        }
        searchFocused = false
        commitNavigationPath(NavigationPath([id]))
        currentStackSessionId = id
    }

    /// Opens a session in the appropriate layout mode.
    private func openSession(_ id: String) {
        draftLog.info("🔑DRAFT openSession id=\(id) isWide=\(isWideLayout) prevSel=\(selectedSessionId ?? "nil") prevReal=\(newSessionRealId ?? "nil") prevDraft=\(activeDraftId ?? "nil")")
        // Dismiss keyboard when navigating to a session
        searchFocused = false
        if isWideLayout {
            // Reset new-session tracking when navigating away
            if Self.isNewSessionId(id) {
                newSessionRealId = nil
                activeDraftId = nil
            } else if let current = selectedSessionId, Self.isNewSessionId(current) {
                newSessionRealId = nil
                activeDraftId = nil
            }
            selectedSessionId = id
            draftLog.info("🔑DRAFT openSession DONE selectedSessionId=\(id)")
        } else {
            // [T-ios-bg-nav-push-watchdog] Append onto whatever the path WILL
            // be — a deferred commit is the newer truth, so basing the append
            // on the still-stale live `navigationPath` would drop it.
            var next = pendingBackgroundNavigation?.path ?? navigationPath
            next.append(id)
            commitNavigationPath(next)
            currentStackSessionId = id
        }
    }

    // MARK: - Session Context Menu

    /// Cached, language-aware "Lock with <biometry>" menu label.
    ///
    /// [T-ios-contextmenu-localized-mainthread-hang] `String(localized:)` is an
    /// EAGER Foundation catalog lookup (unlike `LocalizedStringKey`, which
    /// SwiftUI resolves lazily at render). The lock label is the only eager
    /// `String(localized:)` on the contextMenu body-build path. With ~2000
    /// sessions, every AttributeGraph recompute re-ran this lookup once per
    /// cell on the main thread, accumulating into multi-hundred-second hangs
    /// (measured 226s / 106s). The string depends only on the active app
    /// language and the device biometry name — both fixed for a given render
    /// generation — so we compute it once and reuse it.
    ///
    /// Invalidation: keyed on `appLanguage` (the UserDefaults source of truth
    /// the language picker writes; switching it also re-keys the root via
    /// `.id(appLanguage)`, so the whole tree re-mounts) plus the biometry name.
    /// When the key changes the value is recomputed under the new
    /// `languageBundle`, so a language switch is never locked to the old string.
    private static let lockLabelLock = NSLock()
    private nonisolated(unsafe) static var cachedLockLabel: String?
    private nonisolated(unsafe) static var cachedLockLabelKey: String?

    fileprivate static func lockWithBiometryLabel() -> String {
        let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? ""
        let biometry = BiometricAuth.biometryDisplayName
        let key = "\(lang)\u{1F}\(biometry)"
        lockLabelLock.lock()
        defer { lockLabelLock.unlock() }
        if cachedLockLabelKey == key, let cached = cachedLockLabel {
            return cached
        }
        let value = AppLocalized("Lock with \(biometry)")
        cachedLockLabel = value
        cachedLockLabelKey = key
        return value
    }

    // MARK: - Menu action wiring

    /// [T-ios-crash-contextmenu-uaf] Connect the stable action channel to
    /// ContentView's instance methods. Called once from onAppear; the closure
    /// captures `self` whose @State backing is stable for the view-graph
    /// lifetime, so the handler remains valid even if body re-evaluates.
    private func wireMenuActions() {
        menuActions.handler = { [self] action in
            switch action {
            case .togglePin(let sid):
                Task { @MainActor in
                    await ChatStore.shared.toggleSessionPin(sid)
                    refreshSessionList()
                }
            case .exportJSON(let sid):
                exportSessions(ids: [sid], format: .json)
            case .exportText(let sid):
                exportSessions(ids: [sid], format: .plainText)
            case .editTitle(let sid):
                if let current = sessions.first(where: { $0.id == sid }) {
                    sessionToEdit = current
                }
            case .regenerateTitle(let sid):
                regenerateTitle(sessionId: sid)
            case .lockSession(let sid):
                SessionLockStore.shared.lock(sid)
            case .unlockSession(let sid):
                Task {
                    let reason = AppLocalized("Unlock this session to remove \(BiometricAuth.biometryDisplayName) protection")
                    let ok = await BiometricAuth.authenticate(reason: reason)
                    if ok { SessionLockStore.shared.unlockPermanently(sid) }
                }
            case .duplicate(let sid):
                Task { @MainActor in
                    if let dup = await SessionForkManager.shared.duplicateSession(sessionId: sid) {
                        refreshSessionList()
                        selectedSessionId = dup.id
                    }
                }
            case .forceSync(let sid):
                runForceSyncOnSelection(singleSession: sid)
            case .forcePull(let sid):
                runForcePullOnSession(sid)
            case .select(let sid):
                isSelecting = true
                selectedIds = [sid]
                scrollToId = sid
            case .moveToFolder(let sid):
                let filed = sessions.first(where: { $0.id == sid })?.isFiled ?? false
                folderPickerRequest = FolderPickerRequest(
                    sessionIds: [sid], fromMultiSelect: false, anyFiled: filed)
            case .delete(let sid):
                print("[DELETE] Context menu tapped for session: \(sid)")
                let info = Self.computeDeleteInfo(for: [sid], totalSessions: sessions.count)
                print("[DELETE] computeDeleteInfo returned: sessionCount=\(info.sessionCount), fileCount=\(info.totalFileCount), size=\(info.totalSize)")
                singleDeleteInfo = info
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.55) {
                    print("[DELETE] singleDeleteInfo set, now setting sessionToDelete")
                    if let current = sessions.first(where: { $0.id == sid }) {
                        sessionToDelete = current
                    }
                    print("[DELETE] sessionToDelete set, sheet should appear")
                }
            }
        }
    }

    // MARK: - Remote Device Sessions

    /// [T-ios-session-list-equatable-jank] id→remote ChatSession lookup, keyed
    /// "deviceId:sessionId". Lets the remote-session rows resolve their model by
    /// id so the ForEach below diffs `[String]`, not `[ChatSession]`.
    private var remoteSessionsById: [String: ChatSession] {
        var d: [String: ChatSession] = [:]
        for entry in remoteDeviceSessions {
            for s in entry.sessions { d["\(entry.device.id):\(s.id)"] = s }
        }
        return d
    }

    @ViewBuilder
    private var remoteDeviceSessionSections: some View {
        let _ = syncLog.info("[iCloud] remoteDeviceSessionSections evaluated: \(remoteDeviceSessions.count) devices, total sessions=\(remoteDeviceSessions.reduce(0) { $0 + $1.sessions.count })")
        let byId = remoteSessionsById
        // [T-ios-session-list-equatable-jank] Project to (deviceId, deviceName,
        // sessionIds) so SwiftUI diffs id strings instead of deep-comparing the
        // [ChatSession] value array on every transaction flush — same fix as the
        // local sidebar list. (This remote section was the path still triggering
        // Array<ChatSession>.== in the HangDetector stacks after the local-list
        // fix landed.)
        let deviceSections: [(deviceId: String, name: String, ids: [String])] =
            remoteDeviceSessions.map { e in
                (deviceId: e.device.id, name: e.device.deviceName, ids: e.sessions.map(\.id))
            }
        ForEach(deviceSections, id: \.deviceId) { entry in
            let _ = syncLog.info("[iCloud] rendering section for device=\(entry.deviceId) name=\(entry.name) sessions=\(entry.ids.count)")
            Section {
                ForEach(entry.ids, id: \.self) { sessionId in
                    if let session = byId["\(entry.deviceId):\(sessionId)"] {
                        NavigationLink(value: "remote:\(entry.deviceId):\(session.id)") {
                            RemoteSessionRow(session: session)
                        }
                        .listRowInsets(EdgeInsets())
                        .listRowSeparator(.hidden)
                        .contextMenu {
                            Button {
                                // [T-ios-state-publish-offmain-crash] @MainActor
                                // so the @State write stays on the main thread.
                                let sid = session.id
                                let deviceId = entry.deviceId
                                Task { @MainActor in
                                    if let forked = await SessionForkManager.shared.forkSession(
                                        remoteSessionId: sid, remoteDeviceId: deviceId
                                    ) {
                                        refreshSessionList()
                                        selectedSessionId = forked.id
                                    }
                                }
                            } label: {
                                Label("Fork Session", systemImage: "arrow.branch")
                            }
                        }
                    }
                }
            } header: {
                Label(entry.name, systemImage: "iphone")
            }
        }
    }

    // [T-ios-state-publish-offmain-crash] @MainActor: writes `remoteDeviceSessions`
    // (@State) after several ChatStore (actor) awaits; without this the
    // continuation could resume off-main and publish state from a background
    // thread (the [ChatSession] AttributeGraph-compare crash path).
    @MainActor
    private func refreshRemoteDeviceSessions() async {
        let allDevices = await ChatStore.shared.listSyncDevices()
        let devices = allDevices.filter { $0.id != DeviceIdentity.deviceId }
        syncLog.info("[RemoteSync] refreshRemoteDeviceSessions: \(allDevices.count) total devices, \(devices.count) remote (self=\(DeviceIdentity.deviceId))")
        var result: [(device: SyncDevice, sessions: [ChatSession])] = []
        for device in devices {
            let remoteSessions = await ChatStore.shared.listRemoteSessions(deviceId: device.id)
            syncLog.info("[RemoteSync] device \(device.id) (\(device.deviceName)): \(remoteSessions.count) sessions")
            if !remoteSessions.isEmpty {
                result.append((device: device, sessions: remoteSessions))
            }
        }
        syncLog.info("[RemoteSync] Final result: \(result.count) devices with sessions")
        remoteDeviceSessions = result
    }

    private let syncLog = AppLogger(category: "RemoteSync")

    // MARK: - Share Extension Handling

    private func processPendingShare() {
        shareLog.info("[Share] processPendingShare called")

        // [T-share-vs-shortcut-state] An incoming share is the user's newest
        // explicit intent. If a Shortcuts / Home-Screen quick-action workflow
        // is still sitting in a non-idle state from an EARLIER launch (e.g.
        // `waitingForChatMount` stranded because the app was backgrounded
        // before the draft view mounted — that state has no timeout of its
        // own), it keeps steering navigation: its `ensuringHome` timeout or
        // `pendingDispatch` observer can fire `openSessionForPendingQuickAction`
        // and REPLACE the share's freshly opened session (and arm a stale
        // camera/voice cover on it) while the user is already typing. Clear
        // stale residue before the share takes over navigation. The 10s bar
        // leaves a genuinely in-flight workflow (cold quick-action launch
        // racing a persisted share record) untouched — that one still owns
        // its own checkpoints.
        QuickActionWorkflow.shared.resetIfStale(olderThan: 10, reason: "incoming share takeover")

        guard let pending = SharedContainerStore.loadPendingShare() else {
            // [T-share-double-raise] Reaching here with a buffer already staged
            // is the DUPLICATE pass, not a failure: the record was consumed and
            // cleared microseconds ago by the first pass. Say so explicitly —
            // the old "no data from extension" wording made a benign duplicate
            // look like the extension had written nothing.
            if shareCoordinator.pendingShareBuffer != nil {
                shareLog.info("[Share] processPendingShare: record already consumed, buffer staged (duplicate raise) — no-op")
            } else {
                shareLog.warning("[Share] loadPendingShare returned nil — no data from extension")
            }
            shareCoordinator.hasPendingShare = false
            return
        }

        shareLog.info("[Share] Loaded \(pending.items.count) items into buffer — launch flow unchanged")
        for (i, item) in pending.items.enumerated() {
            shareLog.info("[Share]   item[\(i)] kind=\(item.kind.rawValue) value=\(String(item.value.prefix(100)))")
        }

        SharedContainerStore.clearPendingShare()
        shareCoordinator.hasPendingShare = false
        shareCoordinator.storeBuffer(pending)
        // Navigation is NOT touched here. The normal launch screen logic runs independently.
        // AIChatView will consume the buffer when a new chat session is created (cached.isNew=true).
    }

    // MARK: - Empty State

    @StateObject private var providerStore = ProviderConfigStore.shared
    @State private var showAddProvider = false
    @State private var showSelectModels = false

    private var emptyState: some View {
        let hasProviders = !providerStore.instances.isEmpty
        let hasGroups = !providerStore.modelGroups.isEmpty

        return VStack(spacing: 32) {
            Spacer()
            // App icon / hero
            Image(systemName: "sparkles")
                .font(.system(size: 56))
                .foregroundStyle(.tint)
                .padding(.bottom, 4)

            VStack(spacing: 8) {
                Text("Welcome to Minis")
                    .font(.title2.bold())
                Text("Your first On-Device Agent is almost ready.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }

            // Setup steps
            VStack(spacing: 16) {
                // Step 1 – Add Provider
                setupStep(
                    number: 1,
                    title: "Add a Provider",
                    subtitle: hasProviders ? "Done" : "Configure an API key or sign in with OAuth",
                    isDone: hasProviders
                ) {
                    if !hasProviders {
                        showAddProvider = true
                    }
                }

                // Step 2 – Select Models
                setupStep(
                    number: 2,
                    title: "Select Models",
                    subtitle: hasGroups ? "Done" : (hasProviders ? "Choose the models you want to use" : "Complete step 1 first"),
                    isDone: hasGroups
                ) {
                    if hasProviders && !hasGroups {
                        showSelectModels = true
                    }
                }

                // Step 3 – Start chatting
                setupStep(
                    number: 3,
                    title: "Start a Conversation",
                    subtitle: hasGroups ? "Tap the button below to begin" : "Complete step 2 first",
                    isDone: false
                ) {
                    if hasGroups {
                        openSession(Self.makeNewSessionId())
                    }
                }
            }
            .padding(.horizontal, 8)
            .frame(maxWidth: 400)
            Spacer()
        }
        .frame(maxHeight: .infinity)
        .padding(.horizontal, 32)
        .sheet(isPresented: $showAddProvider) {
            NavigationStack {
                AddProviderView()
            }
        }
        .sheet(isPresented: $showSelectModels) {
            NavigationStack {
                OnboardingModelSelectionView()
            }
        }
    }

    // title/subtitle must stay LocalizedStringKey, not String. A string literal at
    // the call site localizes fine on its own, but routing it through a String
    // parameter erases that: `Text(String)` stores the value verbatim and never
    // consults the string table, so these steps rendered English even though
    // Localizable.xcstrings has all 8 locales for these keys. Typing the parameter
    // as LocalizedStringKey keeps the literals (both branches of the `isDone`
    // ternaries included) resolving as keys.
    private func setupStep(
        number: Int,
        title: LocalizedStringKey,
        subtitle: LocalizedStringKey,
        isDone: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                // Step indicator
                ZStack {
                    Circle()
                        .fill(isDone ? Color.green : Color.accentColor)
                        .frame(width: 32, height: 32)
                    if isDone {
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                    } else {
                        Text("\(number)")
                            .font(.system(size: 15, weight: .semibold, design: .rounded))
                            .foregroundColor(.white)
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(isDone ? .secondary : .primary)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if !isDone {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color(UIColor.secondarySystemGroupedBackground))
            )
        }
        .buttonStyle(.plain)
        .disabled(isDone)
    }

    // MARK: - Search Bar

    private func scheduleSearch() {
        searchTask?.cancel()
        let query = searchText.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else {
            searchMatchedIds = nil
            searchMatchSnippets = [:]
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000) // 300ms debounce
            guard !Task.isCancelled else { return }
            let results = await ChatStore.shared.searchSessions(query: query)
            if !Task.isCancelled {
                searchMatchedIds = Set(results.map(\.session.id))
                // Build a sessionId → snippet map for body-only matches
                // (title matches don't need a snippet — the highlighted
                // title already shows the hit). T-search-highlight 8edb74f2.
                var snippets: [String: String] = [:]
                for r in results {
                    if !r.titleMatched, let snip = r.matchSnippet, !snip.isEmpty {
                        snippets[r.session.id] = snip
                    }
                }
                searchMatchSnippets = snippets
            }
        }
    }

    private func dismissSearch() {
        withAnimation(.easeInOut(duration: 0.2)) { showSearchBar = false }
        searchText = ""
        searchMatchedIds = nil
        searchMatchSnippets = [:]
        searchTask?.cancel()
        searchFocused = false
    }

    /// [T-ios-search-focus-sticky] Auto-exit the search bar when the user
    /// navigates into a session (or starts a new chat) WITHOUT having typed an
    /// actual query. Rationale: tapping the search field then tapping a chat
    /// without searching used to leave the field revealed + focused on return,
    /// forcing a manual tap on the ✕. If there IS a non-blank query, we keep the
    /// search state intact so the user returns to their results. Whitespace-only
    /// text counts as empty. No-op when the search bar isn't shown.
    private func dismissSearchIfEmptyOnNavigate() {
        guard showSearchBar else { return }
        guard searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        dismissSearch()
    }

    /// [T-ios-session-coldload-listsessions-block] Refresh the session list to
    /// pick up any preview change in the session the user just left, but only
    /// AFTER the incoming session's loadSession() has claimed and released the
    /// ChatStore actor for its DB reads. Firing listSessions() synchronously on
    /// navigation raced it onto the (non-preemptible, serialized) actor and hung
    /// the incoming spinner ~1.5s. A 0.6s debounce clears the incoming load
    /// (~50-150ms) with margin and coalesces bursts of session switching into a
    /// single trailing scan.
    private func scheduleOutgoingPreviewRefresh() {
        outgoingPreviewRefreshWork?.cancel()
        let work = DispatchWorkItem {
            refreshSessionList()
        }
        outgoingPreviewRefreshWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: work)
    }

    /// [T-ios-listsessions-refresh-coalesce] Single serialized entry point for
    /// refreshing `sessions` from the store. If a refresh is already running,
    /// mark one trailing run pending instead of spawning a concurrent Task —
    /// collapsing bursts (navigation + sync + streaming ticks) into at most one
    /// in-flight + one queued run. The ChatStore cache makes a clean run cheap.
    @MainActor
    private func refreshSessionList() {
        guard !sessionRefreshInFlight else {
            sessionRefreshPending = true
            return
        }
        sessionRefreshInFlight = true
        Task(priority: .utility) { @MainActor in
            sessions = await ChatStore.shared.listSessions()
            folders = await ChatStore.shared.listFolders()
            sessionRefreshInFlight = false
            if sessionRefreshPending {
                sessionRefreshPending = false
                refreshSessionList()
            }
        }
    }

    /// Reveal the search bar (if hidden) and focus its text field. Bound to the
    /// hardware ⌘F shortcut while the session list is on screen. Focusing inside
    /// the reveal animation is unreliable, so set focus on the next runloop tick
    /// once the field exists in the view tree.
    private func focusSearch() {
        if !showSearchBar {
            withAnimation(.easeInOut(duration: 0.2)) { showSearchBar = true }
        }
        DispatchQueue.main.async { searchFocused = true }
    }

    // MARK: - FAB Row (New Chat + Search)

    @State private var fabDidDrag = false
    @FocusState private var searchFocused: Bool

    @State private var searchDragOffset: CGFloat = 0
    @State private var searchDidDrag = false

    /// Namespace tying the search FAB and the expanded search bar together as one
    /// glass fragment, so the two morph instead of cross-fading (iOS 26+).
    @Namespace private var fabGlassNamespace

    /// Brand fill for the new-chat FAB. On iOS 26 this becomes the glass TINT
    /// rather than an opaque fill, so the button keeps its colour identity while
    /// the system material supplies the depth. Below 26 it stays the flat fill
    /// it has always been.
    private static let newChatBrandColor = Color(UIColor { $0.userInterfaceStyle == .dark
        ? UIColor(red: 80/255, green: 76/255, blue: 66/255, alpha: 1)
        : UIColor(red: 183/255, green: 175/255, blue: 150/255, alpha: 1) })

    /// Glass TINT for the new-chat FAB — deliberately NOT `newChatBrandColor`.
    ///
    /// Tinting with the raw brand colour at full strength produced an opaque
    /// disc with no refraction at all (measured on device: ring pixel std 1.1
    /// against ~20 for untinted glass), which is what read as "not glass".
    /// Simply lowering that colour's alpha fixed light mode but vanished in
    /// dark: the dark brand value (80/76/66) sits so close to the near-black
    /// page that 0.15–0.25 alpha yielded a warmth (R−B) of only 1.6–3.1,
    /// indistinguishable from the untinted button.
    ///
    /// So the dark variant is lifted and warmed rather than just faded. At
    /// alpha 0.30 the measured result is better than the old opaque version on
    /// BOTH axes in dark mode — warmth 22.6 vs 14.0 (more brand identity) with
    /// ring std 9.3 vs 1.1 (real show-through) — and light mode keeps a warm
    /// cast (warmth 9.9) while staying visibly translucent.
    /// `Glass.tint` honours the colour's alpha, so the strength is baked in here.
    private static let newChatGlassTint = Color(UIColor { $0.userInterfaceStyle == .dark
        ? UIColor(red: 196/255, green: 176/255, blue: 120/255, alpha: 0.30)
        : UIColor(red: 183/255, green: 175/255, blue: 150/255, alpha: 0.30) })

    /// Clear/dismiss control inside the expanded search capsule.
    ///
    /// A bare glyph with NO background of its own, which is what system search
    /// fields do. The alternatives were compared on device: `.buttonStyle(.glass)`
    /// and a small `glassEffect` circle both put a second glass surface INSIDE
    /// the already-glass capsule, and glass-on-glass read as a raised, competing
    /// control — noticeably so in dark mode — for what is only a small clear
    /// action. Letting the capsule stay the single glass surface keeps the row
    /// coherent with the FABs beside it.
    ///
    /// `xmark` rather than the old `xmark.circle.fill`: the filled circle was
    /// itself a solid background, the very thing that clashed with the glass.
    /// Sub-26 keeps the original filled-circle look, where there is no glass for
    /// it to fight with.
    @ViewBuilder
    private var searchClearButton: some View {
        if #available(iOS 26.0, *) {
            Button { dismissSearch() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                    // [T-ios-search-bar-glass-hit-hole] The frame alone was not
                    // a tap target: with `.buttonStyle(.plain)` and no fill,
                    // hit-testing follows the DRAWN GLYPH, so only the ~13pt
                    // strokes of the "xmark" were live and the rest of this box
                    // was a hole. Height goes to 44 (Apple's minimum) and
                    // `contentShape` makes the whole box tappable; the glyph is
                    // unchanged, so this is hit area only, no visual change.
                    // The bar is 56pt tall, so 44 fits without growing it.
                    .frame(width: 32, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            Button { dismissSearch() } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    /// Glyph colour for the new-chat FAB.
    ///
    /// Was hardcoded `.white`, which worked when the button was an opaque
    /// mid-tone brand disc. Once the fill became 0.30-alpha glass the light-mode
    /// surface turned pale and a white glyph nearly vanished into it.
    ///
    /// Note this can't simply be `Color(UIColor.label)` like the search FAB uses:
    /// that FAB is glass on 26+ AND `secondarySystemBackground` below it, both
    /// light-ish surfaces, so `label` is right in every case. The new-chat FAB's
    /// sub-26 fallback is still the OPAQUE brand colour (183/175/150 light,
    /// 80/76/66 dark) — mid-tone in light mode, where `label` (near-black) has
    /// less contrast than the white that shipped there for years. So the choice
    /// is made per rendering path: adaptive on glass, unchanged white on the
    /// opaque fallback.
    private static var newChatIconColor: Color {
        if #available(iOS 26.0, *) {
            // Translucent glass: follow the interface style. Not pure black in
            // light mode — the brand surface is warm, so a slightly softened
            // near-black sits better on it than #000 while still reading clearly.
            return Color(UIColor { $0.userInterfaceStyle == .dark
                ? UIColor.white
                : UIColor(white: 0.13, alpha: 1) })
        } else {
            // Opaque brand disc, as before.
            return .white
        }
    }

    /// Circular FAB surface wrapping its `icon`.
    ///
    /// The icon has to be passed IN rather than `.overlay`-ed on afterwards:
    /// `.glassEffect` draws the material over the view it modifies, so an
    /// overlay applied to the surface ends up UNDER the glass and disappears
    /// (verified on device — the tinted disc rendered with no glyph, pixel
    /// variance ~1.2 across its centre). Putting the icon inside means the glass
    /// is the background and the glyph rides on top of it.
    ///
    /// iOS 26+: `.glassEffect(.regular[.tint], in: .circle)`. The old manual
    /// `.shadow` is dropped on that path on purpose — Liquid Glass renders its
    /// own shadow/edge, and stacking the hand-rolled one on top reads as a dark
    /// halo rather than depth. Sub-26 keeps the original opaque circle AND its
    /// shadow, unchanged.
    ///
    /// Unlike `FolderSurface`, live glass is safe here: the FAB floats in a
    /// `safeAreaInset` over a stable backdrop and never scrolls past
    /// heterogeneous content, so the flicker that forced that type onto a
    /// sampled constant does not apply.
    @ViewBuilder
    private func fabCircleSurface<Icon: View>(
        tint: Color?,
        fallbackFill: Color,
        fallbackShadowOpacity: Double,
        @ViewBuilder icon: () -> Icon
    ) -> some View {
        if #available(iOS 26.0, *) {
            icon()
                .frame(width: 56, height: 56)
                .glassEffect(
                    tint.map { Glass.regular.tint($0) } ?? Glass.regular,
                    in: .circle
                )
                // [T-fab-glass-contextmenu-regression] Without this the long-press
                // menu on the new-chat FAB stops opening.
                //
                // The pre-glass label was `Circle().fill(...)`, a filled shape,
                // so its hit-test region was the whole 56x56 disc and
                // `.contextMenu` (attached to that same view) picked up a
                // long-press anywhere on the button. The glass version's content
                // is a bare `Image` — `.frame` only reserves space, and
                // `.glassEffect` draws a material without contributing a
                // hit-testable shape — so the only interactive pixels left were
                // the glyph's own strokes. A long press on the surrounding
                // (visually filled) area hit nothing and no menu appeared.
                //
                // Tapping still worked, which is what made this look like a
                // gesture-priority fight with glassEffect rather than a hit-test
                // hole: DraggableFAB re-applies `.frame` and `.onTapGesture` at
                // ITS level, one layer out, so taps were being caught there.
                // `.contextMenu` is the only one of the three attached inside.
                //
                // Restoring an explicit circular content shape gives the glass
                // surface the same hit region the filled Circle had. Applied
                // AFTER glassEffect so it covers the rendered disc.
                .contentShape(.circle)
                // …and the same shape again for the context-menu PREVIEW.
                // `.contentShape(_:)` only sets the INTERACTION region; the
                // lifted platter resolves its shape separately and otherwise
                // falls back to the view's rectangular bounds, which is what
                // showed a grey rounded-rect slab peeking out from under the
                // circular button on long press. `ChatMessageRow` already
                // declares the two shapes separately for the same reason.
                .contentShape(.contextMenuPreview, Circle())
        } else {
            Circle()
                .fill(fallbackFill)
                .overlay { icon() }
                .shadow(color: .black.opacity(fallbackShadowOpacity), radius: 8, x: 0, y: 4)
        }
    }

    @ViewBuilder
    private var fabRow: some View {
        // [T-fab-glass-contextmenu-regression] NO GlassEffectContainer here.
        //
        // The row was briefly wrapped in `GlassEffectContainer(spacing: 10)` so the
        // search FAB and the expanded search bar (which share a `glassEffectID`)
        // would morph into one another like the system Dock. That container is what
        // broke the new-chat FAB's long-press "New Chat with Group" menu.
        //
        // Verified on device by A/B-ing the view tree with the debug inspector, same
        // screen both times, looking for views owning a `UIContextMenuInteraction`:
        //   with the container:  [WKContentView, UpdateCoalescingCollectionView]
        //   without it:          [WKContentView, HostingView, UpdateCoalescingCollectionView]
        // The `HostingView` that hosts this row only gets its context-menu
        // interaction when the container is absent — inside it, SwiftUI never
        // materialises one, so no long press can ever raise the menu no matter how
        // the hit region is shaped. (That is why the earlier `.contentShape(.circle)`
        // fix did not help: it widened a hit region for an interaction that was
        // never created.)
        //
        // Dropping the container costs only the FAB→search-bar morph animation,
        // which falls back to the scale+opacity transition the row already declares.
        // The glass MATERIAL is unaffected — `.glassEffect` does not require a
        // container, and the FABs still render as glass (verified by screenshot).
        fabRowContent
    }

    @ViewBuilder
    private var fabRowContent: some View {
        ZStack {
            // New chat FAB (draggable)
            DraggableFAB(
                fabOnLeft: $fabOnLeft,
                dragOffset: $fabDragOffset,
                didDrag: $fabDidDrag
            ) {
                if !fabDidDrag { openSession(Self.makeNewSessionId()) }
            } label: {
                fabCircleSurface(
                    tint: Self.newChatGlassTint,
                    fallbackFill: Self.newChatBrandColor,
                    fallbackShadowOpacity: 0.2
                ) {
                    Image(systemName: {
                        if #available(iOS 17.0, *) { return "bubble.left.and.text.bubble.right" }
                        return "plus.message.fill"
                    }())
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Self.newChatIconColor)
                }
                    .contextMenu {
                        let groups = Array(ProviderConfigStore.shared.config.modelGroups.prefix(10))
                        if !groups.isEmpty {
                            Section(AppLocalized("New Chat with Group")) {
                                ForEach(groups) { group in
                                    Button {
                                        openSession(Self.makeNewSessionId(groupId: group.id))
                                    } label: {
                                        Label(group.name, systemImage: "square.stack.3d.up")
                                    }
                                }
                            }
                        }
                    }
            }

            // Search FAB or inline search bar (hidden when no sessions)
            if !sessions.isEmpty {
                if showSearchBar {
                    // Inline search bar — fills space between edges, leaving room for New Chat
                    GeometryReader { geo in
                        let fabSize: CGFloat = 56
                        let edgePad: CGFloat = 16
                        let gap: CGFloat = 10
                        let barX: CGFloat = fabOnLeft
                            ? edgePad + fabSize + gap
                            : edgePad
                        let barWidth: CGFloat = geo.size.width - edgePad * 2 - fabSize - gap

                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(.secondary)
                            TextField("Search chats...", text: $searchText)
                                .textFieldStyle(.plain)
                                .autocorrectionDisabled()
                                .focused($searchFocused)
                                .onChange(of: searchText) { _ in scheduleSearch() }
                            searchClearButton
                        }
                        // [T-ios-search-bar-glass-hit-hole] Trailing padding is
                        // trimmed to 8 so the X button's 32pt-wide target sits
                        // closer to the capsule edge instead of behind 18pt of
                        // dead space (the button keeps its own internal
                        // padding, so the glyph barely moves). Leading stays 18
                        // — that side holds the magnifier and needs the inset.
                        .padding(.leading, 18)
                        .padding(.trailing, 8)
                        .frame(width: barWidth, height: fabSize)
                        .modifier(SearchBarSurface())
                        // [T-ios-search-bar-glass-hit-hole] `glassEffect(in:)`
                        // RENDERS a capsule but contributes no hit region of
                        // its own, and this row sits in a `safeAreaInset` over
                        // the session List — an inset does not swallow touches
                        // where it has nothing hit-testable. So every point of
                        // the bar not covered by a real control (icon,
                        // TextField, X button) passed the touch straight
                        // through to the cell scrolling underneath and opened
                        // whatever session or folder was there.
                        //
                        // Declaring the capsule's shape restores the hit region
                        // to match what is drawn. NOTE this is necessary but
                        // not sufficient on its own: verified on device that
                        // the capsule still cannot fully consume taps (the
                        // a11y tree exposes no element for it, only its
                        // children), so the durable part of this fix is the
                        // enlarged, explicitly-shaped X target in
                        // `searchClearButton` — that is what the user actually
                        // aims at, and it now hits at all four corners.
                        .contentShape(.capsule)
                        .modifier(FABGlassMorphID(namespace: fabGlassNamespace))
                        .position(x: barX + barWidth / 2, y: fabSize / 2)
                    }
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.85, anchor: fabOnLeft ? .leading : .trailing).combined(with: .opacity),
                        removal: .scale(scale: 0.85, anchor: fabOnLeft ? .leading : .trailing).combined(with: .opacity)
                    ))
                    .onAppear { searchFocused = true }
                } else {
                    // Search FAB (draggable, inverted side)
                    DraggableFAB(
                        fabOnLeft: $fabOnLeft,
                        dragOffset: $searchDragOffset,
                        didDrag: $searchDidDrag,
                        inverted: true
                    ) {
                        if !searchDidDrag {
                            withAnimation(.easeInOut(duration: 0.2)) { showSearchBar = true }
                        }
                    } label: {
                        fabCircleSurface(
                            tint: nil,
                            fallbackFill: Color(UIColor.secondarySystemBackground),
                            fallbackShadowOpacity: 0.15
                        ) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(Color(UIColor.label))
                        }
                            .modifier(FABGlassMorphID(namespace: fabGlassNamespace))
                    }
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.85, anchor: fabOnLeft ? .leading : .trailing).combined(with: .opacity),
                        removal: .scale(scale: 0.85, anchor: fabOnLeft ? .leading : .trailing).combined(with: .opacity)
                    ))
                }
            }
        }
        .frame(height: 56)
        .padding(.bottom, 20)
    }

    // MARK: - Selectable Row

    private func selectableRow(_ session: ChatSession) -> some View {
        Button {
            if selectedIds.contains(session.id) {
                selectedIds.remove(session.id)
            } else {
                selectedIds.insert(session.id)
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: selectedIds.contains(session.id) ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(selectedIds.contains(session.id) ? Color.accentColor : Color(UIColor.tertiaryLabel))
                SessionRow(session: session)
            }
            .padding(.leading, 16)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Section Header

    @ViewBuilder
    private func sectionHeader(index: Int, group: SidebarGroup) -> some View {
        if isSelecting {
            let groupIds = Set(group.ids)
            let allSelected = !groupIds.isEmpty && groupIds.isSubset(of: selectedIds)
            Button {
                if allSelected {
                    selectedIds.subtract(groupIds)
                } else {
                    selectedIds.formUnion(groupIds)
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: allSelected ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 18))
                        .foregroundStyle(allSelected ? Color.accentColor : Color(UIColor.tertiaryLabel))
                    // group.label is a stable English key (used for logic like
                    // == "Pinned"); localize only at display via LocalizedStringKey.
                    // Folder names are user data — render verbatim, not as a key.
                    if group.folderId != nil {
                        Text(group.label)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color(UIColor.secondaryLabel))
                    } else {
                        Text(LocalizedStringKey(group.label))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color(UIColor.secondaryLabel))
                    }
                }
                .textCase(nil)
            }
            .buttonStyle(.plain)
        } else if index > 0 || group.label == "Pinned" {
            HStack(spacing: 4) {
                if group.label == "Pinned" {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(Color(UIColor.secondaryLabel))
                }
                // group.label stays an English key for logic; LocalizedStringKey
                // resolves the display string per system language.
                Text(LocalizedStringKey(group.label))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color(UIColor.secondaryLabel))
            }
            .textCase(nil)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(dropTargetFolderId == "" ? Color.accentColor.opacity(0.18) : Color.clear)
            )
            // Dropping on a date-bucket header moves the sessions OUT of any
            // folder — the drag gesture works both directions, otherwise
            // moving out would still require a trip through the menu.
            .dropDestination(for: String.self) { sessionIds, _ in
                Task { @MainActor in
                    await ChatStore.shared.setFolder(nil, forSessions: sessionIds)
                    refreshSessionList()
                }
                return true
            } isTargeted: { over in
                if over {
                    dropTargetFolderId = ""
                } else if dropTargetFolderId == "" {
                    dropTargetFolderId = nil
                }
            }
        }
    }

    /// Display aggregates for the Move-to-Group picker rows: the same
    /// composed icon glyphs and member count the home group card shows, plus
    /// the subtitle context — the folder's description when one is set,
    /// otherwise the newest member's title (mirroring the card's summary).
    /// Computed once per sheet presentation, not per row.
    private func folderPickerItems() -> [FolderPickerSheet.FolderItem] {
        let byFolder = Dictionary(grouping: sessions.filter { $0.folderId != nil },
                                  by: { $0.folderId! })
        return folders.map { folder in
            let m = (byFolder[folder.id] ?? []).sorted { $0.updatedAt > $1.updatedAt }
            // Top-3 DISTINCT category glyphs by recency — same rule as
            // groupedSessionIDs, so the picker icon matches the home card.
            var glyphs: [FolderGlyph] = []
            var seenCategories = Set<String>()
            for s in m where seenCategories.insert(s.category ?? "").inserted {
                let icon = sessionCategoryIcon(for: s.category)
                glyphs.append(FolderGlyph(systemName: icon.systemName, color: icon.color))
                if glyphs.count == 3 { break }
            }
            let desc = folder.desc?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return FolderPickerSheet.FolderItem(
                folder: folder,
                glyphs: glyphs,
                count: m.count,
                subtitle: desc.isEmpty ? m.first?.title : desc)
        }
    }

    /// Folder section header: composed icon + name + count + collapse chevron,
    /// with the collapsed-state status passthrough (running / paused / unread).
    ///
    /// The status badges appear ONLY when collapsed: expanded members carry
    /// their own indicators, and duplicating them in the header would leave
    /// the user guessing which row a header "!" refers to. Collapsing is what
    /// hides the members' state — that's exactly when a proxy is needed.
    /// Folder card mirroring SessionRow's frame — same 44pt icon circle, same
    /// title/subtitle stack, same trailing date column — so a folder reads as
    /// the same species of thing as a session. Only the content differs:
    /// composed member-glyph icon, folder name, and a "N chats · latest
    /// title" summary where a session shows its last message.
    /// `scrollProxy` is forwarded to `toggleFolderCollapsed` so an expand can
    /// re-anchor this header after the accordion removes the previously-open
    /// folder's rows ([T-ios-folder-accordion-scroll-anchor]).
    private func folderSectionHeader(_ group: SidebarGroup, scrollProxy: ScrollViewProxy? = nil) -> some View {
        // Content + onTapGesture instead of a Button: the Button's own
        // long-press handling raced the contextMenu recognizer on some
        // platforms (long-press on the card did not reliably pop the menu).
        // With a bare tap gesture the long-press belongs to the contextMenu
        // alone; the accessibility action keeps the card activatable for
        // AX clients and the debug tap harness.
        HStack(spacing: 8) {
                FolderComposedIcon(glyphs: group.glyphs)
                    // Same overlay corners as SessionRow: spinner ring for
                    // running, bottom-trailing badge for paused, top-trailing
                    // red dot for unread — the symbols the user already knows
                    // from session rows, in the positions they know them.
                    // Collapsed-only: expanded members carry their own
                    // indicators, and a header copy would leave the user
                    // guessing which row it refers to.
                    .overlay {
                        if group.isCollapsed && group.anyActive {
                            SpinningRing(color: group.glyphs.first?.color ?? .gray)
                                .frame(width: 42, height: 42)
                        }
                    }
                    .overlay(alignment: .bottomTrailing) {
                        if group.isCollapsed && group.anyPaused {
                            ZStack {
                                Circle().fill(Color(UIColor.systemBackground))
                                    .frame(width: 16, height: 16)
                                Image(systemName: "exclamationmark.circle.fill")
                                    .font(.system(size: 13))
                                    .foregroundStyle(.orange)
                            }
                            .offset(x: 2, y: 2)
                        }
                    }
                    .overlay(alignment: .topTrailing) {
                        if group.isCollapsed && group.anyUnread {
                            Circle()
                                .fill(Color.red)
                                .frame(width: 8, height: 8)
                                .offset(x: -1, y: 1)
                        }
                    }

                VStack(alignment: .leading, spacing: 4) {
                    // Folder names are user data — verbatim Text, never a
                    // LocalizedStringKey lookup.
                    Text(group.label)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color(UIColor.label))
                        .lineLimit(1)
                    Group {
                        if let title = group.summaryTitle {
                            Text("\(group.totalCount) chats · \(title)")
                        } else if group.totalCount > 0 {
                            Text("\(group.totalCount) chats")
                        } else {
                            Text("Empty group")
                        }
                    }
                    .font(.system(size: 14))
                    .foregroundStyle(Color(UIColor.secondaryLabel))
                    .lineLimit(1)
                }

                Spacer(minLength: 1)

                VStack(alignment: .trailing, spacing: 6) {
                    if let date = group.latestDate {
                        Text(SessionRow.relativeDateImpl(date))
                            .font(.system(size: 13))
                            .foregroundStyle(Color(UIColor.tertiaryLabel))
                    }
                    HStack(spacing: 4) {
                        if group.isFolderPinned {
                            Image(systemName: "pin.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(Color(UIColor.tertiaryLabel))
                        }
                        Image(systemName: "chevron.down")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Color(UIColor.tertiaryLabel))
                            .rotationEffect(.degrees(group.isCollapsed ? -90 : 0))
                    }
                }
            }
            // Inner 10 + outer 6 = 16pt — the same lead as SessionRow's
            // horizontal padding, so the folder icon's left edge and (via the
            // shared 44pt icon frame + 8pt spacing) the folder TITLE both sit
            // exactly on the session rows' alignment grid.
            .padding(.horizontal, 10)
            .padding(.vertical, 10)
            .textCase(nil)
            .contentShape(Rectangle())
            // Mini-bar trigger: only an EXPANDED, populated group needs its
            // header tracked (a collapsed card scrolling away is just a card).
            .background {
                if let fid = group.folderId, !group.isCollapsed, !group.ids.isEmpty {
                    FolderHeaderVisibilityProbe(
                        folderId: fid,
                        thresholdY: folderMiniBarTopY,
                        onVisibilityChange: setFolderHeaderOffscreen)
                }
            }
            .modifier(FolderCardBackground(
                isDropTarget: dropTargetFolderId == group.folderId,
                isExpanded: !group.isCollapsed && !group.ids.isEmpty))
            // Outer insets float the rounded card inside the list width —
            // the inset frame is what separates it from the full-bleed
            // session rows at a glance. (The glass/pre-26 card is what the
            // pinned header shows while its members scroll under; rows
            // passing through the 12pt gutters is the standard floating-
            // header look, not the ghosting the old opaque background fixed.)
            // 6pt matches the splitList selection highlight's horizontal
            // inset, so the folder container and a selected member row share
            // the same left/right edges instead of the container overhanging.
            .padding(.horizontal, 6)
            .padding(.top, 4)
            // Expanded: no bottom gap — the card welds onto the first member
            // row's container segment.
            .padding(.bottom, (group.isCollapsed || group.ids.isEmpty) ? 4 : 0)
        .onTapGesture {
            if let fid = group.folderId { toggleFolderCollapsed(fid, scrollProxy: scrollProxy) }
        }
        .accessibilityAddTraits(.isButton)
        .accessibilityAction {
            if let fid = group.folderId { toggleFolderCollapsed(fid, scrollProxy: scrollProxy) }
        }
        // [T-ios-folder-accordion-scroll-anchor] Record this header's on-screen
        // position so the accordion can decide whether a scroll correction is
        // needed. Unlike FolderHeaderVisibilityProbe (mini-bar, expanded folders
        // only) this tracks EVERY header including collapsed ones — the folder
        // about to be expanded is collapsed at the moment of the tap, so its
        // pre-toggle position is exactly what has to be known.
        //
        // A background GeometryReader: it reads the row's own frame without
        // participating in its layout, so no sizing behaviour changes.
        .background {
            if let fid = group.folderId {
                GeometryReader { geo in
                    Color.clear
                        .onAppear { folderHeaderTopY[fid] = geo.frame(in: .global).minY }
                        .onChange(of: geo.frame(in: .global).minY) { folderHeaderTopY[fid] = $0 }
                }
            }
        }
        // ScrollViewReader anchor for the mini-bar's "back to header" jump.
        .id("folderHeader-\(group.folderId ?? "")")
        .listRowInsets(EdgeInsets())
        .dropDestination(for: String.self) { sessionIds, _ in
            guard let fid = group.folderId else { return false }
            Task { @MainActor in
                await ChatStore.shared.setFolder(fid, forSessions: sessionIds)
                refreshSessionList()
            }
            return true
        } isTargeted: { over in
            if over {
                dropTargetFolderId = group.folderId
            } else if dropTargetFolderId == group.folderId {
                dropTargetFolderId = nil
            }
        }
        .contextMenu {
            if let fid = group.folderId, let folder = folders.first(where: { $0.id == fid }) {
                Button {
                    Task { @MainActor in
                        await ChatStore.shared.toggleFolderPin(fid)
                        refreshSessionList()
                    }
                } label: {
                    Label(LocalizedStringKey(folder.isPinned ? "Unpin" : "Pin"),
                          systemImage: folder.isPinned ? "pin.slash" : "pin")
                }
                Button {
                    renameFolderText = folder.name
                    // [T-folder-rename-desc-wipe] Seed the description too.
                    // Both fields are shared @State that outlive the dialog, and
                    // `onRename` ALWAYS passes the desc field through (empty
                    // clears the stored value, by design). Leaving this unseeded
                    // meant the field opened blank — or holding whatever was
                    // typed for a previously renamed folder — so a rename that
                    // only touched the NAME silently wiped that folder's
                    // description. Repeat across folders and every description
                    // disappears, which reads as "renaming one group overwrote
                    // them all".
                    renameFolderDesc = folder.desc ?? ""
                    folderToRename = folder
                } label: {
                    Label("Rename Group", systemImage: "square.and.pencil")
                }
                Button {
                    newChatInFolder(fid)
                } label: {
                    Label("New Chat in Group", systemImage: "plus.bubble")
                }
                Divider()
                // Dissolve is deliberately NOT destructive-tinted: it touches
                // no user data (sessions move back to the main list). Tinting
                // it red would train the eye to read it as the deleting item.
                Button {
                    folderToDissolve = folder
                } label: {
                    Label("Dissolve Group", systemImage: "folder.badge.minus")
                }
                Divider()
                // The one destructive item, last, with the count in the title
                // so the consequence is visible in the menu itself, not only
                // in the confirmation sheet.
                Button(role: .destructive) {
                    requestDeleteFolderWithSessions(folder)
                } label: {
                    Label("Delete Group & \(group.totalCount) Sessions", systemImage: "trash")
                }
            }
        }
    }

    /// "New chat in folder": file the just-promoted draft. Separate from the
    /// draft-bookkeeping onReceive (which is gated on isWideLayout) because
    /// this must run on iPhone too. The notification can arrive off-main
    /// (ChatStore actor executor) — all state access happens inside the
    /// MainActor hop. Extracted to a method so the body's modifier chain
    /// stays type-checkable.
    private func handleSessionCreatedForPendingFolder(_ note: Notification) {
        guard let newId = note.object as? String else { return }
        let noteDraftId = (note.userInfo as? [String: String])?["draftId"]
        Task { @MainActor in
            guard let pending = pendingFolderDraft else { return }
            guard noteDraftId == pending.draftId else {
                // Some other draft was promoted — ours was abandoned. Drop the
                // intent so it can never mis-file a later unrelated chat.
                if noteDraftId != nil { pendingFolderDraft = nil }
                return
            }
            pendingFolderDraft = nil
            _ = await ChatStore.shared.setFolderIfUnfiled(pending.folderId, forSession: newId)
            refreshSessionList()
        }
    }

    /// Folder-header menu: start a new chat that lands inside the folder.
    /// The folder assignment is deferred to draft promotion (see
    /// pendingFolderDraft); the folder is auto-expanded so the new session
    /// doesn't appear to vanish into a collapsed section.
    private func newChatInFolder(_ folderId: String) {
        if collapsedFolderIds.contains(folderId) { toggleFolderCollapsed(folderId) }
        let draftId = Self.makeNewSessionId()
        pendingFolderDraft = (draftId: draftId, folderId: folderId)
        openSession(draftId)
    }

    /// Folder-header menu: delete the folder AND every member session. Reuses
    /// the multi-select delete chain (computeDeleteInfo → DeleteConfirmSheet →
    /// deleteSelectedSessions) rather than a bespoke path — the delete chain
    /// owns session files, tombstones and sync, and re-implementing it would
    /// mean re-earning its correctness.
    private func requestDeleteFolderWithSessions(_ folder: ChatFolder) {
        let memberIds = Set(sessions.filter { $0.folderId == folder.id }.map(\.id))
        selectedIds = memberIds
        pendingDeleteFolderId = folder.id
        deleteInfo = nil
        isComputingDelete = true
        showDeleteConfirm = true
        let totalSessions = sessions.count
        Task { @MainActor in
            let info = await Task.detached {
                Self.computeDeleteInfo(for: memberIds, totalSessions: totalSessions)
            }.value
            deleteInfo = info
            isComputingDelete = false
        }
    }

    // MARK: - Selection Toolbar

    private var selectionToolbar: some View {
        HStack(spacing: 0) {
            Menu {
                Button {
                    exportSessions(ids: selectedIds, format: .json)
                } label: {
                    Label("JSON", systemImage: "doc.text")
                }
                Button {
                    exportSessions(ids: selectedIds, format: .plainText)
                } label: {
                    Label("Plain Text", systemImage: "text.alignleft")
                }
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 20))
                    Text("Export")
                        .font(.caption2)
                }
                .frame(maxWidth: .infinity)
            }
            .disabled(selectedIds.isEmpty)

            Button {
                let anyFiled = sessions.contains { selectedIds.contains($0.id) && $0.isFiled }
                folderPickerRequest = FolderPickerRequest(
                    sessionIds: selectedIds, fromMultiSelect: true, anyFiled: anyFiled)
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: "folder")
                        .font(.system(size: 20))
                    Text("Move")
                        .font(.caption2)
                }
                .frame(maxWidth: .infinity)
            }
            .disabled(selectedIds.isEmpty)

            // Force Sync is gated to iOS 17+ — CloudKit features (queryable
            // record fields, fetchRecordZoneChanges async APIs) used by the
            // v2 sync engine require iOS 17. iOS 16 users see the action bar
            // without this button rather than seeing it hit a no-op.
            // Same gate as the single-session menu — drop the action bar
            // entry entirely when iCloud sync is off.
            if #available(iOS 17.0, *), iCloudSyncEnabled {
                Button {
                    runForceSyncOnSelection()
                } label: {
                    VStack(spacing: 4) {
                        Image(systemName: forceSyncInFlight ? "arrow.triangle.2.circlepath" : "icloud.and.arrow.up")
                            .font(.system(size: 20))
                        Text("Force Sync")
                            .font(.caption2)
                    }
                    .frame(maxWidth: .infinity)
                }
                .disabled(selectedIds.isEmpty || forceSyncInFlight)
            }

            Button(role: .destructive) {
                deleteInfo = nil
                isComputingDelete = true
                showDeleteConfirm = true
                let ids = selectedIds
                let totalSessions = sessions.count
                Task { @MainActor in
                    let info = await Task.detached {
                        Self.computeDeleteInfo(for: ids, totalSessions: totalSessions)
                    }.value
                    deleteInfo = info
                    isComputingDelete = false
                }
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: "trash")
                        .font(.system(size: 20))
                    Text("Delete")
                        .font(.caption2)
                }
                .frame(maxWidth: .infinity)
                .foregroundStyle(selectedIds.isEmpty ? Color.gray : Color.red)
            }
            .disabled(selectedIds.isEmpty)
        }
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    /// Force-sync the given sessions: bumps each session, its messages,
    /// compact markers, and SessionFiles into the v2 dirty queue, and
    /// tells SyncCore to push immediately. Shows a transient
    /// confirmation toast with the total record count and auto-
    /// dismisses selection mode if engaged.
    ///
    /// Pass `singleSession` for the row-level swipe-action / context-
    /// menu path; falls back to `selectedIds` for the multi-select
    /// toolbar.
    private func runForceSyncOnSelection(singleSession: String? = nil) {
        let ids: Set<String> = singleSession.map { Set([$0]) } ?? selectedIds
        guard !ids.isEmpty, !forceSyncInFlight else { return }
        forceSyncInFlight = true
        Task {
            var totalMarked = 0
            for sid in ids {
                let n = await ChatStore.shared.forceSyncSession(sid)
                totalMarked += n
            }
            // Kick the v2 transport so the freshly-marked rows leave
            // local SQLite in this minute rather than waiting for the
            // ambient debounce.
            if #available(iOS 17.0, *) {
                await MainActor.run { SyncCore.shared.scheduleSend(delay: 0.5) }
            }
            await MainActor.run {
                let sessionCount = ids.count
                forceSyncToast = AppLocalized("Marked \(sessionCount) sessions (\(totalMarked) records) for sync. iCloud is syncing now.")
                forceSyncInFlight = false
                if singleSession == nil {
                    // Multi-select path also exits selection mode for the user.
                    isSelecting = false
                    selectedIds.removeAll()
                }
            }
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            await MainActor.run {
                if forceSyncToast != nil { forceSyncToast = nil }
            }
        }
    }

    /// Force Pull a single session from iCloud: queries cloud for the
    /// session's MessageV2 / CompactMarkerV2 / SessionV2 records, and
    /// only commits the local swap if cloud returned a non-empty set.
    /// Reuses the toast banner from runForceSyncOnSelection.
    private func runForcePullOnSession(_ sessionId: String) {
        guard !forceSyncInFlight else { return }
        forceSyncInFlight = true
        forceSyncToast = AppLocalized("Pulling from iCloud…")
        // [T-ios-sessionrow-destroy-crash] Isolate the whole Task to @MainActor.
        // This is the site 6687cf1a (T-ios-state-publish-offmain-crash) MISSED:
        // a bare `Task {}` started from this non-isolated func inherits a
        // background cooperative thread, so the `sessions =` @State write after
        // the `forcePullSession` actor-hop await lands off-main → it concurrently
        // mutates the [ChatSession] AttributeGraph state while the main thread is
        // mid-transaction destroying the old SessionRow value graph (the crash:
        // BodyAccessor.setBody → assignWithCopy for Button → release →
        // `outlined destroy of SessionRow` → freed ChatSession backing). Forcing
        // the Task onto MainActor makes the @State write main-thread, matching
        // every other session-write site fixed in 6687cf1a.
        Task { @MainActor in
            let outcome = await ChatStore.shared.forcePullSession(sessionId: sessionId)
            switch outcome {
            case .applied(let pulled, let deleted):
                forceSyncToast = AppLocalized("Pulled \(pulled) records · removed \(deleted) local")
            case .cloudEmpty:
                forceSyncToast = AppLocalized("iCloud has no records for this chat — local messages preserved")
            case .failed(let msg):
                forceSyncToast = AppLocalized("Force Pull failed: \(msg) — local messages preserved")
            }
            forceSyncInFlight = false
            // Refresh sessions so any title/updatedAt that came down from
            // the SessionV2 record reflects in the list.
            refreshSessionList()
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if forceSyncToast != nil { forceSyncToast = nil }
        }
    }

    // MARK: - Delete Info

    struct DeleteInfo {
        let sessionCount: Int
        let fileNames: [String]   // first 3 file names
        let totalFileCount: Int
        let totalSize: Int64      // bytes

        var formattedSize: String {
            ByteCountFormatter.string(fromByteCount: totalSize, countStyle: .file)
        }
    }

    private nonisolated static func computeDeleteInfo(for ids: Set<String>, totalSessions: Int) -> DeleteInfo {
        let fm = FileManager.default
        let libBase = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let minisBase = libBase.appendingPathComponent("MinisChat/minis", isDirectory: true)
        let dbPath = libBase.appendingPathComponent("MinisChat/minis.db")

        var totalSize: Int64 = 0
        var allFileNames: [String] = []
        var totalFileCount = 0

        // Estimate DB size contribution (rough: divide DB size by total sessions)
        let totalSessions = max(totalSessions, 1)
        if let dbAttrs = try? fm.attributesOfItem(atPath: dbPath.path),
           let dbSize = dbAttrs[.size] as? Int64 {
            totalSize += dbSize * Int64(ids.count) / Int64(totalSessions)
        }

        for id in ids {
            let sessionDir = minisBase.appendingPathComponent(id, isDirectory: true)
            if let enumerator = fm.enumerator(at: sessionDir, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]) {
                for case let fileURL as URL in enumerator {
                    let vals = try? fileURL.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                    if vals?.isRegularFile == true {
                        totalSize += Int64(vals?.fileSize ?? 0)
                        totalFileCount += 1
                        if allFileNames.count < 3 {
                            allFileNames.append(fileURL.lastPathComponent)
                        }
                    }
                }
            }
        }

        return DeleteInfo(
            sessionCount: ids.count,
            fileNames: allFileNames,
            totalFileCount: totalFileCount,
            totalSize: totalSize
        )
    }

    // MARK: - Actions

    private func deleteSession(_ session: ChatSession) {
        // "Currently displayed" must use the same matching as the sidebar
        // highlight (isSessionHighlighted): a session created from a draft this
        // run keeps selectedSessionId at the DRAFT id while the sidebar row
        // carries the real id (newSessionRealId). Comparing only
        // selectedSessionId == session.id missed that case, so deleting the
        // conversation being viewed left the detail pane showing dead content
        // (user report: iPad/macOS split view). Clearing the selection resets
        // the detail pane to the empty state; onChange(selectedSessionId)
        // clears the draft tracking ids.
        if isSessionHighlighted(session.id) {
            selectedSessionId = nil
        }
        ViewModelCache.shared.remove(sessionId: session.id)
        BrowserUseOffloadBridge.releasePool(forSession: session.id)
        withAnimation {
            sessions.removeAll { $0.id == session.id }
        }
        Task {
            await ChatStore.shared.deleteSession(session.id)
            deleteSessionFiles(session.id)
        }
    }

    private func deleteSelectedSessions() {
        let ids = selectedIds
        // Same draft-id-aware matching as deleteSession: clear the selection
        // when ANY deleted id is the one currently displayed (including a
        // draft-created session whose selection id differs from its row id).
        if ids.contains(where: { isSessionHighlighted($0) }) {
            selectedSessionId = nil
        }
        for id in ids {
            ViewModelCache.shared.remove(sessionId: id)
            BrowserUseOffloadBridge.releasePool(forSession: id)
        }
        withAnimation {
            sessions.removeAll { ids.contains($0.id) }
        }
        // Clear deleteInfo to signal onDismiss that deletion occurred;
        // isSelecting and selectedIds are reset in sheet's onDismiss to avoid animation conflicts.
        deleteInfo = nil
        let folderToDrop = pendingDeleteFolderId
        pendingDeleteFolderId = nil
        Task {
            for id in ids {
                await ChatStore.shared.deleteSession(id)
                deleteSessionFiles(id)
            }
            // Delete-all-in-folder: the folder is empty now; dissolving it
            // just drops the row (and pushes the FolderV2 tombstone).
            if let fid = folderToDrop {
                _ = await ChatStore.shared.dissolveFolder(fid)
                await MainActor.run { refreshSessionList() }
            }
        }
    }

    /// Remove persistent minis files for a session (Library/MinisChat/minis/<sessionId>/).
    private func deleteSessionFiles(_ sessionId: String) {
        let fm = FileManager.default
        let base = fm.urls(for: .libraryDirectory, in: .userDomainMask).first!
            .appendingPathComponent("MinisChat/minis", isDirectory: true)
            .appendingPathComponent(sessionId, isDirectory: true)
        try? fm.removeItem(at: base)
        BrowserTabPool.deletePersistedData(for: sessionId)
    }

    private func regenerateTitle(for session: ChatSession) {
        regenerateTitle(sessionId: session.id)
    }

    private func regenerateTitle(sessionId: String) {
        guard regeneratingTitleSessionId == nil else { return }
        regeneratingTitleSessionId = sessionId

        // [T-ios-state-publish-offmain-crash] @MainActor for the @State writes
        Task { @MainActor in
            defer { regeneratingTitleSessionId = nil }
            do {
                try await AIChatViewModel.regenerateSessionTitle(sessionId: sessionId)
                refreshSessionList()
            } catch {
                AppLogger(category: "RegenerateTitle").error(
                    "session=\(sessionId) failed: \(error.localizedDescription) — \(String(describing: error))"
                )
            }
        }
    }

    enum ExportFormat { case json, plainText }

    private func exportSessions(ids: Set<String>, format: ExportFormat) {
        let targetSessions = sessions.filter { ids.contains($0.id) }
        guard !targetSessions.isEmpty else { return }

        isExporting = true
        exportProgress = (0, 0)

        let isMulti = targetSessions.count > 1

        Task.detached(priority: .userInitiated) {
            let ext: String
            switch format {
            case .json: ext = "json"
            case .plainText: ext = "txt"
            }

            // Per-export workspace under tmp
            let tmpRoot = FileManager.default.temporaryDirectory
                .appendingPathComponent("minis-export-\(UUID().uuidString)", isDirectory: true)
            let workDir = tmpRoot.appendingPathComponent("payload", isDirectory: true)
            do {
                try FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
            } catch {
                await MainActor.run { isExporting = false; exportProgress = nil }
                return
            }

            let baseName: String
            if ids.count == 1, let title = targetSessions.first?.title {
                baseName = title.prefix(60)
                    .replacingOccurrences(of: "/", with: "-")
                    .replacingOccurrences(of: ":", with: "-")
            } else {
                baseName = "minis-sessions-\(ids.count)"
            }
            let payloadURL = workDir.appendingPathComponent("\(baseName).\(ext)")

            // No cheap per-session count available; progress total grows as
            // each session loads (handled inside the stream functions).
            await MainActor.run { exportProgress = (0, 0) }

            let summary: ExportSummary
            do {
                switch format {
                case .json:
                    summary = try await Self.streamExportAsJSON(
                        targetSessions, to: payloadURL,
                        progress: { done, total in
                            Task { @MainActor in exportProgress = (done, total) }
                        }
                    )
                case .plainText:
                    summary = try await Self.streamExportAsPlainText(
                        targetSessions, to: payloadURL,
                        progress: { done, total in
                            Task { @MainActor in exportProgress = (done, total) }
                        }
                    )
                }
            } catch {
                try? FileManager.default.removeItem(at: tmpRoot)
                await MainActor.run { isExporting = false; exportProgress = nil }
                return
            }

            // Zip the workDir (single file inside, but zip wrapping keeps spec contract
            // and shrinks large text payloads significantly).
            let zipURL = tmpRoot.appendingPathComponent("\(baseName).zip")
            let finalURL: URL
            do {
                try await Self.createZip(from: workDir, to: zipURL)
                finalURL = zipURL
            } catch {
                // Fall back to raw payload if zipping failed.
                finalURL = payloadURL
            }

            // Compute summary metadata
            var summaryWithSize = summary
            summaryWithSize.format = ext.uppercased()
            summaryWithSize.sessionCount = targetSessions.count
            if let attrs = try? FileManager.default.attributesOfItem(atPath: finalURL.path),
               let size = attrs[.size] as? Int64 {
                summaryWithSize.fileSizeBytes = size
            }

            // Delay so context menu dismiss animation finishes
            try? await Task.sleep(nanoseconds: 600_000_000)

            await MainActor.run {
                isExporting = false
                exportProgress = nil
                exportFileURL = finalURL
                // [T-export-preview-blank] Preview the raw payload, not the zip.
                // `finalURL` is normally the .zip (that is what gets shared/saved);
                // decoding it as UTF-8 always fails, which is why the preview pane
                // was blank. The uncompressed payload is still sitting in workDir —
                // createZip copies out of it and nothing deletes it on the success
                // path — so hand that to the preview. When zipping fell back,
                // finalURL IS payloadURL and the two are simply the same file.
                exportPreviewURL = payloadURL
                exportSummary = isMulti ? summaryWithSize : nil
                showExportPreview = true
            }
        }
    }

    /// Wrap a directory into a zip via NSFileCoordinator(.forUploading).
    private static func createZip(from sourceDir: URL, to zipURL: URL) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let coordinator = NSFileCoordinator()
            var nsError: NSError?
            coordinator.coordinate(readingItemAt: sourceDir,
                                   options: .forUploading,
                                   error: &nsError) { zippedURL in
                do {
                    if FileManager.default.fileExists(atPath: zipURL.path) {
                        try FileManager.default.removeItem(at: zipURL)
                    }
                    try FileManager.default.copyItem(at: zippedURL, to: zipURL)
                    cont.resume()
                } catch {
                    cont.resume(throwing: error)
                }
            }
            if let nsError { cont.resume(throwing: nsError) }
        }
    }

    /// Stream JSON export to disk in batches of 50 messages — avoids loading
    /// the full payload into memory. Returns aggregate summary stats.
    private static func streamExportAsJSON(
        _ targetSessions: [ChatSession],
        to fileURL: URL,
        progress: @escaping (Int, Int) -> Void
    ) async throws -> ExportSummary {
        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        guard let handle = FileHandle(forWritingAtPath: fileURL.path) else {
            throw NSError(domain: "ExportStream", code: 1)
        }
        defer { try? handle.close() }

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let isoFmt = ISO8601DateFormatter()

        var summary = ExportSummary()
        var done = 0

        try handle.write(contentsOf: Data("[\n".utf8))
        var firstSession = true

        for session in targetSessions {
            let allMessages = await ChatStore.shared.loadMessages(sessionId: session.id)
            guard let firstUserIdx = allMessages.firstIndex(where: { $0.role == .user }) else { continue }
            let messages = Array(allMessages[firstUserIdx...])

            // Total is best-effort: at minimum we know what we've processed plus
            // this session's pending messages.
            let total = done + messages.count
            progress(done, total)

            // Per-session header
            if !firstSession { try handle.write(contentsOf: Data(",\n".utf8)) }
            firstSession = false

            var sessionHeader: [String: Any] = [
                "id": session.id,
                "modelId": session.modelId,
                "createdAt": isoFmt.string(from: session.createdAt),
                "updatedAt": isoFmt.string(from: session.updatedAt)
            ]
            if let title = session.title { sessionHeader["title"] = title }
            if let category = session.category { sessionHeader["category"] = category }

            // Write the session object opening, then stream messages array.
            let headerData = try JSONSerialization.data(withJSONObject: sessionHeader, options: [.prettyPrinted, .sortedKeys])
            // Strip the trailing "}" so we can append "messages": [...].
            guard var headerStr = String(data: headerData, encoding: .utf8) else { continue }
            if headerStr.hasSuffix("}") { headerStr.removeLast() }
            try handle.write(contentsOf: Data(headerStr.utf8))
            try handle.write(contentsOf: Data(",\n  \"messages\": [\n".utf8))

            var firstMsg = true
            var batchBuffer: [[String: Any]] = []
            batchBuffer.reserveCapacity(50)

            func flushBatch() throws {
                guard !batchBuffer.isEmpty else { return }
                for dict in batchBuffer {
                    let d = try JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys])
                    if !firstMsg { try handle.write(contentsOf: Data(",\n".utf8)) }
                    firstMsg = false
                    try handle.write(contentsOf: d)
                }
                batchBuffer.removeAll(keepingCapacity: true)
            }

            for msg in messages {
                done += 1
                if msg.isToolResultOnly { continue }

                var cleanParts: [[String: Any]] = []
                for part in msg.parts {
                    switch part {
                    case .text(let text):
                        cleanParts.append(["type": "text", "text": text])
                    case .toolUse(let tu):
                        var toolDict: [String: Any] = ["type": "tool_use", "name": tu.name]
                        if let desc = tu.description { toolDict["description"] = desc }
                        if !tu.input.isEmpty { toolDict["input"] = tu.input }
                        cleanParts.append(toolDict)
                    case .toolResult(let tr):
                        var resultDict: [String: Any] = [
                            "type": "tool_result",
                            "tool": tr.toolUseId,
                            "success": tr.success
                        ]
                        if !tr.output.isEmpty {
                            let maxLen = 2000
                            if tr.output.count > maxLen {
                                resultDict["output"] = String(tr.output.prefix(maxLen)) + "\n…[truncated]"
                            } else {
                                resultDict["output"] = tr.output
                            }
                        }
                        if let snap = tr.snapshot {
                            if let text = snap.text { resultDict["snapshot"] = text }
                            if let dur = snap.duration { resultDict["duration"] = dur }
                        }
                        cleanParts.append(resultDict)
                    case .mediaRef:
                        cleanParts.append(["type": "media", "note": "image/file attachment"])
                        summary.attachmentCount += 1
                    }
                }

                guard !cleanParts.isEmpty else { continue }

                var dict: [String: Any] = [
                    "role": msg.role.rawValue,
                    "parts": cleanParts,
                    "createdAt": isoFmt.string(from: msg.createdAt)
                ]
                if let usage = msg.tokenUsage,
                   let usageData = try? encoder.encode(usage),
                   let usageJSON = try? JSONSerialization.jsonObject(with: usageData) {
                    dict["tokenUsage"] = usageJSON
                }
                if let reasoning = msg.reasoningContent, !reasoning.isEmpty {
                    dict["reasoning"] = reasoning
                }
                batchBuffer.append(dict)

                summary.totalMessages += 1
                if summary.earliest == nil || msg.createdAt < summary.earliest! {
                    summary.earliest = msg.createdAt
                }
                if summary.latest == nil || msg.createdAt > summary.latest! {
                    summary.latest = msg.createdAt
                }

                if batchBuffer.count >= 50 {
                    try flushBatch()
                    progress(done, total)
                }
            }
            try flushBatch()

            try handle.write(contentsOf: Data("\n  ]\n}".utf8))
            progress(done, total)
        }

        try handle.write(contentsOf: Data("\n]\n".utf8))
        return summary
    }

    /// Stream plain-text export to disk — writes each message immediately
    /// so memory stays bounded regardless of session size.
    private static func streamExportAsPlainText(
        _ targetSessions: [ChatSession],
        to fileURL: URL,
        progress: @escaping (Int, Int) -> Void
    ) async throws -> ExportSummary {
        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        guard let handle = FileHandle(forWritingAtPath: fileURL.path) else {
            throw NSError(domain: "ExportStream", code: 2)
        }
        defer { try? handle.close() }

        let dateFmt = DateFormatter()
        dateFmt.dateStyle = .medium
        dateFmt.timeStyle = .short
        let timeFmt = DateFormatter()
        timeFmt.dateFormat = "HH:mm"

        var summary = ExportSummary()
        var done = 0

        for (i, session) in targetSessions.enumerated() {
            if i > 0 {
                try handle.write(contentsOf: Data(("\n\n" + String(repeating: "=", count: 60) + "\n\n").utf8))
            }
            var header = "# \(session.title ?? "Untitled")\n"
            header += "Model: \(session.modelId)\n"
            header += "Created: \(dateFmt.string(from: session.createdAt))\n"
            header += String(repeating: "-", count: 40) + "\n"
            try handle.write(contentsOf: Data(header.utf8))

            let allMessages = await ChatStore.shared.loadMessages(sessionId: session.id)
            guard let firstUserIdx = allMessages.firstIndex(where: { $0.role == .user }) else { continue }
            let messages = Array(allMessages[firstUserIdx...])
            let total = done + messages.count
            progress(done, total)

            var batchWritten = 0
            for msg in messages {
                done += 1
                if msg.isToolResultOnly { continue }

                let role = msg.role == .user ? "User" : "Minis"
                let time = timeFmt.string(from: msg.createdAt)
                var parts: [String] = []
                for part in msg.parts {
                    switch part {
                    case .text(let text):
                        if !text.isEmpty { parts.append(text) }
                    case .toolUse(let tu):
                        parts.append("[Tool: \(tu.description ?? tu.name)]")
                    case .toolResult(let tr):
                        if let snap = tr.snapshot, let text = snap.text, !text.isEmpty {
                            let preview = text.count > 200 ? String(text.prefix(200)) + "…" : text
                            parts.append("[Result: \(preview)]")
                        } else {
                            parts.append("[Result: \(tr.success ? "ok" : "failed")]")
                        }
                    case .mediaRef:
                        parts.append("[Attachment]")
                        summary.attachmentCount += 1
                    }
                }
                let body = parts.joined(separator: "\n")
                if !body.isEmpty {
                    let chunk = "\n[\(role)] \(time)\n\(body)\n---\n"
                    try handle.write(contentsOf: Data(chunk.utf8))
                    summary.totalMessages += 1
                    if summary.earliest == nil || msg.createdAt < summary.earliest! {
                        summary.earliest = msg.createdAt
                    }
                    if summary.latest == nil || msg.createdAt > summary.latest! {
                        summary.latest = msg.createdAt
                    }
                }
                batchWritten += 1
                if batchWritten >= 50 {
                    batchWritten = 0
                    progress(done, total)
                }
            }
            progress(done, total)
        }
        return summary
    }

}

// MARK: - Export Summary

struct ExportSummary {
    var format: String = ""
    var sessionCount: Int = 0
    var totalMessages: Int = 0
    var attachmentCount: Int = 0
    var earliest: Date? = nil
    var latest: Date? = nil
    var fileSizeBytes: Int64 = 0
}

// MARK: - Delete Confirm Sheet

private struct DeleteConfirmSheet: View {
    @Binding var info: ContentView.DeleteInfo?
    let isLoading: Bool
    let onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if isLoading || info == nil {
                    Spacer()
                    ProgressView("Calculating…")
                        .controlSize(.large)
                    Spacer()
                    let _ = print("[DELETE] DeleteConfirmSheet showing ProgressView. isLoading=\(isLoading), info is \(info == nil ? "nil" : "non-nil")")
                } else if let info {
                    let _ = print("[DELETE] DeleteConfirmSheet showing info. sessionCount=\(info.sessionCount)")
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            // Sessions
                            infoRow(
                                title: "Sessions",
                                value: "\(info.sessionCount) session\(info.sessionCount == 1 ? "" : "s") and all messages"
                            )

                            // Files
                            if info.totalFileCount > 0 {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Associated Files")
                                        .font(.subheadline.bold())
                                        .foregroundStyle(.primary)
                                    ForEach(info.fileNames, id: \.self) { name in
                                        HStack(spacing: 6) {
                                            Image(systemName: "doc")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                            Text(name)
                                                .font(.subheadline)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    if info.totalFileCount > info.fileNames.count {
                                        Text("and \(info.totalFileCount - info.fileNames.count) more file\(info.totalFileCount - info.fileNames.count == 1 ? "" : "s")")
                                            .font(.caption)
                                            .foregroundStyle(.tertiary)
                                    }
                                }
                            }

                            // Storage
                            infoRow(title: "Releases Storage", value: info.formattedSize)
                        }
                        .padding(20)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Divider()

                    // Buttons
                    VStack(spacing: 10) {
                        Button(role: .destructive) {
                            onDelete()
                            dismiss()
                        } label: {
                            Text("Delete (\(info.formattedSize))")
                                .font(.body.bold())
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.red)

                        Button {
                            dismiss()
                        } label: {
                            Text("Cancel")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                }
            }
            .navigationTitle("Confirm Deletion")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func infoRow(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.subheadline.bold())
                .foregroundStyle(.primary)
            Text(value)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Export Preview Sheet

private struct ExportPreviewSheet: View {
    /// The artifact the user shares / saves — normally a `.zip`.
    let fileURL: URL?
    /// [T-export-preview-blank] The text payload to RENDER. Distinct from
    /// `fileURL` because the shared artifact is zipped, and zip bytes never
    /// decode as UTF-8 — reading `fileURL` left the pane blank while the file
    /// size (read from filesystem attributes, not the content) still showed,
    /// which is exactly how the bug presented. Falls back to `fileURL` when the
    /// caller has nothing separate to offer.
    var previewURL: URL? = nil
    let summary: ExportSummary?

    /// File to read for the on-screen text.
    private var textSourceURL: URL? { previewURL ?? fileURL }
    @Environment(\.dismiss) private var dismiss
    @State private var showShareSheet = false
    @State private var showFilePicker = false
    @State private var copied = false
    @State private var previewText: String = ""
    @State private var fileSize: String = ""
    @State private var isLoadingPreview = true

    private let previewLimit = 10000

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Preview — summary for multi-select, full content for single.
                if let summary {
                    summaryView(summary)
                } else if isLoadingPreview {
                    Spacer()
                    ProgressView()
                    Spacer()
                } else {
                    ScrollView {
                        Text(previewText)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color(UIColor.label))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .textSelection(.enabled)
                    }
                    .background(Color(UIColor.secondarySystemBackground))
                }

                Divider()

                // File size info
                if !fileSize.isEmpty {
                    Text(fileSize)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .padding(.top, 6)
                }

                // Action buttons
                HStack(spacing: 0) {
                    // [T-export-preview-blank] Copy needs a TEXT source. It used to
                    // test `fileURL`, which is the zip, so Copy silently vanished
                    // from every single-session export — the same zip-vs-payload
                    // confusion that blanked the preview. Test the payload we
                    // actually read instead, so Copy is offered exactly when there
                    // is something readable to copy.
                    if summary == nil, textSourceURL?.pathExtension.lowercased() != "zip" {
                        actionButton(icon: "doc.on.doc", label: copied ? AppLocalized("Copied") : AppLocalized("Copy")) {
                            if let url = textSourceURL, let content = try? String(contentsOf: url, encoding: .utf8) {
                                UIPasteboard.general.string = content
                            }
                            copied = true
                            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                        }
                    }
                    actionButton(icon: "square.and.arrow.up", label: AppLocalized("Share")) {
                        showShareSheet = true
                    }
                    actionButton(icon: "folder", label: AppLocalized("Save to Files")) {
                        showFilePicker = true
                    }
                }
                .padding(.vertical, 12)
                .background(Color(UIColor.systemBackground))
            }
            .navigationTitle(AppLocalized("Export Preview"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(AppLocalized("Done")) { dismiss() }
                }
            }
            .sheet(isPresented: $showShareSheet) {
                if let url = fileURL {
                    ShareSheet(url: url)
                }
            }
            .sheet(isPresented: $showFilePicker) {
                if let url = fileURL {
                    DocumentExportPicker(url: url)
                }
            }
            .task {
                if summary == nil {
                    await loadPreview()
                } else {
                    // Still compute file size for the action bar.
                    if let url = fileURL,
                       let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
                       let size = attrs[.size] as? Int64 {
                        let formatter = ByteCountFormatter()
                        formatter.countStyle = .file
                        fileSize = formatter.string(fromByteCount: size)
                    }
                    isLoadingPreview = false
                }
            }
        }
    }

    @ViewBuilder
    private func summaryView(_ s: ExportSummary) -> some View {
        let dateFmt: DateFormatter = {
            let f = DateFormatter()
            f.dateStyle = .medium
            f.timeStyle = .short
            return f
        }()
        let sizeStr: String = {
            let f = ByteCountFormatter()
            f.countStyle = .file
            return f.string(fromByteCount: s.fileSizeBytes)
        }()
        let timeRange: String = {
            guard let e = s.earliest, let l = s.latest else { return "—" }
            return "\(dateFmt.string(from: e)) ~ \(dateFmt.string(from: l))"
        }()

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                summaryRow(AppLocalized("Format"), s.format)
                summaryRow(AppLocalized("Sessions"), "\(s.sessionCount)")
                summaryRow(AppLocalized("Messages"), "\(s.totalMessages)")
                summaryRow(AppLocalized("Time range"), timeRange)
                summaryRow(AppLocalized("Attachments"), "\(s.attachmentCount)")
                summaryRow(AppLocalized("Estimated size"), sizeStr)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Color(UIColor.secondarySystemBackground))
    }

    private func summaryRow(_ key: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(key)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .multilineTextAlignment(.trailing)
        }
    }

    private func loadPreview() async {
        guard let url = textSourceURL else {
            isLoadingPreview = false
            return
        }

        // Load file size — of the SHARED artifact (`fileURL`), not the preview
        // payload. The number under the pane labels the file the user is about to
        // share or save, so it has to be the zip's size even though the text above
        // it comes from the uncompressed payload.
        if let sizeURL = fileURL,
           let attrs = try? FileManager.default.attributesOfItem(atPath: sizeURL.path),
           let size = attrs[.size] as? Int64 {
            let formatter = ByteCountFormatter()
            formatter.countStyle = .file
            fileSize = formatter.string(fromByteCount: size)
        }

        // Read only the preview portion to avoid loading huge files into memory
        await Task.detached(priority: .utility) {
            var preview = ""
            if let handle = FileHandle(forReadingAtPath: url.path) {
                // Read at most previewLimit bytes worth of data
                let data = handle.readData(ofLength: previewLimit * 4) // UTF-8 can be up to 4 bytes per char
                handle.closeFile()
                // [T-export-preview-blank] Decode defensively. `readData` cuts at a
                // byte offset, so on any file larger than the read window the cut
                // can land mid-UTF-8-sequence and strict decoding returns nil —
                // another silent blank page, this time only for big exports.
                // Retry by trimming up to 3 trailing bytes (the longest possible
                // partial sequence), then fall back to a lossy decode so the user
                // sees the content rather than nothing.
                var decoded = String(data: data, encoding: .utf8)
                if decoded == nil, data.count > 3 {
                    for drop in 1...3 {
                        if let t = String(data: data.dropLast(drop), encoding: .utf8) {
                            decoded = t
                            break
                        }
                    }
                }
                let text = decoded ?? String(decoding: data, as: UTF8.self)
                if !text.isEmpty {
                    if text.count > previewLimit {
                        preview = String(text.prefix(previewLimit)) + "\n\n…"
                    } else {
                        preview = text
                    }
                }
            }
            await MainActor.run {
                previewText = preview
                isLoadingPreview = false
            }
        }.value
    }

    private func actionButton(icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                Text(label)
                    .font(.caption2)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

// MARK: - Share Sheet

private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        // [T-share-sheet-uti] Defense against ShareKit's
        // UTTypeGetForIdentifier assert on Mac Catalyst — see
        // MinisShareSheet.sanitizedShareURL for context.
        let safeURL = MinisShareSheet.sanitizedShareURL(url) ?? url
        return UIActivityViewController(activityItems: [safeURL], applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

// MARK: - Document Export Picker

private struct DocumentExportPicker: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [url])
        picker.shouldShowFileExtensions = true
        return picker
    }
    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}
}

// MARK: - Draggable FAB

/// A floating action button that can be dragged horizontally and snaps to the left or right edge.
/// Uses UIKit's UIPanGestureRecognizer via UIViewRepresentable for reliable, low-latency drag tracking
/// that doesn't conflict with SwiftUI's Button/tap gestures.
private struct DraggableFAB<Label: View>: View {
    @Binding var fabOnLeft: Bool
    @Binding var dragOffset: CGFloat
    @Binding var didDrag: Bool
    /// When true, this FAB sits on the opposite side of `fabOnLeft` and inverts the snap logic.
    var inverted: Bool = false
    var onTap: () -> Void
    @ViewBuilder var label: () -> Label

    private let fabSize: CGFloat = 56
    private let edgePadding: CGFloat = 16

    var body: some View {
        GeometryReader { geo in
            let screenWidth = geo.size.width
            let leftX = edgePadding + fabSize / 2
            let rightX = screenWidth - edgePadding - fabSize / 2
            let onLeft = inverted ? !fabOnLeft : fabOnLeft
            let restingX = onLeft ? leftX : rightX

            label()
                .frame(width: fabSize, height: fabSize)
                .position(x: restingX + dragOffset, y: fabSize / 2)
                .onTapGesture {
                    onTap()
                }
                .simultaneousGesture(
                    DragGesture(minimumDistance: 10)
                        .onChanged { value in
                            didDrag = true
                            dragOffset = value.translation.width
                        }
                        .onEnded { value in
                            let currentCenter = restingX + value.translation.width
                            let droppedOnLeft = currentCenter < screenWidth / 2
                            // For inverted FAB: dropping on left means the *other* FAB goes right
                            let newFabOnLeft = inverted ? !droppedOnLeft : droppedOnLeft
                            let changed = fabOnLeft != newFabOnLeft
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.75)) {
                                fabOnLeft = newFabOnLeft
                                dragOffset = 0
                            }
                            if changed {
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            }
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
                                didDrag = false
                            }
                        }
                )
        }
        .frame(height: fabSize)
    }
}

// MARK: - Equatable-gated context menu

/// [T-ios-sidebar-contextmenu-memory] + [T-ios-crash-contextmenu-uaf]
///
/// Concrete, closure-free replacement for the old generic EquatableMenuContent.
/// The previous design stored a `@ViewBuilder () -> Content` closure that
/// captured ContentView's `self`; Equatable compared only the key, so SwiftUI
/// retained the OLD instance (and its stale closure) when the key was unchanged.
/// During attribute-graph transactions, `assignWithCopy` of the struct
/// retain/released the closure whose captures had already been deallocated →
/// use-after-free → KERN_PROTECTION_FAILURE (appears as CODESIGNING/Invalid
/// Page because the CPU jumped to a freed stack address).
///
/// This replacement stores ONLY value types (MenuKey) plus a stable class
/// reference (SessionMenuActionChannel, backed by @State in ContentView — alive
/// for the view-graph lifetime). `body` builds the menu from key fields +
/// singleton data; actions go through the channel. No escaping closure, no
/// captured `self`, nothing to dangle.
///
/// Memory optimization preserved: `.equatable()` at the call site still skips
/// `body` when the key is unchanged, so the 8+ Button/Menu/Label tree is NOT
/// rebuilt on every sidebar body pass.
private struct SessionContextMenu: View, Equatable {
    let key: MenuKey
    let actions: SessionMenuActionChannel

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.key == rhs.key }

    var body: some View {
        Button {
            actions.send(.togglePin(key.sid))
        } label: {
            Label(LocalizedStringKey(key.pinned ? "Unpin" : "Pin"),
                  systemImage: key.pinned ? "pin.slash" : "pin")
        }
        Menu {
            Button {
                actions.send(.exportJSON(key.sid))
            } label: {
                Label("JSON", systemImage: "doc.text")
            }
            Button {
                actions.send(.exportText(key.sid))
            } label: {
                Label("Plain Text", systemImage: "text.alignleft")
            }
        } label: {
            Label("Export", systemImage: "square.and.arrow.up")
        }
        Button {
            actions.send(.editTitle(key.sid))
        } label: {
            Label("Edit Title & Category", systemImage: "square.and.pencil")
        }
        Button {
            actions.send(.regenerateTitle(key.sid))
        } label: {
            Label("Regenerate Title", systemImage: "arrow.triangle.2.circlepath")
        }
        if BiometricAuth.isAvailable {
            if SessionLockStore.shared.isLocked(key.sid) {
                Button {
                    actions.send(.unlockSession(key.sid))
                } label: {
                    Label("Remove \(BiometricAuth.biometryDisplayName) Lock", systemImage: "lock.open")
                }
            } else if SessionLockStore.shared.globalEnabled {
                Button {
                    actions.send(.lockSession(key.sid))
                } label: {
                    Label(ContentView.lockWithBiometryLabel(), systemImage: "lock.fill")
                }
            }
        }
        Button {
            actions.send(.duplicate(key.sid))
        } label: {
            Label("Duplicate", systemImage: "doc.on.doc")
        }
        // Single Button that opens the shared folder-picker sheet. Deliberately
        // NOT an inline submenu of folder names: this menu body is the
        // [T-ios-contextmenu-localized-mainthread-hang] site, and listing N
        // folders here would multiply every AttributeGraph recompute by N.
        // A static Label keeps the body cost constant, and keeps folder data
        // out of the menu entirely ([T-ios-crash-contextmenu-uaf]).
        Button {
            actions.send(.moveToFolder(key.sid))
        } label: {
            // Wording follows membership: a session already in a group is being
            // MOVED BETWEEN groups, not filed for the first time. Same idiom as
            // Pin/Unpin above — `LocalizedStringKey` so the chosen key is still
            // looked up in Localizable.xcstrings (a plain String would bypass
            // localization entirely).
            Label(LocalizedStringKey(key.filed ? "Change Group" : "Move to Group"),
                  systemImage: key.filed ? "folder.badge.gearshape" : "folder")
        }
        if iCloudSyncVisible {
            Button {
                actions.send(.forceSync(key.sid))
            } label: {
                Label("Force iCloud Sync", systemImage: "icloud.and.arrow.up")
            }
            Button {
                actions.send(.forcePull(key.sid))
            } label: {
                Label("Force Pull Messages", systemImage: "icloud.and.arrow.down")
            }
        }
        Button {
            actions.send(.select(key.sid))
        } label: {
            Label("Select", systemImage: "checkmark.circle")
        }
        Button {
            let title = (key.title ?? "Untitled").prefix(60)
            let subject = "Content Report: \(title)".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            let msgBody = "Session: \(key.sid)\n\nPlease describe the issue:\n".addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            if let url = URL(string: "mailto:dev@openminis.app?subject=\(subject)&body=\(msgBody)") {
                UIApplication.shared.open(url)
            }
        } label: {
            Label("Report Content", systemImage: "exclamationmark.bubble")
        }
        Button(role: .destructive) {
            actions.send(.delete(key.sid))
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    private var iCloudSyncVisible: Bool {
        if #available(iOS 17.0, *) {
            return UserDefaults.standard.bool(forKey: "cloudSync.v2.enabled")
        }
        return false
    }
}

/// Value key for the sidebar session context menu — only these fields change what
/// the menu renders (Pin/Unpin label, title in Report/Delete). A change here is
/// the only reason to rebuild the menu tree.
private struct MenuKey: Equatable {
    let sid: String
    let pinned: Bool
    let title: String?
    /// Whether the session currently belongs to a group. Drives the
    /// "Move to Group" / "Change Group" wording.
    ///
    /// Carried in the KEY rather than read from a store inside `body`: the menu
    /// is `.equatable()` precisely so `body` is skipped while the key is
    /// unchanged, so a value the label depends on must participate in equality
    /// or the wording would go stale after a move. It is also a plain `Bool`,
    /// keeping this a pure value type — see the type comment above about the
    /// use-after-free that closures/reference captures caused here.
    let filed: Bool
}

// MARK: - Session Row

/// category → (SF Symbol, color) for a session's list icon.
///
/// Single source of truth shared by `SessionRow`, `RemoteSessionRow`, and the
/// folder header's composed icon (which stacks the top members' glyphs). It
/// was previously duplicated as two identical private computed properties on
/// the row structs; the composed icon made a third copy untenable.
/// Pure function — safe to call from the `groupedSessions` aggregation pass.
func sessionCategoryIcon(for category: String?) -> (systemName: String, color: Color) {
    switch category {
    case "code":         return ("terminal.fill", .orange)
    case "writing":      return ("doc.text.fill", .blue)
    case "research":     return ("globe.americas.fill", .teal)
    case "analysis":     return ("chart.pie.fill", .indigo)
    case "creative":     return ("paintbrush.pointed.fill", .pink)
    case "chat":         return ("bubble.left.fill", .green)
    case "math":         return ("number.circle.fill", .purple)
    case "translation":  return ("character.bubble", .cyan)
    case "health":       return ("heart.fill", .red)
    case "finance":      return ("banknote.fill", .mint)
    case "travel":       return ("map.fill", .orange)
    case "education":    return ("book.closed.fill", .blue)
    case "design":       return ("paintpalette.fill", .pink)
    case "productivity": return ("calendar.badge.checkmark", .yellow)
    case "support":      return ("gearshape.fill", .brown)
    case "other":        return ("square.grid.2x2.fill", .gray)
    default:             return ("bubble.left.fill", .gray)
    }
}

/// Folder icon composed from the folder's top member glyphs: a rounded-rect
/// "group" container tinted with the first member's category color, holding up
/// to 3 distinct category symbols. Empty folder → plain gray folder glyph.
/// Pure rendering — the glyph selection/dedup happens in groupedSessionIDs.
/// The "grouped list" glyph from the user-provided asset: two rounded
/// square outlines on the left, four list lines on the right. Traced from
/// the 1024-unit SVG; squares are drawn as even-odd rings so the whole
/// glyph is a single fill (no stroke-vs-fill mixing).
struct GroupGlyphShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let u = rect.width / 1024.0

        func ring(x: CGFloat, y: CGFloat) {
            // Outer 325.8×325.8 with r 93; inner inset by the 46.5 stroke.
            let outer = CGRect(x: x * u, y: y * u, width: 325.8 * u, height: 325.8 * u)
            let inner = outer.insetBy(dx: 46.5 * u, dy: 46.5 * u)
            p.addRoundedRect(in: outer, cornerSize: CGSize(width: 93 * u, height: 93 * u), style: .continuous)
            p.addRoundedRect(in: inner, cornerSize: CGSize(width: 46.5 * u, height: 46.5 * u), style: .continuous)
        }
        func line(cy: CGFloat) {
            let r = CGRect(x: 558.5 * u, y: (cy - 23.27) * u, width: 325.8 * u, height: 46.5 * u)
            p.addRoundedRect(in: r, cornerSize: CGSize(width: 23.27 * u, height: 23.27 * u))
        }

        ring(x: 139.6, y: 139.6)
        ring(x: 139.6, y: 511.9)
        line(cy: 209.5)
        line(cy: 395.6)
        line(cy: 581.8)
        line(cy: 768.0)
        return p
    }
}

/// Group icon: the grouped-list glyph on the SAME circular translucent tint
/// the session rows use, at the same 44pt slot — so a group icon and a
/// session icon are the same species at the same size. The tint and glyph
/// color borrow the newest member's category color (gray when empty), which
/// is all that remains of the member-glyph composition: the shape is
/// uniform, the color still says what the group holds.
struct FolderComposedIcon: View {
    let glyphs: [FolderGlyph]
    var diameter: CGFloat = 44

    var body: some View {
        let tint = glyphs.first?.color ?? .gray
        ZStack {
            // 0.28 vs the session icons' 0.18: a deliberately stronger tint
            // so a group circle reads as a different kind of thing at a
            // glance, while keeping the same size and shape language.
            Circle()
                .fill(tint.opacity(0.28))
            GroupGlyphShape()
                .fill(tint, style: FillStyle(eoFill: true))
                .frame(width: diameter * 0.56, height: diameter * 0.56)
        }
        .frame(width: diameter, height: diameter)
    }
}

private struct SessionRow: View, Equatable {
    // [T-ios-session-list-equatable-jank] Custom Equatable so `.equatable()` at
    // the call site gates parent-driven re-evaluation on a CHEAP compare of just
    // the fields this row actually renders — instead of SwiftUI deep-comparing
    // the whole `ChatSession` value (the `let session` input). Post the
    // sessionsByIdCache fix, the HangDetector scroll stack still showed
    // AGGraphSetOutputValue → ChatSession.== recursion: it was THIS row's
    // whole-struct input being compared per transaction × visible rows.
    // Comparing only the rendered fields (all short) collapses that to a handful
    // of cheap, short-circuiting comparisons. The @ObservedObject trackers below
    // still drive in-place re-eval on running/lock/font changes (Equatable only
    // gates the parent-push path, not observed-object invalidation).
    static func == (lhs: SessionRow, rhs: SessionRow) -> Bool {
        lhs.session.id == rhs.session.id
            && lhs.session.title == rhs.session.title
            && lhs.session.lastMessage == rhs.session.lastMessage
            && lhs.session.updatedAt == rhs.session.updatedAt
            && lhs.session.pinnedAt == rhs.session.pinnedAt
            && lhs.session.category == rhs.session.category
            && lhs.session.source == rhs.session.source
            && lhs.session.remoteDeviceId == rhs.session.remoteDeviceId
            && lhs.isHighlighted == rhs.isHighlighted
            && lhs.highlightQuery == rhs.highlightQuery
            && lhs.matchSnippet == rhs.matchSnippet
            && lhs.isActiveOverride == rhs.isActiveOverride
            && lhs.isSuspendedOverride == rhs.isSuspendedOverride
    }

    let session: ChatSession
    var isHighlighted: Bool = false
    var highlightQuery: String? = nil
    /// When set, replaces the generic `lastMessage` preview in the subtitle
    /// slot with this body-extracted snippet (~100 chars around the first
    /// hit). Only populated when the search hit was on message body —
    /// title matches keep the highlighted title in the title slot and fall
    /// back to `lastMessage` below. (T-search-highlight 8edb74f2)
    var matchSnippet: String? = nil
    /// Running / suspended state passed in as VALUES by the sidebar list so a
    /// flip changes this view's value and re-evaluates `body` in place (same
    /// ForEach identity — no cell rebuild / scroll jump). When nil (e.g. the
    /// bare `SessionRow(session:)` call site) we fall back to reading the
    /// observed trackers directly. [T-ios-ipad-sidebar-running-indicator-stale]
    var isActiveOverride: Bool? = nil
    var isSuspendedOverride: Bool? = nil
    @ObservedObject private var activityTracker = SessionActivityTracker.shared
    @ObservedObject private var concurrencyManager = SessionConcurrencyManager.shared
    @ObservedObject private var lockStore = SessionLockStore.shared
    // [T-ios-session-paused-badge] Observe the badge-state queue so a session's
    // "!" paused badge appears/clears in place (same row identity) when the
    // queue changes. The custom Equatable above gates only the parent-push
    // path; @ObservedObject still drives in-place re-eval on store changes.
    @ObservedObject private var badgeStore = SessionBadgeStore.shared
    // Observe App Base font scale so the row re-evaluates when the user moves
    // the slider. The row's fonts use hardcoded `.system(size:)` which ignore
    // Dynamic Type, so we must explicitly multiply by the App Base scale.
    @ObservedObject private var fontSettings = FontSettings.shared

    init(session: ChatSession,
         isHighlighted: Bool = false,
         isActive: Bool? = nil,
         isSuspended: Bool? = nil,
         highlightQuery: String? = nil,
         matchSnippet: String? = nil) {
        self.session = session
        self.isHighlighted = isHighlighted
        self.isActiveOverride = isActive
        self.isSuspendedOverride = isSuspended
        self.highlightQuery = highlightQuery
        self.matchSnippet = matchSnippet
    }

    private var isActive: Bool {
        isActiveOverride ?? activityTracker.isActive(session.id)
    }

    private var isSuspended: Bool {
        isSuspendedOverride ?? concurrencyManager.isSuspended(session.id)
    }

    /// True when the row should render the lock affordance — global
    /// toggle on, biometric supported, this session is in the locked set,
    /// AND the user hasn't unlocked it in the current run. Without the
    /// `isCurrentlyUnlocked` check, an already-unlocked session keeps
    /// rendering its title + last-message line blurred even though the
    /// detail view is freely accessible — the row blur outlived its
    /// purpose for the rest of the session.
    private var isVisuallyLocked: Bool {
        lockStore.isVisuallyLocked(session.id)
    }

    /// The transient badge to render for this row, after suppressing states that
    /// contradict the row's live activity.
    ///
    /// [T-ios-session-running-shows-pause-badge] A session that is actively
    /// running its agent loop (`isActive`, drawn with the SpinningRing) is by
    /// definition NOT paused — yet a transient `canResume=true` flip mid-run
    /// (interrupted-tail detection at load, a Stop case that then resumes, a
    /// background-suspend that's already back in-flight) can leave a stale
    /// `.paused` in the queue. The launch/foreground reconcile already excludes
    /// active sessions; do the same continuously at render so a spinning row can
    /// never also show ⏸. Non-`.paused` queue states are unaffected.
    private var visibleBadge: SessionBadgeState? {
        guard let badge = badgeStore.topCornerBadge(for: session.id) else { return nil }
        if badge == .paused && isActive { return nil }
        return badge
    }

    /// [T-ios-session-unread-badge] Whether to draw the top-trailing red unread
    /// dot — a background task finished and posted its notification, and the user
    /// hasn't opened the session yet. Independent of `visibleBadge` (the
    /// bottom-trailing corner badge), so an unread session can also show ⏸.
    private var showsUnreadDot: Bool {
        guard !isHighlighted else { return false }
        return badgeStore.hasUnread(for: session.id)
    }

    var body: some View {
        HStack(spacing: 8) {
            // Provider icon with optional spinning/suspended ring
            providerIcon
                .frame(width: 44, height: 44)
                .background(iconBackgroundColor.opacity(isHighlighted ? 0.35 : 0.18))
                .clipShape(Circle())
                .overlay {
                    if isSuspended {
                        SuspendedRing(color: .yellow)
                            .frame(width: 42, height: 42)
                    } else if isActive {
                        SpinningRing(color: iconBackgroundColor)
                            .frame(width: 42, height: 42)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    // Lock badge takes precedence over source / remote
                    // badges: the locked state is the most important
                    // affordance for the user when scanning the list.
                    if isVisuallyLocked {
                        badgeCircle(icon: "lock.fill", color: .gray)
                            .offset(x: 2, y: 2)
                    } else if let badge = visibleBadge {
                        // [T-ios-session-paused-badge] Transient queue states win
                        // over the source / iCloud badges. Head-of-queue is shown.
                        badgeCircle(for: badge)
                            .offset(x: 2, y: 2)
                    } else if session.source == "shortcut" {
                        badgeCircle(icon: "bolt.fill", color: .orange)
                            .offset(x: 2, y: 2)
                    } else if session.isRemote {
                        badgeCircle(icon: "icloud.fill", color: .blue, iconSize: 7)
                            .offset(x: 2, y: 2)
                    }
                }
                // [T-ios-session-unread-badge] Top-trailing red unread dot,
                // independent of the bottom-trailing corner badge above so an
                // unread session can also show ⏸ / iCloud without conflict.
                .overlay(alignment: .topTrailing) {
                    if showsUnreadDot {
                        Circle()
                            .fill(Color.red)
                            .frame(width: 8, height: 8)
                            .offset(x: -1, y: 1)
                    }
                }

            // Title + subtitle. For locked sessions both lines are blurred
            // so neither the conversation topic nor the last message reads
            // as plain text in the list. The avatar and trailing lock-icon
            // overlay sit outside this VStack so they stay crisp.
            VStack(alignment: .leading, spacing: 4) {
                highlightedText(
                    session.title ?? "New Chat",
                    font: .system(size: fontSettings.scaledApp(16), weight: .semibold),
                    color: Color(UIColor.label)
                )
                .lineLimit(1)

                if let snippet = matchSnippet, !snippet.isEmpty {
                    // During active search, prefer the body-match snippet over
                    // the generic lastMessage preview so the user can see WHY
                    // this session matched. Mirrors Android SessionListScreen
                    // 545d585. The snippet itself is also highlighted so the
                    // keyword stands out inside the ~100-char window.
                    highlightedText(
                        snippet,
                        font: .system(size: fontSettings.scaledApp(14)),
                        color: Color(UIColor.secondaryLabel)
                    )
                    .lineLimit(2)
                } else {
                    highlightedText(
                        session.lastMessage ?? "No messages yet",
                        font: .system(size: fontSettings.scaledApp(14)),
                        color: Color(UIColor.secondaryLabel)
                    )
                    .lineLimit(1)
                }
            }
            // Blur the locked content. Compared to the earlier hard
            // `.clipped()` rectangle, the rounded-rectangle clip + a
            // gentler radius + a soft horizontal fade at the trailing
            // edge let the obfuscated text dissolve into the row
            // background instead of presenting a crisp blurred block.
            //
            // [T-ios16-title-color] The modifier chain (blur+clipShape+mask)
            // is only applied while the session is actually locked. iOS 16's
            // SwiftUI renderer drains color saturation through a stack of
            // even-no-op effect modifiers (mask{Rectangle()} included), which
            // showed up as the session title rendering pale grey on iOS 16
            // while iOS 17+ kept it crisp black. Gating the whole effect
            // chain on the lock flag is the cheapest universal fix.
            .modifier(LockedRowEffect(isLocked: isVisuallyLocked))

            Spacer(minLength: 1)

            VStack(alignment: .trailing, spacing: 4) {
                // Date
                Text(relativeDate(session.updatedAt))
                    .font(.system(size: fontSettings.scaledApp(13)))
                    .foregroundStyle(Color(UIColor.tertiaryLabel))
                if isVisuallyLocked {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(Color(UIColor.tertiaryLabel))
                } else if session.isPinned {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 10))
                        .foregroundStyle(Color(UIColor.tertiaryLabel))
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
        #if DEBUG
        // TEMPORARY height probe — confirms List self-sizing jitter source.
        // PreferenceKey fires on every real layout (incl. the self-size
        // correction), unlike onAppear which fires once pre-layout.
        .background(GeometryReader { g in
            Color.clear.preference(key: RowHeightKey.self, value: g.size.height)
        })
        .onPreferenceChange(RowHeightKey.self) { h in
            probeRowHeight(h, matchSnippet == nil ? "plain" : "snippet")
        }
        #endif
    }

    private func badgeCircle(icon: String, color: Color, iconSize: CGFloat = 9, size: CGFloat = 16) -> some View {
        Image(systemName: icon)
            .font(.system(size: iconSize, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(color)
            .clipShape(Circle())
    }

    /// [T-ios-session-paused-badge] Maps a queued badge state to its corner
    /// badge. Reuses the same `badgeCircle` layout/size/position as the iCloud
    /// and lock badges so all session badges sit identically on the icon corner.
    @ViewBuilder
    private func badgeCircle(for state: SessionBadgeState) -> some View {
        switch state {
        case .paused:
            // Pause glyph (⏸), orange to distinguish from the blue iCloud badge.
            badgeCircle(icon: "pause.fill", color: .orange, iconSize: 8)
        case .iCloudSyncing:
            badgeCircle(icon: "icloud.fill", color: .blue, iconSize: 7)
        case .unread:
            // [T-ios-session-unread-badge] `.unread` renders as a separate
            // top-trailing red dot (see the .topTrailing overlay), never through
            // this bottom-trailing corner path. `topCornerBadge` filters it out
            // upstream, so this case is unreachable — render nothing defensively.
            EmptyView()
        }
    }

    @ViewBuilder
    private func highlightedText(_ text: String, font: Font, color: Color) -> some View {
        if let query = highlightQuery, !query.isEmpty,
           text.range(of: query, options: .caseInsensitive) != nil {
            Text(Self.highlightedAttributedString(text: text, query: query, baseColor: color))
                .font(font)
        } else {
            Text(text).font(font).foregroundStyle(color)
        }
    }

    /// Build a SwiftUI `AttributedString` with `backgroundColor` painted on
    /// every case-insensitive occurrence of `query`. Matches Android
    /// `highlightedAnnotatedString` (commit 545d585) — same theme-aware
    /// tint, same case-insensitive match, same span-style approach.
    /// (T-search-highlight 8edb74f2)
    private static func highlightedAttributedString(text: String, query: String, baseColor: Color) -> AttributedString {
        PerfTrace.measure("SessionRow.highlightedAttributedString") {
            highlightedAttributedStringImpl(text: text, query: query, baseColor: baseColor)
        }
    }

    private static func highlightedAttributedStringImpl(text: String, query: String, baseColor: Color) -> AttributedString {
        var attr = AttributedString(text)
        attr.foregroundColor = baseColor
        guard !query.isEmpty else { return attr }
        let highlightBg = Color.accentColor.opacity(0.25)
        let totalChars = text.count
        var searchStart = text.startIndex
        while searchStart < text.endIndex,
              let range = text.range(of: query, options: .caseInsensitive, range: searchStart..<text.endIndex) {
            let lo = text.distance(from: text.startIndex, to: range.lowerBound)
            let hi = text.distance(from: text.startIndex, to: range.upperBound)
            // Clamp to attr length; `AttributedString.index(_:offsetByCharacters:)`
            // traps on out-of-range, so we guard up front. AttributedString
            // built from a Swift `String` shares the same character count
            // as `text.count` (grapheme-cluster clusters identical), so a
            // single bounds check is enough.
            guard lo >= 0, hi <= totalChars, lo < hi else {
                searchStart = range.upperBound
                continue
            }
            let attrLo = attr.index(attr.startIndex, offsetByCharacters: lo)
            let attrHi = attr.index(attr.startIndex, offsetByCharacters: hi)
            let r = attrLo..<attrHi
            attr[r].backgroundColor = highlightBg
            attr[r].foregroundColor = Color.accentColor
            attr[r].inlinePresentationIntent = .stronglyEmphasized
            searchStart = range.upperBound
        }
        return attr
    }


    private var categoryIcon: (systemName: String, color: Color) {
        sessionCategoryIcon(for: session.category)
    }

    @ViewBuilder
    private var providerIcon: some View {
        let icon = categoryIcon
        let size: CGFloat = (icon.systemName == "bubble.left.fill" || icon.systemName == "terminal.fill") ? 18 : 20
        Image(systemName: icon.systemName)
            .font(.system(size: size))
            .foregroundStyle(icon.color)
    }

    private var iconBackgroundColor: Color {
        categoryIcon.color
    }

    private func relativeDate(_ date: Date) -> String {
        PerfTrace.measure("SessionRow.relativeDate") {
            Self.relativeDateImpl(date)
        }
    }

    // [T-ios-sessionlist-time-i18n] Row timestamps were hardcoded English
    // ("Yesterday", "N min ago", raw "M/d") while the section headers around
    // them localize via LocalizedStringKey — a zh UI showed "昨天" headers
    // next to "Yesterday" row times. All four phrase keys already exist in
    // Localizable.xcstrings (8 locales); the weekday/short-date fall-backs
    // use localized format templates so e.g. zh renders 7月8日 instead of 7/8.
    // fileprivate (not private): FolderRowHeader shares this exact formatter —
    // a second hand-rolled variant is how the T-ios-sessionlist-time-i18n
    // drift happened the first time.
    fileprivate static func relativeDateImpl(_ date: Date) -> String {
        let now = Date()
        let calendar = Calendar.current
        let seconds = Int(now.timeIntervalSince(date))
        if calendar.isDateInToday(date) {
            if seconds < 60 {
                return AppLocalized("Just now")
            } else if seconds < 3600 {
                let mins = seconds / 60
                return AppLocalized("\(mins) min ago")
            } else {
                let hrs = seconds / 3600
                return AppLocalized("\(hrs) hr ago")
            }
        } else if calendar.isDateInYesterday(date) {
            return AppLocalized("Yesterday")
        } else {
            let diff = calendar.dateComponents([.day], from: date, to: now)
            let formatter = DateFormatter()
            if let days = diff.day, days < 7 {
                formatter.setLocalizedDateFormatFromTemplate("EEEE")
            } else {
                formatter.setLocalizedDateFormatFromTemplate("Md")
            }
            return formatter.string(from: date)
        }
    }
}

/// Gate the blur + clip + gradient-mask trio on the lock flag so iOS 16
/// doesn't pay the always-on `.mask { Rectangle() }` color-drain cost
/// for unlocked rows (T-ios16-title-color). When unlocked, the row
/// renders with no extra effect modifiers at all.
private struct LockedRowEffect: ViewModifier {
    let isLocked: Bool

    func body(content: Content) -> some View {
        if isLocked {
            content
                .blur(radius: 4)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .mask {
                    LinearGradient(
                        stops: [
                            .init(color: .black, location: 0.0),
                            .init(color: .black, location: 0.82),
                            .init(color: .black.opacity(0.0), location: 1.0),
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                }
        } else {
            content
        }
    }
}

/// Row for a session synced from another device, with an iCloud badge on the icon.
private struct RemoteSessionRow: View {
    let session: ChatSession

    var body: some View {
        HStack(spacing: 8) {
            // Category icon with iCloud badge
            providerIcon
                .frame(width: 44, height: 44)
                .background(iconColor.opacity(0.18))
                .clipShape(Circle())
                .overlay(alignment: .bottomTrailing) {
                    Image(systemName: "icloud.fill")
                        .font(.system(size: 7, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 16, height: 16)
                        .background(Color.blue)
                        .clipShape(Circle())
                        .offset(x: 2, y: 2)
                }

            VStack(alignment: .leading, spacing: 4) {
                Text(session.title ?? "Untitled")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color(UIColor.label))
                    .lineLimit(1)
                Text(session.updatedAt, style: .relative)
                    .font(.system(size: 14))
                    .foregroundStyle(Color(UIColor.secondaryLabel))
                    .lineLimit(1)
            }

            Spacer(minLength: 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }

    private var categoryIcon: (systemName: String, color: Color) {
        sessionCategoryIcon(for: session.category)
    }

    private var iconColor: Color { categoryIcon.color }

    @ViewBuilder
    private var providerIcon: some View {
        let icon = categoryIcon
        let size: CGFloat = (icon.systemName == "bubble.left.fill" || icon.systemName == "terminal.fill") ? 18 : 20
        Image(systemName: icon.systemName)
            .font(.system(size: size))
            .foregroundStyle(icon.color)
    }
}

/// A dashed circle ring indicating a session is suspended (waiting for a concurrency slot).
private struct SuspendedRing: View {
    let color: Color

    var body: some View {
        Circle()
            .stroke(color, style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
    }
}

// MARK: - Session Edit Sheet

struct SessionEditSheet: View {
    let session: ChatSession
    let onSave: (String, String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var editTitle: String = ""
    @State private var editCategory: String = ""
    @State private var isRegenerating = false

    private static let categories: [(key: String, label: String, icon: String, color: Color)] = [
        ("code",         "Code",         "terminal.fill",               .orange),
        ("writing",      "Writing",      "doc.text.fill",               .blue),
        ("research",     "Research",     "globe.americas.fill",         .teal),
        ("analysis",     "Analysis",     "chart.pie.fill",              .indigo),
        ("creative",     "Creative",     "paintbrush.pointed.fill",     .pink),
        ("chat",         "Chat",         "bubble.left.fill",            .green),
        ("math",         "Math",         "number.circle.fill",          .purple),
        ("translation",  "Translation",  "character.bubble",            .cyan),
        ("health",       "Health",       "heart.fill",                  .red),
        ("finance",      "Finance",      "banknote.fill",               .mint),
        ("travel",       "Travel",       "map.fill",                    .orange),
        ("education",    "Education",    "book.closed.fill",            .blue),
        ("design",       "Design",       "paintpalette.fill",           .pink),
        ("productivity", "Productivity", "calendar.badge.checkmark",    .yellow),
        ("support",      "Support",      "gearshape.fill",              .brown),
        ("other",        "Other",        "square.grid.2x2.fill",       .gray),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section("Title") {
                    TextField("Session title", text: $editTitle)
                }

                Section("Category") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 12)], spacing: 12) {
                        ForEach(Self.categories, id: \.key) { cat in
                            Button {
                                editCategory = cat.key
                            } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: cat.icon)
                                        .font(.system(size: 20))
                                        .foregroundStyle(editCategory == cat.key ? .white : cat.color)
                                        .frame(width: 44, height: 44)
                                        .background(
                                            Circle()
                                                .fill(editCategory == cat.key ? cat.color : cat.color.opacity(0.12))
                                        )
                                    Text(LocalizedStringKey(cat.label))
                                        .font(.caption2)
                                        .foregroundStyle(editCategory == cat.key ? cat.color : .secondary)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 8)
                }

                Section {
                    Button {
                        regenerate()
                    } label: {
                        HStack {
                            if isRegenerating {
                                SpinningRing(color: .accentColor)
                                    .frame(width: 18, height: 18)
                            } else {
                                Image(systemName: "arrow.triangle.2.circlepath")
                            }
                            Text("Regenerate Title")
                        }
                        .frame(maxWidth: .infinity, alignment: .center)
                    }
                    .disabled(isRegenerating)
                }
            }
            .navigationTitle("Edit Session")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let title = editTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !title.isEmpty else { return }
                        onSave(title, editCategory.isEmpty ? nil : editCategory)
                    }
                    .bold()
                    .disabled(editTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .onAppear {
                editTitle = session.title ?? ""
                editCategory = session.category ?? ""
            }
        }
    }

    private func regenerate() {
        guard !isRegenerating else { return }
        isRegenerating = true
        Task { @MainActor in
            defer { isRegenerating = false }
            do {
                try await AIChatViewModel.regenerateSessionTitle(sessionId: session.id)
                if let updated = await ChatStore.shared.getSession(session.id) {
                    if let title = updated.title { editTitle = title }
                    editCategory = updated.category ?? editCategory
                }
            } catch {
                AppLogger(category: "RegenerateTitle").error(
                    "session=\(session.id) failed: \(error.localizedDescription) — \(String(describing: error))"
                )
            }
        }
    }
}

/// A self-contained spinning arc that uses TimelineView to avoid
/// stacking animations on repeated onAppear calls.
private struct SpinningRing: View {
    let color: Color

    var body: some View {
        TimelineView(.animation) { timeline in
            let angle = timeline.date.timeIntervalSinceReferenceDate.remainder(dividingBy: 1.0) * 360
            Circle()
                .trim(from: 0, to: 0.3)
                .stroke(color, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                .rotationEffect(.degrees(angle))
        }
    }
}

// MARK: - Appearance Settings

private struct AppIconOption: Identifiable {
    let id: Int
    let title: String
    let subtitle: String
    let iconName: String?  // nil = automatic (default asset catalog icon)
    let imageName: String  // bundle image file name for preview
}

private struct LanguageOption: Identifiable {
    let id: String   // language code, "" = system
    let name: String  // native name
    let flag: String
}

// Native names are deliberately NOT localized: a language picker shows each
// option in its own language so a user who cannot read the current UI language
// can still find theirs.
//
// [T-ios-inapp-language-string-localized] zh-Hant was missing even though the
// catalog carries a full Traditional Chinese translation, so those users could
// not select it in-app; es is new. Every id here must have a matching .lproj in
// the bundle — `Bundle.setLanguage(_:)` looks the directory up by this string
// and silently falls back to the system language if it is absent.
private let supportedLanguages: [LanguageOption] = [
    LanguageOption(id: "",       name: "System", flag: ""),
    LanguageOption(id: "en",     name: "English", flag: "🇺🇸"),
    LanguageOption(id: "zh-Hans", name: "简体中文", flag: "🇨🇳"),
    LanguageOption(id: "zh-Hant", name: "繁體中文", flag: "🇭🇰"),
    LanguageOption(id: "ja",     name: "日本語", flag: "🇯🇵"),
    LanguageOption(id: "ko",     name: "한국어", flag: "🇰🇷"),
    LanguageOption(id: "es",     name: "Español", flag: "🇪🇸"),
    LanguageOption(id: "fr",     name: "Français", flag: "🇫🇷"),
    LanguageOption(id: "de",     name: "Deutsch", flag: "🇩🇪"),
    LanguageOption(id: "ru",     name: "Русский", flag: "🇷🇺"),
]

private struct FontScaleRow: View {
    let label: String
    @Binding var level: FontScaleLevel

    private static let cases = FontScaleLevel.allCases
    private let stepCount = FontScaleRow.cases.count  // 5

    private var currentIndex: Int {
        Self.cases.firstIndex(of: level) ?? 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            // `label` is a fixed English identifier ("Chat Input" etc.);
            // wrap in LocalizedStringKey so SwiftUI looks it up in the
            // String Catalog instead of rendering the verbatim English.
            Text(LocalizedStringKey(label))
            HStack(spacing: 12) {
                Button {
                    let idx = currentIndex - 1
                    if idx >= 0 {
                        level = Self.cases[idx]
                    }
                } label: {
                    Text("A")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)

                SteppedSlider(value: currentIndex, steps: stepCount) { newIndex in
                    if newIndex >= 0, newIndex < Self.cases.count {
                        let newLevel = Self.cases[newIndex]
                        if newLevel != level { level = newLevel }
                    }
                }

                Button {
                    let idx = currentIndex + 1
                    if idx < Self.cases.count {
                        level = Self.cases[idx]
                    }
                } label: {
                    Text("A")
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 2)
    }
}

/// A custom stepped slider that supports both drag and tap-to-snap.
/// Uses local gesture state to avoid layout feedback loops from SwiftUI's Slider.
private struct SteppedSlider: View {
    let value: Int
    let steps: Int
    let onChanged: (Int) -> Void

    private let trackHeight: CGFloat = 4
    private let thumbSize: CGFloat = 22
    private let tickSize: CGFloat = 6

    @State private var dragValue: Int?

    private var displayValue: Int { dragValue ?? value }

    var body: some View {
        GeometryReader { geo in
            let totalW = geo.size.width
            let midY = geo.size.height / 2
            let maxStep = CGFloat(steps - 1)

            ZStack(alignment: .leading) {
                // Track background
                Capsule()
                    .fill(Color(.systemFill))
                    .frame(height: trackHeight)
                    .position(x: totalW / 2, y: midY)

                // Filled portion
                let fillW = maxStep > 0 ? totalW * CGFloat(displayValue) / maxStep : 0
                Capsule()
                    .fill(Color.accentColor)
                    .frame(width: fillW, height: trackHeight)
                    .position(x: fillW / 2, y: midY)

                // Tick marks
                ForEach(0..<steps, id: \.self) { i in
                    let x = maxStep > 0 ? totalW * CGFloat(i) / maxStep : 0
                    Circle()
                        .fill(i <= displayValue ? Color.accentColor : Color(.systemFill))
                        .frame(width: tickSize, height: tickSize)
                        .position(x: x, y: midY)
                }

                // Thumb
                let thumbX = maxStep > 0 ? totalW * CGFloat(displayValue) / maxStep : 0
                Circle()
                    .fill(.white)
                    .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
                    .frame(width: thumbSize, height: thumbSize)
                    .position(x: thumbX, y: midY)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { drag in
                        let step = stepFromX(drag.location.x, width: totalW)
                        if step != dragValue { dragValue = step }
                    }
                    .onEnded { drag in
                        let step = stepFromX(drag.location.x, width: totalW)
                        dragValue = nil
                        onChanged(step)
                    }
            )
        }
        .frame(height: thumbSize + 8)
    }

    private func stepFromX(_ x: CGFloat, width: CGFloat) -> Int {
        guard width > 0, steps > 1 else { return 0 }
        let fraction = x / width
        let clamped = min(max(fraction, 0), 1)
        return Int((clamped * CGFloat(steps - 1)).rounded())
    }
}

private struct AppearanceSettingsView: View {
    @AppStorage("appearanceMode") private var appearanceMode: Int = 0
    @AppStorage("appIconMode") private var appIconMode: Int = 0
    @AppStorage("appLanguage") private var appLanguage: String = ""
    @AppStorage("launchScreen") private var launchScreen: Int = 0  // 0=Auto, 1=Last Session, 2=New Chat, 3=Home
    @AppStorage("toolPreviewEnabled") private var toolPreviewEnabled: Bool = true
    /// 0 = Return inserts a newline (default), 1 = Return sends the message.
    @AppStorage("returnKeyBehavior") private var returnKeyBehavior: Int = 0
    /// When true, holds `UIApplication.isIdleTimerDisabled` while any session
    /// is running a task. See `KeepScreenAwakeController`.
    @AppStorage("keepScreenAwakeDuringTasks") private var keepScreenAwakeDuringTasks: Bool = false
    /// [T-keyboard-auto-pop default flip] When true, the input field becomes
    /// the first responder ~1.5 s after the model finishes a reply (the
    /// historical behavior). ON by default — most users want the composer
    /// ready for a follow-up immediately. Existing users who explicitly
    /// toggled OFF keep their stored false; users who never opened the
    /// toggle get the new ON default via @AppStorage's fallback.
    @AppStorage("chat.autoFocusAfterReply") private var autoFocusAfterReply: Bool = true
    /// [T-thinking-auto-expand-toggle] When true (default, historical
    /// behavior) a NEW streaming thinking block auto-expands while reasoning
    /// streams. When false it stays collapsed until tapped. Read at
    /// block-mount time in ThinkingBlockView.
    @AppStorage("chat.autoExpandThinking") private var autoExpandThinking: Bool = true
    @ObservedObject private var fontSettings = FontSettings.shared

    private let iconOptions: [AppIconOption] = [
        AppIconOption(id: 0, title: "Automatic", subtitle: "Follows system", iconName: nil, imageName: "AlternateIcons/AppIcon-Light"),
        AppIconOption(id: 1, title: "Light", subtitle: "Always light", iconName: "AppIcon-Light", imageName: "AlternateIcons/AppIcon-Light"),
        AppIconOption(id: 2, title: "Dark", subtitle: "Always dark", iconName: "AppIcon-Dark", imageName: "AlternateIcons/AppIcon-Dark"),
        AppIconOption(id: 3, title: "Light (Legacy)", subtitle: "Classic light icon", iconName: "AppIcon-LegacyLight", imageName: "AlternateIcons/AppIcon-LegacyLight"),
        AppIconOption(id: 4, title: "Dark (Legacy)", subtitle: "Classic dark icon", iconName: "AppIcon-LegacyDark", imageName: "AlternateIcons/AppIcon-LegacyDark"),
    ]

    var body: some View {
        List {
            Section {
                Picker("Theme", selection: $appearanceMode) {
                    Text("System").tag(0)
                    Text("Light").tag(1)
                    Text("Dark").tag(2)
                }
                .pickerStyle(.segmented)
            } header: {
                Text("Theme")
            } footer: {
                Text("Override the system appearance for this app.")
            }
            .onChange(of: appearanceMode) { _ in
                guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
                windowScene.windows.forEach { window in
                    window.overrideUserInterfaceStyle = appearanceMode == 1 ? .light : appearanceMode == 2 ? .dark : .unspecified
                }
            }

            Section {
                Picker("Launch Session", selection: $launchScreen) {
                    Text("Auto").tag(0)
                    Text("Last Session").tag(1)
                    Text("New Chat").tag(2)
                    Text("Home").tag(3)
                }
            } header: {
                Text("Launch Session")
            } footer: {
                Text("Choose what to show when the app starts. \"Auto\" opens a new chat if the last session is older than 15 minutes.")
            }

            // Auto-grouping default is OFF on principle: it costs nothing
            // extra (it rides the title-generation call), but it moves user
            // data without being asked — a behavior the user must opt into.
            // No pendingSettingsReopen dance here: that exists only because
            // appLanguage rebuilds the root via .id(); a plain toggle has no
            // such side effect.
            Section {
                Toggle("Auto-Grouping", isOn: autoGroupingBinding)
            } header: {
                Text("Grouping")
            } footer: {
                Text("When a chat's title is first generated, also file it into a matching existing group. Runs once per chat, only uses groups you already created, and leaves the chat ungrouped when nothing matches.")
            }

            Section {
                Picker(AppLocalized("Return Key"), selection: $returnKeyBehavior) {
                    Text(AppLocalized("Newline")).tag(0)
                    Text(AppLocalized("Send")).tag(1)
                }
                .pickerStyle(.segmented)
            } header: {
                Text("Return Key")
            } footer: {
                Text("Choose whether the Return key in the chat input inserts a newline or sends the message. Hardware Shift+Return always inserts a newline.")
            }

            Section {
                Toggle(AppLocalized("Keep Screen Awake"), isOn: $keepScreenAwakeDuringTasks)
            } header: {
                Text("Keep Screen Awake")
            } footer: {
                Text("Prevent the screen from sleeping while any session is running a task. May increase battery drain.")
            }

            Section {
                Toggle(AppLocalized("Auto-Focus Input After Reply"), isOn: $autoFocusAfterReply)
            } header: {
                Text("Auto-Focus Input After Reply")
            } footer: {
                Text("When on, the keyboard pops up automatically after the model finishes replying so the input is ready for a follow-up. On by default; turn off if you prefer to read the response without an unexpected keyboard.")
            }

            Section {
                Toggle(AppLocalized("Tool Preview Window"), isOn: $toolPreviewEnabled)
            } header: {
                Text("Tool Status Bar")
            } footer: {
                Text("Show a live preview thumbnail alongside the tool status bar during agent execution.")
            }

            // [T-thinking-auto-expand-toggle] Whether a NEW streaming thinking
            // block opens expanded (historical behavior, default) or stays
            // collapsed. Only affects the streaming auto-expand; manual taps
            // always work either way.
            Section {
                Toggle(AppLocalized("Expand Thinking While Streaming"), isOn: $autoExpandThinking)
            } header: {
                Text("Deep Thinking")
            } footer: {
                Text("When on, a new thinking block expands automatically while the model is reasoning and collapses when it finishes. When off, thinking blocks stay collapsed — tap one to read it.")
            }

            Section {
                FontScaleRow(
                    label: "Chat Input",
                    level: $fontSettings.chatInputScale
                )
                FontScaleRow(
                    label: "Message Text",
                    level: $fontSettings.messageBaseScale
                )
                FontScaleRow(
                    label: "App Base",
                    level: $fontSettings.appBaseScale
                )
                if fontSettings.isModified {
                    Button("Reset to Defaults") {
                        fontSettings.resetToDefaults()
                    }
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .center)
                }
            } header: {
                Text("Font Size")
            } footer: {
                Text("Scale fonts for chat input, message content, and general app text. Changes apply immediately.")
            }

            if UIApplication.shared.supportsAlternateIcons {
                Section {
                    ForEach(iconOptions) { option in
                        Button {
                            guard appIconMode != option.id else { return }
                            appIconMode = option.id
                            UIApplication.shared.setAlternateIconName(option.iconName) { error in
                                if let error = error {
                                    print("[AppIcon] Failed to set icon: \(error.localizedDescription)")
                                }
                            }
                        } label: {
                            HStack(spacing: 14) {
                                if let img = UIImage(named: option.imageName) {
                                    Image(uiImage: img)
                                        .resizable()
                                        .aspectRatio(contentMode: .fill)
                                        .frame(width: 60, height: 60)
                                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                                .stroke(Color.primary.opacity(0.15), lineWidth: 0.5)
                                        )
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    // `option.title` / `option.subtitle` are fixed English
                                    // keys ("Automatic", "Light", "Always light", etc.).
                                    // Wrap in LocalizedStringKey so the catalog lookup kicks
                                    // in — Text(String) would render them verbatim.
                                    Text(LocalizedStringKey(option.title))
                                        .foregroundStyle(.primary)
                                    Text(LocalizedStringKey(option.subtitle))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if appIconMode == option.id {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.blue)
                                        .font(.title3)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                } header: {
                    Text("App Icon")
                } footer: {
                    Text("\"Automatic\" uses the system icon which adapts to Dark Mode on iOS 18+.")
                }
            }

            Section {
                ForEach(supportedLanguages) { lang in
                    Button {
                        // Persist a reopen-hint BEFORE flipping appLanguage —
                        // the @AppStorage write triggers the root
                        // `.id(appLanguage)` rebuild in MinisApp.swift, which
                        // drops the entire view tree including the Settings
                        // sheet. ContentView/SettingsSheet read this flag on
                        // re-mount and reopen the sheet + push back to the
                        // Appearance page so the user lands where they were
                        // with all strings rendered in the new language.
                        UserDefaults.standard.set("appearance", forKey: "pendingSettingsReopen")
                        // [T-ios-stacknav-transition-attributegraph-race] The
                        // `.id(appLanguage)` re-key below is the app's only
                        // unconditional WHOLE-TREE teardown, and a chat can be
                        // streaming underneath this sheet while it happens —
                        // the same hosting-subgraph race the push/pop observers
                        // guard, with every mounted vm outgoing at once. Pin
                        // them all across the re-mount. No-ops when nothing is
                        // processing, which is the overwhelmingly common case.
                        ViewModelCache.shared.suspendAllForTreeRemount()
                        appLanguage = lang.id
                        Bundle.setLanguage(lang.id.isEmpty ? nil : lang.id)
                    } label: {
                        HStack(spacing: 12) {
                            if !lang.flag.isEmpty {
                                Text(lang.flag).font(.title2)
                            } else {
                                Image(systemName: "globe")
                                    .font(.title3)
                                    .foregroundStyle(.secondary)
                                    .frame(width: 28)
                            }
                            Text(lang.name)
                                .foregroundStyle(.primary)
                            Spacer()
                            if appLanguage == lang.id {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.blue)
                                    .fontWeight(.semibold)
                            }
                        }
                    }
                }
            } header: {
                Text("Language")
            } footer: {
                Text("Override the display language for this app. \"System\" follows your device language.")
            }

        }
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.inline)
        .background(InteractivePopGestureDisabler())
    }

    /// UserDefaults-backed binding (not @AppStorage: the key is read at
    /// title-generation time in AIChatViewModel, so a plain defaults write is
    /// the single source of truth).
    private var autoGroupingBinding: Binding<Bool> {
        Binding(
            get: { UserDefaults.standard.bool(forKey: "autoGroupingEnabled") },
            set: { UserDefaults.standard.set($0, forKey: "autoGroupingEnabled") }
        )
    }
}

/// Disables the navigation controller's interactive pop gesture on the hosting page
/// to prevent conflict with horizontal Slider drag.
private struct InteractivePopGestureDisabler: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = GestureDisablerView()
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) {}

    private class GestureDisablerView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            setPopGesture(enabled: false)
        }
        override func willMove(toWindow newWindow: UIWindow?) {
            super.willMove(toWindow: newWindow)
            if newWindow == nil { setPopGesture(enabled: true) }
        }
        private func setPopGesture(enabled: Bool) {
            // Walk the responder chain to find the UINavigationController
            var responder: UIResponder? = self
            while let r = responder {
                if let nav = r as? UINavigationController {
                    nav.interactivePopGestureRecognizer?.isEnabled = enabled
                    return
                }
                responder = r.next
            }
        }
    }
}

// MARK: - Settings Sheet

private enum SettingsDestination: Hashable {
    case providers
    case providerDetail(instanceId: String)
    case modelGroups
    case modelGroupDetail(groupId: String)
    case usage
    case skills
    // [T-ios-assistant-header-open-soul]
    case soul
    case memory
    case storage
    case mountedFolders
    case sharedFolders
    case logs
    case appearance
    case background
    case about
    case permissions
    case environments
    // [T-mcp-oauth-deeplink]
    case mcpIntegrations
    case mcpServerDetail(serverId: String)
}

private struct SettingsSheet: View {
    @Binding var showTerminal: Bool
    @AppStorage("appearanceMode") private var appearanceMode: Int = 0
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var deepLink = DeepLinkCoordinator.shared
    @State private var navPath = NavigationPath()
    @State private var showFeedbackDialog = false

    var body: some View {
        NavigationStack(path: $navPath) {
            List {
                Section {
                    NavigationLink {
                        ProviderInstancesView()
                    } label: {
                        if #available(iOS 26, *) {
                            Label("Manage Providers", systemImage: "key.circle.fill")
                        } else {
                            Label("Manage Providers", systemImage: "lock.circle.fill")
                        }
                    }

                    NavigationLink {
                        ModelGroupsView()
                    } label: {
                        Label("Model Groups", systemImage: "gearshape.circle.fill")
                    }

                    NavigationLink {
                        UsageStatsView()
                    } label: {
                        Label("Token Usage", systemImage: "chart.line.uptrend.xyaxis.circle.fill")
                    }
                } header: {
                    Text("LLM Providers")
                } footer: {
                    Text("Configure which models the agent uses, manage API keys & OAuth for each provider, and create model groups for fallback or load balancing.")
                }

                Section("Appearance") {
                    NavigationLink {
                        AppearanceSettingsView()
                    } label: {
                        Label {
                            Text("Appearance")
                        } icon: {
                            Image(systemName: "paintbrush.fill")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.indigo, in: Circle())
                        }
                    }
                }

                Section("Agent Runtime") {
                    NavigationLink {
                        SkillsManagementView()
                    } label: {
                        Label {
                            Text("Skills")
                        } icon: {
                            Image(systemName: "puzzlepiece.extension")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.blue, in: Circle())
                        }
                    }
                    NavigationLink {
                        SoulSettingsView()
                    } label: {
                        Label {
                            Text("Soul")
                        } icon: {
                            Image(systemName: "sparkles")
                                .font(.system(size: 11))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.pink, in: Circle())
                        }
                    }
                    NavigationLink {
                        MemoryManagementView()
                    } label: {
                        Label {
                            Text("Memory")
                        } icon: {
                            Image(systemName: "brain.head.profile")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.purple, in: Circle())
                        }
                    }
                    NavigationLink {
                        MCPIntegrationsView()
                    } label: {
                        Label {
                            Text("MCP Integrations")
                        } icon: {
                            Image(systemName: "square.stack.3d.up")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.teal, in: Circle())
                        }
                    }
                    NavigationLink {
                        EnvironmentVariablesView()
                    } label: {
                        Label {
                            Text("Environment Variables")
                        } icon: {
                            Image(systemName: "terminal")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.green, in: Circle())
                        }
                    }
                }

                Section("Storage") {
                    NavigationLink {
                        StorageManagementView()
                    } label: {
                        Label {
                            Text("Storage")
                        } icon: {
                            Image(systemName: "archivebox")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.blue, in: Circle())
                        }
                    }
                    NavigationLink {
                        SharedFoldersSettingsView()
                    } label: {
                        Label {
                            Text("Shared Folders")
                        } icon: {
                            Image(systemName: "folder.fill.badge.person.crop")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.green, in: Circle())
                        }
                    }
                    NavigationLink {
                        MountedFoldersSettingsView()
                    } label: {
                        Label {
                            Text("Mount External Folders")
                        } icon: {
                            Image(systemName: "externaldrive.badge.plus")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.orange, in: Circle())
                        }
                    }
                    if #available(iOS 17.0, *) {
                        NavigationLink {
                            // v2 is the default sync engine; legacy v1
                            // settings page is unreachable from here.
                            CloudSyncSettingsV2View()
                        } label: {
                            Label {
                                Text("iCloud Sync")
                            } icon: {
                                Image(systemName: "icloud")
                                    .font(.system(size: 9))
                                    .foregroundStyle(.white)
                                    .frame(width: 21, height: 21)
                                    .background(.cyan, in: Circle())
                            }
                        }
                    }
                    NavigationLink {
                        BackupAndRestoreView()
                    } label: {
                        Label {
                            // Not just "Backup": this screen is both halves of
                            // the feature, and on a new device restore is the
                            // only one the user is looking for.
                            Text("Backup & Restore")
                        } icon: {
                            // arrow.triangle.2.circlepath reads as a round trip
                            // rather than a one-way export.
                            Image(systemName: "arrow.triangle.2.circlepath")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.indigo, in: Circle())
                        }
                    }
                }

                Section("Permissions") {
                    NavigationLink {
                        OffloadPermissionSettingsView()
                    } label: {
                        Label {
                            Text("Permissions")
                        } icon: {
                            Image(systemName: "lock.shield")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.red, in: Circle())
                        }
                    }
                    if BiometricAuth.isAvailable {
                        NavigationLink {
                            FaceIDProtectionSettingsView()
                        } label: {
                            Label {
                                Text("\(BiometricAuth.biometryDisplayName) Protection")
                            } icon: {
                                // Match SF Symbol to the device's actual sensor — Touch ID
                                // devices showed a Face ID glyph here before.
                                Image(systemName: BiometricAuth.biometryIconName)
                                    .font(.system(size: 11))
                                    .foregroundStyle(.white)
                                    .frame(width: 21, height: 21)
                                    .background(.teal, in: Circle())
                            }
                        }
                    }
                }

                Section("Logs") {
                    NavigationLink {
                        LogManagementView()
                    } label: {
                        Label {
                            Text("Logs")
                        } icon: {
                            Image(systemName: "doc.text")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.gray, in: Circle())
                        }
                    }
                }

                Section("About") {
                    NavigationLink {
                        AboutView()
                    } label: {
                        Label {
                            Text("About Minis")
                        } icon: {
                            Image(systemName: "info")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.indigo, in: Circle())
                        }
                    }
                    Link(destination: URL(string: "https://openminis.github.io/privacy-policy.html")!) {
                        Label {
                            Text("Privacy Policy")
                        } icon: {
                            Image(systemName: "hand.raised")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.teal, in: Circle())
                        }
                    }
                    Button {
                        showFeedbackDialog = true
                    } label: {
                        Label {
                            Text("Feedback")
                        } icon: {
                            Image(systemName: "bubble.left.and.bubble.right.fill")
                                .font(.system(size: 9))
                                .foregroundStyle(.white)
                                .frame(width: 21, height: 21)
                                .background(.indigo, in: Circle())
                        }
                    }
                    .foregroundStyle(.primary)
                    .confirmationDialog("Feedback", isPresented: $showFeedbackDialog, titleVisibility: .visible) {
                        Button("Report a Bug (GitHub)") {
                            if let url = Self.makeBugReportURL() { UIApplication.shared.open(url) }
                        }
                        Button("Feedback (Telegram)") {
                            if let url = URL(string: "https://t.me/+2NzhOJuzRyI1YmM1") { UIApplication.shared.open(url) }
                        }
                        Button("Feedback (Email)") {
                            if let url = Self.makeFeedbackEmailURL() { UIApplication.shared.open(url) }
                        }
                        Button("Cancel", role: .cancel) {}
                    }
                }

            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .navigationDestination(for: SettingsDestination.self) { dest in
                switch dest {
                case .providers:
                    ProviderInstancesView()
                case .providerDetail(let id):
                    ProviderInstanceDetailView(instanceId: id)
                case .modelGroups:
                    ModelGroupsView()
                case .modelGroupDetail(let id):
                    ModelGroupDetailView(groupId: id)
                case .usage:
                    UsageStatsView()
                case .skills:
                    SkillsManagementView()
                case .soul:
                    SoulSettingsView()
                case .memory:
                    MemoryManagementView()
                case .storage:
                    StorageManagementView()
                case .mountedFolders:
                    MountedFoldersSettingsView()
                case .sharedFolders:
                    SharedFoldersSettingsView()
                case .logs:
                    // Pull a one-shot tab hint from the deep link router
                    // (e.g. `?tab=config-audit`). LogManagementView clears
                    // its local state independently; the published value
                    // here is consumed once and reset to nil.
                    LogManagementView(initialTab: deepLink.pendingLogsTab ?? "logs")
                        .onAppear { deepLink.pendingLogsTab = nil }
                case .appearance:
                    AppearanceSettingsView()
                case .background:
                    EnhancedBackgroundSettingsView()
                case .about:
                    AboutView()
                case .environments:
                    EnvironmentVariablesView()
                case .permissions:
                    OffloadPermissionSettingsView()
                // [T-mcp-oauth-deeplink] Detail = the list view told to open
                // the server's edit sheet on appear; a deleted/unknown server
                // just lands on the list (no crash, sensible fallback).
                case .mcpIntegrations:
                    MCPIntegrationsView()
                case .mcpServerDetail(let serverId):
                    MCPIntegrationsView(initialEditServerId: serverId)
                }
            }
            .onAppear {
                applyPendingDeepLink()
                // Legacy flags — kept so older call sites keep working.
                if deepLink.showEnvironmentVariables {
                    navPath.append(SettingsDestination.environments)
                    deepLink.showEnvironmentVariables = false
                }
                if deepLink.showPermissions {
                    navPath.append(SettingsDestination.permissions)
                    deepLink.showPermissions = false
                }
                // Restore the user's location after a language-change rebuild.
                // AppearanceSettingsView's language picker writes this flag
                // right before flipping `appLanguage`, knowing the root
                // `.id(appLanguage)` will tear the whole tree down. ContentView
                // re-opens the sheet on re-mount; here we push back to the
                // destination so the user lands where they were, now rendered
                // in the new language.
                if let dest = UserDefaults.standard.string(forKey: "pendingSettingsReopen") {
                    UserDefaults.standard.removeObject(forKey: "pendingSettingsReopen")
                    switch dest {
                    case "appearance":
                        navPath.append(SettingsDestination.appearance)
                    default:
                        break
                    }
                }
            }
            .onChange(of: deepLink.pendingSettingsTarget) { _ in
                applyPendingDeepLink()
            }
        }
        .preferredColorScheme(appearanceMode == 1 ? .light : appearanceMode == 2 ? .dark : nil)
        .appFontScale()
    }

    /// Translate `DeepLinkCoordinator.pendingSettingsTarget` into a
    /// NavigationStack push and clear the pending value. Called from
    /// `onAppear` (cold-start deep link) and `onChange` (deep link
    /// arriving while the sheet is already open).
    ///
    /// `.environments` keeps its existing prefill semantics — the
    /// environments view consumes `pendingEnvVarCreate` separately on
    /// appear, so we only have to navigate here.
    private func applyPendingDeepLink() {
        guard let target = deepLink.pendingSettingsTarget else { return }
        // Reset path so deep links are predictable: a deep link always
        // lands on the requested destination as the only stack entry,
        // not on top of whatever the user was browsing earlier.
        navPath = NavigationPath()
        switch target {
        case .home:
            break // already at Settings root
        case .providers:
            navPath.append(SettingsDestination.providers)
        case .providerDetail(let id):
            navPath.append(SettingsDestination.providers)
            navPath.append(SettingsDestination.providerDetail(instanceId: id))
        case .modelGroups:
            navPath.append(SettingsDestination.modelGroups)
        case .modelGroupDetail(let id):
            navPath.append(SettingsDestination.modelGroups)
            navPath.append(SettingsDestination.modelGroupDetail(groupId: id))
        case .usage:
            navPath.append(SettingsDestination.usage)
        case .skills:
            navPath.append(SettingsDestination.skills)
        case .soul:
            navPath.append(SettingsDestination.soul)
        case .memory:
            navPath.append(SettingsDestination.memory)
        case .storage:
            navPath.append(SettingsDestination.storage)
        case .mountedFolders:
            navPath.append(SettingsDestination.mountedFolders)
        case .sharedFolders:
            navPath.append(SettingsDestination.sharedFolders)
        case .logs:
            navPath.append(SettingsDestination.logs)
        case .appearance:
            navPath.append(SettingsDestination.appearance)
        case .background:
            navPath.append(SettingsDestination.background)
        case .about:
            navPath.append(SettingsDestination.about)
        case .permissions:
            navPath.append(SettingsDestination.permissions)
        case .environments:
            navPath.append(SettingsDestination.environments)
        case .mcpIntegrations:
            navPath.append(SettingsDestination.mcpIntegrations)
        case .mcpServerDetail(let id):
            navPath.append(SettingsDestination.mcpServerDetail(serverId: id))
        }
        deepLink.pendingSettingsTarget = nil
    }

    /// Compose the feedback mailto URL with a prefilled body that includes
    /// app version, iOS version, and a machine identifier, plus a prompt
    /// asking the user to attach a screenshot manually (mailto:// can't
    /// auto-attach). Using URLComponents so the subject and body go through
    /// proper URL encoding without hand-rolling addingPercentEncoding calls.
    fileprivate static func makeFeedbackEmailURL() -> URL? {
        let bundle = Bundle.main
        let appVersion = bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = bundle.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        let iosVersion = UIDevice.current.systemVersion
        let device = machineIdentifier()

        let body = """
        Please describe your feedback:


        ---
        App Version: \(appVersion) (\(build))
        iOS Version: \(iosVersion)
        Device: \(device)

        Screenshot (optional): Please attach a screenshot if relevant.
        """

        var components = URLComponents()
        components.scheme = "mailto"
        components.path = "dev@openminis.app"
        components.queryItems = [
            URLQueryItem(name: "subject", value: "Minis Feedback"),
            URLQueryItem(name: "body", value: body),
        ]
        return components.url
    }

    /// Build the GitHub Issue URL with a bilingual bug-report template
    /// pre-filled with platform / OS / app / device info. SwiftUI `Link`
    /// hands the URL to UIApplication.shared.open, which routes to Safari.
    fileprivate static func makeBugReportURL() -> URL? {
        let bundle = Bundle.main
        let appVersion = bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = bundle.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        let iosVersion = UIDevice.current.systemVersion
        let device = machineIdentifier()

        let body = """
        ## 📝 Problem Summary

        <!-- Briefly describe the issue you encountered -->


        ## 📱 Basic Information

        | Field | Value |
        |-------|-------|
        | Platform | iOS |
        | OS Version | iOS \(iosVersion) |
        | Minis Version | \(appVersion) (build \(build)) |
        | Device Model | \(device) |

        ## 🔁 Steps to Reproduce

        1.
        2.
        3.

        ## ❌ Error Details

        ```
        paste error here
        ```

        ## ✅ Expected Behavior



        ## 🗂️ Additional Information

        """

        var components = URLComponents(string: "https://github.com/OpenMinis/OpenMinis/issues/new")
        components?.queryItems = [
            URLQueryItem(name: "template", value: "bug_report.md"),
            URLQueryItem(name: "title", value: "[Bug] "),
            URLQueryItem(name: "body", value: body),
        ]
        return components?.url
    }

    /// Returns the hardware model identifier, e.g. "iPhone16,2".
    /// `UIDevice.current.model` returns the generic "iPhone" / "iPad" and
    /// isn't useful in a bug report, so we fall back to utsname.
    private static func machineIdentifier() -> String {
        var sys = utsname()
        uname(&sys)
        let id = withUnsafePointer(to: &sys.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: Int(_SYS_NAMELEN)) {
                String(cString: $0)
            }
        }
        return id.isEmpty ? UIDevice.current.model : id
    }
}

#Preview {
    ContentView()
}

/// Rotate-and-pause animation for the "syncing" title indicator. Period
/// scales with the active throttle so the user can tell at a glance how
/// fast sync is going: full speed = quick pulses, background = slow.
///
/// Loop is driven by a `.task` whose Task is cancelled automatically when
/// the view disappears. Earlier versions used `onAppear { tick() }` with
/// a self-rescheduling `DispatchQueue.main.asyncAfter` chain, which never
/// stopped the old chain — so each tab-switch back to home spawned a new
/// chain on top of the previous one, doubling/quadrupling perceived
/// rotation speed until the view tree rebuilt.
private struct PulseRotateIcon: View {
    @State private var rotation: Double = 0
    var body: some View {
        Image(systemName: "arrow.triangle.2.circlepath")
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(.primary)
            .rotationEffect(.degrees(rotation))
            .task { await pulseLoop() }
    }
    /// Reads SyncCore's currentSendDelay (5s sync sheet → 60s background)
    /// and converts to an animation cadence: animation phase ≈ delay/4,
    /// hold phase ≈ delay/4. Clamped so it never feels frozen or frantic.
    private func currentPeriod() -> (anim: TimeInterval, hold: TimeInterval) {
        let delay: TimeInterval
        if #available(iOS 17.0, *) {
            delay = SyncCore.shared.currentSendDelay
        } else {
            delay = 10
        }
        // 5s → 1.0s anim + 1.0s hold → ~2s period (active)
        // 15s → 1.5s anim + 1.5s hold
        // 30s → 2.0s anim + 2.0s hold
        // 60s → 2.5s anim + 2.5s hold (slow)
        let anim = max(0.8, min(2.5, delay / 12 + 0.5))
        let hold = anim
        return (anim, hold)
    }
    @MainActor
    private func pulseLoop() async {
        while !Task.isCancelled {
            let (anim, hold) = currentPeriod()
            withAnimation(.easeInOut(duration: anim)) {
                rotation += 180
            }
            let total = UInt64((anim + hold) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: total)
        }
    }
}

/// Transient confirmation banner shown after a Force Sync runs against
/// a multi-session selection. Floats at the top of the home view, fades
/// in and out over ~4s. Mirrors the iOS system "Now playing" pill.
private struct ForceSyncToastBanner: View {
    let text: String
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "icloud.and.arrow.up")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
            Text(text)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.accentColor.opacity(0.92))
        )
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
        .padding(.horizontal, 16)
        .frame(maxWidth: 480)
    }
}

