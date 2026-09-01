import Foundation
import Combine

/// [T-ios-badge-diag] Diagnostics for the badge/freshness path.
///
/// This subsystem cost two rounds to diagnose because it logged NOTHING: the
/// first round's conclusion came from code reading alone and fixed only half
/// the problem, and the real cause (reconcile stamping `Date()` on every
/// launch/foreground) only surfaced after grepping a 57MB device log for a
/// line that belonged to a different subsystem entirely. Every write to a
/// badge timestamp is now traceable by `grep '\[BadgeStamp\]'`, so the next
/// "why is this badge stale/fresh" question is answered from the log instead
/// of from inference.
private let logger = AppLogger(category: "SessionBadgeStore")

/// A transient, prioritized status that can be shown as a small corner badge on
/// a session's list-cell icon. Ordered, head-of-queue wins.
///
/// The design is a per-session *queue* rather than a single flag so multiple
/// concurrent statuses can coexist (e.g. a session is both background-paused and
/// iCloud-syncing): the highest-priority one is shown, and when it's cleared the
/// next one surfaces. Only `.paused` is produced today; the enum + queue exist so
/// future states (`.iCloudSyncing`, `.error`, `.uploading`, …) drop in without a
/// rearchitecture, and so iOS/Android stay structurally aligned.
///
/// Note: the existing iCloud badge in `SessionRow` is *derived* from
/// `ChatSession.isRemote` and is intentionally left as the render fallback — it
/// is not migrated into this queue. The queue owns the new, transient states.
enum SessionBadgeState: String, Codable {
    /// The session's agent task was suspended by the system while backgrounded
    /// (the "Interrupted — tap Resume" condition). Cleared once the user opens
    /// the session. Rendered as an orange circle with a white exclamation mark.
    case paused

    /// Reserved for future use — an explicit iCloud-syncing state, should we ever
    /// move the derived `isRemote` badge into the queue. Not produced yet.
    case iCloudSyncing

    /// [T-ios-session-unread-badge] A background task finished and posted its
    /// completion notification, so this session has an unread new message.
    /// Cleared the moment the user opens the session (loadSession). Rendered as
    /// a small red dot at the icon's TOP-trailing corner — a different corner
    /// from `.paused` (bottom-trailing), so the two can coexist. Intentionally
    /// NOT persisted (see `persist()`): it's a transient, lightweight hint that
    /// is fine to lose on relaunch.
    case unread
}

/// Owns the per-session badge-state queues and their persistence. Observed by
/// `SessionRow` (like `SessionLockStore`) so a change re-evaluates the affected
/// rows in place.
@MainActor
final class SessionBadgeStore: ObservableObject {
    static let shared = SessionBadgeStore()

    /// sessionId → ordered badge states (index 0 = highest priority, shown).
    /// `@Published` so observing rows refresh when a session's queue changes.
    @Published private(set) var badgeStates: [String: [SessionBadgeState]] = [:]

    /// sessionId → state → when the session LAST ENTERED that state. Drives
    /// the group-card passthrough freshness window (stale badges go silent on
    /// the card while the row keeps showing them). Mutated in lockstep with
    /// badgeStates, so the @Published above covers UI refresh; a badge that
    /// crosses the window purely by time elapsing is picked up on the next
    /// ambient list refresh rather than by a dedicated timer.
    private(set) var badgeTimestamps: [String: [SessionBadgeState: Date]] = [:]

    private static let storageKey = "sessionBadgeStates.v1"
    private static let timestampsKey = "sessionBadgeTimestamps.v1"

    private init() {
        restore()
    }

    // MARK: - Query

    /// The badge state to render for `sessionId`, or nil if the queue is empty.
    func topBadge(for sessionId: String) -> SessionBadgeState? {
        badgeStates[sessionId]?.first
    }

    /// Whether `sessionId` currently carries the `.unread` hint. Rendered as a
    /// separate top-trailing red dot, independent of `topBadge` (which owns the
    /// bottom-trailing corner), so an unread session can also show e.g. ⏸.
    func hasUnread(for sessionId: String) -> Bool {
        badgeStates[sessionId]?.contains(.unread) ?? false
    }

    /// The highest-priority *corner-badge* state for `sessionId`, skipping
    /// `.unread` (which renders separately as a top-trailing dot, not through the
    /// bottom-trailing corner badge). `.unread` is pushed to the queue front, so
    /// `topBadge` alone would shadow a coexisting `.paused`; this returns the
    /// first non-`.unread` entry instead.
    func topCornerBadge(for sessionId: String) -> SessionBadgeState? {
        badgeStates[sessionId]?.first { $0 != .unread }
    }

