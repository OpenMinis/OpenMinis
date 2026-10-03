import Combine
import Foundation

private let logger = AppLogger(category: "ProgrammaticPrompt")

// [T-p0-programmatic-prompt] Component A of the sub-agent / scheduled-task
// design (docs: subagent_design_v4 §8.1): the ONE channel through which
// anything that is not the user's own tap — the sessions CLI, a Shortcut, a
// scheduled job firing, a helper reporting back — injects a prompt into a
// session.
//
// Why a dedicated entry point instead of `vm.inputText = …; vm.send()`:
//
//  1. `send()` silently returns while `isProcessing` is true, and
//     `enqueuePrompt()` silently returns while it is false. A caller that reads
//     `isProcessing` and then picks one of them races the loop's own
//     epilogue: the classic check-then-act window in which the prompt
//     evaporates. Doing the check and the hand-off in one synchronous
//     @MainActor step (no `await` in between) closes that window.
//  2. The queue's only consumers are loop epilogues. A prompt that lands in
//     the queue after the epilogue's drain already returned (there are DB
//     awaits between that drain and `isProcessing = false`) has no consumer.
//     `startDrainIfIdle` — called from the isProcessing didSet and defensively
//     here — is the rescue.
//  3. Callers get the truth back (`.sent` / `.queued` / `.rejected`) instead of
//     an unconditional "ok".
//  4. `silent` suppresses the tap + completion haptics for a turn nobody tapped.

/// Who is injecting the prompt. Recorded for logs; P2's job machinery will
/// key completion hooks and notification identifiers off it.
enum ProgrammaticPromptOrigin: Equatable {
    /// `minis-sessions-cli send` from inside the sandbox.
    case cli
    /// An App Intent (Shortcuts / Siri).
    case shortcut
    /// An `AgentJobRegistry` job (scheduled trigger, helper completion, …).
    case job(jobId: String)

    var logLabel: String {
        switch self {
        case .cli: return "cli"
        case .shortcut: return "shortcut"
        case .job(let id): return "job:\(id.prefix(8))"
        }
    }
}

/// Truthful outcome of `submitProgrammaticPrompt`.
enum ProgrammaticSubmitOutcome: Equatable {
    /// No loop was running; a new turn started immediately.
    case sent
    /// A loop was running; the prompt is in `promptQueue` and will run as a
    /// fresh turn when that loop ends (or via the idle-drain rescue).
    case queued
    /// The vm declined it. `reason` is a stable snake_case token for callers
    /// that surface it to a script or a Shortcut result.
    case rejected(reason: String)
}

extension AIChatViewModel {

    /// Inject `text` (plus any attachments already staged on `attachments`)
    /// into this session as a user turn, atomically choosing between starting
    /// a loop and queueing behind the running one.
    ///
    /// Must be called on the main actor and must not be preceded by an
    /// `await` that read `isProcessing` — the whole point is that the check
    /// and the hand-off happen in the same synchronous step.
    ///
    /// - Parameters:
    ///   - text: the prompt. Trimmed; an empty prompt with no ready
    ///     attachment is rejected rather than silently dropped.
    ///   - origin: who is asking (logs only in P0).
    ///   - silent: suppress the send/enqueue haptic and, for a `.sent` turn,
    ///     the completion haptic. A `.queued` prompt drains inside the user's
    ///     own loop, whose completion haptic is theirs and is kept.
    @MainActor
    @discardableResult
    func submitProgrammaticPrompt(_ text: String,
                                  origin: ProgrammaticPromptOrigin,
                                  silent: Bool = true) -> ProgrammaticSubmitOutcome {
        let tag = "📨[Programmatic] origin=\(origin.logLabel) vm=\(vmInstanceId) sid=\(sessionId?.prefix(8) ?? "nil")"

        guard remoteDeviceId == nil else {
            logger.warning("\(tag) REJECTED — read-only remote session")
            return .rejected(reason: "read_only_remote_session")
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        // send() prunes non-ready attachments before its own guard, so a
        // pure-attachment prompt whose files never loaded would pass a naive
        // check here and still vanish inside send(). Mirror that pruning.
        let readyAttachments = attachments.filter { $0.loadState == .ready }
        guard !trimmed.isEmpty || !readyAttachments.isEmpty else {
            logger.warning("\(tag) REJECTED — empty prompt and no ready attachment (staged=\(self.attachments.count))")
            return .rejected(reason: attachments.isEmpty ? "empty_prompt" : "attachments_not_ready")
        }
        if isCompacting {
            // compactAndSend has its own queue + post-compact drain; a foreign
            // prompt landing mid-compaction would be rolled back or double
            // drained. Refuse rather than guess.
            logger.warning("\(tag) REJECTED — session is compacting")
            return .rejected(reason: "session_compacting")
        }

        // [T-programmatic-prompt-no-composer] The prompt travels as an
        // argument, NOT through `inputText`.
        //
        // This used to be `inputText = text`, which made the composer — a
        // two-way binding to whatever the user is typing right now — the
        // transport for a prompt nobody typed. Any background turn landing
        // mid-sentence (a helper reporting back, a scheduled job firing, the
        // CLI) overwrote the draft and then send()/enqueuePrompt() cleared it
        // to "", destroying it with no way back. Android never had this: its
        // headless path carries the text in a local and leaves `_inputText`
        // to the composer.
        //
        // Restoring the draft afterwards would not do: `inputText` is
        // @Published with a didSet that drives the slash and mention menus and
        // resets `voiceUsedInComposition`, so a write/restore pair would
        // shut an open mention popup and disturb the caret mid-typing.
        //
        // `attachments` stays shared on purpose — the CLI and the Shortcut
        // intent both stage files onto it before calling (see
        // SessionsOffloadBridge.stageAttachments / SendPromptIntent), and the
        // doc comment above promises they are included.

        if isProcessing {
            // Busy: hand to the queue.
            let before = promptQueue.count
            // A job result is delivered gently: it never interrupts the
            // running plan, it waits for the loop to converge (fix: the
            // device run 15:53 showed a helper result abandoning the parent's
            // remaining plan at the next tool boundary).
            let gentle: Bool = { if case .job = origin { return true } else { return false } }()
            enqueuePrompt(silent: silent, deferUntilIdle: gentle, overrideText: text)
            guard promptQueue.count == before + 1 else {
                logger.warning("\(tag) REJECTED — enqueuePrompt did not accept the prompt (queue \(before) → \(self.promptQueue.count))")
                return .rejected(reason: "enqueue_declined")
            }
            if silent, let queuedId = promptQueue.last?.id {
                silentQueuedPromptIds.insert(queuedId)
            }
            logger.info("\(tag) QUEUED — position \(self.promptQueue.count), loop still running")
            // Defensive: if the loop flipped idle in the same runloop turn
            // (it cannot have, we hold the main actor, but the guard is free).
            startDrainIfIdle(reason: "programmatic-enqueue")
            return .queued
        }

        // Idle: start a loop. send() flips isProcessing synchronously before
        // it spawns its Task, so the flip is the acceptance signal — every
        // other exit from send() is a silent return.
        programmaticSilentTurn = silent
        send(overrideText: text)
        guard isProcessing else {
            programmaticSilentTurn = false
            let reason = showContextExhaustedPrompt ? "context_exhausted" : "send_declined"
            logger.warning("\(tag) REJECTED — send() did not start a loop (\(reason))")
            return .rejected(reason: reason)
        }
        logger.info("\(tag) SENT — new loop started silent=\(silent)")
        return .sent
    }
}

// MARK: - Waiting for a programmatic turn  [T-shortcut-wait-queued-turn]

extension AIChatViewModel {

