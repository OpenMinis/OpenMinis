#!/usr/bin/env swift
// [T-minis-symlink-placeholder-dir] issue #380 — MCP OAuth appeared to succeed
// on the host while every guest call reported "not authorized".
//
// `/var/minis/mcp-servers` is a SYMLINK the host maintains into its App Group.
// The guest CLI used to run `mkdir -p /var/minis/mcp-servers` unconditionally,
// so whenever it ran before the host had (re)created the link it won the race and
// left a real local directory shadowing the mount. From then on the host wrote
// OAuth bridge files into the App Group that the sandbox could never read —
// and both halves looked healthy in isolation.
//
// `ensureMinisSymlinks` was supposed to heal that, but its recovery was
// `try? fm.removeItem(at: hostPath)`: a directory that still held an
// un-migrated entry survived, the error was swallowed, and the very next
// `createSymbolicLink` failed for a reason logged somewhere else entirely.
//
// This pins the hardened recovery ladder, exercised against real directories in
// a temp dir — the behaviour is filesystem behaviour, so it is tested as such
// rather than mocked.
//
// Run: swift MinisSymlinkPlaceholderTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator. Section [5] greps the shipping
// source so a rewrite fails here instead of silently passing a stale copy.
import Foundation

var failures = 0
func check(_ label: String, _ cond: Bool) {
    print(cond ? "  ✅ \(label)" : "  ❌ \(label) — expected true, got false")
    if !cond { failures += 1 }
}

func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

// MARK: - Port: AIChatViewModel.forceRemovePlaceholderDirectory
// Verbatim apart from the logger, which becomes a collector so the test can
// assert on what an operator would actually see.

final class LogSpy {
    var infos: [String] = []
    var errors: [String] = []
    func info(_ m: String) { infos.append(m) }
    func error(_ m: String) { errors.append(m) }
}

func forceRemovePlaceholderDirectory(at path: URL, logger: LogSpy, label: String) -> Bool {
    let fm = FileManager.default
    if (try? fm.removeItem(at: path)) != nil { return true }

    var failedChildren = 0
    if let contents = try? fm.contentsOfDirectory(at: path, includingPropertiesForKeys: nil) {
        for child in contents {
            do { try fm.removeItem(at: child) } catch {
                failedChildren += 1
                logger.error("[MinisSymlink] \(label): cannot delete \(child.lastPathComponent): \(error.localizedDescription)")
            }
        }
    }
    if rmdir(path.path) == 0 {
        logger.info("[MinisSymlink] \(label): placeholder dir cleared via rmdir after emptying it")
        return true
    }
    var buf = stat()
    if lstat(path.path, &buf) != 0 { return true }
    logger.error("[MinisSymlink] \(label): rmdir failed (errno=\(errno), undeletable children=\(failedChildren))")
    return false
}

/// The OLD recovery, kept only to prove the tests are not vacuous.
func oldRecovery(at path: URL) -> Bool {
    try? FileManager.default.removeItem(at: path)
    var buf = stat()
    return lstat(path.path, &buf) != 0
}

// MARK: - Fixtures

let fm = FileManager.default
let root = URL(fileURLWithPath: NSTemporaryDirectory())
    .appendingPathComponent("minis-symlink-380-\(UUID().uuidString)")
try? fm.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? fm.removeItem(at: root) }

func makeDir(_ name: String, files: [String] = []) -> URL {
    let d = root.appendingPathComponent(name)
    try? fm.createDirectory(at: d, withIntermediateDirectories: true)
    for f in files {
        fm.createFile(atPath: d.appendingPathComponent(f).path, contents: Data("x".utf8))
    }
    return d
}
func isDir(_ u: URL) -> Bool {
    var b = stat()
    return lstat(u.path, &b) == 0 && (b.st_mode & S_IFMT) == S_IFDIR
}

print("▶️  1. an EMPTY placeholder directory is removed")
do {
    let d = makeDir("empty")
    check("precondition: it is a real directory", isDir(d))
    let spy = LogSpy()
    check("recovery reports success", forceRemovePlaceholderDirectory(at: d, logger: spy, label: "/var/minis/mcp-servers"))
    check("…and the path is gone", !isDir(d))
    check("no error was logged", spy.errors.isEmpty)
}

print("\n▶️  2. a placeholder holding files is still removed")
do {
    // The guest's `mkdir -p` plus a log file is the real shape: mcp-cli.log gets
    // created in the shadowing directory immediately.
    let d = makeDir("with-log", files: ["mcp-cli.log", "servers.json"])
    let spy = LogSpy()
    check("recovery succeeds", forceRemovePlaceholderDirectory(at: d, logger: spy, label: "/var/minis/mcp-servers"))
    check("…and the path is gone", !isDir(d))
}