    /// Whether the session carries any corner-badge state (paused / error /
    /// any future non-unread kind) entered within `window` of now. This is
    /// the GROUP-CARD passthrough filter only — rows render topCornerBadge
    /// unfiltered regardless of age. A state with no recorded timestamp
    /// (legacy persisted queues predate stamping) counts as stale: silencing
    /// unknown-age leftovers is the point of the window.
    func hasRecentCornerBadge(for sessionId: String, within window: TimeInterval) -> Bool {
        guard let queue = badgeStates[sessionId] else { return false }
        let cutoff = Date().addingTimeInterval(-window)
        return queue.contains { state in
            guard state != .unread else { return false }
            guard let stamped = badgeTimestamps[sessionId]?[state] else { return false }
            return stamped >= cutoff
        }
    }

    /// All session ids currently carrying `.unread`. Aggregation-pass form of
    /// `hasUnread(for:)`: the sidebar grouping calls this ONCE and does hash
    /// lookups per member, instead of entering the store once per member.
    /// O(badged sessions) to build — a small set, unrelated to list size.
    var unreadSessionIds: Set<String> {
        var out: Set<String> = []
        for (sid, queue) in badgeStates where queue.contains(.unread) {
            out.insert(sid)
        }
        return out
    }

    /// All session ids passing `hasRecentCornerBadge(for:within:)`, with the
    /// cutoff computed ONCE for the whole pass (the per-member form allocates
    /// a Date per call). Same aggregation-pass rationale as above. The result
    /// also serves as a memo-key component: as badges age past the window the
    /// set built on the next evaluation differs, which is exactly the
    /// "picked up on the next ambient refresh" semantics the per-member
    /// query already had.
    func freshCornerBadgeSessionIds(within window: TimeInterval) -> Set<String> {
        let cutoff = Date().addingTimeInterval(-window)
        var out: Set<String> = []
        for (sid, queue) in badgeStates {
            guard let stamps = badgeTimestamps[sid] else { continue }
            let fresh = queue.contains { state in
                guard state != .unread else { return false }
                guard let stamped = stamps[state] else { return false }
                return stamped >= cutoff
            }
            if fresh { out.insert(sid) }
        }
        return out
    }

    #if DEBUG
    /// Test injection: set a badge with a back-dated entry stamp so the
    /// freshness window is exercisable from the debug server.
    func debugSetBadge(_ state: SessionBadgeState, for sessionId: String, enteredAt: Date) {
        pushFront(state, for: sessionId, source: .debugInject)
        badgeTimestamps[sessionId, default: [:]][state] = enteredAt
        logger.info(
            "[BadgeStamp] debug-inject sid=\(sessionId.prefix(8)) state=\(state.rawValue) " +
            "enteredAt=\(Self.stampDesc(enteredAt)) age=\(Self.ageDesc(enteredAt))")
        persist()
    }
    #endif

    // MARK: - Mutation

    /// Push `state` to the *front* of the session's queue (highest priority).
    /// No-op if the same state is already at the front, so repeated interruptions
    /// don't stack duplicates. Persists.
    ///
    /// [T-ios-group-pause-badge-restamp] `restamp` distinguishes the two ways a
    /// state gets pushed, which the caller knows and this store cannot infer:
    ///
    ///   • `true` (default) — a REAL new entry into the state (an actual
    ///     interruption just happened). The freshness window keys off the last
    ///     genuine entry, so this overwrites any existing stamp.
    ///   • `false` — the state was merely RE-DETECTED for a session that was
    ///     already in it (loading an old chat whose DB tail still looks
    ///     interrupted, a background pre-warm, a cached VM re-deriving its
    ///     flags). This is the same situation `reconcileInterruptedSessions`
    ///     handles after a hard kill, and it takes the same line: keep the
    ///     existing stamp, and only stamp now when none survives.
    ///
    /// Why it matters: the group-card badge only passes states entered within
    /// the last 24h. When every push re-stamped unconditionally, simply opening
    /// (or cold-start scanning) a chat interrupted days ago reset its stamp to
    /// now, so a long-stale pause flagged its whole group forever — the more
    /// often the user looked at it, the less able it was to expire.
    func pushFront(
        _ state: SessionBadgeState,
        for sessionId: String,
        restamp: Bool = true,
        source: StampSource = .push
    ) {
        var queue = badgeStates[sessionId] ?? []
        // Drop any existing copy so the state isn't duplicated, then prepend.
        queue.removeAll { $0 == state }
        queue.insert(state, at: 0)
        badgeStates[sessionId] = queue
        let previous = badgeTimestamps[sessionId]?[state]
        let wrote = restamp || previous == nil
        if wrote {
            let now = Date()
            badgeTimestamps[sessionId, default: [:]][state] = now
            // [T-ios-badge-diag] The line to grep when a stamp moved and nobody
            // knows who moved it. `restamp` is the caller's declared intent;
            // `age` is how old the stamp being overwritten was, which is what
            // makes an unwanted refresh obvious at a glance (overwriting a
            // 3-day-old stamp is almost always the bug, not the intent).
            logger.info(
                "[BadgeStamp] write sid=\(sessionId.prefix(8)) state=\(state.rawValue) " +
                "source=\(source.rawValue) restamp=\(restamp) " +
                "old=\(Self.stampDesc(previous)) new=\(Self.stampDesc(now)) " +
                "overwroteAge=\(Self.ageDesc(previous))")
        } else {
            logger.info(
                "[BadgeStamp] keep sid=\(sessionId.prefix(8)) state=\(state.rawValue) " +
                "source=\(source.rawValue) restamp=false kept=\(Self.stampDesc(previous)) " +
                "age=\(Self.ageDesc(previous))")
        }
        persist()
    }

