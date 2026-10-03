import XCTest
@testable import Minis

/// `apple-calendar create --all-day` — the two helpers `cmd_create` relies on.
/// [T-calendar-all-day]
///
/// `cmd_create` itself needs EventKit authorisation and a real store, so the
/// decision ("is this an all-day request?") and the normalisation ("what day
/// range does EventKit get?") live in `NativeOffloadUtils` where they can be
/// pinned without a device calendar.
final class CalendarAllDayTests: XCTestCase {

    // MARK: date-only detection

    func testBareDateIsDateOnly() {
        XCTAssertTrue(noff_is_date_only_string("2026-12-01"))
        XCTAssertTrue(noff_is_date_only_string("2026-02-28"))
    }

    func testDatetimeIsNotDateOnly() {
        XCTAssertFalse(noff_is_date_only_string("2026-12-01T09:00"))
        XCTAssertFalse(noff_is_date_only_string("2026-12-01T09:00:00Z"))
        XCTAssertFalse(noff_is_date_only_string("2026-12-01 09:00"))
    }

    func testRelativeAndMalformedAreNotDateOnly() {
        XCTAssertFalse(noff_is_date_only_string("-7d"))
        XCTAssertFalse(noff_is_date_only_string("2026/12/01"))
        XCTAssertFalse(noff_is_date_only_string("2026-13-01"), "month 13 must not parse")
        XCTAssertFalse(noff_is_date_only_string("20261201"))
        XCTAssertFalse(noff_is_date_only_string(""))
        XCTAssertFalse(noff_is_date_only_string(nil))
    }

    // MARK: all-day bounds

    private func components(_ date: Date) -> DateComponents {
        Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
    }

    func testSingleDayBoundsAreStartOfDayToLastSecond() {
        let start = noff_parse_date("2026-12-01T14:30")!
        let end = noff_parse_date("2026-12-01T15:30")!
        var s: NSDate = NSDate(), e: NSDate = NSDate()
        noff_all_day_bounds(start, end, &s, &e)
        let sc = components(s as Date), ec = components(e as Date)
        XCTAssertEqual([sc.year, sc.month, sc.day, sc.hour, sc.minute, sc.second], [2026, 12, 1, 0, 0, 0])
        XCTAssertEqual([ec.year, ec.month, ec.day, ec.hour, ec.minute, ec.second], [2026, 12, 1, 23, 59, 59])
    }

    func testMultiDayBoundsCoverBothDays() {
        let start = noff_parse_date("2026-12-01")!
        let end = noff_parse_date("2026-12-03")!
        var s: NSDate = NSDate(), e: NSDate = NSDate()
        noff_all_day_bounds(start, end, &s, &e)
        let sc = components(s as Date), ec = components(e as Date)
        XCTAssertEqual([sc.day, sc.hour], [1, 0])
        XCTAssertEqual([ec.day, ec.hour, ec.minute, ec.second], [3, 23, 59, 59])
    }

    func testEndBeforeStartCollapsesToOneDay() {
        let start = noff_parse_date("2026-12-05")!
        let end = noff_parse_date("2026-12-02")!
        var s: NSDate = NSDate(), e: NSDate = NSDate()
        noff_all_day_bounds(start, end, &s, &e)
        XCTAssertEqual(components(s as Date).day, 5)
        XCTAssertEqual(components(e as Date).day, 5)
        XCTAssertLessThan(s as Date, e as Date)
    }

    /// The bare-date parse lands on local midnight, so the implied all-day path
    /// (`--start 2026-12-01 --end 2026-12-01`, no flag) normalises to a full day
    /// rather than a zero-length event at 00:00.
    func testImpliedAllDayFromBareDatesIsAFullDay() {
        let d = noff_parse_date("2026-12-01")!
        var s: NSDate = NSDate(), e: NSDate = NSDate()
        noff_all_day_bounds(d, d, &s, &e)
        XCTAssertEqual((e as Date).timeIntervalSince(s as Date), 86_399, accuracy: 3_600,
                       "a full day give or take a DST hour")
        XCTAssertEqual(components(e as Date).hour, 23)
    }
}
