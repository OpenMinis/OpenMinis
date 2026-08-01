import Foundation
import UIKit
import UserNotifications

private let logger = AppLogger(category: "ShortcutDiag")

/// [T-shortcuts-diag-and-pending] Diagnostics + persistent tracking for AppIntent-
/// triggered runs (Shortcuts automations).
///
/// Two concerns handled here:
///
/// 1. **`perform()` entry snapshot** — a single structured log line per Intent
///    call that captures every keep-alive-relevant toggle/permission bit AND
///    the outcome of the eager keep-alive arm. When a future report says
///    "the automation didn't run", we grep this one line and know instantly
///    whether the user had the toggles on, whether notifications were
///    granted, and whether eager-arm actually started or was skipped.
///
/// 2. **Pending-run marker** — a small UserDefaults record written the moment
///    the Intent hands the prompt to the agent loop, cleared the moment the
///    agent loop actually finishes. On the next foreground return we scan
///    for records that never got cleared; if the recorded toggle snapshot
///    shows the user hadn't enabled Background Keep-Alive, we surface a
///    guidance banner nudging them to turn it on. Records older than
///    `staleAge` are wiped on scan to avoid nagging on ancient data.
///
/// Storage keys are namespaced under `shortcutDiag.*` in the standard
/// UserDefaults so they survive process death but do NOT sync to iCloud
/// (this is device-local diagnostic state).
enum ShortcutRunTracker {

    // MARK: - Constants

    private static let pendingRecordsKey = "shortcutDiag.pendingRuns.v1"
    private static let notifiedRunsKey = "shortcutDiag.notifiedRuns.v1"
    private static let staleAge: TimeInterval = 24 * 60 * 60  // 24h
    /// A foreground transition can race with session creation / vm.send(). A
    /// brand-new marker is not evidence of an interrupted automation yet.
    private static let minimumOrphanAge: TimeInterval = 30
    private static let notificationCategoryId = "SHORTCUT_KEEPALIVE_GUIDANCE"

    // MARK: - PerformEntry snapshot (concern #1)

    /// One-line structured snapshot of everything relevant at the top of an
    /// Intent's `perform()`. Emitted BEFORE any await so the value reflects
    /// the state at the moment iOS handed the automation to us.
    ///
    /// Parameters mirror what the report needs to grep: the master switches,
    /// the derived "effective" state, notification permission (for the
    /// user-facing bits), the current keep-alive manager state, and the
    /// outcome of the eager keep-alive arm we just tried.
    @MainActor
    static func logPerformEntry(
        intent: String,
        sessionId: String,
        eagerKeepAliveArmed: Bool,
        eagerKeepAliveSkippedReason: String?
    ) {
        let bka = BackgroundKeepAliveManager.shared
        let enhancedOn = bka.enhancedBackgroundEnabled
        let bgSpeakOn = bka.backgroundSpeakEnabled
        let effective = bka.enhancedBackgroundEffective
        let bkaActive = bka.isActive
        let activeSessionsCount = SessionActivityTracker.shared.activeSessions.count
        let notif = cachedNotificationAuthStatus()

        // Kick off an async refresh of the notif auth cache for next time —
        // getNotificationSettings is async, we don't block perform() on it.
        refreshNotificationAuthCache()

        let skippedReasonField = eagerKeepAliveSkippedReason.map { "reason=\($0)" } ?? "reason=nil"
        logger.info(
            "[ShortcutDiag] perform-entry intent=\(intent) session=\(sessionId.prefix(8)) " +
            "enhancedBackgroundEnabled=\(enhancedOn) backgroundSpeakEnabled=\(bgSpeakOn) " +
            "enhancedBackgroundEffective=\(effective) " +
            "notificationAuth=\(notif) " +
            "bkaIsActive=\(bkaActive) activeSessions.count=\(activeSessionsCount) " +
            "eagerKeepAliveArmed=\(eagerKeepAliveArmed) \(skippedReasonField)"
        )
    }

