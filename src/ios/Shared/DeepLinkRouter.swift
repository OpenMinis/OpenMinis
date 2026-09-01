import Foundation

private let deepLinkLog = AppLogger(category: "DeepLink")

extension Notification.Name {
    /// Posted by `DeepLinkRouter` when `minis://open?session=…&path=…` lands
    /// from the openminis.app launcher. `userInfo["shortcut"]` is a
    /// `WebAppShortcut` reconstructed from the deep-link params; not
    /// necessarily persisted. (Relocated here from the removed
    /// OpenWebAppIntent.swift — T-ios-remove-open-webapp-shortcut-intent.)
    static let openWebAppDeepLink = Notification.Name("openWebAppDeepLink")
}

/// Parses `minis://` URLs into navigation actions and dispatches them
/// to the relevant coordinator. Mirrors the Android `DeepLinkHandler`
/// route table at `src/android/.../deeplink/DeepLinkHandler.kt`.
///
/// Supported URLs (all aliases match the Android side):
///   minis://share
///   minis://views/alarm
///   minis://open_terminal[?init_command=…]
///   minis://open?session=<sid>&path=<scope-prefixed-path>  (openminis.app launcher round-trip)
///   minis://session/<id>      (legacy singular alias)
///   minis://sessions/<id>     (canonical — matches minis-sessions-cli)
///   minis://settings
///   minis://settings/providers[/<instanceId>]
///   minis://settings/model-groups[/<groupId>]   (alias: model_groups)
///   minis://settings/usage                      (alias: usage-stats, usage_stats)
///   minis://settings/skills
///   minis://settings/memory
///   minis://settings/storage
///   minis://settings/mount-external             (alias: mount_external, mounts, mounted-folders, mounted_folders)
///   minis://settings/shared-folders             (alias: shared_folders)
///   minis://settings/logs
///   minis://settings/appearance
///   minis://settings/background
///   minis://settings/about
///   minis://settings/permissions
///   minis://settings/environments[?create_key=…&create_value=…&create_note=…]
///   minis://settings/rootfs                     (alias: mirrors, rootfs-management, rootfs_management)
///
/// Unknown settings paths fall back to the Settings home rather than
/// failing — same as Android — so an LLM-generated link can never
/// strand the user.
enum DeepLinkRouter {

    @MainActor
    static func handle(url: URL, shareCoordinator: ShareCoordinator) {
        guard url.scheme == "minis", let host = url.host else {
            deepLinkLog.info("ignored — non-minis or missing host: \(url.absoluteString)")
            return
        }
        let coord = DeepLinkCoordinator.shared

        switch host {
        case "share":
            // Funnel through ShareCoordinator so any leftover
            // fullScreenCover (gallery / WebApp / camera) is dismissed
            // before the share UI tries to surface.
            shareCoordinator.raisePendingShare()

        case "views":
            // Only one view route today; future ones land here.
            if url.path == "/alarm" {
                coord.showAlarmList = true
            }

        case "open_terminal":
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
            coord.terminalInitCommand = components?.queryItems?.first(where: { $0.name == "init_command" })?.value
            coord.showTerminal = true

        case "open":
            handleWebAppLauncherReturn(url: url)

        case "session", "sessions":
            // `session/<id>` (legacy singular) and `sessions/<id>` (canonical,
            // matches minis-sessions-cli) both route to the same place. Post
            // `.openSessionFromIntent` so we reuse the same code path the
            // App Intents flow uses — which already pops the navigation
            // stack back to root before opening the target session.
            let id = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !id.isEmpty else {
                deepLinkLog.info("\(host) URL missing id: \(url.absoluteString)")
                return
            }
            NotificationCenter.default.post(
                name: .openSessionFromIntent,
                object: nil,
                userInfo: ["sessionId": id]
            )

        case "settings":
            handleSettings(url: url, coord: coord)

        default:
            deepLinkLog.info("unknown host '\(host)': \(url.absoluteString)")
        }
    }

    // MARK: - Settings sub-router

