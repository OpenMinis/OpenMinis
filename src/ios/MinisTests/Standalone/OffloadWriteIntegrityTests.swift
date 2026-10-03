// Tests for [T-truncated-args-visibility #119] / [T-offload-readback-loop]
// — backlog item T17, iOS half.
//
// Pins e8665bd4a (refuse truncated writes, surface repaired args).
// Issues #374 (CONTEXT OFFLOADED placeholder written to disk), #343
// (re-offload loop), #119 (half file on disk), #223.
//
// Invariants:
//   * a file_write / file_edit whose argument stream was cut and "repaired"
//     by the truncation strategy is REFUSED — nothing reaches disk, the model
//     is told the call did not run; read/shell tools still run repaired but
//     get a <system-reminder> naming the repair;
//   * only the `truncation+` strategy gates this; type coercion and fuzzy
//     field-name repairs are not data-loss signals;
//   * content fetched back from /var/minis/offloads is never offloaded again;
//   * the byte count reported for a write is the byte count written.
//
// Ports:
//   repairToolArgs strategies 1–3  — AIChatViewModel+ToolPreflight.swift ~L51
//   the refusal gate                — AIChatViewModel+ConcurrentTools.swift ~L210
//   the readback scanner            — AIChatViewModel+Offloading.swift ~L470
//
// Standalone (`swift OffloadWriteIntegrityTests.swift`) like its neighbours.
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}
func gap(_ l: String, _ closed: Bool) {
    if closed { print("  ✅ \(l) (gap closed)") } else { print("  ⚠️ KNOWN GAP: \(l)") }
}
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

// MARK: - Port: repairToolArgs

struct ToolDef { let name: String; let required: [String] }
struct RepairOutcome { var args: [String: Any]; var repairs: [String] }

func repairToolArgs(name: String, args: [String: Any], rawTail: String?, tools: [ToolDef]) -> RepairOutcome {
    guard let toolDef = tools.first(where: { $0.name == name }) else { return RepairOutcome(args: args, repairs: []) }
    var working = args
    var repairs: [String] = []
    // Strategy 1: truncation repair.
    if working.isEmpty, let tail = rawTail?.trimmingCharacters(in: .whitespacesAndNewlines), !tail.isEmpty {
        let suffixes: [String] = ["", "\"", "\"}", "\"]}", "}", "}}", "]}", "]}}", "]", "]]"]
        for suffix in suffixes {
            let candidate = tail + suffix
            guard let data = candidate.data(using: .utf8),
                  let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
            working = parsed
            repairs.append("truncation+\(suffix.isEmpty ? "noop" : suffix)")
            break
        }
    }
    // Strategy 2: type coercion on required fields.
    for field in toolDef.required {
        guard let raw = working[field] else { continue }
        if raw is String { continue }
        if raw is NSNull { working.removeValue(forKey: field); repairs.append("null-strip:\(field)"); continue }
        if raw is [Any] || raw is [String: Any] { continue }
        if let n = raw as? NSNumber {
            working[field] = CFGetTypeID(n) == CFBooleanGetTypeID() ? (n.boolValue ? "true" : "false") : n.stringValue
            repairs.append("coerce:\(field)")
        }
    }
    // Strategy 3: fuzzy field-name match (distance ≤ 1).
    func lev(_ a: String, _ b: String) -> Int {
        let a = Array(a), b = Array(b)
        var d = Array(0...b.count)
        for i in 1...max(a.count, 1) where i <= a.count {
            var prev = d[0]; d[0] = i
            for j in 1...max(b.count, 1) where j <= b.count {
                let tmp = d[j]
                d[j] = min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] == b[j - 1] ? 0 : 1))
                prev = tmp
            }
        }
        return d[b.count]
    }
    for field in toolDef.required where working[field] == nil {
        if let sib = working.keys.first(where: { $0 != field && lev($0, field) <= 1 }) {
            working[field] = working.removeValue(forKey: sib)
            repairs.append("fuzzy:\(sib)->\(field)")
        }
    }
    return RepairOutcome(args: working, repairs: repairs)
}

