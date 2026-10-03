import Foundation
import UserNotifications
import UIKit

private let logger = AppLogger(category: "ScheduledJobRunner")

// [T-p2-minis-scheduled] The execution half of component B: arms a job's
// trigger (once / loop / cron / on-completion / immediate) as an in-process
// Task and, when it fires, lands the job's prompt on its target through
// component A (`submitProgrammaticPrompt`).
//
// Everything here is best effort within the process lifetime (design v4
// §8.3 / §12): iOS has no AlarmManager, so a timer dies with the app. The
// insurance notification (§3 of the schedule design) is the only thing that
// survives a kill, and it is honest about that — it never claims a result.

@MainActor
enum ScheduledJobRunner {

    static let sourceTag = "scheduled"

    // MARK: - Arm

    /// Arm (or re-arm) the job's trigger. Cancels any previous timer task.
    static func arm(_ job: AgentJob) {
        job.task?.cancel()
        job.task = nil
        guard job.isEnabled, job.state == .pending else {
            logger.info("[Sched] not arming \(job.logLabel) enabled=\(job.isEnabled)")
            return
        }
        switch job.trigger {
        case .immediate:
            job.nextFireAt = Date()
            job.task = Task { @MainActor in await fire(job) ; finishIfExhausted(job) }

        case .once(let after):
            let at = Date().addingTimeInterval(after)
            job.nextFireAt = at
            scheduleInsurance(job, at: at)
            job.task = Task { @MainActor in
                guard await sleepUntil(at) else { return }
                await fire(job)
                AgentJobRegistry.shared.finish(job.id, state: .done, result: nil, keepIfChildRunning: true)
            }

        case .loop(let interval, _):
            let first = Date().addingTimeInterval(interval)
            job.nextFireAt = first
            scheduleInsurance(job, at: first)
            job.task = Task { @MainActor in
                var next = first
                while !Task.isCancelled {
                    guard await sleepUntil(next) else { return }
                    await fire(job)
                    if let r = job.remaining, r <= 0 {
                        AgentJobRegistry.shared.finish(job.id, state: .done, result: nil, keepIfChildRunning: true)
                        return
                    }
                    next = Date().addingTimeInterval(interval)
                    job.nextFireAt = next
                    scheduleInsurance(job, at: next)
                }
            }

        case .cron(let hour, let minute, let days, let start, let end):
            guard let first = nextCronDate(hour: hour, minute: minute, days: days, start: start, end: end, from: Date()) else {
                logger.info("[Sched] cron has no future fire (window ended) — finishing \(job.id.prefix(8))")
                AgentJobRegistry.shared.finish(job.id, state: .done, result: nil)
                return
            }
            job.nextFireAt = first
            scheduleInsurance(job, at: first)
            job.task = Task { @MainActor in
                var next = first
                while !Task.isCancelled {
                    guard await sleepUntil(next) else { return }
                    await fire(job)
                    guard let n = nextCronDate(hour: hour, minute: minute, days: days, start: start, end: end,
                                               from: Date().addingTimeInterval(60)) else {
                        AgentJobRegistry.shared.finish(job.id, state: .done, result: nil, keepIfChildRunning: true)
                        return
                    }
                    next = n
                    job.nextFireAt = n
                    scheduleInsurance(job, at: n)
                }
            }

        case .onCompletion(let ofJobId):
            job.nextFireAt = nil
            job.task = Task { @MainActor in
                let seq = NotificationCenter.default.notifications(named: .agentJobDidFinish)
                for await note in seq {
                    if Task.isCancelled { return }
                    if (note.object as? String) == ofJobId { break }
                }
                await fire(job, upstreamJobId: ofJobId)
                AgentJobRegistry.shared.finish(job.id, state: .done, result: nil, keepIfChildRunning: true)
            }
        }
        logger.info("[Sched] ARMED \(job.logLabel) next=\(job.nextFireAt.map { Self.iso($0) } ?? "event")")
    }

    /// `run --id`: fire regardless of the trigger, off-schedule. The armed
    /// timer (if any) keeps its own cadence.
    static func fireNow(_ job: AgentJob) async -> [String: Any] {
        let sid = await fire(job)
        return ["fired": sid != nil, "session_id": sid ?? NSNull()]
    }

    // MARK: - Fire

