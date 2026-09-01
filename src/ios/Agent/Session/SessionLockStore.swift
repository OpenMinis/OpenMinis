//
//  SessionLockStore.swift
//  MinisApp
//
//  Per-session biometric lock state. Lock metadata lives in a side-channel
//  (UserDefaults) rather than on `ChatSession`, so the in-DB session model
//  remains untouched and the locking layer never participates in
//  sync/export pipelines.
//
//  Three pieces of state are managed:
//
//  1. `lockedSessionIds: Set<String>` — sessions the user has explicitly
//     marked as locked. Persisted across app launches.
//  2. Per-session `unlockedAt: [String: Date]` — in-memory only, tracks
//     when a session was last unlocked via biometrics. Used to drive
//     the idle re-lock timer.
//  3. Global toggle + idle-timeout preference exposed via `@AppStorage`-
//     compatible UserDefaults keys.
//
//  See `BiometricAuth` for the LAContext wrapper that gates entry.
//

import Foundation
import Combine
import LocalAuthentication

/// UserDefaults keys for the Face ID protection feature. Centralized so
/// settings UI and SessionLockStore stay in sync.
enum SessionLockDefaultsKey {
    /// Bool — global on/off switch for Face ID protection.
    static let enabled = "faceIDProtectionEnabled"
    /// Int — idle-timeout in seconds (0 = never auto re-lock).
    static let idleSeconds = "faceIDProtectionIdleSeconds"
    /// JSON-encoded `[String]` — list of session IDs the user has locked.
    static let lockedIds = "faceIDProtectionLockedSessionIds"
    /// Bool — app-level lock on/off.
    static let appLockEnabled = "faceIDAppLockEnabled"
    /// Int — app-level re-lock idle timeout in seconds (0 = never, -1 = on exit).
    static let appLockIdleSeconds = "faceIDAppLockIdleSeconds"
}

/// Built-in idle-timeout choices surfaced in Settings.
struct SessionLockIdleOption: Identifiable, Equatable {
    let seconds: Int
    let labelKey: LocalizedStringResource

    var id: Int { seconds }

    static let allOptions: [SessionLockIdleOption] = [
        // -1 is the sentinel for "lock the moment the app backgrounds /
        // user navigates away" — kept distinct from 0 ("never auto-lock")
        // so neither path needs special-casing inside the timeout math.
        .init(seconds: -1,   labelKey: "Lock on exit"),
        .init(seconds: 30,   labelKey: "30 seconds"),
        .init(seconds: 60,   labelKey: "1 minute"),
        .init(seconds: 300,  labelKey: "5 minutes"),
        .init(seconds: 600,  labelKey: "10 minutes"),
        .init(seconds: 900,  labelKey: "15 minutes"),
        .init(seconds: 3600, labelKey: "1 hour"),
        .init(seconds: 0,    labelKey: "Never auto-lock"),
    ]
}

@MainActor
final class SessionLockStore: ObservableObject {
    static let shared = SessionLockStore()

    /// Sessions the user has locked. Observed by list rows and the chat
    /// screen to render the locked badge / gate the content.
    @Published private(set) var lockedSessionIds: Set<String> = []

    /// In-memory: last successful biometric unlock per session. Used by
    /// `isCurrentlyUnlocked(_:)` together with the idle-timeout setting.
    private var unlockedAt: [String: Date] = [:]

