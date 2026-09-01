//
//  QuickActionWorkflow.swift
//  MinisApp
//
//  Explicit, checkpoint-driven state machine for Home Screen Quick
//  Actions (Chat with Voice / Chat with Camera). Replaces the previous
//  delay-based ("hop a few runloops and hope") routing, which raced
//  with the transient draft AIChatView SwiftUI mounts during
//  NavigationStack push of a new session.
//
//  State machine:
//
//    Idle
//      ↓ AppDelegate.windowScene(performActionFor:)
//      ↓ QuickActionRouter.handle() → workflow.start(.openCamera)
//    PendingDispatch (action chosen; new chat not yet opened)
//      ↓ ContentView.handleNewChatRequest() calls openSession(newId)
//      ↓ then workflow.attachTargetSession(newId)
//    WaitingForChatMount (targetSessionId known; waiting for the
//                         AIChatView whose vm.sessionId matches)
//      ↓ AIChatView.onAppear / onChange(vm.sessionId): sees match +
//        isChatViewVisible=true, calls workflow.markChatReady(receiver:)
//      ↓ workflow invokes receiver.handle(action)
//    ChatReady → receiver flips showCamera = true
//      ↓ fullScreenCover content.onAppear calls markCoverPresented()
//    CoverPresenting → terminal-success
//
//  Fallback: after `markChatReady` we arm a 250ms timer; if the
//  workflow is still `chatReady` (i.e. cover never confirmed it
//  appeared — receiver's @State got torn down), the action is
//  re-delivered to the currently-registered receiver. Capped at 2
//  retries to avoid runaway loops; after that we log + reset to Idle.
//

import Foundation
import SwiftUI

private let logger = AppLogger(category: "QuickActionWorkflow")

@MainActor
final class QuickActionWorkflow: ObservableObject {
    static let shared = QuickActionWorkflow()

    enum State: Equatable {
        case idle
        /// Router accepted the shortcut. ContentView must dismiss
        /// whatever session is currently on top (pop the nav stack
        /// / clear `selectedSessionId`) and then call `markHome()`.
        /// We don't proceed to creating a new session until the host
        /// is observably back at the session list — otherwise the
        /// fresh draft view mounts on top of a still-unwinding old
        /// one and SwiftUI tears the new view back down mid-flight.
        case ensuringHome(ChatLaunchAction)
        case pendingDispatch(ChatLaunchAction)
        case waitingForChatMount(ChatLaunchAction, targetSessionId: String)
        case chatReady(ChatLaunchAction, targetSessionId: String)
        case coverPresenting(ChatLaunchAction, targetSessionId: String)
    }

    @Published private(set) var state: State = .idle {
        didSet { lastTransitionAt = Date() }
    }

    /// When `state` last changed. Lets takeover paths distinguish a LIVE
    /// workflow (milliseconds–seconds old, still advancing) from STALE
    /// residue — e.g. `waitingForChatMount` stranded because the user
    /// backgrounded the app before the draft view ever mounted. Stale
    /// residue must not keep steering navigation when a newer explicit
    /// intent (an incoming share) arrives. [T-share-vs-shortcut-state]
    private(set) var lastTransitionAt = Date()

    /// Reset to idle ONLY when the workflow has been sitting in a non-idle
    /// state for longer than `seconds`. A fresh, genuinely in-flight
    /// workflow (cold quick-action launch racing a persisted share record)
    /// is left alone — its own checkpoints/timeouts still own it. Returns
    /// whether a reset happened.
    @discardableResult
    func resetIfStale(olderThan seconds: TimeInterval, reason: String) -> Bool {
        if case .idle = state { return false }
        let age = Date().timeIntervalSince(lastTransitionAt)
        guard age > seconds else {
            logger.info("resetIfStale skipped — state=\(stateLabel) age=\(String(format: "%.1f", age))s ≤ \(seconds)s (live workflow)")
            return false
        }
        logger.warning("resetIfStale firing — state=\(stateLabel) age=\(String(format: "%.1f", age))s reason=\(reason)")
        reset(reason: reason)
        return true
    }

    private var retryCount = 0
    private let maxRetries = 2
    private let retryInterval: TimeInterval = 0.250
    private let ensureHomeTimeout: TimeInterval = 1.0
    private var ensureHomeToken: Int = 0

    private init() {}

    // MARK: - Public lifecycle

