import SwiftUI

/// A transient message overlaid on the current screen.
///
/// Errors in these flows used to be a row appended to the bottom of a Form.
/// In a directory listing with hundreds of entries that row is far below the
/// fold, so a failed "New Folder" looked like nothing happened at all — the
/// user taps again, and again. An overlay is visible wherever the scroll
/// position happens to be.
///
/// Deliberately not an alert: an alert demands a dismissal tap for something
/// the user can only respond to by trying again, and stacking one over the
/// browser hides the very folder they were looking at.
struct BackupHUD: View {
    let text: String
    var isError: Bool = true

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: isError ? "exclamationmark.triangle.fill"
                                      : "checkmark.circle.fill")
                .foregroundStyle(isError ? .orange : .green)
            Text(text)
                .font(.footnote)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .shadow(radius: 8, y: 2)
        .padding(.horizontal, 24)
        .transition(.move(edge: .top).combined(with: .opacity))
    }
}

extension View {
    /// Show `message` as a floating banner near the top of the screen.
    ///
    /// Bound to an optional so clearing the message dismisses it; the caller
    /// decides how long it lives.
    func backupHUD(_ message: Binding<String?>, isError: Bool = true) -> some View {
        overlay(alignment: .top) {
            if let text = message.wrappedValue {
                BackupHUD(text: text, isError: isError)
                    .padding(.top, 8)
                    .onAppear {
                        // Auto-dismiss: the message is informational, and a
                        // banner that needs manual clearing is another thing
                        // in the user's way.
                        Task {
                            try? await Task.sleep(nanoseconds: 4_000_000_000)
                            withAnimation { message.wrappedValue = nil }
                        }
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: message.wrappedValue)
    }
}
