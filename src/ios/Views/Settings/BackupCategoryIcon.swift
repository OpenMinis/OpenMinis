import SwiftUI

/// The badge shown next to a backup category.
///
/// Shared by the Backup and Restore tabs on purpose: the two screens list the
/// same categories, and a user who selected "Skills" with an orange puzzle
/// icon when backing up should recognise the same row when restoring. Before
/// this, Restore showed bare text and the correspondence had to be inferred
/// from the wording alone.
///
/// Lives in the view layer rather than on `BackupCategory` itself, which is
/// Foundation-only — the format model has no business knowing about Color.
///
/// The badge is a CIRCLE, matching every other row icon under Settings
/// (see `settingsIcon` in CloudSyncSettingsView). The backup screens shipped
/// with rounded squares, which read as a different kind of control sitting in
/// the same list.
/// A circular row badge for a backup ACTION (as opposed to a category).
///
/// The Restore tab's three "Choose…" rows shipped as bare `Label` glyphs —
/// thin outline icons with no badge — while every other row in Settings, and
/// every row on the Backup tab beside them, uses a filled circle. Sitting one
/// tap apart, the difference read as two different kinds of list rather than
/// two halves of one screen.
///
/// 21pt to match `settingsIcon`, which is the size the surrounding Settings
/// rows use; `BackupCategoryIcon` is deliberately larger (28pt) because those
/// rows carry a toggle and more vertical space.
struct BackupActionIcon: View {
    let systemName: String
    let tint: Color

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 9))
            .foregroundStyle(.white)
            .frame(width: 21, height: 21)
            .background(tint, in: Circle())
    }
}

struct BackupCategoryIcon: View {
    let category: BackupCategory

    var body: some View {
        Image(systemName: Self.symbol(for: category))
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 28, height: 28)
            .background(Self.tint(for: category), in: Circle())
    }

    static func symbol(for c: BackupCategory) -> String {
        switch c {
        case .chats: return "bubble.left.and.bubble.right.fill"
        case .sharedFiles: return "doc.fill"
        case .skills: return "puzzlepiece.fill"
        case .memory: return "brain.head.profile"
        case .providers: return "link"
        case .mcpServers: return "square.stack.3d.up.fill"
        case .voiceCorrections: return "waveform"
        case .environmentVariables: return "terminal.fill"
        }
    }

    static func tint(for c: BackupCategory) -> Color {
        switch c {
        case .chats: return .blue
        case .sharedFiles: return .indigo
        case .skills: return .orange
        case .memory: return .pink
        case .providers: return .teal
        case .mcpServers: return .cyan
        case .voiceCorrections: return .purple
        case .environmentVariables: return .brown
        }
    }
}
