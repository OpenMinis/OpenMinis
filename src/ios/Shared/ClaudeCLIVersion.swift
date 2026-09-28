import Foundation

/// [T-anthropic-cli-version] Resolves the `claude-cli/<version>` fingerprint
/// used on the Anthropic OAuth (Claude Code subscription) path.
///
/// ### Why this exists
///
/// Anthropic gates *new models* on a minimum Claude Code version and rejects
/// older clients outright:
///
/// ```
/// 400 invalid_request_error
/// "Claude Code 2.1.195 does not support this model; version 2.1.280 or newer
///  is required. Run 'claude update', or update the Claude desktop app."
/// details.error_code = "claude_code_version_too_old"
/// ```
///
/// The fingerprint used to be a hardcoded `2.1.195`, so **every** model launch
/// broke OAuth users until a new app release shipped a bumped constant:
///
/// | Model             | Required claude-cli | Pinned constant | Result |
/// |-------------------|---------------------|-----------------|--------|
/// | claude-fable-5-1  | >= 2.1.251          | 2.1.195         | 400    |
/// | claude-opus-5-5   | >= 2.1.280          | 2.1.195         | 400    |
///
/// (see #301 — fixed by bumping the constant to 2.1.251, which was already
/// stale by the time Opus 5.5 shipped. A constant cannot win this race.)
///
/// ### Strategy
///
/// Resolve the version at runtime from the npm registry dist-tags endpoint,
/// the same source `claude update` consults — a 56-byte, unauthenticated,
/// CDN-cached JSON document:
///
/// ```
/// GET https://registry.npmjs.org/-/package/@anthropic-ai/claude-code/dist-tags
/// {"stable":"2.1.277","latest":"2.1.284","next":"2.1.284"}
/// ```
///
/// We prefer the highest published tag: the gate checks a *minimum*, so the
/// newest version always satisfies it, whereas `stable` can lag behind a model
/// launch (it did for Opus 5.5 — see anthropics/claude-code#96130, where
/// `stable` was 2.1.267 against a required 2.1.280).
///
/// ### Failure policy
///
/// Never blocks a request. Reads come from an in-memory cache; the refresh runs
/// off the request path (app start + a TTL re-check) and any failure leaves the
/// previous value in place. Resolution order:
///
///   1. live value fetched this session
///   2. last value persisted to `UserDefaults` (survives restarts/offline)
///   3. ``fallbackVersion`` compiled into the build
///
/// Worst case equals the old hardcoded behaviour; the common case self-heals
/// without an app update.
enum ClaudeCLIVersion {

    private static let defaultsKey = "claudeCliVersion"
    private static let fetchedAtKey = "claudeCliVersionFetchedAt"

    private static let distTagsURL = URL(
        string: "https://registry.npmjs.org/-/package/@anthropic-ai/claude-code/dist-tags"
    )!

    /// Compiled-in floor, used only until the first successful fetch on a
    /// fresh install. Kept current with the newest known CLI at release time
    /// so even a fully offline install clears the gates known at that point.
    static let fallbackVersion = "2.1.284"

    /// Re-check at most once per day; the gate moves on release cadence.
    private static let refreshTTL: TimeInterval = 24 * 60 * 60

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: String = fallbackVersion
    nonisolated(unsafe) private static var refreshInFlight = false

    /// Warm the cache from `UserDefaults` and refresh in the background when
    /// the persisted value is stale. Called once at app launch.
    static func prime() {
        let defaults = UserDefaults.standard
        if let stored = defaults.string(forKey: defaultsKey),
           isPlausible(stored) {
            lock.lock()
            if compare(stored, cached) > 0 { cached = stored }
            lock.unlock()
        }
        let fetchedAt = defaults.double(forKey: fetchedAtKey)
        if Date().timeIntervalSince1970 - fetchedAt > refreshTTL {
            refresh()
        }
    }

    /// Best version known right now. Never performs I/O.
    static func current() -> String {
        lock.lock()
        defer { lock.unlock() }
        return cached
    }

    /// The full `User-Agent` header value for the Anthropic OAuth path.
    static func userAgent() -> String {
        "claude-cli/\(current()) (external, cli)"
    }

    /// Fetch dist-tags off the request path. A second call while one is in
    /// flight is a no-op. Failures are ignored — the cached value stands.
    static func refresh() {
        lock.lock()
        if refreshInFlight {
            lock.unlock()
            return
        }
        refreshInFlight = true
        lock.unlock()

        var request = URLRequest(url: distTagsURL)
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        URLSession.shared.dataTask(with: request) { data, response, _ in
            defer {
                lock.lock()
                refreshInFlight = false
                lock.unlock()
            }
            guard let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode),
                  let data,
                  let parsed = parseDistTags(data) else { return }

            lock.lock()
            // Only ever move forward: a registry hiccup or a yanked release
            // must not walk the fingerprint backwards past a gate we already
            // satisfy.
            if compare(parsed, cached) > 0 { cached = parsed }
            let resolved = cached
            lock.unlock()

            UserDefaults.standard.set(resolved, forKey: defaultsKey)
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: fetchedAtKey)
        }.resume()
    }

    /// Pick the highest plausible version among the published dist-tags.
    /// `latest` normally wins; comparing all of them means a registry that
    /// reorders or renames tags still yields the newest release.
    static func parseDistTags(_ data: Data) -> String? {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let dict = object as? [String: Any] else { return nil }
        var best: String?
        for value in dict.values {
            guard let version = value as? String, isPlausible(version) else { continue }
            if best == nil || compare(version, best!) > 0 { best = version }
        }
        return best
    }

    /// Guards against a pathological registry response poisoning the UA.
    static func isPlausible(_ version: String) -> Bool {
        let parts = version.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return false }
        return parts.allSatisfy { !$0.isEmpty && $0.allSatisfy(\.isNumber) }
    }

    /// Numeric dotted-version comparison: 2.1.280 > 2.1.99 > 2.1.9.
    static func compare(_ a: String, _ b: String) -> Int {
        let pa = a.split(separator: ".").map { Int($0) ?? 0 }
        let pb = b.split(separator: ".").map { Int($0) ?? 0 }
        for index in 0..<max(pa.count, pb.count) {
            let na = index < pa.count ? pa[index] : 0
            let nb = index < pb.count ? pb[index] : 0
            if na != nb { return na < nb ? -1 : 1 }
        }
        return 0
    }

    /// Test seam: reset all state so each test starts from a known baseline.
    static func resetForTest() {
        lock.lock()
        cached = fallbackVersion
        refreshInFlight = false
        lock.unlock()
        UserDefaults.standard.removeObject(forKey: defaultsKey)
        UserDefaults.standard.removeObject(forKey: fetchedAtKey)
    }
}