// MARK: - Port: the execution gate (ConcurrentTools ~L189–262, ~L1118)

let writeTools: Set<String> = ["file_write", "file_edit"]

enum Exec: Equatable {
    case refused(modelMessage: String, uiStatus: String)
    case ran(args: [String: String], reminderAppended: Bool, uiStatus: String)
}

/// One tool call through preflight-repair → gate → execution, with the
/// executor stubbed to "success". `parsedArgs` is what the strict parser got
/// (empty on a cut stream), `raw` is the joined delta ring.
func executeToolCall(name: String, parsedArgs: [String: Any], raw: String, tools: [ToolDef]) -> Exec {
    let toolDef = tools.first { $0.name == name }
    let needsRepair: Bool = {
        guard let toolDef else { return false }
        for f in toolDef.required {
            guard let v = parsedArgs[f] else { return true }
            if v is NSNull { return true }
            if let s = v as? String, s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
            if !(v is String) && !(v is [Any]) && !(v is [String: Any]) { return true }
        }
        return false
    }()
    var toolArgs = parsedArgs
    var truncationRepairTag: String? = nil
    if needsRepair {
        let outcome = repairToolArgs(name: name, args: parsedArgs, rawTail: raw, tools: tools)
        if !outcome.repairs.isEmpty {
            toolArgs = outcome.args
            truncationRepairTag = outcome.repairs.first { $0.hasPrefix("truncation+") }
        }
    }
    if let tag = truncationRepairTag, writeTools.contains(name) {
        let path = (toolArgs["path"] as? String) ?? (toolArgs["file_path"] as? String) ?? ""
        let modelMessage = "Error: This call was NOT executed. Its argument stream was truncated in transit (repair strategy: \(tag)), so the `content` your client sent was cut short and would have written an incomplete file\(path.isEmpty ? "" : " to \(path)"). Nothing was written to disk — the target file is unchanged."
        return .refused(modelMessage: modelMessage, uiStatus: "failed: Blocked: arguments were truncated in transit")
    }
    let stringArgs = toolArgs.compactMapValues { $0 as? String }
    let uiStatus = truncationRepairTag != nil ? "failed: Arguments truncated in transit — result may be incomplete" : "success"
    return .ran(args: stringArgs, reminderAppended: truncationRepairTag != nil, uiStatus: uiStatus)
}

let tools = [ToolDef(name: "file_write", required: ["path", "content"]),
             ToolDef(name: "file_edit", required: ["path", "old_string", "new_string"]),
             ToolDef(name: "file_read", required: ["path"]),
             ToolDef(name: "shell_execute", required: ["tool_title", "command"])]

// MARK: - Port: offload store predicate + scanner

func isOffloadStorePath(_ path: String) -> Bool {
    let p = path.trimmingCharacters(in: .whitespacesAndNewlines)
    return p.hasPrefix("/var/minis/offloads/") || p.hasPrefix("minis://offloads/")
}
struct Part { var content: String; var isOffloadReadback = false }
func offloadCandidates(_ parts: [Part]) -> [Int] {
    var out: [Int] = []
    for (i, p) in parts.enumerated() {
        if p.isOffloadReadback { continue }
        if p.content.hasPrefix("[CONTEXT OFFLOADED]") { continue }
        guard p.content.count > 500 else { continue }
        out.append(i)
    }
    return out
}

let bigContent = String(repeating: "line of a large file\n", count: 400)

