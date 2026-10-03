import Foundation
import Darwin.Mach
import os

/// [T-resource-diag] Process-wide resource accounting for the failure class that
/// killed 1.14(19): `EXC_RESOURCE` / `PORT_SPACE`, "Exceeded system-wide
/// per-process Port Limit".
///
/// That crash cost a full symbolication round-trip to explain, because nothing
/// in the app recorded the one number that mattered. The port count had been
/// climbing for minutes; the only artefact was a stack at the instant the
/// ceiling was hit. This type exists so the next occurrence is a line in the
/// log instead of a disassembly session.
///
/// Design constraints, in priority order:
///   1. It must not become the problem it measures. `mach_port_names` is itself
///      a MIG call, so sampling is on a slow timer (60s) and never in a hot
///      path; counters are lock-guarded integers whose uncontended cost is a
///      few instructions, not a syscall.
///   2. Everything here is callable from any thread. The hot paths that feed it
///      are guest threads inside the iSH kernel and background tool work, none
///      of which is main-actor isolated.
///   3. A missing reading is reported as absent, never as zero — a fabricated
///      0 would read as "no leak" and is worse than no data.
enum ResourceDiagnostics {
    private static let logger = AppLogger(category: "ResourceDiag")

    // MARK: - Categorized counters
    //
    // Monotonic where the interesting quantity is a RATE (forks, MIG calls),
    // and paired inc/dec where it is a LEVEL (live XPC connections, webviews).
    // The port crash is the reason the first kind exists: at the moment of
    // death only two guest threads were alive while thread ids had passed
    // 16000, so every "current value" gauge looked healthy. Churn is the
    // signal; a level alone cannot show it.

    private static let guestForks = ManagedAtomic()
    private static let taskInfoCalls = ManagedAtomic()
    private static let threadsCreated = ManagedAtomic()
    private static let threadPortsAcquired = ManagedAtomic()
    private static let threadPortsReleased = ManagedAtomic()
    private static let xpcActive = ManagedAtomic()
    private static let webViewActive = ManagedAtomic()

    /// A guest process fork/clone went through the iSH kernel's fork guard.
    static func noteGuestFork() { guestForks.increment() }

    /// A `task_info` (or equivalent MIG RPC) was issued. Counted separately
    /// from forks because the fix for the port crash was precisely to DECOUPLE
    /// the two: if these track each other again, that regression is back.
    static func noteTaskInfoCall() { taskInfoCalls.increment() }

    /// A thread was created. Feeds the churn figure the crash report needs.
    static func noteThreadCreated() { threadsCreated.increment() }

    /// [T-ish-port-leak-probe] A `mach_thread_self()` send right was taken on
    /// the guest task exit path, and released. These are counted separately so
    /// the log SHOWS the pairing instead of assuming it: if acquired climbs
    /// while released lags, the deallocate is not running and the 1:1
    /// port-per-fork leak is back.
    static func noteThreadPortAcquired() { threadPortsAcquired.increment() }
    static func noteThreadPortReleased() { threadPortsReleased.increment() }

    static func noteXPCConnectionOpened() { xpcActive.increment() }
    static func noteXPCConnectionClosed() { xpcActive.decrement() }
    static func noteWebViewOpened() { webViewActive.increment() }
    static func noteWebViewClosed() { webViewActive.decrement() }

    // MARK: - Port accounting

    /// Number of Mach port names held by this process, or nil if unreadable.
    ///
    /// `mach_port_names` returns two parallel arrays that the caller owns; both
    /// must be `vm_deallocate`d or this leaks the very resource it samples.
    static func currentPortCount() -> Int? {
        var names: mach_port_name_array_t?
        var nameCount: mach_msg_type_number_t = 0
        var types: mach_port_type_array_t?
        var typeCount: mach_msg_type_number_t = 0

        let kr = mach_port_names(mach_task_self_, &names, &nameCount, &types, &typeCount)
        guard kr == KERN_SUCCESS else { return nil }

        if let names {
            vm_deallocate(mach_task_self_,
                          vm_address_t(UInt(bitPattern: names)),
                          vm_size_t(Int(nameCount) * MemoryLayout<mach_port_name_t>.stride))
        }
        if let types {
            vm_deallocate(mach_task_self_,
                          vm_address_t(UInt(bitPattern: types)),
                          vm_size_t(Int(typeCount) * MemoryLayout<mach_port_type_t>.stride))
        }
        return Int(nameCount)
    }

