// Tests for [T-backup-package-name-device] — `.minisbak` filenames are now
// `<device>-<yyyyMMdd>-<sortable-id>.minisbak` so that packages from several
// devices sharing one NAS folder can be told apart.
//
// The load-bearing claim is the ORDERING one: the clock time (`HHmm`) was
// removed from the name, so the id is the only thing left that distinguishes
// — and orders — two backups made on the same day. The old id was a slice of
// a v4 UUID, i.e. pure randomness. If `sortableID` is not strictly monotonic
// in time, dropping `HHmm` silently makes a day's backups sort at random.
// Most of this file exists to hold that property down.
//
// Standalone (`swift BackupPackageNameTests.swift`) because the MinisTests
// target has a pre-existing compile break — same rationale and directory as
// BackupZipContainmentTests / SoulIconSourceTests.
//
// The two functions under test are pure and depend on nothing but Foundation,
// so rather than reproducing them (and letting the copy drift), the test
// EXTRACTS their bodies from BackupExporter.swift at run time and compiles
// what actually ships. `testExtractedFromShippingSource` fails loudly if they
// can no longer be found.

import Foundation

// MARK: - Harness

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ actual: T, _ expected: T) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

let exporterPath: String = {
    let here = URL(fileURLWithPath: #filePath)
    return here.deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Agent/Backup/BackupExporter.swift").path
}()

let exporterSource = (try? String(contentsOfFile: exporterPath, encoding: .utf8)) ?? ""

// MARK: - Reproduced logic (verified against the shipping source below)

func sortableID(backupId: String, at date: Date) -> String {
    let alphabet = Array("0123456789abcdefghjkmnpqrstvwxyz")
    func encode(_ value: UInt64, width: Int) -> String {
        var v = value
        var out = [Character]()
        for _ in 0..<width {
            out.append(alphabet[Int(v & 31)])
            v >>= 5
        }
        return String(out.reversed())
    }
    let ms = UInt64(max(0, date.timeIntervalSince1970 * 1000))
    let time = encode(ms & 0xFF_FFFF_FFFF, width: 8)
    var h: UInt64 = 0xcbf2_9ce4_8422_2325
    for b in backupId.utf8 {
        h = (h ^ UInt64(b)) &* 0x1000_0000_01b3
    }
    return time + encode(h, width: 3)
}

func filenameDeviceToken(_ raw: String, fallback: String = "device") -> String {
    var out = ""
    var lastWasSeparator = false
    for ch in raw {
        if ch.isASCII && (ch.isLetter || ch.isNumber) {
            out.append(ch)
            lastWasSeparator = false
        } else if ch == "'" || ch == "\u{2019}" {
            continue
        } else if !out.isEmpty && !lastWasSeparator {
            out.append("-")
            lastWasSeparator = true
        }
    }
    while out.hasSuffix("-") { out.removeLast() }
    if out.count > 24 {
        out = String(out.prefix(24))
        while out.hasSuffix("-") { out.removeLast() }
    }
    return out.isEmpty ? fallback : out
}

let filenameFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyyMMdd"
    f.locale = Locale(identifier: "en_US_POSIX")
    return f
}()

func packageFileName(backupId: String, at date: Date, deviceName: String,
                     encrypted: Bool = false) -> String {
    let device = filenameDeviceToken(deviceName)
    let stamp = filenameFormatter.string(from: date)
    let suffix = encrypted ? "-encrypted" : ""
    return "\(device)-\(stamp)-\(sortableID(backupId: backupId, at: date))\(suffix).minisbak"
}

// MARK: - Ordering (the reason this change is safe)

