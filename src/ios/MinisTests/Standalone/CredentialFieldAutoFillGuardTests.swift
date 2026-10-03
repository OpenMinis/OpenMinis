#!/usr/bin/env swift
// [T-provider-label-keyboard] issue #364 — the provider "Label" field raised the
// password / keychain AutoFill bar.
//
// Mechanism: iOS decides a form is a credential form from the PASSWORD field,
// then hangs the password bar on the nearest preceding text field as its
// presumed username. So the opt-out has to be declared on the credential field,
// not on the Label. Measured on-simulator while diagnosing this:
//
//     Label (.none + .default)      contentType=nil (undeclared)   secure=no
//     key SecureField (declared)    contentType=one-time-code      secure=YES
//     key TextField   (undeclared)  contentType=nil (undeclared)   secure=no
//
// Two consequences this file exists to pin:
//
//  1. `.textContentType(.none)` compiles to nil — byte identical to declaring
//     nothing. It is NOT a positive "this is not a credential" marker, which is
//     why an earlier attempt that only touched the Label did not fix the report.
//     Both Label fields now declare `.nickname` instead.
//  2. An undeclared SecureField is `secure=YES, contentType=nil` — a password
//     field with no marker, which is exactly what lets the heuristic run.
//
// This bug was found TWICE on two screens with the same shape: AddProviderView
// was fixed in 3825397ca (2026-08-10) and ProviderInstanceDetailView was left
// with only the Label half, so its pairing was never actually broken. Neither
// commit left a named test, which is how the second screen stayed unfixed
// unnoticed for 1293 commits. Hence a source guard.
//
// Run: swift CredentialFieldAutoFillGuardTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator and an XCTest bundle has
// nowhere to run. AutoFill behaviour itself is not observable from a unit test
// (it needs a real keyboard session), so this asserts the DECLARATIONS that
// drive it, read out of the shipping source.
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

/// The modifier chain attached to the first field whose declaration contains
/// `needle`, up to the next field declaration or the end of the view builder.
/// Modifiers are separated from the declaration by comment blocks in this code
/// base, so a fixed look-ahead window would be brittle — this walks to the next
/// `TextField(` / `SecureField(` instead.
/// Drop `//` comment lines. A doc comment that QUOTES a declaration — this fix's
/// own comments explain why `.textContentType(.none)` is a no-op — must not read
/// as that declaration being present. An earlier guard in this code base matched
/// its own prose exactly this way, so the distinction is deliberate.
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

func modifierChain(after needle: String, in src: String) -> String {
    guard let start = src.range(of: needle) else { return "" }
    let rest = src[start.upperBound...]
    // Stop at the next field declaration, so one field's modifiers are never
    // mistaken for its neighbour's.
    let stops = ["TextField(", "SecureField("]
    var end = rest.endIndex
    for s in stops {
        if let r = rest.range(of: s) { end = min(end, r.lowerBound) }
    }
    return String(rest[rest.startIndex..<end])
}

let add = source("Views/Providers/AddProviderView.swift")
let detail = source("Views/Providers/ProviderInstanceDetailView.swift")
let mcp = source("Views/MCP/MCPFormSheet.swift")

guard !add.isEmpty, !detail.isEmpty, !mcp.isEmpty else {
    print("  ⏭  sources not readable from \(#filePath)")
    exit(0)
}

print("▶️  1. every provider credential field opts out of AutoFill")
do {
    // AddProviderView — fixed in 3825397ca, pinned here so a refactor cannot
    // silently drop it.
    let addKeyPlain = modifierChain(after: "TextField(keyPlaceholder, text: $apiKeyInput)", in: add)
    check("Add: API key plaintext branch declares .oneTimeCode",
          addKeyPlain.contains(".textContentType(.oneTimeCode)"))

    let addKeySecure = modifierChain(after: "SecureField(keyPlaceholder, text: $apiKeyInput)", in: add)
    check("Add: API key SecureField declares .oneTimeCode",
          addKeySecure.contains(".textContentType(.oneTimeCode)"))

    let addBearer = modifierChain(after: "SecureField(\"Bearer Token\", text: $manualOAuthTokenInput)", in: add)
    check("Add: Bearer Token SecureField declares .oneTimeCode",
          addBearer.contains(".textContentType(.oneTimeCode)"))

    // ProviderInstanceDetailView — the issue #364 gap. These two are the fix.
    let detailKey = modifierChain(after: "TextField(keyPlaceholder(instance.providerType), text: $keyInputText)", in: detail)
    check("Edit: API key field declares .oneTimeCode (the #364 fix)",
          detailKey.contains(".textContentType(.oneTimeCode)"))

    let detailBearer = modifierChain(after: "SecureField(\"Bearer token\", text: $manualTokenInputText)", in: detail)
    check("Edit: Bearer token SecureField declares .oneTimeCode (the #364 fix)",
          detailBearer.contains(".textContentType(.oneTimeCode)"))

    // MCP: a "Client ID" TextField directly above a SecureField is the same shape.
    let mcpSecret = modifierChain(after: "SecureField(AppLocalized(\"Client Secret (optional)\"), text: $oauthClientSecret)", in: mcp)
    check("MCP: Client Secret declares .oneTimeCode",
          mcpSecret.contains(".textContentType(.oneTimeCode)"))
}