    /// Live thread count for this process, or nil if unreadable.
    static func currentThreadCount() -> Int? {
        var list: thread_act_array_t?
        var count: mach_msg_type_number_t = 0
        guard task_threads(mach_task_self_, &list, &count) == KERN_SUCCESS,
              let list else { return nil }
        // Each returned thread carries a send right; dropping them matters
        // here more than anywhere else in the app.
        for i in 0..<Int(count) {
            mach_port_deallocate(mach_task_self_, list[i])
        }
        vm_deallocate(mach_task_self_,
                      vm_address_t(UInt(bitPattern: list)),
                      vm_size_t(Int(count) * MemoryLayout<thread_t>.stride))
        return Int(count)
    }

    // MARK: - Snapshot

    struct Snapshot {
        let portCount: Int?
        let threadCount: Int?
        let guestForks: UInt64
        let taskInfoCalls: UInt64
        let threadsCreated: UInt64
        /// Threads created since the previous sample — the churn figure. A
        /// gauge cannot express this, and it is what the port crash needed.
        let threadsCreatedDelta: UInt64
        let threadPortsAcquired: UInt64
        let threadPortsReleased: UInt64
        let xpcActive: Int
        let webViewActive: Int

        /// Outstanding `mach_thread_self()` rights. Should sit at ~0; a value
        /// that grows with guestForks is the leak.
        var threadPortsOutstanding: UInt64 {
            threadPortsAcquired >= threadPortsReleased
                ? threadPortsAcquired - threadPortsReleased : 0
        }

        var portLine: String {
            "port_count=\(portCount.map(String.init) ?? "n/a") "
            + "threads=\(threadCount.map(String.init) ?? "n/a") "
            + "threads_new_60s=\(threadsCreatedDelta)"
        }

        var trackLine: String {
            "guestForks=\(guestForks) taskInfoCalls=\(taskInfoCalls) "
            + "threadsCreated=\(threadsCreated) "
            + "threadPorts=\(threadPortsAcquired)/\(threadPortsReleased) "
            + "threadPortsOutstanding=\(threadPortsOutstanding) "
            + "xpcConnActive=\(xpcActive) webviewActive=\(webViewActive)"
        }
    }

    private static let lastThreadsCreated = ManagedAtomic()

    static func snapshot() -> Snapshot {
        let created = threadsCreated.load()
        let previous = lastThreadsCreated.exchange(created)
        return Snapshot(
            portCount: currentPortCount(),
            threadCount: currentThreadCount(),
            guestForks: guestForks.load(),
            taskInfoCalls: taskInfoCalls.load(),
            threadsCreated: created,
            threadsCreatedDelta: created >= previous ? created - previous : 0,
            threadPortsAcquired: threadPortsAcquired.load(),
            threadPortsReleased: threadPortsReleased.load(),
            xpcActive: Int(xpcActive.load()),
            webViewActive: Int(webViewActive.load())
        )
    }

    // MARK: - Periodic sampling

    /// Observed per-process ceiling from the 2026-09-15 reports (the kernel
    /// reported limits of 114835/114866/114883, i.e. it is not a fixed
    /// constant). Used only to derive a warning threshold, never as a hard
    /// bound.
    private static let portLimitEstimate = 114_835
    private static let portWarnThreshold = Int(Double(portLimitEstimate) * 0.8)   // ~91868

    private static let sampleInterval: TimeInterval = 60
    nonisolated(unsafe) private static var timer: DispatchSourceTimer?
    private static let timerQueue = DispatchQueue(label: "com.openminis.resourcediag", qos: .utility)
    private static let started = ManagedAtomic()
    private static let startTime = Date()
    /// Warn once per crossing, not once per sample — a sustained high count
    /// would otherwise print every minute for the rest of the session.
    nonisolated(unsafe) private static var warnedAboveThreshold = false
    /// Previous sample, so a quiet app logs nothing. See sampleAndLog.
    nonisolated(unsafe) private static var lastPortCount = 0
    nonisolated(unsafe) private static var lastGuestForks: UInt64 = 0
    nonisolated(unsafe) private static var lastTaskInfoCalls: UInt64 = 0

    /// Begin periodic sampling. Idempotent.
    static func start() {
        guard started.exchange(1) == 0 else { return }
        let t = DispatchSource.makeTimerSource(queue: timerQueue)
        t.schedule(deadline: .now() + sampleInterval, repeating: sampleInterval)
        t.setEventHandler { sampleAndLog() }
        timer = t
        t.resume()
        logger.info("[ResourceDiag] sampling every \(Int(sampleInterval))s (port warn threshold \(portWarnThreshold))")
    }

