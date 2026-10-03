import Foundation
import UIKit
import Darwin.Mach

private let logger = AppLogger(category: "BrowserResourceMonitor")

/// Resource sampling for the browser-use tool path.
///
/// Motivation: a `browser_use` action issued while the app is backgrounded was
/// observed hanging for ~287 minutes without returning. The suspicion is that
/// iOS suspends the out-of-process WebContent that WKWebView drives, so the
/// action's continuation never resumes — as opposed to an in-app deadlock. We
/// don't have enough signal to tell those apart, so this samples the resource
/// picture around every action.
///
/// Deliberately NOT reusing `SystemResourceMonitor` (AIChatView.swift): that is
/// a `@Published` UI ticker on a 2s main-run-loop `Timer` scoped to a visible
/// sheet, samples *system-wide* CPU via `host_statistics`, and stops when its
/// view disappears — precisely the moment we most need data (backgrounded, no
/// UI). `CrashReporter`'s samplers are `private` and snapshot-only. So this is a
/// separate, log-only, background-safe sampler; nothing here feeds SwiftUI.
///
/// ## What we can and cannot see (read this before adding a memory field)
///
/// **We cannot measure WKWebView's memory from inside the app.** Not "we haven't
/// found the API" — it is closed off by design, and assuming otherwise will make
/// you misread these logs:
///
/// - Rendering runs in a separate process (`com.apple.WebKit.WebContent`). Its
///   footprint is **not** counted in our `task_info`, and our app will not even
///   reliably get a memory warning when WebContent is under pressure.
/// - Reading another process's footprint needs `task_for_pid()` (barred by the
///   sandbox) or `proc_pid_rusage()` (won't cross to WebContent). And we can't
///   get its pid anyway: the public WebKit headers expose no process identifier.
///   `_webProcessIdentifier` is SPI — an App Store review risk, so not an option.
/// - Instruments/Activity Monitor *can* see it only because they are external
///   debuggers outside our sandbox. That capability does not exist at runtime.
///
/// So WebContent dies on its OWN jetsam budget (empirically ~300-450MB for a web
/// page, no fixed limit — it varies with device RAM and system load), and iOS
/// kills just that child process while our app survives untouched.
///
/// The practical consequence for the fields below: `appAvailableMemoryMB` is
/// **our** headroom and says nothing about WebContent. WebContent can climb to
/// its ceiling and get killed while that number does not move at all. It is kept
/// as an app-side baseline ONLY — never read it as "the browser had plenty of
/// memory". The one trustworthy signal that WebContent died is
/// `webViewWebContentProcessDidTerminate`, with `probeWebContentLiveness` as a
/// fallback for when that callback doesn't fire (it often doesn't if the process
/// dies mid-render).
@MainActor
final class BrowserResourceMonitor {
    static let shared = BrowserResourceMonitor()

    /// Sampling cadence while at least one browser action is in flight. Low
    /// frequency on purpose: this can run for minutes in the background, where
    /// both CPU and log volume are budgeted.
    private static let sampleInterval: TimeInterval = 8.0

    /// An action still un-returned after this long, while backgrounded, is the
    /// pathology we're hunting. Emits a one-shot loud snapshot.
    private static let stuckThreshold: TimeInterval = 30.0

    /// Evidence that the WebContent process backing a tab is still alive.
    ///
    /// Needed because `webViewWebContentProcessDidTerminate` is documented as
    /// unreliable: it frequently does NOT fire when the process dies before the
    /// page finishes rendering — which is exactly our suspected scenario. When a
    /// WebContent process is killed, its `WKWebView` is left blank: `title` goes
    /// empty and `url` goes nil. So if an action is stuck AND the view has gone
    /// blank, that is strong (if indirect) evidence the process died silently.
    struct WebContentLiveness {
        let hasURL: Bool
        let hasTitle: Bool
        /// Blank view under a stuck action ⇒ WebContent very likely dead.
        var looksBlank: Bool { !hasURL && !hasTitle }
    }

    /// Injected by `BrowserTabPool` so this stays out of the browser layer:
    /// given a tab id, report whether its WKWebView still looks alive.
    var livenessProbe: ((Int) -> WebContentLiveness?)?