    /// Land the prompt on the target. Returns the session the prompt went to.
    @discardableResult
    private static func fire(_ job: AgentJob, upstreamJobId: String? = nil) async -> String? {
        let registry = AgentJobRegistry.shared
        var prompt = job.prompt ?? ""
        if let up = upstreamJobId, let upstream = registry.job(id: up) {
            prompt = prompt.replacingOccurrences(of: "{{result}}", with: upstream.resultText ?? "")
        }
        // [T-scheduled-task-card] Wrap the injected turn in the callback
        // envelope so the UI renders it as a card instead of a right-aligned
        // user bubble — it is the scheduler firing, not something the user
        // typed. `scheduledEnvelope` keeps `roundPrefix`'s line inside the
        // body verbatim, so what the MODEL reads is unchanged.
        let text = scheduledEnvelope(job, prompt: prompt)
        cancelInsurance(job)

        switch job.target {
        case .new:
            guard let entry = resolveModel(for: job) else {
                logger.warning("[Sched] fire \(job.id.prefix(8)): no model to run on")
                registry.recordFire(job.id, sessionId: nil, ok: false)
                return nil
            }
            let session = await ChatStore.shared.createSession(modelId: entry.model.id,
                                                               title: job.label.map { "Scheduled · \($0)" },
                                                               source: sourceTag)
            if let override = job.modelEntryId, ProviderConfigStore.shared.entry(for: override) != nil {
                ProviderConfigStore.shared.setBinding(
                    SessionModelBinding(sessionId: session.id, primarySource: .directEntry(modelEntryId: override)),
                    for: session.id)
            }
            applyThinkingLevel(job, sessionId: session.id, entry: entry)
            let (vm, _) = ViewModelCache.shared.getOrCreate(for: session.id)
            await vm.loadSession()
            vm.sessionSource = sourceTag
            let outcome = vm.submitProgrammaticPrompt(text, origin: .job(jobId: job.id), silent: true)
            logger.info("[Sched] FIRE \(job.logLabel) → new session \(session.id.prefix(8)) outcome=\(String(describing: outcome))")
            if case .rejected = outcome { registry.recordFire(job.id, sessionId: session.id, ok: false) }
            else { registry.recordFire(job.id, sessionId: session.id, ok: true) }
            return session.id

        case .followUp(let sid):
            guard await ChatStore.shared.getSession(sid) != nil else {
                logger.warning("[Sched] fire \(job.id.prefix(8)): follow-up session \(sid.prefix(8)) is gone — abort")
                registry.recordFire(job.id, sessionId: nil, ok: false)
                return nil
            }
            let (vm, fresh) = ViewModelCache.shared.getOrCreate(for: sid)
            if fresh || vm.messages.isEmpty { await vm.loadSession() }
            applyThinkingLevel(job, sessionId: sid, entry: vm.resolveCurrentEntry())
            let outcome = vm.submitProgrammaticPrompt(text, origin: .job(jobId: job.id), silent: true)
            logger.info("[Sched] FIRE \(job.logLabel) → follow-up \(sid.prefix(8)) outcome=\(String(describing: outcome))")
            if case .rejected = outcome { registry.recordFire(job.id, sessionId: sid, ok: false) }
            else { registry.recordFire(job.id, sessionId: sid, ok: true) }
            return sid

        case .rerun(let sid, let messageId):
            // The sessions bridge blocks its caller with a semaphore and
            // hops to the main actor inside — never call it from here directly.
            let ok: Bool = await Task.detached {
                let dict = SessionsOffloadBridge.retryMessage(sessionId: sid, messageId: messageId, attachmentPaths: [])
                return (dict["ok"] as? Bool) ?? false
            }.value
            logger.info("[Sched] FIRE \(job.logLabel) → rerun \(sid.prefix(8))/\(messageId.prefix(8)) ok=\(ok)")
            registry.recordFire(job.id, sessionId: sid, ok: ok)
            return ok ? sid : nil

        case .childOfCurrent(let parentSid, _):
            // [T-agents-debug-only] Defence in depth behind the CLI check:
            // a job armed while Agents was on must not spawn a helper after
            // the switch went off (or in a Release build, where it is
            // always off). Recorded as a failed fire; no child is created.
            guard AgentToolSwitch.agents.isEnabled else {
                logger.warning("[Sched] fire \(job.id.prefix(8)): child-of-current refused — Sub Agents are off (Settings › Sub Agents)")
                registry.recordFire(job.id, sessionId: nil, ok: false)
                return nil
            }
            let (parentVM, parentFresh) = ViewModelCache.shared.getOrCreate(for: parentSid)
            if parentFresh { await parentVM.loadSession() }
            guard let resolution = SubAgentModelResolver.resolve(subAgent: nil, parent: parentVM) else {
                logger.warning("[Sched] fire \(job.id.prefix(8)): parent has no model")
                registry.recordFire(job.id, sessionId: nil, ok: false)
                return nil
            }
            let title = job.label ?? job.title
            let session = await ChatStore.shared.createSession(modelId: resolution.entry.model.id,
                                                               title: AgentJobRegistry.childSessionTitle(title),
                                                               source: sourceTag,
                                                               parentSessionId: parentSid,
                                                               parentToolUseId: nil)
            ProviderConfigStore.shared.setBinding(
                SessionModelBinding(sessionId: session.id, primarySource: resolution.source), for: session.id)
            applyThinkingLevel(job, sessionId: session.id, entry: resolution.entry)
            let (child, _) = ViewModelCache.shared.getOrCreate(for: session.id)
            await child.loadSession()
            child.sessionSource = sourceTag
            child.helperConfig = HelperConfig(parentSessionId: parentSid, parentToolUseId: "",
                                              jobId: job.id, maxTurns: AIChatViewModel.helperMaxTurns,
                                              title: title, modelOrigin: resolution.origin)
            child.memoryEnabled = false
            child.suppressGeneralCompletionNotification = true
            child.browserTabPool = parentVM.browserTabPool
            job.modelOrigin = resolution.origin.rawValue
            // [T-agent-model-identity] A scheduled child has no parent block;
            // the job carries its identity so the completion callback and
            // the transcript page still say what it ran on.
            job.modelIdentity = HelperModelIdentity.make(resolution: resolution)
            let outcome = child.submitProgrammaticPrompt(text, origin: .job(jobId: job.id), silent: true)
            logger.info("[Sched] FIRE \(job.logLabel) → child \(session.id.prefix(8)) of \(parentSid.prefix(8)) outcome=\(String(describing: outcome))")
            guard outcome == .sent else {
                registry.recordFire(job.id, sessionId: session.id, ok: false)
                return nil
            }
            // The registry's loop-end observer closes the job and runs
            // `then: .followUpParent`; the insurance notification covers a
            // kill in between (design §8.6).
            registry.markRunning(job.id, sessionId: session.id)
            JobInsuranceNotification.schedule(
                jobId: job.id,
                at: Date().addingTimeInterval(TimeInterval(AIChatViewModel.helperMaxMinutes * 60)),
                title: AppLocalized("Minis agent"),
                body: String(format: AppLocalized("The agent \"%@\" may have finished or been interrupted. Open to check."), title),
                userInfo: ["sessionId": parentSid, "childSessionId": session.id, "helperJobId": job.id])
            return session.id
        }
    }

