// Tests for [T-ios-vision-branch-mismatch #182] + [T-ios-vision-group-gate-too-strict]
// + [T-ios-image-path-metadata] — one source of truth for "can the model that
// receives this turn see images".
//
// Three facts, each of which has already been wrong once:
//   1. `activeModelHasNativeVision` (AIChatViewModel+ToolDefinitions.swift:23)
//      resolves from `resolveCurrentEntry()` — the entry the REQUEST is built
//      from — and falls back to `selectedModel` only when that fails. Both the
//      read_image registration (which picks the tool description) and its
//      handler (AIChatViewModel+ConcurrentTools.swift ~766, pixels vs Vision
//      Group) read that one property. 5dd9fa3c0: reading `selectedModel` in
//      one of them registered the Vision-Group description and then served
//      the native pixel branch under group routing.
//   2. `VisionGroupResolver.candidates()` does NOT consult the Keychain
//      (e97089526): a cold-launch credential probe returning false used to
//      make read_image vanish from the tool schema entirely.
//   3. `VisionGroupResolver.attachmentPlaceholder` (af0089217 / 982d9b335)
//      always hands a non-vision model the sandbox path, and names read_image
//      only when a Vision Group is configured; the send is never blocked.
//
// Pure functions are ported; section [5] greps the sources. Standalone
// (`swift VisionCapabilityTruthTests.swift`).

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// MARK: - Minimal model / store mirror

enum Modality { case text, imageInput }
struct LLMModel { let id: String; let modalities: Set<Modality> }
struct ModelEntry { let id: String; let model: LLMModel; let instanceId: String; var isHidden = false }
struct ProviderInstance { let id: String; var isEnabled = true; var hasAnyCredential = true }
struct Group { let memberEntryIds: [String] }
struct Store {
    var entries: [String: ModelEntry] = [:]
    var instances: [String: ProviderInstance] = [:]
    var visionGroup: Group? = nil
}

let textOnly = LLMModel(id: "deepseek-v4", modalities: [.text])
let vision = LLMModel(id: "kimi-k3", modalities: [.text, .imageInput])

// MARK: - Port: activeModelHasNativeVision (ToolDefinitions.swift:23)

func activeModelHasNativeVision(resolved: ModelEntry?, selected: LLMModel) -> Bool {
    let model = resolved?.model ?? selected
    return model.modalities.contains(.imageInput)
}

// MARK: - Port: VisionGroupResolver.candidates / isConfigured

func candidates(_ store: Store) -> [ModelEntry] {
    guard let group = store.visionGroup else { return [] }
    return group.memberEntryIds.compactMap { entryId -> ModelEntry? in
        guard let entry = store.entries[entryId],
              !entry.isHidden,
              entry.model.modalities.contains(.imageInput),
              let instance = store.instances[entry.instanceId],
              instance.isEnabled else { return nil }
        // NOTE: no `instance.hasAnyCredential` — deliberately (e97089526).
        return entry
    }
}
func isConfigured(_ store: Store) -> Bool { !candidates(store).isEmpty }

// MARK: - Port: makeAgentTools' read_image registration

struct ToolDef { let name: String; let description: String }
func registerReadImage(nativeVision: Bool, visionGroupConfigured: Bool) -> ToolDef? {
    guard nativeVision || visionGroupConfigured else { return nil }
    let description = nativeVision
        ? "Read an image file from the Linux filesystem and return it for visual analysis."
        : "Read an image file from the Linux filesystem and return a written description of it."
    return ToolDef(name: "read_image", description: description)
}

// MARK: - Port: the read_image handler's branch pick (ConcurrentTools ~766)

enum Branch { case nativePixels, visionGroupDescription }
func handlerBranch(nativeVision: Bool) -> Branch { nativeVision ? .nativePixels : .visionGroupDescription }

// MARK: - Port: VisionGroupResolver.attachmentPlaceholder

func attachmentPlaceholder(linuxPath: String?, configured: Bool) -> String {
    let path = (linuxPath?.isEmpty == false) ? linuxPath : nil
    guard configured else {
        guard let path else { return "[Image attached but this model does not support vision input]" }
        return "[Image attached at \(path). This model cannot view images directly, but the "
            + "file is readable from the Linux sandbox — you can inspect or process it with "
            + "shell_execute (for example `file`, `identify`, an OCR or Python/Pillow step) "
            + "if the task needs it.]"
    }
    guard let path else {
        return "[Image attached but this model does not support native vision input. "
            + "A Vision Group is configured: call the read_image tool with the image's "
            + "path to get a description of its content.]"
    }
    return "[Image attached: \(path). This model does not support native vision input, but a "
        + "Vision Group is configured — call the read_image tool with this path to get a "
        + "description of the image content. You may pass a `prompt` argument to ask about "
        + "specific details instead of getting a generic description.]"
}

