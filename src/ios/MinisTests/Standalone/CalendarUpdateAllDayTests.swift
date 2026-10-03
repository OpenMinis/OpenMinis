// [T-calendar-all-day] `apple-calendar update --all-day` — the DECISION rule
// (GH#328).
//
// `cmd_update` needs EventKit authorisation and a real store, and the two
// helpers it calls (`noff_is_date_only_string`, `noff_all_day_bounds`) are
// already pinned by MinisTests/CalendarAllDayTests.swift. What is new here, and
// what this file covers, is the rule update applies on TOP of them — which
// differs from create's because `--start` / `--end` are optional on update.
//
// Standalone (`swift CalendarUpdateAllDayTests.swift`) because the MinisTests
// target does not link on this machine (`deps/libs/libish_emu.a` is built for
// iOS, not iOS-simulator). The rule is reproduced verbatim from
// CalendarOffload.m cmd_update.

import Foundation

// MARK: - The rule, copied verbatim from cmd_update

/// Returns: (normaliseBounds, allDayFlag) — or nil when update leaves the
/// event's all-day state alone.
func updateAllDayDecision(startStr: String?, endStr: String?, hasAllDayFlag: Bool) -> (normalise: Bool, allDay: Bool)? {
    let sawDateArg = (startStr != nil || endStr != nil)
    let allBareDates = sawDateArg
        && (startStr == nil || isDateOnly(startStr!))
        && (endStr == nil || isDateOnly(endStr!))
    if hasAllDayFlag || allBareDates {
        return (normalise: true, allDay: true)
    } else if sawDateArg {
        return (normalise: false, allDay: false)
    }
    return nil   // no date args and no flag -> untouched
}

/// Stand-in for noff_is_date_only_string: strictly YYYY-MM-DD.
func isDateOnly(_ s: String) -> Bool {
    let parts = s.split(separator: "-", omittingEmptySubsequences: false)
    guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2 else { return false }
    guard parts.allSatisfy({ $0.allSatisfy(\.isNumber) }) else { return false }
    guard let m = Int(parts[1]), let d = Int(parts[2]), (1...12).contains(m), (1...31).contains(d) else { return false }
    return true
}

var failures = 0
/// Bool overload, for the plain yes/no rules below.
func check(_ name: String, _ got: Bool, _ want: Bool) {
    if got == want { print("  ✅ \(name)") }
    else { print("  ❌ \(name): got \(got), want \(want)"); failures += 1 }
}
func check(_ name: String, _ got: (normalise: Bool, allDay: Bool)?, _ want: (normalise: Bool, allDay: Bool)?) {
    let ok: Bool
    switch (got, want) {
    case (nil, nil): ok = true
    case let (g?, w?): ok = (g == w)
    default: ok = false
    }
    if ok { print("  ✅ \(name)") }
    else {
        print("  ❌ \(name): got \(String(describing: got)), want \(String(describing: want))")
        failures += 1
    }
}

// MARK: - Explicit flag

print("--all-day flag")
check("flag alone (no dates) still day-aligns the existing bounds",
      updateAllDayDecision(startStr: nil, endStr: nil, hasAllDayFlag: true),
      (true, true))
check("flag wins over a supplied time",
      updateAllDayDecision(startStr: "2026-12-01T09:00", endStr: nil, hasAllDayFlag: true),
      (true, true))

// MARK: - Bare-date inference

print("\nBare-date inference")
check("both bare -> all-day",
      updateAllDayDecision(startStr: "2026-12-01", endStr: "2026-12-03", hasAllDayFlag: false),
      (true, true))
check("only --start, bare -> all-day",
      updateAllDayDecision(startStr: "2026-12-01", endStr: nil, hasAllDayFlag: false),
      (true, true))
check("only --end, bare -> all-day",
      updateAllDayDecision(startStr: nil, endStr: "2026-12-03", hasAllDayFlag: false),
      (true, true))
check("one bare, one timed -> NOT all-day",
      updateAllDayDecision(startStr: "2026-12-01", endStr: "2026-12-03T17:00", hasAllDayFlag: false),
      (false, false))

// MARK: - Converting back to timed