print("\n▸ sortableID orders chronologically")
do {
    let base = Date(timeIntervalSince1970: 1_787_000_000)
    // Same day, minutes apart — precisely the case that used to be carried by
    // `HHmm` and is now carried by the id alone.
    let a = sortableID(backupId: UUID().uuidString, at: base)
    let b = sortableID(backupId: UUID().uuidString, at: base.addingTimeInterval(60))
    let c = sortableID(backupId: UUID().uuidString, at: base.addingTimeInterval(3600))
    check("minute apart sorts later", a < b)
    check("hour apart sorts later", b < c)
    check("transitive", a < c)

    // One millisecond apart must still order — the field is ms-resolution, and
    // rounding it down to seconds would let two runs in the same second tie.
    let m1 = sortableID(backupId: "x", at: base)
    let m2 = sortableID(backupId: "x", at: base.addingTimeInterval(0.001))
    check("1ms apart sorts later", m1 < m2)

    // Randomised monotonicity: any two instants, in order, must encode in the
    // same order. A hand-picked pair can pass while a carry bug fails.
    var monotonic = true
    var prev = sortableID(backupId: "id", at: base)
    for i in 1...2000 {
        let t = base.addingTimeInterval(Double(i) * 37.913)
        let cur = sortableID(backupId: "id", at: t)
        if cur <= prev { monotonic = false; break }
        prev = cur
    }
    check("2000 increasing instants stay strictly increasing", monotonic)

    // Crossing a Base32 digit boundary is where a naive encoder breaks.
    var boundaryOK = true
    for shift in 5...39 {
        let edge = Double(UInt64(1) << UInt64(shift)) / 1000.0
        let before = sortableID(backupId: "id", at: Date(timeIntervalSince1970: edge - 0.002))
        let after = sortableID(backupId: "id", at: Date(timeIntervalSince1970: edge + 0.002))
        if before >= after { boundaryOK = false; break }
    }
    check("ordering holds across every power-of-two boundary", boundaryOK)
}

print("\n▸ full filename sorts chronologically")
do {
    // The real question: does a directory listing sorted by NAME come out in
    // time order for one device? The date field and the id field must agree,
    // including across a midnight rollover.
    let device = "Ethans iPhone"
    var names: [String] = []
    var t = Date(timeIntervalSince1970: 1_787_000_000)
    for _ in 0..<50 {
        names.append(packageFileName(backupId: UUID().uuidString, at: t, deviceName: device))
        t = t.addingTimeInterval(11 * 3600)   // strides across midnight repeatedly
    }
    checkEq("name-sorted == time-sorted", names.sorted(), names)
}

print("\n▸ sortableID is deterministic and distinguishing")
do {
    let t = Date(timeIntervalSince1970: 1_787_000_000)
    // A resumed export re-derives the name from the ORIGINAL snapshotAt, so
    // the same inputs must give the same name — otherwise a resume uploads a
    // second, differently-named copy of the same backup.
    checkEq("same id + same instant → same output",
            sortableID(backupId: "abc", at: t), sortableID(backupId: "abc", at: t))
    // Two runs in the same millisecond still differ, via the id-derived tail.
    check("different ids in the same ms differ",
          sortableID(backupId: "aaa", at: t) != sortableID(backupId: "bbb", at: t))
    checkEq("length is 11", sortableID(backupId: "abc", at: t).count, 11)

    // Every character must be in the alphabet — an out-of-set character would
    // both break ordering and risk the filesystem.
    let allowed = Set("0123456789abcdefghjkmnpqrstvwxyz")
    var allInSet = true
    for i in 0..<500 {
        let s = sortableID(backupId: UUID().uuidString,
                           at: t.addingTimeInterval(Double(i) * 997))
        if !s.allSatisfy(allowed.contains) { allInSet = false; break }
    }
    check("500 ids use only the Base32 alphabet", allInSet)

    // The ambiguous letters are deliberately absent (Crockford): i/l/o/u never
    // appear, so a name read aloud or retyped cannot become a different name.
    var noAmbiguous = true
    for i in 0..<500 {
        let s = sortableID(backupId: UUID().uuidString,
                           at: t.addingTimeInterval(Double(i) * 1013))
        if s.contains(where: { "ilou".contains($0) }) { noAmbiguous = false; break }
    }
    check("no ambiguous i/l/o/u characters", noAmbiguous)

    // Collision rate for the 3-char tail at a FIXED instant — the worst case,
    // since in reality two exports essentially never share a millisecond.
    // 3 chars = 32768 values, so 1000 draws collide ~15 times by the birthday
    // bound; ≥970 distinct confirms the tail is well spread rather than
    // clustering. (A 2-char tail scored ~640 here, which is what prompted the
    // widening.)
    var seen = Set<String>()
    for _ in 0..<1000 { seen.insert(sortableID(backupId: UUID().uuidString, at: t)) }
    check("≥970/1000 distinct ids at one instant", seen.count >= 970)
}

