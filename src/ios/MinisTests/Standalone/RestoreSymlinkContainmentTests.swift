// Regression test for [T-ios-restore-symlink-containment] — a restore
// destination must never resolve outside its category root, including via a
// symlink planted in the root (a parent component or the leaf itself).
//
// Standalone (`swift RestoreSymlinkContainmentTests.swift`) because the
// MinisTests target has a pre-existing compile break — same rationale and
// directory as CJKPaginationThresholdTests / InLoopCompactOrderTests.
//
// `isContained` below is copied VERBATIM from
// BackupImporter+Categories.swift so this pins the shipped logic, not a
// paraphrase of it. If you change one, change both.
//
// Unlike the other standalone tests this one builds REAL directories and REAL
// symlinks under a temp dir: the whole point of the fix is filesystem
// resolution, which a string-only test cannot exercise.

import Foundation

// MARK: - Code under test (verbatim from BackupImporter+Categories.swift)

func isContained(_ url: URL, within root: URL) -> Bool {
    let base = root.standardizedFileURL.resolvingSymlinksInPath().path
    let target = url.standardizedFileURL.resolvingSymlinksInPath().path
    return target == base || target.hasPrefix(base.hasSuffix("/") ? base : base + "/")
}

// MARK: - Harness

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool) {
    if actual == expected {
        print("  ✅ \(label)")
    } else {
        print("  ❌ \(label) — expected \(expected), got \(actual)")
        failures += 1
    }
}

let fm = FileManager.default
// Deliberately under /var/folders (the real temp dir), which on Darwin is
// itself reached through /var -> /private/var. That is what makes case 8 a
// genuine test of the symmetry requirement rather than a synthetic one.
let sandbox = fm.temporaryDirectory
    .appendingPathComponent("restore-containment-\(UUID().uuidString)")
let root = sandbox.appendingPathComponent("category-root")
let outside = sandbox.appendingPathComponent("outside")

try! fm.createDirectory(at: root, withIntermediateDirectories: true)
try! fm.createDirectory(at: outside, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: sandbox) }

// A real file outside the root — the thing an attacker would want to clobber.
let victim = outside.appendingPathComponent("victim.txt")
try! "victim".write(to: victim, atomically: true, encoding: .utf8)

// A normal subdirectory inside the root (the legitimate shape).
let normalDir = root.appendingPathComponent("sessions")
try! fm.createDirectory(at: normalDir, withIntermediateDirectories: true)

// A parent-component symlink INSIDE the root pointing OUT of it.
let escapeDir = root.appendingPathComponent("escape-dir")
try! fm.createSymbolicLink(at: escapeDir, withDestinationURL: outside)

// A leaf symlink INSIDE the root pointing at a file OUT of it.
let escapeLeaf = root.appendingPathComponent("escape-leaf.txt")
try! fm.createSymbolicLink(at: escapeLeaf, withDestinationURL: victim)

print("Path traversal (must still be rejected — no regression)")
// 1. `..` traversal
check("1. ../ escapes root",
      isContained(root.appendingPathComponent("a/../../etc/passwd"), within: root), false)
// 2. absolute path outside
check("2. absolute /etc/passwd",
      isContained(URL(fileURLWithPath: "/etc/passwd"), within: root), false)
// 6. cross-category / sibling root
check("6. sibling directory outside root",
      isContained(outside.appendingPathComponent("f.txt"), within: root), false)
// 7. prefix impostor: /a/b-evil must not count as inside /a/b
let impostor = sandbox.appendingPathComponent("category-root-evil")
try! fm.createDirectory(at: impostor, withIntermediateDirectories: true)
check("7. prefix impostor (root-evil)",
      isContained(impostor.appendingPathComponent("f.txt"), within: root), false)

print("\nSymlink containment (the fix)")
// 3. parent-component symlink inside root pointing outside
check("3. parent symlink escaping root",
      isContained(escapeDir.appendingPathComponent("victim.txt"), within: root), false)
// 4. leaf symlink pointing outside
check("4. leaf symlink escaping root",
      isContained(escapeLeaf, within: root), false)
// A symlink that stays INSIDE the root is still fine.
let innerLink = root.appendingPathComponent("inner-link")
try! fm.createSymbolicLink(at: innerLink, withDestinationURL: normalDir)
check("4b. symlink staying inside root is allowed",
      isContained(innerLink.appendingPathComponent("f.txt"), within: root), true)

print("\nLegitimate restores (must NOT be rejected — the regression that matters)")
// 5. non-existent leaf: the normal case for every file being written.
check("5. non-existent leaf inside root",
      isContained(normalDir.appendingPathComponent("not-created-yet.json"), within: root), true)
check("5b. deep non-existent path inside root",
      isContained(root.appendingPathComponent("a/b/c/d.json"), within: root), true)
check("5c. root itself",
      isContained(root, within: root), true)
check("5d. existing directory inside root",
      isContained(normalDir, within: root), true)

// 8. /var vs /private/var symmetry. On Darwin the temp dir already sits behind
// that system symlink, so resolving only one side would make EVERY case above
// fail. Assert it explicitly with both spellings of the same location.
print("\n/var <-> /private/var symmetry (asymmetric resolution would break all restores)")
let varRoot = URL(fileURLWithPath: "/var/folders")
let privateVarRoot = URL(fileURLWithPath: "/private/var/folders")
// IMPORTANT — what `resolvingSymlinksInPath()` actually does, verified with a
// probe rather than assumed: it resolves only the portion of the path that
// EXISTS. A non-existent trailing component is left exactly as written, so
// `<symlinked-root>/not-yet.txt` does NOT get rewritten to
// `<real-root>/not-yet.txt`. That is precisely the behaviour the production
// call site relies on (case 5: writing a file that is not there yet must be
// allowed), so the assertions below deliberately use paths that EXIST on both
// spellings. Asserting interchangeability for a non-existent leaf would be
// testing something Foundation does not promise — and the real caller always
// passes `containmentRoot(for:)`, i.e. both sides come from the same API and
// therefore the same spelling.
if fm.fileExists(atPath: varRoot.path), fm.fileExists(atPath: privateVarRoot.path) {
    check("8. /var/... root and /private/var/... root are the same place",
          isContained(varRoot, within: privateVarRoot), true)
    check("8b. and symmetrically",
          isContained(privateVarRoot, within: varRoot), true)
    // An existing path reached through the /var spelling is inside the
    // /private/var root. This is the case that fails outright if only one
    // side of isContained() is resolved.
    check("8c. existing /var/folders/... target inside /private/var/folders root",
          isContained(fm.temporaryDirectory, within: privateVarRoot), true)
} else {
    print("  ⏭  skipped — no /var -> /private/var symlink on this host")
}
// Same property, proven without depending on any system layout: a symlinked
// root and the real root it points at must be interchangeable for paths that
// exist.
let linkedRoot = sandbox.appendingPathComponent("linked-root")
try! fm.createSymbolicLink(at: linkedRoot, withDestinationURL: root)
check("8d. symlinked root accepts an existing target under the real root",
      isContained(normalDir, within: linkedRoot), true)
check("8e. real root accepts an existing target reached via the symlinked root",
      isContained(linkedRoot.appendingPathComponent("sessions"), within: root), true)

print(failures == 0 ? "\n✅ all checks passed" : "\n❌ \(failures) check(s) failed")
exit(failures == 0 ? 0 : 1)
