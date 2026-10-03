// [T26] minis-model-use sub-calls (iOS half): no identity-asserting default
// persona when --system is absent, --system-file and --prompt-file share one
// path resolver, input_audio blocks survive serialization into BOTH the Chat
// Completions and Responses bodies, and an audio_output (TTS) model gets no
// thinking config and no system instruction on the Gemini path.
//
// Issues: OpenMinis#103/#104/#105 (sub-model answered "I am Minis" — the same
// bug reported three times), #107/#108 (--system-file path resolution),
// #67 (input_audio silently dropped), #135, #280, #226 (Gemini TTS 400 on
// thinking / system instruction).
//
// Standalone (`swift ModelUseSubcallTests.swift`): deps/libs/libish_emu.a is
// device-only arm64, so the app cannot link for a simulator and an XCTest
// bundle has nowhere to run. Sections [1]-[4] port the CLI (ModelUseOffload.m)
// and the request builders verbatim (file:line cited); section [5] re-reads
// the shipping sources so the ports cannot drift.

import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Ported: the CLI's system-prompt decision (ModelUseOffload.m:259-261, 276-300, 382-405)

/// The default the CLI injects when neither --system nor --system-file was passed.
/// Verbatim from ModelUseOffload.m:400-404.
let defaultCallingContext =
    "You are being invoked as a sub-agent inside an app called Minis. "
    + "This is the calling environment, not your identity — keep your own "
    + "model identity unchanged. You are handling a focused task delegated "
    + "by the parent agent loop: answer the request directly and concisely, "
    + "without restating it or adding extra meta-commentary."

/// The pre-[T-modeluse-identity-pollution] wording, kept so the test shows a difference.
let preFixDefault = "You are Minis, an on-device AI assistant running on iOS."

struct CLIArgs {
    var system: String? = nil          // --system
    var systemFile: String? = nil      // --system-file
    var promptFile: String? = nil      // --prompt-file
}

enum CLIError: Error, Equatable { case systemFileNotFound(String); case promptFileNotFound(String) }

/// Port of the --system / --system-file / default sequence. `files` stands in
/// for the filesystem (host path → contents) and `resolve` for
/// noff_resolve_existing_input_path.
func resolveSystemPrompt(_ args: CLIArgs, files: [String: String],
                         resolve: (String) -> String?) throws -> (prompt: String?, injected: Bool) {
    var systemPrompt = args.system
    if let systemFile = args.systemFile, systemPrompt == nil {
        guard let hostPath = resolve(systemFile), let contents = files[hostPath] else {
            throw CLIError.systemFileNotFound("System file not found: \(systemFile)")
        }
        systemPrompt = contents
    }
    var systemPromptWasInjected = false
    if systemPrompt == nil {
        systemPrompt = defaultCallingContext
        systemPromptWasInjected = true
    }
    return (systemPrompt, systemPromptWasInjected)
}

// MARK: - Ported: path resolution (ModelUseOffload.m:183-193, NativeOffloadUtils.m:307-340)

struct FakeFS {
    var existing: Set<String>
    var home = "/var/mobile/Containers/Data/Application/APP"
    var dataRoot: String { home + "/Documents/alpine-rootfs/data" }

    /// noff_resolve_host_path (NativeOffloadUtils.m:307).
    func resolveHostPath(_ guestPath: String) -> String? {
        if guestPath.isEmpty { return nil }
        let privateHome = "/private" + home
        if guestPath.hasPrefix(home) || guestPath.hasPrefix(privateHome)
            || guestPath.hasPrefix("/var/mobile/Containers/Shared/")
            || guestPath.hasPrefix("/private/var/mobile/Containers/Shared/") {
            return guestPath
        }
        var relative = Substring(guestPath)
        while relative.hasPrefix("/") { relative = relative.dropFirst() }
        return dataRoot + "/" + relative
    }