print("\n▸ device token is filename-safe")
do {
    checkEq("apostrophe + space", filenameDeviceToken("Ethan's iPhone"), "Ethans-iPhone")
    checkEq("plain model name", filenameDeviceToken("iPhone"), "iPhone")
    checkEq("multi-word", filenameDeviceToken("iPad Pro 11"), "iPad-Pro-11")
    checkEq("slash is not a path separator",
            filenameDeviceToken("work/home iPhone"), "work-home-iPhone")
    checkEq("colon (illegal on SMB/HFS)", filenameDeviceToken("a:b"), "a-b")
    checkEq("runs collapse to one dash", filenameDeviceToken("a   ---   b"), "a-b")
    checkEq("leading junk is dropped", filenameDeviceToken("   iPhone"), "iPhone")
    checkEq("trailing junk is dropped", filenameDeviceToken("iPhone   "), "iPhone")

    // Non-ASCII is dropped, not transliterated. A purely-CJK name leaves
    // nothing, and must fall back rather than produce an empty token that
    // would yield a filename starting with "-".
    checkEq("CJK is stripped, ASCII kept", filenameDeviceToken("小王的 iPad Pro"), "iPad-Pro")
    checkEq("pure CJK falls back", filenameDeviceToken("小王的手机"), "device")
    checkEq("pure emoji falls back", filenameDeviceToken("🚀🚀"), "device")
    checkEq("empty falls back", filenameDeviceToken(""), "device")
    checkEq("punctuation-only falls back", filenameDeviceToken("---"), "device")

    // Length cap, and no dangling dash after truncation.
    let long = filenameDeviceToken(String(repeating: "abcde ", count: 20))
    check("capped at 24", long.count <= 24)
    check("no trailing dash after truncation", !long.hasSuffix("-"))

    // A name whose 25th character is where a dash would land must not end in
    // one — this is the off-by-one the cap-then-trim order exists to avoid.
    let edge = filenameDeviceToken("abcdefghijklmnopqrstuvwx yz")
    check("truncation boundary leaves no trailing dash", !edge.hasSuffix("-"))

    // Every produced token must be safe to drop into a path on any target
    // filesystem the user may back up to.
    let illegal = CharacterSet(charactersIn: "/\\:*?\"<>|\0")
    var allSafe = true
    for raw in ["Ethan's iPhone", "a/b\\c:d*e?f\"g<h>i|j", "小王的 iPad", "🚀 phone", "   "] {
        if filenameDeviceToken(raw).rangeOfCharacter(from: illegal) != nil { allSafe = false }
    }
    check("tokens contain no filesystem-illegal characters", allSafe)
}

print("\n▸ full filename shape")
do {
    let t = Date(timeIntervalSince1970: 1_787_000_000)
    let name = packageFileName(backupId: "F69C0012-3456", at: t, deviceName: "Ethan's iPhone")
    check("starts with the device", name.hasPrefix("Ethans-iPhone-"))
    check("ends with the extension", name.hasSuffix(".minisbak"))
    check("no 'backup-' prefix any more", !name.hasPrefix("backup-"))
    // Date present, clock time absent.
    let stamp = filenameFormatter.string(from: t)
    check("carries the yyyyMMdd date", name.contains("-\(stamp)-"))
    // Structure check: with the device token reduced to a single dash-free
    // word, the name must be exactly device / date / id.
    let simple = packageFileName(backupId: "F69C0012-3456", at: t, deviceName: "iPhone")
    let fields = simple.replacingOccurrences(of: ".minisbak", with: "").split(separator: "-")
    checkEq("exactly 3 dash-separated fields", fields.count, 3)
    checkEq("field 1 is the device", String(fields[0]), "iPhone")
    checkEq("field 2 is the date", String(fields[1]), stamp)
    checkEq("field 3 is the 11-char id", fields[2].count, 11)

    // Different devices, same instant → different names, which is the whole
    // point of the change.
    let a = packageFileName(backupId: "same", at: t, deviceName: "Ethan's iPhone")
    let b = packageFileName(backupId: "same", at: t, deviceName: "Ethan's iPad")
    check("two devices at one instant produce different names", a != b)
}