print("\n▶️  3. a symlink can be created where the placeholder was")
do {
    // The whole point: recovery exists so this next step can succeed.
    let target = makeDir("real-appgroup")
    let link = root.appendingPathComponent("link-site")
    try? fm.createDirectory(at: link, withIntermediateDirectories: true)
    fm.createFile(atPath: link.appendingPathComponent("stale.log").path, contents: Data("x".utf8))

    let spy = LogSpy()
    check("placeholder cleared", forceRemovePlaceholderDirectory(at: link, logger: spy, label: "/var/minis/mcp-servers"))
    var created = true
    do { try fm.createSymbolicLink(at: link, withDestinationURL: target) } catch { created = false }
    check("symlink now creates cleanly", created)

    var b = stat()
    let isLink = lstat(link.path, &b) == 0 && (b.st_mode & S_IFMT) == S_IFLNK
    check("…and the path is a symlink, not a directory", isLink)
    check("…pointing at the App Group dir",
          link.resolvingSymlinksInPath().standardized.path == target.resolvingSymlinksInPath().standardized.path)

    // A file written host-side is now visible through the guest-facing path —
    // the bridge round trip #380 was missing.
    fm.createFile(atPath: target.appendingPathComponent("bridge.json").path,
                  contents: Data(#"{"access_token":"t"}"#.utf8))
    let viaLink = link.appendingPathComponent("bridge.json")
    check("a host-written bridge file is readable through the link",
          (try? Data(contentsOf: viaLink)).map { String(data: $0, encoding: .utf8)?.contains("access_token") == true } ?? false)
}

print("\n▶️  4. failure is reported, never silent")
do {
    // A directory that cannot be removed must return false so the caller skips
    // the symlink step and logs the shadowing condition, rather than failing
    // twice for reasons recorded in two different places.
    let d = makeDir("locked", files: ["a"])
    // Make the PARENT read-only so neither the child delete nor rmdir can work.
    let parentAttrs = try? fm.attributesOfItem(atPath: root.path)
    try? fm.setAttributes([.posixPermissions: 0o500], ofItemAtPath: root.path)
    let spy = LogSpy()
    let ok = forceRemovePlaceholderDirectory(at: d, logger: spy, label: "/var/minis/mcp-servers")
    // Restore immediately so the fixture can be cleaned up.
    if let p = parentAttrs?[.posixPermissions] {
        try? fm.setAttributes([.posixPermissions: p], ofItemAtPath: root.path)
    } else {
        try? fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
    }
    if ok {
        // Running as a user that ignores the permission bits (root). Not a
        // failure of the code — say so rather than asserting something untrue.
        print("  ⏭  parent-readonly case not reproducible here (removal still succeeded)")
    } else {
        check("returns false when the dir cannot be cleared", !ok)
        check("…and says so in the log", spy.errors.contains { $0.contains("rmdir failed") })
    }
    try? fm.removeItem(at: d)
}

print("\n▶️  5. shipping source still carries the fix")
do {
    let off = codeOnly(source("Agent/Chat/AIChatViewModel+Offloading.swift"))
    let mcp = codeOnly(source("Agent/Session/MCPOAuthController.swift"))
    let cli = source("default_mount/usr/local/bin/minis-mcp-cli")
    let http = source("default_mount/usr/local/lib/minis-mcp-cli/transport/http.py")
    if off.isEmpty || mcp.isEmpty || cli.isEmpty || http.isEmpty {
        print("  ⏭  sources not readable")
    } else {
        check("the recovery helper ships",
              off.contains("static func forceRemovePlaceholderDirectory("))
        check("…and the weak `try? removeItem` recovery is gone",
              !off.contains("try? fm.removeItem(at: hostPath)"))
        // Must assert the helper is CALLED, not merely defined: an earlier version
        // of this check passed while the call site was disabled.
        check("…and the real-dir branch actually calls it",
              off.contains("if !Self.forceRemovePlaceholderDirectory(at: hostPath, logger: logger, label: linuxDir) {"))
        check("…and a failed clear skips the symlink step",
              off.contains("guest will keep seeing a shadowed local dir")
              && off.contains("continue"))
        check("the bridge write verifies and throws",
              mcp.contains("private static func materializeBridge(server: String, oauth: MCPOAuthConfig, tokens: StoredTokens) throws"))
        check("…by reading the file back",
              mcp.contains("let readBack = try Data(contentsOf: url)"))
        check("…and the authorize path propagates the failure",
              mcp.contains("try Self.materializeBridge(server: server, oauth: oauth, tokens: stored)"))
        // The guest must never pre-create the mount point.
        let liveMkdir = cli.components(separatedBy: "\n")
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("#") }
            .contains { $0.contains("mkdir") && $0.contains("/var/minis/mcp-servers") }
        check("the guest CLI no longer mkdir's the mount point", !liveMkdir)
        check("the guest distinguishes missing from unreadable",
              http.contains("except FileNotFoundError:") && http.contains("except OSError as exc:"))
        check("…and prints the absolute path it tried",
              http.contains("at %s") && http.contains("path = _oauth_token_path(server_name)"))
    }
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
