import Foundation
import SwiftUI

// MARK: - Types

enum OffloadPermissionLevel: Int, CaseIterable {
    case bypass = 0
    case askOnce = 1
    case notAllowed = 2

    var displayName: String {
        switch self {
        case .bypass: return "Bypass"
        case .askOnce: return "Ask Once"
        case .notAllowed: return "Not Allowed"
        }
    }
}

enum PermissionResult {
    case allowed
    case denied(String)
}

struct PermissionRequest: Identifiable {
    let id: String
    let commandName: String
    let displayLabel: String
    let description: String
    /// The full shell command string, e.g. "apple-healthkit query --type steps"
    let fullCommand: String
    let continuation: CheckedContinuation<Bool, Never>

    /// Parse the command arguments into displayable key-value pairs.
    /// Handles patterns like: `command subcommand --key value --flag`.
    ///
    /// Only the FIRST shell command's tokens are surfaced — anything past a
    /// pipe / chain operator (`&&`, `||`, `;`, `|`) or a redirect (`>`,
    /// `>>`, `<`) belongs to a separate process or is plumbing the user
    /// shouldn't have to skim through to grant a permission. Without this
    /// gate the previous parser dumped the redirect target, the chained
    /// `python3 -c "..."` blob, and every word inside the quoted python
    /// snippet as `arg` rows, pushing the Allow / Deny buttons below the
    /// sheet's bottom edge.
    var parsedArguments: [(key: String, value: String)] {
        let parts = Self.firstCommandTokens(fullCommand)
        guard parts.count > 1 else { return [] }

        var result: [(key: String, value: String)] = []
        // First non-command token is the subcommand
        var idx = 1
        if idx < parts.count && !parts[idx].hasPrefix("-") {
            result.append((key: "Action", value: parts[idx]))
            idx += 1
        }
        while idx < parts.count {
            let token = parts[idx]
            if token.hasPrefix("--") || token.hasPrefix("-") {
                let key = token.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
                if idx + 1 < parts.count && !parts[idx + 1].hasPrefix("-") {
                    result.append((key: key, value: parts[idx + 1]))
                    idx += 2
                } else {
                    result.append((key: key, value: "true"))
                    idx += 1
                }
            } else {
                result.append((key: "arg", value: token))
                idx += 1
            }
        }
        return result
    }

    /// Whitespace-split the command but stop at the first shell separator so
    /// only the head command's tokens are returned. This is permissive about
    /// quoting (we don't try to honour `'...'` / `"..."` boundaries) — good
    /// enough for the permission-row preview where we just want to suppress
    /// the long tail past `&&`, redirects, etc. that confused users.
    private static let shellSeparators: Set<String> = [
        "&&", "||", ";", "|", ">", ">>", "<", "<<", "&",
    ]

    private static func firstCommandTokens(_ command: String) -> [String] {
        let raw = command.split(separator: " ").map(String.init)
        var head: [String] = []
        for token in raw {
            if shellSeparators.contains(token) { break }
            head.append(token)
        }
        return head
    }
}

// MARK: - Command Definitions

enum OffloadCommandCategory: String, CaseIterable {
    case privacy = "Privacy"
    case media = "Media"
    case system = "System"
}

struct OffloadCommandInfo {
    let name: String
    let displayLabel: String
    let description: String
    let category: OffloadCommandCategory
    /// Only privacy-sensitive commands appear in Settings
    let showInSettings: Bool
}

// MARK: - Manager

/// Fallback session id used when an offload permission check has no chat session
/// context (e.g. invocations outside the chat flow, or before a session id has
/// been assigned). Mirrors the Android constant `OFFLOAD_GLOBAL_SESSION_ID`
/// (commit 20d8e68) so per-session grants stay wire-compatible across platforms.
let OFFLOAD_GLOBAL_SESSION_ID = "offload-global"

@MainActor
final class OffloadPermissionManager: ObservableObject {
    static let shared = OffloadPermissionManager()

