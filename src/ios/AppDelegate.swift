//
//  AppDelegate.swift
//  MinisApp
//
//  Minimal UIApplicationDelegate stub mounted via
//  `@UIApplicationDelegateAdaptor` so we can receive
//  `UIApplicationShortcutItem` events. SwiftUI's `App` lifecycle does
//  not surface Home-Screen Quick Actions on its own — only the
//  legacy UIKit delegate path delivers them reliably across cold
//  launch (via launchOptions) AND warm tap (via
//  `application(_:performActionFor:completionHandler:)`).
//
//  Everything else (URL handling, scene phase, etc.) continues to
//  flow through the SwiftUI App lifecycle — this class only forwards
//  shortcut events to `QuickActionRouter`.
//

import CoreLocation
import UIKit

private let logger = AppLogger(category: "AppDelegate")

final class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        if #available(iOS 17.0, *) {
            CLBackgroundActivitySession().invalidate()
        }

        // [T-notification-tap-vs-launch-session] The UNUserNotificationCenter
        // delegate must be in place BEFORE didFinishLaunching returns, or iOS
        // never delivers a cold-launch notification tap to didReceive — the
        // app then falls through to the Launch Session preference and opens
        // the wrong (new) session. The .onAppear registration in MinisApp
        // remains as an idempotent backstop.
        ShortcutNotificationDelegate.shared.register()

        // Refresh the dynamic shortcut list every cold launch. The
        // items themselves are stable, but their localized titles
        // depend on the current `String(localized:)` resolution which
        // can change when the user switches in-app language.
        Task { @MainActor in
            QuickActionRouter.registerShortcutItems()
        }
        logger.info("didFinishLaunching (scene-based; shortcut routing happens in SceneDelegate)")
        // In a scene-based SwiftUI app, `launchOptions[.shortcutItem]`
        // is NOT populated for cold-launch shortcuts — the item is
        // delivered to `scene(_:willConnectTo:options:)` via
        // `UISceneConnectionOptions.shortcutItem` instead. Warm-tap
        // shortcuts are likewise delivered to
        // `windowScene(_:performActionFor:completionHandler:)` rather
        // than the UIApplication delegate. See SceneDelegate below.
        return true
    }

    /// Tell UIKit to instantiate our `SceneDelegate` for every window
    /// scene. SwiftUI still owns the actual UIWindow + root hosting
    /// controller; this SceneDelegate only handles shortcut routing
    /// (and could add other scene-scoped hooks later).
    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

/// Receives Home Screen Quick Action events in a scene-based SwiftUI
/// app. Mounted via `AppDelegate.configurationForConnecting`.
///
/// Two delivery paths:
///   - Cold launch: `scene(_:willConnectTo:options:)` carries the
///     tapped item in `connectionOptions.shortcutItem`.
///   - Warm tap:    `windowScene(_:performActionFor:completionHandler:)`
///     is invoked while the scene is already attached.
///
/// Both paths forward to `QuickActionRouter.shared.handle`, which
/// bumps `newChatTrigger` (subscribed by ContentView via @ObservedObject)
/// and also posts `.newChatRequested` as a redundancy.
final class SceneDelegate: NSObject, UIWindowSceneDelegate {

    /// Required by UIWindowSceneDelegate. SwiftUI manages the actual
    /// window contents — we just record a reference for potential
    /// later use and consume the cold-launch shortcut item.
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        if let item = connectionOptions.shortcutItem {
            logger.info("scene willConnectTo: cold-launch shortcut type=\(item.type)")
            // Bump synchronously on the main actor. ContentView's
            // `.onAppear` belt-and-braces will route any unconsumed
            // trigger as soon as it mounts.
            Task { @MainActor in
                _ = QuickActionRouter.shared.handle(item)
            }
        } else {
            logger.info("scene willConnectTo (no shortcut)")
        }
        // [T-ios-ipad-airdrop-provider-json-no-import] Cold-launch URL
        // delivery. When the app is NOT already running and the user AirDrops
        // (or "Open in Minis") a file, the file:// URL arrives here in
        // `connectionOptions.urlContexts` — NOT via SwiftUI `.onOpenURL`.
        // Providing this custom UIWindowSceneDelegate suppresses SwiftUI's
        // automatic URL-context bridging, so without forwarding these the
        // Provider-JSON import prompt never appears (the iPad-AirDrop symptom;
        // iPhone happened to still bridge in some launch paths). Route through
        // the SAME pipeline `.onOpenURL` uses.
        if !connectionOptions.urlContexts.isEmpty {
            Self.handleURLContexts(connectionOptions.urlContexts, phase: "willConnectTo")
        }
    }

    func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        logger.info("windowScene performActionFor type=\(shortcutItem.type)")
        Task { @MainActor in
            let handled = QuickActionRouter.shared.handle(shortcutItem)
            completionHandler(handled)
        }
    }

    // [T-ios-ipad-airdrop-provider-json-no-import] Warm URL delivery. When the
    // app is ALREADY running, AirDrop / "Open in Minis" delivers the file URL
    // here. A custom scene delegate must implement this or the URL is dropped
    // (SwiftUI's `.onOpenURL` no longer auto-receives it). Same pipeline as the
    // cold-launch path above and as MinisApp's `.onOpenURL`.
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        Self.handleURLContexts(URLContexts, phase: "openURLContexts")
    }

    /// Route each opened URL through the shared import pipeline, mirroring
    /// MinisApp's `.onOpenURL`: a local file goes to `ExternalFileImporter`
    /// (which detects Provider-export JSON and prompts import-vs-attach), and
    /// anything else falls through to `DeepLinkRouter`. Uses
    /// `ShareCoordinator.shared` — the exact instance MinisApp injects into the
    /// environment — so `raisePendingShare()` drives the same SwiftUI flow.
    private static func handleURLContexts(_ contexts: Set<UIOpenURLContext>, phase: String) {
        for context in contexts {
            let url = context.url
            logger.info("[Share] scene \(phase) URL: \(url.absoluteString)")
            Task { @MainActor in
                let coordinator = ShareCoordinator.shared
                // A .minisbak must reach the restore flow, not the attachment
                // pipeline — canIngest() accepts any file URL, so this has to
                // be checked first.
                if BackupOpenRouter.handle(url) {
                    // handled
                } else if ExternalFileImporter.canIngest(url) {
                    ExternalFileImporter.ingest(url, into: coordinator)
                } else {
                    DeepLinkRouter.handle(url: url, shareCoordinator: coordinator)
                }
            }
        }
    }

    func applicationWillTerminate(_ application: UIApplication) {
        CrashReporter.shared.onWillTerminate()
        // [T-ios-bgactivitysession-leak] Retract the background location session
        // + Live Activity on a clean exit so the system location indicator
        // doesn't stay pinned to the Dynamic Island after the app is gone.
        MainActor.assumeIsolated {
            BackgroundKeepAliveManager.shared.prepareForTermination()
        }
    }
}
