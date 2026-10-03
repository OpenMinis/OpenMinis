// Tests for [T-offload-readback-loop] — GH#343. Content the offloader wrote,
// fetched back by file_read, must not be offloaded again.
//
// Reproduced on an iPhone 11 before the fix: context oscillated
// 123K→108K→124K→109K chars over four rounds while the number of full payloads
// in context stayed at 6, one new offload file per lap and no net progress.
//
// Standalone (`swift OffloadReadbackLoopTests.swift`) like its neighbours:
// deps/libs/libish_emu.a is device-only arm64, so the app cannot link for a
// simulator and an XCTest bundle has nowhere to run.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced rules

/// Mirrors AIChatViewModel.isOffloadStorePath.
func isOffloadStorePath(_ path: String) -> Bool {
    let p = path.trimmingCharacters(in: .whitespacesAndNewlines)
    return p.hasPrefix("/var/minis/offloads/") || p.hasPrefix("minis://offloads/")
}

/// Mirrors the tagging decision at the tool-result assembly point.
func tagFor(tool: String, args: [String: Any]) -> Bool {
    guard tool == "file_read", let path = args["path"] as? String else { return false }
    return isOffloadStorePath(path)
}

struct Part {
    var content: String
    var imgBytes: Int = 0
    var isOffloadReadback: Bool = false
}

struct ScanResult {
    var candidates: [Int] = []
    var skippedAlreadyOffloaded = 0
    var skippedOffloadReadback = 0
    var skippedTooSmall = 0
}

/// Mirrors PATH A — the history candidate scanner, post-fix ordering.
func scan(_ parts: [Part]) -> ScanResult {
    var r = ScanResult()
    for (i, p) in parts.enumerated() {
        if p.isOffloadReadback { r.skippedOffloadReadback += 1; continue }
        if p.content.hasPrefix("[CONTEXT OFFLOADED]") { r.skippedAlreadyOffloaded += 1; continue }
        guard p.content.count > 500 || p.imgBytes > 1024 else { r.skippedTooSmall += 1; continue }
        r.candidates.append(i)
    }
    return r
}

/// Mirrors PATH B — the >15K truncation branch. Returns whether a NEW file
/// would be written.
func wouldWriteCopy(outputChars: Int, isOffloadReadback: Bool, cap: Int = 15_000) -> Bool {
    guard outputChars > cap else { return false }
    return !isOffloadReadback
}

let big = String(repeating: "MOCK-BIG-PAYLOAD line xxxxxxxx\n", count: 800)   // ~24K chars

print("\n[1] The path predicate")
do {
    check("linux offload path", isOffloadStorePath("/var/minis/offloads/tools/file_read_c-1.txt"))
    check("minis:// offload path", isOffloadStorePath("minis://offloads/file_read_x.txt"))
    check("surrounding whitespace tolerated", isOffloadStorePath("  /var/minis/offloads/a.txt "))
    // Deliberately narrower than isPersistentMinisPath: these are real sources
    // whose large results SHOULD still be offloadable.
    check("workspace is NOT an offload path", isOffloadStorePath("/var/minis/workspace/notes.md"), false)
    check("browser is NOT an offload path", isOffloadStorePath("/var/minis/browser/s/shot.jpg"), false)
    check("attachments is NOT an offload path", isOffloadStorePath("/var/minis/attachments/a.png"), false)
    check("/tmp is NOT an offload path", isOffloadStorePath("/tmp/gh343_big.txt"), false)
    // A path merely CONTAINING the word must not match.
    check("a lookalike path does not match", isOffloadStorePath("/var/minis/workspace/offloads/x"), false)
}

print("\n[2] Tagging happens on the argument, not the content")
do {
    check("file_read of an offload file is tagged",
          tagFor(tool: "file_read", args: ["path": "/var/minis/offloads/tools/a.txt"]))
    check("file_read of a normal file is not",
          tagFor(tool: "file_read", args: ["path": "/tmp/gh343_big.txt"]), false)
    // Other tools reaching that directory produce genuinely new output.
    check("shell_execute is never tagged",
          tagFor(tool: "shell_execute", args: ["path": "/var/minis/offloads/a.txt"]), false)
    check("a missing path argument is not tagged",
          tagFor(tool: "file_read", args: [:]), false)
    // The content itself carries file_read's own header and the original
    // payload — the very thing a prefix check cannot tell apart.
    let readback = Part(content: "[/var/minis/offloads/tools/a.txt | 15220 bytes | 258 lines]\n" + big,
                        isOffloadReadback: true)
    check("read-back content does NOT start with the stub marker",
          readback.content.hasPrefix("[CONTEXT OFFLOADED]"), false)
    check("…which is exactly why the flag is needed", readback.isOffloadReadback)
}