    private let defaults: UserDefaults
    private var defaultsObserver: NSObjectProtocol?

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.lockedSessionIds = Self.loadLocked(from: defaults)
        // Settings UI writes `globalEnabled` / `idleTimeoutSeconds` via
        // @AppStorage which bypasses this object. Observe UserDefaults so
        // every dependent view re-renders when those keys change.
        defaultsObserver = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification,
            object: defaults,
            queue: .main
        ) { [weak self] _ in
            self?.objectWillChange.send()
        }
    }

    deinit {
        if let defaultsObserver {
            NotificationCenter.default.removeObserver(defaultsObserver)
        }
    }

    // MARK: - Persistence

    private static func loadLocked(from defaults: UserDefaults) -> Set<String> {
        guard let data = defaults.data(forKey: SessionLockDefaultsKey.lockedIds),
              let ids = try? JSONDecoder().decode([String].self, from: data) else {
            return []
        }
        return Set(ids)
    }

    private func persistLocked() {
        let ids = Array(lockedSessionIds)
        if let data = try? JSONEncoder().encode(ids) {
            defaults.set(data, forKey: SessionLockDefaultsKey.lockedIds)
        }
    }

    // MARK: - Lock state queries

    func isLocked(_ sessionId: String) -> Bool {
        lockedSessionIds.contains(sessionId)
    }

    /// True when the user has unlocked this session AND the idle timeout
    /// has not yet elapsed. A locked session that has never been unlocked
    /// in this app run reports `false`. Used by the chat screen to decide
    /// whether the biometric overlay should appear.
    func isCurrentlyUnlocked(_ sessionId: String) -> Bool {
        guard isLocked(sessionId) else { return true }
        guard let stamp = unlockedAt[sessionId] else { return false }
        let idle = idleTimeoutSeconds
        if idle == 0 {
            // "Never auto-lock" — once unlocked, stay unlocked until the
            // app process exits or the user toggles the lock off.
            return true
        }
        if idle < 0 {
            // "Lock on exit" — the unlock stamp is dropped
            // by `clearAllUnlocks()` when the app backgrounds, so the
            // session is unlocked while the stamp survives this run.
            return true
        }
        return Date().timeIntervalSince(stamp) < TimeInterval(idle)
    }

    /// Shared "should this session render as locked in the UI" check.
    /// Used by the chat-list row, the chat-screen gate overlay, and the
    /// navbar title blur — kept here so all three sites stay in sync.
    func isVisuallyLocked(_ sessionId: String) -> Bool {
        guard globalEnabled, BiometricAuth.isAvailable else { return false }
        guard isLocked(sessionId) else { return false }
        return !isCurrentlyUnlocked(sessionId)
    }

    // MARK: - Mutations

    /// Lock a session. Idempotent.
    func lock(_ sessionId: String) {
        guard !sessionId.isEmpty else { return }
        if lockedSessionIds.insert(sessionId).inserted {
            unlockedAt.removeValue(forKey: sessionId)
            persistLocked()
        }
    }

    /// Permanently unlock a session (removes it from the locked set).
    /// The caller must have already cleared the biometric prompt.
    func unlockPermanently(_ sessionId: String) {
        if lockedSessionIds.remove(sessionId) != nil {
            unlockedAt.removeValue(forKey: sessionId)
            persistLocked()
        }
    }

    /// Note a successful biometric unlock — keeps the session in the
    /// locked set but starts the idle-reset clock.
    func noteUnlock(_ sessionId: String) {
        guard isLocked(sessionId) else { return }
        unlockedAt[sessionId] = Date()
        // Must publish so every observer of `isVisuallyLocked(_:)` (list
        // row, navbar title blur, gate overlay) re-evaluates. Without
        // this, unlocking in the chat view leaves the list row blurred
        // and the navbar title masked until something else nudges the
        // store — the symptom users hit when returning to the list.
        objectWillChange.send()
    }

    /// Drop the session's "currently unlocked" timestamp without removing
    /// it from the locked set — used when the app backgrounds long enough
    /// or the user navigates away and the idle window has elapsed.
    func relockIfIdleExpired(_ sessionId: String) {
        guard isLocked(sessionId), let stamp = unlockedAt[sessionId] else { return }
        let idle = idleTimeoutSeconds
        guard idle > 0 else { return }
        if Date().timeIntervalSince(stamp) >= TimeInterval(idle) {
            unlockedAt.removeValue(forKey: sessionId)
            objectWillChange.send()
        }
    }

    /// Drop every cached unlock — call when the app enters background
    /// (foreground re-evaluates each locked session against the idle
    /// window). Safe to invoke unconditionally.
    func clearAllUnlocks() {
        guard !unlockedAt.isEmpty else { return }
        unlockedAt.removeAll()
        objectWillChange.send()
    }

    /// Evict all idle-expired unlocks. Use from scenePhase observers when
    /// returning to foreground so freshly-elapsed sessions re-lock without
    /// requiring user interaction.
    func evictIdleExpired() {
        let idle = idleTimeoutSeconds
        guard idle > 0, !unlockedAt.isEmpty else { return }
        let cutoff = Date().addingTimeInterval(-TimeInterval(idle))
        let before = unlockedAt.count
        unlockedAt = unlockedAt.filter { _, stamp in stamp > cutoff }
        if unlockedAt.count != before {
            objectWillChange.send()
        }
    }

    /// Drop the unlock stamp for a specific session — e.g. when the user
    /// navigates back to the list, so revisiting it later requires Face
    /// ID even within the idle window.
    func clearUnlock(_ sessionId: String) {
        if unlockedAt.removeValue(forKey: sessionId) != nil {
            objectWillChange.send()
        }
    }

    // MARK: - Global preferences (read-through to UserDefaults)

    /// Master enable for the feature. When `false`, list rows render
    /// normally and the chat-screen gate is bypassed regardless of any
    /// per-session lock state. Lock state itself is preserved so toggling
    /// the feature on later restores the previous locks.
    var globalEnabled: Bool {
        get { defaults.object(forKey: SessionLockDefaultsKey.enabled) as? Bool ?? false }
        set { defaults.set(newValue, forKey: SessionLockDefaultsKey.enabled) }
    }

    /// Idle window in seconds. 0 = never auto-lock once unlocked this run.
    var idleTimeoutSeconds: Int {
        get { defaults.object(forKey: SessionLockDefaultsKey.idleSeconds) as? Int ?? 300 }
        set { defaults.set(newValue, forKey: SessionLockDefaultsKey.idleSeconds) }
    }

    /// Convenience: is the protection effectively active right now? The
    /// gate logic uses this so a disabled global toggle short-circuits
    /// every check.
    func isProtectionActive(for sessionId: String) -> Bool {
        guard globalEnabled else { return false }
        guard BiometricAuth.isAvailable else { return false }
        return isLocked(sessionId)
    }

    // MARK: - App-level lock

    var appLockEnabled: Bool {
        get { defaults.object(forKey: SessionLockDefaultsKey.appLockEnabled) as? Bool ?? false }
        set {
            defaults.set(newValue, forKey: SessionLockDefaultsKey.appLockEnabled)
            objectWillChange.send()
        }
    }

    var appLockIdleSeconds: Int {
        get { defaults.object(forKey: SessionLockDefaultsKey.appLockIdleSeconds) as? Int ?? -1 }
        set {
            defaults.set(newValue, forKey: SessionLockDefaultsKey.appLockIdleSeconds)
            objectWillChange.send()
        }
    }

    /// In-memory timestamp of last successful app-level unlock.
    private var appUnlockedAt: Date?

    /// [T-applock-repeated-faceid] When the app last entered background, used to
    /// distinguish a real exit from the rapid inactive↔active churn iOS generates
    /// (notification/control center pull-down, call/system banners, background
    /// audio/PiP state changes). nil while foreground.
    private var appBackgroundedAt: Date?

    /// [T-applock-repeated-faceid] Grace window for "Lock on exit" mode: only a
    /// background spell of at least this long counts as the user actually leaving
    /// and re-locks the app. Shorter blips keep the existing unlock, so the user
    /// isn't re-prompted for Face ID every time a banner flickers the scene phase.
    static let lockOnExitGraceSeconds: TimeInterval = 15

    /// True when the privacy screen should cover content (task switcher).
    /// Cleared automatically by `evaluateAppLock()` on foreground return.
    @Published var showPrivacyScreen: Bool = false

    /// True when app should show the lock screen.
    @Published private(set) var appIsLocked: Bool = {
        guard UserDefaults.standard.bool(forKey: SessionLockDefaultsKey.appLockEnabled) else { return false }
        return true
    }()

    /// Call on app launch and foreground return to evaluate lock state.
    func evaluateAppLock() {
        showPrivacyScreen = false
        // Snapshot + consume the background spell that just ended (foreground
        // return). nil on a true cold launch (never backgrounded this run).
        let backgroundedFor: TimeInterval? = appBackgroundedAt.map { Date().timeIntervalSince($0) }
        appBackgroundedAt = nil

        guard appLockEnabled, BiometricAuth.isAvailable else {
            appIsLocked = false
            return
        }
        guard let stamp = appUnlockedAt else {
            // Never unlocked this run (cold launch / unlock was dropped) → lock.
            appIsLocked = true
            return
        }
        let idle = appLockIdleSeconds
        if idle < 0 {
            // [T-applock-repeated-faceid] "Lock on exit": only re-lock when the
            // app was genuinely backgrounded for >= the grace window. A cold
            // launch (backgroundedFor == nil) with an existing unlock stamp can
            // only happen within the same process, so honor the stamp. Short
            // inactive↔active blips (banners, control center, bg-audio churn)
            // fall under the grace and keep the unlock — no repeated Face ID.
            if let bg = backgroundedFor, bg >= Self.lockOnExitGraceSeconds {
                appUnlockedAt = nil
                appIsLocked = true
            } else {
                appIsLocked = false
            }
            return
        }
        if idle == 0 {
            appIsLocked = false
            return
        }
        appIsLocked = Date().timeIntervalSince(stamp) >= TimeInterval(idle)
    }

    /// Record a successful app-level unlock.
    func noteAppUnlock() {
        appUnlockedAt = Date()
        appIsLocked = false
    }

    /// [T-applock-repeated-faceid] Note that the app entered background. Records
    /// WHEN so the next foreground `evaluateAppLock()` can measure the spell and
    /// apply the lock-on-exit grace window. Replaces the old immediate
    /// `clearAppUnlock()` on background — clearing now happens in evaluateAppLock
    /// only when the spell actually exceeds the grace.
    func noteAppBackgrounded() {
        appBackgroundedAt = Date()
    }

    /// Clear app unlock stamp — forces Face ID on next evaluate. Retained for
    /// callers that want an unconditional lock (not used by the lock-on-exit
    /// background path anymore; see `noteAppBackgrounded`).
    func clearAppUnlock() {
        appUnlockedAt = nil
    }

}

