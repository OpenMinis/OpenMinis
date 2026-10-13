package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

object FileReadTool {
    const val NAME = "file_read"
    const val MAX_LENGTH_HARD_CAP = 80_000
    const val DEFAULT_MAX_LENGTH = 15_000

    fun resolveMaxLength(requested: Int, userCap: Int): Int {
        val cap = userCap.coerceIn(2_000, MAX_LENGTH_HARD_CAP)
        return requested.coerceIn(1, cap)
    }

    fun resolveLineLimit(requested: Int?, userCap: Int): Int? {
        if (userCap <= 0) return requested?.coerceAtLeast(1)
        val cap = userCap.coerceAtMost(20_000)
        return if (requested == null) cap else requested.coerceIn(1, cap)
    }

    /** [T-binary-preview] Reads at most [limit] bytes fully (read() may return short). */
    internal fun readHead(file: File, limit: Int): ByteArray {
        if (limit <= 0) return ByteArray(0)
        return file.inputStream().use { input ->
            val buf = ByteArray(limit)
            var read = 0
            while (read < limit) {
                val n = input.read(buf, read, limit - read)
                if (n < 0) break
                read += n
            }
            buf.copyOf(read)
        }
    }

    /** UTF-16 BOM → the charset that decodes it; null when not UTF-16. */
    internal fun utf16CharsetOrNull(head: ByteArray): Charset? = when {
        head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> null
    }