    private static func finishIfExhausted(_ job: AgentJob) {
        if job.state == .pending {
            AgentJobRegistry.shared.finish(job.id, state: .done, result: nil, keepIfChildRunning: true)
        }
    }

    // MARK: - Pieces

    /// Structured prefix (schedule design §1.5): the model must know which
    /// fire this is, and how many remain, to decide when to wrap up.
    /// [T-scheduled-task-card] The scheduled firing wrapped as an
    /// `<agent_callback kind="scheduled">` envelope.
    ///
    /// The body is EXACTLY what used to be submitted — `roundPrefix(job) +
    /// prompt` — so the model's input is byte-identical to before this change.
    /// That is deliberate: the prompt instructs the model to decide from the
    /// remaining count whether to start wrapping up, and quietly relocating
    /// that fact into an XML attribute it may not attend to would be a
    /// behaviour change disguised as a display change. The attributes exist
    /// only so the card can show fire/remaining without re-parsing prose.
    static func scheduledEnvelope(_ job: AgentJob, prompt: String) -> String {
        var fireIndex: Int?
        var remaining: Int?
        switch job.trigger {
        case .loop, .cron:
            fireIndex = job.firedCount + 1
            remaining = job.remaining.map { max(0, $0 - 1) }
        default:
            break
        }
        // [T-scheduled-next-fire] When this job will fire AGAIN, so the card can
        // say the task is still planned rather than leaving the user to assume
        // the card's appearance meant it finished.
        //
        // Computed here, not read from `job.nextFireAt`: the envelope is built
        // BEFORE `fire()` runs, so that field still holds the fire happening
        // right now. `arm`'s own scheduling maths is mirrored rather than
        // shared because arm computes the next date only AFTER the fire
        // completes, which is too late to put in this envelope.
        //
        // nil means "no more" — the card then says the task is done instead of
        // promising a fire that will never come.
        let nextFire: Date? = {
            switch job.trigger {
            case .loop(let interval, _):
                // `remaining` is decremented by recordFire during this very
                // firing, so the value here is what will be left afterwards.
                if let r = job.remaining, r - 1 <= 0 { return nil }
                return Date().addingTimeInterval(interval)
            case .cron(let h, let m, let days, let start, let end):
                return nextCronDate(hour: h, minute: m, days: days, start: start, end: end,
                                    from: Date().addingTimeInterval(60))
            case .once, .immediate, .onCompletion:
                return nil
            }
        }()
        let callback = AgentCallback(
            kind: .scheduled,
            jobId: job.id,
            childSessionId: nil,
            title: job.label ?? job.title,
            // Not a lifecycle state — this envelope reports one firing that is
            // happening now, and `running` is what the card's neutral styling
            // and `localizedStatus` already key off.
            status: "running",
            tier: nil, elapsed: nil, tool: nil, activity: nil, turn: nil,
            summary: nil,
            body: roundPrefix(job) + prompt,
            fireIndex: fireIndex,
            remaining: remaining,
            triggerLabel: job.trigger.logLabel,
            nextFireAt: nextFire.map { iso($0) }
        )
        return callback.xml
    }

