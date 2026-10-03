// Tests for OpenMinis#286: apple-reminders date-only dues and the native URL.
//
// [T-reminders-date-only] `--due 2026-02-25` always stored Hour|Minute of the
// parsed midnight in dueDateComponents, so EventKit saw "due at 00:00". The
// Reminders app showed "12:00 AM", and an update could re-point an alarm to
// midnight. A date-only due now stores Year|Month|Day only (all-day), and
// output reports `is_all_day` with due as "yyyy-MM-dd".
//
// [T-reminders-url] EKReminder.URL was never wired, so links went into
// --notes. create/update take --url (validated: absolute, with a scheme),
// update takes --clear-url, and list/create/update report `url`. On device the
// value round-trips through EventKit, but the system Reminders app does not
// display it, and the help says so.
//
// Ports the pure pieces (component units, output formatting, URL
// validation, the update alarm rule) and pins them to CalendarOffload.m /
// RemindersOffload.m. Run: swift RemindersDateOnlyUrlTests.swift
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// MARK: - Ports

/// noff_is_date_only_string (NativeOffloadUtils.m): exactly yyyy-MM-dd.
func isDateOnly(_ s: String) -> Bool {
    let c = Array(s.utf16)
    guard c.count == 10 else { return false }
    for (i, ch) in c.enumerated() {
        if i == 4 || i == 7 { if ch != UInt16(UInt8(ascii: "-")) { return false } }
        else if ch < 48 || ch > 57 { return false }
    }
    return true
}

func dueComponents(_ due: Date, dateOnly: Bool, cal: Calendar) -> DateComponents {
    var units: Set<Calendar.Component> = [.year, .month, .day]
    if !dateOnly { units.formUnion([.hour, .minute]) }
    return cal.dateComponents(units, from: due)
}

/// reminder_put_due: (due, is_all_day)
func putDue(_ c: DateComponents, cal: Calendar) -> (String, Bool) {
    let allDay = c.hour == nil
    if allDay, let y = c.year, let m = c.month, let d = c.day {
        return (String(format: "%04ld-%02ld-%02ld", y, m, d), true)
    }
    let f = ISO8601DateFormatter(); f.timeZone = cal.timeZone
    return (cal.date(from: c).map { f.string(from: $0) } ?? "null", false)
}

func parseURL(_ raw: String) -> URL? {
    let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !t.isEmpty, let u = URL(string: t), let scheme = u.scheme, !scheme.isEmpty else { return nil }
    return u
}

/// The update path's alarm decision when --due changes and --notify is absent.
func updateKeepsAlarm(dueStr: String, hadAlarm: Bool, hasTimeOfDay: Bool, fixed: Bool) -> Bool {
    if fixed && isDateOnly(dueStr) { return false }
    return hadAlarm || hasTimeOfDay
}

var cal = Calendar(identifier: .gregorian)
cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
let midnight = cal.date(from: DateComponents(year: 2026, month: 2, day: 25, hour: 0, minute: 0))!
let evening = cal.date(from: DateComponents(year: 2026, month: 2, day: 25, hour: 18, minute: 0))!

print("▶️  1. date-only due → all-day components")
do {
    let old = cal.dateComponents([.year, .month, .day, .hour, .minute], from: midnight)
    check("OLD: '2026-02-25' stored hour 0 (the 12:00 AM reminder)", old.hour == 0)
    let new = dueComponents(midnight, dateOnly: isDateOnly("2026-02-25"), cal: cal)
    check("NEW: no hour/minute → EventKit all-day", new.hour == nil && new.minute == nil)
    check("…same calendar day", new.year == 2026 && new.month == 2 && new.day == 25)
    let timed = dueComponents(evening, dateOnly: isDateOnly("2026-02-25T18:00"), cal: cal)
    check("a timed due is unchanged (18:00)", timed.hour == 18 && timed.minute == 0)
}
check("date-only detection: exact yyyy-MM-dd only",
      isDateOnly("2026-02-25") && !isDateOnly("2026-02-25T00:00") && !isDateOnly("-2d") && !isDateOnly("2026-2-25"))

print("\n▶️  2. output: due + is_all_day")
do {
    let (due, allDay) = putDue(DateComponents(year: 2026, month: 2, day: 25), cal: cal)
    check("all-day → due 'yyyy-MM-dd', is_all_day true", due == "2026-02-25" && allDay)
    let (tdue, tAllDay) = putDue(cal.dateComponents([.year, .month, .day, .hour, .minute], from: evening), cal: cal)
    check("timed → ISO 8601 with time, is_all_day false", tdue.contains("T18:00") && !tAllDay)
    // Built from components, so no zone can push it to the 24th/26th.
    var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(identifier: "America/Los_Angeles")!
    check("all-day formatting ignores the time zone",
          putDue(DateComponents(year: 2026, month: 2, day: 25), cal: utc).0 == "2026-02-25")
}

