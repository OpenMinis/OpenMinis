package com.openminis.app.ui.markdown

/**
 * Lightweight markdown parser that converts raw markdown text into a list of block nodes.
 * Supports: headings, code blocks (fenced), blockquotes, bullet/numbered/task lists,
 * thematic breaks, tables, and paragraphs. Inline parsing is handled separately.
 */
object MarkdownParser {

    sealed class Block {
        data class Heading(val level: Int, val content: String) : Block()
        data class Paragraph(val content: String) : Block()
        data class CodeBlock(val language: String, val code: String) : Block()
        data class Blockquote(val blocks: List<Block>) : Block()
        data class BulletList(val items: List<ListItem>) : Block()
        data class NumberedList(val startNumber: Int, val items: List<ListItem>) : Block()
        data class Table(val headers: List<String>, val alignments: List<Alignment>, val rows: List<List<String>>) : Block()
        data object ThematicBreak : Block()

        /**
         * Display-mode LaTeX. Either extracted from `$$ … $$` / `\[ … \]`,
         * or promoted from a paragraph that contained nothing but a single
         * inline-math placeholder. mhchem (\ce{…}, \pu{…}) is supported via
         * the bundled assets/katex/mhchem.min.js extension.
         */
        data class MathBlock(val latex: String) : Block()

        /**
         * Media blocks extracted from `![alt](url)` when the line only contains
         * that one image-syntax node. Routing by file extension mirrors iOS
         * SelectableMarkdownView (nativeImageExts / nativeVideoExts / nativeAudioExts).
         */
        data class Image(val alt: String, val url: String) : Block()
        data class Video(val alt: String, val url: String) : Block()
        data class Audio(val alt: String, val url: String) : Block()
    }

    /**
     * A single extracted math expression, identified by a unique placeholder
     * inserted into the markdown stream before block-parsing. After the AST
     * is built, the placeholders are looked up by id and restored either as
     * MathBlock (display) or kept inline (rendered by the inline pass).
     */
    data class MathSpan(val placeholder: String, val latex: String, val isBlock: Boolean)

    /**
     * Holder shared with [MarkdownText] so the inline parser can also resolve
     * placeholders to their original LaTeX. Threaded through composition by
     * passing the whole list of spans alongside the parsed blocks.
     */
    data class ParseResult(val blocks: List<Block>, val mathSpans: List<MathSpan>)

    /** Object Replacement Character (U+FFFC) — sentinel used by math placeholders. */
    private const val ORC = '￼'

    private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
    private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

    /** Regex for a standalone `![alt](url)` node on its own line. */
    private val standaloneImageRegex = Regex("""^\s*!\[([^\]]*)\]\(([^)\s]+)\)\s*$""")

    // [T-android-md-regex-hoist] Block patterns are compiled ONCE here instead
    // of inside the per-line loop in parse().
    //
    // On Android `java.util.regex` is ICU. Every Matcher — and `Regex.find` /
    // `matches` / `containsMatchIn` each create one — allocates a native buffer
    // whose size scales with the input, and that buffer is only released by a
    // Cleaner after GC. Anything ~8 KB lands in Scudo's secondary allocator,
    // which takes a dedicated mmap PLUS a guard page: two VMA entries apiece.
    //
    // parse() ran 8 of these per line, and the streaming path re-parses on every
    // arriving chunk from Dispatchers.Default. On a long session that outran the
    // Cleaner and exhausted vm.max_map_count (65530), so mmap failed and Scudo
    // aborted the process:
    //
    //   Abort message: 'Scudo ERROR: internal map failure (error desc=Out of memory)'
    //   Pattern.matcher -> PatternNative.openMatcher -> operator new -> bad_alloc
    //
    // Measured on a crashed process (Android 16, arm64): 30942 live
    // scudo:secondary mappings + 30942 guard pages = 61884 of 65530 VMA
    // entries — 94 % of the map — for only 240 MB of payload. MemAvailable was
    // 2.2 GB at the time: the allocator ran out of *bookkeeping slots*, not RAM.
    // Four crashes, two of them after barely 2.5 minutes of process uptime, all
    // on the DefaultDispatcher thread.
    //
    // Hoisting does not change behaviour: a Regex is immutable and its Matchers
    // are created per call either way. It removes the repeated *compilation*,
    // which is what allocated the large native buffers.
    private val thematicBreakRegex = Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1(\\s*\\1)*\\s*$")
    private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
    private val bulletRegex = Regex("^\\s{0,3}[-*+]\\s+(.*)$")
    private val bulletMarkerRegex = Regex("^\\s{0,3}[-*+]\\s")
    private val bulletPrefixRegex = Regex("^\\s{0,3}[-*+]\\s+")
    private val orderedRegex = Regex("^\\s{0,3}(\\d{1,2})[.)\\s]\\s*(.*)$")
    private val orderedMarkerRegex = Regex("^\\s{0,3}\\d{1,2}[.)\\s]\\s")
    private val tableSeparatorCellRegex = Regex("^:?-+:?$")
    private val blankLineRegex = Regex("\\n[ \\t]*\\n")
    private val trailingBlankRegex = Regex("\\n[ \\t]*$")
    private val mathPlaceholderRegex = Regex("$ORC" + "MATH(\\d+)" + "$ORC")

