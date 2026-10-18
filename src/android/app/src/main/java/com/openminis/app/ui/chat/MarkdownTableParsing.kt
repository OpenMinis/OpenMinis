package com.openminis.app.ui.chat

/**
 * [T-android-table-align] Markdown table parsing for the streaming renderer.
 *
 * Extracted from `StreamingMarkdownText.kt` (which is on the architecture-policy
 * baseline and may only shrink): the table parser, its alignment enum and the
 * parsed-table value type are self-contained, and having them in their own file
 * makes the alignment contract testable without pulling in the whole streaming
 * markdown machinery.
 */

/**
 * Matches a GFM separator row: pipes, dashes, colons and whitespace only.
 *
 * Kept next to the parser because every consumer (streaming block splitter,
 * `parseTable`, the block classifier) has to agree on what a separator is —
 * disagreeing here is what once let a separator be treated as a data row.
 */
internal val tableSeparatorRegex = Regex("^\\|?[\\s\\-:|]+\\|?$")

/**
 * Per-column alignment from the separator row, matching GFM and the
 * non-streaming [com.openminis.app.ui.markdown.MarkdownParser]: `:---:` centre,
 * `---:` end, `---` / `:---` start.
 *
 * The streaming renderer used to drop the separator row without reading it, so
 * `| :--- |` and `| ---: |` tables both rendered start-aligned — the author's
 * alignment simply disappeared between the parser and the table.
 */
internal enum class MdTableAlign { START, CENTER, END }

/** Alignment markers of one separator row; missing columns are START. */
internal fun parseTableAlignments(separator: String): List<MdTableAlign> =
    separator.trim().removePrefix("|").removeSuffix("|").split("|").map { raw ->
        val cell = raw.trim()
        val left = cell.startsWith(':')
        val right = cell.endsWith(':') && cell.length > 1
        when {
            left && right -> MdTableAlign.CENTER
            right -> MdTableAlign.END
            else -> MdTableAlign.START
        }
    }

/** Headers, rows and per-column alignment of one markdown table. */
internal class ParsedTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<MdTableAlign>,
)

/**
 * Split table lines into headers, body rows and per-column alignment.
 *
 * T308: the leading/trailing pipe is stripped before splitting (if present). The
 * previous `.filter { isNotEmpty() }` swallowed legitimate empty cells like the
 * first column of `| | Manus | TikTok |`, leaving the header with fewer columns
 * than the body rows and breaking alignment.
 */
internal fun parseTable(lines: List<String>): ParsedTable {
    val headers = mutableListOf<String>()
    val rows = mutableListOf<List<String>>()
    var alignments: List<MdTableAlign> = emptyList()
    for (line in lines) {
        val trimmed = line.trim()
        // The separator row is not content, but it is the only place the
        // alignment lives — read it before skipping.
        if (trimmed.matches(tableSeparatorRegex)) {
            if (alignments.isEmpty()) alignments = parseTableAlignments(trimmed)
            continue
        }
        val core = trimmed.removePrefix("|").removeSuffix("|")
        val cells = core.split("|").map { it.trim() }
        if (headers.isEmpty()) headers.addAll(cells) else rows.add(cells)
    }
    return ParsedTable(headers, rows, alignments)
}