    /// noff_resolve_existing_input_path (ModelUseOffload.m:183) — the ONE
    /// resolver --system-file, --prompt-file and --input all go through.
    func resolveExistingInputPath(_ path: String) -> String? {
        if path.isEmpty { return nil }
        if path.hasPrefix("/var/minis/") || path.hasPrefix("/home/") || path.hasPrefix("/tmp/") {
            if let mapped = resolveHostPath(path), existing.contains(mapped) { return mapped }
        }
        if existing.contains(path) { return path }
        return nil
    }

    /// The pre-GH#108 --system-file branch: mapped unconditionally, no existence check.
    func preFixSystemFilePath(_ path: String) -> String? { resolveHostPath(path) }
}

// MARK: - Ported: request bodies

struct AudioAttachment: Equatable { let format: String; let base64Data: String }
struct LLMMessage { var role: String; var content: String; var audios: [AudioAttachment] = [] }

enum InputError: Error, Equatable { case invalid(String) }

/// ModelUseOffloadBridge.parseOpenAIMessages — the input_audio branch (lines 1070-1096).
func parseOpenAIMessages(_ json: String) throws -> [LLMMessage] {
    guard let d = json.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
          let raw = obj["messages"] as? [[String: Any]] else { throw InputError.invalid("no messages") }
    var messages: [LLMMessage] = []
    for msg in raw {
        guard let roleStr = msg["role"] as? String else { continue }
        if roleStr == "tool" || roleStr == "function" { continue }
        let role: String
        switch roleStr {
        case "user": role = "user"
        case "assistant": role = "assistant"
        case "system": continue          // system handled separately via --system
        default: continue
        }
        var content = ""
        var audios: [AudioAttachment] = []
        if let s = msg["content"] as? String {
            content = s
        } else if let parts = msg["content"] as? [[String: Any]] {
            for part in parts {
                let partType = part["type"] as? String
                if partType == "text", let t = part["text"] as? String {
                    content += t
                } else if partType == "input_audio" {
                    guard let audioObj = part["input_audio"] as? [String: Any],
                          let b64 = audioObj["data"] as? String, !b64.isEmpty else {
                        throw InputError.invalid("input_audio block is malformed. Expected {\"type\":\"input_audio\",\"input_audio\":{\"data\":\"<base64>\",\"format\":\"wav\"}}.")
                    }
                    guard Data(base64Encoded: b64, options: [.ignoreUnknownCharacters]) != nil else {
                        throw InputError.invalid("input_audio.data is not valid base64.")
                    }
                    let format = (audioObj["format"] as? String)?.lowercased() ?? "wav"
                    audios.append(AudioAttachment(format: format, base64Data: b64))
                }
            }
        } else { continue }
        guard !content.isEmpty || !audios.isEmpty else { continue }
        var m = LLMMessage(role: role, content: content)
        m.audios = audios
        messages.append(m)
    }
    return messages
}

/// OpenAIProvider.openAIMessageDict (OpenAIProvider.swift:1371-1394), audio + text only.
func openAIMessageDict(_ msg: LLMMessage) -> [String: Any] {
    guard !msg.audios.isEmpty else { return ["role": msg.role, "content": msg.content] }
    var parts: [[String: Any]] = []
    for audio in msg.audios {
        parts.append(["type": "input_audio", "input_audio": ["data": audio.base64Data, "format": audio.format]])
    }
    if !msg.content.isEmpty { parts.append(["type": "text", "text": msg.content]) }
    return ["role": msg.role, "content": parts]
}

/// OpenAIProvider.responsesAPIMessageDict (OpenAIProvider.swift:1403-1428), audio + text only.
func responsesAPIMessageDict(_ msg: LLMMessage) -> [String: Any] {
    if msg.audios.isEmpty { return ["role": msg.role, "content": msg.content] }
    var parts: [[String: Any]] = []
    for audio in msg.audios {
        parts.append(["type": "input_audio", "input_audio": ["data": audio.base64Data, "format": audio.format]])
    }
    if !msg.content.isEmpty { parts.append(["type": "input_text", "text": msg.content]) }
    return ["role": msg.role, "content": parts]
}