    /// Step 1 — Router received a shortcut tap. Always enters
    /// `ensuringHome` first so the host has a chance to pop any
    /// existing chat off the navigation stack BEFORE we ask it to
    /// open a new session. If we're already at home, ContentView's
    /// observer flips us to `pendingDispatch` on the same runloop.
    func start(_ action: ChatLaunchAction) {
        if case .idle = state {
            logger.info("start action=\(String(describing: action)) (clean)")
        } else {
            logger.info("start action=\(String(describing: action)) — clearing stale prevState=\(stateLabel)")
        }
        retryCount = 0
        state = .ensuringHome(action)
        scheduleEnsureHomeTimeout(action: action)
    }

    /// Step 1b — ContentView confirmed (or is at) the home / session
    /// list. Workflow advances to `pendingDispatch` and ContentView
    /// then opens the new session + calls `attachTargetSession`.
    func markHome() {
        guard case .ensuringHome(let action) = state else { return }
        logger.info("markHome action=\(String(describing: action)) — advancing to pendingDispatch")
        state = .pendingDispatch(action)
    }

    /// Step 2 — ContentView has decided which session id will carry
    /// the action. Called from `handleNewChatRequest` right after
    /// `openSession(newId)`. Idempotent for the same id.
    func attachTargetSession(_ id: String) {
        guard case .pendingDispatch(let action) = state else {
            logger.info("attachTargetSession id=\(id) ignored (state=\(stateLabel))")
            return
        }
        logger.info("attachTargetSession id=\(id) action=\(String(describing: action))")
        state = .waitingForChatMount(action, targetSessionId: id)
    }

    /// Step 3 — AIChatView's `.onChange(of: state)` (or its mount
    /// observer) calls this once it confirms `vm.sessionId == target`
    /// AND the view is visible. Workflow advances to `chatReady` so
    /// the view's observer picks up the action and flips the cover's
    /// presentation binding. Also arms the retry timer.
    func markChatReady(sessionId: String) {
        guard case .waitingForChatMount(let action, let targetId) = state else { return }
        guard sessionId == targetId else {
            logger.info("markChatReady sid=\(sessionId) ≠ target=\(targetId); ignoring")
            return
        }
        logger.info("markChatReady action=\(String(describing: action)) target=\(targetId) attempt=\(retryCount + 1)/\(maxRetries + 1)")
        state = .chatReady(action, targetSessionId: targetId)
        scheduleRetryIfNeeded(action: action, targetId: targetId)
    }

    /// Step 4 — fullScreenCover (or analogous overlay) content
    /// successfully appeared. Terminal-success checkpoint.
    func markCoverPresented() {
        guard case .chatReady(let action, let sid) = state else {
            logger.info("markCoverPresented ignored (state=\(stateLabel))")
            return
        }
        logger.info("markCoverPresented action=\(String(describing: action)) sid=\(sid) — workflow complete")
        state = .coverPresenting(action, targetSessionId: sid)
        retryCount = 0
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self else { return }
            if case .coverPresenting = self.state {
                self.state = .idle
            }
        }
    }

    /// Manual abort (e.g. user backgrounds the app before cover shows,
    /// or the `newChat` shortcut fires which has no follow-up action).
    func reset(reason: String) {
        logger.info("reset reason=\(reason) prevState=\(stateLabel)")
        state = .idle
        retryCount = 0
    }

    // MARK: - Timers

    /// If ContentView never calls `markHome()` (e.g. the navigation
    /// state is wedged), force-advance after a grace period so the
    /// workflow doesn't strand the user. This is a safety net, not
    /// the happy path — log loudly when it fires.
    private func scheduleEnsureHomeTimeout(action: ChatLaunchAction) {
        ensureHomeToken &+= 1
        let token = ensureHomeToken
        DispatchQueue.main.asyncAfter(deadline: .now() + ensureHomeTimeout) { [weak self] in
            guard let self else { return }
            guard self.ensureHomeToken == token else { return }
            guard case .ensuringHome(let curAction) = self.state, curAction == action else { return }
            logger.warning("ensureHome timeout (\(self.ensureHomeTimeout)s) — forcing advance to pendingDispatch")
            self.state = .pendingDispatch(action)
        }
    }

    // MARK: - Retry

    private func scheduleRetryIfNeeded(action: ChatLaunchAction, targetId: String) {
        DispatchQueue.main.asyncAfter(deadline: .now() + retryInterval) { [weak self] in
            guard let self else { return }
            // Still stuck in chatReady → cover never appeared, so the
            // current receiver either lost the @State flip during a
            // view-tree rebuild or never observed the transition.
            guard case .chatReady(let curAction, let curTarget) = self.state,
                  curAction == action, curTarget == targetId else { return }
            guard self.retryCount < self.maxRetries else {
                logger.warning("retry exhausted (\(self.maxRetries) attempts) — giving up; action=\(String(describing: action)) target=\(targetId)")
                self.reset(reason: "retry exhausted")
                return
            }
            self.retryCount += 1
            // Demote back to waitingForChatMount; the surviving view's
            // observer will re-trigger markChatReady → chatReady, which
            // publishes a fresh state change and re-flips the cover
            // binding.
            logger.info("retry #\(self.retryCount) — demoting to waitingForChatMount")
            self.state = .waitingForChatMount(action, targetSessionId: targetId)
        }
    }

    // MARK: - SwiftUI bridge

    /// Convenience accessor for the modifier below.
    var statePublisher: Published<State>.Publisher { $state }

    // MARK: - Debug

    private var stateLabel: String {
        switch state {
        case .idle: return "idle"
        case .ensuringHome(let a): return "ensuringHome(\(a))"
        case .pendingDispatch(let a): return "pendingDispatch(\(a))"
        case .waitingForChatMount(let a, let s): return "waitingForChatMount(\(a),sid=\(s))"
        case .chatReady(let a, let s): return "chatReady(\(a),sid=\(s))"
        case .coverPresenting(let a, let s): return "coverPresenting(\(a),sid=\(s))"
        }
    }
}

