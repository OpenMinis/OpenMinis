#!/usr/bin/env swift
// [T-model-override-silent-drop] The model detail sheet silently discarded a
// user's edit when the typed value happened to equal the current auto-detected
// value.
//
// Reported: a model's context window was set to 400000 and saved; hours later it
// read 1048576 again. The daily model refresh had run in between
// (`refreshAllModelsIfNeeded: FIRE` at 09:24:53 in the device log), and the
// vendor value came through because there was no override to suppress it.
//
// Root cause: the sheet LOADS every editable field from `entry.model` — overrides
// overlaid on baseModel — but the save gate compared the typed value against
// `entry.baseModel`. That asymmetry means:
//
//   • typing a value equal to today's auto value records NOTHING, so the next
//     vendor bump shows straight through;
//   • an EXISTING override is cleared the moment the vendor catches up to it,
//     because the sheet shows the override's value and it now equals base.
//
// Confirmed on device (iPhone 11, 2026-09-21): typing 1000000 into a field whose
// auto value was already 1000000 and pressing Save left `overrides.contextWindow`
// null.
//
// The rule the UI actually promises is "leave empty to use the auto-detected
// value", so EMPTY is the way back to auto and a filled field is a choice that
// must persist. Toggles have no empty state, so for those the rule is "differs
// from what the sheet showed, OR an override already exists".
//
// Run: swift ModelOverrideSaveGateTests.swift
//
// Convention: a bare `swift` script — `deps/libs/libish_emu.a` is device-arm64
// only, so the app cannot link for the simulator. The save gate is ported
// verbatim below; section [4] greps the shipping source so a rewrite fails here
// rather than silently passing a stale copy.
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
func codeOnly(_ src: String) -> String {
    src.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
}

// MARK: - Ported model (ModelEntry.swift:195-208, :86-92)

struct Overrides: Equatable {
    var contextWindow: Int? = nil
    var supportsReasoning: Bool? = nil
    var modality: Set<String>? = nil
    var isEmpty: Bool { contextWindow == nil && supportsReasoning == nil && modality == nil }
}
struct Base {
    var contextWindow: Int?
    var supportsReasoning: Bool?
    var modality: Set<String>
}
struct Entry {
    var base: Base
    var overrides: Overrides
    /// `ModelEntry.model` — overrides overlaid on baseModel.
    var effectiveContextWindow: Int? { overrides.contextWindow ?? base.contextWindow }
    var effectiveSupportsReasoning: Bool? { overrides.supportsReasoning ?? base.supportsReasoning }
    var effectiveModality: Set<String> { overrides.modality ?? base.modality }
}

/// What `loadFromEntry` puts in the sheet (ProviderInstanceDetailView.swift ~:1686-1700):
/// every editable field comes from the EFFECTIVE value.
func sheetFields(_ e: Entry) -> (ctxText: String, thinking: Bool, modality: Set<String>) {
    (e.effectiveContextWindow.map(String.init) ?? "",
     e.effectiveSupportsReasoning ?? false,
     e.effectiveModality)
}

/// Verbatim port of the FIXED save gate (ProviderInstanceDetailView.swift ~:1800).
func saveGate(entry e: Entry, ctxText: String, thinking: Bool, modality: Set<String>) -> Overrides {
    var out = Overrides()
    let trimmed = ctxText.trimmingCharacters(in: .whitespaces)
    let typed: Int? = trimmed.isEmpty ? nil : Int(trimmed)

    let baselineModality = e.overrides.modality ?? e.base.modality
    if modality != baselineModality || e.overrides.modality != nil { out.modality = modality }

    out.contextWindow = typed

    let baselineThinking = e.effectiveSupportsReasoning ?? false
    if thinking != baselineThinking || e.overrides.supportsReasoning != nil {
        out.supportsReasoning = thinking
    }
    return out
}

/// The OLD gate, kept only to prove the tests are non-vacuous.
func oldSaveGate(entry e: Entry, ctxText: String, thinking: Bool, modality: Set<String>) -> Overrides {
    var out = Overrides()
    let trimmed = ctxText.trimmingCharacters(in: .whitespaces)
    let typed: Int? = trimmed.isEmpty ? nil : Int(trimmed)
    if modality != e.base.modality { out.modality = modality }
    if typed != e.base.contextWindow { out.contextWindow = typed }
    if thinking != (e.base.supportsReasoning ?? false) { out.supportsReasoning = thinking }
    return out
}

let textOnly: Set<String> = ["text"]

print("▶️  1. the reported bug: typing a value equal to the auto value")
do {
    // deepseek-v4-pro on device: base 1000000, no override.
    let e = Entry(base: Base(contextWindow: 1_000_000, supportsReasoning: nil, modality: textOnly),
                  overrides: Overrides())
    let f = sheetFields(e)
    checkEq("the sheet prefills the auto value", f.ctxText, "1000000")

    let saved = saveGate(entry: e, ctxText: "1000000", thinking: f.thinking, modality: f.modality)
    checkEq("the override IS now recorded", saved.contextWindow, 1_000_000)

    // Exactly what was measured on device before the fix.
    let old = oldSaveGate(entry: e, ctxText: "1000000", thinking: f.thinking, modality: f.modality)
    check("PRE-FIX this recorded nothing (the device measurement)", old.contextWindow == nil)

    // And the consequence: a vendor bump would show straight through.
    var bumped = e
    bumped.base.contextWindow = 1_048_576
    bumped.overrides = old
    checkEq("PRE-FIX a later vendor bump wins", bumped.effectiveContextWindow, 1_048_576)
    var fixed = e
    fixed.base.contextWindow = 1_048_576
    fixed.overrides = saved
    checkEq("POST-FIX the user's value survives the bump", fixed.effectiveContextWindow, 1_000_000)
}

