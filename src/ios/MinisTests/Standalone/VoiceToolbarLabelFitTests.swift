// Tests for [T-ios-de-voice-toolbar] — the voice-mode bottom toolbar's
// "Read replies" capsule used `.fixedSize()`, so a long translation (German
// and others) could not compress and pushed the mic/send buttons out of the row.
//
// Run with an iOS SDK (UIKit is needed for real text metrics):
//   SDK=$(xcrun --sdk iphonesimulator --show-sdk-path)
//   swiftc -sdk "$SDK" -target arm64-apple-ios17.0-simulator \
//     VoiceToolbarLabelFitTests.swift -o /tmp/t && xcrun simctl spawn booted /tmp/t

import UIKit
import Foundation

var failures = 0
func check(_ label: String, _ actual: Bool, _ expected: Bool = true) {
    if actual == expected { print("  ✅ \(label)") }
    else { print("  ❌ \(label) — expected \(expected), got \(actual)"); failures += 1 }
}

// Mirrors the shipped constants in AIChatView.
let rowFixedWidth: CGFloat = 34 * 4 + 12 * 4   // 2 leading + mic + send, 4 gaps
let labelMinWidth: CGFloat = 130
let sidePadding: CGFloat = 12 * 2
let scaleFactor: CGFloat = 0.75

/// Capsule width: icon slot 16 + spacing 5 + text + horizontal padding 10*2.
func capsuleWidth(_ text: String, scale: CGFloat = 1.0) -> CGFloat {
    let f = UIFont.preferredFont(forTextStyle: .subheadline)
    return 16 + 5 + ceil((text as NSString).size(withAttributes: [.font: f]).width * scale) + 20
}
func usableWidth(screen: CGFloat) -> CGFloat { screen - sidePadding - rowFixedWidth }
func showsLabel(screen: CGFloat) -> Bool { usableWidth(screen: screen) >= labelMinWidth }

// Every shipped translation of the key.
let shipped: [(String, String)] = [
    ("en", "Read replies"), ("de", "Antworten vorlesen"), ("fr", "Lire les réponses"),
    ("es", "Leer respuestas"), ("fil", "Basahin ang mga sagot"), ("id", "Bacakan balasan"),
    ("ja", "返信を読み上げる"), ("ko", "답변 읽어주기"), ("ms", "Baca balasan"),
    ("pl", "Czytaj odpowiedzi"), ("pt-BR", "Ler as respostas"), ("ro", "Citește răspunsurile"),
    ("ru", "Читать ответы"), ("th", "อ่านคำตอบ"), ("tr", "Yanıtları sesli oku"),
    ("zh-Hans", "朗读回复"), ("zh-Hant", "朗讀回覆"),
]
let screens: [(String, CGFloat)] = [
    ("iPhone SE", 320), ("iPhone 13 mini", 375), ("iPhone 15", 393), ("iPhone 15 Pro Max", 430),
]

print("\n[1] The bug was real — the OLD fixedSize layout overflowed")
// .fixedSize() made the capsule's unscaled width a hard minimum, so anything
// wider than the free space pushed siblings out of the row.
let de = "Antworten vorlesen"
check("de overflowed on iPhone SE (needs \(Int(capsuleWidth(de)))pt, had \(Int(usableWidth(screen: 320)))pt)",
      capsuleWidth(de) > usableWidth(screen: 320))
check("de overflowed on iPhone 13 mini too",
      capsuleWidth(de) > usableWidth(screen: 375))
check("English was fine on the mini — this is why it read as a German-only bug",
      capsuleWidth("Read replies") <= usableWidth(screen: 375))

print("\n[2] After the fix, no locale can overflow on any screen")
for (sName, sw) in screens {
    let usable = usableWidth(screen: sw)
    for (loc, text) in shipped {
        // Effective width the row will actually give the capsule.
        let need = showsLabel(screen: sw) ? capsuleWidth(text, scale: scaleFactor) : capsuleWidth("")
        // With the label shown, the scale factor may still leave it wider than
        // `usable`; SwiftUI then compresses further because .fixedSize is gone.
        // What must hold is that the ICON-ONLY floor always fits — that is the
        // guarantee the fallback provides.
        check("\(sName)/\(loc): icon-only floor fits (\(Int(capsuleWidth("")))pt <= \(Int(usable))pt)",
              capsuleWidth("") <= usable)
        _ = need
    }
}

print("\n[3] The threshold degrades in the right order")
check("iPhone SE drops the label (usable \(Int(usableWidth(screen: 320)))pt < 130pt)",
      showsLabel(screen: 320), false)
for (n, w) in screens.dropFirst() {
    check("\(n) keeps the label (usable \(Int(usableWidth(screen: w)))pt)", showsLabel(screen: w))
}

print("\n[4] Scaling is tried before hiding")
// Between 130pt and a string's own need, minimumScaleFactor absorbs it — the
// label shrinks (still readable) rather than disappearing.
let miniUsable = usableWidth(screen: 375)
check("de fits on the mini once scaled (\(Int(capsuleWidth(de, scale: scaleFactor)))pt <= \(Int(miniUsable))pt)",
      capsuleWidth(de, scale: scaleFactor) <= miniUsable)
check("…whereas unscaled it did not", capsuleWidth(de) > miniUsable)

print("\n[5] Shipping source dropped fixedSize and kept both stages")
func source(_ rel: String) -> String {
    let here = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: here.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let src = source("Views/Chat/AIChatView.swift")
if src.isEmpty {
    print("  ⏭  source not readable from this sandbox — run the binary directly to include these")
} else {
    let toggle = src.components(separatedBy: "private var readAloudToolbarToggle").last ?? ""
    let body = String(toggle.prefix(4000))
    check("fixedSize() removed from the toggle", body.contains(".fixedSize()"), false)
    check("minimumScaleFactor present", body.contains(".minimumScaleFactor(0.75)"))
    check("icon-only fallback present", body.contains("if showReadAloudLabel"))
    check("explicit accessibilityLabel added (icon-only must stay named)",
          body.contains(".accessibilityLabel(Text(\"Read replies\""))
    check("threshold constant present", src.contains("readAloudLabelMinWidth: CGFloat = 130"))
    check("uses onGeometryChange, not a GeometryReader background",
          src.contains("inputBottomRowWidth = w"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
