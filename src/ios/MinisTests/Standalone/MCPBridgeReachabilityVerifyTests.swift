// Tests for [T-mcp-bridge-unreachable] (commit f7b43fe2c, issue #380) — what
// the new bridge-file "read-back verification" can and cannot detect.
//
// #380: a guest-created real directory at the fakefs `var/minis/mcp-servers`
// path shadows the symlink into the App Group, so the host writes the OAuth
// bridge file where the sandbox never looks. f7b43fe2c made
// `MCPOAuthController.materializeBridge` throw unless the file "can be read
// back", documenting that "a write that succeeded into a directory the guest
// cannot reach is the #380 failure mode, and it is indistinguishable from
// success unless the bytes are verified here".
//
// But the read-back uses the SAME URL as the write —
// `bridgeFileURL(server:)`, i.e. `<AppGroup>/MinisConfig/mcp-servers/oauth/…`
// — so it succeeds whenever the write did. It never looks at the guest-visible
// path, so in exactly the #380 state (placeholder dir squatting the link) the
// authorization is still reported as successful.
//
// Invariant pinned: the verification must observe the file through the path
// the guest resolves (or at least assert that path is a symlink/bind to the
// persistent dir), not through the host path it just wrote.
//
// Standalone: `swift MCPBridgeReachabilityVerifyTests.swift`.

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

// MARK: - Real filesystem reproduction of the #380 state

let fm = FileManager.default
let tmp = fm.temporaryDirectory.appendingPathComponent("mcp-bridge-\(UUID().uuidString)")
let persistent = tmp.appendingPathComponent("AppGroup/MinisConfig/mcp-servers", isDirectory: true)
let guestLink = tmp.appendingPathComponent("fakefs/data/var/minis/mcp-servers", isDirectory: true)
try! fm.createDirectory(at: persistent, withIntermediateDirectories: true)
try! fm.createDirectory(at: guestLink.deletingLastPathComponent(), withIntermediateDirectories: true)
defer { try? fm.removeItem(at: tmp) }

// Healthy wiring: the guest path is a symlink into the App Group.
try! fm.createSymbolicLink(at: guestLink, withDestinationURL: persistent)

func materializeShipped(token: String) -> Bool {
    // Mirrors materializeBridge: write to the host URL, read back the host URL.
    let url = persistent.appendingPathComponent("oauth/srv.json")
    do {
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: ["access_token": token]).write(to: url, options: .atomic)
        let back = try Data(contentsOf: url)
        let obj = try JSONSerialization.jsonObject(with: back) as? [String: Any]
        return (obj?["access_token"] as? String) == token
    } catch { return false }
}
func guestSees(token: String) -> Bool {
    let url = guestLink.appendingPathComponent("oauth/srv.json")
    guard let d = try? Data(contentsOf: url),
          let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return false }
    return (o["access_token"] as? String) == token
}

print("\n▶️  healthy link")
check("shipped verify passes", materializeShipped(token: "t1"))
check("guest sees the token", guestSees(token: "t1"))

print("\n▶️  #380: a guest-created placeholder dir replaces the link")
try! fm.removeItem(at: guestLink)
try! fm.createDirectory(at: guestLink, withIntermediateDirectories: true)
let verified = materializeShipped(token: "t2")
check("the guest can NOT see the new token (precondition: the #380 state)", !guestSees(token: "t2"))
check("the shipped read-back still reports success (demonstrates the verification is a tautology)", verified)

// What a reachability check has to look at.
func guestPathIsLinked() -> Bool {
    guard let attrs = try? fm.attributesOfItem(atPath: guestLink.path),
          attrs[.type] as? FileAttributeType == .typeSymbolicLink,
          let dest = try? fm.destinationOfSymbolicLink(atPath: guestLink.path) else { return false }
    return URL(fileURLWithPath: dest).standardizedFileURL == persistent.standardizedFileURL
}
check("a guest-side link check detects the shadow", !guestPathIsLinked())

// MARK: - Source invariant

print("\n▶️  source invariants (MCPOAuthController.materializeBridge)")
let oauth = source("Agent/Session/MCPOAuthController.swift")
if let r = oauth.range(of: "private static func materializeBridge(") {
    let fn = String(oauth[r.lowerBound...].prefix(4000))
    check("precondition: the read-back reads the same `url` it wrote",
          fn.contains("try data.write(to: url") && fn.contains("Data(contentsOf: url)"))
    let checksGuestSide = fn.contains("minisMcpServersLinuxDir") || fn.contains("destinationOfSymbolicLink")
        || fn.contains("lstat(") || fn.contains("dataPath")
    check("verification observes the guest-visible path (BUG if this fails: #380 still reports success)",
          checksGuestSide)
}

print(failures == 0 ? "\n✅ ALL PASS" : "\n❌ \(failures) FAILED")
exit(failures == 0 ? 0 : 1)
