// Tests for [T-ish-shell-timeout-preserve-output] — a timed-out shell_execute
// must hand back what the command PRINTED, not just the fact that it died.
//
// The bug: `ISHExecutionCoordinator.runCommand`'s timeout work item resumed
// the continuation with a hardcoded string:
//
//     ISHCommandResult(output: "(command timed out after \(N)s)", exitCode: -1)
//
// Everything the command had already written to stdout/stderr was replaced by
// that one line. For the model this is the worst possible answer: a build that
// logged 200 lines and then hung is indistinguishable from one that hung
// instantly, so it cannot tell how far the command got, read the error that
// preceded the hang, or judge whether re-running would help.
//
// Why the fix mirrors lines rather than reading the executor's buffer: the
// executor's `ISHShellExecutionResult` is assembled by the completion
// callback, which by definition never fires for a command we killed. The line
// callback, however, has already delivered every one of those lines. Note the
// SYNC path (`ISHShellExecutor.executeCommandSync`) already did the right
// thing — it recovers `timedOutCtx.result` — so preserving output is the
// established intent, and only the async coordinator path had regressed.
//
// Standalone (`swift ShellTimeoutPreserveOutputTests.swift`) like its
// neighbours: deps/libs/libish_emu.a is device-only arm64, so the app cannot
// link for a simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func ck(_ l: String, _ ok: Bool) {
    if ok { print("  ✅ \(l)") } else { print("  ❌ \(l)"); failures += 1 }
}
func ckEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Model of the coordinator's partial-output mirror

let kMaxPartialOutputChars = 256_000

/// Mirrors the accumulator + timeout assembly added to runCommand.
final class TimeoutModel {
    private let lock = NSLock()
    private var lines: [String] = []
    private var chars = 0
    private var truncated = false

    /// Mirrors `recordPartial` on the line callback.
    func record(_ line: String) {
        lock.lock(); defer { lock.unlock() }
        guard chars < kMaxPartialOutputChars else { truncated = true; return }
        lines.append(line)
        chars += line.count + 1
    }

    func snapshot() -> (text: String, truncated: Bool) {
        lock.lock(); defer { lock.unlock() }
        return (lines.joined(separator: "\n"), truncated)
    }

    /// Mirrors the timeout branch's output assembly.
    func timedOutOutput(after seconds: Int) -> String {
        let snap = snapshot()
        let notice = "[Command timed out after \(seconds)s"
            + (snap.text.isEmpty
                ? " with no output captured]"
                : " — above is partial output captured before the timeout]")
        if snap.text.isEmpty { return notice }
        var out = snap.text
        if snap.truncated {
            out += "\n\n[Earlier output beyond \(kMaxPartialOutputChars) chars was dropped]"
        }
        return out + "\n\n" + notice
    }
}

// MARK: - 1. The reported bug

print("\n▶️  the old behaviour discarded everything the command printed")
let old = "(command timed out after 30s)"
ck("old output contains no trace of the command's own output",
   !old.contains("Compiling") && !old.contains("error"))

print("\n▶️  partial output is preserved, with the notice appended")
let m = TimeoutModel()
for l in ["Compiling module A", "Compiling module B", "error: missing symbol foo"] {
    m.record(l)
}
let out = m.timedOutOutput(after: 30)
ck("the first line survives", out.contains("Compiling module A"))
ck("the middle line survives", out.contains("Compiling module B"))
ck("the error the model actually needs survives", out.contains("error: missing symbol foo"))
ck("the timeout is still reported", out.contains("timed out after 30s"))

print("\n▶️  the notice comes AFTER the output, never before")
// The model reads top-to-bottom; the last thing it should see is why the
// transcript stops. A leading notice also invites it to stop reading there.
let idxErr = out.range(of: "error: missing symbol foo")!.lowerBound
let idxNotice = out.range(of: "[Command timed out")!.lowerBound
ck("output precedes the notice", idxErr < idxNotice)
ck("the notice is the final line", out.hasSuffix("]"))

print("\n▶️  line order and content are preserved verbatim")
let m2 = TimeoutModel()
["one", "two", "three"].forEach(m2.record)
ck("lines keep arrival order",
   m2.snapshot().text == "one\ntwo\nthree")

// MARK: - 2. The empty case reads correctly

print("\n▶️  a command that printed nothing says so, and says it once")
let empty = TimeoutModel()
let emptyOut = empty.timedOutOutput(after: 5)
ckEq("exactly the no-output notice", emptyOut,
     "[Command timed out after 5s with no output captured]")
