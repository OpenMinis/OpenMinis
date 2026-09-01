import SwiftMath
import UIKit

private let logger = AppLogger(category: "SwiftMathRenderer")

/// Renders LaTeX math formulas to UIImage using SwiftMath (pure native, no WebView).
enum SwiftMathRenderer {

    /// LRU-style cache keyed by "D:<latex>" or "I:<latex>".
    private static let cache = NSCache<NSString, SwiftMathRenderResult>()
    private static let cacheSetup: Void = { cache.countLimit = 256 }()

    /// [T-ios-math-invisible-dark-mode] The interface style the formula bitmaps
    /// must be drawn for.
    ///
    /// Both render paths rasterise a DETACHED view / attributed string through
    /// `UIGraphicsImageRenderer`. Nothing in that pipeline has a window or a
    /// trait ancestor, so a dynamic `UIColor` (`.label`) resolves against the
    /// process default — light, i.e. BLACK — and every formula came out as
    /// black glyphs on the dark transcript: reserved its space, drew nothing.
    /// Verified on iPhone 8 / iOS 16.1: identical message, dark = blank bands,
    /// light = renders perfectly, dark again = blank (shots 14/16/17 of the
    /// 2026-08-14 regression run).
    ///
    /// Read from the key window because that is where BOTH inputs land: the
    /// system appearance, and the in-app Theme override, which
    /// `ContentView`'s `appearanceMode` picker writes straight onto
    /// `window.overrideUserInterfaceStyle` (ContentView.swift:6948). Reading
    /// `UITraitCollection.current` instead would miss the override whenever the
    /// user's theme disagrees with the system.
    ///
    /// Falls back to `.current` off-main or before a window exists — the
    /// renderers are main-thread-only in practice, and a wrong guess is
    /// self-correcting because the style is part of the cache key.
    static var interfaceStyle: UIUserInterfaceStyle {
        guard Thread.isMainThread else { return UITraitCollection.current.userInterfaceStyle }
        let window = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first
        guard let window else { return UITraitCollection.current.userInterfaceStyle }
        if window.overrideUserInterfaceStyle != .unspecified {
            return window.overrideUserInterfaceStyle
        }
        return window.traitCollection.userInterfaceStyle
    }

    /// `UIColor.label` resolved for `interfaceStyle` — a CONCRETE color, so it
    /// survives rasterisation in a trait-less context.
    private static func resolvedLabelColor() -> UIColor {
        UIColor.label.resolvedColor(with: UITraitCollection(userInterfaceStyle: interfaceStyle))
    }

