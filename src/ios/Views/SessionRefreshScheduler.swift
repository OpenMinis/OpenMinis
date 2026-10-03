import Foundation

/// [T-ios-listsessions-perf] Decides whether a session-list refresh may run
/// now or must wait out a cooldown.
///
/// Why this exists as its own type: the sidebar's refresh was gated by
/// `.throttle(for: .seconds(1))` on the `.sessionDidUpdate` publisher, which
/// silently did nothing. The CPU Profiler trace of an 11-minute agent run
/// measured a single refresh at 4.4 s median and 9.1 s p90 (36 s worst case) —
/// far longer than the 1 s throttle window, so by the time a run finished its
/// throttle had long since reopened. The trailing `sessionRefreshPending` flag
/// then re-queued a run immediately, and the two together produced 63 rebuilds
/// back to back with a median idle gap of 2.8 s: the ChatStore actor was busy
/// inside listSessions for 55% of wall time, and the device hit Thermal
/// Serious.
///
/// The fix is to measure the gap from when the last refresh FINISHED rather
/// than from when it started or from when a notification arrived. A throttle
/// keyed on arrival cannot bound work whose duration exceeds its own window;
/// a cooldown keyed on completion always can.
enum SessionRefreshScheduler {

    /// Minimum idle time between the end of one refresh and the start of the
    /// next. DECISION (per the task spec): 3 s.
    ///
    /// With a 4 s refresh this caps the duty cycle at roughly 4-in-7 rather
    /// than the effectively-continuous loop the trace recorded, while staying
    /// short enough that a sidebar preview still visibly tracks a running
    /// agent's tool rounds.
    static let cooldown: TimeInterval = 3

    enum Decision: Equatable {
        /// Nothing has run recently enough to matter — go.
        case runNow
        /// Wait this long, then run. Always > 0.
        case `defer`(TimeInterval)
    }

    /// - Parameters:
    ///   - now: current time.
    ///   - lastFinishedAt: when the previous refresh completed, or nil if none
    ///     has completed in this session.
    ///   - cooldown: overridable for tests.
    static func decide(
        now: Date,
        lastFinishedAt: Date?,
        cooldown: TimeInterval = SessionRefreshScheduler.cooldown
    ) -> Decision {
        // First event after launch always fires immediately: the user opening
        // the sidebar must not wait out a cooldown that nothing has earned.
        guard let last = lastFinishedAt else { return .runNow }

        let elapsed = now.timeIntervalSince(last)

        // A clock that jumped BACKWARDS (NTP correction; Date is wall-clock)
        // would otherwise defer for as long as the jump. Only treat a jump as
        // such — a small negative or zero elapsed is the ordinary case of the
        // trailing re-run re-entering immediately after `lastFinishedAt` was
        // stamped, and that one MUST take the cooldown. Letting `elapsed <= 0`
        // mean "run now" would reopen the exact back-to-back loop this whole
        // change exists to close.
        if elapsed < -1 { return .runNow }

        guard elapsed < cooldown else { return .runNow }
        return .defer(max(cooldown - elapsed, 0.001))
    }
}
