// Tests for [T-paste-single-split] + [T-paste-mediaref] — `[Pasted#N]` is
// expanded exactly ONCE, at the draft → AgentMessage boundary.
//
// History of the shape (f9faeaa4a): the paste used to be expanded at
// request-build time by a wrapper (`resolveHistoryForOutbound`) that had to be
// applied around every provider call site; three separate bugs came from a
// call site the wrap missed, each time handing the model the literal
// `[Pasted#N]` instead of the content. Now `consumePastedDraft`
// (AIChatViewModel+Attachments.swift ~219) is the single consumer: it
// produces `modelText` (what agentHistory stores — never a resolvable
// literal) and `storedParts` (alternating .text / .mediaRef for parts_json),
// and removes the consumed entries from the buffer. On reload,
// `ChatStore.toAgentMessage` (ChatStore.swift ~5979) inlines a pasted
// mediaRef back as `.text`, so every provider request, retry, fallback and
// compaction summary reads history as-is.
//
// Pure pieces ported: PastePlaceholder.expand / isPastedTextRef /
// idFromMediaRefFileName, consumePastedDraft (file write stubbed), and the
// hydration gate. Standalone (`swift PastedDraftResolverTests.swift`).

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ actual: T, _ expected: T) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label)\n     expected: \(expected)\n     actual:   \(actual)"); failures += 1 }
}

// MARK: - Port: PastePlaceholder

struct PastedText { let id: Int; let text: String }
struct MediaRef: Equatable { let id: String; let mimeType: String; let originalFileName: String?; let subdir: String; let bytes: Data }
enum ContentPart: Equatable { case text(String), mediaRef(MediaRef) }

enum PastePlaceholder {
    static let regex = try! NSRegularExpression(pattern: #"\[Pasted#(\d+)\]"#)
    static func literal(for id: Int) -> String { "[Pasted#\(id)]" }
    static let pastedMimeType = "text/plain"
    static let mediaRefFileNamePrefix = "Pasted#"
    static let mediaSubdir = "pasted"
    static let unavailableMarker = "[pasted content unavailable — the stored text file is missing]"
    static func isPastedTextRef(_ ref: MediaRef) -> Bool {
        ref.mimeType == pastedMimeType && (ref.originalFileName?.hasPrefix(mediaRefFileNamePrefix) ?? false)
    }
    static func mediaRefFileName(for id: Int) -> String { "\(mediaRefFileNamePrefix)\(id).txt" }
    static func idFromMediaRefFileName(_ name: String?) -> Int? {
        guard let name, name.hasPrefix(mediaRefFileNamePrefix) else { return nil }
        let rest = name.dropFirst(mediaRefFileNamePrefix.count)
        let digits = rest.prefix { $0.isNumber }
        return digits.isEmpty ? nil : Int(digits)
    }
    static func expand(_ text: String, lookup: (Int) -> String?) -> String {
        guard text.contains("[Pasted#") else { return text }
        let ns = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: ns.length))
        guard !matches.isEmpty else { return text }
        var result = ""
        var lastEnd = 0
        for m in matches {
            result += ns.substring(with: NSRange(location: lastEnd, length: m.range.location - lastEnd))
            let idStr = ns.substring(with: m.range(at: 1))
            if let id = Int(idStr), let replacement = lookup(id) { result += replacement }
            else { result += ns.substring(with: m.range) }
            lastEnd = m.range.location + m.range.length
        }
        result += ns.substring(from: lastEnd)
        return result
    }
}

// MARK: - Port: consumePastedDraft (ChatStore.saveMedia stubbed to an in-memory ref)

final class DraftVM {
    var pastedTexts: [PastedText] = []
    var savedFiles: [MediaRef] = []
    var consumeCalls = 0

    func saveMedia(data: Data, mimeType: String, originalFileName: String, subdir: String) -> MediaRef {
        let ref = MediaRef(id: UUID().uuidString, mimeType: mimeType, originalFileName: originalFileName, subdir: subdir, bytes: data)
        savedFiles.append(ref)
        return ref
    }

