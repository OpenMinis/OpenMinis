// [T-copilot-per-request-headers] Derivation of Copilot's per-request headers.
//
// Run: swift CopilotPerRequestHeadersTests.swift
//
// These headers were previously computed by a function with NO callers, so
// neither `X-Initiator` nor `Copilot-Vision-Request` ever reached the wire.
// The logic below is a mirror of `CopilotConstants.perRequestHeaders(forBody:)`;
// the last section asserts the shipping source still matches it, which is what
// catches the wiring being removed again.

import Foundation

// MARK: - Mirror of the shipping implementation

func requestHeaders(isAgentTurn: Bool, hasImages: Bool) -> [String: String] {
    var h: [String: String] = [:]
    h["X-Initiator"] = isAgentTurn ? "agent" : "user"
    h["X-Request-Id"] = UUID().uuidString
    if hasImages { h["Copilot-Vision-Request"] = "true" }
    return h
}

func perRequestHeaders(forBody body: [String: Any]) -> [String: String] {
    let messages = (body["messages"] as? [[String: Any]])
        ?? (body["input"] as? [[String: Any]])
        ?? []

    let isAgentTurn = (messages.last?["role"] as? String) == "tool"

    let hasImages = messages.contains { msg in
        guard let parts = msg["content"] as? [[String: Any]] else { return false }
        return parts.contains { part in
            let t = part["type"] as? String
            return t == "image_url" || t == "input_image"
        }
    }

    return requestHeaders(isAgentTurn: isAgentTurn, hasImages: hasImages)
}

// MARK: - Harness

var failures = 0
func check(_ label: String, _ cond: Bool, _ expected: Bool = true) {
    if cond == expected {
        print("  ✅ \(label)")
    } else {
        print("  ❌ \(label) — expected \(expected), got \(cond)")
        failures += 1
    }
}

func textMsg(_ role: String, _ text: String) -> [String: Any] {
    ["role": role, "content": text]
}

func imageMsg(_ role: String, type: String) -> [String: Any] {
    ["role": role, "content": [["type": type, "image_url": ["url": "data:..."]]]]
}

// MARK: - 1. X-Initiator

print("\n[1] X-Initiator distinguishes a human turn from an agent turn")

let humanTurn: [String: Any] = ["messages": [
    textMsg("system", "you are helpful"),
    textMsg("user", "hello"),
]]
check("a user-authored turn is `user`",
      perRequestHeaders(forBody: humanTurn)["X-Initiator"] == "user")

// The agent loop feeds tool results back as the LAST message — that is the
// signal, and mislabelling it as human is the account-flagging risk.
let agentTurn: [String: Any] = ["messages": [
    textMsg("user", "list the files"),
    ["role": "assistant", "content": "", "tool_calls": [["id": "t1"]]],
    ["role": "tool", "tool_call_id": "t1", "content": "a.txt\nb.txt"],
]]
check("a tool-result continuation is `agent`",
      perRequestHeaders(forBody: agentTurn)["X-Initiator"] == "agent")

// A tool result EARLIER in history with a fresh user message after it is a
// human turn again — only the last message decides.
let humanAfterTools: [String: Any] = ["messages": [
    ["role": "tool", "tool_call_id": "t1", "content": "done"],
    textMsg("assistant", "I listed them."),
    textMsg("user", "now delete a.txt"),
]]
check("history containing tool results is still `user` when the user spoke last",
      perRequestHeaders(forBody: humanAfterTools)["X-Initiator"] == "user")

check("X-Initiator is always present",
      perRequestHeaders(forBody: ["messages": []])["X-Initiator"] != nil)

// MARK: - 2. Both message-list shapes

print("\n[2] Both request shapes are understood")

let responsesShape: [String: Any] = ["input": [
    ["role": "tool", "tool_call_id": "t1", "content": "ok"],
]]
check("responses API `input` is read, not just `messages`",
      perRequestHeaders(forBody: responsesShape)["X-Initiator"] == "agent")

check("an unrecognised body shape degrades to `user`, not a crash",
      perRequestHeaders(forBody: ["prompt": "legacy"])["X-Initiator"] == "user")

// MARK: - 3. Copilot-Vision-Request

print("\n[3] Copilot-Vision-Request is set only when images are attached")

check("absent for a text-only turn",
      perRequestHeaders(forBody: humanTurn)["Copilot-Vision-Request"] == nil)

let chatImage: [String: Any] = ["messages": [imageMsg("user", type: "image_url")]]
check("set for chat-completions `image_url`",
      perRequestHeaders(forBody: chatImage)["Copilot-Vision-Request"] == "true")

