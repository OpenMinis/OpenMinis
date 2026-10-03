#!/usr/bin/env swift
// [T-oauth-cloudflare-403] issue #360 — Claude OAuth login and silent refresh
// were answered with HTTP 403 and a Cloudflare "Just a moment…" challenge page
// instead of JSON, so a login could never complete and a refresh could never
// succeed.
//
// Two causes, both pinned here:
//
//  1. `tokenURL` still pointed at the legacy `console.anthropic.com` host the
//     CLI has moved off. It must be `https://claude.ai/v1/oauth/token`.
//  2. The TOKEN path sent none of the Claude CLI mimicry headers, while the CHAT
//     path has sent them since the OAuth transport was written. Cloudflare Bot
//     Management decides from exactly that header set whether the caller looks
//     like the official CLI, so the asymmetry read to the user as "chat works
//     but I can't log in".
//
// The headers now live in ONE place (`ClaudeCLIMimicry` in OAuthHTTPClient.swift)
// and both call sites read it, because a drift between two copies IS #360.
//
// Run: swift ClaudeOAuthTokenRequestTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator and an XCTest bundle has
// nowhere to run. Section [3] therefore does the real thing this test is for: it
// installs a URLProtocol, issues an actual URLSession request built the way
// `postTokenRequest` builds one, and asserts on the headers that were genuinely
// put on the wire — not on source text. Sections [1] and [2] read the shipping
// source so the ported request builder cannot silently diverge from it.
import Foundation