    // MARK: - Pending record (concern #3)

    /// A run that was launched by an Intent and is waiting for the agent loop
    /// to finish. Persisted verbatim; the fields we snapshot are precisely
    /// what the next-foreground guidance decision needs to make sense of a
    /// stale record.
    struct PendingRecord: Codable {
        let id: String                        // UUID, used as dictionary key + notification id
        let intent: String
        let sessionId: String
        let startedAt: Date
        let enhancedBackgroundEnabled: Bool
        let backgroundSpeakEnabled: Bool
        let eagerKeepAliveArmed: Bool
        let eagerKeepAliveSkippedReason: String?
    }

    /// Write a pending record. Returns the record's id so the caller can pass
    /// it to `markCompleted` once the agent loop settles. Safe to call from
    /// any actor; UserDefaults itself is thread-safe.
    @discardableResult
    @MainActor
    static func markPending(
        intent: String,
        sessionId: String,
        eagerKeepAliveArmed: Bool,
        eagerKeepAliveSkippedReason: String?
    ) -> String {
        let bka = BackgroundKeepAliveManager.shared
        let record = PendingRecord(
            id: UUID().uuidString,
            intent: intent,
            sessionId: sessionId,
            startedAt: Date(),
            enhancedBackgroundEnabled: bka.enhancedBackgroundEnabled,
            backgroundSpeakEnabled: bka.backgroundSpeakEnabled,
            eagerKeepAliveArmed: eagerKeepAliveArmed,
            eagerKeepAliveSkippedReason: eagerKeepAliveSkippedReason
        )
        var records = loadRecords()
        records[record.id] = record
        saveRecords(records)
        logger.info("[ShortcutDiag] pending MARKED id=\(record.id.prefix(8)) intent=\(intent) session=\(sessionId.prefix(8)) armed=\(eagerKeepAliveArmed)")
        return record.id
    }

    /// Replace the temporary `intent-eager:<UUID>` session used while a new
    /// chat is being created with its durable ChatStore id. Without this, an
    /// interruption notification deep-links to a session that never existed.
    @MainActor
    static func rebindSession(recordId: String, toSessionId sessionId: String) {
        guard !sessionId.isEmpty else { return }
        var records = loadRecords()
        guard let old = records[recordId], old.sessionId != sessionId else { return }
        records[recordId] = PendingRecord(
            id: old.id,
            intent: old.intent,
            sessionId: sessionId,
            startedAt: old.startedAt,
            enhancedBackgroundEnabled: old.enhancedBackgroundEnabled,
            backgroundSpeakEnabled: old.backgroundSpeakEnabled,
            eagerKeepAliveArmed: old.eagerKeepAliveArmed,
            eagerKeepAliveSkippedReason: old.eagerKeepAliveSkippedReason
        )
        saveRecords(records)
        logger.info("[ShortcutDiag] pending REBOUND id=\(recordId.prefix(8)) session=\(sessionId.prefix(8))")
    }

    /// Clear a pending record. Called from the completion-observer path once
    /// the agent loop's `isProcessing` returns to false (the same signal the
    /// existing "Follow-up Done" notification uses).
    @MainActor
    static func markCompleted(recordId: String, reason: String) {
        var records = loadRecords()
        guard let removed = records.removeValue(forKey: recordId) else { return }
        saveRecords(records)
        let elapsed = Date().timeIntervalSince(removed.startedAt)
        logger.info("[ShortcutDiag] pending CLEARED id=\(recordId.prefix(8)) intent=\(removed.intent) elapsed=\(String(format: "%.1f", elapsed))s reason=\(reason)")
    }

    // MARK: - Foreground scan (concern #3 payoff)