// MARK: - [1] group routing: selectedModel text-only, resolved entry vision

print("\n[1] registration and handler read the REQUEST's model, not selectedModel")
do {
    let resolved = ModelEntry(id: "e-vision", model: vision, instanceId: "i1")
    let native = activeModelHasNativeVision(resolved: resolved, selected: textOnly)
    check("resolved vision entry wins over a text-only selectedModel", native)
    check("handler takes the native pixel branch", handlerBranch(nativeVision: native) == .nativePixels)
    let tool = registerReadImage(nativeVision: native, visionGroupConfigured: false)
    check("read_image registered with the NATIVE description",
          tool?.description.contains("return it for visual analysis") == true)

    // The inverse mismatch (5dd9fa3c0's field case): UI shows a vision model,
    // the session binding routes to a text-only one.
    let resolvedText = ModelEntry(id: "e-text", model: textOnly, instanceId: "i1")
    let native2 = activeModelHasNativeVision(resolved: resolvedText, selected: vision)
    check("resolved text-only entry wins over a vision selectedModel", native2, false)
    check("handler takes the Vision Group branch", handlerBranch(nativeVision: native2) == .visionGroupDescription)
    let tool2 = registerReadImage(nativeVision: native2, visionGroupConfigured: true)
    check("read_image registered with the DESCRIPTION wording",
          tool2?.description.contains("written description") == true)
    // Registration description and handler branch are derived from the same
    // Bool, so they can never disagree.
    for (r, s) in [(resolved, textOnly), (resolvedText, vision), (nil, vision), (nil, textOnly)] {
        let n = activeModelHasNativeVision(resolved: r, selected: s)
        let desc = registerReadImage(nativeVision: n, visionGroupConfigured: true)!.description
        let branch = handlerBranch(nativeVision: n)
        check("agreement: \(r?.model.id ?? "nil") / \(s.id)",
              (branch == .nativePixels) == desc.contains("visual analysis"))
    }
    check("selectedModel is only a fallback when resolution fails",
          activeModelHasNativeVision(resolved: nil, selected: vision)
            && !activeModelHasNativeVision(resolved: nil, selected: textOnly))
}

// MARK: - [2] Keychain probe says no → read_image still registered

print("\n[2] credential probe does not gate the Vision Group")
do {
    var store = Store()
    store.entries["e-kimi"] = ModelEntry(id: "e-kimi", model: vision, instanceId: "kimi")
    store.instances["kimi"] = ProviderInstance(id: "kimi", isEnabled: true, hasAnyCredential: false)
    store.visionGroup = Group(memberEntryIds: ["e-kimi"])
    check("candidates ignore hasAnyCredential=false", candidates(store).count == 1)
    check("isConfigured is true", isConfigured(store))
    let tool = registerReadImage(nativeVision: false, visionGroupConfigured: isConfigured(store))
    check("read_image is in the tool schema for a text-only host", tool != nil)

    // What DOES gate: hidden entry, missing modality, disabled instance, dangling refs.
    var hidden = store; hidden.entries["e-kimi"]!.isHidden = true
    check("hidden entry is excluded", candidates(hidden).isEmpty)
    var noMod = store; noMod.entries["e-kimi"] = ModelEntry(id: "e-kimi", model: textOnly, instanceId: "kimi")
    check("entry without imageInput is excluded", candidates(noMod).isEmpty)
    var disabled = store; disabled.instances["kimi"]!.isEnabled = false
    check("disabled instance is excluded", candidates(disabled).isEmpty)
    var dangling = store; dangling.visionGroup = Group(memberEntryIds: ["e-deleted", "e-kimi"])
    check("a dangling member is skipped, not fatal", candidates(dangling).map(\.id) == ["e-kimi"])
    var noGroup = store; noGroup.visionGroup = nil
    check("no group → not configured", !isConfigured(noGroup))
    check("no group + text-only host → read_image NOT registered",
          registerReadImage(nativeVision: false, visionGroupConfigured: isConfigured(noGroup)) == nil)
}

// MARK: - [3] no vision + no Vision Group → placeholder carries the path

print("\n[3] non-vision model, no Vision Group")
do {
    let p = attachmentPlaceholder(linuxPath: "/var/minis/attachments/uploads/photo.jpg", configured: false)
    check("placeholder contains the linux path", p.contains("/var/minis/attachments/uploads/photo.jpg"))
    check("…points at shell_execute in the sandbox", p.contains("shell_execute"))
    check("…does NOT name read_image (the tool is not registered for this model)", !p.contains("read_image"))
    let noPath = attachmentPlaceholder(linuxPath: nil, configured: false)
    check("no path + no group → the historical literal", noPath == "[Image attached but this model does not support vision input]")
    check("empty path is treated as no path", attachmentPlaceholder(linuxPath: "", configured: false) == noPath)
    // The send is not blocked: the image part becomes a text placeholder in
    // the request, it is never a reason to refuse the turn (see [5] grep).
    check("placeholder is a non-empty text substitute (send proceeds)", !p.isEmpty)
}

