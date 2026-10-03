import Foundation

/// [T-perf-cpu-probe] CPU attribution for sub agent runs and tool calls.
///
/// The question this exists to answer: when several sub agents run at once, is
/// one agent or one KIND of tool call disproportionately responsible for the
/// CPU load that precedes a stall or a resource kill? Crash reports gave a
/// single instantaneous `app_cpu` reading with nothing to attribute it to.
///
/// ## Why this aggregates instead of logging every call
///
/// A line per tool call does not survive contact with the workload it is meant
/// to measure. The sub agent runs that produced the PORT_SPACE kills issued
/// thousands of shell calls across concurrent agents; two lines each would bury
/// the log, and writing them goes through `LoggingManager`'s serialized writer —
/// the diagnostic would become a contributor to the very contention it is
/// watching.
///
/// So per-call data is folded into an in-memory bucket keyed by tool (or by
/// agent), and one summary line per bucket is emitted every 60s:
///
///     [ToolCallPerf] tool=shell_execute caller=LENS#44bb0d4e n=412 cpu_avg=38.2% cpu_max=91.0% dur_avg=1.8s dur_max=44.0s
///     [SubAgentPerf] agent=LENS n=3 ok=2 err=0 cancel=1 cpu_avg=52.1% cpu_max=88.4% dur_avg=31.5s dur_max=62.0s
///
/// That is strictly MORE useful than the raw stream for the stated goal — "find
/// the tool or agent that eats CPU" is a group-by, which this has already done —
/// while costing a bounded number of lines regardless of concurrency.
///
/// Individual events are logged only when they are outliers worth seeing on
/// their own (`cpuOutlierThreshold`, rate-limited), so a single pathological
/// call is still visible without the other 411 being printed.
///
/// ## Cost
///
/// The CPU reading is `BrowserResourceMonitor.currentAppCPUPercent()`, which is
/// cached with a 2s TTL — deliberately, because the underlying walk is
/// MIG-heavy and must not run per call. Recording an event is a lock, a few
/// arithmetic updates on an existing struct, and no allocation in the common
/// case.
enum PerfProbe {
    private static let logger = AppLogger(category: "PerfProbe")

    /// A single event whose CPU reading is high enough to be worth one line of
    /// its own. Rate-limited so a sustained hot loop cannot spam.
    private static let cpuOutlierThreshold: Double = 150.0   // >1.5 cores
    private static let outlierMinInterval: TimeInterval = 10

    private static let flushInterval: TimeInterval = 60

    // MARK: - Buckets

    private struct Bucket {
        var count = 0
        var okCount = 0
        var errCount = 0
        var cancelCount = 0
        var cpuSum: Double = 0
        var cpuSamples = 0
        var cpuMax: Double = 0
        var durSum: TimeInterval = 0
        var durMax: TimeInterval = 0

        mutating func record(cpu: Double, duration: TimeInterval, outcome: String?) {
            count += 1
            if cpu >= 0 {
                cpuSum += cpu
                cpuSamples += 1
                cpuMax = max(cpuMax, cpu)
            }
            durSum += duration
            durMax = max(durMax, duration)
            // [T-perf-cpu-probe] These strings are `AgentJobState.rawValue`,
            // so they must match that enum: `done` / `cancelled` / `failed` /
            // `timeout`. An earlier version matched "ok", which no state ever
            // produces, so every successful run fell through to the error
            // bucket and the first field logs read `ok=0 err=4` for four runs
            // the registry had recorded as `state=done`.
            //
            // `timeout` is counted as an error on purpose: a run cut short by
            // its budget did not deliver a result, and lumping it with `done`
            // would hide exactly the case worth seeing next to a CPU figure.
            switch outcome {
            case "done": okCount += 1
            case "cancelled": cancelCount += 1
            case .some: errCount += 1      // failed, timeout, anything new
            case nil: break
            }
        }

        var metrics: String {
            let cpuAvg = cpuSamples > 0 ? String(format: "%.1f%%", cpuSum / Double(cpuSamples)) : "n/a"
            let cpuMaxS = cpuSamples > 0 ? String(format: "%.1f%%", cpuMax) : "n/a"
            let durAvg = count > 0 ? String(format: "%.1fs", durSum / Double(count)) : "n/a"
            return "n=\(count) cpu_avg=\(cpuAvg) cpu_max=\(cpuMaxS) "
                + "dur_avg=\(durAvg) dur_max=\(String(format: "%.1fs", durMax))"
        }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var toolBuckets: [String: Bucket] = [:]
    nonisolated(unsafe) private static var agentBuckets: [String: Bucket] = [:]
    nonisolated(unsafe) private static var lastOutlierLog: TimeInterval = 0
    nonisolated(unsafe) private static var flushTimer: DispatchSourceTimer?
    private static let flushQueue = DispatchQueue(label: "com.openminis.perfprobe", qos: .utility)
    nonisolated(unsafe) private static var didStart = false