/// Thin LAContext wrapper. Keeps the LocalAuthentication import contained
/// to one file and exposes a single async `authenticate` entry point.
enum BiometricAuth {
    /// Cached LAContext capability probe. `canEvaluatePolicy` is documented
    /// as cheap, but the FIRST call in a process is NOT: it round-trips over
    /// synchronous NSXPC to the LocalAuthentication daemon and cold-starts
    /// the biometric stack. A Time Profiler trace caught this as a single
    /// 552 ms main-thread hang during scroll — the first visible
    /// `sessionContextMenu` builder (SwiftUI evaluates context-menu builders
    /// eagerly for every row) was the first reader, so its `dispatch_once`
    /// initializer ran the blocking XPC inline on the render pass.
    ///
    /// Fix: do the probe ONCE, off the main thread, at launch (`prewarm()`),
    /// and have every accessor read a plain atomically-published value. The
    /// value never changes inside a process lifetime — enrolling new
    /// biometrics requires backgrounding the app, and the OS rebuilds the
    /// view hierarchy when biometric enrollment changes — so a process-wide
    /// cache computed once is safe. Before the prewarm lands (the first few
    /// ms after launch) accessors return the conservative default
    /// `(available: false, name: "Biometrics")`, which only means the
    /// optional Face ID menu item is briefly hidden — never a wrong unlock.
    private static let lock = NSLock()
    nonisolated(unsafe) private static var _cached: (available: Bool, name: String)?