// MARK: - SwiftUI integration

/// Attached to each AIChatView to observe workflow state without
/// inflating the view's body expression (Swift type-checker / runtime
/// metadata resolution can stack-overflow on already-large bodies when
/// extra `.onReceive`/`.onChange` modifiers are added inline).
///
/// The modifier:
///   - Re-checks workflow state whenever sessionId / visibility changes.
///   - Subscribes to `$state` and forwards `waitingForChatMount` matches
///     (so this view can call markChatReady) and `chatReady` deliveries
///     (so it can flip its cover @State).
struct QuickActionObserverModifier: ViewModifier {
    let sessionId: String?
    let isVisible: Bool
    let onWaitingMatch: () -> Void
    let onChatReady: (ChatLaunchAction) -> Void

    func body(content: Content) -> some View {
        content
            .onReceive(QuickActionWorkflow.shared.statePublisher, perform: handleState)
            .onChange(of: isVisible) { visible in
                if visible { onWaitingMatch() }
            }
            .onChange(of: sessionId) { _ in
                onWaitingMatch()
            }
    }

    private func handleState(_ newState: QuickActionWorkflow.State) {
        // NOTE: don't gate on captured `isVisible` here — modifier
        // struct closures may hold a stale snapshot from a previous
        // body evaluation. The view-side closures re-read the live
        // @State (`isChatViewVisible`) themselves before acting.
        switch newState {
        case .waitingForChatMount(_, let target):
            if sessionId == target { onWaitingMatch() }
        case .chatReady(let action, let target):
            if sessionId == target { onChatReady(action) }
        default:
            break
        }
    }
}

extension View {
    /// Returns `AnyView` rather than a structural `ModifiedContent<Self, …>`
    /// so the modifier's three nested modifiers (onReceive + two onChange)
    /// don't deepen the caller's generic chain. Applied to AIChatView.body,
    /// which is already sitting at SwiftUI's runtime-type-decode limit;
    /// adding this modifier in T-quick-action-workflow tipped that chain
    /// past the threshold and caused SIGSEGV in
    /// __swift_instantiateConcreteTypeFromMangledNameV2 on cold launch via
    /// the share / quick-action path. AnyView here is a paired defense
    /// alongside the inline AnyView wrap in AIChatView's fullScreenCover
    /// content closure — together they keep the body's mangled type short
    /// enough to decode without blowing the stack. Same class of crash as
    /// the 2026-04-17 `inputBar.getter` blow-up; remove both AnyViews
    /// once the chat body is structurally split into separate View
    /// structs (v1.10 follow-up).
    func observingQuickActions(modifier: QuickActionObserverModifier) -> some View {
        AnyView(self.modifier(modifier))
    }
}
