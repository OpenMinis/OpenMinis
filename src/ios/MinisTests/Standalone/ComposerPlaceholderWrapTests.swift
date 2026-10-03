// Tests for [T-ios-composer-placeholder-wrap] — the chat composer's placeholder
// used to be a single-line UILabel pinned by leading+top only, so any hint
// wider than the composer ran off the edge and was clipped mid-sentence.
//
// Run with an iOS SDK (UIKit is required to measure real Auto Layout):
//   SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
//   swiftc -sdk "$SDK" -target arm64-apple-ios17.0-simulator \
//     ComposerPlaceholderWrapTests.swift -o /tmp/t && /tmp/t
//
// Section [3] re-reads the shipping source, so it also guards against the two
// halves of the fix being separated later.

import UIKit
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

/// Mirrors makeUIView's placeholder setup. `bindWidth` + `numberOfLines`
/// are the two halves of the fix; both are needed.
func placeholderFrame(text: String, tvWidth: CGFloat,
                      numberOfLines: Int, bindWidth: Bool) -> CGRect {
    let tv = UITextView(frame: CGRect(x: 0, y: 0, width: tvWidth, height: 44))
    tv.textContainerInset = .zero
    tv.textContainer.lineFragmentPadding = 0
    let label = UILabel()
    label.text = text
    label.font = UIFont.systemFont(ofSize: 16.5)
    label.numberOfLines = numberOfLines
    label.lineBreakMode = .byWordWrapping
    label.translatesAutoresizingMaskIntoConstraints = false
    tv.addSubview(label)
    var cs = [label.leadingAnchor.constraint(equalTo: tv.leadingAnchor),
              label.topAnchor.constraint(equalTo: tv.topAnchor)]
    if bindWidth { cs.append(label.widthAnchor.constraint(equalTo: tv.widthAnchor)) }
    NSLayoutConstraint.activate(cs)
    tv.setNeedsLayout(); tv.layoutIfNeeded()
    return label.frame
}

func shipped(_ text: String, _ w: CGFloat) -> CGRect {
    placeholderFrame(text: text, tvWidth: w, numberOfLines: 0, bindWidth: true)
}
func old(_ text: String, _ w: CGFloat) -> CGRect {
    placeholderFrame(text: text, tvWidth: w, numberOfLines: 1, bindWidth: false)
}

// The real strings from AIChatView's placeholderRotation, longest first.
let hints = [
    "Long-press anywhere in a reply to select and copy it all",
    "Minis can make the Return key send or insert a newline",
    "Try @ to mention files for Minis to read",
    "Try dragging text into the chat to send it",
    "Type / for SKILLs and quick commands",
]
let defaultHint = "Message Minis (@ to mention files)"
let width: CGFloat = 300   // a narrow-ish composer (iPhone SE class)

print("\n[1] No shipped placeholder overflows the composer")
for h in hints + [defaultHint] {
    let f = shipped(h, width)
    check("fits: \"\(h.prefix(38))…\" (w=\(Int(f.width)))", f.width <= width + 0.5)
}

print("\n[2] The regression is real — old setup DID overflow")
// If this ever stops being true the test has lost its meaning.
let worst = hints[0]
let oldF = old(worst, width), newF = shipped(worst, width)
check("old setup overflowed (w=\(Int(oldF.width)) > \(Int(width)))", oldF.width > width)
check("old setup was one line", oldF.height < 30)
check("new setup wraps to >1 line (h=\(Int(newF.height)))", newF.height > oldF.height)
check("new setup stays within width", newF.width <= width + 0.5)

// Narrow composer (split view / large Dynamic Type): still must not overflow.
print("\n[3] Still contained at a narrow width")
for w in [CGFloat(200), 240, 320, 390] {
    let f = shipped(worst, w)
    check("w=\(Int(w)): contained (label w=\(Int(f.width)), h=\(Int(f.height)))", f.width <= w + 0.5)
}

print("\n[4] Shipping source keeps BOTH halves of the fix")
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let src = source("Views/Chat/ChatInputBar.swift")
if src.isEmpty {
    // Run under `simctl spawn`, the source tree is outside the simulator's
    // sandbox and #filePath cannot be read. Skip rather than report a failure
    // the code does not have — a false red here would be worse than no check.
    print("  ⏭  source not readable from this sandbox — run the binary directly to include these")
} else {
    check("placeholder allows multiple lines", src.contains("placeholderLabel.numberOfLines = 0"))
    check("placeholder width is bound to the text view",
          src.contains("placeholderLabel.widthAnchor.constraint(equalTo: tv.widthAnchor)"))
    check("word wrapping is explicit",
          src.contains("placeholderLabel.lineBreakMode = .byWordWrapping"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