    static let allCommands: [OffloadCommandInfo] = [
        // Privacy — user-configurable
        .init(name: "apple-healthkit", displayLabel: "HealthKit", description: "Steps, heart rate, sleep, and other health records", category: .privacy, showInSettings: true),
        .init(name: "apple-calendar", displayLabel: "Calendar", description: "Events, schedules, and calendar details", category: .privacy, showInSettings: true),
        .init(name: "apple-reminders", displayLabel: "Reminders", description: "Tasks, due dates, and reminder lists", category: .privacy, showInSettings: true),
        .init(name: "apple-photos", displayLabel: "Photos", description: "Photos, videos, and album metadata", category: .privacy, showInSettings: true),
        .init(name: "apple-location", displayLabel: "Location", description: "Current GPS coordinates and location history", category: .privacy, showInSettings: true),
        .init(name: "apple-homekit", displayLabel: "HomeKit", description: "Smart home devices, rooms, and scenes", category: .privacy, showInSettings: true),
        .init(name: "apple-clipboard", displayLabel: "Clipboard", description: "Text and images copied to the clipboard", category: .privacy, showInSettings: true),
        // Media — no personal data, always bypass
        .init(name: "apple-speak", displayLabel: "Speak", description: "", category: .media, showInSettings: false),
        .init(name: "apple-speech", displayLabel: "Speech", description: "", category: .media, showInSettings: false),
        .init(name: "apple-player", displayLabel: "Player", description: "", category: .media, showInSettings: false),
        .init(name: "apple-media", displayLabel: "Media", description: "", category: .media, showInSettings: false),
        // System — no personal data, always bypass
        .init(name: "apple-device", displayLabel: "Device", description: "", category: .system, showInSettings: false),
        .init(name: "apple-notification", displayLabel: "Notification", description: "", category: .system, showInSettings: false),
        .init(name: "apple-alarm", displayLabel: "Alarm", description: "", category: .system, showInSettings: false),
        .init(name: "apple-open", displayLabel: "Open URL", description: "", category: .system, showInSettings: false),
        .init(name: "apple-maps", displayLabel: "Maps", description: "", category: .system, showInSettings: false),
        .init(name: "apple-weather", displayLabel: "Weather", description: "", category: .system, showInSettings: false),
        .init(name: "apple-nlp", displayLabel: "NLP", description: "", category: .system, showInSettings: false),
        .init(name: "apple-vision", displayLabel: "Vision", description: "", category: .system, showInSettings: false),
    ]

    @Published var pendingRequest: PermissionRequest?

    /// Per-session "Ask Once" grants: [sessionId: Set<commandName>]
    private var sessionGrants: [String: Set<String>] = [:]

    private let defaults = UserDefaults.standard
    private let logger = AppLogger(category: "OffloadPermission")

    private init() {}

    // MARK: - Storage

    private func defaultsKey(for command: String) -> String {
        "offloadPermission.\(command)"
    }

    func permissionLevel(for command: String) -> OffloadPermissionLevel {
        let raw = defaults.integer(forKey: defaultsKey(for: command))
        return OffloadPermissionLevel(rawValue: raw) ?? .bypass
    }

    func setPermissionLevel(_ level: OffloadPermissionLevel, for command: String) {
        defaults.set(level.rawValue, forKey: defaultsKey(for: command))
    }

    func setAllBypass() {
        for cmd in Self.allCommands {
            setPermissionLevel(.bypass, for: cmd.name)
        }
        sessionGrants.removeAll()
    }

    // MARK: - Command Extraction