    func consumePastedDraft(_ draft: String) -> (modelText: String, storedParts: [ContentPart])? {
        consumeCalls += 1
        guard !pastedTexts.isEmpty, draft.contains("[Pasted#") else { return nil }
        let ns = draft as NSString
        let matches = PastePlaceholder.regex.matches(in: draft, range: NSRange(location: 0, length: ns.length))
        guard !matches.isEmpty else { return nil }

        var modelText = ""
        var storedParts: [ContentPart] = []
        var pending = ""
        var lastEnd = 0
        var consumedIds: Set<Int> = []
        for m in matches {
            let before = ns.substring(with: NSRange(location: lastEnd, length: m.range.location - lastEnd))
            lastEnd = m.range.location + m.range.length
            let idStr = ns.substring(with: m.range(at: 1))
            guard let id = Int(idStr), let entry = pastedTexts.first(where: { $0.id == id }) else {
                let literal = ns.substring(with: m.range)
                modelText += before + literal
                pending += before + literal
                continue
            }
            modelText += before + entry.text
            pending += before
            if !pending.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { storedParts.append(.text(pending)) }
            pending = ""
            let ref = saveMedia(data: Data(entry.text.utf8), mimeType: PastePlaceholder.pastedMimeType,
                                originalFileName: PastePlaceholder.mediaRefFileName(for: id), subdir: PastePlaceholder.mediaSubdir)
            storedParts.append(.mediaRef(ref))
            consumedIds.insert(id)
        }
        guard !consumedIds.isEmpty else { return nil }
        let tail = ns.substring(from: lastEnd)
        modelText += tail
        pending += tail
        if !pending.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { storedParts.append(.text(pending)) }
        pastedTexts.removeAll { consumedIds.contains($0.id) }
        return (modelText, storedParts)
    }
}

// MARK: - Port: ChatStore.toAgentMessage's mediaRef hydration

enum AgentPart: Equatable { case text(String), imageData(mime: String) }
func hydrate(_ parts: [ContentPart], fileExists: (MediaRef) -> Bool = { _ in true }) -> [AgentPart] {
    var out: [AgentPart] = []
    for p in parts {
        switch p {
        case .text(let s): out.append(.text(s))
        case .mediaRef(let ref):
            if PastePlaceholder.isPastedTextRef(ref) {
                if fileExists(ref), let s = String(data: ref.bytes, encoding: .utf8) { out.append(.text(s)) }
                else { out.append(.text(PastePlaceholder.unavailableMarker)) }
            } else if fileExists(ref) {
                out.append(.imageData(mime: ref.mimeType))
            }
        }
    }
    return out
}

let bigPaste = String(repeating: "SELECT * FROM logs WHERE level = 'error';\n", count: 60)

// MARK: - [1] draft with [Pasted#1] + buffered content → stored message is the expanded text

print("\n[1] draft [Pasted#1] + buffer → agentHistory text is fully expanded")
do {
    let vm = DraftVM()
    vm.pastedTexts = [PastedText(id: 1, text: bigPaste)]
    let r = vm.consumePastedDraft("Explain this query:\n[Pasted#1]\nthanks")!
    checkEq("modelText = prose + full paste + prose", r.modelText, "Explain this query:\n\(bigPaste)\nthanks")
    check("modelText carries NO resolvable literal", !r.modelText.contains("[Pasted#"))
    checkEq("stored parts alternate text / mediaRef / text",
            r.storedParts.map { if case .text = $0 { return "text" }; return "mediaRef" }, ["text", "mediaRef", "text"])
    if case .mediaRef(let ref) = r.storedParts[1] {
        check("ref is text/plain under the pasted subdir with the Pasted#N file name",
              ref.mimeType == "text/plain" && ref.subdir == "pasted" && ref.originalFileName == "Pasted#1.txt")
        check("ref bytes are the paste", String(data: ref.bytes, encoding: .utf8) == bigPaste)
        check("ref is recognised as a pasted text ref", PastePlaceholder.isPastedTextRef(ref))
        checkEq("id round-trips through the file name", PastePlaceholder.idFromMediaRefFileName(ref.originalFileName), 1)
    } else { check("stored part [1] is a mediaRef", false) }
    check("the stored .text parts do NOT contain the paste (bubble never typesets it)",
          r.storedParts.allSatisfy { if case .text(let t) = $0 { return !t.contains("SELECT") }; return true })
    check("buffer entry was consumed", vm.pastedTexts.isEmpty)

    // Two pastes, and a literal at the very start / end.
    let vm2 = DraftVM()
    vm2.pastedTexts = [PastedText(id: 1, text: "AAA"), PastedText(id: 2, text: "BBB")]
    let r2 = vm2.consumePastedDraft("[Pasted#1] and [Pasted#2]")!
    checkEq("two pastes expand in order", r2.modelText, "AAA and BBB")
    checkEq("stored: ref, text, ref", r2.storedParts.map { if case .text = $0 { return "text" }; return "mediaRef" }, ["mediaRef", "text", "mediaRef"])
    check("both consumed", vm2.pastedTexts.isEmpty)
}

