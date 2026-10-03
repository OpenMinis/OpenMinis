// Tests for [T-copilot-models-refresh-window] (M01) — the automatic model-list
// refresh gate is a 6-hour ROLLING window, re-checked on foreground resume,
// not "once per calendar day".
//
// Pins fe625d5fd. Field case 2026-09-19: the Mac refreshed at 09:55, GitHub
// enabled gpt-6-astra for the account during the day, and the app made zero
// further /models calls until a manual refresh at 22:20 — the old
// `Calendar.isDateInToday` gate would not ask again before midnight.
//
// The gate arithmetic (the part that was wrong) is reproduced verbatim from
// ProviderConfigStore.refreshAllModelsIfNeeded; the OLD gate is kept alongside
// as a witness. Section [5] greps the shipping source for the window constant,
// the rolling comparison, the absence of the day-boundary check, and BOTH call
// sites (cold launch + scenePhase → .active).
//
// Standalone (`swift ModelsRefreshWindowTests.swift`) like its neighbours.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Under test (mirrors ProviderConfigStore.refreshAllModelsIfNeeded)

let modelsRefreshWindow: TimeInterval = 6 * 60 * 60

/// The NEW gate. `lastRefresh == nil` (first launch) must fire.
func shouldSkip(lastRefresh: Date?, now: Date) -> Bool {
    if let lastRefresh, now.timeIntervalSince(lastRefresh) < modelsRefreshWindow { return true }
    return false
}

/// The OLD gate, kept to show what changed.
func oldShouldSkip(lastRefresh: Date?, now: Date, calendar: Calendar) -> Bool {
    guard let lastRefresh else { return false }
    return calendar.isDate(lastRefresh, inSameDayAs: now)
}

/// Minimal model of the store: the gate, the enabled-instance guard and the
/// "stamp the date BEFORE firing" ordering.
struct Harness {
    var lastRefresh: Date? = nil
    var enabledInstances = 1
    var fired: [Date] = []
    mutating func refreshAllModelsIfNeeded(now: Date) {
        if shouldSkip(lastRefresh: lastRefresh, now: now) { return }
        guard enabledInstances > 0 else { return }
        lastRefresh = now
        fired.append(now)
    }
}

var cal = Calendar(identifier: .gregorian)
cal.timeZone = TimeZone(secondsFromGMT: 0)!
func at(_ h: Int, _ m: Int = 0, day: Int = 19) -> Date {
    var c = DateComponents(); c.year = 2026; c.month = 9; c.day = day; c.hour = h; c.minute = m
    c.timeZone = TimeZone(secondsFromGMT: 0)
    return cal.date(from: c)!
}

print("\n[1] The reported scenario — refreshed 09:55, model granted mid-day, user returns 22:20")
do {
    var h = Harness()
    h.refreshAllModelsIfNeeded(now: at(9, 55))
    checkEq("cold launch fires", h.fired.count, 1)
    // Old gate: same calendar day → skip. New gate: 12h25m > 6h → fire.
    check("OLD gate would have skipped at 22:20", oldShouldSkip(lastRefresh: at(9, 55), now: at(22, 20), calendar: cal))
    h.refreshAllModelsIfNeeded(now: at(22, 20))
    checkEq("NEW gate fires again the same day", h.fired.count, 2)
}

print("\n[2] Boundary — 5h59 skips, 6h00 fires, 6h01 fires")
do {
    let last = at(9, 55)
    check("5h59m later → skip", shouldSkip(lastRefresh: last, now: last.addingTimeInterval(6 * 3600 - 60)))
    check("5h59m59s later → skip", shouldSkip(lastRefresh: last, now: last.addingTimeInterval(6 * 3600 - 1)))
    check("exactly 6h later → fire (strict <)", shouldSkip(lastRefresh: last, now: last.addingTimeInterval(6 * 3600)), false)
    check("6h01m later → fire", shouldSkip(lastRefresh: last, now: last.addingTimeInterval(6 * 3600 + 60)), false)
}

print("\n[3] First launch — nil last-refresh date fires")
do {
    check("nil date does not skip", shouldSkip(lastRefresh: nil, now: at(3)), false)
    var h = Harness()
    h.refreshAllModelsIfNeeded(now: at(0, 1))
    checkEq("fires on the very first check", h.fired.count, 1)
    checkEq("…and stamps the date", h.lastRefresh, at(0, 1))
}

