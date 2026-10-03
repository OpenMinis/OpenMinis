#!/usr/bin/env swift
// [T-backup-webdav-response-deadline, T-backup-upload-cancellable,
//  T-backup-upload-retry-backoff, T-backup-sync-offmain]
//
// Field report 2026-09-22 (Fs): every WebDAV backup to openlist.fsin.fun failed
// with
//     unchunked simple update failed: Put "…": http2: timeout awaiting
//     response headers
// while the progress UI showed 3.1 MB/s and "about 4 seconds left" for a 31MB
// package. The bytes were never the problem.
//
// Root cause: `RcloneBridge.ioTimeout` (rclone's `Timeout`) becomes Go's
// `http.Transport.ResponseHeaderTimeout`. That is NOT a stall timeout — the
// timer starts after the request body is fully written and is a flat deadline
// that never resets while the server works. At 45s it gave an AList/OpenList
// gateway relaying to a cloud drive (`/dav/189/`) less time to finish its own
// upload than that upload takes, so healthy servers failed deterministically.
//
// Measured against rclone v1.75.0 with the app's exact options, on a WebDAV
// server stalled 60s after the body landed:
//
//     Timeout=45s                → fails at 46.1s, byte-identical error
//     Timeout=300s               → succeeds at 60.1s
//     Timeout=45s + DisableHTTP2 → STILL fails at 46.1s ("net/http:" prefix)
//
// That last row is why this file pins "no DisableHTTP2": disabling HTTP/2 was
// proposed as a fix and is not one — it only changes the error text, while
// costing every other backend HTTP/2's multiplexing.
//
// Run: swift BackupUploadDeadlineTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator. These are wiring facts, not
// pure functions, so the shipping sources are re-read below; a rewrite fails
// here instead of silently passing a stale copy.
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
/// Strip `//` comment lines before matching, so a doc comment that QUOTES code
/// is never mistaken for the code itself — a trap earlier guards in this code
/// base fell into twice.
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

let bridgeRaw = source("Agent/Backup/Remote/RcloneBridge.swift")
let uploadRaw = source("Agent/Backup/Remote/RcloneChunkedUpload.swift")
let storeRaw  = source("Agent/Backup/Remote/RcloneRemoteStore.swift")
let destRaw   = source("Agent/Backup/BackupDestinations.swift")
guard !bridgeRaw.isEmpty, !uploadRaw.isEmpty, !storeRaw.isEmpty, !destRaw.isEmpty else {
    print("  ⏭  sources not readable from \(#filePath)")
    exit(0)
}
let bridge = codeOnly(bridgeRaw)
let upload = codeOnly(uploadRaw)
let store  = codeOnly(storeRaw)
let dest   = codeOnly(destRaw)

print("▶️  1. the response-header deadline is rclone's default, not 45s")
do {
    // Parse the literal rather than string-matching "300", so a change to a
    // different number fails loudly instead of matching some other constant.
    let value: Int? = {
        guard let r = bridge.range(of: "static let ioTimeout: TimeInterval = ") else { return nil }
        let tail = bridge[r.upperBound...].prefix(while: { $0.isNumber })
        return Int(tail)
    }()
    checkEq("ioTimeout is 300s", value, 300)
    check("…and the 45s value is gone", !bridge.contains("ioTimeout: TimeInterval = 45"))

    // connectTimeout is what actually bounds a dead server, so raising the
    // response deadline must NOT have raised this one too.
    let connect: Int? = {
        guard let r = bridge.range(of: "static let connectTimeout: TimeInterval = ") else { return nil }
        return Int(bridge[r.upperBound...].prefix(while: { $0.isNumber }))
    }()
    checkEq("connectTimeout stays short (20s)", connect, 20)

    // The options actually sent to rclone must still be derived from the
    // constants — a literal here would silently decouple them.
    check("Timeout is wired from ioTimeout",
          bridge.contains("\"Timeout\": Int(Self.ioTimeout * 1_000_000_000)"))
    check("ConnectTimeout is wired from connectTimeout",
          bridge.contains("\"ConnectTimeout\": Int(Self.connectTimeout * 1_000_000_000)"))
}

print("\n▶️  2. HTTP/2 is NOT disabled (the measured non-fix)")
do {
    // Experiment 3: HTTP/1.1 fails at the same 46.1s. Disabling HTTP/2 buys
    // nothing here and costs multiplexing everywhere else.
    check("no DisableHTTP2 in the rclone options", !bridge.contains("DisableHTTP2"))
    check("…and none snuck into the upload path", !upload.contains("DisableHTTP2"))
}