// MARK: - [2] second consumption is idempotent

print("\n[2] consuming the same draft twice → nil, nothing re-expanded")
do {
    let vm = DraftVM()
    vm.pastedTexts = [PastedText(id: 1, text: bigPaste)]
    let first = vm.consumePastedDraft("see [Pasted#1]")!
    let second = vm.consumePastedDraft("see [Pasted#1]")
    check("second call returns nil (buffer already drained)", second == nil)
    checkEq("only one media file was ever written", vm.savedFiles.count, 1)
    // …and re-consuming the already-EXPANDED text is a no-op too: nothing in
    // agentHistory can be expanded a second time.
    vm.pastedTexts = [PastedText(id: 1, text: "SHOULD NOT APPEAR")]
    check("expanded modelText has nothing to consume", vm.consumePastedDraft(first.modelText) == nil)
    checkEq("still one media file", vm.savedFiles.count, 1)
    // Pasted CONTENT that itself looks like a placeholder is never re-scanned.
    let vm3 = DraftVM()
    vm3.pastedTexts = [PastedText(id: 1, text: "outer [Pasted#2] inner"), PastedText(id: 2, text: "INJECTED")]
    let r3 = vm3.consumePastedDraft("x [Pasted#1] y")!
    checkEq("no expansion through paste content", r3.modelText, "x outer [Pasted#2] inner y")
    check("#2 was not consumed", vm3.pastedTexts.map(\.id) == [2])
    checkEq("PastePlaceholder.expand: single pass, no rescans",
            PastePlaceholder.expand("[Pasted#1]", lookup: { $0 == 1 ? "[Pasted#2]" : "NO" }), "[Pasted#2]")
}

// MARK: - [3] a hand-typed literal is not a marker

print("\n[3] literal [Pasted#9] with no buffer entry → passes through verbatim")
do {
    let vm = DraftVM()
    vm.pastedTexts = [PastedText(id: 1, text: "real")]
    let r = vm.consumePastedDraft("what does [Pasted#9] mean? also [Pasted#1]")!
    checkEq("unknown id kept verbatim, known id expanded", r.modelText, "what does [Pasted#9] mean? also real")
    checkEq("the literal stays inside the stored text part", r.storedParts.first, .text("what does [Pasted#9] mean? also "))
    // Only unknown ids → nil, draft untouched, buffer untouched.
    let vm2 = DraftVM()
    vm2.pastedTexts = [PastedText(id: 1, text: "real")]
    check("draft with only unknown ids → nil (caller keeps plain .text)", vm2.consumePastedDraft("[Pasted#9]") == nil)
    check("buffer untouched", vm2.pastedTexts.count == 1)
    // Lookalike spellings are not markers at all.
    for s in ["[pasted#1]", "[Pasted #1]", "[Pasted#]", "Pasted#1", "[Pasted#1"] {
        check("\(s) is not a marker", vm2.consumePastedDraft(s) == nil)
    }
    checkEq("expand() leaves an unknown id alone", PastePlaceholder.expand("a [Pasted#9] b", lookup: { _ in nil }), "a [Pasted#9] b")
    check("file-name recovery rejects non-paste names",
          PastePlaceholder.idFromMediaRefFileName("notes.txt") == nil && PastePlaceholder.idFromMediaRefFileName(nil) == nil)
}

// MARK: - [4] compaction / reload input containing a pasted mediaRef is already resolved