    @MainActor
    private static func handleSettings(url: URL, coord: DeepLinkCoordinator) {
        let segments = url.path
            .split(separator: "/", omittingEmptySubsequences: true)
            .map { String($0) }
        guard let head = segments.first else {
            // bare `minis://settings`
            coord.pendingSettingsTarget = .home
            return
        }
        let arg = segments.count > 1 ? segments[1] : nil
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)

        switch head {
        case "providers":
            coord.pendingSettingsTarget = (arg?.isEmpty == false)
                ? .providerDetail(instanceId: arg!)
                : .providers

        case "model-groups", "model_groups":
            coord.pendingSettingsTarget = (arg?.isEmpty == false)
                ? .modelGroupDetail(groupId: arg!)
                : .modelGroups

        case "usage", "usage-stats", "usage_stats":
            coord.pendingSettingsTarget = .usage

        case "skills":
            coord.pendingSettingsTarget = .skills

        // [T-ios-assistant-header-open-soul] Previously fell through to the
        // `default` branch and landed on Settings home, even though the Soul
        // screen exists — so a `minis://settings/soul` link (or the agent
        // generating one) quietly under-delivered.
        case "soul":
            coord.pendingSettingsTarget = .soul

        // [T-mcp-oauth-deeplink] minis://settings/mcp-servers/<serverId> —
        // jump straight to the server's edit form (Authorize button). The
        // AUTH_REQUIRED error from minis-mcp-cli embeds this link. Server
        // names may be percent-encoded; url.path already decodes them.
        case "mcp-servers", "mcp_servers", "mcp":
            coord.pendingSettingsTarget = (arg?.isEmpty == false)
                ? .mcpServerDetail(serverId: arg!)
                : .mcpIntegrations

        case "memory":
            coord.pendingSettingsTarget = .memory

        case "storage":
            coord.pendingSettingsTarget = .storage

        case "mount-external", "mount_external", "mounts",
             "mounted-folders", "mounted_folders":
            coord.pendingSettingsTarget = .mountedFolders

        case "shared-folders", "shared_folders":
            coord.pendingSettingsTarget = .sharedFolders

        case "logs":
            // Optional ?tab=… selects the segmented control on the
            // Logs screen. Currently recognized: "logs" (default),
            // "config-audit". Unknown values fall back to the default.
            coord.pendingLogsTab = components?.queryItems?
                .first(where: { $0.name == "tab" })?.value
            coord.pendingSettingsTarget = .logs

        case "appearance":
            coord.pendingSettingsTarget = .appearance

        case "background":
            // [T-settings-focus-highlight] Optional `?focus=key=true,key2` —
            // parsing + decision logic lives in DeepLinkCoordinator so the
            // router (and every settings screen) stays free of `key=value` rules.
            coord.setFocus(rawQueryValue: components?.queryItems?
                .first(where: { $0.name == "focus" })?.value)
            coord.pendingSettingsTarget = .background

        case "about":
            coord.pendingSettingsTarget = .about

        case "permissions":
            coord.pendingSettingsTarget = .permissions

        case "environments":
            // `create_key` is the only required param. Missing
            // `create_value`/`create_note` default to empty so a link
            // like `…?create_key=GH_TOKEN&create_value=` (present-but-
            // empty) still opens the prefilled form. Without
            // `create_key`, plain navigation to the env vars list.
            let key = components?.queryItems?.first(where: { $0.name == "create_key" })?.value
            if let key, !key.isEmpty {
                let value = components?.queryItems?.first(where: { $0.name == "create_value" })?.value ?? ""
                let note = components?.queryItems?.first(where: { $0.name == "create_note" })?.value ?? ""
                coord.pendingEnvVarCreate = .init(key: key, value: value, note: note)
            }
            coord.pendingSettingsTarget = .environments

        case "rootfs", "rootfs-management", "rootfs_management",
             "mirrors":
            // RootfsManagementView lives in its own sheet on iOS, not
            // inside the SettingsSheet stack. Mirrors live as a section
            // inside it, so /mirrors and /rootfs route to the same
            // destination, matching the Android implementation.
            coord.pendingRootfsManagement = true

        default:
            // Unknown path → land on Settings home instead of failing,
            // so an LLM-generated link still gets the user *somewhere*
            // they can browse from. Mirrors Android.
            deepLinkLog.info("unknown settings path '\(head)' — falling back to Settings home")
            coord.pendingSettingsTarget = .home
        }
    }

    // MARK: - openminis.app launcher round-trip

    /// Parses a `minis://open?session=…&path=…` URL fired by the
    /// openminis.app launcher when the pinned home-screen tile is
    /// launched in standalone mode. The `path` query is scope-prefixed
    /// so we can recover `(scope, scopeContext, htmlPath)` without
    /// looking anything up:
    ///
    ///   path=attachments/<rest>      → sessionAttachment(ctx=session)
    ///   path=workspace/<rest>        → sessionWorkspace(ctx=session)
    ///   path=shared:<rest>           → shared
    ///   path=mount:<uuid>/<rest>     → mount(ctx=<uuid>)
    ///
    /// Builds a transient `WebAppShortcut` (no persistence) and posts
    /// `.openWebAppDeepLink` so the SwiftUI root presents the immersive
    /// WebView screen.
    @MainActor
    private static func handleWebAppLauncherReturn(url: URL) {
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let session = components?.queryItems?.first(where: { $0.name == "session" })?.value
        guard let rawPath = components?.queryItems?.first(where: { $0.name == "path" })?.value,
              !rawPath.isEmpty else {
            deepLinkLog.warning("minis://open missing path")
            return
        }

        let scope: WebAppPathScope
        let ctx: String?
        let htmlPath: String

        if rawPath.hasPrefix("attachments/") {
            guard let sid = session, !sid.isEmpty else {
                deepLinkLog.warning("minis://open path=attachments/… missing session")
                return
            }
            scope = .sessionAttachment
            ctx = sid
            htmlPath = String(rawPath.dropFirst("attachments/".count))
        } else if rawPath.hasPrefix("workspace/") {
            guard let sid = session, !sid.isEmpty else {
                deepLinkLog.warning("minis://open path=workspace/… missing session")
                return
            }
            scope = .sessionWorkspace
            ctx = sid
            htmlPath = String(rawPath.dropFirst("workspace/".count))
        } else if rawPath.hasPrefix("shared:") {
            scope = .shared
            ctx = nil
            htmlPath = String(rawPath.dropFirst("shared:".count))
        } else if rawPath.hasPrefix("mount:") {
            // mount:<uuid>/<rest>
            let rest = rawPath.dropFirst("mount:".count)
            guard let slash = rest.firstIndex(of: "/") else {
                deepLinkLog.warning("minis://open path=mount:… missing /<rest>")
                return
            }
            scope = .mount
            ctx = String(rest[..<slash])
            htmlPath = String(rest[rest.index(after: slash)...])
        } else {
            deepLinkLog.warning("minis://open unknown path prefix in \(rawPath)")
            return
        }

        let title = url.fragment.flatMap { $0.removingPercentEncoding } ?? ""
        let shortcut = WebAppShortcut(
            id: UUID().uuidString,
            htmlPath: htmlPath,
            pathScope: scope,
            scopeContext: ctx,
            title: title,
            iconRef: .preset("globe"),
            iconCachePath: nil,
            createdAt: Date(),
            sourceSessionId: (scope == .sessionAttachment || scope == .sessionWorkspace) ? ctx : nil
        )

        deepLinkLog.info("minis://open scope=\(scope.rawValue) ctx=\(ctx ?? "nil") htmlPath=\(htmlPath)")
        // Dismiss any leftover fullScreenCover (image gallery, in-chat
        // web preview, camera, etc.) BEFORE attempting to present the
        // WebApp. iOS only allows one fullScreenCover per host view at a
        // time, so a present-while-another-is-up silently no-ops.
        NotificationCenter.default.post(name: .dismissAllImmersivePresentations, object: nil)
        Task { @MainActor in
            // 350ms covers the standard fullScreenCover dismiss animation
            // (~300ms) with a small safety margin.
            try? await Task.sleep(nanoseconds: 350_000_000)
            NotificationCenter.default.post(
                name: .openWebAppDeepLink,
                object: nil,
                userInfo: ["shortcut": shortcut]
            )
        }
    }
}