print("\n[4] Foreground-resume trigger — same gate, many calls, bounded fetches")
do {
    // The app is opened 30 times during a 24h day (every 48 min). A rolling
    // 6h window allows at most 4 fetches per provider per day (the commit's
    // stated cost bound); the old gate would have allowed exactly 1.
    var h = Harness()
    var old = 0
    var oldLast: Date? = nil
    var t = at(0, 0)
    for _ in 0..<30 {
        h.refreshAllModelsIfNeeded(now: t)
        if !oldShouldSkip(lastRefresh: oldLast, now: t, calendar: cal) { old += 1; oldLast = t }
        t = t.addingTimeInterval(48 * 60)
    }
    checkEq("rolling window: 4 fetches in a day of continuous resumes", h.fired.count, 4)
    checkEq("old day gate: only 1", old, 1)
    // Every fire is at least 6h after the previous one.
    let gaps = zip(h.fired, h.fired.dropFirst()).map { $1.timeIntervalSince($0) }
    check("no two fires closer than 6h", gaps.allSatisfy { $0 >= modelsRefreshWindow })
}

print("\n[4b] Midnight crossing no longer matters — 23:30 then 00:30 is still inside the window")
do {
    let last = at(23, 30)
    let next = at(0, 30, day: 20)
    check("old gate: different day → would fire needlessly", oldShouldSkip(lastRefresh: last, now: next, calendar: cal), false)
    check("new gate: 1h later → skip", shouldSkip(lastRefresh: last, now: next))
}

print("\n[4c] No enabled instances — nothing fires and the date is NOT stamped")
do {
    var h = Harness(); h.enabledInstances = 0
    h.refreshAllModelsIfNeeded(now: at(9))
    checkEq("no fire", h.fired.count, 0)
    check("date untouched so enabling a provider later fires immediately", h.lastRefresh == nil)
    h.enabledInstances = 1
    h.refreshAllModelsIfNeeded(now: at(9, 1))
    checkEq("fires once a provider exists", h.fired.count, 1)
}

print("\n[4d] Clock skew — a last-refresh date in the FUTURE still skips (negative interval < window)")
do {
    // Not a behaviour we want forever, but it is what ships: a future date is
    // treated like a very recent one. Pinned so a change is deliberate.
    check("future date → skip", shouldSkip(lastRefresh: at(12), now: at(11)))
}

print("\n[5] Shipping source matches these assumptions")
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let store = source("Providers/ProviderConfigStore.swift")
let app = source("MinisApp.swift")
if store.isEmpty || app.isEmpty {
    print("  ⏭  source not readable from this sandbox")
} else {
    check("window constant is 6h", store.contains("static let modelsRefreshWindow: TimeInterval = 6 * 60 * 60"))
    check("gate compares a rolling interval against the window",
          store.contains("if let lastRefresh, Date().timeIntervalSince(lastRefresh) < Self.modelsRefreshWindow {"))
    // The old gate must be gone from this function.
    if let r = store.range(of: "func refreshAllModelsIfNeeded()") {
        let body = String(store[r.lowerBound...].prefix(2600))
        // The word survives in a comment that records the old behaviour; the
        // CALL must not.
        check("no calendar-day check in the gate", body.contains("calendar.isDateInToday("), false)
        check("date is stamped before the fetches are kicked off",
              body.contains("UserDefaults.standard.set(Date(), forKey: key)")
              && body.range(of: "UserDefaults.standard.set(Date(), forKey: key)")!.lowerBound
                 < body.range(of: "await autoRefreshModels(for: instance)")!.lowerBound)
        check("skips when there are no enabled instances", body.contains("guard !enabledInstances.isEmpty else"))
    } else {
        check("refreshAllModelsIfNeeded exists", false)
    }
    let callSites = app.components(separatedBy: "ProviderConfigStore.shared.refreshAllModelsIfNeeded()").count - 1
    checkEq("MinisApp calls the gate from two places (launch + foreground)", callSites, 2)
    check("the foreground call site is documented as the resume re-check",
          app.contains("Re-check the model-list\n                        // refresh window on every return to the foreground"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
