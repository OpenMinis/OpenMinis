// Tests for [T-ios-listsessions-perf] Phase 6 — provider-side send-path work
// that was being redone every turn.
//
// Standalone (`swift ProviderSendPathCacheTests.swift`) like its neighbours:
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

// MARK: - 1. bodyMentionsTools (mirrors OAuthHTTPClient.RequestBodyPatcher)

func bodyMentionsTools(_ body: Data) -> Bool {
    let needle = Array("\"tools\"".utf8)
    let n = needle.count
    guard body.count >= n else { return false }
    return body.withUnsafeBytes { raw -> Bool in
        guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return true }
        let limit = body.count - n
        var i = 0
        while i <= limit {
            if base[i] == needle[0] {
                var k = 1
                while k < n, base[i + k] == needle[k] { k += 1 }
                if k == n { return true }
            }
            i += 1
        }
        return false
    }
}

print("\n▶️  bodyMentionsTools: never a false negative")

/// The property that matters: if JSONSerialization would find a top-level
/// "tools" key, the byte scan MUST say true — otherwise the patcher is skipped
/// and a tool definition silently loses its cache_control / eager streaming.
func hasToolsKey(_ body: Data) -> Bool {
    guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return false }
    return json["tools"] != nil
}

let bodies: [(String, String)] = [
    ("no tools", #"{"model":"claude-opus-5","messages":[{"role":"user","content":"hi"}]}"#),
    ("empty object", #"{}"#),
    ("with tools", #"{"model":"m","tools":[{"name":"bash"}],"messages":[]}"#),
    ("tools first", #"{"tools":[{"name":"read"}],"model":"m"}"#),
    ("tools empty array", #"{"model":"m","tools":[]}"#),
    ("tools last", #"{"model":"m","messages":[],"tools":[{"name":"x"}]}"#),
    ("word 'tools' in message text only",
     #"{"model":"m","messages":[{"role":"user","content":"what tools do you have"}]}"#),
    ("quoted tools inside content (false positive is allowed)",
     #"{"model":"m","messages":[{"role":"user","content":"the \"tools\" key"}]}"#),
    ("unicode content", #"{"model":"m","messages":[{"role":"user","content":"你好 🚀"}],"tools":[{"name":"t"}]}"#),
    ("large body with tools at the end",
     "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\""
        + String(repeating: "x", count: 50000) + "\"}],\"tools\":[{\"name\":\"t\"}]}"),
]

for (name, raw) in bodies {
    let data = Data(raw.utf8)
    let scan = bodyMentionsTools(data)
    let real = hasToolsKey(data)
    if real && !scan {
        check("FALSE NEGATIVE on '\(name)' — patcher would be skipped", false)
    } else {
        let note = (scan && !real) ? " (false positive, falls through to the parse — fine)" : ""
        print("  ✅ \(name): scan=\(scan) actual=\(real)\(note)")
    }
}

// The tool-less request is the one this exists for: it must skip.
check("a plain request with no tools skips the parse",
      !bodyMentionsTools(Data(#"{"model":"m","messages":[{"role":"user","content":"hi"}]}"#.utf8)))

print("\n▶️  edge cases")
check("empty body", !bodyMentionsTools(Data()))
check("body shorter than the needle", !bodyMentionsTools(Data("{}".utf8)))
check("needle exactly at the end", bodyMentionsTools(Data(#"{"a":1,"tools""#.utf8)))
check("partial needle only", !bodyMentionsTools(Data(#"{"tool":1}"#.utf8)))
check("needle at offset 0", bodyMentionsTools(Data(#""tools":[]"#.utf8)))

// MARK: - 2. The downscale cache's NSNull memo
//
// downscaleForAnthropic returns nil for "already small enough". Without a
// distinct marker for that, every already-small image misses the cache forever
// and pays a full UIImage decode on every turn — which is most of the cost the
// cache is meant to remove.

print("\n▶️  downscale cache: a nil result must still be memoized")

final class CacheModel {
    private var store: [Int: Any] = [:]
    var computes = 0

    /// Mirrors the real lookup: NSNull stands for "computed, result was nil".
    func downscale(_ key: Int, compute: () -> String?) -> String? {
        if let hit = store[key] { return hit as? String }
        computes += 1
        let result = compute()
        store[key] = result ?? (NSNull() as Any)
        return result
    }
}

let c = CacheModel()
// A large image that DOES downscale.
checkEq("first call computes", c.downscale(1) { "downscaled" }, "downscaled")
checkEq("second call is cached", c.downscale(1) { "SHOULD NOT RUN" }, "downscaled")
checkEq("one compute so far", c.computes, 1)

// A small image that needs no downscale — the nil case.
checkEq("small image computes once", c.downscale(2) { nil }, nil)
checkEq("two computes", c.computes, 2)
checkEq("small image is cached as nil", c.downscale(2) { "SHOULD NOT RUN" }, nil)
checkEq("still two computes — the nil was memoized", c.computes, 2)

// Over a 30-turn conversation carrying 3 images, one of them already small.
let conv = CacheModel()
for _ in 0..<30 {
    _ = conv.downscale(10) { "big-a" }
    _ = conv.downscale(11) { "big-b" }
    _ = conv.downscale(12) { nil }          // already small
}
checkEq("30 turns x 3 images = 3 computes, not 90", conv.computes, 3)
print("  📊 legacy: 90 decode+redraw+JPEG cycles  |  cached: 3")

// MARK: - 3. The log encoder is used once, not three times

print("\n▶️  logRequestParameter encodes the parameter once")
let providerPath = "../../Providers/Anthropic/AnthropicProvider.swift"
if let src = try? String(contentsOfFile: providerPath, encoding: .utf8),
   let start = src.range(of: "func logRequestParameter"),
   let end = src.range(of: "/// Log a non-streaming API response", range: start.upperBound..<src.endIndex) {
    let body = String(src[start.lowerBound..<end.lowerBound])
    let encodes = body.components(separatedBy: "JSONEncoder().encode(parameter)").count - 1
    checkEq("JSONEncoder().encode(parameter) appears once", encodes, 1)
    check("and the result is reused via a local", body.contains("encodedParameter"))
    // It must stay DEBUG-only: in Release none of this should exist at all.
    check("the function is still #if DEBUG gated",
          src.range(of: "#if DEBUG", range: src.startIndex..<start.lowerBound) != nil)
} else {
    check("could read AnthropicProvider.swift", false)
}

print("\n" + String(repeating: "─", count: 60))
if failures == 0 {
    print("✅ All provider send-path tests passed")
    exit(0)
} else {
    print("❌ \(failures) failure(s)")
    exit(1)
}