    /// Render a LaTeX formula synchronously. Returns nil on parse error.
    static func render(latex: String, displayMode: Bool, fontSize: CGFloat = 17) -> SwiftMathRenderResult? {
        _ = cacheSetup

        let key = cacheKey(latex: latex, displayMode: displayMode, fontSize: fontSize) as NSString
        if let cached = cache.object(forKey: key) {
            return cached
        }

        let preprocessed = preprocessLatex(latex)

        // [issue #117-2] SwiftMath 1.7.3's atom(forCharacter:) returns nil for
        // CJK and simply `continue`s, so `\text{中文}` "parses" cleanly and
        // renders blank — a success as far as `label.error` is concerned, which
        // meant the Unicode fallback below could never take over. Detect the
        // case up front and decline, letting the caller fall back to
        // `renderFallback`, which draws CJK with the system font. (The library's
        // unreleased main branch adds a text-mode Unicode fallback; once that
        // ships this guard can go.)
        if containsUnrenderableText(preprocessed) {
            return nil
        }

        // Use MTMathUILabel for rendering — more reliable on iOS 18 than MTMathImage.
        let label = MTMathUILabel()
        label.latex = preprocessed
        label.fontSize = fontSize
        // [T-ios-math-invisible-dark-mode] MUST be pre-resolved: this label is
        // never added to a window, so a dynamic color would rasterise black.
        label.textColor = resolvedLabelColor()
        label.labelMode = displayMode ? .display : .text
        label.textAlignment = displayMode ? .center : .left
        label.backgroundColor = .clear

        // Check for parse errors before rendering.
        if let error = label.error {
            logger.warning("parse error for '\(latex.prefix(60))': \(error.localizedDescription)")
            return nil
        }

        // Force layout to get intrinsic size.
        label.sizeToFit()
        let size = label.intrinsicContentSize
        guard size.width > 0, size.height > 0 else {
            logger.warning("zero size for '\(latex.prefix(60))'")
            return nil
        }

        // Add horizontal padding — MTMathUILabel.intrinsicContentSize sometimes
        // underreports width, causing inline formulas to overlap with following text.
        let hPad: CGFloat = 4
        // [issue #117-4] Height must clear SwiftMath's own minimum, not just
        // the intrinsic size. `_layoutSubviews` clamps its layout height UP to
        // fontSize/2 for short formulas (a bare `x`: ascent 7.73 + descent 0.19
        // = 7.92 < 8.745 at 17.49pt) and then centres on THAT, which pushed the
        // baseline to a NEGATIVE offset — the formula drew partly below the
        // bitmap and the attachment could not be baseline-aligned. Giving the
        // label at least the clamped height makes the clamp inert, so
        // `textY == descent` exactly and the glyphs stay inside the image.
        let minLayoutHeight = fontSize / 2
        let pixelSize = CGSize(
            width: ceil(size.width + hPad),
            height: ceil(max(size.height, minLayoutHeight))
        )
        label.frame = CGRect(origin: .zero, size: pixelSize)
        // [issue #117-4] Setting `frame` only MARKS layout dirty — SwiftMath's
        // `_layoutSubviews` (which builds and positions `displayList`) runs on
        // the next layout pass, which for an off-screen label never comes on
        // its own. Without this the bitmap was rendered from the displayList
        // positioned for the earlier `sizeToFit()` frame, so the formula drew
        // several points too high inside its own image (measured: `x²` ink
        // ended 13px above the bitmap bottom instead of 1px) and the descent
        // read below described a layout the pixels did not match.
        label.layoutIfNeeded()

        // Render the label to an image.
        // MTMathUILabel draws in a flipped CG coordinate system, so
        // we flip the context before rendering via the CALayer.
        let renderer = UIGraphicsImageRenderer(size: pixelSize)
        let image = renderer.image { ctx in
            let cgCtx = ctx.cgContext
            cgCtx.saveGState()
            cgCtx.translateBy(x: 0, y: pixelSize.height)
            cgCtx.scaleBy(x: 1, y: -1)
            label.layer.render(in: cgCtx)
            cgCtx.restoreGState()
        }

        // [issue #117-4] Capture where the baseline actually landed inside the
        // bitmap, so the text attachment can sit the formula ON the surrounding
        // text's baseline instead of guessing from the image height.
        //
        // The `layoutIfNeeded()` above ran `_layoutSubviews`, so `displayList`
        // is populated and positioned for THIS frame. SwiftMath places it with
        // (MTMathUILabel._layoutSubviews):
        //
        //     height = ascent + descent, clamped UP to fontSize/2
        //     textY  = (availableHeight - height) / 2 + descent + insets.bottom
        //
        // in a bottom-up coordinate system — textY IS the baseline's distance
        // from the label's bottom edge. The clamp is replicated rather than
        // assumed inert: `pixelSize.height` is a ceil() of the intrinsic size,
        // so availableHeight generally exceeds ascent+descent by a sub-point
        // remainder, and for a very short formula (e.g. a bare `-`) the
        // fontSize/2 floor genuinely engages and shifts the baseline down.
        // Reading label.displayList?.position.y would be equivalent; the
        // formula is spelled out so the dependency on SwiftMath's internal
        // layout is visible if the library ever changes it.
        var baselineFromBottom: CGFloat = 0
        if let display = label.displayList {
            let availableHeight = pixelSize.height - label.contentInsets.top - label.contentInsets.bottom
            var height = display.ascent + display.descent
            if height < fontSize / 2 { height = fontSize / 2 }
            baselineFromBottom = (availableHeight - height) / 2 + display.descent + label.contentInsets.bottom
        }

        let result = SwiftMathRenderResult(image: image, size: pixelSize, baselineFromBottom: baselineFromBottom)
        cache.setObject(result, forKey: key)
        return result
    }

    // MARK: - Internal

