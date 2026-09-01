// Regression test for [T-ios-backup-zip-canonical-containment] — a ZIP entry
// must never resolve outside the extraction root, even when a component of the
// path is a symlink.
//
// Standalone (`swift BackupZipContainmentTests.swift`) because the MinisTests
// target has a pre-existing compile break — same rationale and directory as
// CJKPaginationThresholdTests / RestoreSymlinkContainmentTests.
//
// Both the OLD and the NEW guard are reproduced below so this test can prove
// the fix actually changes behaviour: `oldGuard` is copied verbatim from
// BackupZipExtractor.swift before the change, `newGuard` matches it after. A
// test that only exercises the new code cannot show the bug was real.
//
// The interesting case is deliberately NOT a `../` string — the old guard
// already rejected those. It is a path with no `..` at all whose parent
// component happens to be a symlink pointing out of the root. That passes a
// purely lexical check and still escapes, which is why containment has to be
// resolved against the filesystem.

import Foundation

// MARK: - The guard, before and after

/// Verbatim copy of the pre-fix logic.
func oldGuard(_ name: String, destination: URL) -> URL? {
    let components = name.split(separator: "/").map(String.init)
    guard !name.hasPrefix("/"), !components.contains("..") else { return nil }
    return components.reduce(destination) { $0.appendingPathComponent($1) }
}

/// Verbatim copy of the post-fix logic (see BackupZipExtractor.safeDestination).
func newGuard(_ name: String, destination: URL) -> URL? {
    let components = name.split(separator: "/").map(String.init)
    guard !name.hasPrefix("/"), !components.contains("..") else { return nil }
    let out = components.reduce(destination) { $0.appendingPathComponent($1) }

    let root = destination.standardizedFileURL.resolvingSymlinksInPath().path
    // Resolve the deepest EXISTING ancestor: the leaf itself normally does not
    // exist yet, and resolvingSymlinksInPath() leaves a non-existent trailing
    // component untouched — so checking only the leaf would miss a symlinked
    // parent, which is exactly the attack.
    var probe = out
    while !FileManager.default.fileExists(atPath: probe.path) {
        let parent = probe.deletingLastPathComponent()
        if parent.path == probe.path { break }
        probe = parent
    }
    let real = probe.standardizedFileURL.resolvingSymlinksInPath().path
    guard real == root || real.hasPrefix(root + "/") else { return nil }
    return out
}

// MARK: - Harness

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

let fm = FileManager.default
let base = fm.temporaryDirectory.appendingPathComponent("zipcontain-\(UUID().uuidString)")
let root = base.appendingPathComponent("root")
let outside = base.appendingPathComponent("outside")
try! fm.createDirectory(at: root, withIntermediateDirectories: true)
try! fm.createDirectory(at: outside, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: base) }

let victim = outside.appendingPathComponent("victim.txt")
try! "SECRET".write(to: victim, atomically: true, encoding: .utf8)

// A symlink INSIDE the extraction root pointing OUT of it. In a real archive
// this is what an earlier entry would have created.
try! fm.createSymbolicLink(at: root.appendingPathComponent("esc"), withDestinationURL: outside)
// A directory symlink that stays inside — must still be allowed.
try! fm.createDirectory(at: root.appendingPathComponent("real"), withIntermediateDirectories: true)
try! fm.createSymbolicLink(at: root.appendingPathComponent("inner"),
                           withDestinationURL: root.appendingPathComponent("real"))

print("Lexical traversal — both guards must reject (no regression)")
for (label, name) in [("absolute path", "/etc/passwd"),
                      ("../ at front", "../evil.txt"),
                      ("../ nested", "a/../../evil.txt"),
                      (".. only component", "..")] {
    check("old rejects \(label)", oldGuard(name, destination: root) == nil, true)
    check("new rejects \(label)", newGuard(name, destination: root) == nil, true)
}

print("\nSymlinked parent — THE BUG: old allows, new rejects")
let attack = "esc/victim.txt"          // contains no ".." and is not absolute
check("old ALLOWS the escape (proves the bug was real)",
      oldGuard(attack, destination: root) != nil, true)
check("new rejects the escape",
      newGuard(attack, destination: root) == nil, true)

// Show it is a genuine escape, not a theoretical one.
if let out = oldGuard(attack, destination: root) {
    let realRoot = root.resolvingSymlinksInPath().path
    let resolved = out.resolvingSymlinksInPath().path
    check("old path really resolves outside the root",
          !resolved.hasPrefix(realRoot + "/"), true)
}

print("\nLegitimate package contents — new guard must NOT reject")
for (label, name) in [("wrapper + nested file", "minisbak-UUID/data/messages.jsonl"),
                      ("blob path", "minisbak-UUID/blobs/ab/abcdef0123.blob"),
                      ("unicode filename", "minisbak-UUID/文件/résumé.md"),
                      ("empty directory entry", "minisbak-UUID/blobs/"),
                      ("deep non-existent leaf", "minisbak-UUID/a/b/c/d.json"),
                      ("symlink staying inside root", "inner/ok.txt"),
                      ("dot in filename", "minisbak-UUID/data/a.b.c.jsonl"),
                      ("space in filename", "minisbak-UUID/my file.txt")] {
    check("new allows \(label)", newGuard(name, destination: root) != nil, true)
}

print("\nDarwin specifics — these are filenames, not traversal, and must be allowed")
// On Darwin '\' is an ordinary character and fullwidth dots are not '.', so
// these stay inside the root. Rejecting them would break legitimate archives
// that happen to contain such names.
for (label, name) in [("backslashes", #"a\b\c.txt"#),
                      ("fullwidth dots", "\u{FF0E}\u{FF0E}/x.txt"),
                      ("three dots", "a/.../x.txt")] {
    guard let out = newGuard(name, destination: root) else {
        print("  ❌ new rejected \(label) — would break legitimate archives"); failures += 1; continue
    }
    let realRoot = root.standardizedFileURL.resolvingSymlinksInPath().path
    var probe = out
    while !fm.fileExists(atPath: probe.path) {
        let p = probe.deletingLastPathComponent()
        if p.path == probe.path { break }
        probe = p
    }
    check("\(label) stays inside root",
          probe.resolvingSymlinksInPath().path.hasPrefix(realRoot), true)
}

print("\nPrefix impostor — a sibling whose name starts with the root's name")
let impostor = base.appendingPathComponent("root-evil")
try! fm.createDirectory(at: impostor, withIntermediateDirectories: true)
try! fm.createSymbolicLink(at: root.appendingPathComponent("imp"), withDestinationURL: impostor)
check("new rejects root-evil via symlink", newGuard("imp/x.txt", destination: root) == nil, true)

print(failures == 0 ? "\n✅ all checks passed" : "\n❌ \(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