print("▶️  1. a truncated file_write is refused — nothing written")
do {
    // The stream died inside `content`; the strict parser yielded {}.
    let raw = "{\"path\": \"/var/minis/workspace/app.py\", \"content\": \"import os\\nimport sys\\n"
    let r = executeToolCall(name: "file_write", parsedArgs: [:], raw: raw, tools: tools)
    guard case .refused(let msg, let ui) = r else { check("refused", false); exit(1) }
    check("refused, not executed", true)
    check("model is told the call did NOT run", msg.hasPrefix("Error: This call was NOT executed"))
    check("…names the repair strategy", msg.contains("repair strategy: truncation+\"}"))
    check("…names the target path", msg.contains("to /var/minis/workspace/app.py"))
    check("…and says the file is unchanged", msg.contains("Nothing was written to disk"))
    checkEq("UI shows a blocked status", ui, "failed: Blocked: arguments were truncated in transit")
    // Same for file_edit.
    let rawEdit = "{\"path\": \"/x\", \"old_string\": \"a\", \"new_string\": \"partial"
    check("file_edit is refused too", { if case .refused = executeToolCall(name: "file_edit", parsedArgs: [:], raw: rawEdit, tools: tools) { return true }; return false }())
    // The pre-fix outcome, for contrast: the repair "succeeds" and the half content is a valid write.
    let repaired = repairToolArgs(name: "file_write", args: [:], rawTail: raw, tools: tools)
    check("PRE-FIX: the repaired args look perfectly writable", (repaired.args["content"] as? String) == "import os\nimport sys\n" && repaired.repairs == ["truncation+\"}"])
}

print("▶️  2. read/shell tools run repaired, but the model is told")
do {
    let rawRead = "{\"path\": \"/var/minis/workspace/app.py"
    let r = executeToolCall(name: "file_read", parsedArgs: [:], raw: rawRead, tools: tools)
    guard case .ran(let args, let reminder, let ui) = r else { check("file_read ran", false); exit(1) }
    checkEq("repaired path is used", args["path"], "/var/minis/workspace/app.py")
    check("a <system-reminder> is appended to the result", reminder)
    checkEq("UI does not render a clean success", ui, "failed: Arguments truncated in transit — result may be incomplete")
    let rawShell = "{\"tool_title\": \"ls\", \"command\": \"ls -la"
    check("shell_execute runs repaired", { if case .ran(_, true, _) = executeToolCall(name: "shell_execute", parsedArgs: [:], raw: rawShell, tools: tools) { return true }; return false }())
}

print("▶️  3. only the truncation strategy gates; shape repairs still write")
do {
    // Type coercion: content arrived complete, just as a number.
    let r = executeToolCall(name: "file_write", parsedArgs: ["path": "/x", "content": 42], raw: "{\"path\":\"/x\",\"content\":42}", tools: tools)
    guard case .ran(let args, let reminder, let ui) = r else { check("coerced write ran", false); exit(1) }
    checkEq("coerced to a string", args["content"], "42")
    check("no reminder, clean success", !reminder && ui == "success")
    // Fuzzy field name: `contnt` → `content`, value intact.
    let r2 = executeToolCall(name: "file_write", parsedArgs: ["path": "/x", "contnt": "full body"], raw: "", tools: tools)
    guard case .ran(let args2, false, "success") = r2 else { check("fuzzy write ran", false); exit(1) }
    checkEq("fuzzy-renamed content is the FULL value", args2["content"], "full body")
    // A complete call never enters the repair pass at all.
    let r3 = executeToolCall(name: "file_write", parsedArgs: ["path": "/x", "content": bigContent], raw: "", tools: tools)
    check("complete args → runs untouched", { if case .ran(let a, false, "success") = r3 { return a["content"] == bigContent }; return false }())
    // A literal `{}` (no delta ever carried content) parses with the empty
    // suffix and is tagged `truncation+noop`; for a write that is still a
    // refusal — there is no content to write, and nothing must reach disk.
    let r4 = executeToolCall(name: "file_write", parsedArgs: [:], raw: "{}", tools: tools)
    check("a literal {} file_write is refused with the noop tag", { if case .refused(let m, _) = r4 { return m.contains("truncation+noop") }; return false }())
}

