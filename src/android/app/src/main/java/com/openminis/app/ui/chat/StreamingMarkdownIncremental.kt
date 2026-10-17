package com.openminis.app.ui.chat

/**
 * Block-layer incremental parse for the LIVE streaming fragment.
 *
 * The live branch used to call [parseMarkdownBlocks] on the whole fragment on
 * every throttled tick. Block parsing classifies each line with several ICU
 * regexes, so a long paragraph/table/fence paid O(fragment) regex work per
 * tick — the per-tick cost the stream cannot amortize, and the mechanism
 * behind the reported native-memory pressure (ICU copies each `Matcher`
 * input as UTF-16).
 *
 * The inline layer already solved the same shape with a frozen-prefix split
 * (see `inlineIncremental`). This does it one level up:
 *
 *  - if the new text starts with the previous text, the previous block list
 *    is reusable up to the START of its last block;
 *  - only that last block (the growing tail) is re-parsed;
 *  - every earlier block is known-complete and is carried over untouched.
 *
 * The parser is strictly sequential over lines, and blocks never overlap, so
 * re-parsing from a block's first line cannot change any earlier block. When
 * the prefix check fails — a non-append edit, a fragment swap, or a block
 * whose `raw` is not a contiguous suffix (loose lists drop blank lines from
 * `raw`) — we fall back to a full parse rather than risk a wrong splice.
 */
internal data class BlockParseSnapshot(
    val raw: String,
    val blocks: List<MdBlock>,
    /** Chars actually re-parsed on this tick (equals `raw.length` on a full parse). */
    val reparsedChars: Int,
)

internal suspend fun parseMarkdownBlocksIncremental(
    previous: BlockParseSnapshot?,
    text: String,
): BlockParseSnapshot {
    // Fast path: a single paragraph growing by plain characters. Appending to
    // the same line cannot change block type (block syntax is line-start
    // sensitive), and a Paragraph carries only its raw text, so no ICU regex
    // needs to run at all. This is the common "one long answer paragraph"
    // streaming shape and the worst source of per-tick Matcher/native copies.
    fastAppendParagraph(previous, text)?.let { return it }

    val reusable = reusablePrefix(previous, text)
        ?: return BlockParseSnapshot(text, parseMarkdownBlocks(text), text.length)
    val suffix = text.substring(reusable.start)
    val tail = parseMarkdownBlocks(suffix)
    return BlockParseSnapshot(
        raw = text,
        blocks = reusable.blocks + tail,
        reparsedChars = suffix.length,
    )
}

/**
 * Append-only plain-text growth of the last paragraph. Media syntax is
 * excluded because the block layer may split a paragraph into Image/Video/
 * Audio blocks; a line break is excluded because it can start a new block.
 */
private fun fastAppendParagraph(
    previous: BlockParseSnapshot?,
    text: String,
): BlockParseSnapshot? {
    if (previous == null || previous.blocks.isEmpty()) return null
    if (!text.startsWith(previous.raw) || text.length <= previous.raw.length) return null
    // A trailing newline means the new characters start a NEW block, not the
    // same paragraph; let the block parser decide.
    if (previous.raw.endsWith("\n") || previous.raw.endsWith("\r")) return null
    val last = previous.blocks.last()
    if (last !is MdBlock.Paragraph || last.raw.isEmpty()) return null
    if (!previous.raw.endsWith(last.raw)) return null
    val suffix = text.substring(previous.raw.length)
    if (suffix.any { it == '\n' || it == '\r' || it == '!' || it == '[' || it == ']' || it == '(' || it == ')' }) {
        return null
    }
    return BlockParseSnapshot(
        raw = text,
        blocks = previous.blocks.dropLast(1) + MdBlock.Paragraph(last.raw + suffix),
        reparsedChars = suffix.length,
    )
}

private class ReusablePrefix(val start: Int, val blocks: List<MdBlock>)

private fun reusablePrefix(previous: BlockParseSnapshot?, text: String): ReusablePrefix? {
    if (previous == null || previous.blocks.isEmpty()) return null
    if (text.length < previous.raw.length) return null
    if (!text.startsWith(previous.raw)) return null
    val last = previous.blocks.last()
    if (last.raw.isEmpty()) return null
    // `last.raw` must be a contiguous slice of the previous text. Loose lists
    // join only their item lines, so this misses and we take the full parse —
    // deliberate, because splicing a non-contiguous `raw` would corrupt the
    // boundary.
    val start = previous.raw.lastIndexOf(last.raw)
    if (start <= 0) return null
    return ReusablePrefix(start, previous.blocks.dropLast(1))
}

/**
 * Observability for the incremental path: confirms on-device that ticks with
 * a growing fragment actually re-parse only the tail, rather than the whole
 * fragment. Silent until [FLUSH_TICKS], then one line per window.
 */
internal object BlockParseMetrics {
    private const val FLUSH_TICKS = 20
    private var ticks = 0
    private var fullChars = 0L
    private var reparsed = 0L

    @Synchronized
    fun record(fullCharsOnTick: Int, reparsedChars: Int) {
        ticks++
        fullChars += fullCharsOnTick
        reparsed += reparsedChars
        if (ticks < FLUSH_TICKS) return
        val n = ticks
        val savedPct = if (fullChars > 0) (100L - reparsed * 100L / fullChars) else 0L
        com.openminis.app.logging.AppLogger.info(
            "StreamRender",
            "[StreamRender][blockIncr] ticks=$n fullChars=$fullChars " +
                "reparsedChars=$reparsed savedPct=$savedPct",
        )
        ticks = 0
        fullChars = 0
        reparsed = 0
    }
}