    static func roundPrefix(_ job: AgentJob) -> String {
        let name = job.label ?? job.title
        switch job.trigger {
        case .loop, .cron:
            let fire = job.firedCount + 1
            let remaining = job.remaining.map { " · remaining \($0 - 1 < 0 ? 0 : $0 - 1)" } ?? ""
            return "[Scheduled task \"\(name)\" · fire \(fire)\(remaining) · \(job.trigger.logLabel)]\n"
        case .onCompletion(let of):
            return "[Scheduled task \"\(name)\" · triggered by completion of job \(of.prefix(8))]\n"
        case .once, .immediate:
            return "[Scheduled task \"\(name)\"]\n"
        }
    }

    /// [T-scheduled-thinking-level] Apply the job's `--thinking` override to
    /// the session it is about to fire into.
    ///
    /// nil means "leave it alone", which is what every pre-existing job has:
    /// a `.followUp` keeps the conversation's own level, and a `.new` session
    /// keeps whatever its group default gave it. Only an explicit flag writes.
    ///
    /// Clamped to the entry's `effectiveMaxThinkingLevel` for the same reason
    /// the send path clamps ([T-fallback-thinking-preclamp]): asking a model
    /// for a level above its ceiling is a 400, and a scheduled job would repeat
    /// that failure on every fire with nobody watching.
    static func applyThinkingLevel(_ job: AgentJob, sessionId: String, entry: ModelEntry?) {
        guard let requested = job.thinkingLevel else { return }
        let store = ProviderConfigStore.shared
        let level = entry.map { min(requested, $0.effectiveMaxThinkingLevel) } ?? requested
        var cfg = store.inferenceConfig(for: sessionId) ?? SessionInferenceConfig()
        guard cfg.thinkingLevel != level else { return }
        cfg.thinkingLevel = level
        store.setInferenceConfig(cfg, for: sessionId)
        logger.info("[Sched] \(job.id.prefix(8)) thinking=\(level.rawValue) on \(sessionId.prefix(8))")
    }

    private static func resolveModel(for job: AgentJob) -> ModelEntry? {
        let store = ProviderConfigStore.shared
        if let override = job.modelEntryId, let e = store.entry(for: override) { return e }
        if let gid = store.defaultPrimaryGroupId, let group = store.group(for: gid),
           let id = ModelGroupRouter.resolve(group: group, sessionId: job.id, store: store),
           let e = store.entry(for: id) { return e }
        return store.resolvedAgentLoopEntries.first
    }

    /// Sleep until `date`. Returns false when cancelled.
    /// [T-scheduled-suspend-drift] Sleep in bounded steps, re-reading the
    /// WALL CLOCK each time, instead of one `Task.sleep` for the whole delta.
    ///
    /// A single sleep is measured in process-execution time: when iOS suspends
    /// the app the countdown stops with it, so a job due in 60s that spent 10
    /// minutes backgrounded fired 10 minutes LATE on resume rather than
    /// immediately. `Date()` keeps running across suspension, so re-deriving
    /// the remaining interval every step means a wake-up finds the deadline
    /// already passed and fires at once. Same wall-clock-over-sleep-relative
    /// reasoning as the compaction watchdog (T-ios-compact-no-timeout) and
    /// BrowserTabPool.
    ///
    /// This does NOT make firing reliable while suspended — nothing can; see
    /// the header note. It only removes the drift for the common case where
    /// the process survived and simply was not scheduled for a while.
    private static let sleepStep: TimeInterval = 30