    /// One in-flight browser action.
    private struct Inflight {
        let action: String
        let url: String?
        let tabId: Int?
        let startedAt: Date
        let startedInBackground: Bool
        /// Fires once at `stuckThreshold`; cancelled if the action returns first.
        var stuckTask: Task<Void, Never>?
        var stuckReported = false
    }

    private var inflight: [UUID: Inflight] = [:]
    private var samplingTask: Task<Void, Never>?
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var memoryWarningObserver: NSObjectProtocol?

    private init() {
        observeLifecycle()
    }

    // MARK: - Snapshot

    /// One sample of everything we can legally see.
    struct Snapshot {
        /// `active` / `inactive` / `background`.
        let appState: String
        /// Our own process footprint (MB). Does NOT include WebContent, which
        /// is a separate process — this is a baseline to contrast against, not
        /// a measure of the browser.
        let appMemoryMB: Int
        /// `os_proc_available_memory()` (MB): how much more *this* process may
        /// allocate before jetsam.
        ///
        /// NOT a system-wide or browser-wide figure. WebContent is jetsammed on
        /// its own separate budget, so it can grow to its ceiling and be killed
        /// while this number sits perfectly still. Do not read a healthy value
        /// here as "the browser wasn't short on memory" — it cannot tell you that.
        let appAvailableMemoryMB: Int
        /// Our process's CPU, summed across live threads (%). 100 = one core.
        let appCPUPercent: Double
        /// Genuinely system-wide (unlike the memory fields above), but it is a
        /// *thermal* signal only. Severe thermal pressure makes the system
        /// throttle and shed processes, so it is context for a hang — it is not
        /// a memory reading and must not be used as a stand-in for one.
        let thermalState: String
        let lowPowerMode: Bool
        /// Only meaningful while backgrounded; `nil` in the foreground, where
        /// UIKit reports a sentinel (`.greatestFiniteMagnitude`).
        let backgroundTimeRemaining: TimeInterval?

        var logLine: String {
            // `app_avail_mem`, not `sys_avail_mem`: it is OUR jetsam headroom and
            // excludes WebContent entirely. Named to stop a future reader
            // concluding "memory was fine" from a browser hang. See the type doc.
            var parts: [String] = [
                "app_state=\(appState)",
                "app_mem=\(appMemoryMB)MB",
                "app_avail_mem=\(appAvailableMemoryMB)MB",
                "app_cpu=\(String(format: "%.1f", appCPUPercent))%",
                "thermal=\(thermalState)",
                "low_power=\(lowPowerMode)",
            ]
            if let remaining = backgroundTimeRemaining {
                parts.append("bg_time_left=\(String(format: "%.0f", remaining))s")
            }
            return parts.joined(separator: " ")
        }
    }

    /// Sample now. Cheap enough (three Mach calls) to run inline on the main
    /// actor; callers are already `@MainActor` and this avoids the hop that
    /// reading `UIApplication.shared.applicationState` would otherwise require.
    func snapshot() -> Snapshot {
        let state = UIApplication.shared.applicationState
        let stateName: String
        switch state {
        case .active: stateName = "active"
        case .inactive: stateName = "inactive"
        case .background: stateName = "background"
        @unknown default: stateName = "unknown"
        }

        let thermal: String
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: thermal = "nominal"
        case .fair: thermal = "fair"
        case .serious: thermal = "serious"
        case .critical: thermal = "critical"
        @unknown default: thermal = "unknown"
        }

        // UIKit returns a huge sentinel when we're not actually in a background
        // task; reporting "1.79e+308s left" would be noise.
        let rawRemaining = UIApplication.shared.backgroundTimeRemaining
        let remaining: TimeInterval? =
            (state == .background && rawRemaining < 24 * 60 * 60) ? rawRemaining : nil