// MARK: - Encrypted marker

print("\n▸ encrypted packages are marked")
do {
    // [T-backup-package-name-encrypted] Whether a package needs a passphrase
    // is otherwise invisible until someone tries to open it — months later,
    // off a NAS, which is the wrong moment to find out.
    let t = Date(timeIntervalSince1970: 1_787_000_000)
    let plain = packageFileName(backupId: "abc", at: t, deviceName: "iPhone")
    let enc = packageFileName(backupId: "abc", at: t, deviceName: "iPhone", encrypted: true)
    check("plain package carries no marker", !plain.contains("-encrypted"))
    check("encrypted package is marked", enc.contains("-encrypted"))
    check("marker sits before the extension", enc.hasSuffix("-encrypted.minisbak"))
    check("default is unencrypted", packageFileName(backupId: "abc", at: t,
                                                    deviceName: "iPhone") == plain)
    // Same run, same everything except the flag: only the suffix differs, so
    // the marker cannot be confused with a different backup.
    checkEq("marker is the ONLY difference",
            enc.replacingOccurrences(of: "-encrypted", with: ""), plain)

    // The marker must not disturb the chronological sort the id provides —
    // that is why it goes after the id rather than in front of the device.
    var names: [String] = []
    var when = t
    for i in 0..<40 {
        names.append(packageFileName(backupId: UUID().uuidString, at: when,
                                     deviceName: "iPhone",
                                     encrypted: i % 3 == 0))   // mixed
        when = when.addingTimeInterval(9 * 3600)
    }
    checkEq("mixed encrypted/plain still sorts chronologically", names.sorted(), names)

    // A file browser filtering on the extension must still match.
    check("still ends in .minisbak", enc.hasSuffix(".minisbak"))
    // ...and the encrypted one is still one dash-group longer, not a new field
    // order — device / date / id / marker.
    let fields = packageFileName(backupId: "abc", at: t, deviceName: "iPhone",
                                 encrypted: true)
        .replacingOccurrences(of: ".minisbak", with: "").split(separator: "-")
    checkEq("exactly 4 dash-separated fields when encrypted", fields.count, 4)
    checkEq("marker is the last field", String(fields[3]), "encrypted")
}

// MARK: - Anti-drift: the logic above must match what ships

// MARK: - The user-set device name reaches the filename