/// OpenAIProvider.swift:1113-1121 — chat/completions body's system handling.
func chatCompletionsBody(model: String, messages: [[String: Any]], systemPrompt: String?) -> [String: Any] {
    var allMessages = messages
    if let sys = systemPrompt, !sys.isEmpty {
        allMessages.insert(["role": "system", "content": sys], at: 0)
    }
    return ["model": model, "messages": allMessages]
}

/// OpenAIProvider.swift:1267-1269 — Responses body's system handling.
func responsesBody(model: String, input: [[String: Any]], systemPrompt: String?) -> [String: Any] {
    var body: [String: Any] = ["model": model, "input": input]
    if let sys = systemPrompt, !sys.isEmpty { body["instructions"] = sys }
    return body
}

// MARK: - Ported: Gemini audio_output path

/// ThinkingRuleResolver.geminiThinkingConfig (ThinkingRuleResolver.swift:711-731) — the
/// specialized-suffix short-circuit, plus the gemini-3 family branch it must beat.
func geminiThinkingConfig(modelId: String, levelEnabled: Bool) -> [String: Any] {
    let id = modelId.lowercased()
    let noThinkingSuffixes = ["-tts", "-image", "-embedding", "-vision"]
    if noThinkingSuffixes.contains(where: { id.hasSuffix($0) || id.contains("\($0)-") }) { return [:] }
    if levelEnabled, id.contains("gemini-3") { return ["thinkingLevel": "low", "includeThoughts": true] }
    if !levelEnabled, id.contains("gemini-3") { return ["thinkingLevel": "minimal"] }
    return [:]
}

/// GeminiProvider.buildBody (GeminiProvider.swift:308-310, 360-378) — the parts
/// that depend on the audio_output modality.
func geminiBody(modelId: String, audioOutput: Bool, systemPrompt: String?, levelEnabled: Bool) -> [String: Any] {
    var body: [String: Any] = [:]
    let rejectsSystemInstruction = audioOutput
    if let sys = systemPrompt, !sys.isEmpty, !rejectsSystemInstruction {
        body["systemInstruction"] = ["parts": [["text": sys]]]
    }
    var config: [String: Any] = ["maxOutputTokens": 4096]
    let thinkCfg = geminiThinkingConfig(modelId: modelId, levelEnabled: levelEnabled)
    if !thinkCfg.isEmpty { config["thinkingConfig"] = thinkCfg }
    if audioOutput { config["responseModalities"] = ["AUDIO"] }
    body["generationConfig"] = config
    return body
}

// MARK: - Helpers

func contentParts(_ dict: [String: Any]) -> [[String: Any]] { (dict["content"] as? [[String: Any]]) ?? [] }
func audioPart(_ parts: [[String: Any]]) -> [String: Any]? {
    parts.first { ($0["type"] as? String) == "input_audio" }?["input_audio"] as? [String: Any]
}

// MARK: - [1] No identity-asserting default persona