    private static func sleepUntil(_ date: Date) async -> Bool {
        while true {
            if Task.isCancelled { return false }
            let remaining = date.timeIntervalSinceNow
            if remaining <= 0 { return !Task.isCancelled }
            let chunk = min(remaining, sleepStep)
            do { try await Task.sleep(nanoseconds: UInt64(chunk * 1_000_000_000)) }
            catch { return false }
        }
    }

    /// Next occurrence of HH:MM on one of `days` (Calendar weekday numbers,
    /// 1 = Sunday), inside the optional [start, end] window.
    nonisolated static func nextCronDate(hour: Int, minute: Int, days: Set<Int>, start: Date?, end: Date?, from: Date) -> Date? {
        let cal = Calendar.current
        let allowed = days.isEmpty ? Set(1...7) : days
        let base = max(from, start ?? from)
        for offset in 0..<(7 * 53) {
            guard let day = cal.date(byAdding: .day, value: offset, to: cal.startOfDay(for: base)) else { continue }
            guard allowed.contains(cal.component(.weekday, from: day)) else { continue }
            var comps = cal.dateComponents([.year, .month, .day], from: day)
            comps.hour = hour; comps.minute = minute; comps.second = 0
            guard let candidate = cal.date(from: comps) else { continue }
            if candidate <= base { continue }
            if let end, candidate > end { return nil }
            return candidate
        }
        return nil
    }

    /// "30m" / "2h" / "90s" / "1d" / bare seconds.
    nonisolated static func parseDuration(_ s: String) -> TimeInterval? {
        let t = s.trimmingCharacters(in: .whitespaces).lowercased()
        guard !t.isEmpty else { return nil }
        let unit = t.last!
        let numPart = "0123456789.".contains(unit) ? t : String(t.dropLast())
        guard let n = Double(numPart), n >= 0 else { return nil }
        switch unit {
        case "s": return n
        case "m": return n * 60
        case "h": return n * 3600
        case "d": return n * 86400
        default: return "0123456789.".contains(unit) ? n : nil
        }
    }

    nonisolated static func iso(_ d: Date) -> String {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f.string(from: d)
    }

    // MARK: - Insurance

    private static func scheduleInsurance(_ job: AgentJob, at date: Date) {
        let name = job.label ?? job.title
        JobInsuranceNotification.schedule(
            jobId: job.id, at: date.addingTimeInterval(5),
            title: AppLocalized("Minis scheduled task"),
            body: String(format: AppLocalized("\"%@\" is due. Open Minis to see whether it ran."), name),
            userInfo: job.target.parentSessionId.map { ["sessionId": $0] }
                ?? job.target.sessionIdForNavigation.map { ["sessionId": $0] } ?? [:])
    }

    private static func cancelInsurance(_ job: AgentJob) {
        JobInsuranceNotification.cancel(jobId: job.id)
    }
}

// MARK: - Insurance notification (schedule design §3)

/// A local notification registered when a job is armed and withdrawn the
/// moment the app itself handles the fire. If the process is killed first,
/// the notification is the only thing left standing — its text therefore
/// states only what is known (the task was due) and never a result.
enum JobInsuranceNotification {
    static func identifier(_ jobId: String) -> String { "job-\(jobId)" }

    static func schedule(jobId: String, at date: Date, title: String, body: String, userInfo: [String: String]) {
        guard UserDefaults.standard.object(forKey: "backgroundNotificationsEnabled") == nil
                || UserDefaults.standard.bool(forKey: "backgroundNotificationsEnabled") else { return }
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .notDetermined else { return }
            center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = ShortcutNotification.categoryId
        content.userInfo = userInfo
        let interval = max(1, date.timeIntervalSinceNow)
        let request = UNNotificationRequest(identifier: identifier(jobId), content: content,
                                            trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false))
        // Replace any earlier registration for the same job (loop re-arms).
        center.removePendingNotificationRequests(withIdentifiers: [identifier(jobId)])
        center.add(request)
    }

    /// Both queues: a notification that already fired (app was backgrounded
    /// past the deadline) is only removable from the delivered list.
    static func cancel(jobId: String) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier(jobId)])
        center.removeDeliveredNotifications(withIdentifiers: [identifier(jobId)])
    }
}

extension AgentJobTarget {
    /// The session a notification tap should open for this target.
    var sessionIdForNavigation: String? {
        switch self {
        case .new: return nil
        case .followUp(let sid): return sid
        case .rerun(let sid, _): return sid
        case .childOfCurrent(let parent, _): return parent
        }
    }
}