    /// [T-ios-badge-diag] Who asked for a stamp write. Carried into the log so a
    /// stray refresh can be attributed to a call site without re-reading code.
    enum StampSource: String {
        /// A live interruption observed by the owning chat VM.
        case push
        /// The load/pre-warm path re-deriving an interruption that already happened.
        case redetect
        /// Launch/foreground reconcile restoring a marker from the DB tail.
        case reconcile
        /// The one-time repair of stamps polluted by earlier builds.
        case repair
        /// DEBUG injection from the debug RPC.
        case debugInject
    }

    /// Compact, greppable timestamp rendering (`nil` stays visible as "none").
    private static func stampDesc(_ date: Date?) -> String {
        guard let date else { return "none" }
        return String(format: "%.0f", date.timeIntervalSince1970)
    }

    /// How long ago a stamp was, in hours — the number that actually matters
    /// against the 24h window.
    private static func ageDesc(_ date: Date?) -> String {
        guard let date else { return "n/a" }
        return String(format: "%.1fh", -date.timeIntervalSinceNow / 3600)
    }

    /// Remove `state` from the session's queue (if present). The next state in
    /// the queue, if any, becomes the visible badge. Persists.
    func remove(_ state: SessionBadgeState, for sessionId: String) {
        guard var queue = badgeStates[sessionId] else { return }
        let before = queue.count
        queue.removeAll { $0 == state }
        guard queue.count != before else { return }
        if queue.isEmpty {
            badgeStates.removeValue(forKey: sessionId)
        } else {
            badgeStates[sessionId] = queue
        }
        let dropped = badgeTimestamps[sessionId]?[state]
        badgeTimestamps[sessionId]?.removeValue(forKey: state)
        if badgeTimestamps[sessionId]?.isEmpty == true {
            badgeTimestamps.removeValue(forKey: sessionId)
        }
        // [T-ios-badge-diag] Dropping the stamp is what lets a later re-add mint
        // a brand-new one — the recurrence mechanism behind the original bug.
        // Logged so a remove→re-add churn loop is visible as a pattern rather
        // than having to be deduced.
        logger.info(
            "[BadgeStamp] drop sid=\(sessionId.prefix(8)) state=\(state.rawValue) " +
            "droppedStamp=\(Self.stampDesc(dropped)) age=\(Self.ageDesc(dropped))")
        persist()
    }