print("\nBack to a timed event")
check("timed --start/--end clears the all-day flag",
      updateAllDayDecision(startStr: "2026-12-01T09:00", endStr: "2026-12-01T10:00", hasAllDayFlag: false),
      (false, false))
check("a single timed --start also clears it",
      updateAllDayDecision(startStr: "2026-12-01T09:00", endStr: nil, hasAllDayFlag: false),
      (false, false))

// MARK: - The regression this rule exists to prevent

print("\nUnrelated edits must not touch all-day state")
check("--title only -> untouched (nil)",
      updateAllDayDecision(startStr: nil, endStr: nil, hasAllDayFlag: false),
      nil)
// This is the trap: inferring from an ABSENT argument would read "no --start"
// as "not a bare date" (or as one) and silently flip a timed event. Returning
// nil is what keeps `--location`-only edits inert.

// MARK: - all-day -> timed ordering (device-found bug)

// [T-calendar-all-day-to-timed] Measured on iPhone 11 / iOS 27: while `allDay`
// is still YES, EventKit DISCARDS writes to startDate/endDate. The save
// succeeds and reports allDay:NO, but the times stay 00:00:00-23:59:59.
//
// So the flag must be cleared BEFORE the dates are written — the opposite of
// the all-day direction, where the flag goes on AFTER the range is day-aligned.
// This models the two decisions cmd_update now makes.

/// Does a timed date argument clear the all-day flag up front?
func clearsAllDayFirst(startStr: String?, endStr: String?, isAllDay: Bool, hasAllDayFlag: Bool) -> Bool {
    let timedArg = (startStr != nil && !isDateOnly(startStr!))
                || (endStr != nil && !isDateOnly(endStr!))
    return timedArg && isAllDay && !hasAllDayFlag
}

/// Which field is written first, so the pair never passes through end < start.
/// Returns "end" or "start" for the first write; nil when only one is supplied.
func firstWrite(newStart: Int?, newEnd: Int?, currentEnd: Int) -> String? {
    guard let s = newStart, newEnd != nil else { return nil }
    return s > currentEnd ? "end" : "start"
}

print("\nall-day -> timed: clear the flag first")
check("timed --start on an all-day event clears the flag first",
      clearsAllDayFirst(startStr: "2026-12-10T14:00", endStr: nil, isAllDay: true, hasAllDayFlag: false),
      true)
check("timed --start AND --end likewise",
      clearsAllDayFirst(startStr: "2026-12-10T14:00", endStr: "2026-12-10T16:00", isAllDay: true, hasAllDayFlag: false),
      true)
check("bare dates do NOT clear it (they mean all-day)",
      clearsAllDayFirst(startStr: "2026-12-10", endStr: "2026-12-11", isAllDay: true, hasAllDayFlag: false),
      false)
check("an explicit --all-day wins over a supplied time",
      clearsAllDayFirst(startStr: "2026-12-10T14:00", endStr: nil, isAllDay: true, hasAllDayFlag: true),
      false)
check("an already-timed event needs no clearing",
      clearsAllDayFirst(startStr: "2026-12-10T14:00", endStr: nil, isAllDay: false, hasAllDayFlag: false),
      false)
check("a --title-only edit never touches the flag",
      clearsAllDayFirst(startStr: nil, endStr: nil, isAllDay: true, hasAllDayFlag: false),
      false)

print("\nWrite order never leaves end < start")
// Current all-day event ends at 23:59 (minute 1439). Moving it to 14:00-16:00
// keeps start below the current end, so start can be written first.
check("new start before current end -> write start first",
      firstWrite(newStart: 840, newEnd: 960, currentEnd: 1439) == "start", true)
// Moving the event LATER than the current end: writing start first would leave
// start > end for an instant, so end goes first.
check("new start after current end -> write end first",
      firstWrite(newStart: 1500, newEnd: 1560, currentEnd: 1439) == "end", true)
check("only one field supplied -> no ordering question",
      firstWrite(newStart: 840, newEnd: nil, currentEnd: 1439) == nil, true)

print(failures == 0 ? "\n✅ all checks passed" : "\n❌ \(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