    /** Magic-byte sniff for the preview header; null when unrecognized. */
    internal fun guessBinaryType(head: ByteArray): String? {
        fun at(offset: Int, vararg magic: Int): Boolean {
            if (head.size < offset + magic.size) return false
            for (i in magic.indices) if (head[offset + i] != magic[i].toByte()) return false
            return true
        }
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "PNG image"
            at(0, 0xFF, 0xD8, 0xFF) -> "JPEG image"
            at(0, 0x47, 0x49, 0x46, 0x38) -> "GIF image"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "WebP image"
            at(0, 0x42, 0x4D) -> "BMP image"
            at(0, 0x25, 0x50, 0x44, 0x46) -> "PDF document"
            at(0, 0x50, 0x4B, 0x03, 0x04) -> "ZIP archive (docx/apk/jar…)"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7-Zip archive"
            at(0, 0x52, 0x61, 0x72, 0x21) -> "RAR archive"
            at(0, 0x1F, 0x8B) -> "gzip archive"
            at(0, 0x42, 0x5A, 0x68) -> "bzip2 archive"
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> "xz archive"
            at(0, 0x7F, 0x45, 0x4C, 0x46) -> "ELF executable"
            at(0, 0x53, 0x51, 0x4C, 0x69, 0x74, 0x65) -> "SQLite database"
            at(0, 0xCA, 0xFE, 0xBA, 0xBE) -> "Java class"
            at(0, 0x00, 0x61, 0x73, 0x6D) -> "WebAssembly module"
            at(0, 0x4F, 0x67, 0x67, 0x53) -> "Ogg media"
            at(0, 0x66, 0x4C, 0x61, 0x43) -> "FLAC audio"
            at(0, 0x49, 0x44, 0x33) -> "MP3 audio"
            at(4, 0x66, 0x74, 0x79, 0x70) -> "MP4/ISO media"
            at(257, 0x75, 0x73, 0x74, 0x61, 0x72) -> "tar archive"
            else -> null
        }
    }

    /** xxd-style preview: first [limit] bytes, 16 per line, bounded by construction. */
    internal fun hexPreview(head: ByteArray, limit: Int = 256): String {
        val n = minOf(head.size, limit)
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            val lineEnd = minOf(i + 16, n)
            sb.append(String.format(Locale.ROOT, "%08x  ", i))
            for (j in 0 until 16) {
                if (i + j < lineEnd) sb.append(String.format(Locale.ROOT, "%02x ", head[i + j]))
                else sb.append("   ")
                if (j == 7) sb.append(' ')
            }
            sb.append('|')
            for (j in i until lineEnd) {
                val c = head[j].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append('|')
            if (lineEnd < n) sb.append('\n')
            i = lineEnd
        }
        return sb.toString()
    }

    /** [T-binary-preview] What a binary read returns instead of a dead end. */
    internal fun binaryPreview(path: String, size: Long, head: ByteArray): String {
        val type = guessBinaryType(head) ?: "unknown binary"
        val shown = minOf(head.size, 256)
        return "[$path | $size bytes | binary: $type]\n" +
            "Hex preview (first $shown of $size bytes):\n" +
            hexPreview(head) +
            "\nBinary content is not inlined. Images: use the read_image tool. " +
            "More bytes: shell_execute with xxd / file / strings."
    }


    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Binary files return a type guess and a bounded hex preview instead of raw contents; UTF-16 text is decoded.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (capped in Settings → Tool limits; hard cap 80000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            // T-FILEREAD-CAP: hard upper bound on returned content length.
            // Pre-cap, the agent could ask for `max_length=1_000_000` and we
            // would happily inline a 400 KB base64 image into a tool_result —
            // which then renders as a single user-message bubble and locks
            // up Compose's StaticLayout / LineBreaker for tens of seconds
            // (see HangDetector report for session
            // e84882d7-2087-47f8-9300-ff2c897fe0b4: 820 KB partsJson, 43 s
            // hang in nComputeLineBreaks). Cap at 80 KB regardless of
            // requested value; the truncation tail below tells the agent the
            // full file size so it can paginate with offset/lines if needed.
            // iOS mirrors this cap in AIChatViewModel.executeFileRead.
            val userChars = com.openminis.app.data.ToolLimitPrefs.fileReadMaxChars()
            val requestedChars = if (args.has("max_length")) args.optInt("max_length") else userChars
            val maxLength = resolveMaxLength(requestedChars, userChars)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = identityFile(context, path)
                ?: PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult(
                    "Error: Cannot resolve path: $path${guestNamespaceHint(path)}",
                    false,
                    toolTitle = toolTitle,
                )

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path${guestNamespaceHint(path)}", false, toolTitle = toolTitle)
            }

            if (file.isDirectory) {
                return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
            }

            val size = file.length()

            // [T-binary-preview] Binary files used to be a dead end: "binary
            // file — cannot display contents". Now they return a magic-byte
            // type guess plus a bounded xxd-style preview, so the agent can
            // identify the file and pick the next tool (read_image for
            // images, shell_execute xxd/strings for more bytes) instead of
            // falling back to shell for every binary. UTF-16 text starts
            // FF FE / FE FF and is full of NULs — it is TEXT, decoded with
            // the right charset rather than mislabelled binary.
            val head = readHead(file, minOf(8192L, size).toInt())
            val textCharset = utf16CharsetOrNull(head)
            val isBinary = textCharset == null && head.any { it == 0.toByte() }

            if (isBinary) {
                return ToolExecutionResult(
                    binaryPreview(path, size, head),
                    true, toolTitle = toolTitle
                )
            }

            val allLines = file.readLines(textCharset ?: Charsets.UTF_8)
            val totalLines = allLines.size

            val requestedLines = resolveLineLimit(
                if (args.has("lines")) args.optInt("lines") else null,
                com.openminis.app.data.ToolLimitPrefs.fileReadMaxLines(),
            )

            val selectedLines = if (direction == "tail") {
                val count = requestedLines ?: totalLines
                val start = (totalLines - count).coerceAtLeast(0)
                allLines.subList(start, totalLines)
            } else {
                val start = (offset - 1).coerceIn(0, totalLines)
                val end = if (requestedLines != null) {
                    (start + requestedLines).coerceAtMost(totalLines)
                } else {
                    totalLines
                }
                allLines.subList(start, end)
            }

            val showStart = if (direction == "tail") {
                (totalLines - selectedLines.size) + 1
            } else {
                offset
            }
            val showEnd = showStart + selectedLines.size - 1

            var content = selectedLines.joinToString("\n")

            // [T-fileread-truncation-header] The header used to report the line
            // range chosen BEFORE truncation, and said nothing about having
            // truncated at all — only the body gained a trailing
            // "... (truncated)". So a cut-off read still announced
            // "showing 1-1324 of 1324", which the agent took as the whole file
            // and never paged on.
            //
            // Recompute the range that actually survived and hand back the
            // offset to resume from. Confined to the truncating branch; a read
            // that fits is byte-identical to before.
            var effectiveStart = showStart
            var effectiveEnd = showEnd
            var nextOffset: Int? = null
            var wasTruncated = false
            if (content.length > maxLength) {
                wasTruncated = true
                if (direction == "tail") {
                    // tail asks for the END of the file; take() returned the
                    // start of the tail window instead — the opposite.
                    content = content.takeLast(maxLength)
                    // Drop a leading partial line so the first line is whole.
                    val firstNewline = content.indexOf('\n')
                    if (firstNewline in 0 until content.length - 1) {
                        content = content.substring(firstNewline + 1)
                    }
                    effectiveStart = effectiveEnd - content.count { it == '\n' }
                    // No next_offset for tail: paging forward from the end of
                    // the file is meaningless.
                } else {
                    content = content.take(maxLength)
                    // Back off to the last complete line, so the next page does
                    // not re-read or split a line.
                    val lastNewline = content.lastIndexOf('\n')
                    if (lastNewline > 0) content = content.substring(0, lastNewline)
                    effectiveEnd = showStart + content.count { it == '\n' }
                    if (effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
                }
            }

            var header = "[$path | $size bytes | $totalLines lines | " +
                "showing $effectiveStart-$effectiveEnd of $totalLines"
            if (wasTruncated) {
                header += " | truncated at $maxLength chars"
                // Named to match the tool's own `offset` parameter so the model
                // can copy it straight into the next call.
                header += if (nextOffset != null) ", next_offset=$nextOffset"
                          else ", retry with a smaller lines value"
            }
            header += "]"
            ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }
}

internal fun identityFile(context: android.content.Context, path: String): java.io.File? {
    val name = when (path.trim()) {
        "/.arch" -> ".arch"
        "/.distro" -> ".distro"
        else -> return null
    }
    val root = com.openminis.app.sandbox.RootfsManager.getInstance(context).rootfsDir
    val file = java.io.File(root, name)
    return file.takeIf { it.isFile }
}

internal fun guestNamespaceHint(path: String): String {
    val p = path.trim()
    val guestOnly = p == "/" || p == "/proc" || p.startsWith("/proc/") ||
        p == "/sys" || p.startsWith("/sys/") ||
        p == "/dev" || p.startsWith("/dev/") ||
        p == "/etc" || p.startsWith("/etc/") ||
        p == "/.arch" || p == "/.distro"
    if (!guestOnly) return ""
    return ". App-process file tools cannot see this guest path. Use shell_execute, for example `cat $p`."
}