print("\n[4] history hydrated from parts_json inlines the paste as text")
do {
    let ref = MediaRef(id: "m1", mimeType: "text/plain", originalFileName: "Pasted#4.txt", subdir: "pasted", bytes: Data(bigPaste.utf8))
    let parts: [ContentPart] = [.text("Explain:"), .mediaRef(ref), .text("thanks")]
    let hydrated = hydrate(parts)
    checkEq("mediaRef → .text with the full paste, at the same position",
            hydrated, [.text("Explain:"), .text(bigPaste), .text("thanks")])
    check("no image part was fabricated from the UTF-8 bytes",
          !hydrated.contains { if case .imageData = $0 { return true }; return false })
    check("hydrated text carries no literal", !hydrated.contains { if case .text(let t) = $0 { return t.contains("[Pasted#") }; return false })
    // Compaction reads effectiveAgentHistory() — i.e. this hydrated form —
    // so the summariser sees content, never a marker.
    let compactInput = hydrated.compactMap { if case .text(let t) = $0 { return t }; return nil }.joined(separator: "\n")
    check("compaction input contains the paste body", compactInput.contains("SELECT * FROM logs"))
    check("compaction input contains no marker", !compactInput.contains("[Pasted#"))
    // Missing file → explicit marker, never silent drop.
    checkEq("missing file → unavailable marker", hydrate([.mediaRef(ref)], fileExists: { _ in false }),
            [.text(PastePlaceholder.unavailableMarker)])
    // A real upload keeps being an image.
    let img = MediaRef(id: "m2", mimeType: "image/png", originalFileName: "shot.png", subdir: "attachments", bytes: Data([0x89]))
    checkEq("image ref → imageData", hydrate([.mediaRef(img)]), [.imageData(mime: "image/png")])
}

// MARK: - [5] source: the request path no longer expands anything

print("\n[5] shipping source cross-check")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let attachments = sourceOf("Agent/Chat/AIChatViewModel+Attachments.swift")
let vm = sourceOf("Agent/Chat/AIChatViewModel.swift")
let rb = sourceOf("Agent/Chat/AIChatViewModel+RequestBudget.swift")
let compaction = sourceOf("Agent/Chat/AIChatViewModel+Compaction.swift")
let fallback = sourceOf("Agent/Chat/AIChatViewModel+Fallback.swift")
let chatStore = sourceOf("Agent/Chat/ChatStore.swift")
check("Attachments read", !attachments.isEmpty)
check("view model read", !vm.isEmpty)
check("ChatStore read", !chatStore.isEmpty)
check("consumePastedDraft is defined once, in Attachments",
      attachments.components(separatedBy: "func consumePastedDraft(").count - 1 == 1
        && !vm.contains("func consumePastedDraft("))
check("it is the only place the buffer is drained by consumption",
      attachments.contains("pastedTexts.removeAll { consumedIds.contains($0.id) }")
        && vm.components(separatedBy: "pastedTexts.removeAll").count - 1 == 0)
check("every send entry (send / programmatic / queued) consumes at the draft boundary",
      vm.components(separatedBy: "consumePastedDraft(").count - 1 >= 3)
let requestPathFiles = [vm, rb, compaction, fallback, sourceOf("Providers/OpenAI/OpenAIAgentProvider.swift"), sourceOf("Providers/Anthropic/AnthropicAgentProvider.swift")]
check("no request-time expansion function is called anywhere on the request path",
      requestPathFiles.contains { $0.contains("resolveHistoryForOutbound(") || $0.contains("expandPastedPlaceholdersForRequest(") || $0.contains("PastePlaceholder.expand(") }, false)
check("applyRequestImageBudget does not touch pasted text", !rb.contains("Pasted"))
check("the regex accepts exactly one spelling",
      attachments.contains(##"static let regex = try! NSRegularExpression(pattern: #"\[Pasted#(\d+)\]"#)"##))
check("unknown ids keep their literal", attachments.contains("let literal = ns.substring(with: m.range)   // unknown id: keep literal"))
check("hydration inlines a pasted ref as .text", chatStore.contains("if PastePlaceholder.isPastedTextRef(ref) {"))
check("hydration emits the unavailable marker on a missing file",
      chatStore.contains("agentParts.append(.text(PastePlaceholder.unavailableMarker))"))

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