    /// Begin periodic flushing. Idempotent; safe to call from anywhere.
    static func start() {
        lock.lock()
        defer { lock.unlock() }
        guard !didStart else { return }
        didStart = true
        let t = DispatchSource.makeTimerSource(queue: flushQueue)
        t.schedule(deadline: .now() + flushInterval, repeating: flushInterval)
        t.setEventHandler { flush() }
        flushTimer = t
        t.resume()
    }

    /// Emit one summary line per non-empty bucket and reset. Nothing is logged
    /// when nothing ran, so an idle app stays silent.
    static func flush() {
        lock.lock()
        let tools = toolBuckets
        let agents = agentBuckets
        toolBuckets.removeAll(keepingCapacity: true)
        agentBuckets.removeAll(keepingCapacity: true)
        lock.unlock()

        for (key, b) in tools.sorted(by: { $0.value.cpuMax > $1.value.cpuMax }) {
            // key is "tool|caller" — split so both stay their own log field.
            let parts = key.split(separator: "|", maxSplits: 1).map(String.init)
            let tool = parts.first ?? key
            let caller = parts.count > 1 ? parts[1] : "?"
            logger.info("[ToolCallPerf] tool=\(tool) caller=\(caller) \(b.metrics)")
        }
        for (agent, b) in agents.sorted(by: { $0.value.cpuMax > $1.value.cpuMax }) {
            logger.info("[SubAgentPerf] agent=\(agent) \(b.metrics) "
                + "ok=\(b.okCount) err=\(b.errCount) cancel=\(b.cancelCount)")
        }
    }

    // MARK: - Recording

    private static func record(
        into buckets: inout [String: Bucket],
        key: String, cpu: Double, duration: TimeInterval, outcome: String?
    ) {
        var b = buckets[key] ?? Bucket()
        b.record(cpu: cpu, duration: duration, outcome: outcome)
        buckets[key] = b
    }

    /// Log one line for a single event that stands out, at most once per
    /// `outlierMinInterval`. Caller must NOT hold `lock`.
    private static func noteOutlierIfNeeded(_ line: @autoclosure () -> String, cpu: Double) {
        guard cpu >= cpuOutlierThreshold else { return }
        let now = Date().timeIntervalSince1970
        lock.lock()
        guard now - lastOutlierLog >= outlierMinInterval else { lock.unlock(); return }
        lastOutlierLog = now
        lock.unlock()
        logger.warning("[PerfProbe][HIGH-CPU] \(line())")
    }

    // MARK: - Sub agent lifecycle

    /// A sub agent run is starting. Returns the start time to hand back to
    /// `subAgentEnd`, so no shared state is needed to compute duration.
    ///
    /// Deliberately silent: the start of a run carries no measurement, and the
    /// end event records both the duration and the CPU that matters.
    static func subAgentStart(job: String, agent: String?) -> Date { Date() }

    /// A sub agent run ended. `outcome` separates completion from failure and
    /// cancellation — a cancelled run's CPU profile means something different
    /// from a completed one's, and merging them would hide the case most worth
    /// finding: an agent killed BECAUSE it was pegging the CPU.
    static func subAgentEnd(job: String, agent: String?, outcome: String, started: Date?) {
        let cpu = BrowserResourceMonitor.currentAppCPUPercent()
        let duration = started.map { Date().timeIntervalSince($0) } ?? 0
        let name = agent ?? "builtin"

        lock.lock()
        record(into: &agentBuckets, key: name, cpu: cpu, duration: duration, outcome: outcome)
        lock.unlock()

        noteOutlierIfNeeded(
            "job=\(job) agent=\(name) outcome=\(outcome) cpu=\(String(format: "%.1f%%", cpu)) "
            + "duration=\(String(format: "%.1fs", duration))",
            cpu: cpu)
    }

    // MARK: - Tool calls

    static func toolBefore(tool: String, job: String) -> Date { Date() }

    static func toolAfter(tool: String, job: String, started: Date) {
        let cpu = BrowserResourceMonitor.currentAppCPUPercent()
        let duration = Date().timeIntervalSince(started)

        // [T-perf-cpu-probe] Bucket per (tool, caller), not per tool.
        //
        // The first field logs showed `tool=browser_use cpu_avg=123%` with no
        // way to tell whether that was one agent spinning or load spread across
        // several — which is the actual question under concurrency. Keying by
        // caller as well answers it directly, and the per-tool total is still
        // recoverable by summing the rows.
        //
        // Cardinality is bounded in practice: at most a handful of live jobs
        // times the tools they use, and buckets are cleared on every flush, so
        // a long session cannot accumulate them.
        lock.lock()
        record(into: &toolBuckets, key: "\(tool)|\(job)", cpu: cpu, duration: duration, outcome: nil)
        lock.unlock()

        noteOutlierIfNeeded(
            "tool=\(tool) caller=\(job) cpu=\(String(format: "%.1f%%", cpu)) "
            + "duration=\(String(format: "%.1fs", duration))",
            cpu: cpu)
    }
}
