#!/usr/bin/env swift
// [T-restore-result-severity] The restore result screen showed "files not in
// the backup" lines in red (missingBlobs) or grey (size-capped), with wording
// that ended "— the backup is incomplete". Users whose restore had actually
// SUCCEEDED read the red as a crash or data corruption.
//
// Product decision: two tiers, told apart by colour AND icon.
//   • red, no icon  — `failed`: the category's restore itself failed;
//   • orange + ⚠️   — sizeSkippedInPackage, notDownloadedInPackage,
//                     missingBlobs, rolledBack: the restore worked, some files
//                     were never in the package.
//
// Two things this pins that are easy to regress:
//   * the ⚠️ is an `Image`, not a character in the string — every one of these
//     lines is a localized key with 17–18 translations, and prefixing the key
//     would orphan them all and drop every locale back to English;
//   * missingBlobs is still SURFACED (review S7 made it visible after it used
//     to be swallowed as a clean success) — only its tier changed.
//
// Run: swift RestoreResultSeverityTests.swift
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
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

let viewRaw = source("Views/Settings/BackupRestoreView.swift")
let catalogRaw = source("Localizable.xcstrings")
guard !viewRaw.isEmpty, !catalogRaw.isEmpty else { print("  ⏭  sources not readable"); exit(0) }
let view = codeOnly(viewRaw)

/// The body of `reportSection`, so assertions cannot match elsewhere in the file.
let report: String = {
    guard let s = view.range(of: "private func reportSection("),
          let e = view.range(of: "} header: {", range: s.upperBound..<view.endIndex) else { return "" }
    return String(view[s.upperBound..<e.lowerBound])
}()

print("▶️  1. real failures stay red")
do {
    check("reportSection located", !report.isEmpty)
    check("the failed line is still red",
          report.contains("Text(\"\\(displayNameRaw(c.category)): \\(failed)\")")
          && report.contains(".foregroundStyle(.red)"))
    // Exactly one red in the section — the failure line. Anything else red is
    // the regression this change exists to remove.
    check("…and it is the ONLY red in the result section",
          report.components(separatedBy: ".foregroundStyle(.red)").count - 1 == 1)
}

print("\n▶️  2. every 'not in the package' line uses the warning style")
do {
    let lines = [
        "file(s) weren't in the backup (size limit)",
        "file(s) weren't in the backup (not downloaded from iCloud on the source device)",
        "file(s) were listed in the backup but missing from it",
        "Rolled back: ",
    ]
    for l in lines {
        // Each must sit inside a restoreNotice(...) call on its own line.
        let wrapped = report.components(separatedBy: "\n").contains {
            $0.contains("restoreNotice(Text(") && $0.contains(l)
        }
        check("restoreNotice wraps: \(l.prefix(48))", wrapped)
    }
    check("size-capped is no longer grey fine print",
          !report.contains(".foregroundStyle(.secondary)"))
}

print("\n▶️  3. the warning style is orange + an icon, not a string prefix")
do {
    check("the helper exists", view.contains("private func restoreNotice(_ text: Text) -> some View {"))
    check("…orange", view.contains(".foregroundStyle(.orange)"))
    check("…with a warning-triangle Image", view.contains("Image(systemName: \"exclamationmark.triangle.fill\")"))
    // The ⚠️ must NOT be baked into any localized key — that orphans 17–18
    // translations per line.
    check("no ⚠️ character inside the view's string literals", !view.contains("⚠️"))
}

print("\n▶️  4. missingBlobs is still surfaced, without the alarming clause")
do {
    check("missingBlobs still produces a line", report.contains("if c.missingBlobs > 0 {"))
    check("the '— the backup is incomplete' wording is gone from the view",
          !view.contains("the backup is incomplete"))
}

print("\n▶️  5. translations carried over")
do {
    let newKey = "%@: %lld file(s) were listed in the backup but missing from it"
    check("the new key is in the catalog", catalogRaw.contains("\"\(newKey)\""))
    if let data = catalogRaw.data(using: .utf8),
       let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
       let strings = root["strings"] as? [String: Any],
       let entry = strings[newKey] as? [String: Any],
       let locs = entry["localizations"] as? [String: Any] {
        check("…with at least 17 locales (was 18 on the old key)", locs.count >= 17)
        // Every translation must keep its format specifiers, or it crashes or
        // prints garbage at runtime.
        var allSpecifiersKept = true
        for (loc, v) in locs {
            let s = ((v as? [String: Any])?["stringUnit"] as? [String: Any])?["value"] as? String ?? ""
            let hasObj = s.contains("%@") || s.contains("%1$@")
            if !(hasObj && s.contains("lld")) { print("     missing specifier in \(loc): \(s)"); allSpecifiersKept = false }
        }
        check("…and every translation keeps its %@ / %lld", allSpecifiersKept)
    } else {
        check("catalog parsed", false)
    }
    // The three keys whose text did not change must keep their translations —
    // which is why the ⚠️ is an Image, not a prefix.
    for k in ["%@: %lld file(s) weren't in the backup (size limit)",
              "%@: %lld file(s) weren't in the backup (not downloaded from iCloud on the source device)",
              "Rolled back: %@"] {
        check("unchanged key still translated: \(k.prefix(40))", catalogRaw.contains("\"\(k)\""))
    }
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