print("\n▶️  3. update alarm rule when --due changes (no --notify)")
check("OLD: timed → date-only re-pointed the alarm to midnight",
      updateKeepsAlarm(dueStr: "2026-03-01", hadAlarm: true, hasTimeOfDay: false, fixed: false))
check("NEW: timed → date-only drops the time alarm",
      !updateKeepsAlarm(dueStr: "2026-03-01", hadAlarm: true, hasTimeOfDay: false, fixed: true))
check("date-only → timed attaches one", updateKeepsAlarm(dueStr: "2026-03-01T09:00", hadAlarm: false, hasTimeOfDay: true, fixed: true))
check("timed → timed keeps it", updateKeepsAlarm(dueStr: "2026-03-02T10:00", hadAlarm: true, hasTimeOfDay: true, fixed: true))

print("\n▶️  4. URL validation")
check("https URL accepted", parseURL("https://example.com/a?b=1")?.absoluteString == "https://example.com/a?b=1")
check("other schemes accepted (mailto, obsidian)", parseURL("mailto:a@b.c") != nil && parseURL("obsidian://open?vault=x") != nil)
check("surrounding whitespace trimmed", parseURL("  https://x.y  ")?.absoluteString == "https://x.y")
check("no scheme rejected", parseURL("example.com/page") == nil)
check("empty rejected", parseURL("") == nil && parseURL("   ") == nil)
check("not a URL rejected", parseURL("not a url") == nil)

// MARK: - Sources

let root = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func source(_ rel: String) -> String {
    (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let cal0 = source("NativeOffloads/CalendarOffload.m")
let help = source("NativeOffloads/RemindersOffload.m")
func body(_ start: String) -> String {
    guard let a = cal0.range(of: start),
          let b = cal0.range(of: "\n}\n", range: a.upperBound..<cal0.endIndex) else { return "" }
    return String(cal0[a.lowerBound..<b.upperBound])
}
let listFn = body("int calendar_cmd_reminders(")
let createFn = body("int calendar_cmd_remind(")
let updateFn = body("int calendar_cmd_update_reminder(")

print("\n▶️  5. sources")
check("no reminder path hard-codes Hour|Minute components any more",
      !createFn.contains("NSCalendarUnitHour | NSCalendarUnitMinute)") && !updateFn.contains("NSCalendarUnitHour | NSCalendarUnitMinute)"))
check("create and update build components via the date-only aware helper",
      createFn.contains("reminder_due_components(due, noff_is_date_only_string(dueStr))")
        && updateFn.contains("reminder_due_components(due, noff_is_date_only_string(dueStr))"))
check("start anchor still follows due (#282)",
      createFn.contains("reminder.startDateComponents = reminder.dueDateComponents")
        && updateFn.contains("reminder.startDateComponents = reminder.dueDateComponents"))
check("helper uses date units only for date-only",
      cal0.contains("if (!dateOnly) units |= NSCalendarUnitHour | NSCalendarUnitMinute;"))
check("list, create and update all report due/is_all_day through reminder_put_due",
      listFn.contains("reminder_put_due(d, r);") && createFn.contains("reminder_put_due(data, reminder);")
        && updateFn.contains("reminder_put_due(data, reminder);"))
check("all-day due formatted from components",
      cal0.contains("d[@\"due\"] = [NSString stringWithFormat:@\"%04ld-%02ld-%02ld\","))
check("create's default notify stays off for a date-only due",
      createFn.contains(": (dueDate != nil && due_string_has_time_of_day(dueStr));"))
check("update never re-points an alarm to a date-only midnight",
      updateFn.contains("if (!noff_is_date_only_string(dueStr)\n            && (had || due_string_has_time_of_day(dueStr))) {"))
check("list/create/update report url",
      listFn.contains("d[@\"url\"] = r.URL.absoluteString ?: [NSNull null];")
        && createFn.contains("data[@\"url\"] = reminder.URL.absoluteString ?: [NSNull null];")
        && updateFn.contains("@\"url\": reminder.URL.absoluteString ?: [NSNull null],"))
check("create validates --url before saving",
      (createFn.range(of: "parse_reminder_url(urlStr, &urlErr)")?.lowerBound ?? createFn.endIndex)
        < (createFn.range(of: "saveReminder:reminder")?.lowerBound ?? createFn.startIndex))
check("update: --url, --clear-url, and their mutual exclusion",
      updateFn.contains("parse_reminder_url(updUrlStr, &urlErr)")
        && updateFn.contains("reminder.URL = nil;")
        && updateFn.contains("--url and --clear-url are mutually exclusive."))
check("URL must have a scheme", cal0.contains("if (!url || url.scheme.length == 0) {"))
check("help documents --url and --clear-url",
      help.contains("--url <url>          Store a link in the reminder's URL field") && help.contains("--clear-url          Remove the link"))
check("help is honest that the Reminders app does not display the URL",
      help.contains("Reminders app does not display this field"))

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)"); exit(1) }