ck("no dangling separator", !emptyOut.hasPrefix("\n"))
// The phrasing must not claim output is above when there is none.
ck("does not promise partial output that isn't there",
   !emptyOut.contains("above is partial output"))

// MARK: - 3. The cap

print("\n▶️  the mirror is bounded")
let flood = TimeoutModel()
let chunk = String(repeating: "x", count: 1000)
for _ in 0..<1000 { flood.record(chunk) }   // ~1MB offered
let snap = flood.snapshot()
ck("the mirror stopped growing at the cap", snap.text.count <= kMaxPartialOutputChars + 1001)
ck("truncation is recorded", snap.truncated)
let floodOut = flood.timedOutOutput(after: 60)
ck("the user is told output was dropped", floodOut.contains("was dropped"))
ck("and the timeout notice still lands last", floodOut.hasSuffix("]"))

print("\n▶️  an ordinary command never trips the cap")
let normal = TimeoutModel()
for i in 0..<200 { normal.record("line \(i) of build output") }
ck("not truncated", !normal.snapshot().truncated)
ck("no dropped-output notice", !normal.timedOutOutput(after: 30).contains("was dropped"))

// MARK: - 4. Thread safety
//
// Lines arrive on the MAIN queue while the timeout body runs on `killQueue`,
// so the accumulator is genuinely raced. Without the lock this loses lines or
// crashes on concurrent array append.

print("\n▶️  concurrent writers do not lose or corrupt lines")
let raced = TimeoutModel()
let group = DispatchGroup()
for w in 0..<8 {
    DispatchQueue.global().async(group: group) {
        for i in 0..<250 { raced.record("w\(w)-\(i)") }
    }
}
group.wait()
let racedLines = raced.snapshot().text.components(separatedBy: "\n")
ckEq("every line from every writer is present", racedLines.count, 8 * 250)
ck("no empty/corrupted entries", racedLines.allSatisfy { $0.hasPrefix("w") })

print("\n▶️  a snapshot taken while writing still returns a well-formed string")
let live = TimeoutModel()
let g2 = DispatchGroup()
DispatchQueue.global().async(group: g2) {
    for i in 0..<2000 { live.record("l\(i)") }
}
var snapshots: [String] = []
for _ in 0..<50 { snapshots.append(live.timedOutOutput(after: 10)) }
g2.wait()
ck("every concurrent snapshot ends with the notice",
   snapshots.allSatisfy { $0.hasSuffix("]") })

// MARK: - 5. Source invariants

print("\n▶️  source invariants")

func read(_ p: String) -> String? { try? String(contentsOfFile: p, encoding: .utf8) }
guard let coord = read("../../Agent/ISH/ISHExecutionCoordinator.swift") else {
    print("  ❌ could not read ISHExecutionCoordinator.swift"); failures += 1; exit(1)
}

// The regression itself: the bare sentinel must be gone from the resume.
ck("the output-discarding resume is gone",
   !coord.contains("ISHCommandResult(output: \"(command timed out after \\(Int(effectiveTimeout))s)\""))
ck("the timeout resumes with the assembled output",
   coord.contains("continuation.resume(returning: ISHCommandResult(output: timedOutput, exitCode: -1))"))

// The mirror must be fed from the line callback, or it is always empty.
ck("the line callback feeds the mirror", coord.contains("recordPartial(line)"))
ck("and still forwards the line to the caller",
   coord.range(of: "recordPartial(line)\n                lineCallback(line)") != nil)

// Locked, because main queue and killQueue race.
ck("the accumulator is lock-guarded", coord.contains("let partialLock = NSLock()"))
ck("the cap exists", coord.contains("static let kMaxPartialOutputChars"))

// Ordering: the notice must be appended to the output, not prepended.
ck("notice is appended after the output",
   coord.contains("timedOutput += \"\\n\\n\" + notice"))

// The pre-start failure branch has no output to preserve and must stay as-is.
ck("the pid<0 branch is untouched", coord.contains("errorMsg = \"Command timed out\""))

// The kill must still happen before finalize, or the pipes close before the
// last writes are readable — the comment this fix depends on.
let killIdx = coord.range(of: "ISHShellExecutor.killProcessGroup(pid)")?.lowerBound
let finalIdx = coord.range(of: "ISHShellExecutor.finalizeTimedOutPid(pid)")?.lowerBound
if let killIdx, let finalIdx {
    ck("kill still precedes finalize", killIdx < finalIdx)
} else {
    ck("found both kill and finalize", false)
}

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All shell-timeout partial-output tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