    private static func probe() -> (available: Bool, name: String) {
        let ctx = LAContext()
        var err: NSError?
        let available = ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err)
        // The display name must reflect the device's biometric HARDWARE, not
        // whether biometrics is currently usable. `biometryType` is populated by
        // the canEvaluatePolicy call above and reports the sensor type (.faceID /
        // .touchID / .opticID) even when the specific WithBiometrics policy would
        // fail — e.g. Face ID not enrolled yet, not authorized for this app, or
        // temporarily locked out after failed attempts. Gating the name behind
        // canEvaluatePolicy(...WithBiometrics) is what made a real Face ID device
        // fall back to the generic "Biometrics" (shown as 生物识别) whenever
        // biometrics wasn't immediately evaluable. Read biometryType directly so
        // the title is a stable "Face ID Protection" / "Touch ID Protection".
        let name: String
        switch ctx.biometryType {
        case .faceID:  name = "Face ID"
        case .touchID: name = "Touch ID"
        case .opticID: name = "Optic ID"
        default:
            // Only reached when there is genuinely no biometric sensor
            // (biometryType == .none) — a passcode-only device.
            name = AppLocalized("Biometrics")
        }
        return (available, name)
    }

    /// Resolve the cached capability, computing it inline if `prewarm()`
    /// hasn't completed yet. The inline path is the slow XPC; the whole
    /// point of `prewarm()` is to make sure we never reach it on the main
    /// thread. Reads after prewarm are a single locked pointer load.
    private static func cached() -> (available: Bool, name: String) {
        lock.lock()
        if let c = _cached { lock.unlock(); return c }
        lock.unlock()
        let v = probe()
        lock.lock(); _cached = v; lock.unlock()
        return v
    }

    /// Run the capability probe off the main thread, once, at launch so the
    /// first main-thread accessor (typically a `sessionContextMenu` builder
    /// during scroll) reads a precomputed value instead of blocking on the
    /// LocalAuthentication XPC. Idempotent and safe to call from any thread.
    static func prewarm() {
        lock.lock()
        let alreadyDone = _cached != nil
        lock.unlock()
        guard !alreadyDone else { return }
        DispatchQueue.global(qos: .utility).async {
            let v = probe()
            lock.lock(); if _cached == nil { _cached = v }; lock.unlock()
        }
    }

    /// True when the device has a biometric sensor enrolled OR a passcode
    /// configured (we fall back to passcode automatically). Surfaces the
    /// "show Face ID section in Settings" gate. Returns `false` until the
    /// launch-time `prewarm()` completes.
    static var isAvailable: Bool {
        lock.lock(); defer { lock.unlock() }
        return _cached?.available ?? false
    }

    /// Localized name of the available biometric mechanism — "Face ID",
    /// "Touch ID", "Optic ID" — for display in prompts and Settings.
    /// Falls back to "Biometrics" when unknown / not yet prewarmed.
    static var biometryDisplayName: String { cached().name }

    /// SF Symbol name matching the device's biometric sensor. Use in icon
    /// slots so a Touch ID device doesn't show a Face ID glyph (and vice
    /// versa). Falls back to a generic lock for non-biometric / unknown.
    private static let iconNameCached: String = {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err) else {
            return "lock.shield"
        }
        switch ctx.biometryType {
        case .faceID:  return "faceid"
        case .touchID: return "touchid"
        case .opticID: return "opticid"
        default:       return "lock.shield"
        }
    }()
    static var biometryIconName: String { iconNameCached }

    /// Prompt the user. Tries biometrics first, falls back to passcode if
    /// biometrics is unavailable / not enrolled / locked-out — matches
    /// the user's intent ("Face ID OR passcode unlocks Minis sessions").
    /// Returns true on success, false on any error/cancellation.
    @MainActor
    static func authenticate(reason: String) async -> Bool {
        // [T-applock-passcode-fallback] Use `.deviceOwnerAuthentication`, NOT
        // `.deviceOwnerAuthenticationWithBiometrics`. The biometrics-only policy
        // does biometrics ONLY: tapping the "Use Passcode" fallback button does
        // not present a passcode UI — evaluatePolicy returns immediately with
        // LAError.userFallback (and biometry lockout returns .biometryLockout),
        // so the user taps "Use Passcode" and nothing happens (the reported bug).
        // `.deviceOwnerAuthentication` natively chains biometrics → system-managed
        // passcode fallback, which is exactly "Face ID OR passcode unlocks Minis".
        // Do NOT set localizedFallbackTitle: under this policy the system manages
        // the fallback button; an empty/custom title can suppress the immediate
        // passcode affordance.
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else {
            return false
        }
        return await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, evalErr in
                if !ok, let e = evalErr {
                    AppLogger(category: "AppLock").info("[AppLock] evaluatePolicy failed: \((e as NSError).code) \(e.localizedDescription)")
                }
                cont.resume(returning: ok)
            }
        }
    }
}