    /// The offload command a shell invocation will actually run, or nil.
    ///
    /// [GH#242] Matched on the first token's BASENAME, because that is what the
    /// guest kernel dispatches on: `native_offload_lookup()` →
    /// `offload_find()` takes `strrchr(guest_path, '/')` and compares the
    /// trailing component against the registry
    /// (deps/ish/kernel/native_offload.c:186-196).
    ///
    /// This function used to compare the raw first token, so
    /// `/usr/local/bin/apple-healthkit` matched nothing and returned nil — and
    /// the caller reads nil as "not an offload command" and runs it unchecked
    /// (AIChatViewModel+ConcurrentTools.swift). The kernel then dispatched it
    /// anyway on basename, so an absolute path reached HealthKit / HomeKit /
    /// Photos / Location with no prompt, regardless of Bypass / Ask Once /
    /// Not Allowed. Verified on device: `/usr/local/bin/apple-clipboard` is a
    /// ZERO-byte placeholder yet returned real clipboard JSON, which is only
    /// possible via that native dispatch.
    ///
    /// Two matchers over one decision will drift again, so the rule here is
    /// deliberately the kernel's rule and nothing else. Registered names are
    /// bare (no slashes), so normalising the caller's side is sufficient.
    ///
    /// Scope: this closes path-based spellings (absolute, `./`, `../`, any
    /// prefix). It does NOT cover indirection where the first token is a
    /// different program — `sh -c '…'`, `env …`, a script, a subprocess — for
    /// which the name never appears as argv[0] here. Those need the check to
    /// move to the dispatch site itself; see the issue.
    static func extractOffloadCommand(from shellCommand: String) -> String? {
        let trimmed = shellCommand.trimmingCharacters(in: .whitespacesAndNewlines)
        let firstToken = trimmed.split(separator: " ", maxSplits: 1).first.map(String.init) ?? trimmed
        let basename = (firstToken as NSString).lastPathComponent
        // Return the REGISTERED name, not the caller's spelling: every consumer
        // (permissionLevel, sessionGrants, the prompt's display label) keys off
        // the registry, and handing back "/usr/local/bin/apple-healthkit" would
        // look up nothing and silently degrade to the default level.
        if allCommands.contains(where: { $0.name == basename }) {
            return basename
        }
        return nil
    }

    // MARK: - Permission Check

    func checkPermission(for command: String, sessionId: String?, fullCommand: String = "") async -> PermissionResult {
        // Mirror Android: prefer the caller-supplied session id; fall back to the
        // global bucket when the chat hasn't bound a session yet (or when invoked
        // outside chat). This keeps `Ask Once` grants per-session when possible
        // while still working for non-chat callers.
        let trimmed = sessionId?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let sessionId = trimmed.isEmpty ? OFFLOAD_GLOBAL_SESSION_ID : trimmed
        let level = permissionLevel(for: command)

        switch level {
        case .bypass:
            return .allowed

        case .notAllowed:
            logger.info("Permission denied (Not Allowed): \(command)")
            return .denied("Permission denied: the user has disabled '\(command)'. To enable it, go to Settings > Permissions or tap: [Open Permissions](minis://settings/permissions)")

        case .askOnce:
            // Check session grant
            if sessionGrants[sessionId]?.contains(command) == true {
                return .allowed
            }

            let cmdInfo = Self.allCommands.first(where: { $0.name == command })
            let displayLabel = cmdInfo?.displayLabel ?? command
            let description = cmdInfo?.description ?? ""

            let allowed = await withCheckedContinuation { continuation in
                let request = PermissionRequest(
                    id: UUID().uuidString,
                    commandName: command,
                    displayLabel: displayLabel,
                    description: description,
                    fullCommand: fullCommand,
                    continuation: continuation
                )
                self.pendingRequest = request

                // 30s timeout
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    if self.pendingRequest?.id == request.id {
                        self.pendingRequest = nil
                        continuation.resume(returning: false)
                    }
                }
            }

            if allowed {
                sessionGrants[sessionId, default: []].insert(command)
                logger.info("Permission granted (Ask Once): \(command)")
                return .allowed
            } else {
                logger.info("Permission denied (Ask Once): \(command)")
                if sessionGrants[sessionId]?.contains(command) == true {
                    // Was granted via timeout race — treat as denied
                    return .denied("Permission denied: authorization for '\(command)' timed out. To change permissions: [Open Permissions](minis://settings/permissions)")
                }
                return .denied("Permission denied: the user declined '\(command)' for this session. To change permissions: [Open Permissions](minis://settings/permissions)")
            }
        }
    }

    // MARK: - UI Response

    func respond(to requestId: String, allowed: Bool) {
        guard let request = pendingRequest, request.id == requestId else { return }
        pendingRequest = nil
        request.continuation.resume(returning: allowed)
    }

    // MARK: - Session Reset

    func resetSessionGrants(for sessionId: String) {
        sessionGrants.removeValue(forKey: sessionId)
    }
}
