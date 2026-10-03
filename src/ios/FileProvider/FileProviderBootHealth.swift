import Foundation

/// [T-ios-fp-mac-bootcrash] Circuit breaker for a File Provider extension that
/// cannot boot.
///
/// ## The failure this exists for
///
/// On Macs running the iPhone build ("Designed for iPad" / iOS-app-on-Mac), the
/// FP appex sometimes dies PRE-MAIN: `SIGILL` at `DYLD-STUB$$NSExtensionMain`,
/// with only the appex, dyld and libsystem_platform loaded — dyld never bound
/// the entry stub, so no line of our code runs. `fileproviderd` then relaunches
/// it, and has been observed doing so 5 times in 8 seconds.
///
/// Nothing inside the extension can defend against this: the defence has to
/// live in the main app, because a REGISTERED DOMAIN is the only reason
/// `fileproviderd` tries to launch the appex at all.
///
/// ## Why a breaker instead of "never register on Mac"
///
/// That blunt fix shipped once (`c4669fca4`) and was reverted (`90803ceb3`):
/// Finder browsing works between crash bursts, so the extension DOES boot most
/// of the time on these machines. Disabling the domain outright would trade a
/// mostly-working feature for a quieter crash report — and the leading theory
/// (`d881875e1`) is that these are launches that happen while the bundle is
/// being replaced by a TestFlight update, i.e. a transient that heals itself.
///
/// So: register normally, watch what actually happens, and withdraw the domain
/// only for a machine that is demonstrably stuck in a boot loop.
///
/// ## How it decides
///
/// The two processes communicate through one small plist in the shared App
/// Group (UserDefaults is NOT shared between the app and its appex):
///
/// - The main app calls `noteRegistrationAttempt()` each launch where it
///   registers the domain. That increments `pendingBoots` — "we have asked for
///   a domain this many times without ever hearing back".
/// - The appex calls `recordSuccessfulBoot()` once it is actually running.
///   That resets `pendingBoots` to 0.
/// - When `pendingBoots` reaches `tripThreshold`, `shouldWithhold()` starts
///   returning true and the app stops registering.
///
/// Three properties make this safe to ship without being able to reproduce the
/// crash:
///
/// 1. **Generation-scoped.** All state is keyed to the executable's mtime, the
///    same generation stamp the trace log already records. A new build (or a
///    bundle replacement — the very event the leading theory blames) starts
///    from zero, so a trip can never outlive the binary that earned it.
/// 2. **Self-healing.** The appex only has to boot ONCE to clear the count.
/// 3. **Bounded blast radius.** Tripping withdraws a File Provider domain.
///    User data is untouched — it lives at the App Group path, and
///    `NSFileProviderManager.remove` only unregisters. On a machine that is
///    crash-looping, Files integration is already not working.
///
/// The threshold is deliberately not 1: a single failed launch is the
/// transient this is NOT meant to punish.
enum FileProviderBootHealth {
    /// Consecutive registrations with no successful boot before the breaker
    /// trips. Three, so an isolated bad launch (the bundle-replacement window)
    /// costs nothing, while a genuine loop is caught within three app launches.
    static let tripThreshold = 3

    private static let appGroupId = "group.com.openminis.app"
    private static let fileName = "fp-boot-health.plist"

    private struct State: Codable {
        /// Executable generation this state describes. State for any other
        /// generation is stale and discarded.
        var generation: String
        /// Registrations since the last successful boot.
        var pendingBoots: Int
        /// Set once the breaker trips, purely so the app can log WHY it is
        /// withholding (and so a human reading the file can tell).
        var trippedAt: Date?
    }

    private static var fileURL: URL? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupId
        ) else { return nil }
        let dir = container.appendingPathComponent("MinisConfig", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(fileName)
    }

    /// Serializes the read-modify-write between the two processes' own threads.
    /// This is NOT cross-process locking: the app writes at launch and the
    /// appex writes at boot, and the worst case of an interleave is one
    /// miscounted attempt, which the threshold already tolerates.
    private static let queue = DispatchQueue(label: "com.openminis.app.fpBootHealth")

    private static func loadState() -> State? {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? PropertyListDecoder().decode(State.self, from: data)
    }

    private static func save(_ state: State) {
        guard let url = fileURL,
              let data = try? PropertyListEncoder().encode(state) else { return }
        try? data.write(to: url, options: .atomic)
    }

    /// The running executable's generation stamp (its mtime), matching what the
    /// trace log records. Falls back to the version/build string when the mtime
    /// is unreadable — a stable-but-coarser key is better than no scoping.
    static func currentGeneration() -> String {
        let bundle = Bundle.main
        if let execURL = bundle.executableURL,
           let mod = (try? FileManager.default.attributesOfItem(atPath: execURL.path))?[.modificationDate] as? Date {
            return ISO8601DateFormatter().string(from: mod)
        }
        let ver = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(ver)(\(build))"
    }

    /// Called by the APPEX once it is running. Clears the pending count for
    /// this generation, which also un-trips a previously tripped breaker.
    static func recordSuccessfulBoot(generation: String = currentGeneration()) {
        queue.sync {
            save(State(generation: generation, pendingBoots: 0, trippedAt: nil))
        }
    }

    /// Called by the MAIN APP just before it registers the domain. Returns the
    /// new pending count.
    @discardableResult
    static func noteRegistrationAttempt(generation: String = currentGeneration()) -> Int {
        queue.sync {
            let existing = loadState()
            // A different generation means a new binary: start clean.
            let pending = (existing?.generation == generation ? (existing?.pendingBoots ?? 0) : 0) + 1
            var tripped = existing?.generation == generation ? existing?.trippedAt : nil
            if pending >= tripThreshold, tripped == nil { tripped = Date() }
            save(State(generation: generation, pendingBoots: pending, trippedAt: tripped))
            return pending
        }
    }

    /// Whether the app should withhold domain registration this launch.
    ///
    /// Only ever true on iOS-app-on-Mac. The pre-main SIGILL has been seen
    /// exclusively there, and a real iOS device that somehow failed to boot the
    /// appex should keep retrying rather than silently lose Files integration.
    static func shouldWithholdRegistration(generation: String = currentGeneration()) -> Bool {
        guard ProcessInfo.processInfo.isiOSAppOnMac else { return false }
        return queue.sync {
            guard let s = loadState(), s.generation == generation else { return false }
            return s.pendingBoots >= tripThreshold
        }
    }

    /// Human-readable state for logging / the debug RPC.
    static func describe() -> String {
        let gen = currentGeneration()
        guard let s = loadState() else { return "gen=\(gen) pending=0 (no state)" }
        let stale = s.generation == gen ? "" : " (STALE gen=\(s.generation))"
        let tripped = s.trippedAt.map { " trippedAt=\(ISO8601DateFormatter().string(from: $0))" } ?? ""
        return "gen=\(gen) pending=\(s.pendingBoots)\(tripped)\(stale)"
    }
}