    /// [T-ios-session-paused-badge-hardkill] Reconcile the `.paused` badges
    /// against the authoritative set of currently-interrupted sessions (derived
    /// from the persisted message tail by `ChatStore.interruptedSessionIds`).
    /// Called once at launch so a session hard-killed (jetsam/SIGKILL) while
    /// running — which never hit the background-expiry push path — still shows
    /// the badge after restart, and a session that is NO longer interrupted
    /// (resumed/completed before the kill) gets its stale badge cleared.
    ///
    /// Only touches `.paused`; other queued states are left intact.
    func reconcileInterruptedSessions(
        _ interruptedIds: Set<String>,
        entryDates: [String: Date] = [:],
        trigger: String = "unspecified"
    ) {
        var changed = false
        // [T-ios-badge-diag] Per-run tallies. The single most useful signal here
        // is `fallbackNow`: every one of those is a badge whose age is a guess,
        // and a large count means the tail dates aren't arriving — the exact
        // condition that made 288 stale pauses look current on the reporter's
        // device. `firstStamp` vs `keptStamp` shows whether reconcile is
        // restoring markers (expected) or re-minting them (not).
        var firstStamp = 0, keptStamp = 0, fromTailDate = 0, fallbackNow = 0, cleared = 0
        // Add .paused for interrupted sessions that don't have it yet.
        for sid in interruptedIds where badgeStates[sid]?.contains(.paused) != true {
            var queue = badgeStates[sid] ?? []
            queue.insert(.paused, at: 0)
            badgeStates[sid] = queue
            // Reconcile RESTORES a marker after a hard kill; it is not a new
            // entry into the state, so keep an existing stamp. Only stamp now
            // when none survives (the original push never happened/persisted).
            //
            // [T-ios-group-pause-badge-reconcile-stamp] When none survives, use
            // the DB tail's own timestamp — the moment the session was actually
            // left hanging — NOT `Date()`. This is the path that kept the 24h
            // window broken even after the pushFront fix: reconcile runs on
            // every launch AND every foreground, and a device carrying hundreds
            // of long-interrupted sessions (288 on the reporter's, per
            // [ChatStore.interruptedSessionIds] in their log) had every one of
            // them stamped "now" the first time the window shipped. It also
            // recurs: `remove` drops the stamp, so any later re-add would mint
            // a fresh one again. Falling back to `Date()` only when the tail
            // date is genuinely unavailable keeps the old behavior for the
            // case it was written for.
            if badgeTimestamps[sid]?[.paused] == nil {
                let tailDate = entryDates[sid]
                let stamp = tailDate ?? Date()
                badgeTimestamps[sid, default: [:]][.paused] = stamp
                firstStamp += 1
                if tailDate != nil { fromTailDate += 1 } else { fallbackNow += 1 }
                // Per-session detail only for the fallback case: those are the
                // ones whose age is unverified, so they're worth naming. The
                // tail-dated majority is covered by the summary counts, keeping
                // a 288-session reconcile from emitting 288 lines.
                if tailDate == nil {
                    logger.info(
                        "[BadgeStamp] reconcile-fallback sid=\(sid.prefix(8)) — no tail date, " +
                        "stamped now (age unverified)")
                }
            } else {
                keptStamp += 1
            }
            changed = true
        }
        // Remove .paused from sessions that are no longer interrupted.
        for (sid, queue) in badgeStates where queue.contains(.paused) && !interruptedIds.contains(sid) {
            var q = queue
            q.removeAll { $0 == .paused }
            if q.isEmpty { badgeStates.removeValue(forKey: sid) } else { badgeStates[sid] = q }
            badgeTimestamps[sid]?.removeValue(forKey: .paused)
            changed = true
            cleared += 1
        }
        if changed { persist() }
        logger.info(
            "[BadgeReconcile] trigger=\(trigger) interrupted=\(interruptedIds.count) " +
            "tailDatesAvailable=\(entryDates.count) firstStamp=\(firstStamp) " +
            "keptStamp=\(keptStamp) fromTailDate=\(fromTailDate) fallbackNow=\(fallbackNow) " +
            "clearedNoLongerInterrupted=\(cleared) changed=\(changed)")
    }

