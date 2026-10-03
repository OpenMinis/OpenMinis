import AppIntents

/// Registers app shortcuts so they appear in Shortcuts with zero user setup.
///
/// Phrase localization: the English phrases below are the KEYS. Localized
/// Siri trigger phrases (zh-Hans/zh-Hant, e.g. "问问Minis") live in
/// `src/ios/<locale>.lproj/AppShortcuts.strings` — the table name
/// AppShortcuts is what the AppIntents runtime looks up. Legacy .strings
/// (not .xcstrings) because the deployment target is iOS 16 and Xcode
/// rejects AppShortcuts.xcstrings below iOS 17. Add new phrases here AND
/// to every AppShortcuts.strings; keys must match exactly with
/// `\(.applicationName)` spelled `${applicationName}` in the tables.
///
/// [T-ios16-appshortcut-null-init] MUST stay callable on iOS 16 — no
/// type-level `@available(iOS 17.0, *)`, and every `AppShortcut` below must
/// resolve to the iOS 16.0 initializer.
///
/// This type used to be gated to iOS 17 because the code called
/// `AppShortcut(intent:phrases:shortTitle:systemImageName:)` with NON-optional
/// arguments, and that overload is iOS 17.0+. The gate did not keep iOS 16 out:
/// a type-level `@available` is a compile-time rule, but the protocol
/// conformance record is still in the binary, and the AppIntents runtime finds
/// it and calls `appShortcuts` whenever the system resolves an App Shortcut
/// (`AppContext.fetchAction(for:)`, background, no user action needed). On
/// iOS 16 the weak-linked iOS 17 initializer is NULL, so the call jumped to
/// address 0 — TestFlight crash bucket "???: 0x0", 9 reports on stock iOS
/// 16.0–16.7 devices across 1.12–1.14.
///
/// The fix is removing that gate. The source is unchanged, but with the type no
/// longer iOS 17-only the compiler may not choose the iOS 17 overload (that
/// would be an availability error), so it resolves every call to the older
/// `init(intent:phrases:shortTitle:systemImageName:)` taking `LocalizedStringResource?`
/// / `String?`. That one is available from iOS 16.0 and carries the same two
/// values, so iOS 17+ keeps the short titles and icons, and iOS 16 no longer
/// calls a missing symbol. Verified on the object file: the only imported
/// `AppShortcut.init` is the optional-parameter one.
///
/// Do not "fix" this by making the arguments explicit optionals: Apple's
/// build-time App Intents metadata extractor rejects
/// `LocalizedStringResource?("…")` (it needs a literal or a direct initializer
/// call) and `systemImageName` is `_const`. Branching with `if #available`
/// inside the builder is not an option either: that needs AppShortcutsBuilder
/// methods that are themselves iOS 17.4+.
struct MinisShortcutsProvider: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        // Siri-facing "ask Minis" entry — opens the app and lands in the
        // conversation. Note: iOS App Intents cannot capture free-form trailing
        // text from the phrase itself (e.g. "…the weather today"); Siri collects
        // the prompt via the parameter's requestValueDialog follow-up. The
        // phrases below are the invocation triggers, not the prompt.
        AppShortcut(
            intent: AskMinisIntent(),
            phrases: [
                "Ask \(.applicationName)",
                "Ask \(.applicationName) a question",
                "Talk to \(.applicationName)",
                "New \(.applicationName) chat",
            ],
            shortTitle: "Ask Minis",
            systemImageName: "sparkles"
        )
        AppShortcut(
            intent: QuickTaskIntent(),
            phrases: [
                "Run a \(.applicationName) quick task",
                "Use \(.applicationName) quick task",
            ],
            shortTitle: "Quick Task",
            systemImageName: "bolt.fill"
        )
        AppShortcut(
            intent: SendPromptIntent(),
            phrases: [
                "Send a prompt to \(.applicationName)",
                "Ask \(.applicationName) something",
                "Start a \(.applicationName) task",
            ],
            shortTitle: "Send Prompt",
            systemImageName: "message.fill"
        )
        AppShortcut(
            intent: GetSessionStatusIntent(),
            phrases: [
                "Get \(.applicationName) session status",
                "Check \(.applicationName) task",
            ],
            shortTitle: "Session Status",
            systemImageName: "info.circle.fill"
        )
        AppShortcut(
            intent: ListSessionsIntent(),
            phrases: [
                "List \(.applicationName) sessions",
                "Show \(.applicationName) chats",
            ],
            shortTitle: "List Sessions",
            systemImageName: "list.bullet"
        )
        AppShortcut(
            intent: FollowUpSessionIntent(),
            phrases: [
                "Follow up a \(.applicationName) session",
                "Continue a \(.applicationName) session",
            ],
            shortTitle: "Follow Up",
            systemImageName: "arrowshape.turn.up.left.fill"
        )
        // RetryRunIntent is not registered here because its
        // @IntentParameterDependency causes an iOS 16 launch crash
        // (Swift metadata resolution). It remains available in Shortcuts
        // via the "All Actions" list.
        AppShortcut(
            intent: OpenSessionIntent(),
            phrases: [
                "Open a \(.applicationName) session",
            ],
            shortTitle: "Open Session",
            systemImageName: "arrow.up.right.square"
        )
        // [T-ios-remove-open-webapp-shortcut-intent] OpenWebAppIntent removed —
        // the Home-Screen WebApp tile path was replaced by another mechanism,
        // so the Shortcuts/AppIntents action is no longer registered.
    }
}