    /// Called from MinisApp's scenePhase → .active handler. A pending record is
    /// only orphaned after a short grace period and when its real session is no
    /// longer active. This avoids warning when the user opens Minis while the
    /// automation is still running. Records older than `staleAge` are dropped
    /// silently; newer interrupted records receive one guidance notification.
    @MainActor
    static func checkPendingOnForeground() {
        var records = loadRecords()
        guard !records.isEmpty else { return }
        var notified = loadNotifiedIds()
        let now = Date()

        // Split into "interrupted, needs attention" vs "too stale, drop".
        // Young records and records backed by a currently-active session stay
        // pending for their normal completion observer to clear.
        var stale: [PendingRecord] = []
        var orphaned: [PendingRecord] = []
        for record in records.values {
            let age = now.timeIntervalSince(record.startedAt)
            if age > staleAge {
                stale.append(record)
            } else if age < minimumOrphanAge {
                logger.info("[ShortcutDiag] pending DEFERRED-YOUNG id=\(record.id.prefix(8)) age=\(String(format: "%.0f", age))s")
            } else if SessionActivityTracker.shared.isActive(record.sessionId) {
                logger.info("[ShortcutDiag] pending STILL-RUNNING id=\(record.id.prefix(8)) session=\(record.sessionId.prefix(8)) age=\(String(format: "%.0f", age))s")
            } else {
                orphaned.append(record)
            }
        }

        // Prune stale
        for r in stale {
            records.removeValue(forKey: r.id)
            notified.remove(r.id)
            logger.info("[ShortcutDiag] pending DROPPED-STALE id=\(r.id.prefix(8)) intent=\(r.intent) age=\(String(format: "%.0f", now.timeIntervalSince(r.startedAt)))s")
        }

        // Decide guidance for fresh orphans
        // Categorize each orphan and post at most one notification per orphan
        // (`notifiedIds` set), then clear the record so we don't loop on the
        // same one next foreground.
        for record in orphaned {
            let category = classify(record)
            let alreadyNotified = notified.contains(record.id)
            logger.info(
                "[ShortcutDiag] orphaned pending id=\(record.id.prefix(8)) intent=\(record.intent) " +
                "session=\(record.sessionId.prefix(8)) age=\(String(format: "%.0f", now.timeIntervalSince(record.startedAt)))s " +
                "category=\(category) alreadyNotified=\(alreadyNotified) " +
                "enhancedBackgroundEnabled=\(record.enhancedBackgroundEnabled) " +
                "backgroundSpeakEnabled=\(record.backgroundSpeakEnabled) " +
                "eagerKeepAliveArmed=\(record.eagerKeepAliveArmed)"
            )
            if !alreadyNotified {
                postGuidanceNotification(record: record, category: category)
                notified.insert(record.id)
            }
            // Clear the record after notifying so a second foreground return
            // doesn't churn on the same one — the notification is a durable
            // enough signal for the user; we don't need to keep re-scanning.
            records.removeValue(forKey: record.id)
        }

        saveRecords(records)
        saveNotifiedIds(notified)
    }

    // MARK: - Classification

    enum OrphanCategory: String {
        /// User hadn't turned on the keep-alive toggles — this is the
        /// actionable one where guidance actually helps.
        case keepAliveDisabled
        /// User HAS the toggles on but the run still didn't finish — either
        /// a legacy record from before the eager-arm fix, or a genuinely
        /// stuck run for a different reason. Logged, no user-facing nag.
        case keepAliveEnabledButStillOrphaned
    }

    private static func classify(_ record: PendingRecord) -> OrphanCategory {
        let effective = record.enhancedBackgroundEnabled && record.backgroundSpeakEnabled
        return effective ? .keepAliveEnabledButStillOrphaned : .keepAliveDisabled
    }

    // MARK: - Guidance notification