    /// [T-ios-group-pause-badge-reconcile-stamp] Correct `.paused` stamps that
    /// earlier builds wrote as "now" for sessions that had actually been
    /// interrupted long before.
    ///
    /// Why a repair is needed at all: the stamp is persisted, so fixing the
    /// writers only stops NEW pollution — every install that already ran a
    /// build between the window shipping (2026-08-02) and this fix still holds
    /// stamps saying a days-old pause happened moments ago, and the group card
    /// reads those. The reporter's device had 288 interrupted sessions, so this
    /// is the bulk of them.
    ///
    /// Strategy — trust the DB, never guess: a stamp is rewritten ONLY when the
    /// session's interrupted tail is demonstrably older than the stamp claims.
    /// The tail date is the real entry time, so "stamp newer than tail" can only
    /// mean the stamp was minted by a re-detect/reconcile rather than by the
    /// interruption itself. A tolerance absorbs the ordinary case where the push
    /// legitimately lands a moment after the message is written.
    ///
    /// Deliberately NOT "wipe everything unknown": that would silence genuinely
    /// fresh badges too (a real interruption 10 minutes ago), trading a
    /// false-positive for a false-negative. Sessions absent from `entryDates`
    /// (no longer interrupted) are left alone — reconcile's removal pass owns
    /// those. Runs once per launch and is idempotent: after the first pass the
    /// stamps already match the tails, so nothing changes on later passes.
    func repairPollutedPausedStamps(entryDates: [String: Date]) {
        /// A push racing its own message write can land slightly after it.
        /// Anything beyond this gap is a re-stamp, not the original entry.
        let tolerance: TimeInterval = 5 * 60
        var repaired = 0
        var examined = 0
        var maxShiftHours: Double = 0
        for (sid, tailDate) in entryDates {
            guard let stamped = badgeTimestamps[sid]?[.paused] else { continue }
            examined += 1
            guard stamped > tailDate.addingTimeInterval(tolerance) else { continue }
            let shiftHours = stamped.timeIntervalSince(tailDate) / 3600
            maxShiftHours = max(maxShiftHours, shiftHours)
            // [T-ios-badge-diag] Per-session detail: which stamp was wrong and
            // by how much. `shift` is the size of the lie — a stamp claiming a
            // pause was 0h old when the tail says 72h shows up as shift=72.0h.
            logger.info(
                "[BadgeRepair] fix sid=\(sid.prefix(8)) " +
                "wasStamped=\(Self.stampDesc(stamped)) (age \(Self.ageDesc(stamped))) → " +
                "tail=\(Self.stampDesc(tailDate)) (age \(Self.ageDesc(tailDate))) " +
                "shift=\(String(format: "%.1f", shiftHours))h")
            badgeTimestamps[sid, default: [:]][.paused] = tailDate
            repaired += 1
        }
        if repaired > 0 { persist() }
        // Always emit the summary, including the repaired=0 steady state: that
        // zero IS the signal that the self-heal has converged. A count that
        // stays high across launches means something is still re-polluting the
        // stamps and this is only papering over it — the thing to notice early.
        logger.info(
            "[BadgeRepair] summary examined=\(examined) repaired=\(repaired) " +
            "maxShift=\(String(format: "%.1f", maxShiftHours))h " +
            "tailDatesAvailable=\(entryDates.count)")
    }

    /// Remove a session's entire queue (e.g. when the session is deleted).
    func clearAll(for sessionId: String) {
        guard badgeStates[sessionId] != nil else { return }
        badgeStates.removeValue(forKey: sessionId)
        badgeTimestamps.removeValue(forKey: sessionId)
        persist()
    }

    // MARK: - Persistence

    private func persist() {
        // Encode [String: [String]] (rawValues) so a future enum-case addition
        // can't fail to decode the whole store — unknown cases are skipped on
        // restore instead.
        // [T-ios-session-unread-badge] `.unread` is a transient hint that must
        // not survive relaunch (a completed-in-background task the user hasn't
        // read yet should not re-flag a red dot forever). Strip it before
        // encoding; queues that held only `.unread` drop out entirely.
        let raw = badgeStates
            .mapValues { $0.filter { $0 != .unread }.map(\.rawValue) }
            .filter { !$0.value.isEmpty }
        if let data = try? JSONEncoder().encode(raw) {
            UserDefaults.standard.set(data, forKey: Self.storageKey)
        }
        // Entry stamps ride a parallel key with the same strip-unread rule.
        let rawTs = badgeTimestamps
            .mapValues { perState -> [String: Double] in
                var out: [String: Double] = [:]
                for (state, date) in perState where state != .unread {
                    out[state.rawValue] = date.timeIntervalSince1970
                }
                return out
            }
            .filter { !$0.value.isEmpty }
        if let data = try? JSONEncoder().encode(rawTs) {
            UserDefaults.standard.set(data, forKey: Self.timestampsKey)
        }
    }

    private func restore() {
        guard let data = UserDefaults.standard.data(forKey: Self.storageKey),
              let raw = try? JSONDecoder().decode([String: [String]].self, from: data)
        else { return }
        var restored: [String: [SessionBadgeState]] = [:]
        for (sessionId, rawStates) in raw {
            // Skip unknown/legacy raw values rather than dropping the session.
            let states = rawStates.compactMap { SessionBadgeState(rawValue: $0) }
            if !states.isEmpty { restored[sessionId] = states }
        }
        badgeStates = restored

        if let tsData = UserDefaults.standard.data(forKey: Self.timestampsKey),
           let rawTs = try? JSONDecoder().decode([String: [String: Double]].self, from: tsData) {
            var ts: [String: [SessionBadgeState: Date]] = [:]
            for (sessionId, perState) in rawTs {
                for (rawState, epoch) in perState {
                    guard let state = SessionBadgeState(rawValue: rawState) else { continue }
                    ts[sessionId, default: [:]][state] = Date(timeIntervalSince1970: epoch)
                }
            }
            badgeTimestamps = ts
        }
    }
}