    private static func cacheKey(latex: String, displayMode: Bool, fontSize: CGFloat) -> String {
        // [T-ios-math-invisible-dark-mode] The bitmap now bakes in a CONCRETE
        // text color, so the interface style is part of its identity. Without
        // this component, fixing the color alone would still show blank
        // formulas after a theme switch: the entries rendered before the switch
        // stay in the cache and get served back with the old (now invisible)
        // glyph color. Cheap insurance — a style flip is rare, and the worst
        // case is re-rendering at ~0.6ms per formula into a 256-entry cache.
        let style = interfaceStyle == .dark ? "dk:" : "lt:"
        return style + (displayMode ? "D:" : "I:") + "\(Int(fontSize)):" + latex
    }

    /// [issue #117-2] True when the formula carries characters SwiftMath 1.7.3
    /// drops on the floor instead of reporting as an error — CJK, emoji, and
    /// anything else outside the Latin Modern coverage its atom factory knows.
    /// Rendering these through MTMathUILabel yields a silently blank or
    /// partially-missing image, so callers should use the Unicode fallback.
    private static func containsUnrenderableText(_ latex: String) -> Bool {
        latex.unicodeScalars.contains { scalar in
            // The cutoff sits just under the CJK blocks (U+3000 and up) and
            // above everything the font does cover: Latin-1 math (± U+00B1,
            // × U+00D7), Greek (α U+03B1 … π U+03C0), arrows and Mathematical
            // Operators (→ U+2192, ∑ U+2211, √ U+221A, ∫ U+222B, ≤ U+2264).
            // Above it lie CJK, Hangul, kana and emoji — exactly the scalars
            // the atom factory skips.
            scalar.value > 0x2FFF
        }
    }

    /// Normalize LaTeX commands that SwiftMath doesn't support well.
    private static func preprocessLatex(_ latex: String) -> String {
        var s = latex
            .trimmingCharacters(in: .whitespacesAndNewlines)

        s = s.replacingOccurrences(of: "\\dots", with: "\\ldots")
        s = s.replacingOccurrences(of: "\\implies", with: "\\Rightarrow")
        s = s.replacingOccurrences(of: "\\iff", with: "\\Leftrightarrow")
        s = s.replacingOccurrences(of: "\\dfrac", with: "\\frac")
        s = s.replacingOccurrences(of: "\\tfrac", with: "\\frac")
        s = s.replacingOccurrences(of: "\\begin{align}", with: "\\begin{aligned}")
        s = s.replacingOccurrences(of: "\\end{align}", with: "\\end{aligned}")
        s = s.replacingOccurrences(of: "\\begin{align*}", with: "\\begin{aligned}")
        s = s.replacingOccurrences(of: "\\end{align*}", with: "\\end{aligned}")
        s = s.replacingOccurrences(of: "\\begin{gather}", with: "\\begin{gathered}")
        s = s.replacingOccurrences(of: "\\end{gather}", with: "\\end{gathered}")
        s = s.replacingOccurrences(of: "\\begin{gather*}", with: "\\begin{gathered}")
        s = s.replacingOccurrences(of: "\\end{gather*}", with: "\\end{gathered}")
        // [issue #117-2] `\text{` / `\textbf{` / `\operatorname{` are deliberately
        // NOT rewritten to their `\math…` counterparts any more. SwiftMath maps
        // `\text` and `\mathrm` to the same `.roman` font style, so the rewrite
        // looked harmless — but MTMathListBuilder flips `spacesAllowed` on for
        // `text` alone (`spacesAllowed = command == "text"`), which is the one
        // thing that makes `\text{}` preserve spaces. Rewriting it silently
        // collapsed `\text{hello world}` to "helloworld". Likewise
        // `\operatorname` is special-cased in the builder to emit a real
        // operator with correct spacing, which `\mathrm` does not. These
        // rewrites are a leftover from the old iosMath dependency, which had no
        // `\text` support; the current library handles all three natively.
        s = s.replacingOccurrences(of: "\\bold{", with: "\\mathbf{")
        s = s.replacingOccurrences(of: "\\displaystyle", with: "")

        return s
    }

    // MARK: - Unicode Fallback