print("\n[1] --system absent: the default describes the environment, never an identity")
do {
    let r = try! resolveSystemPrompt(CLIArgs(), files: [:], resolve: { _ in nil })
    check("a default IS injected (Responses proxies reject an empty instructions block)", r.injected)
    check("the default does not open with \"You are Minis\"", r.prompt!.contains("You are Minis"), false)
    check("the default says it is NOT the model's identity", r.prompt!.contains("not your identity"))
    check("PRE-FIX: the old default was a bare identity assertion", preFixDefault.hasPrefix("You are Minis"))

    // Explicit empty --system is preserved: no default, and the body carries no system field.
    let empty = try! resolveSystemPrompt(CLIArgs(system: ""), files: [:], resolve: { _ in nil })
    check("--system \"\" is preserved (no default)", !empty.injected && empty.prompt == "")
    let chat = chatCompletionsBody(model: "m", messages: [["role": "user", "content": "hi"]], systemPrompt: empty.prompt)
    check("--system \"\" → chat body has no system message",
          (chat["messages"] as! [[String: Any]]).allSatisfy { ($0["role"] as? String) != "system" })
    let resp = responsesBody(model: "m", input: [], systemPrompt: empty.prompt)
    check("--system \"\" → responses body has no instructions", resp["instructions"] == nil)

    // Explicit --system wins over --system-file (the file is not even resolved).
    var resolved = false
    let both = try! resolveSystemPrompt(CLIArgs(system: "be terse", systemFile: "/var/minis/workspace/s.md"),
                                        files: [:], resolve: { _ in resolved = true; return nil })
    checkEq("--system wins over --system-file", both.prompt, "be terse")
    check("…and --system-file is not resolved at all", resolved, false)
    check("an explicit system is never flagged as injected", both.injected, false)

    // A system role inside the input JSON is ignored: --system is the only channel.
    let msgs = try! parseOpenAIMessages(#"{"messages":[{"role":"system","content":"You are Minis"},{"role":"user","content":"hi"}]}"#)
    check("a system message in --input JSON is dropped (system only via --system)",
          msgs.map(\.role) == ["user"])
}

// MARK: - [2] --system-file and --prompt-file share one resolver

print("\n[2] --system-file resolves through the same path contract as --prompt-file")
do {
    let fs = FakeFS(existing: [
        "/var/mobile/Containers/Data/Application/APP/Documents/alpine-rootfs/data/var/minis/workspace/x.md",
        "/var/mobile/Containers/Data/Application/APP/Documents/alpine-rootfs/data/var/minis/workspace/p.txt",
        "/var/mobile/Containers/Data/Application/APP/Documents/attach/host.md",
    ])
    let sysGuest = "/var/minis/workspace/x.md", promptGuest = "/var/minis/workspace/p.txt"
    let sysHost = fs.resolveExistingInputPath(sysGuest)
    let promptHost = fs.resolveExistingInputPath(promptGuest)
    check("both guest paths map under the fakefs data root",
          sysHost == fs.dataRoot + sysGuest && promptHost == fs.dataRoot + promptGuest)
    check("same directory in, same directory out (one resolver for both flags)",
          (sysHost! as NSString).deletingLastPathComponent == (promptHost! as NSString).deletingLastPathComponent)

    // Through the CLI port: the system file's CONTENTS become the prompt.
    let files = [sysHost!: "# persona from file"]
    let r = try! resolveSystemPrompt(CLIArgs(systemFile: sysGuest), files: files, resolve: fs.resolveExistingInputPath)
    checkEq("--system-file contents are used verbatim", r.prompt, "# persona from file")
    check("…and nothing is injected", r.injected, false)

    // A host-side path (already translated by exec_handler) passes through unchanged.
    let host = "/var/mobile/Containers/Data/Application/APP/Documents/attach/host.md"
    checkEq("an already-host path is returned literally", fs.resolveExistingInputPath(host), host)
    checkEq("noff_resolve_host_path is idempotent for sandbox paths", fs.resolveHostPath(host), host)

    // GH#108: a missing file is reported with the FULL path the caller typed.
    do {
        _ = try resolveSystemPrompt(CLIArgs(systemFile: "/var/minis/workspace/missing/s.txt"), files: [:], resolve: fs.resolveExistingInputPath)
        check("missing system file throws", false)
    } catch let e as CLIError {
        checkEq("missing system file names the full caller path", e,
                .systemFileNotFound("System file not found: /var/minis/workspace/missing/s.txt"))
    } catch { check("unexpected error type", false) }
    check("PRE-GH#108: the old branch mapped unconditionally and never checked existence",
          fs.preFixSystemFilePath("/var/minis/workspace/missing/s.txt") != nil
          && fs.resolveExistingInputPath("/var/minis/workspace/missing/s.txt") == nil)

    check("an empty path resolves to nothing", fs.resolveExistingInputPath("") == nil)
    check("a guest path outside the bind prefixes is tried literally only",
          fs.resolveExistingInputPath("/etc/x.md") == nil)
}

// MARK: - [3] input_audio survives into both request bodies

print("\n[3] input_audio blocks reach both the Chat Completions and Responses bodies")
do {
    let wav = Data("RIFF....WAVEfmt ".utf8).base64EncodedString()
    let input = """
    {"messages":[{"role":"user","content":[
      {"type":"text","text":"transcribe this"},
      {"type":"input_audio","input_audio":{"data":"\(wav)","format":"WAV"}}
    ]}]}
    """
    let msgs = try! parseOpenAIMessages(input)
    checkEq("one user message parsed", msgs.count, 1)
    checkEq("audio attached with lowercased format", msgs[0].audios, [AudioAttachment(format: "wav", base64Data: wav)])

    let chat = openAIMessageDict(msgs[0])
    let chatAudio = audioPart(contentParts(chat))
    check("chat/completions body carries input_audio {data, format}",
          (chatAudio?["data"] as? String) == wav && (chatAudio?["format"] as? String) == "wav")
    check("chat/completions text part is `text`",
          contentParts(chat).contains { ($0["type"] as? String) == "text" })

    let resp = responsesAPIMessageDict(msgs[0])
    let respAudio = audioPart(contentParts(resp))
    check("responses body carries the SAME nested input_audio shape",
          (respAudio?["data"] as? String) == wav && (respAudio?["format"] as? String) == "wav")
    check("responses text part is `input_text`",
          contentParts(resp).contains { ($0["type"] as? String) == "input_text" })

    // Audio-only (no text) still produces a part list, not an empty string body.
    let audioOnly = try! parseOpenAIMessages(#"{"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"\#(wav)"}}]}]}"#)
    checkEq("audio-only message is kept (no text needed)", audioOnly.count, 1)
    checkEq("format defaults to wav", audioOnly[0].audios.first?.format, "wav")
    check("audio-only chat dict has no empty text part",
          !contentParts(openAIMessageDict(audioOnly[0])).contains { ($0["type"] as? String) == "text" })

    // Malformed blocks are a hard error, not a silent drop (the GH#67 regression).
    do {
        _ = try parseOpenAIMessages(#"{"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"format":"wav"}}]}]}"#)
        check("malformed input_audio throws", false)
    } catch let e as InputError {
        check("malformed input_audio is rejected loudly", { if case .invalid(let m) = e { return m.contains("malformed") }; return false }())
    } catch { check("unexpected error", false) }
    do {
        _ = try parseOpenAIMessages(#"{"messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"***not-base64***"}}]}]}"#)
        check("invalid base64 throws", false)
    } catch let e as InputError {
        checkEq("invalid base64 is rejected", e, .invalid("input_audio.data is not valid base64."))
    } catch { check("unexpected error", false) }
}

// MARK: - [4] audio_output model: no thinking, no system instruction (Gemini)

print("\n[4] An audio_output (TTS) model gets no thinking config and no system instruction")
do {
    let tts = geminiBody(modelId: "gemini-3.1-flash-tts-preview", audioOutput: true,
                         systemPrompt: defaultCallingContext, levelEnabled: false)
    let cfg = tts["generationConfig"] as! [String: Any]
    check("responseModalities = [AUDIO]", (cfg["responseModalities"] as? [String]) == ["AUDIO"])
    check("no thinkingConfig for a -tts model", cfg["thinkingConfig"] == nil)
    check("no systemInstruction for an audio_output model (400 otherwise)", tts["systemInstruction"] == nil)

    // The #226 failure: the family rule matched "gemini-3" first and sent thinkingLevel.
    let pre = geminiThinkingConfig(modelId: "gemini-3.1-flash-tts-preview", levelEnabled: true)
    check("even an explicitly enabled level cannot reintroduce thinking on a TTS id", pre.isEmpty)
    check("the suffix rule also matches a mid-id `-tts-`", geminiThinkingConfig(modelId: "gemini-2.5-pro-preview-tts", levelEnabled: false).isEmpty)

    // Control: a text model on the same family keeps both.
    let text = geminiBody(modelId: "gemini-3.1-flash", audioOutput: false,
                          systemPrompt: defaultCallingContext, levelEnabled: false)
    check("a text model keeps systemInstruction", text["systemInstruction"] != nil)
    check("a text model keeps its thinkingConfig",
          (text["generationConfig"] as! [String: Any])["thinkingConfig"] != nil)
}

// MARK: - [5] Drift guards

print("\n[5] Shipping sources match these ports")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let cli = source("NativeOffloads/ModelUseOffload.m")
let bridge = source("NativeOffloads/ModelUseOffloadBridge.swift")
let oai = source("Providers/OpenAI/OpenAIProvider.swift")
let gem = source("Providers/Gemini/GeminiProvider.swift")
let think = source("Providers/Thinking/ThinkingRuleResolver.swift")
if [cli, bridge, oai, gem, think].contains(where: { $0.isEmpty }) {
    print("  ⏭  a source is not readable"); failures += 1
} else {
    check("the CLI default no longer asserts an identity",
          !cli.contains("systemPrompt = @\"You are Minis"))
    check("the CLI default is the calling-context text",
          cli.contains("@\"You are being invoked as a sub-agent inside an app called Minis. \""))
    check("…and says it is not the model's identity", cli.contains("This is the calling environment, not your identity"))
    check("the default is only injected when systemPrompt is nil", cli.contains("if (!systemPrompt) {\n        systemPrompt = @\"You are being invoked"))
    check("--system-file is read only when --system is absent", cli.contains("if (systemFile && !systemPrompt) {"))
    check("--system-file uses noff_resolve_existing_input_path", cli.contains("NSString *hostPath = noff_resolve_existing_input_path(systemFile);"))
    check("--prompt-file uses the same resolver", cli.contains("NSString *hostPath = noff_resolve_existing_input_path(promptFile);"))
    check("--input uses the same resolver", cli.contains("NSString *hostPath = noff_resolve_existing_input_path(inputPath);"))
    check("a missing system file names the caller's path", cli.contains("@\"System file not found: %@\", systemFile"))
    check("the resolver tries bind prefixes then the literal path",
          cli.contains("if ([path hasPrefix:@\"/var/minis/\"] || [path hasPrefix:@\"/home/\"] || [path hasPrefix:@\"/tmp/\"]) {\n        NSString *mapped = noff_resolve_host_path(path);"))
    check("system role in input JSON is skipped by the bridge", bridge.contains("case \"system\": continue // system handled separately via --system"))
    check("the bridge parses input_audio and rejects malformed blocks",
          bridge.contains("} else if partType == \"input_audio\" {") && bridge.contains("input_audio block is malformed."))
    check("chat body inserts system only when non-empty",
          oai.contains("if let sys = systemPrompt, !sys.isEmpty {\n            allMessages.insert([\"role\": \"system\", \"content\": sys], at: 0)"))
    check("responses body sets instructions only when non-empty",
          oai.contains("if let sys = systemPrompt, !sys.isEmpty {\n            body[\"instructions\"] = sys"))
    check("chat dict serializes input_audio", oai.contains("// GH#67: official Chat Completions audio-input shape."))
    check("responses dict serializes input_audio with the same nested shape",
          oai.contains("// GH#67: the Responses API keeps the SAME nested input_audio shape"))
    check("Gemini rejects systemInstruction for audio_output models",
          gem.contains("model.capabilities.supportedModalities.contains(.audioOutput)\n    }")
          && gem.contains("if let sys = systemPrompt, !sys.isEmpty, !rejectsSystemInstruction {"))
    check("Gemini audio_output → responseModalities AUDIO", gem.contains("config[\"responseModalities\"] = [\"AUDIO\"]"))
    let suffixIdx = think.range(of: "let noThinkingSuffixes = [\"-tts\", \"-image\", \"-embedding\", \"-vision\"]")!.lowerBound
    let levelIdx = think.range(of: "if level.isEnabled {", range: suffixIdx..<think.endIndex)!.lowerBound
    check("the -tts short-circuit is hoisted above the level/family branches", suffixIdx < levelIdx)
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