print("\n▶️  2. empty is the documented way back to auto-detection")
do {
    let e = Entry(base: Base(contextWindow: 1_048_576, supportsReasoning: nil, modality: textOnly),
                  overrides: Overrides(contextWindow: 400_000))
    checkEq("the sheet shows the override, not the base", sheetFields(e).ctxText, "400000")

    let cleared = saveGate(entry: e, ctxText: "", thinking: false, modality: textOnly)
    check("clearing the field removes the override", cleared.contextWindow == nil)

    var after = e; after.overrides = cleared
    checkEq("…so the auto value takes over again", after.effectiveContextWindow, 1_048_576)
    // Whitespace must count as empty, or a stray space would pin a nil override.
    let blank = saveGate(entry: e, ctxText: "   ", thinking: false, modality: textOnly)
    check("a whitespace-only field also means auto", blank.contextWindow == nil)
}

print("\n▶️  3. an existing override survives the vendor catching up")
do {
    // The second half of the bug: base moves TO the override's value, and a
    // no-op re-save used to clear the override.
    let e = Entry(base: Base(contextWindow: 400_000, supportsReasoning: true, modality: textOnly),
                  overrides: Overrides(contextWindow: 400_000, supportsReasoning: true))
    let f = sheetFields(e)

    let saved = saveGate(entry: e, ctxText: f.ctxText, thinking: f.thinking, modality: f.modality)
    checkEq("ctx override survives a no-op re-save", saved.contextWindow, 400_000)
    checkEq("thinking override survives too", saved.supportsReasoning, true)

    let old = oldSaveGate(entry: e, ctxText: f.ctxText, thinking: f.thinking, modality: f.modality)
    check("PRE-FIX both were silently dropped",
          old.contextWindow == nil && old.supportsReasoning == nil)

    // And then a vendor flip back would have lost the user's choice.
    var flipped = e
    flipped.base.supportsReasoning = false
    flipped.overrides = old
    checkEq("PRE-FIX a later vendor flip wins", flipped.effectiveSupportsReasoning, false)
    var kept = e
    kept.base.supportsReasoning = false
    kept.overrides = saved
    checkEq("POST-FIX the user's toggle survives", kept.effectiveSupportsReasoning, true)
}

print("\n▶️  4. untouched fields still inherit vendor updates")
do {
    // The fix must not pin every field forever — a user who never opened the
    // sheet keeps receiving vendor capability bumps.
    let e = Entry(base: Base(contextWindow: 128_000, supportsReasoning: false, modality: textOnly),
                  overrides: Overrides())
    let f = sheetFields(e)
    let saved = saveGate(entry: e, ctxText: f.ctxText, thinking: f.thinking, modality: f.modality)
    check("a toggle left alone records no override", saved.supportsReasoning == nil)
    check("modality left alone records no override", saved.modality == nil)

    var bumped = e
    bumped.base.supportsReasoning = true
    bumped.overrides = saved
    checkEq("…so a vendor capability bump still flows through",
            bumped.effectiveSupportsReasoning, true)

    // Changing a toggle IS recorded.
    let toggled = saveGate(entry: e, ctxText: f.ctxText, thinking: true, modality: f.modality)
    checkEq("flipping the toggle records it", toggled.supportsReasoning, true)
}

print("\n▶️  5. shipping source still carries the fix")
do {
    let src = codeOnly(source("Views/Providers/ProviderInstanceDetailView.swift"))
    if src.isEmpty { print("  ⏭  source not readable") } else {
        check("contextWindow is recorded unconditionally from the typed value",
              src.contains("newOverrides.contextWindow = typedContextWindow"))
        check("…and the old base comparison is gone",
              !src.contains("if typedContextWindow != entry.baseModel.contextWindow {"))
        check("modality baselines against the EFFECTIVE value",
              src.contains("let baselineModality = entry.model.modalityOverride"))
        check("…and keeps an existing override alive",
              src.contains("|| entry.overrides.modalityOverride != nil {"))
        check("thinking baselines against the EFFECTIVE value",
              src.contains("let baselineThinking = entry.model.supportsReasoning ?? false"))
        check("…and keeps an existing override alive",
              src.contains("|| entry.overrides.supportsReasoning != nil {"))
        // baseModel must remain API truth — the file's own comment explains why
        // anything written there dies at the next refresh.
        check("baseModel is still copied through untouched",
              src.contains("contextWindow: entry.baseModel.contextWindow,"))
    }
}

print("")
if failures == 0 { print("✅ ALL PASSED") } else { print("❌ \(failures) FAILURE(S)") }
exit(failures == 0 ? 0 : 1)