print("\n▶️  2. both Label fields carry a POSITIVE non-credential marker")
do {
    let addLabel = modifierChain(after: "TextField(\"Provider label\", text: $labelInput)", in: add)
    check("Add: Label declares .nickname", addLabel.contains(".textContentType(.nickname)"))
    check("Add: Label keeps an explicit .default keyboard", addLabel.contains(".keyboardType(.default)"))

    let detailLabel = modifierChain(after: "TextField(\"Label\", text: $editingLabel)", in: detail)
    check("Edit: Label declares .nickname", detailLabel.contains(".textContentType(.nickname)"))
    check("Edit: Label keeps an explicit .default keyboard", detailLabel.contains(".keyboardType(.default)"))

    // `.none` compiles to nil, so re-introducing it would silently undo item 2
    // without changing behaviour in any way a reader would notice.
    // codeOnly: these files' comments legitimately discuss `.none` while
    // explaining why it was replaced.
    check("Add: Label no longer declares the no-op .none",
          !codeOnly(addLabel).contains(".textContentType(.none)"))
    check("Edit: Label no longer declares the no-op .none",
          !codeOnly(detailLabel).contains(".textContentType(.none)"))
}

print("\n▶️  3. the pairing type is never declared on these screens")
do {
    // `.username` beside a password field is the exact association AutoFill
    // hunts for. It must never appear on a provider/MCP form.
    for (name, src) in [("AddProviderView", add), ("ProviderInstanceDetailView", detail), ("MCPFormSheet", mcp)] {
        check("\(name) never declares .username", !src.contains(".textContentType(.username)"))
        // Opting INTO the strong-password flow is the opposite of the fix.
        check("\(name) never declares .password / .newPassword",
              !src.contains(".textContentType(.password)") && !src.contains(".textContentType(.newPassword)"))
    }
}

print("\n▶️  4. every SecureField in the app is accounted for")
do {
    // A SecureField with no textContentType is `secure=YES, contentType=nil` —
    // the shape that lets the heuristic claim a neighbouring field. The four
    // below are genuine passphrase fields where the strong-password /
    // save-to-Keychain flow is DESIRABLE (the user is choosing a backup
    // passphrase, not pasting a vendor credential), so they are allowlisted with
    // that reason rather than silently ignored.
    //
    // The point of an explicit allowlist: a NEW undeclared SecureField fails
    // here and its author has to make the same decision consciously.
    let allowlisted: Set<String> = [
        "Views/Settings/BackupSettingsView.swift",
        "Views/Settings/BackupRestoreView.swift",
        "Views/Settings/BackupDestinationDetailView.swift",
        "Views/Settings/RcloneAddServerView.swift",
    ]
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    var offenders: [String] = []
    var scanned = 0
    if let e = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) {
        for case let url as URL in e {
            guard url.pathExtension == "swift" else { continue }
            let rel = url.path.replacingOccurrences(of: root.path + "/", with: "")
            // Tests port these declarations on purpose; only ship code counts.
            guard !rel.hasPrefix("MinisTests/") else { continue }
            guard let text = try? String(contentsOf: url, encoding: .utf8),
                  text.contains("SecureField(") else { continue }
            scanned += 1
            if allowlisted.contains(rel) { continue }
            // Every SecureField in this file must have a content type somewhere
            // in its own modifier chain.
            var cursor = text.startIndex
            while let r = text.range(of: "SecureField(", range: cursor..<text.endIndex) {
                let chain = modifierChain(after: String(text[r.lowerBound..<text.index(r.upperBound, offsetBy: 0)]), in: String(text[r.lowerBound...]))
                _ = chain
                let rest = String(text[r.upperBound...])
                var end = rest.endIndex
                for s in ["TextField(", "SecureField("] {
                    if let rr = rest.range(of: s) { end = min(end, rr.lowerBound) }
                }
                let ownChain = String(rest[rest.startIndex..<end])
                if !ownChain.contains(".textContentType(") {
                    offenders.append(rel)
                }
                cursor = r.upperBound
            }
        }
    }
    check("at least the known SecureField hosts were scanned", scanned >= 5)
    check("no un-allowlisted SecureField is missing a textContentType — found: \(Set(offenders).sorted())",
          offenders.isEmpty)
}

print("")
if failures == 0 {
    print("✅ ALL PASSED")
} else {
    print("❌ \(failures) FAILURE(S)")
}
exit(failures == 0 ? 0 : 1)