    private static func sampleAndLog() {
        let s = snapshot()
        let uptime = Int(Date().timeIntervalSince(startTime))

        // [T-resource-diag] Stay silent while nothing moves. An idle app would
        // otherwise print two lines a minute forever, which buries the samples
        // that matter and costs writer time for no information. Ports are only
        // reported when they shift by more than a trivial amount, and the
        // counter line only when a counter actually advanced.
        let portsMoved = s.portCount.map { abs($0 - lastPortCount) >= 200 } ?? false
        let countersMoved = s.guestForks != lastGuestForks
            || s.taskInfoCalls != lastTaskInfoCalls
            || s.threadsCreatedDelta > 0
        if portsMoved || countersMoved || uptime < Int(sampleInterval) * 2 {
            logger.info("[PortMonitor] \(s.portLine) t=+\(uptime)s")
            logger.info("[ResourceTrack] \(s.trackLine)")
        }
        if let p = s.portCount { lastPortCount = p }
        lastGuestForks = s.guestForks
        lastTaskInfoCalls = s.taskInfoCalls

        guard let ports = s.portCount else { return }
        if ports >= portWarnThreshold {
            if !warnedAboveThreshold {
                warnedAboveThreshold = true
                // The suspect is whichever counter is climbing fastest; print
                // them alongside so the warning is actionable on its own
                // rather than pointing at a separate log line.
                logger.warning("""
                [PortMonitor][WARNING] port_count=\(ports) approaching per-process limit(~\(portLimitEstimate)) \
                — possible port leak. \(s.trackLine)
                """)
            }
        } else {
            warnedAboveThreshold = false
        }
    }

    // MARK: - Crash-report contribution

    /// One-line resource summary for the in-app crash report. Computed at crash
    /// time from counters that are already in memory, plus a fresh port read —
    /// all cheap enough for a signal-adjacent path.
    static func crashReportLines() -> String {
        let s = snapshot()
        var out = "\nPort count:       \(s.portCount.map(String.init) ?? "unavailable")"
        out += "\nLive threads:     \(s.threadCount.map(String.init) ?? "unavailable")"
        out += "\nThreads created:  \(s.threadsCreated) total (+\(s.threadsCreatedDelta) since last sample)"
        out += "\nGuest forks:      \(s.guestForks)"
        out += "\nMIG task_info:    \(s.taskInfoCalls)"
        out += "\nThread ports:     \(s.threadPortsAcquired) taken / \(s.threadPortsReleased) released "
            + "(\(s.threadPortsOutstanding) outstanding)"
        out += "\nXPC connections:  \(s.xpcActive) active"
        out += "\nActive WebViews:  \(s.webViewActive)"
        return out
    }
}

/// Minimal thread-safe counter.
///
/// Swift has no stable atomics in the standard library at this deployment
/// target and `OSAtomic*` is deprecated, so this uses an `os_unfair_lock` —
/// uncontended acquire/release is a handful of instructions, which is well
/// within budget even on the guest-fork path, and unlike a hand-rolled
/// non-atomic read it is actually correct under concurrency.
final class ManagedAtomic: @unchecked Sendable {
    private var value: UInt64 = 0
    private var lock = os_unfair_lock_s()

    init() {}

    func increment() {
        os_unfair_lock_lock(&lock)
        value &+= 1
        os_unfair_lock_unlock(&lock)
    }

    func decrement() {
        os_unfair_lock_lock(&lock)
        if value > 0 { value &-= 1 }
        os_unfair_lock_unlock(&lock)
    }

    func load() -> UInt64 {
        os_unfair_lock_lock(&lock)
        defer { os_unfair_lock_unlock(&lock) }
        return value
    }

    func exchange(_ new: UInt64) -> UInt64 {
        os_unfair_lock_lock(&lock)
        defer { os_unfair_lock_unlock(&lock) }
        let old = value
        value = new
        return old
    }
}

// MARK: - C entry points
//
// [T-resource-diag] `ISHKernel.m` does not import the generated Swift header,
// and the fork guard runs on guest threads deep inside the emulator where an
// ObjC message send would be the most expensive thing in the path. These are
// plain C functions so the kernel side can bump a counter with a direct call.

@_cdecl("minis_diag_note_guest_fork")
public func minis_diag_note_guest_fork() {
    ResourceDiagnostics.noteGuestFork()
}

@_cdecl("minis_diag_note_task_info_call")
public func minis_diag_note_task_info_call() {
    ResourceDiagnostics.noteTaskInfoCall()
}

@_cdecl("minis_diag_note_thread_created")
public func minis_diag_note_thread_created() {
    ResourceDiagnostics.noteThreadCreated()
}

@_cdecl("minis_diag_note_thread_port_acquired")
public func minis_diag_note_thread_port_acquired() {
    ResourceDiagnostics.noteThreadPortAcquired()
}

@_cdecl("minis_diag_note_thread_port_released")
public func minis_diag_note_thread_port_released() {
    ResourceDiagnostics.noteThreadPortReleased()
}
