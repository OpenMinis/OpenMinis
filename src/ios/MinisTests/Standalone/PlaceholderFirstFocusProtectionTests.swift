// Tests for [T-ios-context-usage-hint-first-focus-only] — the composer's
// context-usage placeholder line survives exactly one focus gain.
//
// Pins the Coordinator's state machine (a copy of the relevant transitions
// from src/ios/Views/Chat/ChatInputBar.swift) and greps the source for the
// wiring: the allowance is reset per generation, spent on the first focus,
// and a second focus retires the line and rotates.
//
// Standalone (`swift PlaceholderFirstFocusProtectionTests.swift`).
import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}

/// The Coordinator's transient-hint state, as the source transitions it.
struct Composer {
    let pool = ["default", "tip1", "tip2", "tip3", "tip4"]
    var label = "default"
    var text = ""
    var poolIndex = 0
    var generation = 0
    var hintText: String? = nil
    var hasUnconsumedTransientHint = false
    var hasProtectedFirstFocus = false
    var rotations = 0

    /// syncContextUsageHint + showTransientHint (debounce collapsed).
    mutating func hintArrives(gen: Int, text: String) {
        guard gen != generation else { return }
        generation = gen
        hintText = text
        hasUnconsumedTransientHint = true
        hasProtectedFirstFocus = false
        label = text
    }
    /// rotatePlaceholderOnFocus (VoiceOver off, rotation allowed on first focus).
    mutating func focus() {
        guard text.isEmpty else { return }
        if hasUnconsumedTransientHint {
            guard hasProtectedFirstFocus else { hasProtectedFirstFocus = true; return }
            hasUnconsumedTransientHint = false
            hintText = nil
            hasProtectedFirstFocus = false
            label = pool[poolIndex]
        }
        var next = 1
        while next == poolIndex { next += 1 }
        poolIndex = next
        label = pool[next]
        rotations += 1
    }
    mutating func blur() {}
    /// textViewDidChange → consumeTransientHintIfNeeded.
    mutating func type(_ s: String) {
        text += s
        guard hasUnconsumedTransientHint, !text.isEmpty else { return }
        hasUnconsumedTransientHint = false
        hintText = nil
        poolIndex = 0
        hasProtectedFirstFocus = false
        label = pool[0]
    }
}

print("▶️  1. first focus after the reply keeps the usage line")
do {
    var c = Composer()
    c.hintArrives(gen: 1, text: "Context 72% used")
    c.focus()                       // chat.autoFocusAfterReply, or the user's own tap
    check("line still on the label", c.label == "Context 72% used")
    check("no rotation happened", c.rotations == 0)
    check("allowance spent", c.hasProtectedFirstFocus)
    check("line still unconsumed", c.hasUnconsumedTransientHint)
}

print("▶️  2. blur, then focus again without typing → normal rotation, line gone")
do {
    var c = Composer()
    c.hintArrives(gen: 1, text: "Context 72% used")
    c.focus(); c.blur(); c.focus()
    check("rotated to a pool tip", c.pool.dropFirst().contains(c.label))
    check("exactly one rotation", c.rotations == 1)
    check("line retired", !c.hasUnconsumedTransientHint && c.hintText == nil)
    check("allowance state cleared", !c.hasProtectedFirstFocus)
    c.blur(); c.focus()
    check("third focus keeps rotating (not locked)", c.rotations == 2)
}

print("▶️  3. first focus then typing → the ordinary typing consumption")
do {
    var c = Composer()
    c.hintArrives(gen: 1, text: "Context 72% used")
    c.focus()
    c.type("h")
    check("consumed by typing", !c.hasUnconsumedTransientHint && c.hintText == nil)
    check("pool index back at default", c.poolIndex == 0 && c.label == "default")
    check("allowance state cleared by consumption", !c.hasProtectedFirstFocus)
    check("no rotation was triggered", c.rotations == 0)
}

print("▶️  4. a new generation re-arms the allowance")
do {
    var c = Composer()
    c.hintArrives(gen: 1, text: "Context 72% used")
    c.focus()                                       // spends it
    check("spent", c.hasProtectedFirstFocus)
    c.hintArrives(gen: 2, text: "Context 81% used")
    check("re-armed by the new generation", !c.hasProtectedFirstFocus)
    c.focus()
    check("second line survives ITS first focus", c.label == "Context 81% used" && c.rotations == 0)
    c.blur(); c.focus()
    check("and retires on its second", c.rotations == 1 && !c.hasUnconsumedTransientHint)
    c.hintArrives(gen: 2, text: "Context 81% used")
    check("same generation re-rendered does not revive it", !c.hasUnconsumedTransientHint)
}

print("▶️  5. no line showing → rotation untouched")
do {
    var c = Composer()
    c.focus(); c.blur(); c.focus()
    check("two rotations, never protected", c.rotations == 2 && !c.hasProtectedFirstFocus)
}

// MARK: - Source invariants
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
let bar = (try? String(contentsOf: root.appendingPathComponent("Views/Chat/ChatInputBar.swift"), encoding: .utf8)) ?? ""
print("▶️  source invariants")
check("allowance is Coordinator state", bar.contains("private(set) var hasProtectedFirstFocus = false"))
check("reset when a new generation is claimed", bar.contains("hasUnconsumedTransientHint = true\n            hasProtectedFirstFocus = false"))
check("first focus spends it and yields", bar.contains("guard hasProtectedFirstFocus else {\n                    hasProtectedFirstFocus = true"))
check("second focus retires the line", bar.contains("hintLog.info(\"[rotate] second focus — retiring usage line") && bar.contains("hasUnconsumedTransientHint = false\n                transientHint = nil\n                transientHintApplied = false\n                hasProtectedFirstFocus = false"))
check("a pending debounce cannot resurrect a retired line", bar.contains("contextHintDebounce?.cancel()\n                contextHintDebounce = nil\n                hasUnconsumedTransientHint = false"))
check("typing consumption also clears the allowance", bar.contains("placeholderPoolIndex = 0\n            transientHintApplied = false\n            hasProtectedFirstFocus = false"))
check("retire path restores the label before any early return", bar.contains("label.text = placeholderPool[placeholderPoolIndex]"))
check("VoiceOver / Reduce Motion handling untouched", bar.contains("guard !UIAccessibility.isVoiceOverRunning else { return }") && bar.contains("guard !UIAccessibility.isReduceMotionEnabled else {"))

print(failures == 0 ? "\n🎉 all checks passed" : "\n💥 \(failures) failure(s)")
exit(failures == 0 ? 0 : 1)
