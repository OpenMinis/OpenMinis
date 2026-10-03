//
//  TerminalTypes.swift
//  MinisApp
//
//  Data structures for the terminal emulator
//

import UIKit

// MARK: - Colors

/// Terminal color representation
enum TerminalColor: Equatable {
    case `default`
    case indexed(UInt8)       // 0-255 xterm palette
    case rgb(UInt8, UInt8, UInt8)
}

/// Standard xterm 256-color palette
struct TerminalPalette {
    static let colors: [UIColor] = {
        var palette = [UIColor]()
        palette.reserveCapacity(256)

        // 0-7: Standard colors
        palette.append(UIColor(red: 0/255, green: 0/255, blue: 0/255, alpha: 1))       // Black
        palette.append(UIColor(red: 205/255, green: 0/255, blue: 0/255, alpha: 1))     // Red
        palette.append(UIColor(red: 0/255, green: 205/255, blue: 0/255, alpha: 1))     // Green
        palette.append(UIColor(red: 205/255, green: 205/255, blue: 0/255, alpha: 1))   // Yellow
        palette.append(UIColor(red: 0/255, green: 0/255, blue: 238/255, alpha: 1))     // Blue
        palette.append(UIColor(red: 205/255, green: 0/255, blue: 205/255, alpha: 1))   // Magenta
        palette.append(UIColor(red: 0/255, green: 205/255, blue: 205/255, alpha: 1))   // Cyan
        palette.append(UIColor(red: 229/255, green: 229/255, blue: 229/255, alpha: 1)) // White

        // 8-15: Bright colors
        palette.append(UIColor(red: 127/255, green: 127/255, blue: 127/255, alpha: 1)) // Bright Black
        palette.append(UIColor(red: 255/255, green: 0/255, blue: 0/255, alpha: 1))     // Bright Red
        palette.append(UIColor(red: 0/255, green: 255/255, blue: 0/255, alpha: 1))     // Bright Green
        palette.append(UIColor(red: 255/255, green: 255/255, blue: 0/255, alpha: 1))   // Bright Yellow
        palette.append(UIColor(red: 92/255, green: 92/255, blue: 255/255, alpha: 1))   // Bright Blue
        palette.append(UIColor(red: 255/255, green: 0/255, blue: 255/255, alpha: 1))   // Bright Magenta
        palette.append(UIColor(red: 0/255, green: 255/255, blue: 255/255, alpha: 1))   // Bright Cyan
        palette.append(UIColor(red: 255/255, green: 255/255, blue: 255/255, alpha: 1)) // Bright White

        // 16-231: 6x6x6 color cube
        for r in 0..<6 {
            for g in 0..<6 {
                for b in 0..<6 {
                    let rv: CGFloat = r == 0 ? 0 : CGFloat(55 + 40 * r) / 255.0
                    let gv: CGFloat = g == 0 ? 0 : CGFloat(55 + 40 * g) / 255.0
                    let bv: CGFloat = b == 0 ? 0 : CGFloat(55 + 40 * b) / 255.0
                    palette.append(UIColor(red: rv, green: gv, blue: bv, alpha: 1))
                }
            }
        }

        // 232-255: Grayscale ramp
        for i in 0..<24 {
            let v = CGFloat(8 + 10 * i) / 255.0
            palette.append(UIColor(red: v, green: v, blue: v, alpha: 1))
        }

        return palette
    }()

    /// Default foreground color (light gray)
    static let defaultForeground = UIColor(red: 204/255, green: 204/255, blue: 204/255, alpha: 1)

    /// Default background color (black)
    static let defaultBackground = UIColor.black

    /// Resolve a TerminalColor to a UIColor
    static func resolve(_ color: TerminalColor, isForeground: Bool, bold: Bool = false) -> UIColor {
        switch color {
        case .default:
            return isForeground ? defaultForeground : defaultBackground
        case .indexed(let idx):
            // Bold + standard color (0-7) → bright variant (8-15)
            if bold && isForeground && idx < 8 {
                return colors[Int(idx) + 8]
            }
            return colors[Int(idx)]
        case .rgb(let r, let g, let b):
            return UIColor(red: CGFloat(r)/255, green: CGFloat(g)/255, blue: CGFloat(b)/255, alpha: 1)
        }
    }
}