    @MainActor
    private static func postGuidanceNotification(record: PendingRecord, category: OrphanCategory) {
        // Respect the same global toggle used by other Shortcut notifications.
        let notificationsAllowed = UserDefaults.standard.object(forKey: "backgroundNotificationsEnabled") == nil
            || UserDefaults.standard.bool(forKey: "backgroundNotificationsEnabled")
        guard notificationsAllowed else {
            logger.info("[ShortcutDiag] guidance SUPPRESSED id=\(record.id.prefix(8)) reason=backgroundNotificationsEnabled=false")
            return
        }

        let title: String
        let body: String
        switch category {
        case .keepAliveDisabled:
            title = String(localized: "Automation may not have completed")
            body = String(localized: "The Shortcut sent to Minis may not have finished running in the background. Turn on Background Keep-Alive in Settings so automations can complete reliably.")
        case .keepAliveEnabledButStillOrphaned:
            // Softer wording — the user already did the right thing; we
            // still tell them but avoid finger-pointing at their setup.
            title = String(localized: "Automation may not have completed")
            body = String(localized: "A Shortcut sent to Minis may not have finished. Open the session to check.")
        }

        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }

        let action = UNNotificationCategory(
            identifier: notificationCategoryId,
            actions: [],
            intentIdentifiers: []
        )
        center.setNotificationCategories([action])

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = notificationCategoryId
        var userInfo: [AnyHashable: Any] = [
            "shortcutDiagCategory": category.rawValue
        ]
        // A process can be suspended before ChatStore creates the real session.
        // In that case open Minis normally instead of routing to a placeholder.
        if !record.sessionId.hasPrefix("intent-eager:") && record.sessionId != "unknown" {
            userInfo["sessionId"] = record.sessionId
        }
        content.userInfo = userInfo
        let request = UNNotificationRequest(
            identifier: "shortcutDiag-orphan-\(record.id)",
            content: content,
            trigger: nil
        )
        center.add(request)
        logger.info("[ShortcutDiag] guidance POSTED id=\(record.id.prefix(8)) category=\(category)")
    }

    // MARK: - Notification-auth cache

    // getNotificationSettings is async; we don't want to block Intent perform()
    // waiting for it. Instead we keep a cached last-known status and refresh it
    // out-of-band. Fresh installs will log "unknown" once, then correct.
    private static var _notificationAuthCache: String = UserDefaults.standard.string(forKey: "shortcutDiag.notificationAuthCache.v1") ?? "unknown"

    private static func cachedNotificationAuthStatus() -> String {
        _notificationAuthCache
    }

    private static func refreshNotificationAuthCache() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            let value: String
            switch settings.authorizationStatus {
            case .notDetermined: value = "notDetermined"
            case .denied: value = "denied"
            case .authorized: value = "authorized"
            case .provisional: value = "provisional"
            case .ephemeral: value = "ephemeral"
            @unknown default: value = "unknown"
            }
            _notificationAuthCache = value
            UserDefaults.standard.set(value, forKey: "shortcutDiag.notificationAuthCache.v1")
        }
    }

    // MARK: - Storage (UserDefaults, JSON-encoded)

    private static func loadRecords() -> [String: PendingRecord] {
        guard let data = UserDefaults.standard.data(forKey: pendingRecordsKey),
              let decoded = try? JSONDecoder().decode([String: PendingRecord].self, from: data)
        else { return [:] }
        return decoded
    }

    private static func saveRecords(_ records: [String: PendingRecord]) {
        if records.isEmpty {
            UserDefaults.standard.removeObject(forKey: pendingRecordsKey)
            return
        }
        if let encoded = try? JSONEncoder().encode(records) {
            UserDefaults.standard.set(encoded, forKey: pendingRecordsKey)
        }
    }

    private static func loadNotifiedIds() -> Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: notifiedRunsKey) ?? [])
    }

    private static func saveNotifiedIds(_ ids: Set<String>) {
        if ids.isEmpty {
            UserDefaults.standard.removeObject(forKey: notifiedRunsKey)
        } else {
            UserDefaults.standard.set(Array(ids), forKey: notifiedRunsKey)
        }
    }
}