    /**
     * Classify a media URL by file extension. Extracts the extension from the
     * *last path segment only* so filenames that contain '#' or '?' (either
     * literal or percent-encoded) still get their real extension. Handles both
     * unencoded names (e.g. `foo#China.mp4`) and encoded ones (`foo%23China.mp4`).
     */
    private fun mediaBlockFor(alt: String, url: String): Block {
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in nativeVideoExts -> Block.Video(alt, url)
            in nativeAudioExts -> Block.Audio(alt, url)
            else -> Block.Image(alt, url)
        }
    }

    data class ListItem(val content: String, val checked: Boolean? = null)

    enum class Alignment { LEFT, CENTER, RIGHT }

    /**
     * Top-level entry: extract math, run the standard block parser on the
     * placeholder-substituted text, then run the math-restore pass that
     * promotes single-math paragraphs to MathBlock. Inline math placeholders
     * stay in the paragraph text so the inline pass can render them via
     * KaTeX. Mirrors iOS MarkdownMathExtractor.extract → cmark → restore.
     */
    fun parseWithMath(markdown: String): ParseResult {
        val (cleaned, spans) = extractMath(markdown)
        val rawBlocks = parse(cleaned)
        val restored = restoreMath(rawBlocks, spans)
        return ParseResult(restored, spans)
    }

    fun parse(markdown: String): List<Block> {
        android.util.Log.d("MdParser", "parse() len=${markdown.length} preview=${markdown.take(160).replace("\n","\\n")}")
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block
            if (line.trimStart().startsWith("```")) {
                val indent = line.indexOf('`')
                val fence = line.trimStart()
                val lang = fence.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val cl = lines[i]
                    if (cl.trimStart().startsWith("```") && cl.trim() == "```") {
                        i++
                        break
                    }
                    codeLines.add(cl)
                    i++
                }
                blocks.add(Block.CodeBlock(lang, codeLines.joinToString("\n")))
                continue
            }

            // Thematic break
            if (line.matches(thematicBreakRegex)) {
                blocks.add(Block.ThematicBreak)
                i++
                continue
            }

            // ATX Heading
            val headingMatch = headingRegex.find(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2].trimEnd().removeSuffix("#").trimEnd()
                blocks.add(Block.Heading(level, content))
                i++
                continue
            }

            // Blockquote
            if (line.trimStart().startsWith("> ") || line.trimStart() == ">") {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].trimStart().startsWith("> ") || lines[i].trimStart() == ">")) {
                    val ql = lines[i].trimStart()
                    quoteLines.add(if (ql == ">") "" else ql.removePrefix("> "))
                    i++
                }
                val inner = parse(quoteLines.joinToString("\n"))
                blocks.add(Block.Blockquote(inner))
                continue
            }

            // Table (must have at least header + separator)
            if (i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                val table = parseTable(lines, i)
                if (table != null) {
                    blocks.add(table.first)
                    i = table.second
                    continue
                }
            }

            // Bullet list (-, *, +)
            val bulletMatch = bulletRegex.find(line)
            if (bulletMatch != null) {
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val bm = bulletRegex.find(lines[i])
                    if (bm == null) break
                    val content = bm.groupValues[1]
                    items.add(parseListItem(content))
                    i++
                    // Collect continuation lines (indented)
                    while (i < lines.size && lines[i].startsWith("  ") && !bulletMarkerRegex.matches(lines[i])) {
                        items[items.lastIndex] = items.last().copy(
                            content = items.last().content + "\n" + lines[i].trimStart()
                        )
                        i++
                    }
                }
                blocks.add(Block.BulletList(items))
                continue
            }

            // Numbered list. Marker digits are capped at 2 so a paragraph
            // starting with a year or big number ("2020. 年的…") isn't
            // misparsed as ordered-list item #2020 (user report). Real lists
            // rarely exceed 99 items; CommonMark itself caps markers at 9
            // digits, we deliberately go tighter.
            val numMatch = orderedRegex.find(line)
            if (numMatch != null) {
                val startNum = numMatch.groupValues[1].toIntOrNull() ?: 1
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val nm = orderedRegex.find(lines[i])
                    if (nm == null) break
                    items.add(ListItem(nm.groupValues[2]))
                    i++
                }
                blocks.add(Block.NumberedList(startNum, items))
                continue
            }

            // Standalone image / video / audio on its own line:
            //   ![alt](minis://workspace/clip.mp4)
            // Routed by extension so the media renderer can pick the right preview.
            val mediaMatch = standaloneImageRegex.find(line)
            if (mediaMatch != null) {
                val alt = mediaMatch.groupValues[1]
                val url = mediaMatch.groupValues[2]
                val blk = mediaBlockFor(alt, url)
                android.util.Log.d("MdParser", "media match: alt=\"$alt\" url=$url -> ${blk::class.simpleName}")
                blocks.add(blk)
                i++
                continue
            } else if (line.contains("![") && line.contains("](")) {
                android.util.Log.d("MdParser", "image-like line did NOT match standalone regex: ${line.take(160)}")
            }

            // Blank line — skip
            if (line.isBlank()) {
                i++
                continue
            }

            // Paragraph (collect contiguous non-blank lines that don't match other patterns)
            val paraLines = mutableListOf(line)
            i++
            while (i < lines.size) {
                val pl = lines[i]
                if (pl.isBlank() || pl.trimStart().startsWith("```") ||
                    pl.trimStart().startsWith("# ") || pl.trimStart().startsWith("> ") ||
                    bulletPrefixRegex.containsMatchIn(pl) ||
                    // Keep in sync with the numbered-list marker above (≤2 digits).
                    orderedMarkerRegex.containsMatchIn(pl) ||
                    pl.matches(thematicBreakRegex) ||
                    standaloneImageRegex.containsMatchIn(pl)
                ) break
                paraLines.add(pl)
                i++
            }
            blocks.add(Block.Paragraph(paraLines.joinToString("\n")))
        }

        return blocks
    }

    private fun parseListItem(content: String): ListItem {
        // Task list: [x] or [ ]
        if (content.startsWith("[x] ") || content.startsWith("[X] ")) {
            return ListItem(content.substring(4), checked = true)
        }
        if (content.startsWith("[ ] ")) {
            return ListItem(content.substring(4), checked = false)
        }
        return ListItem(content)
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        val cells = trimmed.split('|').filter { it.isNotBlank() }
        return cells.all { it.trim().matches(tableSeparatorCellRegex) }
    }

    private fun parseTable(lines: List<String>, startIdx: Int): Pair<Block.Table, Int>? {
        val headerLine = lines[startIdx]
        val separatorLine = lines[startIdx + 1]

        val headers = splitTableRow(headerLine)
        val sepCells = splitTableRow(separatorLine)
        if (headers.isEmpty() || sepCells.isEmpty()) return null

        val alignments = sepCells.map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(':') && trimmed.endsWith(':') -> Alignment.CENTER
                trimmed.endsWith(':') -> Alignment.RIGHT
                else -> Alignment.LEFT
            }
        }

        var i = startIdx + 2
        val rows = mutableListOf<List<String>>()
        while (i < lines.size) {
            val row = lines[i]
            if (row.isBlank() || !row.contains('|')) break
            rows.add(splitTableRow(row))
            i++
        }

        return Block.Table(headers, alignments, rows) to i
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        // [T-minis-url-fullwidth-pipe-android] Fast path: the only reason to
        // protect a `|` from the cell split is a markdown link whose URL
        // contains a raw ASCII pipe, e.g. `[f](minis://ns/a|b.png)` — the model
        // (or a hand-authored row) can emit an un-encoded `|` inside the URL,
        // and a naive `split('|')` would chop the link in two. App-generated
        // minis:// URLs already percent-encode `|` to `%7C`, so this is purely
        // defence-in-depth. Rows WITHOUT a `](` link keep the original exact
        // `split('|')` behaviour verbatim — important because the paren-aware
        // walk below would otherwise swallow a `|` after an unbalanced `(` in a
        // plain text cell (e.g. `| a (note | b |`). Fullwidth `｜` (U+FF5C) is
        // never split by either path since `split('|')` matches only U+007C.
        if (!trimmed.contains("](")) {
            return trimmed.split('|').map { it.trim() }
        }
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in trimmed) {
            if (ch == '(' && depth == 0) depth++
            else if (ch == ')' && depth > 0) depth--
            if (ch == '|' && depth == 0) {
                cells.add(current.toString().trim())
                current.clear()
            } else {
                current.append(ch)
            }
        }
        cells.add(current.toString().trim())
        return cells
    }

    // ─── Math extraction ──────────────────────────────────────────────────────
    //
    // Walks the source character-by-character, swallowing four math delimiters:
    //
    //   $$ … $$    display math
    //   \[ … \]    display math (LaTeX-style)
    //   $ … $      inline math
    //   \( … \)    inline math (LaTeX-style)
    //
    // Fenced code blocks and inline code spans are skipped verbatim so the
    // delimiters inside them survive (chat transcripts often paste raw
    // markdown source). Each match is replaced with a sentinel like
    // ￼ MATH<n> ￼ so the placeholder cannot collide with normal
    // Markdown syntax (￼ = Object Replacement Character, never appears
    // in user prose). Mirrors iOS MarkdownMathExtractor.extract verbatim.

    private fun extractMath(markdown: String): Pair<String, List<MathSpan>> {
        val spans = mutableListOf<MathSpan>()
        val out = StringBuilder()
        val chars = markdown
        val n = chars.length
        var i = 0

        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        // [T-android-latex-code-mask] Precomputed once: which indices sit
        // inside a fence or inline code, so a closing `$`/`$$` can never be
        // matched across a code boundary (issue #117 defect 3).
        val codeMask = buildCodeMask(chars)

        fun atLineStart(idx: Int): Boolean = idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            // Detect opening fence at line start
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) { fl++; j++ }
                    if (fl >= 3) {
                        inFence = true
                        fenceChar = c
                        fenceLen = fl
                        while (i < n && chars[i] != '\n') { out.append(chars[i]); i++ }
                        if (i < n) { out.append(chars[i]); i++ }
                        continue
                    }
                }
            }

            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) { fl++; j++ }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') { out.append(chars[i]); i++ }
                        if (i < n) { out.append(chars[i]); i++ }
                        continue
                    }
                }
                out.append(chars[i]); i++
                continue
            }

            // Inline code spans — copy verbatim, including the dollar signs inside.
            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') { run++; j++ }
                var k = j
                var found = false
                while (k <= n - run) {
                    var match = 0
                    while (k + match < n && chars[k + match] == '`') match++
                    if (match == run) {
                        for (idx in i until k + match) out.append(chars[idx])
                        i = k + match
                        found = true
                        break
                    }
                    k += if (match > 0) match else 1
                }
                if (found) continue
                for (idx in i until j) out.append(chars[idx])
                i = j
                continue
            }

            // \$ — preserve escaped dollar verbatim.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '$') {
                out.append(chars[i]); out.append(chars[i + 1])
                i += 2
                continue
            }

            // \[ … \] — display math.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '[') {
                val end = findClose(chars, i + 2, "\\]")
                if (end != null) {
                    val latex = stripBlockquoteMarkers(chars.substring(i + 2, end))
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, true))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // \( … \) — inline math.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '(') {
                val end = findClose(chars, i + 2, "\\)")
                if (end != null) {
                    val latex = chars.substring(i + 2, end)
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, false))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // $$ … $$ — display math.
            if (chars[i] == '$' && i + 1 < n && chars[i + 1] == '$') {
                val end = findDoubleDollar(chars, i + 2, codeMask)
                // [T-android-latex-code-mask] The closer must be outside code
                // (codeMask, above) AND a multi-line body must still look like
                // a formula — otherwise an unclosed `$$` swallows prose.
                if (end != null && isPlausibleDisplayBody(chars.substring(i + 2, end))) {
                    val latex = stripBlockquoteMarkers(chars.substring(i + 2, end))
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, true))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // $ … $ — inline math (with currency-skip heuristic).
            if (chars[i] == '$' && i + 1 < n && chars[i + 1] != '$' && chars[i + 1] != ' ') {
                val end = findSingleDollar(chars, i + 1, codeMask)
                if (end != null) {
                    val latex = chars.substring(i + 1, end)
                    if (looksLikeMath(latex)) {
                        spans.add(MathSpan(makePlaceholder(spans.size), latex, false))
                        out.append(spans.last().placeholder)
                        i = end + 1
                        continue
                    }
                }
            }

            out.append(chars[i])
            i++
        }

        return out.toString() to spans
    }

    private fun makePlaceholder(idx: Int): String = "${ORC}MATH$idx$ORC"

    /**
     * Strip blockquote markers from a multi-line math body. Math is extracted
     * before block-level parsing, so a display formula written inside a
     * blockquote drags the continuation lines' `> ` markers into the LaTeX:
     *
     *     > $$
     *     > E = mc^2
     *     > $$
     *
     * used to yield `"\n> E = mc^2\n> "` and render a literal `>`. Only strip
     * when *every* non-blank line after the first carries a `^ {0,3}> ?`
     * marker — that proves the span really sits inside a quote, and protects
     * legitimate formula content whose lines begin with `>` (comparisons,
     * matrix rows). Mirrors iOS MarkdownMathExtractor.stripBlockquoteMarkers.
     */
    private fun stripBlockquoteMarkers(latex: String): String {
        if (!latex.contains('\n')) return latex

        // The opening `$$` / `\[` is consumed by the caller, so the first
        // segment is the tail of the marker line and never carries a marker
        // of its own — validate and strip only lines 2…n.
        val lines = latex.split("\n").toMutableList()
        var sawMarker = false
        for (idx in 1 until lines.size) {
            val line = lines[idx]
            var cursor = 0
            while (cursor < line.length && line[cursor] == ' ' && cursor < 3) cursor++
            if (cursor >= line.length) continue // blank line: neutral
            if (line[cursor] != '>') return latex // a bare line — not a quote
            sawMarker = true
            cursor++
            if (cursor < line.length && line[cursor] == ' ') cursor++
            lines[idx] = line.substring(cursor)
        }
        if (!sawMarker) return latex
        return lines.joinToString("\n")
    }

    private fun findClose(chars: String, from: Int, close: String): Int? {
        val n = chars.length
        var i = from
        while (i <= n - close.length) {
            var match = true
            for (j in close.indices) {
                if (chars[i + j] != close[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return null
    }

    /**
     * [T-android-latex-code-mask] (issue #117 defect 3, iOS bce7e2ed)
     *
     * The main [extractMath] loop skips fenced blocks and inline code when
     * deciding where a formula may *open*, but the closing-delimiter searches
     * were blind forward scans. So one bare `$$` in prose — a model that
     * forgot to close it, or text merely *explaining* LaTeX — paired with a
     * `$$` inside a later ``` fence and swallowed every paragraph between
     * them, including the fence's own opening line. The renderer then saw the
     * orphaned closing ``` as a NEW fence and turned the remainder into a code
     * block: the "a whole section suddenly disappeared" symptom.
     *
     * This precomputes, once per parse, which indices sit inside fenced blocks
     * or inline code, mirroring the main loop's rules. A closer inside the mask
     * is rejected.
     */
    private fun buildCodeMask(chars: String): BooleanArray {
        val n = chars.length
        val mask = BooleanArray(n)
        var i = 0
        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        fun atLineStart(idx: Int): Boolean =
            idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) { fl++; j++ }
                    if (fl >= 3) {
                        inFence = true; fenceChar = c; fenceLen = fl
                        while (i < n && chars[i] != '\n') { mask[i] = true; i++ }
                        if (i < n) { mask[i] = true; i++ }
                        continue
                    }
                }
            }
            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) { fl++; j++ }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') { mask[i] = true; i++ }
                        if (i < n) { mask[i] = true; i++ }
                        continue
                    }
                }
                mask[i] = true; i++
                continue
            }
            // Inline code span: `…` / ``…`` — same run-matching rule the main
            // loop uses when it copies these verbatim.
            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') { run++; j++ }
                var k = j
                var closed = -1
                while (k < n) {
                    if (chars[k] == '`') {
                        var r2 = 0
                        var m = k
                        while (m < n && chars[m] == '`') { r2++; m++ }
                        if (r2 == run) { closed = m; break }
                        k = m
                    } else k++
                }
                val end = if (closed > 0) closed else j
                for (idx in i until end) mask[idx] = true
                i = end
                continue
            }
            i++
        }
        return mask
    }

    /**
     * [T-android-latex-code-mask] A multi-line `$$` body must still look like a
     * formula, so a genuinely unclosed `$$` cannot eat prose even when the
     * closer is outside any code span. Single-line `$$…$$` is accepted
     * unconditionally, leaving ordinary display math untouched. Mirrors iOS.
     */
    private fun isPlausibleDisplayBody(body: String): Boolean {
        if (!body.contains('\n')) return true
        // A blank line means a paragraph break — prose, not one formula.
        if (blankLineRegex.containsMatchIn(body)) return false
        // The conventional block shape puts the closing `$$` alone on its own
        // line, i.e. the body ends with a newline (plus optional indent). That
        // is a strong enough signal on its own — requiring a LaTeX glyph here
        // too would wrongly demote glyph-free but valid math such as
        // "$$\n1 + 2 = 3\n$$" to plain text.
        if (trailingBlankRegex.containsMatchIn(body)) return true
        // Otherwise the closer is mid-line, which is the shape a stray
        // delimiter in prose produces — require a LaTeX-ish glyph.
        return body.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }
    }

    private fun findDoubleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n - 1) {
            if (chars[i] == '$' && chars[i + 1] == '$' &&
                (codeMask == null || !codeMask[i])
            ) return i
            i++
        }
        return null
    }

    private fun findSingleDollar(chars: String, from: Int, codeMask: BooleanArray?): Int? {
        val n = chars.length
        var i = from
        while (i < n) {
            if (chars[i] == '\\' && i + 1 < n) { i += 2; continue }
            if (chars[i] == '$' && (i == 0 || chars[i - 1] != ' ') &&
                (codeMask == null || !codeMask[i])
            ) return i
            if (chars[i] == '\n') return null
            i++
        }
        return null
    }

    /**
     * Currency-skip heuristic: $5 / $10.99 should stay as plain text. Treat
     * the `$ … $` content as math only if it has a math-ish character
     * (`\\^_{}` / common big-symbol unicode) or is longer than 2 chars (so
     * `x+y` qualifies but bare digits like `5` do not). Mirrors iOS.
     */
    private fun looksLikeMath(content: String): Boolean {
        if (content.isEmpty()) return false
        // T208 Layer B: previous heuristic rejected single-letter math like
        // `$c$` or `$i$` — `length > 2` returned false and the literal
        // dollar-wrapped text leaked into the rendered output. Any of:
        //   - obvious LaTeX glyphs (`\`, `^`, `_`, `{`, `}`, integrals…)
        //   - short alphanumeric content with no whitespace and no leading
        //     digit (currency like `$5` filtered out by the leading-digit
        //     check; `$5.99` further filtered by the dot rule below)
        // qualifies as math. Mirrors iOS MarkdownMathExtractor.looksLikeMath.
        val mathChars = charArrayOf('\\', '^', '_', '{', '}', '∫', '∑', '∏', '√', '+', '-', '=', '<', '>')
        for (c in content) {
            if (c in mathChars) return true
        }
        // Currency heuristic: `$5`, `$5.99`, `$1,000` look numeric. Skip.
        if (content[0].isDigit()) return false
        // Whitespace at either edge usually means a stray dollar, not math.
        if (content.first().isWhitespace() || content.last().isWhitespace()) return false
        // Short ASCII identifier-ish content (`$c$`, `$x$`, `$pi$`) up to
        // 30 chars is almost certainly a variable reference.
        if (content.length <= 30 && content.all { it.isLetterOrDigit() }) return true
        return content.length > 2
    }

    /**
     * Walk the AST replacing math placeholders. Display-math placeholders
     * promote (or replace) their containing paragraph to a [Block.MathBlock];
     * inline placeholders remain in the paragraph text so the inline parser
     * can render them by looking up the placeholder in the [MathSpan] list.
     */
    private fun restoreMath(blocks: List<Block>, spans: List<MathSpan>): List<Block> {
        if (spans.isEmpty()) return blocks
        val byPlaceholder = spans.associateBy { it.placeholder }
        return blocks.flatMap { restoreBlock(it, byPlaceholder) }
    }

    private fun restoreBlock(block: Block, map: Map<String, MathSpan>): List<Block> {
        return when (block) {
            is Block.Paragraph -> restoreParagraph(block.content, map)
            is Block.Heading -> listOf(block)  // headings keep their inline placeholders
            is Block.Blockquote -> listOf(Block.Blockquote(block.blocks.flatMap { restoreBlock(it, map) }))
            is Block.BulletList,
            is Block.NumberedList,
            is Block.Table,
            is Block.CodeBlock,
            is Block.MathBlock,
            is Block.ThematicBreak,
            is Block.Image,
            is Block.Video,
            is Block.Audio -> listOf(block)
        }
    }

    /**
     * Splits a paragraph that contains math placeholders. Each display-math
     * placeholder breaks the paragraph and emits a [Block.MathBlock] of its
     * own; inline placeholders stay embedded so the inline parser can render
     * them through KaTeX. A paragraph that contained nothing but a single
     * display-math placeholder collapses to one MathBlock with no surrounding
     * Paragraph stub.
     */
    private fun restoreParagraph(content: String, map: Map<String, MathSpan>): List<Block> {
        if (!content.contains(ORC)) return listOf(Block.Paragraph(content))

        val out = mutableListOf<Block>()
        val buf = StringBuilder()
        var lastEnd = 0
        for (match in mathPlaceholderRegex.findAll(content)) {
            val full = match.value
            val span = map[full] ?: continue
            // Append text before this placeholder.
            if (match.range.first > lastEnd) {
                buf.append(content, lastEnd, match.range.first)
            }
            if (span.isBlock) {
                // Flush any accumulated text as a paragraph, then emit the math block.
                if (buf.isNotBlank()) {
                    out.add(Block.Paragraph(buf.toString().trimEnd()))
                }
                buf.clear()
                out.add(Block.MathBlock(span.latex))
            } else {
                // Keep inline placeholder in-stream — inline pass will render it.
                buf.append(full)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < content.length) buf.append(content, lastEnd, content.length)
        if (buf.isNotBlank()) out.add(Block.Paragraph(buf.toString().trimEnd()))
        // Empty paragraph (only whitespace remained) — drop entirely.
        return if (out.isEmpty()) listOf(Block.Paragraph(content)) else out
    }
}
