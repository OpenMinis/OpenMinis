// Tests for [T-terminal-glyph-key-no-character] — the glyph-advance cache key
// must not read String storage, and the precomputed scalars must always agree
// with the character they were derived from.
//
// Standalone (`swift TerminalGlyphKeyTests.swift`) like its neighbours: the
// MinisTests target cannot link for a simulator (deps/libs/libish_emu.a is
// device-only arm64). The types are reproduced here; section [4] re-reads the
// shipping sources so the copies cannot drift.

import Foundation

var failures = 0
func check(_ l: String, _ a: Bool, _ e: Bool = true) {
    if a == e { print("  ✅ \(l)") } else { print("  ❌ \(l) — expected \(e), got \(a)"); failures += 1 }
}
func checkEq<T: Equatable>(_ l: String, _ a: T, _ b: T) {
    if a == b { print("  ✅ \(l)") } else { print("  ❌ \(l)\n     expected: \(b)\n     actual:   \(a)"); failures += 1 }
}

// MARK: - Reproduced types

struct Glyph: Equatable {
    var first: UInt32
    var rest: [UInt32]
    init(_ character: Character) {
        var it = character.unicodeScalars.makeIterator()
        self.first = it.next()?.value ?? 0x20
        var tail: [UInt32] = []
        while let s = it.next() { tail.append(s.value) }
        self.rest = tail
    }
    static let blank = Glyph(" ")
}

struct Cell: Equatable {
    private(set) var character: Character = " "
    private(set) var glyph: Glyph = .blank
    var width: UInt8 = 1
    init(character: Character = " ", width: UInt8 = 1) {
        self.character = character
        self.glyph = Glyph(character)
        self.width = width
    }
    mutating func setCharacter(_ c: Character) { character = c; glyph = Glyph(c) }
}

struct Key: Hashable {
    let first: UInt32
    let rest: [UInt32]
    let font: ObjectIdentifier
    init(glyph: Glyph, font: AnyObject) {
        self.first = glyph.first
        self.rest = glyph.rest
        self.font = ObjectIdentifier(font)
    }
}

final class FontStub {}
let fontA = FontStub(), fontB = FontStub()

// The alphabet a terminal actually shows, plus the awkward tail.
let samples: [Character] = [
    "a", "Z", "0", " ", "~", "\t",
    "中", "日", "한",            // CJK fullwidth
    "é",                          // precomposed
    "e\u{301}",                   // combining — multi-scalar
    "😀",                          // emoji
    "👍🏽",                         // emoji + skin-tone modifier
    "🇯🇵",                          // regional indicator pair
    "\u{200B}",                   // zero-width space
    "\u{1F469}\u{200D}\u{1F4BB}", // ZWJ sequence
]

print("\n[1] Every cell's glyph matches its character")
for c in samples {
    let cell = Cell(character: c)
    let expected = c.unicodeScalars.map(\.value)
    let actual = [cell.glyph.first] + cell.glyph.rest
    checkEq("scalars for \(c.debugDescription)", actual, expected)
}

print("\n[2] The memberwise-init trap is closed")
// This is the specific reason `character` is not a plain `var` with a didSet:
// didSet does not fire from an initializer, so a memberwise construction would
// have stored Glyph.blank while the character was something else — every
// printed cell mis-measured.
do {
    let cell = Cell(character: "中")
    check("an initializer-built cell is NOT left blank", cell.glyph != Glyph.blank)
    checkEq("…and carries the right scalar", cell.glyph.first, 0x4E2D)
    var m = Cell(character: "a")
    m.setCharacter("中")
    checkEq("setCharacter updates the glyph too", m.glyph, Glyph("中"))
    checkEq("…and the character", String(m.character), "中")
}

print("\n[3] Key identity behaves like the cache needs")
do {
    checkEq("same char + same font collide (a cache hit)",
            Key(glyph: Glyph("a"), font: fontA), Key(glyph: Glyph("a"), font: fontA))
    check("same char + different font do NOT collide",
          Key(glyph: Glyph("a"), font: fontA) != Key(glyph: Glyph("a"), font: fontB))
    check("different chars do not collide",
          Key(glyph: Glyph("a"), font: fontA) != Key(glyph: Glyph("b"), font: fontA))
    // The documented trade-off: normalization-equal characters are distinct
    // rows, each measuring to the same advance.
    check("precomposed and combining forms are separate rows",
          Key(glyph: Glyph("é"), font: fontA) != Key(glyph: Glyph("e\u{301}"), font: fontA))
    // Every sample must be hashable without trapping, and round-trip a dict.
    var cache: [Key: Int] = [:]
    for (i, c) in samples.enumerated() { cache[Key(glyph: Glyph(c), font: fontA)] = i }
    checkEq("all samples occupy distinct rows", cache.count, samples.count)
    for (i, c) in samples.enumerated() {
        if cache[Key(glyph: Glyph(c), font: fontA)] != i {
            check("round-trip \(c.debugDescription)", false); break
        }
    }
    check("every sample round-trips through the cache", true)
    // Single-scalar cells must not allocate a tail — that is the whole point
    // of splitting `first` out.
    check("ASCII carries no heap tail", Glyph("a").rest.isEmpty)
    check("CJK carries no heap tail", Glyph("中").rest.isEmpty)
    check("a combining sequence does carry one", !Glyph("e\u{301}").rest.isEmpty)
}

print("\n[4] Shipping sources match these assumptions")
func source(_ rel: String) -> String {
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return (try? String(contentsOf: root.appendingPathComponent(rel), encoding: .utf8)) ?? ""
}
let canvas = source("iSH/Terminal/TerminalCanvasView.swift")
let types  = source("iSH/Terminal/TerminalTypes.swift")

if canvas.isEmpty || types.isEmpty { print("  ⏭  sources not readable") } else {
    // The regression itself: the key must never derive scalars from a
    // Character. Scoped to CODE lines — the doc comment above the key quotes
    // the old expression on purpose, to record what crashed.
    let canvasCode = canvas.components(separatedBy: "\n")
        .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("//") }
        .joined(separator: "\n")
    check("no code path maps over unicodeScalars",
          !canvasCode.contains("unicodeScalars"))
    check("the crashing initializer signature is gone",
          !canvas.contains("init(character: Character, font: UIFont)"))
    check("the key is built from the precomputed glyph",
          canvas.contains("init(glyph: TerminalCell.Glyph, font: UIFont)"))
    check("the renderer passes the cell's stored glyph",
          canvas.contains("glyph: cell.glyph"))
    check("the scalar walk happens once, at write time",
          types.contains("struct Glyph: Equatable")
          && types.contains("var it = character.unicodeScalars.makeIterator()"))
    check("TerminalCell has an explicit init (didSet would not cover memberwise)",
          types.contains("init(character: Character = \" \","))
    check("…which seeds the glyph", types.contains("self.glyph = Glyph(character)"))
    check("character cannot be written without updating the glyph",
          types.contains("private(set) var character: Character")
          && types.contains("mutating func setCharacter("))
    check("the cache stays bounded", canvas.contains("glyphAdvanceCacheMax"))
}

print("\n\(failures == 0 ? "✅ ALL PASSED" : "❌ \(failures) FAILURE(S)")")
exit(failures == 0 ? 0 : 1)
