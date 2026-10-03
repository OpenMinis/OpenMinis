import Foundation

/// Masks env-var values that leaked into shell tool output before they
/// reach the model.
///
/// Mask rule: `len < 8` → all `*`; `len >= 8` → first 2 + (len-4) `*` +
/// last 2 (e.g. `sk-1********ajhks`).
///
/// Matching is plain `String.replacingOccurrences` — no regex — to avoid
/// ReDoS on attacker-influenced output. Values with length `<= 4` are
/// skipped to avoid mangling unrelated text — short common strings
/// (e.g. `"true"`, `"data"`, `"http"`) frequently collide with normal
/// output, and replacing every occurrence with `*` would be more
/// disruptive than leaving them visible.
enum EnvVarRedactor {
    static let minMatchLen = 5

    /// English-only system reminder appended to a tool result when at least
    /// one redaction occurred. Single line so it survives downstream
    /// trimming/normalization, with explicit instructions to the model on
    /// how to operate without echoing secrets.
    static let systemReminder = "<system-reminder>Privacy mode is ON: one or more environment variable values were detected in this output and have been masked. Prefer running commands that consume secrets via environment variable references (e.g. `curl -H \"Authorization: Bearer $API_KEY\" ...`) rather than echoing them. If the user needs to inspect raw values, ask them to disable Privacy Mode at Settings → Environment Variables.</system-reminder>"

    static func mask(_ value: String) -> String {
        let n = value.count
        if n < 8 {
            return String(repeating: "*", count: n)
        }
        let head = value.prefix(2)
        let tail = value.suffix(2)
        return "\(head)\(String(repeating: "*", count: n - 4))\(tail)"
    }

    /// Redacts `output` against the given env-var values. Returns the
    /// rewritten string and the number of distinct values that produced at
    /// least one replacement.
    ///
    /// Values are de-duplicated and processed longest-first so that
    /// `BAR` appearing as a substring of a longer value `FOOBAR` does not
    /// pre-empt the longer match.
    static func redact(_ output: String, against values: [String]) -> (String, Int) {
        let candidates = Set(values
            .filter { $0.count >= minMatchLen })
            .sorted { $0.count > $1.count }
        guard !candidates.isEmpty else { return (output, 0) }

        var current = output
        var hits = 0
        for v in candidates {
            if current.contains(v) {
                current = current.replacingOccurrences(of: v, with: mask(v))
                hits += 1
            }
        }
        return (current, hits)
    }

    /// Convenience wrapper — pulls values from disk + keychain (so it can
    /// run from any thread without a main-actor hop) and returns the
    /// possibly reminder-suffixed output plus the hit count. No-op when
    /// Privacy Mode is disabled.
    static func redactIfEnabled(_ output: String) -> (String, Int) {
        guard EnvVarPrivacyStore.isEnabled() else { return (output, 0) }
        let values = loadAllValues()
        let (masked, hits) = redact(output, against: values)
        if hits == 0 { return (masked, 0) }
        // Append the reminder with two newlines so it's clearly separated
        // from the (possibly trailing-newline) shell output.
        return (masked + "\n\n" + systemReminder, hits)
    }

    /// [T-envvar-redactor-main-thread-keychain] Cached values + the lock that
    /// guards them.
    ///
    /// `loadAllValues()` was re-running on EVERY tool result: re-read
    /// env-vars.json, JSON-decode it, then one synchronous `SecItemCopyMatching`
    /// per variable. A crash report caught that whole chain on **Thread 0**
    /// (EnvVarEntry.init(from:) ← loadAllValues ← redactIfEnabled ←
    /// executeSingleToolUse), despite the comment below promising it "can run
    /// from any thread" — nothing forced it off the main actor, so a chatty
    /// tool ran a disk+keychain round-trip per output chunk on the main thread.
    ///
    /// Keychain reads can block indefinitely (the item is
    /// `WhenUnlockedThisDeviceOnly`, and `SecItemCopyMatching` talks to
    /// securityd over XPC), which on the main thread is a watchdog kill
    /// waiting to happen.
    ///
    /// The values change only when the user edits them in Settings, so cache
    /// them and let `invalidateCache()` clear it. NSLock rather than actor
    /// isolation because callers are deliberately nonisolated.
    nonisolated(unsafe) private static var cachedValues: [String]?
    private static let cacheLock = NSLock()
    /// [T-envvar-redactor-fill-race] Bumped by every `invalidateCache()`.
    /// The load runs outside the lock, so an invalidate can land while it is
    /// in flight; the loader only publishes its result if the generation it
    /// started at is still current, otherwise its (possibly stale) list would
    /// overwrite the invalidation and a just-added secret would stay unmasked.
    nonisolated(unsafe) private static var cacheGeneration: UInt64 = 0

    /// Drop the cached values. Called whenever the env-var set changes, so a
    /// newly added secret is masked from the very next tool result.
    static func invalidateCache() {
        cacheLock.withLock {
            cachedValues = nil
            cacheGeneration &+= 1
        }
    }

    /// Read every env-var value directly off disk + keychain. Mirrors
    /// `EnvVarStore.allAsDict()` but stays nonisolated.
    ///
    /// Cached — see `cachedValues`. The load itself is unchanged.
    private static func loadAllValues() -> [String] {
        cacheLock.lock()
        if let cachedValues {
            cacheLock.unlock()
            return cachedValues
        }
        let generationAtStart = cacheGeneration
        cacheLock.unlock()

        let loaded = loadAllValuesUncached()

        // [T-envvar-redactor-fill-race] Publish only if no invalidate raced
        // this load. The caller still gets `loaded`; a racing invalidate just
        // means the next call reloads fresh values.
        cacheLock.withLock {
            if cacheGeneration == generationAtStart { cachedValues = loaded }
        }
        return loaded
    }

    private static func loadAllValuesUncached() -> [String] {
        let libraryURL = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let fileURL = libraryURL.appendingPathComponent("MinisChat/env-vars.json")
        guard let data = try? Data(contentsOf: fileURL),
              let entries = try? JSONDecoder().decode([EnvVarEntry].self, from: data) else {
            return []
        }
        var out: [String] = []
        out.reserveCapacity(entries.count)
        for entry in entries {
            if let v = EnvVarStore.loadValueSync(forKey: entry.key), !v.isEmpty {
                out.append(v)
            }
        }
        return out
    }
}