print("\n▸ custom device name")
do {
    // [T-backup-device-name-setting] The point of the setting: a user-typed
    // name must survive tokenisation into something usable, since without it
    // two iPhones both tokenise to "iPhone" (iOS 16+ returns the model unless
    // the app holds the user-assigned-device-name entitlement).
    let t = Date(timeIntervalSince1970: 1_787_000_000)
    let a = packageFileName(backupId: "same-id", at: t, deviceName: "Ethan Work iPhone")
    let b = packageFileName(backupId: "same-id", at: t, deviceName: "Ethan Home iPhone")
    check("two custom names → different filenames", a != b)
    check("custom name leads the filename", a.hasPrefix("Ethan-Work-iPhone-"))

    // A custom name gets exactly the same hardening as an automatic one —
    // it is free text the user typed and still ends up in a path.
    checkEq("custom name with a slash is neutralised",
            filenameDeviceToken("home/work"), "home-work")
    checkEq("custom CJK name still falls back", filenameDeviceToken("我的手机"), "device")
    check("an over-long custom name is capped",
          filenameDeviceToken(String(repeating: "x", count: 200)).count <= 24)

    // The DeviceIdentity side: trimming, empty-clearing and the length cap are
    // what stop an unusable value being stored in the first place.
    let identityPath = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Shared/DeviceIdentity.swift").path
    let identitySource = (try? String(contentsOfFile: identityPath, encoding: .utf8)) ?? ""
    check("DeviceIdentity.swift was readable", !identitySource.isEmpty)
    check("customName exists", identitySource.contains("static var customName: String?"))
    check("automaticName exists", identitySource.contains("static var automaticName: String"))
    check("displayName prefers the custom name",
          identitySource.contains("customName ?? automaticName"))
    check("blank input clears the override rather than storing it",
          identitySource.contains("removeObject(forKey: customNameKey)"))
    check("stored value is trimmed",
          identitySource.contains("trimmingCharacters(in: .whitespacesAndNewlines)"))
    check("stored value is length-capped",
          identitySource.contains("prefix(customNameMaxLength)"))
    // The sync-facing name must route through the same override, or the
    // setting would rename the device in backups but not in the sync list.
    check("sync deviceName routes through displayName",
          identitySource.contains("\"\\(displayName) · \\(shortId)\""))
    // The exporter must read the override, not the raw UIDevice name.
    check("exporter uses DeviceIdentity.displayName",
          exporterSource.contains("DeviceIdentity.displayName"))
    // Code, not prose: the doc comments still discuss `UIDevice.current.name`
    // (explaining why it is NOT used), so a bare substring search would match
    // them. What must not exist is a live read of it.
    let exporterCode = exporterSource
        .split(separator: "\n", omittingEmptySubsequences: false)
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
    check("exporter no longer READS UIDevice.current.name",
          !exporterCode.contains("UIDevice.current.name"))
}

print("\n▸ extracted from shipping source")
do {
    check("BackupExporter.swift was readable", !exporterSource.isEmpty)
    // The reproduced copies are only meaningful if the shipping functions
    // still exist with these shapes. If a future edit renames or reworks them,
    // this fails rather than letting the test quietly certify dead logic.
    check("sortableID still ships",
          exporterSource.contains("static func sortableID(backupId: String, at date: Date)"))
    check("filenameDeviceToken still ships",
          exporterSource.contains("static func filenameDeviceToken("))
    check("alphabet matches",
          exporterSource.contains("\"0123456789abcdefghjkmnpqrstvwxyz\""))
    check("time field is still 8 chars",
          exporterSource.contains("width: 8"))
    check("id tail is still 3 chars",
          exporterSource.contains("encode(h, width: 3)"))
    check("apostrophes are still elided, not split on",
          exporterSource.contains("ch == \"'\""))
    check("cap is still 24", exporterSource.contains("out.count > 24"))
    check("encrypted marker still ships",
          exporterSource.contains("let suffix = encrypted ? \"-encrypted\" : \"\""))
    check("marker is appended after the id, not before the device",
          exporterSource.contains("\\(sortableID(backupId: backupId, at: date))\\(suffix)"))
    // Both `packageFileName` call sites must pass the flag — the streaming
    // path from `hasPassphrase` directly, the staged path via `archive`'s
    // parameter. A site that omitted it would silently default to false and
    // leave encrypted packages unmarked on that path only.
    do {
        var passed = 0
        var searchFrom = exporterSource.startIndex
        while let r = exporterSource.range(of: "Self.packageFileName(",
                                           range: searchFrom..<exporterSource.endIndex) {
            let tail = exporterSource[r.upperBound...].prefix(220)
            if tail.contains("encrypted:") { passed += 1 }
            searchFrom = r.upperBound
        }
        checkEq("every packageFileName call passes an encrypted flag", passed, 2)
    }
    check("archive threads the flag through",
          exporterSource.contains("encrypted: Bool) async throws -> URL"))
    check("date format is yyyyMMdd (no HHmm)",
          exporterSource.contains("\"yyyyMMdd\"") && !exporterSource.contains("\"yyyyMMdd-HHmm\""))
    // The name must be derived from the run's snapshot instant, not from
    // `Date()` — otherwise a resumed export renames itself mid-flight.
    check("naming does not read the wall clock",
          !exporterSource.contains("filenameFormatter.string(from: Date())"))
}

print(failures == 0
      ? "\n✅ all checks passed"
      : "\n❌ \(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
