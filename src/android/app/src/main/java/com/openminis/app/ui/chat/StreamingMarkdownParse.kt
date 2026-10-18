package com.openminis.app.ui.chat

import com.openminis.app.text.BoundedText
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun parseMarkdownBlocks(content: String, depth: Int = 0): List<MdBlock> {
    // Do not substring the whole document: LargeContentGuard expand and
    // file preview (up to 512 KB) must keep block structure. ICU stays
    // bounded by the per-line [BoundedText.MAX_ICU_INPUT_CHARS] skip below.
    val src = content
    val blocks = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    // Counter so we don't query coroutineContext on EVERY line (small but
    // measurable allocation overhead at ~thousands of lines per pass).
    var sinceLastCheck = 0

    while (i < lines.size) {
        if (sinceLastCheck >= 64) {
            coroutineContext.ensureActive()
            sinceLastCheck = 0
        }
        sinceLastCheck++
        val line = lines[i]
        if (line.length > BoundedText.MAX_ICU_INPUT_CHARS) {
            blocks.addParagraphChunks(line)
            i++
            continue
        }
        val trimmed = line.trimStart()

        when {
            // T155: display math `$$...$$` (single-line or multi-line) and `\[...\]`
            trimmed.startsWith("$$") -> {
                val rest = trimmed.removePrefix("$$")
                val inlineEnd = rest.indexOf("$$")
                if (inlineEnd >= 0) {
                    // Same-line `$$ … $$`
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    // [T-android-latex-code-mask] Multi-line: LOOK AHEAD for the
                    // closing `$$` and validate it before committing. The old
                    // loop scanned forward unconditionally, so an unclosed `$$`
                    // (a model forgetting to close it, or prose explaining
                    // LaTeX) paired with a `$$` inside a LATER ``` fence and
                    // swallowed every paragraph in between plus the fence's own
                    // opening line — leaving an orphaned closing fence. This is
                    // issue #117 defect 3 on the streaming path; the same defect
                    // was fixed in MarkdownParser (521b2dc7), but THIS is the
                    // renderer the chat transcript actually uses.
                    val closeIdx = findDisplayMathClose(lines, i + 1)
                    if (closeIdx == null) {
                        // No plausible closer — emit the `$$` as ordinary text
                        // and let the following lines parse normally.
                        blocks.addParagraphChunks(line)
                        i++
                    } else {
                        val rawLines = mutableListOf(line)
                        val mathLines = mutableListOf<String>()
                        if (rest.isNotEmpty()) mathLines.add(rest)
                        i++
                        while (i <= closeIdx) {
                            rawLines.add(lines[i])
                            if (i == closeIdx) {
                                val close = lines[i].indexOf("$$")
                                val pre = lines[i].substring(0, close)
                                if (pre.isNotEmpty()) mathLines.add(pre)
                                i++
                                break
                            }
                            mathLines.add(lines[i])
                            i++
                        }
                        blocks.add(
                            MdBlock.MathDisplay(
                                rawLines.joinToString("\n"),
                                mathLines.joinToString("\n").trim(),
                            ),
                        )
                    }
                }
            }
            trimmed.startsWith("\\[") -> {
                val rest = trimmed.removePrefix("\\[")
                val inlineEnd = rest.indexOf("\\]")
                if (inlineEnd >= 0) {
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    val rawLines = mutableListOf(line)
                    val mathLines = mutableListOf<String>()
                    if (rest.isNotEmpty()) mathLines.add(rest)
                    i++
                    while (i < lines.size) {
                        rawLines.add(lines[i])
                        val close = lines[i].indexOf("\\]")
                        if (close >= 0) {
                            val pre = lines[i].substring(0, close)
                            if (pre.isNotEmpty()) mathLines.add(pre)
                            i++
                            break
                        }
                        mathLines.add(lines[i])
                        i++
                    }
                    blocks.add(MdBlock.MathDisplay(rawLines.joinToString("\n"), mathLines.joinToString("\n").trim()))
                }
            }
            // Fenced code block
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                val rawLines = mutableListOf(line)
                var closed = false
                i++
                while (i < lines.size) {
                    rawLines.add(lines[i])
                    if (lines[i].trimStart().startsWith("```")) { closed = true; i++; break }
                    codeLines.add(lines[i])
                    i++
                }
                // A stream that ends mid-fence must not hide the tail as if it
                // were a complete snippet: carry the flag so the renderer can
                // mark it and refuse to execute a truncated block.
                blocks.addCodeChunks(
                    rawLines.joinToString("\n"),
                    lang,
                    codeLines.joinToString("\n"),
                    closed,
                )
            }

            // Heading
            trimmed.startsWith("#") && (trimmed.length == 1 || trimmed[trimmed.indexOfFirst { it != '#' }.coerceAtLeast(0)] == ' ') -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                val text = trimmed.drop(level).trimStart()
                blocks.add(MdBlock.Heading(line, level, text))
                i++
            }

            // Horizontal rule
            trimmed.matches(thematicBreakRegex) -> {
                blocks.add(MdBlock.HorizontalRule(line))
                i++
            }

            // Image / Video / Audio: ![alt](url) on its own line — routed by file extension.
            trimmed.matches(standaloneImageLineRegex) -> {
                val match = imageMatchRegex.find(trimmed)
                if (match != null) {
                    val alt = match.groupValues[1]
                    val url = match.groupValues[2]
                    val blk = mediaBlockFrom(line, alt, url)
                    blocks.add(blk)
                }
                i++
            }

            // Table (line contains | and next line is separator)
            trimmed.contains('|') && i + 1 < lines.size &&
                lines[i + 1].trim().matches(tableSeparatorRegex) -> {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].contains('|') ||
                        lines[i].trim().matches(tableSeparatorRegex))) {
                    tableLines.add(lines[i])
                    i++
                }
                val parsed = parseTable(tableLines)
                blocks.add(
                    MdBlock.Table(
                        tableLines.joinToString("\n"),
                        parsed.headers,
                        parsed.rows,
                        parsed.alignments,
                    ),
                )
            }

            // Blockquote — strict CommonMark match: `>` followed by space or end
            // of line. The looser `startsWith(">")` accidentally swallowed
            // shell-output prompts like `>foo`, comparisons (`>5`, `>=`), and
            // generally any inline `>`-led token an LLM happens to emit, which
            // wrapped innocent prose in an orange leading rule (T117).
            isBlockquoteLine(trimmed) -> {
                val rawLines = mutableListOf<String>()
                val innerLines = mutableListOf<String>()
                while (i < lines.size && isBlockquoteLine(lines[i].trimStart())) {
                    rawLines.add(lines[i])
                    innerLines.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                // Recurse one level per quote depth, but never without a
                // bound: a hostile/streamed `>` sled used to reach
                // StackOverflowError. At the cap, render the innermost content
                // as plain paragraphs inside one final quote.
                val innerBlocks = if (depth >= MAX_BLOCKQUOTE_DEPTH) {
                    innerLines.filter { it.isNotBlank() }.map { MdBlock.Paragraph(it) }
                } else {
                    parseMarkdownBlocks(innerLines.joinToString("\n"), depth + 1)
                }
                blocks.add(MdBlock.BlockQuote(rawLines.joinToString("\n"), innerBlocks))
            }

            // Task list: - [x] or - [ ]
            trimmed.matches(taskListItemRegex) -> {
                val items = mutableListOf<TaskItem>()
                val rawLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().matches(taskListItemRegex)) {
                    rawLines.add(lines[i])
                    val t = lines[i].trimStart()
                    val checked = t.contains("[x]", ignoreCase = true)
                    val text = t.replaceFirst(taskListPrefixRegex, "")
                    items.add(TaskItem(checked, text))
                    i++
                }
                blocks.add(MdBlock.TaskList(rawLines.joinToString("\n"), items))
            }

            // Unordered list
            trimmed.matches(bulletListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val baseIndent = line.length - trimmed.length
                // [T-list-item-block-children] Continuation lines carrying a
                // fenced code block or a table used to be appended to the
                // item text verbatim, so the renderer printed raw pipes and
                // backticks as prose. Collect them per item and let
                // appendContinuation decide: plain text still merges into
                // `text` (legacy behavior), block-shaped continuations parse
                // into `children` which the list renderer nests.
                var cont = mutableListOf<String>()
                suspend fun flushCont() {
                    if (cont.isNotEmpty() && items.isNotEmpty()) {
                        items[items.lastIndex] = appendContinuation(items.last(), cont)
                    }
                    cont = mutableListOf()
                }
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(bulletListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        // Continuation or nested — collect for the last item
                        if (items.isNotEmpty()) cont.add(t)
                    } else {
                        flushCont()
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(bulletListPrefixRegex, "")))
                    }
                    i++
                }
                flushCont()
                blocks.add(MdBlock.UnorderedList(rawLines.joinToString("\n"), items))
            }

            // Ordered list
            trimmed.matches(numberedListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val startMatch = numberedListStartRegex.find(trimmed)
                val startNum = startMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val baseIndent = line.length - trimmed.length
                // [T-list-item-block-children] Same continuation treatment
                // as the bullet branch above.
                var cont = mutableListOf<String>()
                suspend fun flushCont() {
                    if (cont.isNotEmpty() && items.isNotEmpty()) {
                        items[items.lastIndex] = appendContinuation(items.last(), cont)
                    }
                    cont = mutableListOf()
                }
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(numberedListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        if (items.isNotEmpty()) cont.add(t)
                    } else {
                        flushCont()
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(numberedListPrefixRegex, "")))
                    }
                    i++
                }
                flushCont()
                blocks.add(MdBlock.OrderedList(rawLines.joinToString("\n"), items, startNum))
            }

            // Empty line
            trimmed.isEmpty() -> { i++ }

            // Paragraph
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    // Cheap first-char dispatch before the block-start
                    // regexes: ordinary prose lines are the overwhelming
                    // majority in a paragraph, and each `matches` call hands
                    // the line to ICU. The table probe stays separate because
                    // a pipe can appear anywhere in the line.
                    //
                    // `paraLines.isNotEmpty()` guarantees progress: a line
                    // that looks like a block start but matched no earlier
                    // branch (e.g. `#not-a-heading`) must be consumed here,
                    // otherwise the outer loop never advances and the parser
                    // spins forever.
                    if (paraLines.isNotEmpty() &&
                        (t.isEmpty() || isParagraphTerminator(t) ||
                            (t.contains('|') && i + 1 < lines.size &&
                                lines[i + 1].trim().matches(tableSeparatorRegex)))
                    ) break
                    paraLines.add(l)
                    i++
                }
                val text = paraLines.joinToString("\n")
                if (text.isNotBlank()) {
                    // Split out inline media (`![alt](url)` that's a .mp4/.mp3/etc)
                    // so videos/audio that the LLM emits adjacent to text still
                    // get their dedicated preview card. Image extensions stay
                    // inline (rendered as `[alt]` link) since Compose's inline
                    // text-image attachment path isn't implemented here.
                    //
                    // T208-4 part 3: after the media split, run a second pass
                    // that promotes "wide" inline math (matrices, multi-row
                    // \\, large \frac, long formulas) to standalone display
                    // blocks. Compose's `InlineTextContent` placeholder is
                    // fixed-size — wide formulas inside it either clip or
                    // scale to unreadable. Splitting at parse time lets each
                    // wide span render at its natural display-mode size on
                    // its own line; short inline math (`$x_i$`) stays inline.
                    val mediaBlocks = splitParagraphOnInlineMedia(text)
                    for (b in mediaBlocks) {
                        if (b is MdBlock.Paragraph) {
                            for (p in splitParagraphOnWideMath(b.raw)) {
                                if (p is MdBlock.Paragraph) blocks.addParagraphChunks(p.raw)
                                else blocks.add(p)
                            }
                        } else {
                            blocks.add(b)
                        }
                    }
                }
            }
        }
    }
    return blocks
}