// MARK: - [4] no vision + Vision Group → placeholder points at read_image

print("\n[4] non-vision model, Vision Group configured")
do {
    let p = attachmentPlaceholder(linuxPath: "/var/minis/attachments/uploads/photo.jpg", configured: true)
    check("placeholder names read_image", p.contains("read_image"))
    check("…with the path", p.contains("/var/minis/attachments/uploads/photo.jpg"))
    check("…and mentions the prompt argument", p.contains("`prompt`"))
    let noPath = attachmentPlaceholder(linuxPath: nil, configured: true)
    check("no path but configured → still names read_image", noPath.contains("read_image"))
    check("the four tiers are all distinct",
          Set([p, noPath,
               attachmentPlaceholder(linuxPath: "/x", configured: false),
               attachmentPlaceholder(linuxPath: nil, configured: false)]).count == 4)
}

// MARK: - [5] shipping source cross-check

print("\n[5] shipping source cross-check")
func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let toolDefs = sourceOf("Agent/Chat/AIChatViewModel+ToolDefinitions.swift")
let concurrent = sourceOf("Agent/Chat/AIChatViewModel+ConcurrentTools.swift")
let resolver = sourceOf("Providers/VisionGroupResolver.swift")
let vm = sourceOf("Agent/Chat/AIChatViewModel.swift")
let attachments = sourceOf("Agent/Chat/AIChatViewModel+Attachments.swift")
let openai = sourceOf("Providers/OpenAI/OpenAIAgentProvider.swift")
check("ToolDefinitions read", !toolDefs.isEmpty)
check("ConcurrentTools read", !concurrent.isEmpty)
check("VisionGroupResolver read", !resolver.isEmpty)
check("OpenAIAgentProvider read", !openai.isEmpty)

// (1) single source of truth
check("activeModelHasNativeVision resolves from resolveCurrentEntry() with selectedModel as fallback",
      toolDefs.contains("let model = resolveCurrentEntry()?.model ?? selectedModel"))
/// Code lines only — the doc comment on activeModelHasNativeVision quotes the
/// old wrong expression on purpose.
func codeLines(_ src: String) -> String {
    src.split(separator: "\n", omittingEmptySubsequences: false)
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}
check("ToolDefinitions never reads selectedModel.capabilities (outside comments)",
      codeLines(toolDefs).contains("selectedModel.capabilities"), false)
check("registration uses activeModelHasNativeVision",
      toolDefs.contains("let nativeVision = activeModelHasNativeVision"))
check("handler uses the SAME property",
      concurrent.contains("let nativeVision = self.activeModelHasNativeVision"))
check("handler never reads selectedModel.capabilities (outside comments)",
      codeLines(concurrent).contains("selectedModel.capabilities"), false)
check("registration gate is native OR group",
      toolDefs.contains("if nativeVision || visionGroupConfigured {"))
check("isConfigured is evaluated before the || (keeps the mirror fresh)",
      (toolDefs.range(of: "let visionGroupConfigured = VisionGroupResolver.isConfigured")?.lowerBound ?? toolDefs.endIndex)
        < (toolDefs.range(of: "if nativeVision || visionGroupConfigured {")?.lowerBound ?? toolDefs.startIndex))

// (2) no keychain probe in candidates()
check("candidates() ships", resolver.contains("static func candidates(seed: Int = 0) -> [ModelEntry] {"))
check("candidates() does not consult hasAnyCredential", {
    guard let start = resolver.range(of: "static func candidates(seed: Int = 0) -> [ModelEntry] {"),
          let end = resolver.range(of: "static func groupName()") else { return false }
    return !resolver[start.lowerBound..<end.lowerBound].contains("hasAnyCredential")
}())
check("candidates() still checks modality + instance enabled",
      resolver.contains("entry.model.capabilities.supportedModalities.contains(.imageInput),")
        && resolver.contains("instance.isEnabled else { return nil }"))

// (3)/(4) placeholder tiers + send never blocked
check("placeholder: no-group path tier mentions shell_execute and not read_image",
      resolver.contains("file is readable from the Linux sandbox — you can inspect or process it with")
        && resolver.contains("Deliberately does NOT mention read_image"))
check("placeholder: group tier names read_image with the path",
      resolver.contains("Vision Group is configured — call the read_image tool with this path"))
check("OpenAI serializers substitute the placeholder for non-vision models (never drop, never block)",
      openai.components(separatedBy: "VisionGroupResolver.attachmentPlaceholder(linuxPath: linuxPath)").count - 1 >= 2)
check("send() has no imageInput gate (no send-time modality check in the view model)",
      vm.contains(".imageInput") || attachments.contains(".imageInput"), false)

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
