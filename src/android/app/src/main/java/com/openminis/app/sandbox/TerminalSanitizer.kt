package com.openminis.app.sandbox

/**
 * Strips ANSI escape sequences and handles CR-based line overwrites
 * from terminal output. Corresponds to iOS AIChatViewModel.sanitizeTerminalOutput().
 */
object TerminalSanitizer {

    // Matches ANSI/VT escape sequences:
    //   ESC [ ... final_byte (CSI sequences)
    //   ESC ] ... ST (OSC sequences terminated by BEL or ESC\)
    //   ESC followed by single character (simple escapes)
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * [T-android-longsession-sanitize-pretrim / GH#326] Ceiling applied to
     * [sanitize]'s INPUT, before its multi-pass rewrite.
     *
     * Sized at 8x the 50 000-char output cap rather than close to it, because
     * the trim happens before escape-stripping: the margin has to absorb
     * whatever the passes are about to delete, so that a result which would
     * have fitted under the final cap after cleaning is never shortened by
     * this. 400k chars is ~800 KB — bounded work for the passes, while still
     * far more text than the 50k that ultimately survives.
     */
    private const val PRE_SANITIZE_MAX_CHARS = 400_000

    /**
     * Sanitize terminal output in two passes:
     * 1. CR folding — simulate carriage return overwriting
     * 2. Strip remaining ANSI/VT escape sequences
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // [T-android-longsession-sanitize-pretrim / GH#326] Cut the input down
        // BEFORE the five full-copy passes below.
        //
        // Those passes each materialise another string the size of their input
        // (foldCarriageReturns builds a StringBuilder, then two Regex.replace,
        // a .filter, and a .lines()/joinToString), so peak transient memory is
        // several times the raw output — while the caller throws all but
        // [truncateIfNeeded]'s 50k away one line later. A command that dumps a
        // large file or a runaway build log therefore allocates hundreds of MB
        // of char[] for a result that was always going to be discarded, and
        // those large contiguous allocations are served by the allocator's
        // secondary (mmap) path — the documented shape of the
        // `Scudo ERROR: internal map failure` self-abort seen in the field.
        //
        // Pre-trimming keeps a generous margin over the final cap so the
        // visible result is unchanged in every realistic case: sanitising can
        // only ever SHRINK the text (it deletes escapes and control bytes), so
        // any input already under the margin is untouched, and for a larger one
        // the head/tail this keeps still bracket far more than the 50k that
        // survives. Only the discarded middle of an enormous dump is affected,
        // and that middle was never going to be shown.
        val pre = truncateIfNeeded(raw, PRE_SANITIZE_MAX_CHARS)

        // Pass 1: CR folding
        val crFolded = foldCarriageReturns(pre)

        // Pass 2: Strip ANSI sequences
        val stripped = ANSI_REGEX.replace(crFolded, "")

        // Pass 3: Remove null bytes and non-printable control chars (except \n \t)
        val cleaned = stripped.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        // Pass 4: Remove "null" artifacts from PRoot/pipe issues
        // - Lines that are entirely "null"
        // - Runs of repeated "null" (e.g., "nullnullnull" → "")
        // - Lines that are just "null" appended to a prefix (e.g., "file:nullnullnull")
        val noNullLines = cleaned.lines()
            .filter { it.trim() != "null" }
            .joinToString("\n")
            .replace(Regex("(?:null){2,}"), "") // Remove runs of 2+ consecutive "null"

        // Pass 5: Collapse excessive blank lines (3+ consecutive → 2)
        return noNullLines.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * Truncate output if it exceeds maxChars, keeping head and tail.
     */
    fun truncateIfNeeded(output: String, maxChars: Int = 50_000): String {
        if (output.length <= maxChars) return output

        val keepEach = maxChars / 2
        val head = output.substring(0, keepEach)
        val tail = output.substring(output.length - keepEach)
        val omitted = output.length - maxChars
        return "$head\n\n[... $omitted characters omitted ...]\n\n$tail"
    }

    /**
     * Simulate CR (\r) behavior: when a line contains \r (without \n),
     * the text after \r overwrites from the beginning of the line.
     *
     * Real terminal semantics, not "last segment wins": each \r moves the
     * cursor to column 0 and the next segment overwrites character by
     * character, so whatever the previous content had BEYOND the new
     * segment's length stays visible — `"AAAA\rBB"` renders as `"BBAA"`,
     * and a progress line that shrinks (`"  50%[=====>    ]\r100%"`) keeps
     * its old tail. Keeping only the last non-empty segment (the behavior
     * this replaced) silently dropped that tail whenever a later segment
     * was shorter. Empty segments (a trailing \r, or \r\r) move the cursor
     * without writing, so they are no-ops.
     *
     * [T-android-sanitize-cr-escapes] Escape sequences are ZERO-width here.
     * The fold used to run on the raw text, before escape stripping, so the
     * invisible bytes counted as screen columns: `Downloading 50%\r\e[KDone`
     * (pip / npm / apk / git progress) rendered as "Doneding 50%" because
     * ESC[K was treated as 3 printable chars instead of "erase to end of
     * line", and a coloured segment overwritten by a plain one kept the old
     * visible text while the overwrite landed on the colour codes. Lines with
     * a \r are now simulated cursor-wise: escapes are consumed without
     * advancing the cursor, EL (ESC[K / ESC[1K / ESC[2K) is interpreted, and
     * other control bytes (which pass 3 deletes anyway) take no column.
     * Lines without \r are untouched; pass 2 strips their escapes as before.
     *
     * O(line length): every char is visited once.
     */
    private fun foldCarriageReturns(text: String): String {
        val lines = text.split('\n')
        val result = StringBuilder()
        val matcher = ANSI_REGEX.toPattern().matcher("")

        for ((index, line) in lines.withIndex()) {
            if (index > 0) result.append('\n')

            if ('\r' !in line) {
                result.append(line)
                continue
            }

            matcher.reset(line)
            val buffer = StringBuilder(line.length)
            var cursor = 0
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\r') {
                    cursor = 0
                    i++
                    continue
                }
                if (c == '\u001B') {
                    matcher.region(i, line.length)
                    if (matcher.lookingAt()) {
                        val seq = matcher.group()
                        if (seq.length >= 3 && seq[1] == '[' && seq.last() == 'K') {
                            eraseInLine(buffer, cursor, seq.substring(2, seq.length - 1))
                        }
                        i = matcher.end()
                    } else {
                        i++ // lone ESC: zero-width, removed by pass 3
                    }
                    continue
                }
                if (c != '\t' && c.code < 0x20) {
                    i++ // non-printable: zero-width, removed by pass 3
                    continue
                }
                if (cursor < buffer.length) buffer.setCharAt(cursor, c) else buffer.append(c)
                cursor++
                i++
            }
            result.append(buffer)
        }

        return result.toString()
    }

    /** EL — `ESC[<param>K`: 0/empty = cursor to end, 1 = start to cursor, 2 = whole line. */
    private fun eraseInLine(buffer: StringBuilder, cursor: Int, param: String) {
        when (param) {
            "", "0" -> if (cursor < buffer.length) buffer.setLength(cursor)
            "1" -> for (k in 0..minOf(cursor, buffer.length - 1)) buffer.setCharAt(k, ' ')
            "2" -> {
                val keep = minOf(cursor, buffer.length)
                buffer.setLength(keep)
                for (k in 0 until keep) buffer.setCharAt(k, ' ')
            }
        }
    }
}
