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

    /// Clear a pending record. Called from the completion-observer path once
    /// the agent loop's `isProcessing` returns to false (the same signal the
    /// existing "Follow-up Done" notification uses).
    static func markCompleted(recordId: String, reason: String) {
        var records = loadRecords()
        guard let removed = records.removeValue(forKey: recordId) else { return }
        saveRecords(records)
        let elapsed = Date().timeIntervalSince(removed.startedAt)
        logger.info("[ShortcutDiag] pending CLEARED id=\(recordId.prefix(8)) intent=\(removed.intent) elapsed=\(String(format: "%.1f", elapsed))s reason=\(reason)")
    }

    // MARK: - Foreground scan (concern #3 payoff)

    /// Called from MinisApp's scenePhase → .active handler. Any pending record
    /// still present is by definition orphaned: the Intent handed off to the
    /// agent loop and neither the loop's completion path nor a previous
    /// foreground scan reached `markCompleted`. Records older than
    /// `staleAge` are dropped silently; newer records where the user hadn't
    /// enabled keep-alive get a one-shot guidance notification.
    ///
    /// [T-shortcut-orphan-false-positive] "Orphaned" only means the completion
    /// SIGNAL was lost — not that the task failed. In async mode markCompleted
    /// runs inside a detached `Task { for await … }` living in the short-lived
    /// AppIntent process; if that process is suspended or reaped after perform()
    /// returns, the Task never resumes and the record is stranded even though the
    /// agent answered normally. There is also a plain race: on foreground return
    /// this scan runs immediately while a resuming agent loop only flips
    /// isProcessing (→ markCompleted) a moment later.
    ///
    /// So before warning the user, verify against the DATABASE whether the
    /// session actually produced a completed reply after the run began. Async
    /// because ChatStore is an actor and the orphan's VM is usually gone from
    /// memory by now — the persisted messages are the only reliable source.
    @MainActor
    static func checkPendingOnForeground() async {
        var records = loadRecords()
        guard !records.isEmpty else { return }
        var notified = loadNotifiedIds()
        let now = Date()

        // Split into "still fresh, needs attention" vs "too stale, drop".
        var stale: [PendingRecord] = []
        var orphaned: [PendingRecord] = []
        for record in records.values {
            if now.timeIntervalSince(record.startedAt) > staleAge {
                stale.append(record)
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
            // [T-shortcut-orphan-false-positive] Empirical completion check
            // BEFORE any guidance decision. If the session demonstrably answered,
            // this is a lost signal rather than a stalled task — clear silently.
            if await sessionDidComplete(record: record) {
                logger.info(
                    "[ShortcutDiag] pending completion-verified silent clear id=\(record.id.prefix(8)) " +
                    "intent=\(record.intent) session=\(record.sessionId.prefix(8)) — session has a completed " +
                    "assistant reply after startedAt; markCompleted signal was lost, task itself was fine"
                )
                records.removeValue(forKey: record.id)
                notified.remove(record.id)
                continue
            }

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

    // MARK: - Completion verification

    /// Whether `record`'s session demonstrably finished the run this record was
    /// tracking. Returns `false` whenever it cannot prove completion.
    ///
    /// [T-shortcut-orphan-false-positive] Deliberately biased toward
    /// false-negatives: a wrong `true` swallows a genuine "your automation
    /// didn't finish" warning, which is the failure this whole tracker exists to
    /// surface. A wrong `false` only costs one redundant notification. So every
    /// uncertain path — lookup failure, empty session, still-running — answers
    /// `false` and lets the original classification run.
    @MainActor
    private static func sessionDidComplete(record: PendingRecord) async -> Bool {
        // A session still actively processing has not finished. This also covers
        // the foreground race: a resuming agent loop is mid-run, will flip
        // isProcessing shortly, and markCompleted will clear the record itself.
        if SessionActivityTracker.shared.activeSessions.contains(record.sessionId) {
            logger.info("[ShortcutDiag] completion-check id=\(record.id.prefix(8)) → still active, not verified")
            return false
        }

        // A placeholder id ("intent-eager:<UUID>") never became a real session
        // row, so there is nothing to look up. Fall through to the old logic.
        guard !record.sessionId.isEmpty, !record.sessionId.hasPrefix("intent-eager:") else {
            logger.info("[ShortcutDiag] completion-check id=\(record.id.prefix(8)) → placeholder/empty sessionId, not verified")
            return false
        }

        let messages = await ChatStore.shared.loadMessages(sessionId: record.sessionId)
        guard !messages.isEmpty else {
            logger.info("[ShortcutDiag] completion-check id=\(record.id.prefix(8)) → no persisted messages, not verified")
            return false
        }

        // Look for an assistant reply persisted AFTER this run started. Ordering
        // by createdAt is what ties the evidence to THIS run rather than to an
        // older turn in a long-lived session (RetryRun/FollowUp both operate on
        // sessions that already have history).
        //
        // A small tolerance absorbs clock skew between the record's timestamp and
        // the DB write: `startedAt` is stamped in the AppIntent process before the
        // send, so a reply belonging to this run can never legitimately predate it
        // by more than a moment.
        let cutoff = record.startedAt.addingTimeInterval(-2)
        let repliesAfterStart = messages.filter {
            $0.role == .assistant && $0.createdAt >= cutoff
        }
        guard !repliesAfterStart.isEmpty else {
            logger.info("[ShortcutDiag] completion-check id=\(record.id.prefix(8)) → no assistant reply after startedAt, not verified")
            return false
        }

        // The reply must be a real answer: not an errored turn, and not the
        // internal bridge row inserted when a queued message interrupts a tool
        // loop (that one means the run was interrupted, not that it completed).
        let usableReply = repliesAfterStart.contains { msg in
            guard msg.errorInfo == nil else { return false }
            guard !msg.isInternalBridge else { return false }
            let hasText = msg.parts.contains { part in
                if case .text(let t) = part {
                    return !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                }
                return false
            }
            return hasText
        }
        if !usableReply {
            logger.info("[ShortcutDiag] completion-check id=\(record.id.prefix(8)) → replies present but all errored/empty/bridge, not verified")
            return false
        }
        return true
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
            title = AppLocalized("Automation may not have completed")
            body = AppLocalized("The Shortcut sent to Minis may not have finished running in the background. Turn on Background Keep-Alive in Settings so automations can complete reliably.")
        case .keepAliveEnabledButStillOrphaned:
            // Softer wording — the user already did the right thing; we
            // still tell them but avoid finger-pointing at their setup.
            title = AppLocalized("Automation may not have completed")
            body = AppLocalized("A Shortcut sent to Minis may not have finished. Open the session to check.")
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
        content.userInfo = [
            "sessionId": record.sessionId,
            "shortcutDiagCategory": category.rawValue,
        ]
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