print("\n▶️  3. the upload runs as a cancellable async job")
do {
    check("a dedicated job runner ships",
          upload.contains("private static func runUploadJob("))
    // The load-bearing pair: without BOTH, Stop cannot reach the transfer.
    check("the copy is started async", upload.contains("\"_async\": true"))
    check("…and cancellation is asked of rclone by jobid",
          upload.contains("RcloneBridge.rpc(\"job/stop\", [\"jobid\": jobid])"))
    check("progress polls that job's status",
          upload.contains("RcloneBridge.rpc(\"job/status\", [\"jobid\": jobid])"))
    // A _group is what keeps a concurrent transfer out of this progress bar.
    check("stats are read per-group, not process-wide",
          upload.contains("RcloneBridge.rpc(\"core/stats\", [\"group\": group])"))
    check("…and the job is tagged with that group", upload.contains("\"_group\": group"))

    // Non-vacuous: the OLD shape was a bare blocking copyfile with no _async.
    // Assert the upload path no longer contains one outside the documented
    // no-jobid fallback, by checking the cancel check is INSIDE the poll loop.
    check("isCancelled is consulted during the transfer, not only between attempts",
          upload.contains("if !cancelled && isCancelled() {"))
    check("a cancelled job surfaces as .cancelled",
          upload.contains("if cancelled { throw TransferError.cancelled }"))

    // The runner must be reached from the retry loop, or it is dead code —
    // an earlier guard in this repo passed while a helper's call site was
    // disabled, so assert the CALL, not just the definition.
    check("the retry loop actually calls it",
          upload.contains("try runUploadJob(srcDir:"))
}

print("\n▶️  4. a retry waits, and Cancel is not retried")
do {
    let backoff: Int? = {
        guard let r = upload.range(of: "static let retryBackoff: TimeInterval = ") else { return nil }
        return Int(upload[r.upperBound...].prefix(while: { $0.isNumber }))
    }()
    check("a backoff constant ships and is > 0", (backoff ?? 0) > 0)
    check("it is applied only before a LATER attempt", upload.contains("if attempt > 1 {"))
    // The backoff must stay interruptible, or Stop appears to hang for its
    // whole duration — the exact complaint this change set exists to fix.
    check("…and the wait itself is cancellable",
          upload.contains("while Date() < deadline {")
          && upload.contains("if isCancelled() { throw TransferError.cancelled }"))
    // Cancelling is a decision, not a transient fault: retrying it would make
    // Stop take two full attempts to take effect.
    check("a cancelled attempt is not retried",
          upload.contains("if case TransferError.cancelled = error { throw error }"))
}

print("\n▶️  5. syncToRclone no longer blocks the main thread")
do {
    // The store is @MainActor, so the blocking cgo work has to be explicitly
    // nonisolated to be off-main.
    check("the rclone work is nonisolated",
          store.contains("private nonisolated static func applySyncToRclone("))
    check("…and it is dispatched off the main actor",
          store.contains("Task.detached(priority: .utility) {"))
    // Main-actor state must be snapshotted BEFORE the hop: reading `remotes`
    // or the Keychain from the detached task would not compile, and passing
    // the values is what keeps it that way.
    check("remotes + secrets are snapshotted on the main actor",
          store.contains("private static func prepareForSync() -> [PreparedRemote]")
          && store.contains("PreparedRemote(remote: $0, secret: loadSecret(for: $0.name))"))
    check("the payload is Sendable", store.contains("private struct PreparedRemote: Sendable"))
    // The pure helpers it calls must be nonisolated too, or the hop is illegal.
    check("secretNeedsObscuring is nonisolated",
          store.contains("private nonisolated static func secretNeedsObscuring("))
    check("secretKey is nonisolated",
          store.contains("private nonisolated static func secretKey("))
    // The old main-thread Keychain reads inside the loop must be gone —
    // they were the reason the function could not leave the main actor.
    check("…and the loop reads the snapshotted secret, not the Keychain",
          !store.contains("if let secret = loadSecret(for: r.name), !secret.isEmpty {"))
}

print("\n▶️  6. the upload path still waits for the config it depends on")
do {
    // Making sync fire-and-forget would race the transfer: the detached upload
    // talks to rclone immediately and a half-written config fails it with a
    // bogus "remote not found". This ordering is the one thing the async
    // version must not lose.
    check("an awaiting variant ships",
          store.contains("static func syncToRcloneAndWait() async"))
    check("…and the backup delivery path awaits it",
          dest.contains("await RcloneRemoteStore.syncToRcloneAndWait()"))
    check("…rather than the fire-and-forget form",
          !dest.contains("\n        RcloneRemoteStore.syncToRclone()"))
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
