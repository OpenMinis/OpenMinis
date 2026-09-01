//
//  ChatAccessibilityAnnouncer.swift
//  MinisApp
//
//  [T-ios-voiceover-announce] Speaks a short VoiceOver announcement when an
//  agent turn ends.
//
//  Why this exists: streaming a reply is a purely visual event. The text grows,
//  `isProcessing` flips back to false, and nothing tells VoiceOver anything —
//  so a blind user has to keep sweeping the message list to guess whether the
//  reply is done. App Store feedback called this out directly.
//
//  Why it lives in the view layer rather than in AIChatViewModel: posting a
//  UIAccessibility notification is a UIKit side effect, and the view model's
//  `isProcessing` observer is already load-bearing for sync deferral, hang
//  logging and snapshot re-application. Bolting UI behaviour onto that observer
//  would couple the view model to UIKit and make the ordering of those existing
//  effects harder to reason about. Instead this is a `.onChange` observer
//  attached to the chat view: it sees the same published value, runs on the
//  main actor by construction, and disappears with the view.
//

import SwiftUI
import UIKit

/// Why an agent turn ended. Drives which announcement is spoken.
///
/// Kept separate from the announcement text so the decision (what happened)
/// and the wording (how to say it) can be tested and localized independently.
enum ChatTurnOutcome {
    case finished
    case stopped
    case failed
}

enum ChatAccessibilityAnnouncer {

    /// Speak the outcome of a finished agent turn, if VoiceOver is listening.
    ///
    /// No-ops when VoiceOver is off: `UIAccessibility.post` is harmless in that
    /// case, but building the localized string and touching UIKit for a user
    /// who cannot hear it is pointless work on the main thread during the
    /// already-busy end-of-stream moment.
    @MainActor
    static func announceTurnEnded(_ outcome: ChatTurnOutcome) {
        guard UIAccessibility.isVoiceOverRunning else { return }

        let message: String
        switch outcome {
        case .finished:
            message = AppLocalized("Reply complete",
                             comment: "VoiceOver announcement when the assistant finishes replying")
        case .stopped:
            message = AppLocalized("Generation stopped",
                             comment: "VoiceOver announcement when the user stops generation")
        case .failed:
            // Deliberately distinct from "Reply complete": announcing success
            // on a failed turn is worse than saying nothing, because the user
            // would go looking for an answer that is not there.
            message = AppLocalized("Reply failed",
                             comment: "VoiceOver announcement when the assistant reply ends in an error")
        }

        UIAccessibility.post(notification: .announcement, argument: message)
    }
}

// MARK: - View modifier

private struct ChatTurnAnnouncementModifier: ViewModifier {
    /// The value being watched — the view model's `isProcessing`.
    let isProcessing: Bool
    /// Evaluated only at the moment a turn ends, to classify the outcome.
    let outcomeAtEnd: () -> ChatTurnOutcome

    func body(content: Content) -> some View {
        content.onChange(of: isProcessing) { processing in
            // Only the true -> false edge is a turn ending. `onChange` fires
            // once per distinct value, so a single end produces a single
            // announcement; re-renders in between do not re-fire it.
            guard !processing else { return }
            ChatAccessibilityAnnouncer.announceTurnEnded(outcomeAtEnd())
        }
    }
}

extension View {
    /// Announce to VoiceOver when an agent turn ends.
    ///
    /// - Parameters:
    ///   - isProcessing: the view model's streaming flag.
    ///   - outcomeAtEnd: classifies the turn, evaluated lazily at the end edge
    ///     so it reads the final state (cancel flag, error) rather than a value
    ///     captured when the view was built.
    func announceChatTurnEnd(
        isProcessing: Bool,
        outcomeAtEnd: @escaping () -> ChatTurnOutcome
    ) -> some View {
        modifier(ChatTurnAnnouncementModifier(isProcessing: isProcessing,
                                              outcomeAtEnd: outcomeAtEnd))
    }
}