    /// Render a LaTeX formula to UIImage by converting to Unicode text
    /// and drawing with the system font. Handles CJK inside \text{} and
    /// common math operators that SwiftMath's Latin Modern font can't show.
    static func renderFallback(latex: String, displayMode: Bool, fontSize: CGFloat = 17) -> SwiftMathRenderResult? {
        let key = ("F:" + cacheKey(latex: latex, displayMode: displayMode, fontSize: fontSize)) as NSString
        if let cached = cache.object(forKey: key) { return cached }

        let unicode = latexToUnicode(latex)
        guard !unicode.isEmpty else { return nil }

        let textFont = UIFont.systemFont(ofSize: fontSize)
        let mathFont = UIFont(name: "STIXTwoMath-Regular", size: fontSize) ?? textFont
        let boldFont = UIFont.boldSystemFont(ofSize: fontSize)

        let attributed = buildAttributedString(unicode, textFont: textFont, mathFont: mathFont, boldFont: boldFont)
        guard attributed.length > 0 else { return nil }

        // [issue #117-3] Bound the fallback image. A mispaired `$$` could
        // capture whole paragraphs, and an unlimited height turned that into a
        // giant wall of text laid straight into the transcript. The closing
        // searches no longer cross code boundaries, but keep a ceiling here so
        // any future runaway degrades into a clipped block rather than a page.
        let constraintWidth: CGFloat = displayMode ? 600 : 2000
        let maxHeight: CGFloat = displayMode ? 2000 : 200
        let boundingRect = attributed.boundingRect(
            with: CGSize(width: constraintWidth, height: maxHeight),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        // [issue #117-2 follow-up] Simulated scripts are drawn with a
        // `.baselineOffset`, so a superscript rides above the line box and a
        // subscript below it. `boundingRect` accounts for that, but the old
        // 1pt top inset / 2pt height slack left no room for the overshoot and
        // clipped the tallest glyphs. Give the raised and lowered runs a couple
        // of points each; costs nothing when the formula has no scripts.
        let scriptPad: CGFloat = 3
        let pixelSize = CGSize(
            width: ceil(boundingRect.width) + 4,
            height: min(ceil(boundingRect.height) + scriptPad * 2, maxHeight) + 2
        )
        guard pixelSize.width > 0, pixelSize.height > 0 else { return nil }

        let renderer = UIGraphicsImageRenderer(size: pixelSize)
        let image = renderer.image { _ in
            attributed.draw(in: CGRect(origin: CGPoint(x: 2, y: 1 + scriptPad), size: boundingRect.size))
        }
        let result = SwiftMathRenderResult(image: image, size: pixelSize)
        cache.setObject(result, forKey: key)
        return result
    }

    private static func buildAttributedString(_ text: String, textFont: UIFont, mathFont: UIFont, boldFont: UIFont) -> NSAttributedString {
        let result = NSMutableAttributedString()
        // [T-ios-math-invisible-dark-mode] Same trap as the SwiftMath path —
        // `attributed.draw(in:)` below runs inside a trait-less image renderer.
        let textColor = resolvedLabelColor()

        // [issue #117-2 follow-up] Script state, driven by the sentinels
        // `markScripts` planted. Nested scripts (`x^{a^{b}}`) push a level; the
        // shift compounds but the font floor stops it collapsing to unreadable.
        // 0 = baseline, >0 = superscript depth, <0 = subscript depth.
        var scriptDepth = 0
        var isSuperscript = true

        for ch in text {
            if ch == supOpen { scriptDepth += 1; isSuperscript = true; continue }
            if ch == subOpen { scriptDepth += 1; isSuperscript = false; continue }
            if ch == scriptClose { scriptDepth = max(0, scriptDepth - 1); continue }

            // Plain baseline text: unchanged from before.
            guard scriptDepth > 0 else {
                let font = fallbackFont(for: ch, textFont: textFont, mathFont: mathFont)
                result.append(NSAttributedString(string: String(ch), attributes: [.font: font, .foregroundColor: textColor]))
                continue
            }

            // Preferred: a real Unicode script character. It carries correct
            // metrics on its own, so no baseline shift is applied.
            let map = isSuperscript ? superscriptMap : subscriptMap
            if scriptDepth == 1, let mapped = map[ch] {
                let font = fallbackFont(for: mapped, textFont: textFont, mathFont: mathFont)
                result.append(NSAttributedString(string: String(mapped), attributes: [.font: font, .foregroundColor: textColor]))
                continue
            }

            // Otherwise simulate: shrink and shift off the baseline. This is
            // what rescues CJK inside `\text{}` and multi-character spans —
            // the case the user actually hit — since neither has Unicode
            // script forms at all.
            let baseSize = fallbackFont(for: ch, textFont: textFont, mathFont: mathFont).pointSize
            let scale = pow(0.72, CGFloat(min(scriptDepth, 2)))
            let scriptSize = max(baseSize * scale, baseSize * 0.5)
            let shift = (isSuperscript ? 1.0 : -1.0) * baseSize * 0.34 * (scriptDepth == 1 ? 1.0 : 1.35)
            let shrunk = fallbackFont(for: ch, textFont: textFont.withSize(scriptSize), mathFont: mathFont.withSize(scriptSize))
            result.append(NSAttributedString(string: String(ch), attributes: [
                .font: shrunk,
                .foregroundColor: textColor,
                .baselineOffset: shift,
            ]))
        }
        return result
    }

    /// Pick the text or math face for one character — the original rule,
    /// extracted so the script path can reuse it at a reduced size.
    private static func fallbackFont(for ch: Character, textFont: UIFont, mathFont: UIFont) -> UIFont {
        guard let scalar = ch.unicodeScalars.first else { return mathFont }
        return (scalar.properties.isAlphabetic && !scalar.properties.isMath) ? textFont : mathFont
    }

    /// Convert common LaTeX to Unicode text. Not exhaustive — covers the
    /// operators, Greek letters, and \text{} that appear in everyday formulas.
    private static func latexToUnicode(_ latex: String) -> String {
        var s = latex.trimmingCharacters(in: .whitespacesAndNewlines)

        // Strip \text{...} / \textbf{...} / \mathrm{...} / \operatorname{...} wrappers, keep content
        let textPattern = try! NSRegularExpression(pattern: #"\\(?:text|textbf|mathrm|operatorname|mathbf|bold)\{([^}]*)\}"#)
        s = textPattern.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "$1")

        // \frac{a}{b} → a/b
        let fracPattern = try! NSRegularExpression(pattern: #"\\(?:frac|dfrac|tfrac)\{([^}]*)\}\{([^}]*)\}"#)
        s = fracPattern.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "$1/$2")

        // \sqrt{x} → √x
        let sqrtPattern = try! NSRegularExpression(pattern: #"\\sqrt\{([^}]*)\}"#)
        s = sqrtPattern.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "√($1)")

        let replacements: [(String, String)] = [
            ("\\div", "÷"), ("\\times", "×"), ("\\cdot", "·"),
            ("\\pm", "±"), ("\\mp", "∓"), ("\\leq", "≤"), ("\\geq", "≥"),
            ("\\neq", "≠"), ("\\approx", "≈"), ("\\equiv", "≡"),
            ("\\infty", "∞"), ("\\sum", "∑"), ("\\prod", "∏"),
            ("\\int", "∫"), ("\\partial", "∂"), ("\\nabla", "∇"),
            ("\\forall", "∀"), ("\\exists", "∃"),
            ("\\in", "∈"), ("\\notin", "∉"), ("\\subset", "⊂"), ("\\supset", "⊃"),
            ("\\cup", "∪"), ("\\cap", "∩"),
            ("\\to", "→"), ("\\rightarrow", "→"), ("\\leftarrow", "←"),
            ("\\Rightarrow", "⇒"), ("\\Leftarrow", "⇐"), ("\\Leftrightarrow", "⇔"),
            ("\\implies", "⇒"), ("\\iff", "⇔"),
            ("\\ldots", "…"), ("\\cdots", "⋯"), ("\\vdots", "⋮"),
            // [issue #117-2 follow-up] `\circ` was missing entirely, so the
            // `\command` sweep below deleted it and left `H^\circ` as a bare
            // `H^` — half of the `H^/R` artefact in the user's screenshot.
            // `\deg`/`\prime` are here for the same reason.
            ("\\circ", "°"), ("\\degree", "°"), ("\\deg", "°"),
            ("\\prime", "′"), ("\\angle", "∠"), ("\\perp", "⊥"), ("\\propto", "∝"),
            ("\\alpha", "α"), ("\\beta", "β"), ("\\gamma", "γ"), ("\\delta", "δ"),
            ("\\epsilon", "ε"), ("\\zeta", "ζ"), ("\\eta", "η"), ("\\theta", "θ"),
            ("\\iota", "ι"), ("\\kappa", "κ"), ("\\lambda", "λ"), ("\\mu", "μ"),
            ("\\nu", "ν"), ("\\xi", "ξ"), ("\\pi", "π"), ("\\rho", "ρ"),
            ("\\sigma", "σ"), ("\\tau", "τ"), ("\\upsilon", "υ"), ("\\phi", "φ"),
            ("\\chi", "χ"), ("\\psi", "ψ"), ("\\omega", "ω"),
            ("\\Gamma", "Γ"), ("\\Delta", "Δ"), ("\\Theta", "Θ"), ("\\Lambda", "Λ"),
            ("\\Xi", "Ξ"), ("\\Pi", "Π"), ("\\Sigma", "Σ"), ("\\Phi", "Φ"),
            ("\\Psi", "Ψ"), ("\\Omega", "Ω"),
            ("\\quad", "  "), ("\\qquad", "    "), ("\\,", " "),
            ("\\;", " "), ("\\!", ""),
            ("\\displaystyle", ""), ("\\left", ""), ("\\right", ""),
            ("\\big", ""), ("\\Big", ""), ("\\bigg", ""), ("\\Bigg", ""),
        ]
        for (cmd, repl) in replacements {
            s = s.replacingOccurrences(of: cmd, with: repl)
        }

        // Strip remaining \command sequences (e.g. \mathrm, \mathbf without braces)
        let cmdPattern = try! NSRegularExpression(pattern: #"\\[a-zA-Z]+"#)
        s = cmdPattern.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")

        // [issue #117-2 follow-up] Superscripts / subscripts.
        //
        // This used to be two no-ops (`"^" -> "^"`, `"_" -> "_"`), so scripts
        // survived as literal carets and underscores while the brace cleanup
        // just below deleted their grouping. `H^\circ/R\,\text{斜率}` came out
        // as `H^/R_斜率` — unreadable, and `^`/`_` read as division and an
        // underline rather than as scripts.
        //
        // Run BEFORE the brace cleanup: `^{...}` grouping is the only thing
        // that says how far a multi-character script extends, and the two lines
        // below would erase it. Spans are wrapped in private-use sentinels that
        // survive the remaining text passes; `buildAttributedString` turns them
        // into real Unicode script characters where the set covers them, and
        // into baseline-shifted smaller text where it doesn't (CJK, most
        // multi-character spans).
        s = markScripts(in: s)

        // Clean up braces and extra whitespace
        s = s.replacingOccurrences(of: "{", with: "")
        s = s.replacingOccurrences(of: "}", with: "")

        let multiSpace = try! NSRegularExpression(pattern: #" {3,}"#)
        s = multiSpace.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "  ")

        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Superscript / subscript handling (issue #117-2 follow-up)

    /// Private-use sentinels delimiting a script span. Chosen from the BMP
    /// private-use area so they can't collide with anything a real formula
    /// contains, and so the intervening text passes (command stripping, brace
    /// cleanup, whitespace collapsing) carry them through untouched.
    private static let supOpen: Character = "\u{E000}"
    private static let subOpen: Character = "\u{E001}"
    private static let scriptClose: Character = "\u{E002}"

    /// Rewrite `^{...}` / `_{...}` / `^X` / `_X` into sentinel-delimited spans.
    ///
    /// Scans by hand rather than by regex because the braced form nests
    /// (`^{a^{b}}`) and a regex character class can't match balanced braces.
    /// An unmatched or empty script (a trailing `^`, or `_{}`) is dropped
    /// entirely — a stray caret in the output is exactly the artefact this is
    /// meant to remove.
    private static func markScripts(in input: String) -> String {
        var out = ""
        var i = input.startIndex

        while i < input.endIndex {
            let ch = input[i]
            guard ch == "^" || ch == "_" else {
                out.append(ch)
                i = input.index(after: i)
                continue
            }

            let opener = (ch == "^") ? supOpen : subOpen
            var j = input.index(after: i)
            guard j < input.endIndex else { break }  // trailing '^' / '_' — drop

            if input[j] == "{" {
                // Braced: consume to the matching close brace.
                var depth = 0
                var body = ""
                var closed = false
                while j < input.endIndex {
                    let c = input[j]
                    if c == "{" {
                        depth += 1
                        if depth == 1 { j = input.index(after: j); continue }
                    } else if c == "}" {
                        depth -= 1
                        if depth == 0 { closed = true; j = input.index(after: j); break }
                    }
                    body.append(c)
                    j = input.index(after: j)
                }
                if closed {
                    // Nested scripts inside the body are already marked by the
                    // recursive call; an empty body contributes nothing.
                    let inner = markScripts(in: body)
                    if !inner.isEmpty {
                        out.append(opener); out.append(contentsOf: inner); out.append(scriptClose)
                    }
                    i = j
                    continue
                }
                // Unbalanced braces (truncated / malformed source, common while
                // a formula is still streaming in). Emit the body unmarked
                // rather than scripting the `{` itself — that produced an empty
                // span once the brace cleanup ran, i.e. a script marker around
                // nothing.
                if !body.isEmpty {
                    out.append(contentsOf: markScripts(in: body))
                }
                i = j
                continue
            }

            // Unbraced: a single character is the script (LaTeX's own rule).
            let c = input[j]
            if c != " " {
                out.append(opener); out.append(c); out.append(scriptClose)
            }
            i = input.index(after: j)
        }
        return out
    }

    /// Unicode superscript forms. Only characters with a true dedicated
    /// codepoint are listed — anything absent falls back to baseline-shifted
    /// text, which looks far better than a wrong-looking substitute.
    private static let superscriptMap: [Character: Character] = [
        "0": "⁰", "1": "¹", "2": "²", "3": "³", "4": "⁴",
        "5": "⁵", "6": "⁶", "7": "⁷", "8": "⁸", "9": "⁹",
        "+": "⁺", "-": "⁻", "−": "⁻", "=": "⁼", "(": "⁽", ")": "⁾",
        "n": "ⁿ", "i": "ⁱ",
    ]

    /// Unicode subscript forms. Note the Latin coverage is much thinner than
    /// the superscript set (no `b`, `c`, `d`, `f`, `g`, `q`, `w`, `y`, `z`),
    /// which is why the baseline-shift fallback matters more here.
    private static let subscriptMap: [Character: Character] = [
        "0": "₀", "1": "₁", "2": "₂", "3": "₃", "4": "₄",
        "5": "₅", "6": "₆", "7": "₇", "8": "₈", "9": "₉",
        "+": "₊", "-": "₋", "−": "₋", "=": "₌", "(": "₍", ")": "₎",
        "a": "ₐ", "e": "ₑ", "h": "ₕ", "i": "ᵢ", "j": "ⱼ", "k": "ₖ",
        "l": "ₗ", "m": "ₘ", "n": "ₙ", "o": "ₒ", "p": "ₚ", "r": "ᵣ",
        "s": "ₛ", "t": "ₜ", "u": "ᵤ", "v": "ᵥ", "x": "ₓ",
    ]
}

// MARK: - Render Result

final class SwiftMathRenderResult: NSObject {
    let image: UIImage
    let size: CGSize
    /// [issue #117-4] Distance from the image's BOTTOM edge up to the
    /// formula's typographic baseline, in points. This is the only value that
    /// makes true baseline alignment possible: SwiftMath lays the formula out
    /// around a baseline whose position depends on the content (a `y` descends
    /// below it, `x²` does not), so no fixed fraction of the image height —
    /// cap-height or x-height centring included — can stand in for it.
    ///
    /// Zero means "unknown" (the Unicode fallback path, which draws with
    /// UIKit's own text layout); callers must keep an approximation for that
    /// case rather than assume a baseline at the image bottom.
    let baselineFromBottom: CGFloat

    init(image: UIImage, size: CGSize, baselineFromBottom: CGFloat = 0) {
        self.image = image
        self.size = size
        self.baselineFromBottom = baselineFromBottom
    }
}
