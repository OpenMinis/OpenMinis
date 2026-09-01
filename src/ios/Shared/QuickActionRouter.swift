//
//  QuickActionRouter.swift
//  MinisApp
//
//  Home-screen Quick Actions (long-press app icon → "New Chat",
//  "Chat with Voice", "Chat with Camera").
//
//  Two responsibilities:
//   1. Register the three `UIApplicationShortcutItem`s once at app
//      launch (dynamic registration so they can be added / removed
//      without touching Info.plist).
//   2. Receive `UIApplicationShortcutItem` events from the AppDelegate
//      (both cold-launch via launchOptions and warm tap via
//      `application(_:performActionFor:completionHandler:)`) and route
//      them to the rest of the app:
//        - All three open a new chat session by posting
//          `.newChatRequested` (the existing ContentView observer).
//        - Voice + Camera also stash a pending `ChatLaunchAction` that
//          `AIChatView.onAppear` consumes once to start speech /
//          present the camera sheet.
//

import Foundation
import UIKit

private let logger = AppLogger(category: "QuickAction")

/// One-shot action consumed by `AIChatView` when a new chat opens from
/// a Home Screen shortcut.
enum ChatLaunchAction: Equatable {
    case startVoice
    case openCamera
}

@MainActor
final class QuickActionRouter: ObservableObject {
    static let shared = QuickActionRouter()

    /// Monotonically incremented whenever a shortcut requests a new
    /// chat. ContentView observes this so it can react to cold-launch
    /// shortcuts where `.newChatRequested` may post before the
    /// `.onReceive` subscription is attached. Notifications + this
    /// counter form a belt-and-braces wakeup path.
    @Published private(set) var newChatTrigger: Int = 0

    private init() {}

    // MARK: - Shortcut item types (also referenced from AppDelegate)

    enum ShortcutType {
        static let newChat = "me.wsen.minis.newChat"
        static let voiceChat = "me.wsen.minis.voiceChat"
        static let cameraChat = "me.wsen.minis.cameraChat"
    }

    /// Install the dynamic shortcut items on the running app. Idempotent —
    /// can be called multiple times without growing the list. We always
    /// overwrite so removing / re-ordering only requires editing one
    /// place.
    static func registerShortcutItems() {
        let items: [UIApplicationShortcutItem] = [
            UIApplicationShortcutItem(
                type: ShortcutType.newChat,
                localizedTitle: AppLocalized("shortcut.newChat.title"),
                localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(systemImageName: "bubble.left.and.bubble.right"),
                userInfo: nil
            ),
            UIApplicationShortcutItem(
                type: ShortcutType.voiceChat,
                localizedTitle: AppLocalized("shortcut.voiceChat.title"),
                localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(systemImageName: "mic.fill"),
                userInfo: nil
            ),
            UIApplicationShortcutItem(
                type: ShortcutType.cameraChat,
                localizedTitle: AppLocalized("shortcut.cameraChat.title"),
                localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(systemImageName: "camera.fill"),
                userInfo: nil
            ),
        ]
        UIApplication.shared.shortcutItems = items
        logger.info("registerShortcutItems installed=\(items.count)")
    }

    /// Entry point called by `AppDelegate` for both cold-launch
    /// shortcut items (via launchOptions) and warm taps. Returns true
    /// when the shortcut type was recognized, so the system can mark
    /// the request as handled.
    @discardableResult
    func handle(_ shortcutItem: UIApplicationShortcutItem) -> Bool {
        logger.info("handle shortcut type=\(shortcutItem.type)")
        switch shortcutItem.type {
        case ShortcutType.newChat:
            QuickActionWorkflow.shared.reset(reason: "newChat shortcut — no follow-up action")
            postNewChat()
            return true
        case ShortcutType.voiceChat:
            QuickActionWorkflow.shared.start(.startVoice)
            postNewChat()
            return true
        case ShortcutType.cameraChat:
            QuickActionWorkflow.shared.start(.openCamera)
            postNewChat()
            return true
        default:
            logger.warning("unknown shortcut type: \(shortcutItem.type)")
            return false
        }
    }

    private func postNewChat() {
        // Bump the @Published counter — ContentView's `.onChange(of:
        // newChatTrigger)` + `.onAppear` belt-and-braces drives the
        // session creation. Do NOT also post `.newChatRequested`: that
        // legacy notification is consumed by a separate observer in
        // ContentView, so posting both makes `handleNewChatRequest`
        // run twice with two different fresh session ids — the
        // workflow's attached target then doesn't match the AIChatView
        // that actually ends up visible.
        newChatTrigger &+= 1
        logger.info("postNewChat trigger=\(newChatTrigger)")
    }
}