print("▶️  4. reported bytes equal bytes written")
do {
    /// executeFileWrite's write step: the count logged/reported is
    /// `contentData.count`, and contentData is what hits disk.
    func write(_ content: String, to url: URL) -> (reported: Int, onDisk: Int) {
        let data = content.data(using: .utf8)!
        try! data.write(to: url)
        let size = (try! FileManager.default.attributesOfItem(atPath: url.path)[.size] as! NSNumber).intValue
        return (data.count, size)
    }
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("offload-write-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: dir) }
    let cjk = "中文内容 — multi-byte\n" + bigContent
    let r = write(cjk, to: dir.appendingPathComponent("a.txt"))
    checkEq("multi-byte content: reported == on disk", r.reported, r.onDisk)
    checkEq("…and equals utf8 count, not character count", r.reported, cjk.utf8.count)
    check("character count would have been wrong", cjk.count != cjk.utf8.count)
    // A truncated write that slipped through WOULD report a smaller count than
    // the model intended — which is why the count alone is not a safety net.
    let half = String(cjk.prefix(cjk.count / 2))
    check("a half write reports fewer bytes than the full content", write(half, to: dir.appendingPathComponent("b.txt")).reported < cjk.utf8.count)
}

print("▶️  5. reading an offload-store file never re-offloads")
do {
    check("file_read of the store is tagged", isOffloadStorePath("/var/minis/offloads/tools/file_write_c1.txt"))
    let parts = [Part(content: "[CONTEXT OFFLOADED] Content saved to: /var/minis/offloads/tools/a.txt"),
                 Part(content: "[/var/minis/offloads/tools/a.txt | …]\n" + bigContent, isOffloadReadback: true),
                 Part(content: bigContent)]
    checkEq("stub and read-back are both inert; only genuine content is a candidate", offloadCandidates(parts), [2])
    // Loop model: reading the newest stub back each lap must not grow the store.
    var store = 0; var hist = [Part(content: bigContent)]
    for _ in 0..<5 {
        let c = offloadCandidates(hist); store += c.count
        hist = c.map { _ in Part(content: "[CONTEXT OFFLOADED] Content saved to: /var/minis/offloads/tools/x.txt") } + hist.filter { $0.isOffloadReadback || $0.content.count <= 500 }
        hist.append(Part(content: "[/var/minis/offloads/tools/x.txt | …]\n" + bigContent, isOffloadReadback: true))
    }
    checkEq("only the original is ever written", store, 1)
}

print("▶️  6. the placeholder itself must never become file content (#374)")
do {
    // Offloading rewrites a file_write's `input["content"]` IN HISTORY to the
    // stub. If that tool_use is ever executed from the rewritten input (a
    // retry, a resumed/continued pending call, the model copying its own
    // earlier call), the stub lands on disk over the real file and reports a
    // success whose byte count matches the stub. Refused now, exactly like the
    // truncated-write path.
    let stubPrefix = "[CONTEXT OFFLOADED]"
    let noticeTag = "[Minis System Notice]"
    let envelope = "<system-reminder>"
    // LEGACY format — still in every session persisted before the format change
    // and in history synced from a peer on an older build.
    let stub = "\(stubPrefix) Content (~4406 tokens, 15220 bytes) saved to: /var/minis/offloads/tools/file_write_c1.txt\nUse file_read tool to retrieve if needed."
    // CURRENT format — verbatim port of `offloadedStubNotice`.
    let notice = """
    \(envelope)
    \(noticeTag) The original content (~4406 tokens, 15220 bytes) of this tool argument \
    has been pruned to save context and offloaded to: /var/minis/offloads/tools/file_write_c1.txt

    CRITICAL: This is a placeholder notice, NOT actual file content. NEVER pass this \
    placeholder or reuse it in subsequent file_write or file_edit calls. If you need the \
    original content, call file_read on the offloaded path first.
    </system-reminder>
    """
    /// Verbatim port of `AIChatViewModel.isOffloadedStub`
    /// (+Offloading.swift). BOTH formats must be recognised.
    func isOffloadedStub(_ value: String) -> Bool {
        let t = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix(stubPrefix) { return true }
        return t.hasPrefix(envelope) && t.contains(noticeTag)
    }
    /// Port of the gate (+ConcurrentTools.swift, after the truncation refusal)
    /// and of `offloadPlaceholderRefusal` (+FileTools.swift). Returns the
    /// offending field name on a refusal, nil when the write may proceed.
    func offloadRefusal(name: String, args: [String: Any]) -> String? {
        guard writeTools.contains(name) else { return nil }
        let payloadKeys = name == "file_write" ? ["content"] : ["new_string"]
        return payloadKeys.first { isOffloadedStub((args[$0] as? String) ?? "") }
    }
    // Both formats refuse, and neither branch may be dropped.
    checkEq("a write whose content is the CURRENT notice is refused",
            offloadRefusal(name: "file_write", args: ["path": "/x", "content": notice]), "content")
    checkEq("…and a file_edit whose replacement is the CURRENT notice",
            offloadRefusal(name: "file_edit", args: ["path": "/x", "old_string": "a", "new_string": notice]), "new_string")
    checkEq("a newline-prefixed CURRENT notice is still caught",
            offloadRefusal(name: "file_write", args: ["path": "/x", "content": "\n  " + notice]), "content")
    // A bare <system-reminder> is legitimately emitted by other subsystems (the
    // persona reminder). It is NOT an offload stub and must not be refused.
    check("a bare <system-reminder> without the notice tag is NOT refused",
          offloadRefusal(name: "file_write", args: ["path": "/x", "content": "\(envelope)\nremember the user prefers metric.\n</system-reminder>"]) == nil)
    check("the notice tag alone, without the envelope, is NOT refused",
          offloadRefusal(name: "file_write", args: ["path": "/x", "content": "\(noticeTag) a note the model wrote itself"]) == nil)
    checkEq("a write whose content IS the stub is refused",
            offloadRefusal(name: "file_write", args: ["path": "/x", "content": stub]), "content")
    checkEq("…and a file_edit whose replacement text is the stub",
            offloadRefusal(name: "file_edit", args: ["path": "/x", "old_string": "a", "new_string": stub]), "new_string")
    // Leading whitespace must not smuggle it past the prefix test.
    checkEq("a newline-prefixed stub is still caught",
            offloadRefusal(name: "file_write", args: ["path": "/x", "content": "\n  " + stub]), "content")
    // A stub in file_edit's SEARCH text is not a data-loss write — it just will
    // not match, which the edit tool already reports. Not this gate's business.
    check("a stub in file_edit's old_string is NOT refused here",
          offloadRefusal(name: "file_edit", args: ["path": "/x", "old_string": stub, "new_string": "real"]) == nil)
    // Genuine content, including content that merely MENTIONS the marker, writes.
    check("real content writes", offloadRefusal(name: "file_write", args: ["path": "/x", "content": bigContent]) == nil)
    check("content that only mentions the marker mid-body writes",
          offloadRefusal(name: "file_write", args: ["path": "/x", "content": "notes about \(stubPrefix) handling"]) == nil)
    check("read tools are untouched by this gate",
          offloadRefusal(name: "file_read", args: ["path": stub]) == nil)
    let ft = source("Agent/Chat/AIChatViewModel+FileTools.swift")
    let ct = source("Agent/Chat/AIChatViewModel+ConcurrentTools.swift")
    let off = source("Agent/Chat/AIChatViewModel+Offloading.swift")
    if ft.isEmpty || ct.isEmpty || off.isEmpty { print("  ⏭  sources not readable") } else {
        // The prefix is a single shared constant, so the stub writer and the
        // refusal gate cannot drift apart.
        check("the legacy marker is one constant, not three literals",
              off.contains("static let offloadedStubPrefix = \"[CONTEXT OFFLOADED]\""))
        check("the current format's tag and envelope are constants too",
              off.contains("static let offloadedStubNoticeTag = \"[Minis System Notice]\"")
              && off.contains("static let offloadedStubEnvelope = \"<system-reminder>\""))
        // The shared predicate is the single detection point, and it must keep
        // BOTH branches — dropping the legacy one silently unprotects every
        // history persisted before the format change.
        check("detection is one shared predicate",
              off.contains("static func isOffloadedStub(_ value: String) -> Bool"))
        check("…that accepts the legacy marker",
              off.contains("if trimmed.hasPrefix(offloadedStubPrefix) { return true }"))
        check("…and requires BOTH envelope and tag for the current format",
              off.contains("return trimmed.hasPrefix(offloadedStubEnvelope)")
              && off.contains("&& trimmed.contains(offloadedStubNoticeTag)"))
        check("a pruned ARGUMENT gets the system-reminder notice",
              off.contains("newInput[\"content\"] = Self.offloadedStubNotice(")
              && off.contains("CRITICAL: This is a placeholder notice, NOT actual file content."))
        check("…and is marked as offloaded provenance, not just by its text",
              off.contains("isOffloadedArgument: true"))
        check("the scanner skip routes through the shared predicate",
              off.contains("if Self.isOffloadedStub(content) {"))
        check("the dispatch gate refuses placeholder writes",
              ct.contains("let payloadKeys = tu.name == \"file_write\" ? [\"content\"] : [\"new_string\"]")
              && ct.contains("REFUSED offload-placeholder write"))
        check("…and returns an error tool_result so the model sees it",
              ct.contains("Error: This call was NOT executed. Its `\\(offendingKey)` is an offload "))
        check("…pointing the model at file_read to recover the content",
              ct.contains("Use file_read on the path named inside the placeholder"))
        check("the file tools carry the same guard (defense-in-depth)",
              ft.contains("static func offloadPlaceholderRefusal(")
              && ft.contains("offloadPlaceholderRefusal(\n            field: \"content\", value: content, path: path")
              && ft.contains("offloadPlaceholderRefusal(\n            field: \"new_string\", value: newString, path: path"))
        check("…and routes through the shared dual-format predicate",
              ft.contains("guard Self.isOffloadedStub(value) else { return nil }"))
        check("the dispatch gate also uses the shared predicate",
              ct.contains("return Self.isOffloadedStub(v)"))
    }
    check("byte mismatch would expose it: stub is far shorter than the original", stub.utf8.count < bigContent.utf8.count)
}

print("▶️  7. shipping sources still carry the pinned lines")
do {
    let pre = source("Agent/Chat/AIChatViewModel+ToolPreflight.swift")
    let ct = source("Agent/Chat/AIChatViewModel+ConcurrentTools.swift")
    let offl = source("Agent/Chat/AIChatViewModel+Offloading.swift")
    if pre.isEmpty || ct.isEmpty || offl.isEmpty { print("  ⏭  sources not readable") } else {
        check("truncation repair is tagged `truncation+`", pre.contains("repairs.append(\"truncation+\\(suffix.isEmpty ? \"noop\" : suffix)\")"))
        check("only the truncation tag is a data-loss signal", ct.contains("truncationRepairTag = repairOutcome.repairs.first { $0.hasPrefix(\"truncation+\") }"))
        check("the refusal is gated on write tools", ct.contains("tu.name == \"file_write\" || tu.name == \"file_edit\" {"))
        check("refused result is an error tool_result", ct.contains("resultPart: .toolResult(id: tu.id, name: tu.name, content: modelMessage, isError: true)"))
        check("repaired-but-executed calls do not render success", ct.contains("} else if toolSuccess, truncationRepairTag != nil {"))
        check("…and carry a reminder to the model", ct.contains("The argument stream for this call was truncated in "))
        check("read-back parts are skipped before the prefix test", offl.contains("if isReadback {") && offl.contains("skippedOffloadReadback += 1"))
        check("file_write/file_edit inputs are still offload candidates (the #374 precondition)", offl.contains("guard name == \"file_write\" || name == \"file_edit\" else { continue }"))
        // [T-offload-toolinput-guards] The two guards the .toolResult branch has
        // always had. Asserted on the source because the scanner reads live
        // history, which a standalone script cannot construct.
        check("…but an already-pruned argument is skipped, by flag and by text",
              offl.contains("if isOffloadedArgument {")
              && offl.contains("if let content = input[\"content\"] as? String, Self.isOffloadedStub(content) {"))
        check("…and an unanswered tool call's argument is never pruned",
              offl.contains("guard answeredToolUseIds.contains(id) else {")
              && offl.contains("skippedUnanswered += 1"))
        check("answered ids are collected over the whole history, not the candidate range",
              offl.contains("for msg in agentHistory {") && offl.contains("answeredToolUseIds.insert(id)"))
    }
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