let responsesImage: [String: Any] = ["input": [imageMsg("user", type: "input_image")]]
check("set for responses-API `input_image`",
      perRequestHeaders(forBody: responsesImage)["Copilot-Vision-Request"] == "true")

// An image anywhere in history still means the request carries an image.
let imageInHistory: [String: Any] = ["messages": [
    imageMsg("user", type: "image_url"),
    textMsg("assistant", "That is a cat."),
    textMsg("user", "what breed?"),
]]
check("set when the image is earlier in the conversation",
      perRequestHeaders(forBody: imageInHistory)["Copilot-Vision-Request"] == "true")

// String content must not be mistaken for parts.
let stringContent: [String: Any] = ["messages": [textMsg("user", "image_url")]]
check("plain text mentioning image_url does NOT set the flag",
      perRequestHeaders(forBody: stringContent)["Copilot-Vision-Request"] == nil)

// MARK: - 4. X-Request-Id

print("\n[4] X-Request-Id is per-request")

let a = perRequestHeaders(forBody: humanTurn)["X-Request-Id"]
let b = perRequestHeaders(forBody: humanTurn)["X-Request-Id"]
check("present", a != nil)
check("differs between two requests", a != b)

// MARK: - 5. No static headers are duplicated here

print("\n[5] Static headers stay on extraHeaders, not duplicated per request")

let h = perRequestHeaders(forBody: humanTurn)
check("User-Agent is not re-sent per request", h["User-Agent"] == nil)
check("Editor-Version is not re-sent per request", h["Editor-Version"] == nil)
check("Copilot-Integration-Id is not re-sent per request", h["Copilot-Integration-Id"] == nil)

// MARK: - 6. The shipping source is actually wired up

print("\n[6] The shipping source still wires this to the request path")

func sourceOf(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Standalone
        .deletingLastPathComponent()   // MinisTests
        .deletingLastPathComponent()   // ios
    let url = here.appendingPathComponent(rel)
    return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
}

let constants = sourceOf("Providers/Copilot/CopilotConstants.swift")
check("CopilotConstants exposes perRequestHeaders(forBody:)",
      constants.contains("static func perRequestHeaders(forBody body: [String: Any])"))
check("it reads both `messages` and `input`",
      constants.contains("body[\"messages\"]") && constants.contains("body[\"input\"]"))
check("it derives the agent turn from a trailing tool role",
      constants.contains("== \"tool\""))

let factory = sourceOf("Providers/LLMProviderFactory.swift")
check("makeCopilotProvider assigns perRequestHeaders",
      factory.contains("provider.perRequestHeaders"))

let openai = sourceOf("Providers/OpenAI/OpenAIProvider.swift")
check("OpenAIProvider declares the hook",
      openai.contains("var perRequestHeaders:"))
// The three request paths Copilot can travel: the agent loop (streamRaw) and
// the two builders. A dropped call site here is the exact regression that made
// these headers dead code in the first place.
let callSites = openai.components(separatedBy: "perRequestHeaders?(").count - 1
check("the hook is applied at all three request sites (found \(callSites))",
      callSites == 3)

// MARK: - 7. Backup covers the Copilot token

print("\n[7] The Copilot OAuth token is included in backups")

let backup = sourceOf("Agent/Backup/BackupSecrets.swift")
check("BackupSecrets handles .githubCopilot",
      backup.contains("case .githubCopilot"))
check("it reads CopilotTokenStorage",
      backup.contains("CopilotTokenStorage.self"))
// The `default:` is what silently swallowed Copilot — the compiler could not
// flag the missing case. Enumerating the remainder restores that protection.
check("the switch no longer ends in a catch-all default",
      backup.contains("case .antigravity, .openAIResponses, .openRouter, .unsupported:"))

// MARK: - 8. Single-flight defer ordering

print("\n[8] Session-token single-flight clears its slot in the right scope")

let oauth = sourceOf("Providers/Copilot/OAuth/CopilotOAuthManager.swift")
// The slot must be assigned, THEN deferred-cleared in the enclosing scope. With
// the defer inside the Task body it could clear an empty slot and leave a
// completed task parked in the dictionary, serving a stale token forever.
if let assignRange = oauth.range(of: "inFlightSession[instanceId] = task"),
   let deferRange = oauth.range(of: "defer { inFlightSession[instanceId] = nil }") {
    check("the defer follows the assignment",
          assignRange.upperBound <= deferRange.lowerBound)
} else {
    check("assignment and defer both present", false)
}
check("the defer is no longer inside the Task body",
      oauth.contains("defer { self.inFlightSession[instanceId] = nil }"), false)

// MARK: - Result

print("")
if failures == 0 {
    print("✅ ALL CHECKS PASSED")
} else {
    print("❌ \(failures) FAILURE(S)")
    exit(1)
}