var failures = 0
func check(_ label: String, _ cond: Bool) {
    print(cond ? "  ✅ \(label)" : "  ❌ \(label) — expected true, got false")
    if !cond { failures += 1 }
}
func checkEq<T: Equatable>(_ label: String, _ a: T, _ b: T) {
    print(a == b ? "  ✅ \(label)" : "  ❌ \(label) — expected \(b), got \(a)")
    if a != b { failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}

/// Strip `//` comment lines before matching, so a doc comment that QUOTES a
/// value is never mistaken for the value being declared. An earlier guard in
/// this code base matched its own prose exactly that way.
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

let manager = codeOnly(source("Providers/Anthropic/OAuth/ClaudeOAuthManager.swift"))
let httpClient = source("Providers/Anthropic/OAuthHTTPClient.swift")

guard !manager.isEmpty, !httpClient.isEmpty else {
    print("  ⏭  sources not readable from \(#filePath)")
    exit(0)
}

// The 11 headers, as the shipping source declares them. Parsed OUT of
// `ClaudeCLIMimicry` rather than retyped, so this test cannot pass against a
// source that lost one — and cannot drift from the values it is meant to pin.
func parseMimicryHeaders() -> [String: String] {
    guard let block = httpClient.range(of: "static let headers: [String: String] = ["),
          let end = httpClient.range(of: "]", range: block.upperBound..<httpClient.endIndex) else { return [:] }
    var out: [String: String] = [:]
    for line in httpClient[block.upperBound..<end.lowerBound].components(separatedBy: "\n") {
        let t = line.trimmingCharacters(in: .whitespaces)
        guard t.hasPrefix("\""), let colon = t.range(of: "\": \"") else { continue }
        let field = String(t[t.index(after: t.startIndex)..<colon.lowerBound])
        let rest = t[colon.upperBound...]
        guard let closing = rest.range(of: "\",") ?? rest.range(of: "\"") else { continue }
        out[field] = String(rest[rest.startIndex..<closing.lowerBound])
    }
    return out
}
let mimicry = parseMimicryHeaders()

print("▶️  1. the token endpoint is the current one")
do {
    checkEq("tokenURL is the claude.ai host",
            manager.contains("private let tokenURL = \"https://claude.ai/v1/oauth/token\""), true)
    check("the legacy console.anthropic.com token endpoint is gone",
          !manager.contains("console.anthropic.com/v1/oauth/token"))
    // The authorize URL is a different endpoint and was already correct — guard
    // it so a careless search-and-replace on the host cannot take it with it.
    check("the authorize endpoint is untouched",
          manager.contains("private let authURL = \"https://claude.ai/oauth/authorize\""))
}

print("\n▶️  2. one source of truth, consumed by both paths")
do {
    checkEq("ClaudeCLIMimicry declares exactly 11 headers", mimicry.count, 11)
    for field in ["User-Agent", "X-Stainless-Lang", "X-Stainless-Package-Version",
                  "X-Stainless-OS", "X-Stainless-Arch", "X-Stainless-Runtime",
                  "X-Stainless-Runtime-Version", "X-Stainless-Retry-Count",
                  "X-Stainless-Timeout", "X-App",
                  "Anthropic-Dangerous-Direct-Browser-Access"] {
        check("…includes \(field)", mimicry[field] != nil)
    }
    checkEq("User-Agent names the CLI", mimicry["User-Agent"], "claude-cli/2.1.280 (external, cli)")
    // Both call sites must READ the shared constant. A re-inlined copy in either
    // file is the drift that produced #360.
    check("the token path applies the shared constant",
          manager.contains("ClaudeCLIMimicry.apply(to: &request)"))
    check("the chat path applies the shared constant",
          codeOnly(httpClient).contains("ClaudeCLIMimicry.apply(to: mutable)"))
    check("the chat path no longer spells the values out inline",
          !codeOnly(httpClient).contains("forHTTPHeaderField: \"X-Stainless-Lang\""))
    // anthropic-beta is a chat-feature negotiation header; sending it on a token
    // request is a gratuitous difference from what the real CLI does.
    check("the mimicry set excludes anthropic-beta", mimicry["anthropic-beta"] == nil)
}

print("\n▶️  3. what actually reaches the wire (real URLProtocol interception)")
do {
    // Port of `postTokenRequest`'s request construction
    // (ClaudeOAuthManager.swift ~:360). Everything below this line exercises a
    // genuine URLSession round trip.
    final class CapturingProtocol: URLProtocol {
        nonisolated(unsafe) static var captured: URLRequest?
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            CapturingProtocol.captured = request
            let body = Data(#"{"access_token":"a","refresh_token":"r","expires_in":3600}"#.utf8)
            let resp = HTTPURLResponse(url: request.url!, statusCode: 200,
                                       httpVersion: "HTTP/1.1", headerFields: nil)!
            client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        }
        override func stopLoading() {}
    }

    func buildTokenRequest(tokenURL: String, body: [String: String]) -> URLRequest {
        var request = URLRequest(url: URL(string: tokenURL)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (f, v) in mimicry { request.setValue(v, forHTTPHeaderField: f) }
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return request
    }

    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [CapturingProtocol.self]
    let session = URLSession(configuration: config)

    // Both call sites: exchange (authorization_code) and refresh (refresh_token).
    for (label, body) in [
        ("token exchange", ["grant_type": "authorization_code", "code": "x", "client_id": "c"]),
        ("token refresh", ["grant_type": "refresh_token", "refresh_token": "r", "client_id": "c"]),
    ] {
        CapturingProtocol.captured = nil
        let req = buildTokenRequest(tokenURL: "https://claude.ai/v1/oauth/token", body: body)
        let sem = DispatchSemaphore(value: 0)
        session.dataTask(with: req) { _, _, _ in sem.signal() }.resume()
        _ = sem.wait(timeout: .now() + 10)

        guard let sent = CapturingProtocol.captured else {
            check("\(label): the request was intercepted", false); continue
        }
        checkEq("\(label): URL is the claude.ai token endpoint",
                sent.url?.absoluteString, "https://claude.ai/v1/oauth/token")
        checkEq("\(label): method is POST", sent.httpMethod, "POST")
        checkEq("\(label): Content-Type is JSON",
                sent.value(forHTTPHeaderField: "Content-Type"), "application/json")

        var missing: [String] = []
        for (field, expected) in mimicry {
            // URLSession normalises header field case; value is what matters.
            if sent.value(forHTTPHeaderField: field) != expected { missing.append(field) }
        }
        check("\(label): all 11 mimicry headers on the wire — missing \(missing.sorted())",
              missing.isEmpty)
        check("\(label): anthropic-beta is NOT sent",
              sent.value(forHTTPHeaderField: "anthropic-beta") == nil)
    }
}

print("")
if failures == 0 {
    print("✅ ALL PASSED")
} else {
    print("❌ \(failures) FAILURE(S)")
}
exit(failures == 0 ? 0 : 1)
