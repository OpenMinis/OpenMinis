#!/usr/bin/env swift
// [T-ish-offload-path-scope] OpenMinis#288 — any file named `ffmpeg` anywhere in
// the sandbox was replaced by the app's in-process FFmpeg.
//
// Root cause: `offload_find` (deps/ish/kernel/native_offload.c) matched an exec
// on its BASENAME alone. `./ffmpeg`, `/tmp/build/ffmpeg`, a `#!/bin/sh` wrapper
// in a workspace — all were taken over, the user's own program never ran, and
// nothing said so.
//
// Fix, for offload names that shadow a real program (ffmpeg, ffprobe):
//   * claimed only at /bin, /usr/bin, /usr/local/bin — everything else,
//     relative paths included, runs the guest's own file;
//   * a #! script is never taken over, even at those paths;
//   * MINIS_NO_FFMPEG_OFFLOAD=1 / NO_OFFLOAD=1 in the exec env turns it off.
// Minis-only offloads (apple-*, minis-*) are unchanged: there is no real binary
// behind them to protect, the offload IS the command.
//
// The policy's own tests are C (deps/ish/kernel/offload_tests/path_scope) and
// are RUN from here, so this suite exercises them. The end-to-end proof — the
// same scenarios through the real kernel execve on the host iSH CLI, fixed vs
// basename-only — is recorded in the commit message.
//
// Run: swift NativeOffloadPathScopeTests.swift
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

let repo = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
func read(_ rel: String) -> String {
    (try? String(contentsOf: repo.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

let exec = codeOnly(read("deps/ish/kernel/exec.c"))
let offload = codeOnly(read("deps/ish/kernel/native_offload.c"))
let meson = read("deps/ish/meson.build")
guard !exec.isEmpty, !offload.isEmpty else {
    print("  ⏭  deps/ish sources not present (submodule not checked out)")
    exit(0)
}

print("▶️  1. the C policy tests pass")
do {
    let runner = repo.appendingPathComponent("deps/ish/kernel/offload_tests/path_scope/run.sh").path
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/bin/sh")
    p.arguments = [runner]
    let pipe = Pipe()
    p.standardOutput = pipe
    p.standardError = pipe
    do {
        try p.run()
        p.waitUntilExit()
        let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        let failed = out.components(separatedBy: "\n").filter { $0.contains("❌") }
        failed.forEach { print("     \($0.trimmingCharacters(in: .whitespaces))") }
        check("path_scope C test exits 0", p.terminationStatus == 0)
        check("…and reports no failed checks", failed.isEmpty)
        // Count individual check lines only — not the trailing "✅ ALL PASSED".
        let passed = out.components(separatedBy: "\n").filter { $0.hasPrefix("  ✅") }.count
        check("…with all 39 policy checks run (\(passed))", passed >= 39)
    } catch {
        check("path_scope runner launched (\(error.localizedDescription))", false)
    }
}

print("\n▶️  2. the exec dispatch uses the policy-aware lookup")
do {
    check("sys_execve calls native_offload_lookup_exec with the exec envp",
          exec.contains("native_offload_lookup_exec(filename, envp, &offload_is_generic)"))
    check("…and no longer the name-only lookup",
          !exec.contains("native_offload_lookup(filename)"))
    check("a #! script is refused for generic offloads",
          exec.contains("if (native_path && offload_is_generic && guest_file_is_shebang_script(filename)) {"))
    check("the shebang probe exists", exec.contains("static bool guest_file_is_shebang_script(const char *path) {"))
    // A missing file must NOT block the offload (ash probes absent PATH entries).
    check("…and a failed open answers false, not true",
          exec.contains("if (IS_ERR(fd))\n        return false;"))
}

print("\n▶️  3. the lookup applies path policy + env hatch")
do {
    check("path policy is applied",
          offload.contains("if (!native_offload_path_allowed(e->guest_name, guest_path)) {"))
    check("env hatch is applied",
          offload.contains("if (native_offload_env_disabled(e->guest_name, envp)) {"))
    // The older entry point must not be a bypass.
    check("native_offload_lookup routes through the policy too",
          offload.contains("return native_offload_lookup_exec(guest_path, NULL, NULL);"))
    check("the policy file is compiled into the kernel",
          meson.contains("'kernel/native_offload_policy.c',"))
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