// MARK: - Text Attributes

/// Text rendering attributes as an OptionSet
struct TextAttributes: OptionSet, Equatable {
    let rawValue: UInt16

    static let bold          = TextAttributes(rawValue: 1 << 0)
    static let dim           = TextAttributes(rawValue: 1 << 1)
    static let italic        = TextAttributes(rawValue: 1 << 2)
    static let underline     = TextAttributes(rawValue: 1 << 3)
    static let blink         = TextAttributes(rawValue: 1 << 4)
    static let inverse       = TextAttributes(rawValue: 1 << 5)
    static let hidden        = TextAttributes(rawValue: 1 << 6)
    static let strikethrough = TextAttributes(rawValue: 1 << 7)
}

// MARK: - Terminal Cell

/// A single character cell in the terminal grid
struct TerminalCell: Equatable {

    /// [T-terminal-glyph-key-no-character] A cell's Unicode scalars, captured
    /// once when the cell is written and stored as plain integers.
    ///
    /// The renderer needs these on every redraw to key its glyph-advance
    /// cache. Deriving them there — `character.unicodeScalars.map(\.value)` —
    /// meant walking String storage per glyph per frame, which is where
    /// 1.14(16) trapped inside the Swift runtime. Computing them at write
    /// time does the work once, on bytes the ANSI parser has just validated,
    /// and hands the hot path nothing but integers.
    ///
    /// `first` is inline because virtually every cell is a single scalar;
    /// `rest` is non-empty only for a combining sequence.
    struct Glyph: Equatable {
        var first: UInt32
        var rest: [UInt32]

        init(_ character: Character) {
            var it = character.unicodeScalars.makeIterator()
            // A Character is never empty, but read defensively rather than
            // force-unwrap: this runs on every cell written.
            self.first = it.next()?.value ?? 0x20
            var tail: [UInt32] = []
            while let s = it.next() { tail.append(s.value) }
            self.rest = tail
        }

        static let blank = Glyph(" ")
    }

    /// The cell's character. Write it through `setCharacter(_:)` — assigning
    /// this directly would leave `glyph` stale.
    private(set) var character: Character = " "
    /// Scalars for `character`, kept in lockstep by every writer. See `Glyph`.
    private(set) var glyph: Glyph = .blank
    var foreground: TerminalColor = .default
    var background: TerminalColor = .default
    var attributes: TextAttributes = []
    /// Character display width (1 for ASCII, 2 for CJK fullwidth)
    var width: UInt8 = 1

    /// Whether this cell is part of a wide character (the trailing half)
    var isWideTrailer: Bool = false

    /// Explicit rather than memberwise, so `glyph` cannot be left behind.
    /// A `didSet` on `character` would not have covered this: didSet does not
    /// fire from an initializer, so every memberwise construction — which is
    /// how `CursorStyle.makeCell` builds every printed cell — would have
    /// stored a blank glyph and mis-measured the whole terminal.
    init(character: Character = " ",
         foreground: TerminalColor = .default,
         background: TerminalColor = .default,
         attributes: TextAttributes = [],
         width: UInt8 = 1,
         isWideTrailer: Bool = false) {
        self.character = character
        self.glyph = Glyph(character)
        self.foreground = foreground
        self.background = background
        self.attributes = attributes
        self.width = width
        self.isWideTrailer = isWideTrailer
    }

    /// Replace the character, recomputing `glyph` with it.
    mutating func setCharacter(_ c: Character) {
        character = c
        glyph = Glyph(c)
    }

    static let blank = TerminalCell()
}

// MARK: - Cursor

/// Current SGR state applied to new characters
struct CursorStyle: Equatable {
    var foreground: TerminalColor = .default
    var background: TerminalColor = .default
    var attributes: TextAttributes = []

    /// Create a TerminalCell with the current style
    func makeCell(character: Character, width: UInt8 = 1) -> TerminalCell {
        TerminalCell(
            character: character,
            foreground: foreground,
            background: background,
            attributes: attributes,
            width: width
        )
    }
}

/// Cursor visual shape
enum CursorShape {
    case block
    case underline
    case bar
}

// MARK: - Input

/// Arrow key direction for keyboard handling
enum ArrowDirection {
    case up, down, left, right
}