    /// The id of the prompt `submitProgrammaticPrompt` just queued, or nil when
    /// the outcome was not `.queued`. Read it IMMEDIATELY after the submit, on
    /// the main actor, before anything else can touch the queue.
    func lastQueuedProgrammaticPromptId(for outcome: ProgrammaticSubmitOutcome) -> UUID? {
        guard outcome == .queued else { return nil }
        return promptQueue.last?.id
    }

    /// Suspends until the turn carrying a programmatic prompt has finished.
    ///
    /// Callers used to do `for await p in $isProcessing.values { if !p { break } }`
    /// directly. That is right for `.sent` — send() flips isProcessing before it
    /// returns, so the first value the publisher replays is `true` — but wrong for
    /// `.queued`: the session was busy with an EARLIER turn, and the first `false`
    /// is that turn ending, before the queued prompt has even started. A "Wait for
    /// Result" Shortcut then returned the previous answer as if it were its own.
    ///
    /// Completion is therefore defined as: the queued prompt (if any) has left
    /// `promptQueue` (the drain took it) AND `isProcessing` is false. Both are
    /// watched together so a flip in either re-evaluates the pair, and the
    /// current values are replayed on subscription, so a turn that already
    /// finished resolves immediately rather than waiting for a change that will
    /// never come.
    ///
    /// Returns `false` if the task was cancelled before the turn finished
    /// (Shortcuts cancels perform() when its own time limit expires or the user
    /// stops the run); the caller decides what partial result to hand back.
    @discardableResult
    func awaitProgrammaticTurn(queuedPromptId: UUID?) async -> Bool {
        let states = Publishers.CombineLatest($isProcessing, $promptQueue)
            .map { processing, queue -> ProgrammaticTurnState in
                let stillQueued = queuedPromptId.map { id in queue.contains { $0.id == id } } ?? false
                return ProgrammaticTurnState(processing: processing, stillQueued: stillQueued)
            }
            .values
        return await Self.awaitTurnCompletion(in: states)
    }

    /// The pure part of `awaitProgrammaticTurn`, over any state sequence, so the
    /// completion rule is unit-testable without a view model.
    nonisolated static func awaitTurnCompletion<S: AsyncSequence>(in states: S) async -> Bool
    where S.Element == ProgrammaticTurnState {
        do {
            for try await state in states {
                if Task.isCancelled { return false }
                if state.isFinished { return true }
            }
        } catch {
            return false
        }
        // The sequence ended without a finished state: the publisher completed
        // (vm deallocated) or the task was cancelled mid-await.
        return false
    }
}

/// One observation of the pair `awaitTurnCompletion` decides on.
struct ProgrammaticTurnState: Equatable, Sendable {
    let processing: Bool
    /// Only meaningful for a `.queued` submit: true while the drain has not yet
    /// picked the prompt up. Always false for `.sent`.
    let stillQueued: Bool

    /// Finished = nothing left to run for this prompt AND the loop is idle.
    var isFinished: Bool { !processing && !stillQueued }
}