        return Snapshot(
            appState: stateName,
            appMemoryMB: Self.appMemoryMB(),
            appAvailableMemoryMB: Self.appAvailableMemoryMB(),
            appCPUPercent: Self.appCPUPercent(),
            thermalState: thermal,
            lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled,
            backgroundTimeRemaining: remaining
        )
    }

    // MARK: - Action tracking

    /// Call immediately before dispatching a browser action. Returns a handle to
    /// pass to `actionDidFinish`.
    @discardableResult
    func actionWillStart(action: String, url: String?, tabId: Int?) -> UUID {
        let id = UUID()
        let snap = snapshot()
        let isBackground = snap.appState == "background"

        var entry = Inflight(
            action: action,
            url: url,
            tabId: tabId,
            startedAt: Date(),
            startedInBackground: isBackground,
            stuckTask: nil,
            stuckReported: false
        )

        let target = url.map { " url=\($0)" } ?? ""
        let tab = tabId.map { " tab=\($0)" } ?? ""
        logger.info("[BrowserToolDiag] start action=\(action)\(tab)\(target) \(snap.logLine)")

        if isBackground {
            // The exact condition under investigation: iOS may suspend the
            // WebContent process, and the action's continuation then never
            // resumes.
            logger.warning(
                "[BrowserToolDiag] backgrounded-dispatch action=\(action)\(tab) — "
                + "browser action issued while app is in background; WebContent may be "
                + "suspended by the system and this action may never return. \(snap.logLine)"
            )
        }

        entry.stuckTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.stuckThreshold * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self?.reportStuck(id: id)
        }

        inflight[id] = entry
        startSamplingIfNeeded()
        return id
    }

    /// Call when the action returns — success, failure, or throw.
    func actionDidFinish(_ id: UUID, success: Bool, error: String? = nil) {
        guard let entry = inflight.removeValue(forKey: id) else { return }
        entry.stuckTask?.cancel()

        let elapsedMs = Int(Date().timeIntervalSince(entry.startedAt) * 1000)
        let snap = snapshot()
        let tab = entry.tabId.map { " tab=\($0)" } ?? ""
        let outcome = success ? "ok" : "fail"
        let errPart = error.map { " error=\($0)" } ?? ""
        // A backgrounded action that eventually returns is still worth flagging:
        // it tells us the suspension is recoverable rather than terminal.
        let bgPart = entry.startedInBackground ? " started_in_background=true" : ""

        logger.info(
            "[BrowserToolDiag] end action=\(entry.action)\(tab) result=\(outcome) "
            + "elapsed=\(elapsedMs)ms\(bgPart)\(errPart) \(snap.logLine)"
        )

        // Anything that was declared stuck and then came back tells us how long
        // the suspension actually lasted — the number we can't get from the
        // 287-minute report.
        if entry.stuckReported {
            logger.warning(
                "[BrowserToolDiag] stuck-recovered action=\(entry.action)\(tab) "
                + "elapsed=\(elapsedMs)ms result=\(outcome) — action returned after being "
                + "flagged stuck. \(snap.logLine)"
            )
        }

        stopSamplingIfIdle()
    }

    /// Fired once per action that blows through `stuckThreshold`.
    private func reportStuck(id: UUID) {
        guard var entry = inflight[id], !entry.stuckReported else { return }
        entry.stuckReported = true
        inflight[id] = entry

        let elapsed = Int(Date().timeIntervalSince(entry.startedAt))
        let snap = snapshot()
        let tab = entry.tabId.map { " tab=\($0)" } ?? ""
        let target = entry.url.map { " url=\($0)" } ?? ""

        logger.warning(
            "[BrowserToolDiag] STUCK action=\(entry.action)\(tab)\(target) "
            + "elapsed=\(elapsed)s started_in_background=\(entry.startedInBackground) "
            + "still_running — \(snap.logLine)"
        )

        // We cannot read WebContent's memory, and its death callback is known to
        // miss when it dies mid-render. So on a stuck action, ask whether the
        // WKWebView has gone blank — the only in-app evidence available that the
        // process died underneath us without telling anyone.
        guard let tabId = entry.tabId, let live = livenessProbe?(tabId) else { return }
        if live.looksBlank {
            logger.error(
                "[BrowserToolDiag] webcontent-presumed-dead tab=\(tabId) "
                + "action=\(entry.action) elapsed=\(elapsed)s — WKWebView is blank "
                + "(url=nil title=empty) while the action is still pending, and no "
                + "termination callback fired. WebContent was very likely killed by "
                + "the system; this action will never return on its own. \(snap.logLine)"
            )
        } else {
            // Live view + stuck action ⇒ NOT a dead WebContent. Points instead at
            // a suspended/throttled process or a stranded continuation. Recording
            // the negative is what lets us tell those apart later.
            logger.warning(
                "[BrowserToolDiag] webcontent-alive tab=\(tabId) action=\(entry.action) "
                + "elapsed=\(elapsed)s has_url=\(live.hasURL) has_title=\(live.hasTitle) "
                + "— WKWebView still holds its page, so this is NOT a WebContent kill; "
                + "suspect suspension or a stranded continuation."
            )
        }
    }

    // MARK: - Periodic sampling

    /// Runs only while something is in flight, so an idle app costs nothing.
    private func startSamplingIfNeeded() {
        guard samplingTask == nil, !inflight.isEmpty else { return }
        samplingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.sampleInterval * 1_000_000_000))
                guard !Task.isCancelled else { return }
                guard let self else { return }
                guard await self.emitPeriodicSample() else { return }
            }
        }
    }

    /// Returns false when there's nothing left to watch, ending the loop.
    private func emitPeriodicSample() -> Bool {
        guard !inflight.isEmpty else {
            samplingTask = nil
            return false
        }
        let snap = snapshot()
        let now = Date()
        let running = inflight.values
            .map { entry -> String in
                let secs = Int(now.timeIntervalSince(entry.startedAt))
                let tab = entry.tabId.map { "#\($0)" } ?? "#-"
                return "\(entry.action)\(tab)@\(secs)s"
            }
            .sorted()
            .joined(separator: ",")

        logger.info(
            "[BrowserResourceMonitor] sample inflight=\(inflight.count) "
            + "actions=[\(running)] \(snap.logLine)"
        )
        return true
    }

    private func stopSamplingIfIdle() {
        guard inflight.isEmpty else { return }
        samplingTask?.cancel()
        samplingTask = nil
    }

    // MARK: - Lifecycle / pressure correlation

    /// Foreground↔background transitions and memory warnings are logged with the
    /// same tag so a single grep can line them up against action start/end times
    /// — the whole point is correlating "went to background" with "stopped
    /// returning".
    private func observeLifecycle() {
        let center = NotificationCenter.default
        let transitions: [(Notification.Name, String)] = [
            (UIApplication.didEnterBackgroundNotification, "didEnterBackground"),
            (UIApplication.willEnterForegroundNotification, "willEnterForeground"),
            (UIApplication.didBecomeActiveNotification, "didBecomeActive"),
            (UIApplication.willResignActiveNotification, "willResignActive"),
        ]
        for (name, label) in transitions {
            let token = center.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated {
                    BrowserResourceMonitor.shared.logLifecycle(label)
                }
            }
            lifecycleObservers.append(token)
        }

        memoryWarningObserver = center.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { _ in
            MainActor.assumeIsolated {
                BrowserResourceMonitor.shared.logMemoryWarning()
            }
        }
    }

    private func logLifecycle(_ transition: String) {
        let snap = snapshot()
        let count = inflight.count
        let line = "[BrowserToolDiag] lifecycle=\(transition) inflight_browser_actions=\(count) \(snap.logLine)"
        // Crossing into the background WITH work in flight is the setup for the
        // hang, so make that one findable at WARN.
        if count > 0, transition == "didEnterBackground" {
            logger.warning(line + " — browser actions in flight while backgrounding")
        } else {
            logger.info(line)
        }
    }

    private func logMemoryWarning() {
        let snap = snapshot()
        logger.warning(
            "[BrowserToolDiag] memory-warning inflight_browser_actions=\(inflight.count) \(snap.logLine)"
        )
    }

    /// Called from `webViewWebContentProcessDidTerminate` — the single
    /// unambiguous "the system killed the browser" signal. Everything else here
    /// is inference; this is proof.
    func webContentProcessDidTerminate(tabId: Int?, url: String?) {
        let snap = snapshot()
        let tab = tabId.map { " tab=\($0)" } ?? ""
        let target = url.map { " url=\($0)" } ?? ""
        logger.error(
            "[BrowserToolDiag] webcontent-terminated\(tab)\(target) "
            + "inflight_browser_actions=\(inflight.count) — WKWebView WebContent process was "
            + "killed by the system (OOM/jetsam). Any in-flight action on this tab will not "
            + "return. \(snap.logLine)"
        )
    }

    // MARK: - Mach sampling

    /// Resident footprint of *this* process (MB). `phys_footprint` is what
    /// jetsam actually judges us on. Excludes WebContent by construction.
    private static func appMemoryMB() -> Int {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard kr == KERN_SUCCESS else { return -1 }
        return Int(info.phys_footprint) / (1024 * 1024)
    }

    /// Remaining allocation budget before *this* process is jetsammed (MB).
    /// Scoped to us; WebContent has its own independent budget we cannot read.
    private static func appAvailableMemoryMB() -> Int {
        Int(os_proc_available_memory()) / (1024 * 1024)
    }

    /// Sum of per-thread CPU for this process (%). 100.0 == one saturated core.
    /// Unlike `SystemResourceMonitor`'s `host_statistics`, this is scoped to us,
    /// so a pegged WebContent will NOT show up here — a low number alongside a
    /// stuck action is itself evidence that we're blocked on another process
    /// rather than spinning.
    /// [T-perf-cpu-probe] Shared, nonisolated CPU reading, CACHED.
    ///
    /// `appCPUPercent()` below is `private` and this type is `@MainActor`, but
    /// sub agents and tool calls run off the main actor and need the same
    /// number. Rather than duplicate the thread walk, expose it — one
    /// implementation, one definition of the metric, so `[SubAgentPerf]` and
    /// `[ToolCallPerf]` lines are directly comparable with the `app_cpu` field
    /// this monitor already emits.
    ///
    /// The cache is not an optimisation, it is a correctness requirement for
    /// the callers. `appCPUPercent()` walks every thread with `task_threads` +
    /// per-thread `thread_info` — both MIG RPCs, and `task_threads` returns a
    /// send right per thread. Calling that twice per tool call, with several
    /// sub agents running, is precisely the MIG-in-a-hot-path pattern that
    /// caused the PORT_SPACE kills this diagnostic exists to investigate.
    ///
    /// 2s TTL: short enough that a before/after pair around anything slower
    /// than that still shows real movement, long enough that a burst of quick
    /// tool calls shares one reading instead of issuing dozens of walks.
    private static let cpuCacheTTL: TimeInterval = 2.0
    nonisolated(unsafe) private static var cpuCacheValue: Double = -1
    nonisolated(unsafe) private static var cpuCacheStamp: TimeInterval = 0
    private static let cpuCacheLock = NSLock()

    nonisolated static func currentAppCPUPercent() -> Double {
        let now = Date().timeIntervalSince1970
        cpuCacheLock.lock()
        if cpuCacheStamp != 0, now - cpuCacheStamp < cpuCacheTTL {
            defer { cpuCacheLock.unlock() }
            return cpuCacheValue
        }
        cpuCacheLock.unlock()

        let fresh = appCPUPercent()

        cpuCacheLock.lock()
        cpuCacheValue = fresh
        cpuCacheStamp = now
        cpuCacheLock.unlock()
        return fresh
    }

    nonisolated private static func appCPUPercent() -> Double {
        var threadList: thread_act_array_t?
        var threadCount: mach_msg_type_number_t = 0
        guard task_threads(mach_task_self_, &threadList, &threadCount) == KERN_SUCCESS,
              let threads = threadList else { return -1 }

        defer {
            vm_deallocate(
                mach_task_self_,
                vm_address_t(UInt(bitPattern: threads)),
                vm_size_t(Int(threadCount) * MemoryLayout<thread_t>.stride)
            )
        }

        var total: Double = 0
        for i in 0..<Int(threadCount) {
            var info = thread_basic_info()
            var count = mach_msg_type_number_t(THREAD_INFO_MAX)
            let kr = withUnsafeMutablePointer(to: &info) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                    thread_info(threads[i], thread_flavor_t(THREAD_BASIC_INFO), $0, &count)
                }
            }
            guard kr == KERN_SUCCESS else { continue }
            // Idle threads report a stale cpu_usage; excluding them keeps the
            // number comparable across samples.
            if info.flags & TH_FLAGS_IDLE == 0 {
                total += Double(info.cpu_usage) / Double(TH_USAGE_SCALE) * 100.0
            }
        }
        return total
    }
}
