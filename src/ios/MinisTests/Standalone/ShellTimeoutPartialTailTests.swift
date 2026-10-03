// Tests for [T-ish-shell-timeout-preserve-output] (commit 3092a9c4b) — the
// edge the original suite (ShellTimeoutPreserveOutputTests) does not cover:
// what survives when a timed-out command printed MORE than the mirror cap.
//
// The commit's stated goal is that the model can "read the error that preceded
// the hang". The mirror, however, keeps the FIRST kMaxPartialOutputChars and
// drops every later line (`guard partialChars < cap else { truncated = true;
// return }`) — i.e. it throws away exactly the tail that holds that error —
// and then labels the loss "[Earlier output ... was dropped]", which is the
// opposite of what happened. The downstream head+tail truncation in
// AIChatViewModel+ISHCommand cannot recover it: its "tail" is the tail of the
// already-clipped head.
//
// Invariant pinned: after a timeout, the LAST line the command printed before
// it hung is always in the returned output, and the truncation notice names
// the part that was actually dropped.
//
// Standalone: `swift ShellTimeoutPartialTailTests.swift`.

import Foundation

var failures = 0
func check(_ label: String, _ ok: Bool) {
    if ok { print("  ✅ \(label)") } else { print("  ❌ \(label)"); failures += 1 }
}

let root = FileManager.default.currentDirectoryPath
func source(_ rel: String) -> String {
    var dir = URL(fileURLWithPath: root)
    for _ in 0..<7 {
        let p = dir.appendingPathComponent("src/ios/" + rel).path
        if let s = try? String(contentsOfFile: p, encoding: .utf8) { return s }
        dir = dir.deletingLastPathComponent()
    }
    print("  ⚠️  could not locate \(rel)")
    return ""
}

let coord = source("Agent/ISH/ISHExecutionCoordinator.swift")

// Read the cap from the shipping source so the model tracks it.
let cap: Int = {
    guard let r = coord.range(of: "static let kMaxPartialOutputChars = ") else { return 256_000 }
    let digits = coord[r.upperBound...].prefix { $0.isNumber || $0 == "_" }.filter { $0 != "_" }
    return Int(String(digits)) ?? 256_000
}()

// MARK: - Model of the shipped mirror (head-keeping)

struct HeadMirror {
    var lines: [String] = []
    var chars = 0
    var truncated = false
    mutating func record(_ line: String) {
        guard chars < cap else { truncated = true; return }
        lines.append(line)
        chars += line.count + 1
    }
    func output() -> String {
        var out = lines.joined(separator: "\n")
        if truncated { out += "\n\n[Earlier output beyond \(cap) chars was dropped]" }
        return out
    }
}

// A build that logs a lot, then prints its real error, then hangs.
let noisy = (0..<6000).map { "compiling unit \($0) ........................................" }
let fatal = "error: linker command failed — undefined symbol _foo"
var shipped = HeadMirror()
for l in noisy { shipped.record(l) }
shipped.record(fatal)
let shippedOut = shipped.output()

print("\n▶️  model of the shipped mirror, command printed > cap then hung (cap=\(cap))")
check("precondition: the run actually exceeded the cap", shipped.truncated)
check("the shipped mirror loses the last line before the hang (demonstrates the bug)",
      !shippedOut.contains(fatal))
check("…while its notice claims EARLIER output was dropped",
      shippedOut.contains("[Earlier output"))

// MARK: - Proposed behaviour: keep the tail

struct TailMirror {
    var lines: [String] = []
    var chars = 0
    var dropped = false
    mutating func record(_ line: String) {
        lines.append(line); chars += line.count + 1
        while chars > cap, lines.count > 1 {
            chars -= lines.removeFirst().count + 1
            dropped = true
        }
    }
    func output() -> String {
        (dropped ? "[Earlier output beyond \(cap) chars was dropped]\n\n" : "") + lines.joined(separator: "\n")
    }
}
var tail = TailMirror()
for l in noisy { tail.record(l) }
tail.record(fatal)
print("\n▶️  tail-keeping mirror (proposed fix)")
check("the error printed right before the hang survives", tail.output().contains(fatal))
check("the mirror stays bounded", tail.chars <= cap)
check("the notice precedes the kept output and matches what was dropped",
      tail.output().hasPrefix("[Earlier output"))

// MARK: - Source invariant on the shipping code

print("\n▶️  source invariants (ISHExecutionCoordinator)")
if !coord.isEmpty {
    // Locate recordPartial's body.
    let recordSrc: String = {
        guard let r = coord.range(of: "func recordPartial(_ line: String) {") else { return "" }
        return String(coord[r.lowerBound...].prefix(900))
    }()
    check("recordPartial exists", !recordSrc.isEmpty)
    let dropsNewLines = recordSrc.contains("guard partialChars < Self.kMaxPartialOutputChars else {")
        && recordSrc.contains("return\n")
    check("recordPartial keeps the TAIL once over the cap (BUG if this fails: newest lines are discarded)",
          !dropsNewLines)
}

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
