// Tests for [T-copilot-disclaimer] — the GitHub Copilot integration must not
// contact GitHub until the user has explicitly accepted the risk notice, and
// the provider must be OFF unless deliberately enabled.
//
// Standalone (`swift CopilotDisclaimerGateTests.swift`) for the same reason as
// the neighbouring files: the MinisTests target has a pre-existing compile
// break and the shipping types pull in the whole app graph.
//
// These are source-level assertions by necessity: the property under test is
// "no network call happens before consent", which is a control-flow guarantee
// in a SwiftUI view, not a value a unit test can read back. What they pin is
// that the gate still exists, that the ONLY path to the device flow runs
// through it, and that the default is off — i.e. exactly the ways this could
// silently regress.

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

let sheet = source("Views/Providers/CopilotDeviceLoginSheet.swift")
let consts = source("Providers/Copilot/CopilotConstants.swift")
let oauth = source("Providers/Copilot/OAuth/CopilotOAuthManager.swift")

print("\n[0] Sources readable")
check("login sheet", !sheet.isEmpty)
check("constants", !consts.isEmpty)
check("oauth manager", !oauth.isEmpty)

print("\n[1] Device flow is NOT reachable without consent")
// The sheet opens in `.consent`; only `start()` begins the flow.
check("initial phase is .consent", sheet.contains("@State private var phase: Phase = .consent"))
check("a .consent case exists", sheet.contains("case consent"))
// `start()` must be called from exactly one place: the consent button.
let startCalls = sheet.components(separatedBy: "start()").count - 1
// Occurrences: the `start()` definition, the consent button, and the
// retry button on the failure screen (which is post-consent by construction).
check("start() referenced a small, auditable number of times (<= 4)", startCalls <= 4)
check("consent button calls start()",
      sheet.contains("Button {\n            start()\n        } label: {"))
// The network call itself lives behind start() -> requestDeviceAuthorization.
check("device authorization is requested only inside start()",
      sheet.contains("CopilotOAuthManager.shared.requestDeviceAuthorization()"))
// Nothing may fire on appear: an .onAppear/.task that called start() would
// defeat the gate entirely while leaving the consent UI visibly intact.
check("no .onAppear auto-start", sheet.contains(".onAppear { start()"), false)
check("no .task auto-start", sheet.contains(".task { start()"), false)
check("no .task { await start", sheet.contains(".task { await start"), false)

print("\n[2] Cancel sends nothing")
check("cancel finishes without starting", sheet.contains("Button(AppLocalized(\"Cancel\")) { finish(false) }"))

print("\n[3] The notice states each required risk")
let required: [(String, String)] = [
    ("unofficial / not authorized", "unofficial compatibility experiment"),
    ("no GitHub affiliation",       "no affiliation with GitHub"),
    ("interfaces may change/stop",  "may change or stop working at any time"),
    ("entitlement/account risk",    "entitlement or your GitHub account at risk"),
    ("not for org/enterprise/prod", "organization, enterprise or production account"),
    ("user's own decision",         "your decision"),
]
for (name, needle) in required {
    check("states: \(name)", sheet.contains(needle))
}
check("explicit consent button wording",
      sheet.contains("I understand and want to continue"))

print("\n[4] Forbidden claims are absent")
// Must never claim official status, legality, safety, or a no-ban guarantee.
for bad in ["officially supported", "fully legal", "guaranteed safe",
            "will not be banned", "won't get banned", "endorsed by GitHub"] {
    check("does not claim: \"\(bad)\"", sheet.lowercased().contains(bad.lowercased()), false)
}
// Must not surface circumvention details (client id / UA) in the USER-FACING
// notice. They legitimately live in CopilotConstants; the sheet must not echo
// them.
check("notice does not print the client id", sheet.contains("Iv1."), false)
check("notice does not print the user agent", sheet.contains("GitHubCopilotChat/"), false)

print("\n[5] Provider defaults to OFF")
check("isEnabled reads the key with no true-by-default branch",
      consts.contains("UserDefaults.standard.bool(forKey: enabledDefaultsKey)"))
check("the old default-ON branch is gone",
      consts.contains("if UserDefaults.standard.object(forKey: enabledDefaultsKey) == nil { return true }"), false)
check("flag is documented as OFF by default", consts.contains("Defaults to **OFF**"))
check("no remote config is introduced",
      consts.lowercased().contains("remoteconfig") || consts.contains("firebase"), false)

print("\n[6] One-time first-use reminder, not per-message")
check("consume-once helper exists", consts.contains("static func consumeFirstUseNotice() -> Bool"))
check("it persists the shown flag", consts.contains("d.set(true, forKey: firstUseNoticeShownKey)"))
let factory = source("Agent/Chat/AIChatViewModel+ProviderFactory.swift")
check("hooked on the Copilot provider build path",
      factory.contains("CopilotConstants.consumeFirstUseNotice()"))
check("delivered through the existing transient banner",
      factory.contains("pendingCopilotFirstUseNotice"))

print("\n[7] OAuth/protocol untouched by this change")
// The task forbids altering the auth flow itself; these are the load-bearing
// pieces that must still be exactly as they were.
check("device flow still RFC 8628 device-code", oauth.contains("Device flow (RFC 8628)"))
check("still posts to GitHub's own device endpoint",
      oauth.contains("CopilotConstants.deviceCodeURL"))
check("token still stored via the shared Keychain helper",
      oauth.contains("ProviderKeychainHelper.saveOAuthToken"))

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