/**
 * [T-list-item-block-children] Fold a list item's continuation lines into
 * the item. Plain continuation lines keep the legacy behavior (merge into
 * `text`). When the continuation carries block-shaped content — a fenced
 * code block or a table — it parses into `children` instead, so the list
 * renderer can nest real blocks under the item instead of printing pipes
 * and backticks as prose. Leading paragraphs of the parsed continuation
 * still merge into `text` (they are prose belonging to the item).
 *
 * The table probe mirrors the paragraph branch's conservative shape: a
 * pipe in the line AND the next line being a separator row. Prose like
 * "a | b" without a separator stays merged text.
 */
internal suspend fun appendContinuation(item: ListItem, contLines: List<String>): ListItem {
    if (contLines.isEmpty()) return item
    val hasBlock = contLines.any { it.trimStart().startsWith("```") } ||
        contLines.withIndex().any { (j, ln) ->
            ln.contains('|') && j + 1 < contLines.size &&
                contLines[j + 1].trim().matches(tableSeparatorRegex)
        }
    if (!hasBlock) {
        return item.copy(text = item.text + "\n" + contLines.joinToString("\n"))
    }
    val parsed = parseMarkdownBlocks(contLines.joinToString("\n"))
    val leading = mutableListOf<String>()
    var idx = 0
    while (idx < parsed.size && parsed[idx] is MdBlock.Paragraph) {
        leading.add(parsed[idx].raw)
        idx++
    }
    return item.copy(
        text = (listOf(item.text) + leading).joinToString("\n"),
        children = parsed.drop(idx),
    )
}

private const val MAX_BLOCKQUOTE_DEPTH = 16

/**
 * Superset of the per-line block-start checks the paragraph loop used to run
 * on every line. Dispatch on the first character first so ordinary prose
 * lines never reach an ICU-backed `Regex.matches`; the regex branches are the
 * same ones the old unconditional chain used, so parse output is unchanged.
 *
 * Keep in lockstep with the top-level `when` in [parseMarkdownBlocks]: every
 * branch that can terminate a paragraph must return true here.
 */
private fun isParagraphTerminator(t: String): Boolean {
    if (t.isEmpty()) return true
    return when (t[0]) {
        '#' -> true
        '`' -> t.startsWith("```")
        '>' -> isBlockquoteLine(t)
        '-', '*', '_', '+' -> t.matches(thematicBreakRegex) || t.matches(bulletListItemRegex)
        '!' -> t.matches(standaloneImageLineRegex)
        in '0'..'9' -> t.matches(numberedListItemRegex)
        else -> false
    }
}