print("\n[3] PATH A — the history scanner skips a read-back")
do {
    let parts = [
        Part(content: "[CONTEXT OFFLOADED] Content (~4406 tokens) saved to: /var/minis/offloads/tools/a.txt"),
        Part(content: "[/var/minis/offloads/tools/a.txt | …]\n" + big, isOffloadReadback: true),
        Part(content: big),                    // genuine new material
        Part(content: "ok"),                   // too small
    ]
    let r = scan(parts)
    checkEq("only the genuine part is a candidate", r.candidates, [2])
    checkEq("the stub is counted as already-offloaded", r.skippedAlreadyOffloaded, 1)
    checkEq("the read-back has its OWN counter", r.skippedOffloadReadback, 1)
    checkEq("small parts still counted separately", r.skippedTooSmall, 1)

    // The pre-fix behaviour, for contrast: without the flag the read-back
    // becomes a candidate and the loop starts.
    var unflagged = parts
    unflagged[1].isOffloadReadback = false
    let before = scan(unflagged)
    checkEq("PRE-FIX: the read-back would have been offloaded again",
            before.candidates, [1, 2])
    checkEq("PRE-FIX: nothing was counted as a read-back", before.skippedOffloadReadback, 0)
}

print("\n[4] PATH B — the truncation branch writes no second copy")
do {
    check("a large NORMAL result is copied to disk",
          wouldWriteCopy(outputChars: 70_879, isOffloadReadback: false))
    check("a large READ-BACK is not",
          wouldWriteCopy(outputChars: 70_879, isOffloadReadback: true), false)
    check("a small read-back is untouched either way",
          wouldWriteCopy(outputChars: 200, isOffloadReadback: true), false)
    check("a small normal result is untouched",
          wouldWriteCopy(outputChars: 200, isOffloadReadback: false), false)
}

print("\n[5] The loop terminates")
do {
    // Simulate the observed cycle: each lap the model reads the newest stub.
    // Pre-fix, every lap appends a candidate and writes a file. Post-fix the
    // read-back is inert, so the count stops growing.
    func lapsUntilStable(fixed: Bool, laps: Int = 6) -> Int {
        var parts = [Part(content: big)]          // the original large result
        var filesWritten = 0
        for _ in 0..<laps {
            let r = scan(parts)
            for _ in r.candidates { filesWritten += 1 }
            // Everything offloaded becomes a stub…
            parts = r.candidates.map { _ in
                Part(content: "[CONTEXT OFFLOADED] Content saved to: /var/minis/offloads/tools/x.txt")
            } + parts.filter { $0.isOffloadReadback || $0.content.count <= 500 }
            // …and the model reads the newest one back.
            parts.append(Part(content: "[/var/minis/offloads/tools/x.txt | …]\n" + big,
                              isOffloadReadback: fixed))
        }
        return filesWritten
    }
    let pre = lapsUntilStable(fixed: false)
    let post = lapsUntilStable(fixed: true)
    check("PRE-FIX: a file is written on every lap", pre >= 6)
    checkEq("POST-FIX: only the original is ever written", post, 1)
    check("the fix strictly reduces writes", post < pre)
}

print("\n[6] Shipping sources match these assumptions")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let agentProv = source("Providers/AgentProvider.swift")
let offl = source("Agent/Chat/AIChatViewModel+Offloading.swift")
let conc = source("Agent/Chat/AIChatViewModel+ConcurrentTools.swift")
let pers = source("Agent/Chat/AIChatViewModel+Persistence.swift")
let budget = source("Agent/Chat/AIChatViewModel+RequestBudget.swift")

if agentProv.isEmpty || offl.isEmpty { print("  ⏭  sources not readable") } else {
    check("the enum carries the tag, defaulted false",
          agentProv.contains("isOffloadReadback: Bool = false)"))
    check("the predicate exists and is offloads-only",
          offl.contains("nonisolated static func isOffloadStorePath(")
          && offl.contains("p.hasPrefix(\"/var/minis/offloads/\") || p.hasPrefix(\"minis://offloads/\")"))
    check("the tag is derived from the file_read ARGUMENT",
          conc.contains("guard tu.name == \"file_read\",")
          && conc.contains("return AIChatViewModel.isOffloadStorePath(path)"))
    // PATH A
    check("the scanner skips a tagged part",
          offl.contains("if isReadback {") && offl.contains("skippedOffloadReadback += 1"))
    check("…before the prefix test, not after",
          offl.range(of: "skippedOffloadReadback += 1")!.lowerBound
          < offl.range(of: "skippedAlreadyOffloaded += 1")!.lowerBound)
    check("the counter is in the log line",
          offl.contains("\\(skippedOffloadReadback) offload readback"))
    // PATH B
    check("the truncation branch is gated on the tag",
          conc.contains("} else if toolOutput.count > maxToolResultLength && isOffloadReadback {"))
    check("…and writes no file in that branch",
          !conc.range(of: "&& isOffloadReadback {").map {
              String(conc[$0.upperBound...]).prefix(700).contains("offloadToolOutput(")
          }!)
    // The flag must survive every part rebuild, or a reminder launders it.
    checkEq("every rebuild site carries the flag forward",
            (pers + budget + offl).components(separatedBy: "isOffloadReadback: isReadback").count - 1, 4)
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
